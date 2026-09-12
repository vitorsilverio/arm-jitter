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

/// B19.11c/B19.11d — `FDOT_hb_v`/`FDOT_hb_vi`/`FDOT_sb_v`/`FDOT_sb_vi` no executor: a ponte
/// registrador↔núcleo (núcleo já testado exaustivamente em {@code AdvSimdLanesFp8DotProductTest})
/// E o consumo de verdade de `FPMR` — mesma disciplina de
/// {@code Ir64VectorFpArithmeticExecutorFp8FusedMultiplyAddTest} (B19.11b), mas com `Q` afetando o
/// número real de lanes processadas (ao contrário de `FMLAL_hb`, que sempre processa os 128 bits
/// inteiros). O executor (`Ir64VectorFpArithmeticExecutor#executeFp8DotProduct`) já nasceu
/// genérico por `wideDestination` na B19.11c — a B19.11d só precisou decodificar, sem tocar
/// aqui; os testes `fdotSb*` abaixo cobrem esse caminho que já existia mas não tinha teste.
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

    // ── FDOT_sb_v (B19.11d) ──────────────────────────────────────────────────────────────────────

    @Test
    void fdotSbVSumsFourProductsPerLaneInSinglePrecision() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        // !Q: 2 lanes (`.2s`), cada uma soma 4 produtos: (1*1)+(2*2)+(3*3)+(4*4) = 30.
        for (int lane = 0; lane < 2; lane++) {
            for (int k = 0; k < 4; k++) {
                fp.setElement(1, 4 * lane + k, 0, fp8(1.0f + k));
                fp.setElement(2, 4 * lane + k, 0, fp8(1.0f + k));
            }
            fp.setElement(0, lane, 2, AdvSimdLanes.floatBits(0.0f));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProduct(true, false, 0, 1, 2));
        for (int lane = 0; lane < 2; lane++) {
            assertEquals(30.0f, Float.intBitsToFloat((int) fp.element(0, lane, 2)));
        }
        assertEquals(0L, fp.high64(0));
    }

    @Test
    void fdotSbAndFdotHbProduceDifferentDestinationWidthsFromSameSources() {
        // Aceite da B19.11d: `FDOT_sb`×`FDOT_hb` não podem ser confundidas — mesma fonte, resultado
        // em precisão simples (`FDOT_sb`) contra meia precisão (`FDOT_hb`), usando só os 2 primeiros
        // elementos FP8 nos dois casos (o núcleo de `FDOT_hb` só consome os 2 primeiros bytes).
        Aarch64Core hbCore = newCore();
        Aarch64FpRegisters hbFp = hbCore.fp();
        writeFpmr(hbCore, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        hbFp.setElement(1, 0, 0, fp8(3.0f));
        hbFp.setElement(1, 1, 0, fp8(2.0f));
        hbFp.setElement(2, 0, 0, fp8(3.0f));
        hbFp.setElement(2, 1, 0, fp8(2.0f));
        hbFp.setElement(0, 0, 1, AdvSimdLanes.halfBits(0.0f));
        EXECUTOR.executeOp(hbCore, new Ir64Op.VectorFp8DotProduct(false, false, 0, 1, 2));
        assertEquals(13.0f, AdvSimdLanes.halfToFloat(hbFp.element(0, 0, 1))); // (3*3)+(2*2)=13

        Aarch64Core sbCore = newCore();
        Aarch64FpRegisters sbFp = sbCore.fp();
        writeFpmr(sbCore, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        sbFp.setElement(1, 0, 0, fp8(3.0f));
        sbFp.setElement(1, 1, 0, fp8(2.0f));
        sbFp.setElement(1, 2, 0, fp8(0.0f));
        sbFp.setElement(1, 3, 0, fp8(0.0f));
        sbFp.setElement(2, 0, 0, fp8(3.0f));
        sbFp.setElement(2, 1, 0, fp8(2.0f));
        sbFp.setElement(2, 2, 0, fp8(0.0f));
        sbFp.setElement(2, 3, 0, fp8(0.0f));
        sbFp.setElement(0, 0, 2, AdvSimdLanes.floatBits(0.0f));
        EXECUTOR.executeOp(sbCore, new Ir64Op.VectorFp8DotProduct(true, false, 0, 1, 2));
        assertEquals(13.0f, Float.intBitsToFloat((int) sbFp.element(0, 0, 2))); // mesmo valor lógico
        // mas em larguras/`esz` diferentes — meia precisão (`esz=1`) × precisão simples (`esz=2`).
    }

    // ── FDOT_sb_vi (B19.11d) ─────────────────────────────────────────────────────────────────────

    @Test
    void fdotSbViBroadcastsSingleRmGroup() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3 | FPMR_F8S2_E4M3);
        for (int k = 0; k < 4; k++) {
            fp.setElement(1, k, 0, fp8(1.0f));
            fp.setElement(3, 4 + k, 0, fp8(2.0f)); // grupo no índice 1
        }
        fp.setElement(0, 0, 2, AdvSimdLanes.floatBits(0.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFp8DotProductByElement(true, false, 0, 1, 3, 1));
        assertEquals(8.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2))); // (1*2)*4 = 8
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
