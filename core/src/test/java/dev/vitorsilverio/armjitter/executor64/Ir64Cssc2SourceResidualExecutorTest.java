package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64MinMaxOp;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.21 — semântica de `CTZ` e `SMAX`/`SMIN`/`UMAX`/`UMIN` de registrador geral (interpretador
/// = oráculo, G1).
class Ir64Cssc2SourceResidualExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── CTZ ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ctzCountsTrailingZeros() {
        Aarch64Core core = newCore();
        core.setX(1, 0b1000L);
        EXECUTOR.executeOp(core, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CTZ, 0, 1, true));
        assertEquals(3, core.x(0));
    }

    @Test
    void ctzOfZeroReturnsRegisterWidth() {
        // CTZ(0) = largura do registrador (mesma convenção de CLZ(0), confirmada contra o manual).
        Aarch64Core wide = newCore();
        wide.setX(1, 0L);
        EXECUTOR.executeOp(wide, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CTZ, 0, 1, true));
        assertEquals(64, wide.x(0));

        Aarch64Core narrow = newCore();
        narrow.setX(1, 0L);
        EXECUTOR.executeOp(narrow, new Ir64Op.DataProcessing1Source(Ir64OneSourceOp.CTZ, 0, 1, false));
        assertEquals(32, narrow.x(0));
    }

    // ── SMAX/SMIN/UMAX/UMIN ─────────────────────────────────────────────────────────────────────

    @Test
    void smaxAndUmaxDifferWithNegativeOperand() {
        // -1 (0xFFFFFFFF...) é o MAIOR valor sem sinal e o MENOR valor com sinal — prova de que
        // o sinal é respeitado/ignorado corretamente em cada operação.
        Aarch64Core core = newCore();
        core.setX(1, -1L);
        core.setX(2, 5L);

        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.SMAX, 0, 1, 2, true));
        assertEquals(5L, core.x(0), "SMAX: 5 > -1 com sinal");

        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.UMAX, 0, 1, 2, true));
        assertEquals(-1L, core.x(0), "UMAX: -1 (0xFFFF...) > 5 sem sinal");
    }

    @Test
    void sminAndUminDifferWithNegativeOperand() {
        Aarch64Core core = newCore();
        core.setX(1, -1L);
        core.setX(2, 5L);

        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.SMIN, 0, 1, 2, true));
        assertEquals(-1L, core.x(0), "SMIN: -1 < 5 com sinal");

        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.UMIN, 0, 1, 2, true));
        assertEquals(5L, core.x(0), "UMIN: 5 < -1 (0xFFFF...) sem sinal");
    }

    @Test
    void narrowFormComparesOnlyLow32BitsSignExtendedWhenSigned() {
        Aarch64Core core = newCore();
        core.setX(1, 0xFFFF_FFFFL); // -1 em W, mas zero-estendido no core
        core.setX(2, 5L);

        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.SMAX, 0, 1, 2, false));
        assertEquals(5L, core.x(0), "SMAX em W: -1 (sign-extend) < 5");

        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.UMAX, 0, 1, 2, false));
        assertEquals(0xFFFF_FFFFL, core.x(0), "UMAX em W: 0xFFFFFFFF > 5 sem sinal");
    }

    @Test
    void resultIsZeroExtendedInNarrowForm() {
        Aarch64Core core = newCore();
        core.setX(0, 0xFFFF_FFFF_0000_0000L); // topo sujo antes da instrução
        core.setX(1, 3L);
        core.setX(2, 7L);
        EXECUTOR.executeOp(core, new Ir64Op.MinMaxGeneral(Ir64MinMaxOp.UMAX, 0, 1, 2, false));
        assertEquals(7L, core.x(0), "W: topo alto deve ser zerado, não preservado");
    }
}
