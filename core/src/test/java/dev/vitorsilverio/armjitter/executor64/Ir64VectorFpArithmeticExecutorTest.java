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
                Ir64VectorFpThreeSameOp.ADD, true, 2, 0, 1, 2));

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
                Ir64VectorFpThreeSameOp.ADD, false, 3, 0, 1, 2));

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
                Ir64VectorFpThreeSameOp.SUB, false, 2, 0, 1, 2));
        assertEquals(6.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.DIV, false, 2, 0, 1, 2));
        assertEquals(2.5f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MUL, false, 2, 0, 1, 2));
        assertEquals(40.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void maxMinPropagateNanMaxnmMinnmDont() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(Float.NaN) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(5.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MAX, false, 2, 0, 1, 2));
        assertTrue(Float.isNaN(Float.intBitsToFloat((int) fp.element(0, 0, 2))), "MAX propaga NaN");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MAXNM, false, 2, 0, 1, 2));
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
                Ir64VectorFpThreeSameOp.MLA, false, 2, 0, 1, 2));
        assertEquals(7.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "1 + 2*3 = 7");

        fp.setElement(0, 0, 2, Float.floatToRawIntBits(10.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.MLS, false, 2, 0, 1, 2));
        assertEquals(4.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)), "10 - 2*3 = 4");
    }

    @Test
    void compareOpsProduceAllOnesOrZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.CMGT, false, 2, 0, 1, 2));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.CMEQ, false, 2, 0, 1, 2));
        assertEquals(0L, fp.element(0, 0, 2));
    }

    @Test
    void facgeUsesAbsoluteValue() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-5.0f) & 0xFFFF_FFFFL);
        fp.setElement(2, 0, 2, Float.floatToRawIntBits(3.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.FACGE, false, 2, 0, 1, 2));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2), "|-5| >= |3|");

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticThreeSame(
                Ir64VectorFpThreeSameOp.CMGT, false, 2, 0, 1, 2));
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
                Ir64VectorFpPairwiseOp.ADD, true, 2, 0, 1, 2));

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

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.ABS, false, 2, 0, 1));
        assertEquals(0, Float.floatToRawIntBits(Float.intBitsToFloat((int) fp.element(0, 0, 2))),
                "ABS(-0.0) preserva +0.0 exato via manipulação de bit");
    }

    @Test
    void negManipulatesSignBitNotArithmetic() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(0.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.NEG, false, 2, 0, 1));
        assertTrue(Float.floatToRawIntBits(Float.intBitsToFloat((int) fp.element(0, 0, 2))) < 0,
                "NEG(+0.0) produz -0.0 pelo bit de sinal");
    }

    @Test
    void sqrtVector() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(16.0));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.SQRT, false, 3, 0, 1));
        assertEquals(4.0, Double.longBitsToDouble(fp.element(0, 0, 3)));
    }

    @Test
    void compareZeroVector() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.CMLT0, false, 2, 0, 1));
        assertEquals(0xFFFF_FFFFL, fp.element(0, 0, 2));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.CMGT0, false, 2, 0, 1));
        assertEquals(0L, fp.element(0, 0, 2));
    }

    @Test
    void rintnRoundsTiesToEven() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 3, Double.doubleToRawLongBits(2.5));
        fp.setElement(1, 1, 3, Double.doubleToRawLongBits(3.5));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RINTN, true, 3, 0, 1));

        assertEquals(2.0, Double.longBitsToDouble(fp.element(0, 0, 3)), "2.5 arredonda para 2 (par)");
        assertEquals(4.0, Double.longBitsToDouble(fp.element(0, 1, 3)), "3.5 arredonda para 4 (par)");
    }

    @Test
    void rintzTruncatesTowardZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.9f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RINTZ, false, 2, 0, 1));
        assertEquals(-1.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void scvtfUcvtfConvertIntegerElements() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, 0xFFFF_FFFFL); // word: -1 assinado / 4294967295 não assinado

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.SCVTF, false, 2, 0, 1));
        assertEquals(-1.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.UCVTF, false, 2, 0, 1));
        assertEquals(4294967295.0f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));
    }

    @Test
    void fcvtzsFcvtzuTruncateAndSaturate() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(3.9f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTZS, false, 2, 0, 1));
        assertEquals(3, (int) fp.element(0, 0, 2));

        // FCVTZU de -1.0 satura em 0 (sem sinal não pode representar negativo).
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(-1.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTZU, false, 2, 0, 1));
        assertEquals(0L, fp.element(0, 0, 2));
    }

    @Test
    void fcvtasRoundsTiesAwayFromZero() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTAS, false, 2, 0, 1));
        assertEquals(3, (int) fp.element(0, 0, 2), "2.5 empata e afasta de zero (ties-away) = 3, diferente de RINTN");
    }

    @Test
    void recpeRsqrteApproximateReciprocal() {
        Aarch64Core core = newCore();
        Aarch64FpRegisters fp = core.fp();
        fp.setElement(1, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);

        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RECPE, false, 2, 0, 1));
        assertEquals(0.25f, Float.intBitsToFloat((int) fp.element(0, 0, 2)));

        fp.setElement(1, 0, 2, Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL);
        EXECUTOR.executeOp(core, new Ir64Op.VectorFpArithmeticUnary(Ir64VectorFpUnaryOp.RSQRTE, false, 2, 0, 1));
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
}
