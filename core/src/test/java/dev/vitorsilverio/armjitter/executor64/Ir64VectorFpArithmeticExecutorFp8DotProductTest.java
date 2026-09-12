package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// B19.11c — `FDOT_hb_v`/`FDOT_hb_vi` no executor: a ponte registrador↔núcleo (núcleo já testado
/// exaustivamente em {@code AdvSimdLanesFp8DotProductTest}) E o consumo de verdade de `FPMR` —
/// mesma disciplina de {@code Ir64VectorFpArithmeticExecutorFp8FusedMultiplyAddTest} (B19.11b),
/// mas com `Q` afetando o número real de lanes processadas (ao contrário de `FMLAL_hb`, que
/// sempre processa os 128 bits inteiros).
class Ir64VectorFpArithmeticExecutorFp8DotProductTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final boolean E4M3 = true;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void writeFpmr(Aarch64Core core, long value) {
        core.setX(0, value);
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPMR, 0));
    }

    private static final long FPMR_F8S1_E4M3 = 0b001L;
    private static final long FPMR_F8S2_E4M3 = 0b001L << 3;
    private static final int FPMR_LSCALE_SHIFT = 16;
    private static final int FPMR_OSM_BIT = 14;

    private static int fp8(float value) {
        return AdvSimdLanes.floatToFp8(value, E4M3, false);
    }

    // ── FDOT_hb_v ────────────────────────────────────────────────────────────────────────────────

    @Test
    void fdotHbVSumsTwoProductsPerLaneNotQuad() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        // 4 lanes (!Q): cada uma soma (n0*m0)+(n1*m1) = (1*1)+(2*2) = 5, para todas as lanes.
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(1, 2 * lane, 0, fp8(1.0f));
            fp.setElement(1, 2 * lane + 1, 0, fp8(2.0f));
            fp.setElement(2, 2 * lane, 0, fp8(1.0f));
            fp.setElement(2, 2 * lane + 1, 0, fp8(2.0f));
            fp.setElement(0, lane, 1, AdvSimdLanes.halfBits(0.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProduct(false, false, 0, 1, 2));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(5.0f, AdvSimdLanes.halfToFloat(fp.element(0, lane, 1)));
        }
        // `!Q`: bits altos de Rd zerados (disciplina "destructive" padrão).
        assertEquals(0L, fp.high64(0));
    }

    @Test
    void fdotHbVQuadProcessesEightLanes() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int lane = 0; lane < 8; lane++) {
            fp.setElement(1, 2 * lane, 0, fp8(1.0f));
            fp.setElement(1, 2 * lane + 1, 0, fp8(1.0f));
            fp.setElement(2, 2 * lane, 0, fp8(1.0f));
            fp.setElement(2, 2 * lane + 1, 0, fp8(1.0f));
            fp.setElement(0, lane, 1, AdvSimdLanes.halfBits(0.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProduct(false, true, 0, 1, 2));
        for (int lane = 0; lane < 8; lane++) {
            assertEquals(2.0f, AdvSimdLanes.halfToFloat(fp.element(0, lane, 1)));
        }
    }

    @Test
    void fdotHbVUsesLscaleLow4BitsOnly() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // LSCALE = 0x50 (bits[6:4]=101): os 4 bits BAIXOS consumidos por FDOT_hb são 0x0 -- prova
        // que só `[3:0]` entra, MESMA máscara de `FMLAL_hb` (B19.11b).
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3 | (0x50L << FPMR_LSCALE_SHIFT));
        fp.setElement(1, 0, 0, fp8(4.0f));
        fp.setElement(1, 1, 0, fp8(0.0f));
        fp.setElement(2, 0, 0, fp8(1.0f));
        fp.setElement(2, 1, 0, fp8(0.0f));
        fp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProduct(false, false, 0, 1, 2));
        assertEquals(4.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    @Test
    void osmSaturatesOverflowToMaxNormalInsteadOfInfinity() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3 | (1L << FPMR_OSM_BIT));
        fp.setElement(1, 0, 0, fp8(448.0f));
        fp.setElement(1, 1, 0, fp8(448.0f));
        fp.setElement(2, 0, 0, fp8(448.0f));
        fp.setElement(2, 1, 0, fp8(448.0f));
        fp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProduct(false, false, 0, 1, 2));
        assertEquals(65504f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    // ── Aliasing (E10) ───────────────────────────────────────────────────────────────────────────

    @Test
    void rdSameAsRnIsSafe() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        fp.setElement(0, 0, 0, fp8(1.0f));
        fp.setElement(0, 1, 0, fp8(2.0f));
        fp.setElement(2, 0, 0, fp8(1.0f));
        fp.setElement(2, 1, 0, fp8(1.0f));
        // `Rd`==`Rn`: o acumulador INICIAL da lane 0 é a reinterpretação em `binary16` dos MESMOS
        // bytes FP8 que servem de fonte — lido ANTES da operação, já que a memória é compartilhada
        // (prova E10: sem este `double read` a asserção abaixo teria que assumir `acc=0`, errado
        // aqui por construção).
        double accBefore = AdvSimdLanes.halfToFloat(fp.element(0, 0, 1));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProduct(false, false, 0, 0, 2));
        float expected = (float) (1.0 * 1.0 + 2.0 * 1.0 + accBefore);
        assertEquals(expected, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }

    // ── FDOT_hb_vi ───────────────────────────────────────────────────────────────────────────────

    @Test
    void fdotHbViBroadcastsSingleRmGroup() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(1, 2 * lane, 0, fp8(1.0f + lane));
            fp.setElement(1, 2 * lane + 1, 0, fp8(0.0f));
            fp.setElement(0, lane, 1, AdvSimdLanes.halfBits(0.0f));
        }
        // Rm: grupo no índice 5 = (10.0, 0.0); resto do registrador irrelevante.
        fp.setElement(3, 10, 0, fp8(10.0f));
        fp.setElement(3, 11, 0, fp8(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProductByElement(false, false, 0, 1, 3, 5));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(10.0f * (1.0f + lane), AdvSimdLanes.halfToFloat(fp.element(0, lane, 1)));
        }
    }

    @Test
    void fdotHbViIndexSelectsDifferentGroup() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        fp.setElement(1, 0, 0, fp8(1.0f));
        fp.setElement(1, 1, 0, fp8(0.0f));
        fp.setElement(3, 0, 0, fp8(3.0f));
        fp.setElement(3, 1, 0, fp8(0.0f));
        fp.setElement(3, 2, 0, fp8(9.0f));
        fp.setElement(3, 3, 0, fp8(0.0f));
        fp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProductByElement(false, false, 0, 1, 3, 0));
        assertEquals(3.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));

        fp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProductByElement(false, false, 0, 1, 3, 1));
        assertEquals(9.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
        assertNotEquals(3.0f, AdvSimdLanes.halfToFloat(fp.element(0, 0, 1)));
    }
}
