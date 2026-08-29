package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
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

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, 0, 1));
        assertEquals(3.5f, fp.sFloat(0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMINV, 0, 1));
        assertEquals(-5.0f, fp.sFloat(0));
    }

    @Test
    void fmaxnmvFminnmvIgnoreNaN() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setFourSingleElements(fp, 1, Float.NaN, 7.0f, 1.0f, 2.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXNMV, 0, 1));
        assertEquals(7.0f, fp.sFloat(0), "FMAXNMV ignora o NaN quando há operando numérico");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMINNMV, 0, 1));
        assertEquals(1.0f, fp.sFloat(0), "FMINNMV ignora o NaN quando há operando numérico");
    }

    @Test
    void fmaxvPropagatesNaN() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        setFourSingleElements(fp, 1, Float.NaN, 7.0f, 1.0f, 2.0f);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpAcrossLanes(Ir64VectorFpAcrossLanesOp.FMAXV, 0, 1));
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
}
