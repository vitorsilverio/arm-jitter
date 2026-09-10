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

/// B19.11 — as 6 conversões de `FEAT_FP8` no executor: a ponte registrador↔núcleo (núcleo já
/// testado exaustivamente em {@code AdvSimdLanesFp8Test}) E o consumo de verdade de `FPMR`
/// (formato/escala/`OSC`), a diferença central desta task em relação ao resto do executor (que
/// nunca lê um registrador de CONTROLE para decidir semântica).
class Ir64VectorFpArithmeticExecutorFp8Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static void writeFpmr(Aarch64Core core, long value) {
        core.setX(0, value);
        EXECUTOR.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.FPMR, 0));
    }

    private static final long FPMR_F8D_E4M3 = 0b001L << 6;
    private static final long FPMR_F8S1_E4M3 = 0b001L;
    private static final long FPMR_F8S2_E4M3 = 0b001L << 3;

    // ── FCVTN_bs (destino de FPMR.F8D, escala de FPMR.NSCALE) ──────────────────────────────────────

    @Test
    void fcvtnBsUsesDestinationFormatFromFpmr() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, 0L); // F8D = E5M2 (0b000)
        fp.setElement(1, 0, 2, AdvSimdLanes.floatBits(1.5f));
        fp.setElement(2, 0, 2, AdvSimdLanes.floatBits(-1.5f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, false, 0, 1, 2));
        int e5m2Bits = (int) fp.element(0, 0, 0);
        assertEquals(AdvSimdLanes.floatToFp8(1.5f, false, false), e5m2Bits);

        writeFpmr(core, FPMR_F8D_E4M3); // F8D = E4M3
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, false, 0, 1, 2));
        int e4m3Bits = (int) fp.element(0, 0, 0);
        assertEquals(AdvSimdLanes.floatToFp8(1.5f, true, false), e4m3Bits);
        assertNotEquals(e5m2Bits, e4m3Bits, "mesmo valor de entrada, formato de destino diferente");
    }

    @Test
    void fcvtnBsInterleavesRnLowAndRmHighAndClearsUpperWhenLowerHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, 0L);
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujar Vd antes
        for (int i = 0; i < 4; i++) {
            fp.setElement(1, i, 2, AdvSimdLanes.floatBits(1.0f + i));
            fp.setElement(2, i, 2, AdvSimdLanes.floatBits(5.0f + i));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, false, 0, 1, 2));
        for (int i = 0; i < 4; i++) {
            assertEquals(AdvSimdLanes.floatToFp8(1.0f + i, false, false), (int) fp.element(0, i, 0));
            assertEquals(AdvSimdLanes.floatToFp8(5.0f + i, false, false), (int) fp.element(0, 4 + i, 0));
        }
        assertEquals(0L, fp.word(1), "q=false zera a metade alta de Vd (FCVTN)");
    }

    @Test
    void fcvtn2BsWritesUpperHalfAndPreservesLower() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, 0L);
        fp.setD(0, 0x1111_1111_2222_2222L); // metade baixa pré-existente, deve sobreviver
        fp.setElement(1, 0, 2, AdvSimdLanes.floatBits(3.0f));
        fp.setElement(2, 0, 2, AdvSimdLanes.floatBits(7.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, true, 0, 1, 2));
        assertEquals(0x1111_1111_2222_2222L, fp.low64(0), "FCVTN2 preserva a metade baixa");
        assertEquals(AdvSimdLanes.floatToFp8(3.0f, false, false), (int) fp.element(0, 8, 0));
        assertEquals(AdvSimdLanes.floatToFp8(7.0f, false, false), (int) fp.element(0, 12, 0));
    }

    @Test
    void fcvtnBhDoublesElementCountWithQInsteadOfSelectingHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, 0L);
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 1, AdvSimdLanes.halfBits(1.0f + i));
            fp.setElement(2, i, 1, AdvSimdLanes.halfBits(9.0f + i));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(true, true, 0, 1, 2));
        for (int i = 0; i < 8; i++) {
            assertEquals(AdvSimdLanes.floatToFp8(1.0f + i, false, false), (int) fp.element(0, i, 0));
            assertEquals(AdvSimdLanes.floatToFp8(9.0f + i, false, false), (int) fp.element(0, 8 + i, 0));
        }
    }

    @Test
    void fcvtnScalesByNscaleBeforeConverting() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, 1L << 24); // NSCALE = +1 -> escala por 2^1
        fp.setElement(1, 0, 2, AdvSimdLanes.floatBits(1.0f));
        fp.setElement(2, 0, 2, AdvSimdLanes.floatBits(0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, false, 0, 1, 2));
        assertEquals(AdvSimdLanes.floatToFp8(2.0f, false, false), (int) fp.element(0, 0, 0),
                "1.0 escalado por NSCALE=+1 vira 2.0 antes de converter");
    }

    @Test
    void fcvtnOverflowSaturatesOrGeneratesNaturalResultPerOsc() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8D_E4M3); // sem OSC (bit15=0): overflow em E4M3 -> NaN
        fp.setElement(1, 0, 2, AdvSimdLanes.floatBits(1_000_000f));
        fp.setElement(2, 0, 2, AdvSimdLanes.floatBits(0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, false, 0, 1, 2));
        assertEquals(0b0_1111_111, (int) fp.element(0, 0, 0));

        writeFpmr(core, FPMR_F8D_E4M3 | (1L << 15)); // OSC=1: satura no máximo normal
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertToFp8(false, false, 0, 1, 2));
        assertEquals(0b0_1111_110, (int) fp.element(0, 0, 0));
    }

    // ── F1CVTL/F2CVTL/BF1CVTL/BF2CVTL (origem de FPMR.F8S1/F8S2, escala de LSCALE/LSCALE2) ─────────

    @Test
    void f1cvtlUsesFirstStreamFormatAndScale() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3); // F8S1 = E4M3, LSCALE = 0
        int bits = 0b0_1111_110; // E4M3: 448.0
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, bits);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(false, false, false, 0, 1));
        assertEquals(AdvSimdLanes.halfBits(448.0f), fp.element(0, 0, 1));
    }

    @Test
    void f2cvtlUsesSecondStreamIndependentlyOfFirst() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // F8S1 = E5M2 (0), F8S2 = E4M3 (0b001<<3) -- mesmo padrão de bits, formatos diferentes.
        writeFpmr(core, FPMR_F8S2_E4M3);
        int bits = 0b0_1111_110;
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, bits);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(false, false, false, 0, 1));
        long asFirstStream = fp.element(0, 0, 1); // lê F8S1 = E5M2
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(true, false, false, 0, 1));
        long asSecondStream = fp.element(0, 0, 1); // lê F8S2 = E4M3
        assertNotEquals(asFirstStream, asSecondStream, "F1CVTL×F2CVTL não podem ler o mesmo formato aqui");
        assertEquals(AdvSimdLanes.halfBits(AdvSimdLanes.fp8ToFloat(bits, true)), asSecondStream);
    }

    @Test
    void f1cvtlAndF2cvtlUseIndependentDownscale() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // LSCALE = 1 (downscale por 2^-1), LSCALE2 = 2 (downscale por 2^-2), F8S1=F8S2=E5M2.
        writeFpmr(core, (0b0010L << 32) | (0b0001L << 16));
        int bits = 0b0_01111_00; // E5M2: 1.0
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, bits);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(false, false, false, 0, 1));
        assertEquals(AdvSimdLanes.halfBits(0.5f), fp.element(0, 0, 1), "F1CVTL: 1.0 * 2^-1");
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(true, false, false, 0, 1));
        assertEquals(AdvSimdLanes.halfBits(0.25f), fp.element(0, 0, 1), "F2CVTL: 1.0 * 2^-2");
    }

    @Test
    void bf1cvtlProducesBfloat16NotBinary16() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S1_E4M3); // F8S1 = E4M3
        int bits = 0b0_1111_110; // E4M3: 448.0 -- fora do alcance normal de bfloat16? não, cabe.
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, bits);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(false, true, false, 0, 1));
        long produced = fp.element(0, 0, 1);
        assertEquals(AdvSimdLanes.bf16Bits(448.0f), produced);
        assertNotEquals(AdvSimdLanes.halfBits(448.0f) & 0xFFFFL, produced & 0xFFFFL,
                "bfloat16 e binary16 têm layouts diferentes para o mesmo valor");
    }

    @Test
    void bf2cvtlProducesBfloat16FromSecondStream() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, FPMR_F8S2_E4M3);
        int bits = 0b0_1111_110;
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, bits);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(true, true, false, 0, 1));
        assertEquals(AdvSimdLanes.bf16Bits(448.0f), fp.element(0, 0, 1));
    }

    @Test
    void widenSelectsUpperHalfOfRnWithQAndFillsFullRegister() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        writeFpmr(core, 0L); // F8S1 = E5M2
        int lowBits = 0b0_01111_00; // 1.0
        int highBits = 0b0_10000_00; // 2.0
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 0, lowBits);
            fp.setElement(1, 8 + i, 0, highBits);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(false, false, false, 0, 1));
        assertEquals(AdvSimdLanes.halfBits(1.0f), fp.element(0, 0, 1));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFromFp8(false, false, true, 0, 1));
        assertEquals(AdvSimdLanes.halfBits(2.0f), fp.element(0, 0, 1));
    }
}
