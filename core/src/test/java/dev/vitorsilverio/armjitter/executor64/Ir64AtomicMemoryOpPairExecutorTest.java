package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.25 — semântica RMW de 128 bits de `LDCLRP`/`LDSETP`/`SWPP` (`FEAT_LSE128`) direto no
/// executor (interpretador = oráculo, G1). Ver javadoc de {@link Ir64Op.AtomicMemoryOpPair}: ao
/// contrário de {@link Ir64Op.AtomicMemoryOp} (`Rs` separado de `Rt`), aqui o PRÓPRIO par
/// `(Rt,Rt2)` é o operando de entrada E recebe o valor antigo lido — semântica in-place.
class Ir64AtomicMemoryOpPairExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int RN = 2;
    private static final int RT = 0;
    private static final int RT2 = 1;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static Ir64Op.AtomicMemoryOpPair op(Ir64AtomicOp operation) {
        return new Ir64Op.AtomicMemoryOpPair(RT, RT2, RN, operation, false, false);
    }

    @Test
    void ldclrpClearsBitsAcrossTheFullPairAndReturnsOldValue() {
        Aarch64Core core = newCore();
        core.setX(RN, 0x40L);
        core.memory().write64(0x40L, 0xFFFF_FFFF_FFFF_FFFFL);        // lo
        core.memory().write64(0x48L, 0xFFFF_FFFF_FFFF_FFFFL);        // hi
        core.setX(RT, 0x0F0F_0F0F_0F0F_0F0FL);   // operando lo
        core.setX(RT2, 0x00FF_00FF_00FF_00FFL);  // operando hi

        EXECUTOR.executeOp(core, op(Ir64AtomicOp.CLR));

        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, core.x(RT), "Rt recebe o par ANTIGO (lo)");
        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, core.x(RT2), "Rt2 recebe o par ANTIGO (hi)");
        assertEquals(~0x0F0F_0F0F_0F0F_0F0FL & 0xFFFF_FFFF_FFFF_FFFFL, core.memory().read64(0x40L),
                "old & ~operando, lo");
        assertEquals(~0x00FF_00FF_00FF_00FFL & 0xFFFF_FFFF_FFFF_FFFFL, core.memory().read64(0x48L),
                "old & ~operando, hi — confere que a limpeza cruza a fronteira dos 64 bits (cada "
                        + "metade usa o operando da PRÓPRIA metade, não uma máscara de 128 bits só)");
    }

    @Test
    void ldsetpSetsBitsAcrossThePair() {
        Aarch64Core core = newCore();
        core.setX(RN, 0x40L);
        core.memory().write64(0x40L, 0x0L);
        core.memory().write64(0x48L, 0x0L);
        core.setX(RT, 0x1L);
        core.setX(RT2, 0x2L);

        EXECUTOR.executeOp(core, op(Ir64AtomicOp.SET));

        assertEquals(0L, core.x(RT), "valor antigo era zero");
        assertEquals(0L, core.x(RT2));
        assertEquals(0x1L, core.memory().read64(0x40L));
        assertEquals(0x2L, core.memory().read64(0x48L));
    }

    @Test
    void swppExchangesThePairAndReturnsOldValue() {
        Aarch64Core core = newCore();
        core.setX(RN, 0x40L);
        core.memory().write64(0x40L, 0xDEAD_BEEFL);
        core.memory().write64(0x48L, 0xCAFE_BABEL);
        core.setX(RT, 0x1111L);
        core.setX(RT2, 0x2222L);

        EXECUTOR.executeOp(core, op(Ir64AtomicOp.SWP));

        assertEquals(0xDEAD_BEEFL, core.x(RT));
        assertEquals(0xCAFE_BABEL, core.x(RT2));
        assertEquals(0x1111L, core.memory().read64(0x40L));
        assertEquals(0x2222L, core.memory().read64(0x48L));
    }

    @Test
    void baseRegisterSpIsUsedAsAddress() {
        Aarch64Core core = newCore();
        core.setSp(0x80L);
        core.memory().write64(0x80L, 5L);
        core.memory().write64(0x88L, 7L);
        core.setX(RT, 1L);
        core.setX(RT2, 2L);
        EXECUTOR.executeOp(core, new Ir64Op.AtomicMemoryOpPair(RT, RT2, 31, Ir64AtomicOp.SET, false, false));
        assertEquals(5L, core.memory().read64(0x80L));
        assertEquals(7L, core.memory().read64(0x88L));
    }

    @Test
    void storeIsUnconditionalAndDropsPendingExclusiveReservationOnBothHalves() {
        Aarch64Core core = newCore();
        core.setX(RN, 0x40L);
        core.memory().write64(0x40L, 0L);
        core.memory().write64(0x48L, 0L);

        // Reserva em [0x40] (lo) — o RMW de 128 bits escreve os dois QWORDS incondicionalmente,
        // então a reserva pendente tem que cair, mesma disciplina de toda a família LSE (B19.1).
        EXECUTOR.executeOp(core, new Ir64Op.LoadExclusive(3, RN, Ir64MemSize.DOUBLEWORD, false));
        EXECUTOR.executeOp(core, op(Ir64AtomicOp.SET));
        EXECUTOR.executeOp(core, new Ir64Op.StoreExclusive(4, 5, RN, Ir64MemSize.DOUBLEWORD, false));
        assertEquals(1L, core.x(4), "reserva em [Rn] derrubada pelo notifyOrdinaryWrite do RMW de 128 bits");

        // Mesma checagem para a metade alta ([Rn+8]) — precisa do PRÓPRIO notifyOrdinaryWrite,
        // não só o da metade baixa (achado: fácil esquecer o segundo `notifyOrdinaryWrite`).
        core.setX(6, 0x48L);
        EXECUTOR.executeOp(core, new Ir64Op.LoadExclusive(7, 6, Ir64MemSize.DOUBLEWORD, false));
        EXECUTOR.executeOp(core, op(Ir64AtomicOp.SET));
        EXECUTOR.executeOp(core, new Ir64Op.StoreExclusive(8, 9, 6, Ir64MemSize.DOUBLEWORD, false));
        assertEquals(1L, core.x(8), "reserva em [Rn+8] também derrubada");
    }
}
