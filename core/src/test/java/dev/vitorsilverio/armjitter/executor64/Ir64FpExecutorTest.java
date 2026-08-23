package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes da IR de FP escalar de A64 (B6.5.2): vetores concretos por operação, executados no
/// interpretador (o oráculo, G1). Decode fica em B6.5.3 — os `Ir64Op` são montados à mão, mesmo
/// padrão de {@code IrVfpExecutorTest} (precedente VFP32).
class Ir64FpExecutorTest {
    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)));
    }

    // ── 1. FADD/FSUB/FMUL/FDIV single e double ──────────────────────────────────

    @Test
    void addSingleRoundsLikeIeee754Float() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 0.1f);
        core.fp().setSFloat(1, 0.2f);
        Ir64Op.Fp64Alu add = new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ADD, false, 2, 0, 1);
        new Ir64BlockExecutor().executeOp(core, add);
        assertEquals(0x3E99999A, Float.floatToRawIntBits(0.1f + 0.2f));
        assertEquals(Float.floatToRawIntBits(0.1f + 0.2f), core.fp().s(2));
    }

    @Test
    void subMulDivDoublePrecision() {
        Aarch64Core subCore = newCore();
        subCore.fp().setDDouble(0, 5.0);
        subCore.fp().setDDouble(1, 2.0);
        new Ir64BlockExecutor().executeOp(subCore, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.SUB, true, 2, 0, 1));
        assertEquals(3.0, subCore.fp().dDouble(2));

        Aarch64Core core = newCore();
        core.fp().setDDouble(0, 5.0);
        core.fp().setDDouble(1, 2.0);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MUL, true, 3, 0, 1));
        assertEquals(10.0, core.fp().dDouble(3));

        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.DIV, true, 4, 0, 1));
        assertEquals(2.5, core.fp().dDouble(4));
    }

    // ── 2. FNEG/FABS preservam payload de NaN via bit de sinal ──────────────────

    @Test
    void negFlipsOnlyTheSignBitPreservingNanPayload() {
        int nanWithPayload = 0x7FC00001;
        Aarch64Core core = newCore();
        core.fp().setS(0, nanWithPayload);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NEG, false, 1, 0, 0));
        assertEquals(nanWithPayload ^ Integer.MIN_VALUE, core.fp().s(1));
    }

    @Test
    void negOfPositiveZeroIsNegativeZeroBitwise() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 0.0f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NEG, false, 1, 0, 0));
        assertEquals(Integer.MIN_VALUE, core.fp().s(1));
    }

    @Test
    void absClearsTheSignBitPreservingNanPayload() {
        long nanWithPayload = 0xFFF8_0000_0000_0001L;
        Aarch64Core core = newCore();
        core.fp().setD(0, nanWithPayload);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ABS, true, 1, 0, 0));
        assertEquals(nanWithPayload & Long.MAX_VALUE, core.fp().d(1));
    }

    // ── 3. FMOV registrador↔registrador: bits crus, cross-size irrelevante aqui ─

    @Test
    void movRegisterToRegisterCopiesRawBitsIncludingNanPayloadSingle() {
        int nanWithPayload = 0x7FC0BEEF;
        Aarch64Core core = newCore();
        core.fp().setS(0, nanWithPayload);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MOV, false, 1, 0, 0));
        assertEquals(nanWithPayload, core.fp().s(1));
    }

    @Test
    void movRegisterToRegisterCopiesRawBitsIncludingNanPayloadDouble() {
        long nanWithPayload = 0xFFF8_0000_0000_BEEFL;
        Aarch64Core core = newCore();
        core.fp().setD(0, nanWithPayload);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MOV, true, 1, 0, 0));
        assertEquals(nanWithPayload, core.fp().d(1));
    }

    // ── 4. FMOV imediato: bits já resolvidos, o executor só grava ───────────────

    @Test
    void moveImmediateWritesRawBitsSingle() {
        Aarch64Core core = newCore();
        long bits = Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL;
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64MoveImmediate(false, 3, bits));
        assertEquals(1.0f, core.fp().sFloat(3));
    }

    @Test
    void moveImmediateWritesRawBitsDouble() {
        Aarch64Core core = newCore();
        long bits = Double.doubleToRawLongBits(31.0);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64MoveImmediate(true, 3, bits));
        assertEquals(31.0, core.fp().dDouble(3));
    }

    // ── 5. FCMP: os 4 quadrantes da tabela NZCV, escritos direto em PSTATE ──────

    @Test
    void compareEqualSetsZAndC() {
        assertNzcv(compareSingle(1.0f, 1.0f), false, true, true, false);
    }

    @Test
    void compareLessThanSetsN() {
        assertNzcv(compareSingle(1.0f, 2.0f), true, false, false, false);
    }

    @Test
    void compareGreaterThanSetsCOnly() {
        assertNzcv(compareSingle(2.0f, 1.0f), false, false, true, false);
    }

    @Test
    void compareUnorderedWithNanSetsCAndV() {
        assertNzcv(compareSingle(Float.NaN, 1.0f), false, false, true, true);
    }

    @Test
    void compareWithZeroUsesZeroAsSecondOperand() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, -1.0);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Compare(true, true, false, 0, -1));
        assertNzcv(core, true, false, false, false);
    }

    private static Aarch64Core compareSingle(float vn, float vm) {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, vn);
        core.fp().setSFloat(1, vm);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Compare(false, false, false, 0, 1));
        return core;
    }

    private static void assertNzcv(Aarch64Core core, boolean n, boolean z, boolean c, boolean v) {
        assertEquals(n, core.pstate().negative(), "N");
        assertEquals(z, core.pstate().zero(), "Z");
        assertEquals(c, core.pstate().carry(), "C");
        assertEquals(v, core.pstate().overflow(), "V");
    }

    // ── 6. FCVT: F32→F64 e F64→F32 ───────────────────────────────────────────────

    @Test
    void convertF32ToF64IsExactWidening() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 1.5f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F32_TO_F64, 1, 0));
        assertEquals(1.5, core.fp().dDouble(1));
    }

    @Test
    void convertF64ToF32RoundsCorrectly() {
        Aarch64Core core = newCore();
        // 0.1 em double não é exatamente representável em float: o narrowing precisa arredondar.
        core.fp().setDDouble(0, 0.1);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F64_TO_F32, 1, 0));
        assertEquals((float) 0.1, core.fp().sFloat(1));
    }

    @Test
    void convertF32ToF64PreservesNanQuietness() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, Float.NaN);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F32_TO_F64, 1, 0));
        assertTrue(Double.isNaN(core.fp().dDouble(1)));
    }

    @Test
    void convertF64ToF32PreservesNanQuietness() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, Double.NaN);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F64_TO_F32, 1, 0));
        assertTrue(Float.isNaN(core.fp().sFloat(1)));
    }

    // ── 7. kind() contíguos a partir de 23, sem gap ─────────────────────────────

    @Test
    void newKindsAreContiguousFrom23() {
        assertEquals(23, Ir64Op.Kind.FP64_ALU);
        assertEquals(24, Ir64Op.Kind.FP64_MOVE_IMMEDIATE);
        assertEquals(25, Ir64Op.Kind.FP64_COMPARE);
        assertEquals(26, Ir64Op.Kind.FP64_CONVERT);
    }

    // ── 8. Executor de FP nunca altera o PC ─────────────────────────────────────

    @Test
    void fpOpsNeverChangeProgramCounter() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 1.0f);
        core.fp().setSFloat(1, 2.0f);
        boolean pcChanged = new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ADD, false, 2, 0, 1));
        assertFalse(pcChanged);
    }

    // ── 9. B8.4: NMUL/SQRT/MAX/MIN/MAXNM/MINNM (Fp64Alu estendido) ──────────────

    @Test
    void nmulNegatesTheProductNotAFactor() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 3.0f);
        core.fp().setSFloat(1, 4.0f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.NMUL, false, 2, 0, 1));
        assertEquals(-12.0f, core.fp().sFloat(2));
    }

    @Test
    void sqrtSingleAndDouble() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 16.0f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.SQRT, false, 1, 0, 0));
        assertEquals(4.0f, core.fp().sFloat(1));

        core.fp().setDDouble(2, 81.0);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.SQRT, true, 3, 0, 2));
        assertEquals(9.0, core.fp().dDouble(3));
    }

    @Test
    void maxAndMinPropagateNanFromEitherOperand() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, Float.NaN);
        core.fp().setSFloat(1, 5.0f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MAX, false, 2, 0, 1));
        assertTrue(Float.isNaN(core.fp().sFloat(2)));
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MIN, false, 3, 0, 1));
        assertTrue(Float.isNaN(core.fp().sFloat(3)));
    }

    @Test
    void maxPrefersPositiveZeroMinPrefersNegativeZero() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 0.0f);
        core.fp().setSFloat(1, -0.0f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MAX, false, 2, 0, 1));
        assertEquals(0, Float.floatToRawIntBits(core.fp().sFloat(2)), "FMAX(+0,-0) = +0");
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MIN, false, 3, 0, 1));
        assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(core.fp().sFloat(3)), "FMIN(+0,-0) = -0");
    }

    @Test
    void maxnmAndMinnmReturnTheNumberWhenOnlyOneOperandIsNan() {
        // FPMaxNum/FPMinNum: diferente de MAX/MIN puro, um NaN isolado NÃO contamina o resultado —
        // só quando os DOIS operandos são NaN o resultado é NaN.
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, Double.NaN);
        core.fp().setDDouble(1, 7.0);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MAXNM, true, 2, 0, 1));
        assertEquals(7.0, core.fp().dDouble(2));
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MINNM, true, 3, 0, 1));
        assertEquals(7.0, core.fp().dDouble(3));
    }

    @Test
    void maxnmOfTwoNansIsNan() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, Double.NaN);
        core.fp().setDDouble(1, Double.NaN);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MAXNM, true, 2, 0, 1));
        assertTrue(Double.isNaN(core.fp().dDouble(2)));
    }

    @Test
    void maxnmWithoutNanBehavesLikeNormalMax() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, 3.0);
        core.fp().setDDouble(1, 9.0);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.MAXNM, true, 2, 0, 1));
        assertEquals(9.0, core.fp().dDouble(2));
    }

    // ── 10. B8.4: FMADD/FMSUB/FNMADD/FNMSUB (Fp64MultiplyAdd, arredondamento único) ─────────────

    @Test
    void fmaddIsAPlusNTimesM() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 2.0f); // n
        core.fp().setSFloat(1, 3.0f); // m
        core.fp().setSFloat(2, 1.0f); // a
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64MultiplyAdd(false, false, false, 3, 0, 1, 2));
        assertEquals(7.0f, core.fp().sFloat(3), "a + n*m = 1 + 2*3 = 7");
    }

    @Test
    void fmsubIsAMinusNTimesM() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, 2.0); // n
        core.fp().setDDouble(1, 3.0); // m
        core.fp().setDDouble(2, 10.0); // a
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64MultiplyAdd(true, false, true, 3, 0, 1, 2));
        assertEquals(4.0, core.fp().dDouble(3), "a - n*m = 10 - 2*3 = 4");
    }

    @Test
    void fnmaddIsNegatedAPlusNTimesM() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 2.0f); // n
        core.fp().setSFloat(1, 3.0f); // m
        core.fp().setSFloat(2, 1.0f); // a
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64MultiplyAdd(false, true, true, 3, 0, 1, 2));
        assertEquals(-7.0f, core.fp().sFloat(3), "-(a + n*m) = -(1 + 2*3) = -7");
    }

    @Test
    void fnmsubIsNTimesMMinusA() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 2.0f); // n
        core.fp().setSFloat(1, 3.0f); // m
        core.fp().setSFloat(2, 1.0f); // a
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64MultiplyAdd(false, true, false, 3, 0, 1, 2));
        assertEquals(5.0f, core.fp().sFloat(3), "n*m - a = 2*3 - 1 = 5");
    }

    @Test
    void fusedMultiplyAddRoundsOnceUnlikeSeparateMulThenAdd() {
        // Vetor clássico de arredondamento único: a soma separada (mul arredondado, depois add
        // arredondado) perde precisão que a fma (arredondamento ÚNICO) preserva.
        Aarch64Core core = newCore();
        double n = 1.0 + Math.ulp(1.0);
        double m = 1.0 - Math.ulp(1.0);
        double a = -1.0;
        core.fp().setDDouble(0, n);
        core.fp().setDDouble(1, m);
        core.fp().setDDouble(2, a);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64MultiplyAdd(true, false, false, 3, 0, 1, 2));
        assertEquals(Math.fma(n, m, a), core.fp().dDouble(3));
        assertNotEquals((n * m) + a, core.fp().dDouble(3), "fma real arredonda diferente de mul+add separados");
    }

    @Test
    void fmaddPreservesNanSignViaBitFlipNotArithmeticNegation() {
        // Mesma armadilha de NEG/ABS: negar o BIT de sinal de um NaN (não `0-x`) preserva o
        // payload — aqui verificado indiretamente checando que o bit de sinal do NaN de `va`
        // aparece invertido no resultado quando `n*m` também é NaN (soma de dois NaN preserva o
        // sinal do primeiro operando na fma real do Java, mesma convenção do hardware ARM).
        int nanWithPayload = 0x7FC00001;
        Aarch64Core core = newCore();
        core.fp().setS(0, nanWithPayload); // n = NaN
        core.fp().setSFloat(1, 1.0f); // m
        core.fp().setSFloat(2, 1.0f); // a
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64MultiplyAdd(false, false, false, 3, 0, 1, 2));
        assertTrue(Float.isNaN(core.fp().sFloat(3)));
    }
}
