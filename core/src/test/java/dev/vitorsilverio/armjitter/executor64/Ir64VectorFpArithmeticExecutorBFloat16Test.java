package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.7 — semântica de `FEAT_BF16` direto no executor (interpretador = oráculo, G1). Núcleo de
/// conversão em {@link AdvSimdLanes} testado exaustivamente à parte
/// ({@code AdvSimdLanesBFloat16Test}); aqui só a ponte registrador↔núcleo de cada uma das 8 linhas.
class Ir64VectorFpArithmeticExecutorBFloat16Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    private static long bf16(float value) {
        return AdvSimdLanes.bf16Bits(value);
    }

    // ── BFCVT ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void bfcvtRoundsToNearestEvenAndZeroesRest() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setS(1, Float.floatToRawIntBits(1.0f));
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujar Vd antes
        EXECUTOR.executeOp(core, new Ir64Op.Fp64ConvertToBf16(0, 1));
        assertEquals(0x3F80L, fp.word(0) & 0xFFFFL);
        assertEquals(0L, fp.word(0) >>> 16, "resto do V0 fica zerado (SIMD&FP destructive write)");
        assertEquals(0L, fp.word(1));
    }

    // ── BFCVTN/BFCVTN2 ──────────────────────────────────────────────────────────────────────────

    @Test
    void bfcvtnLowHalfZeroesHigh() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(1.0f));
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(2.0f));
        fp.setQ(0, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertPrecision(
                dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp.BFCVTN, false, 1, 0, 1));
        assertEquals(bf16(1.0f), fp.element(0, 0, 1));
        assertEquals(bf16(2.0f), fp.element(0, 1, 1));
        assertEquals(0L, fp.word(1), "forma sem `2` zera a metade alta");
    }

    @Test
    void bfcvtn2HighHalfPreservesLow() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setD(0, 0x1111_1111_2222_2222L); // metade baixa pré-existente, deve sobreviver
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(3.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertPrecision(
                dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp.BFCVTN, true, 1, 0, 1));
        assertEquals(0x1111_1111_2222_2222L, fp.low64(0), "BFCVTN2 preserva a metade baixa");
        assertEquals(bf16(3.0f), fp.element(0, 4, 1));
    }

    // ── BFDOT ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void bfdotVectorAccumulatesPairsInFloat() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vn.4H = [1.0, 2.0, ...], Vm.4H = [3.0, 4.0, ...] -> lane0 = 1*3 + 2*4 = 11
        fp.setElement(1, 0, 1, bf16(1.0f));
        fp.setElement(1, 1, 1, bf16(2.0f));
        fp.setElement(2, 0, 1, bf16(3.0f));
        fp.setElement(2, 1, 1, bf16(4.0f));
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(0.5f)); // acumulador pré-existente
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpDotProductBFloat16(false, 0, 1, 2));
        assertEquals(11.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(0L, fp.word(1), "q=false zera a metade alta de Vd");
    }

    @Test
    void bfdotByElementReplicatesFixedPair() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vn.8H (2 lanes de resultado): lane0 usa Vn[0..1], lane1 usa Vn[2..3].
        fp.setElement(1, 0, 1, bf16(1.0f));
        fp.setElement(1, 1, 1, bf16(1.0f));
        fp.setElement(1, 2, 1, bf16(2.0f));
        fp.setElement(1, 3, 1, bf16(2.0f));
        // Vm par fixo no índice 1 (segundo par de 2 halfwords), replicado nas 2 lanes.
        fp.setElement(2, 2, 1, bf16(5.0f));
        fp.setElement(2, 3, 1, bf16(5.0f));
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpDotProductBFloat16ByElement(false, 0, 1, 2, 1));
        // lane0 = 1*5 + 1*5 = 10 ; lane1 = 2*5 + 2*5 = 20
        assertEquals(10.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(20.0f, Float.intBitsToFloat((int) fp.element(0, 1, 2)));
    }

    // ── BFMLALB/BFMLALT ─────────────────────────────────────────────────────────────────────────

    @Test
    void bfmlalbUsesEvenElementsOnly() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        float[] n = {1, 10, 2, 20, 3, 30, 4, 40}; // pares: B=[1,2,3,4], T=[10,20,30,40]
        float[] m = {1, 2, 1, 2, 1, 2, 1, 2};     // pares: B=[1,1,1,1], T=[2,2,2,2]
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 1, bf16(n[i]));
            fp.setElement(2, i, 1, bf16(m[i]));
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongBFloat16(false, 0, 1, 2));
        assertEquals(1f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(2f, Float.intBitsToFloat((int) fp.element(0, 1, 2)));
        assertEquals(3f, Float.intBitsToFloat((int) fp.element(0, 2, 2)));
        assertEquals(4f, Float.intBitsToFloat((int) fp.element(0, 3, 2)));
    }

    @Test
    void bfmlaltUsesOddElementsOnlyAndAccumulates() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        float[] n = {1, 10, 2, 20, 3, 30, 4, 40};
        float[] m = {1, 2, 1, 2, 1, 2, 1, 2};
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 1, bf16(n[i]));
            fp.setElement(2, i, 1, bf16(m[i]));
        }
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(100f)); // acumulador pré-existente na lane0
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongBFloat16(true, 0, 1, 2));
        assertEquals(120f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "100 + 10*2");
        assertEquals(40f, Float.intBitsToFloat((int) fp.element(0, 1, 2)), "0 + 20*2");
    }

    @Test
    void bfmlalByElementReplicatesFixedElement() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        float[] n = {1, 10, 2, 20, 3, 30, 4, 40};
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 1, bf16(n[i]));
        }
        fp.setElement(2, 5, 1, bf16(2.0f)); // elemento fixo índice 5 (ímpar)
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMultiplyAddLongBFloat16ByElement(true, 0, 1, 2, 5));
        // top=true -> elementos ímpares de Vn: [10,20,30,40] * 2 = [20,40,60,80]
        assertEquals(20f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(40f, Float.intBitsToFloat((int) fp.element(0, 1, 2)));
        assertEquals(60f, Float.intBitsToFloat((int) fp.element(0, 2, 2)));
        assertEquals(80f, Float.intBitsToFloat((int) fp.element(0, 3, 2)));
    }

    // ── BFMMLA ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void bfmmlaAsymmetricMatrixAccumulates() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // Vn: linha0 = [1,2,3,4], linha1 = [5,6,7,8] (matriz 2x4 assimétrica).
        float[] rows = {1, 2, 3, 4, 5, 6, 7, 8};
        // Vm: coluna0 = [1,0,0,0], coluna1 = [0,1,2,0] (matriz 4x2 assimétrica).
        float[] cols = {1, 0, 0, 0, 0, 1, 2, 0};
        for (int i = 0; i < 8; i++) {
            fp.setElement(1, i, 1, bf16(rows[i]));
            fp.setElement(2, i, 1, bf16(cols[i]));
        }
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(0.5f)); // Vd[0][0] pré-existente
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpMatrixMultiplyAccumulateBFloat16(0, 1, 2));
        // dot(row0,col0)=1*1=1 ; dot(row0,col1)=2*1+3*2=8 ; dot(row1,col0)=5*1=5 ; dot(row1,col1)=6*1+7*2=20
        assertEquals(1.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "0.5 + dot(row0,col0)");
        assertEquals(8f, Float.intBitsToFloat((int) fp.element(0, 1, 2)));
        assertEquals(5f, Float.intBitsToFloat((int) fp.element(0, 2, 2)));
        assertEquals(20f, Float.intBitsToFloat((int) fp.element(0, 3, 2)));
    }
}
