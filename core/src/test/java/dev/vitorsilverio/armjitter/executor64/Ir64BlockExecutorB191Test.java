package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.1 — semântica RMW dos atômicos `FEAT_LSE` direto no executor (interpretador = oráculo, G1).
/// `LDAPR` não tem executor próprio (reaproveita {@link Ir64Op.Load64}, já coberto).
class Ir64BlockExecutorB191Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static Ir64Op.AtomicMemoryOp op(int rs, int rt, int rn, Ir64MemSize size, Ir64AtomicOp o) {
        return new Ir64Op.AtomicMemoryOp(rs, rt, rn, size, o, false, false);
    }

    // ── operações bit a bit ─────────────────────────────────────────────────────────────────────

    @Test
    void ldaddWordReturnsOldAndWritesSum() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);              // Rn
        core.memory().write32(0x40L, 0x0000_0005);
        core.setX(0, 0x10L);             // Rs
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.WORD, Ir64AtomicOp.ADD));
        assertEquals(0x5L, core.x(1), "Rt recebe o valor antigo, zero-estendido");
        assertEquals(0x15, core.memory().read32(0x40L));
    }

    @Test
    void ldaddDoublewordWraps() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write64(0x40L, 0xFFFF_FFFF_FFFF_FFFFL);
        core.setX(0, 2L);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.DOUBLEWORD, Ir64AtomicOp.ADD));
        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, core.x(1));
        assertEquals(1L, core.memory().read64(0x40L));
    }

    @Test
    void ldclrLdeorLdset() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.setX(0, 0x0FL);
        core.memory().write32(0x40L, 0xFF);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.WORD, Ir64AtomicOp.CLR));
        assertEquals(0xF0, core.memory().read32(0x40L), "old & ~Rs");

        core.memory().write32(0x40L, 0xAA);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.WORD, Ir64AtomicOp.EOR));
        assertEquals(0xA5, core.memory().read32(0x40L), "old ^ Rs");

        core.memory().write32(0x40L, 0x80);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.WORD, Ir64AtomicOp.SET));
        assertEquals(0x8F, core.memory().read32(0x40L), "old | Rs");
    }

    @Test
    void swpReplacesAndReturnsOld() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write32(0x40L, 0xDEAD);
        core.setX(0, 0xBEEFL);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.WORD, Ir64AtomicOp.SWP));
        assertEquals(0xDEADL, core.x(1));
        assertEquals(0xBEEF, core.memory().read32(0x40L));
    }

    // ── sinal ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void ldsmaxSignedOnByte() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write8(0x40L, 0xFF);   // -1 com sinal em 8 bits
        core.setX(0, 0x01L);               // +1
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.BYTE, Ir64AtomicOp.SMAX));
        assertEquals(0xFFL, core.x(1), "old lido zero-estendido");
        assertEquals(0x01, core.memory().read8(0x40L) & 0xFF, "max(-1,+1) = +1");
    }

    @Test
    void ldsminSignedOnByte() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write8(0x40L, 0xFF);   // -1
        core.setX(0, 0x01L);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.BYTE, Ir64AtomicOp.SMIN));
        assertEquals(0xFF, core.memory().read8(0x40L) & 0xFF, "min(-1,+1) = -1");
    }

    @Test
    void ldumaxUnsignedHighBitSet() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write8(0x40L, 0xFF);   // 255 sem sinal
        core.setX(0, 0x01L);
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.BYTE, Ir64AtomicOp.UMAX));
        assertEquals(0xFF, core.memory().read8(0x40L) & 0xFF, "umax(255,1) = 255");
        EXECUTOR.executeOp(core, op(0, 1, 2, Ir64MemSize.BYTE, Ir64AtomicOp.UMIN));
        assertEquals(0x01, core.memory().read8(0x40L) & 0xFF, "umin(255,1) = 1");
    }

    // ── aliases / registradores especiais ───────────────────────────────────────────────────────

    @Test
    void rtXzrKeepsRegister31ZeroButStillWrites() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write32(0x40L, 7);
        core.setX(0, 3L);
        EXECUTOR.executeOp(core, op(0, 31, 2, Ir64MemSize.WORD, Ir64AtomicOp.ADD));
        assertEquals(0L, core.x(31), "XZR permanece 0 (alias ST<op>)");
        assertEquals(10, core.memory().read32(0x40L), "RMW acontece igual");
    }

    @Test
    void rsXzrOperatesWithZero() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write32(0x40L, 0x1234);
        EXECUTOR.executeOp(core, op(31, 1, 2, Ir64MemSize.WORD, Ir64AtomicOp.ADD));
        assertEquals(0x1234L, core.x(1), "LDADD XZR = leitura atômica pura");
        assertEquals(0x1234, core.memory().read32(0x40L), "reescreve o mesmo valor");
    }

    @Test
    void rnSpUsedAsBase() {
        Aarch64Core core = newCore();
        core.setSp(0x80L);
        core.memory().write64(0x80L, 100L);
        core.setX(0, 1L);
        EXECUTOR.executeOp(core, op(0, 1, 31, Ir64MemSize.DOUBLEWORD, Ir64AtomicOp.ADD));
        assertEquals(101L, core.memory().read64(0x80L));
    }

    // ── monitor de exclusividade ────────────────────────────────────────────────────────────────

    @Test
    void atomicOpDropsPendingExclusiveReservation() {
        Aarch64Core core = newCore();
        core.setX(2, 0x40L);
        core.memory().write32(0x40L, 0);
        EXECUTOR.executeOp(core, new Ir64Op.LoadExclusive(1, 2, Ir64MemSize.WORD, false));
        EXECUTOR.executeOp(core, op(0, 3, 2, Ir64MemSize.WORD, Ir64AtomicOp.ADD));
        EXECUTOR.executeOp(core, new Ir64Op.StoreExclusive(4, 5, 2, Ir64MemSize.WORD, false));
        assertEquals(1L, core.x(4), "reserva derrubada pelo notifyOrdinaryWrite do atomic op");
    }
}
