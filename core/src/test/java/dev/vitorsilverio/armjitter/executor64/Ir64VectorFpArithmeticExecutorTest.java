package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Semântica dos ops de AdvSIMD FP vetorial (B8.9) direto no executor (interpretador = oráculo,
/// G1) — complementa {@code Aarch64AdvSimdFpVectorDecoderTest} (decode).
class Ir64VectorFpArithmeticExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(64);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void addSingleElementwise() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 1, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.ADD, false, true, 2, 0, 1, 2));

        assertEquals(4.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(2.0f, Float.intBitsToFloat((int) fp.element(0, 1, 2)));
    }

    @Test
    void addDoubleAndNonQuadZeroesHighBits() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0L, 0xFFFF_FFFF_FFFF_FFFFL); // "sujeira" pré-existente
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(1.25));
        fp.setElement(2, 0, 3, Double.doubleToRawLongBits(2.75));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.ADD, false, false, 3, 0, 1, 2));

        assertEquals(4.0, Double.longBitsToDouble(fp.element(0, 0, 3)));
        assertEquals(0L, fp.high64(0), "forma não-quad zera os 64 bits altos (destructive write)");
    }

    @Test
    void subDivMul() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(10.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.SUB, false, false, 2, 0, 1, 2));
        assertEquals(6.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.DIV, false, false, 2, 0, 1, 2));
        assertEquals(2.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MUL, false, false, 2, 0, 1, 2));
        assertEquals(40.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void maxMinPropagateNanMaxnmMinnmDont() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(5.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MAX, false, false, 2, 0, 1, 2));
        assertTrue(Float.isNaN(Float.intBitsToFloat((int) fp.element(0, 0, 2))), "MAX propaga NaN");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MAXNM, false, false, 2, 0, 1, 2));
        assertEquals(5.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "MAXNM ignora o NaN");
    }

    @Test
    void mlaMlsFusedAccumulate() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(0, 0, 2, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL); // Rd atual
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MLA, false, false, 2, 0, 1, 2));
        assertEquals(7.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "1 + 2*3 = 7");

        fp.setElement(0, 0, 2, Float.floatToRawIntBits(10.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MLS, false, false, 2, 0, 1, 2));
        assertEquals(4.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "10 - 2*3 = 4");
    }

    @Test
    void compareOpsProduceAllOnesOrZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.CMGT, false, false, 2, 0, 1, 2));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.CMEQ, false, false, 2, 0, 1, 2));
        assertEquals(0L, fp.element(0, 0, 2));
    }

    @Test
    void facgeUsesAbsoluteValue() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.FACGE, false, false, 2, 0, 1, 2));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2), "|-5| >= |3|");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.CMGT, false, false, 2, 0, 1, 2));
        assertEquals(0L, fp.element(0, 0, 2), "sem valor absoluto, -5 > 3 é falso");
    }

    @Test
    void pairwiseAdd() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        fp.setElement(1, 2, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);
        fp.setElement(1, 3, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(10.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 1, 2, Float.floatToRawIntBits(20.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 2, 2, Float.floatToRawIntBits(30.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 3, 2, Float.floatToRawIntBits(40.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.ADD, false, true, 2, 0, 1, 2));

        assertEquals(3.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "Rn[0]+Rn[1]");
        assertEquals(7.0f, Float.intBitsToFloat((int) fp.element(0, 1, 2)), "Rn[2]+Rn[3]");
        assertEquals(30.0f, Float.intBitsToFloat((int) fp.element(0, 2, 2)), "Rm[0]+Rm[1]");
        assertEquals(70.0f, Float.intBitsToFloat((int) fp.element(0, 3, 2)), "Rm[2]+Rm[3]");
    }

    @Test
    void absManipulatesSignBitNotArithmetic() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-0.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.ABS, false, false, 2, 0, 1));
        assertEquals(0, Float.floatToRawIntBits(Float.intBitsToFloat((int) fp.element(0, 0, 2))),
                "ABS(-0.0) preserva +0.0 exato via manipulação de bit");
    }

    @Test
    void negManipulatesSignBitNotArithmetic() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(0.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.NEG, false, false, 2, 0, 1));
        assertTrue(Float.floatToRawIntBits(Float.intBitsToFloat((int) fp.element(0, 0, 2))) < 0,
                "NEG(+0.0) produz -0.0 pelo bit de sinal");
    }

    @Test
    void sqrtVector() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(16.0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.SQRT, false, false, 3, 0, 1));
        assertEquals(4.0, Double.longBitsToDouble(fp.element(0, 0, 3)));
    }

    @Test
    void compareZeroVector() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.CMLT0, false, false, 2, 0, 1));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.CMGT0, false, false, 2, 0, 1));
        assertEquals(0L, fp.element(0, 0, 2));
    }

    @Test
    void rintnRoundsTiesToEven() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(2.5));
        fp.setElement(1, 1, 3, Double.doubleToRawLongBits(3.5));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RINTN, false, true, 3, 0, 1));

        assertEquals(2.0, Double.longBitsToDouble(fp.element(0, 0, 3)), "2.5 arredonda para 2 (par)");
        assertEquals(4.0, Double.longBitsToDouble(fp.element(0, 1, 3)), "3.5 arredonda para 4 (par)");
    }

    @Test
    void rintzTruncatesTowardZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.9f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RINTZ, false, false, 2, 0, 1));
        assertEquals(-1.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void scvtfUcvtfConvertIntegerElements() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 0xFFFF_FFFFL); // word: -1 assinado / 4294967295 não assinado

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.SCVTF, false, false, 2, 0, 1));
        assertEquals(-1.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.UCVTF, false, false, 2, 0, 1));
        assertEquals(4294967295.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void fcvtzsFcvtzuTruncateAndSaturate() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(3.9f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTZS, false, false, 2, 0, 1));
        assertEquals(3, (int) fp.element(0, 0, 2));

        // FCVTZU de -1.0 satura em 0 (sem sinal não pode representar negativo).
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTZU, false, false, 2, 0, 1));
        assertEquals(0L, fp.element(0, 0, 2));
    }

    @Test
    void fcvtasRoundsTiesAwayFromZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTAS, false, false, 2, 0, 1));
        assertEquals(3, (int) fp.element(0, 0, 2), "2.5 empata e afasta de zero (ties-away) = 3, diferente de RINTN");
    }

    @Test
    void recpeRsqrteApproximateReciprocal() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RECPE, false, false, 2, 0, 1));
        assertEquals(0.25f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RSQRTE, false, false, 2, 0, 1));
        assertEquals(0.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    /// `FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV` (B8.10) — reduz os 4 elementos `.4s` de `Rn`.
    private static void setFourSingleElements(Aarch64FpRegisters fp, int reg, float a, float b, float c, float d) {
        fp.setElement(reg, 0, 2, Float.floatToRawIntBits(a) & 0xFFFF_FFFFL);
        fp.setElement(reg, 1, 2, Float.floatToRawIntBits(b) & 0xFFFF_FFFFL);
        fp.setElement(reg, 2, 2, Float.floatToRawIntBits(c) & 0xFFFF_FFFFL);
        fp.setElement(reg, 3, 2, Float.floatToRawIntBits(d) & 0xFFFF_FFFFL);
    }

    @Test
    void fmaxvFminvReduceFourElements() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setFourSingleElements(fp, 1, 1.0f, -5.0f, 3.5f, 2.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, true, 2, 0, 1));
        assertEquals(3.5f, fp.sFloat(0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMINV, true, 2, 0, 1));
        assertEquals(-5.0f, fp.sFloat(0));
    }

    @Test
    void fmaxnmvFminnmvIgnoreNaN() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setFourSingleElements(fp, 1, Float.NaN, 7.0f, 1.0f, 2.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXNMV, true, 2, 0, 1));
        assertEquals(7.0f, fp.sFloat(0), "FMAXNMV ignora o NaN quando há operando numérico");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMINNMV, true, 2, 0, 1));
        assertEquals(1.0f, fp.sFloat(0), "FMINNMV ignora o NaN quando há operando numérico");
    }

    @Test
    void fmaxvPropagatesNaN() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setFourSingleElements(fp, 1, Float.NaN, 7.0f, 1.0f, 2.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, true, 2, 0, 1));
        assertEquals(true, Float.isNaN(fp.sFloat(0)), "FMAXV (sem NM) propaga NaN");
    }

    // ── B19.2: formas AdvSIMD ESCALARES (three same + pairwise) ────────────────────────────────

    private static Ir64Op.VectorFpArithmeticThreeSame scalar3(Ir64VectorFpThreeSameOp op, int esz) {
        return new Ir64Op.VectorFpArithmeticThreeSame(op, true, false, esz, 0, 1, 2);
    }

    @Test
    void scalarThreeSameProcessesOnlyLane0AndZeroesRest() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xDEAD_BEEF_DEAD_BEEFL, 0xFFFF_FFFF_FFFF_FFFFL); // sujeira em todo o Rd
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(10.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(99.0f) & 0xFFFF_FFFFL); // lane 1 não deve ser lida
        fp.setElement(2, 1, 2, Float.floatToRawIntBits(99.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.ABD, 2)); // |10 - 4| = 6

        assertEquals(6.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(0L, fp.high64(0), "escrita escalar zera os 64 bits altos");
        assertEquals(0L, fp.low64(0) >>> 32, "escrita escalar zera o resto do low64 (acima de S)");
    }

    @Test
    void scalarFmulxZeroTimesInfinity() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(0.0));
        fp.setElement(2, 0, 3, Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));

        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.MULX, 3));
        assertEquals(2.0, Double.longBitsToDouble(fp.element(0, 0, 3)), "FPMulX: 0*Inf = 2.0");
    }

    @Test
    void scalarFrecpsFrsqrtsSteps() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.RECPS, 2));
        assertEquals(2.0f - 2.0f * 3.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "2 - a*b");

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.RSQRTS, 2));
        assertEquals((3.0f - 2.0f * 3.0f) / 2.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "(3 - a*b)/2");
    }

    @Test
    void scalarCompareAndAbsCompare() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.CMGE, 2));
        assertEquals(0L, fp.element(0, 0, 2), "-5 >= 3 é falso");

        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.FACGE, 2));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2), "|-5| >= |3|");

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalar3(Ir64VectorFpThreeSameOp.CMEQ, 2));
        assertEquals(0L, fp.element(0, 0, 2), "NaN nunca compara igual");
    }

    @Test
    void scalarPairwiseAddAndMaxMinPropagation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(1.25));
        fp.setElement(1, 1, 3, Double.doubleToRawLongBits(2.75));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.ADD, true, false, 3, 0, 1, 1));
        assertEquals(4.0, Double.longBitsToDouble(fp.element(0, 0, 3)), "V1[0]+V1[1]");
        assertEquals(0L, fp.high64(0), "escrita escalar zera bits altos");

        // FMINP propaga NaN de um só operando; FMINNMP não.
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.MIN, true, false, 2, 0, 1, 1));
        assertEquals(true, Float.isNaN(Float.intBitsToFloat((int) fp.element(0, 0, 2))), "FMINP_s propaga NaN");

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.MINNM, true, false, 2, 0, 1, 1));
        assertEquals(3.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "FMINNMP_s ignora NaN de um operando");
    }

    // ── B19.3: AdvSIMD FP ESCALAR "two-register misc" + conversões ─────────────────────────────

    private static Ir64Op.VectorFpArithmeticUnary scalarUnary(Ir64VectorFpUnaryOp op, int esz) {
        return new Ir64Op.VectorFpArithmeticUnary(op, true, false, esz, 0, 1);
    }

    @Test
    void scalarCompareAgainstZeroLaneZeroOnly() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0xDEAD_BEEF_DEAD_BEEFL, -1L); // sujeira em todo o Rd
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-0.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.CMEQ0, 2)); // -0.0 == 0.0
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2), "-0.0 conta como zero");
        assertEquals(0L, fp.high64(0), "escrita escalar zera os 64 bits altos");
        assertEquals(0L, fp.low64(0) >>> 32, "escrita escalar zera o resto do low64");

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.CMGT0, 2));
        assertEquals(0L, fp.element(0, 0, 2), "NaN > 0 é falso");

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-3.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.CMLT0, 2));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2), "-3 < 0");
    }

    @Test
    void scalarFrecpeFrsqrte() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.RECPE, 2));
        assertEquals(0.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.RSQRTE, 2));
        assertEquals(0.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void scalarFrecpxReflectsExponent() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FRECPX, 2));
        assertEquals(0.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "expoente refletido, mantissa 0");
        assertEquals(0L, fp.high64(0));
        assertEquals(0L, fp.low64(0) >>> 32);

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(0.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FRECPX, 2));
        assertEquals(Float.POSITIVE_INFINITY, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "+0 -> +Inf");

        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY));
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FRECPX, 3));
        assertEquals(Double.doubleToRawLongBits(-0.0), fp.element(0, 0, 3), "-Inf -> -0.0");

        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(Double.NaN));
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FRECPX, 3));
        assertEquals(true, Double.isNaN(Double.longBitsToDouble(fp.element(0, 0, 3))), "NaN -> NaN");
    }

    @Test
    void scalarFcvtxnRoundToOdd() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        // d exatamente no meio entre 1.0f (mantissa PAR) e o próximo float ⇒ round-to-odd sobe.
        double halfway = 1.0 + Math.scalb(1.0, -24);
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(halfway));
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FCVTXN, 3));
        int result = (int) fp.element(0, 0, 2);
        assertEquals(1, result & 1, "round-to-odd força o LSB a 1");
        assertEquals(Float.floatToRawIntBits(1.0f) + 1, result, "vizinho ímpar de 1.0f");
        assertTrue(Float.floatToRawIntBits((float) halfway) != result,
                "difere do round-to-nearest-even de (float) d (que dá 1.0f)");
        assertEquals(0L, fp.high64(0));
        assertEquals(0L, fp.low64(0) >>> 32, "Rd[63:32] zerado");

        // valor exato em float ⇒ igual a (float) d.
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(1.5));
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FCVTXN, 3));
        assertEquals(Float.floatToRawIntBits(1.5f), (int) fp.element(0, 0, 2));
    }

    @Test
    void scalarIcvtConversions() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 5L);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.SCVTF, 2));
        assertEquals(5.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(3.9f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FCVTZS, 2));
        assertEquals(3, (int) fp.element(0, 0, 2));

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FCVTPS, 2));
        assertEquals(3, (int) fp.element(0, 0, 2), "FCVTPS 2.5 -> ceil -> 3");
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, scalarUnary(Ir64VectorFpUnaryOp.FCVTMS, 2));
        assertEquals(2, (int) fp.element(0, 0, 2), "FCVTMS 2.5 -> floor -> 2");
    }

    @Test
    void scalarFixedPointConvert() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        // SCVTF s, #4: int 32 -> 32 / 2^4 = 2.0
        fp.setElement(1, 0, 2, 32L);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 2, 4, true, true, 0, 1));
        assertEquals(2.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "32 / 2^4");
        assertEquals(0L, fp.high64(0));
        assertEquals(0L, fp.low64(0) >>> 32);

        // FCVTZU s, #8: 1.5 * 2^8 = 384, truncado
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 2, 8, false, false, 0, 1));
        assertEquals(384L, fp.element(0, 0, 2), "1.5 * 2^8");

        // FCVTZU s de -1.0 satura em 0
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 2, 0, false, false, 0, 1));
        assertEquals(0L, fp.element(0, 0, 2), "saturação preservada");

        // SCVTF d, #33: int 2^34 -> 2^34 / 2^33 = 2.0
        fp.setElement(1, 0, 3, 1L << 34);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 3, 33, true, true, 0, 1));
        assertEquals(2.0, Double.longBitsToDouble(fp.element(0, 0, 3)));
    }

    // ── B19.4: conversões de PRECISÃO vetoriais ───────────────────────────────────────────────────

    private static Ir64Op.VectorFpConvertPrecision precision(
            Ir64VectorFpConvertPrecisionOp op, boolean q, int esz) {
        return new Ir64Op.VectorFpConvertPrecision(op, q, esz, 0, 1);
    }

    @Test
    void fcvtlHalfToSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L); // sujeira em todo o Rd
        fp.setElement(1, 0, 1, Float.floatToFloat16(1.0f) & 0xFFFFL);
        fp.setElement(1, 1, 1, 0x0001L); // menor subnormal f16 = 2^-24
        fp.setElement(1, 2, 1, 0x7C00L); // +Inf f16
        fp.setElement(1, 3, 1, 0x7E00L); // NaN f16

        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTL, false, 1));

        assertEquals(1.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(Math.scalb(1.0f, -24), Float.intBitsToFloat((int) fp.element(0, 1, 2)), "subnormal exato");
        assertEquals(Float.POSITIVE_INFINITY, Float.intBitsToFloat((int) fp.element(0, 2, 2)));
        assertTrue(Float.isNaN(Float.intBitsToFloat((int) fp.element(0, 3, 2))), "NaN -> NaN");
    }

    @Test
    void fcvtl2ReadsHighHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        // metade baixa: 2.0h em todas; metade alta: 4.0h nas lanes 4..7
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(1, lane, 1, Float.floatToFloat16(2.0f) & 0xFFFFL);
            fp.setElement(1, lane + 4, 1, Float.floatToFloat16(4.0f) & 0xFFFFL);
        }
        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTL, true, 1));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(4.0f, Float.intBitsToFloat((int) fp.element(0, lane, 2)), "FCVTL2 lê a metade ALTA");
        }
    }

    @Test
    void fcvtlSingleToDoubleExact() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(3.5f) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(-0.25f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTL, false, 2));
        assertEquals(3.5, Double.longBitsToDouble(fp.element(0, 0, 3)));
        assertEquals(-0.25, Double.longBitsToDouble(fp.element(0, 1, 3)));
    }

    @Test
    void fcvtnSingleToHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        float rounds = 1.0f + Math.scalb(1.0f, -11); // entre dois f16 ⇒ ties-to-even ⇒ 1.0h
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(rounds) & 0xFFFF_FFFFL);
        fp.setElement(1, 1, 2, Float.floatToRawIntBits(1.0e30f) & 0xFFFF_FFFFL); // overflow ⇒ +Inf

        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTN, false, 1));

        assertEquals(Float.floatToFloat16(rounds) & 0xFFFFL, fp.element(0, 0, 1),
                "round-to-nearest-even, conferido contra Float.floatToFloat16");
        assertEquals(0x7C00L, fp.element(0, 1, 1), "overflow -> +Inf f16");
        assertEquals(0L, fp.high64(0), "FCVTN q=0 zera Rd[127:64]");
        assertEquals(0L, fp.low64(0) >>> 32, "e o resto de Rd[63:32]");
    }

    @Test
    void fcvtn2PreservesLowHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, 0x1111_2222_3333_4444L, 0xDEAD_BEEF_DEAD_BEEFL);
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(1, lane, 2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL);
        }
        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTN, true, 1));
        assertEquals(0x1111_2222_3333_4444L, fp.low64(0), "FCVTN2 q=1 PRESERVA Rd[63:0]");
        for (int lane = 4; lane < 8; lane++) {
            assertEquals(Float.floatToFloat16(2.0f) & 0xFFFFL, fp.element(0, lane, 1), "escreve a metade ALTA");
        }
    }

    @Test
    void fcvtnDoubleToSingle() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(3.25));
        fp.setElement(1, 1, 3, Double.doubleToRawLongBits(1.0e300)); // overflow ⇒ +Inf f32
        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTN, false, 2));
        assertEquals(3.25f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
        assertEquals(Float.POSITIVE_INFINITY, Float.intBitsToFloat((int) fp.element(0, 1, 2)));
    }

    @Test
    void fcvtxnVectorRoundToOdd() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        double halfway = 1.0 + Math.scalb(1.0, -24); // meio entre 1.0f (mantissa PAR) e o próximo
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(halfway));
        fp.setElement(1, 1, 3, Double.doubleToRawLongBits(1.5)); // exato em f32

        EXECUTOR.executeOp(core, precision(Ir64VectorFpConvertPrecisionOp.FCVTXN, false, 2));

        int lane0 = (int) fp.element(0, 0, 2);
        assertEquals(1, lane0 & 1, "round-to-odd força o LSB a 1");
        assertTrue(Float.floatToRawIntBits((float) halfway) != lane0, "difere de (float) d");
        assertEquals(Float.floatToRawIntBits(1.5f), (int) fp.element(0, 1, 2), "valor exato = (float) d");
    }

    @Test
    void fcvtlAndFcvtnAliasRdRn() {
        // Rd == Rn — prova do buffer (Armadilha 4).
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(5, lane, 1, Float.floatToFloat16(lane + 1.0f) & 0xFFFFL);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertPrecision(
                Ir64VectorFpConvertPrecisionOp.FCVTL, false, 1, 5, 5));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(lane + 1.0f, Float.intBitsToFloat((int) fp.element(5, lane, 2)),
                    "FCVTL com Rd==Rn não corrompe fontes ainda não lidas");
        }

        Aarch64Core core2 = newCore();
        Aarch64FpRegisters fp2 = core2.fp();
        for (int lane = 0; lane < 4; lane++) {
            fp2.setElement(6, lane, 2, Float.floatToRawIntBits(lane + 1.0f) & 0xFFFF_FFFFL);
        }
        EXECUTOR.executeOp(core2, new Ir64Op.VectorFpConvertPrecision(
                Ir64VectorFpConvertPrecisionOp.FCVTN, false, 1, 6, 6));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(Float.floatToFloat16(lane + 1.0f) & 0xFFFFL, fp2.element(6, lane, 1),
                    "FCVTN com Rd==Rn correto");
        }
    }

    @Test
    void fcvtFixedPointVectorExecution() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        // SCVTF v0.4s, v1.4s, #4: 32 -> 2.0f em todas as 4 lanes
        for (int lane = 0; lane < 4; lane++) {
            fp.setElement(1, lane, 2, 32L);
        }
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(false, true, 2, 4, true, true, 0, 1));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(2.0f, Float.intBitsToFloat((int) fp.element(0, lane, 2)), "32 / 2^4");
        }

        // FCVTZU v0.2d, v1.2d, #8, q=false ⇒ zera a metade alta
        Aarch64Core core2 = newCore();
        Aarch64FpRegisters fp2 = core2.fp();
        fp2.setQ(0, -1L, -1L);
        fp2.setElement(1, 0, 3, Double.doubleToRawLongBits(1.5));
        EXECUTOR.executeOp(core2, new Ir64Op.VectorFpConvertFixedPoint(false, false, 3, 8, false, false, 0, 1));
        assertEquals(384L, fp2.element(0, 0, 3), "1.5 * 2^8");
        assertEquals(0L, fp2.high64(0), "q=false zera Rd[127:64]");

        // FCVTZU de -1.0 satura em 0
        fp2.setElement(1, 0, 3, Double.doubleToRawLongBits(-1.0));
        EXECUTOR.executeOp(core2, new Ir64Op.VectorFpConvertFixedPoint(false, false, 3, 0, false, false, 0, 1));
        assertEquals(0L, fp2.element(0, 0, 3), "saturação preservada");
    }

    // ── B19.5.3: FEAT_FP16 — pairwise/across-lanes/ponto fixo escalar+vetorial em meia precisão ──

    private static void setHalf(Aarch64FpRegisters fp, int reg, int lane, float value) {
        fp.setElement(reg, lane, 1, AdvSimdLanes.halfBits(value));
    }

    private static float readHalf(Aarch64FpRegisters fp, int reg, int lane) {
        return AdvSimdLanes.halfToFloat(fp.element(reg, lane, 1));
    }

    @Test
    void halfPrecisionScalarPairwiseAddAndPropagation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        setHalf(fp, 1, 0, 1.25f);
        setHalf(fp, 1, 1, 2.75f);

        // FADDP h0, v1.2h
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.ADD, true, false, 1, 0, 1, 1));
        assertEquals(4.0f, readHalf(fp, 0, 0), "V1[0]+V1[1] em binary16");
        assertEquals(0L, fp.high64(0), "escrita escalar zera os 64 bits altos");
        assertEquals(0L, fp.low64(0) >>> 16, "escrita escalar zera acima de H");

        // FMINP propaga NaN de um só operando; FMINNMP não.
        setHalf(fp, 1, 0, Float.NaN);
        setHalf(fp, 1, 1, 3.0f);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.MIN, true, false, 1, 0, 1, 1));
        assertTrue(Float.isNaN(readHalf(fp, 0, 0)), "FMINP_h propaga NaN");

        setHalf(fp, 1, 0, Float.NaN);
        setHalf(fp, 1, 1, 3.0f);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticPairwise(
                Ir64VectorFpPairwiseOp.MINNM, true, false, 1, 0, 1, 1));
        assertEquals(3.0f, readHalf(fp, 0, 0), "FMINNMP_h ignora NaN de um operando");
    }

    @Test
    void halfPrecisionAcrossLanesReadsQAndDistinguishesMaxFromMaxNm() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setHalf(fp, 1, 0, 1.0f);
        setHalf(fp, 1, 1, -5.0f);
        setHalf(fp, 1, 2, 3.5f);
        setHalf(fp, 1, 3, 2.0f);
        setHalf(fp, 1, 4, 9.0f); // só lido se q=true (8h)
        setHalf(fp, 1, 5, 9.0f);
        setHalf(fp, 1, 6, 9.0f);
        setHalf(fp, 1, 7, 9.0f);

        // FMAXV h0, v1.4h — só as 4 lanes baixas
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, false, 1, 0, 1));
        assertEquals(3.5f, readHalf(fp, 0, 0), "reduz só 4h quando q=false");

        // FMAXV h0, v1.8h — as 8 lanes, Q lido de verdade (diferente da forma _s, que não tem essa liberdade)
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, true, 1, 0, 1));
        assertEquals(9.0f, readHalf(fp, 0, 0), "reduz 8h quando q=true");

        // FMAXNMV × FMAXV com um NaN entre as lanes: maxNum ignora, max propaga.
        setHalf(fp, 1, 0, Float.NaN);
        setHalf(fp, 1, 1, 7.0f);
        setHalf(fp, 1, 2, 1.0f);
        setHalf(fp, 1, 3, 2.0f);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXNMV, false, 1, 0, 1));
        assertEquals(7.0f, readHalf(fp, 0, 0), "FMAXNMV ignora o NaN quando há operando numérico");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, false, 1, 0, 1));
        assertTrue(Float.isNaN(readHalf(fp, 0, 0)), "FMAXV (sem NM) propaga NaN");
    }

    @Test
    void halfPrecisionScalarFixedPointRoundTripAndSaturation() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();

        // SCVTF h0, h1, #4: inteiro 32 (0x0020) / 2^4 = 2.0
        fp.setElement(1, 0, 1, 0x0020L);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 1, 4, true, true, 0, 1));
        assertEquals(2.0f, readHalf(fp, 0, 0), "32 / 2^4 (fbits no meio da faixa 1..16)");

        // UCVTF com um padrão que só é positivo SEM sinal (0x8000 = -32768 assinado / 32768 sem sinal)
        fp.setElement(1, 0, 1, 0x8000L);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 1, 1, true, false, 0, 1));
        assertEquals(16384.0f, readHalf(fp, 0, 0), "32768 / 2^1, lido SEM sinal");

        // SCVTF do MESMO padrão, assinado: -32768 / 2^1
        fp.setElement(1, 0, 1, 0x8000L);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 1, 1, true, true, 0, 1));
        assertEquals(-16384.0f, readHalf(fp, 0, 0), "sign-extensão de 16 bits, achado da B19.5.3");

        // FCVTZS h0,h1,#16 (fbits no limite superior da faixa 1..16): 0.25h * 2^16 = 16384, dentro
        // da faixa de int16 assinado — prova que o fator de escala usa 2^16 (não 2^32, que daria um
        // valor completamente diferente, a Armadilha 2 da task).
        setHalf(fp, 1, 0, 0.25f);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 1, 16, false, true, 0, 1));
        assertEquals((short) 16384, (short) fp.element(0, 0, 1), "0.25 * 2^16, fbits=16 (limite superior)");

        // FCVTZS de valor fora da faixa de 16 bits satura em INT16_MIN, sem wrap para 32/64 bits
        // (limite de {@link #saturateToInteger} sem a variante dedicada desta task).
        setHalf(fp, 1, 0, -40000.0f);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 1, 0, false, true, 0, 1));
        assertEquals((short) -32768, (short) fp.element(0, 0, 1), "satura em INT16_MIN, não faz wrap");

        // FCVTZU de negativo satura em 0 (não em um padrão de bits grande por wraparound)
        setHalf(fp, 1, 0, -5.0f);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(true, false, 1, 0, false, false, 0, 1));
        assertEquals(0L, fp.element(0, 0, 1), "FCVTZU satura em 0, não gera padrão de bits grande");
    }

    @Test
    void halfPrecisionVectorFixedPointQZeroesUpperHalf() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setQ(0, -1L, -1L);
        for (int lane = 0; lane < 4; lane++) {
            setHalf(fp, 1, lane, 2.0f);
        }

        // SCVTF v0.4h, v1.4h, #3 (interpretando as lanes como inteiro): 2.0h bits=0x4000=16384, /2^3=2048
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpConvertFixedPoint(false, false, 1, 3, true, true, 0, 1));
        for (int lane = 0; lane < 4; lane++) {
            assertEquals(2048.0f, readHalf(fp, 0, lane), "16384 / 2^3");
        }
        assertEquals(0L, fp.high64(0), "q=false zera Rd[127:64]");
    }
}
