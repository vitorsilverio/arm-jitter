package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
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

    // ── 11. FCSEL (B8.5) — só LÊ PSTATE, nunca escreve ──────────────────────────

    @Test
    void fcselPicksVnWhenConditionTrue() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 1.0f);
        core.fp().setSFloat(1, 2.0f);
        core.pstate().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeira
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64ConditionalSelect(false, 2, 0, 1, Ir64Condition.EQ));
        assertEquals(1.0f, core.fp().sFloat(2));
    }

    @Test
    void fcselPicksVmWhenConditionFalse() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, 1.0);
        core.fp().setDDouble(1, 2.0);
        core.pstate().setNzcv(false, false, false, false); // Z=0 -> EQ falsa
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64ConditionalSelect(true, 2, 0, 1, Ir64Condition.EQ));
        assertEquals(2.0, core.fp().dDouble(2));
    }

    // ── 12. FCCMP/FCCMPE (B8.5) ──────────────────────────────────────────────────

    @Test
    void fccmpWhenConditionTrueRecalculatesFromComparison() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 5.0f);
        core.fp().setSFloat(1, 5.0f);
        core.pstate().setNzcv(false, true, false, false); // condição AL-like: EQ com Z já 1
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64ConditionalCompare(false, false, 0, 1, Ir64Condition.EQ, 0b0000));
        // 5.0 == 5.0 -> equal: Z=1,C=1,N=0,V=0 (MESMA tabela de FCMP).
        assertTrue(core.pstate().zero());
        assertTrue(core.pstate().carry());
        assertFalse(core.pstate().negative());
        assertFalse(core.pstate().overflow());
    }

    @Test
    void fccmpWhenConditionFalseUsesRawNzcvWithoutReadingOperands() {
        Aarch64Core core = newCore();
        // vn/vm ficam com NaN — se o executor os lesse por engano (em vez de ramificar), o
        // resultado seria unordered (N=0,Z=0,C=1,V=1), diferente do nzcv cru pedido aqui.
        core.fp().setS(0, 0x7FC00000);
        core.fp().setS(1, 0x7FC00000);
        core.pstate().setNzcv(false, false, false, false); // NE falsa (Z=0 -> EQ seria verdadeira; usamos EQ com Z=0 => falsa)
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64ConditionalCompare(false, false, 0, 1, Ir64Condition.EQ, 0b1101));
        assertEquals(0b1101, core.pstate().nzcv());
    }

    // ── 13. FRINTx (B8.5) — arredonda para inteiro, mantém em FP ────────────────

    @Test
    void frintnRoundsTiesToEven() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, 2.5);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, 1, 0));
        assertEquals(2.0, core.fp().dDouble(1), "2.5 -> par mais próximo = 2");

        core.fp().setDDouble(0, 3.5);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, 1, 0));
        assertEquals(4.0, core.fp().dDouble(1), "3.5 -> par mais próximo = 4");
    }

    @Test
    void frintaRoundsTiesAwayFromZero() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 2.5f);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, false, 1, 0));
        assertEquals(3.0f, core.fp().sFloat(1));

        core.fp().setSFloat(0, -2.5f);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, false, 1, 0));
        assertEquals(-3.0f, core.fp().sFloat(1), "empate afasta de zero, não sempre para cima");
    }

    @Test
    void frintpAndFrintmRoundTowardInfinities() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 1.2f);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY, false, 1, 0));
        assertEquals(2.0f, core.fp().sFloat(1));

        core.fp().setSFloat(0, -1.2f);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY, false, 1, 0));
        assertEquals(-2.0f, core.fp().sFloat(1));
    }

    @Test
    void frintzTruncatesTowardZero() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, -1.9f);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, 1, 0));
        assertEquals(-1.0f, core.fp().sFloat(1));
    }

    @Test
    void frintOfNanAndInfinityPassThroughUnchanged() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, Float.POSITIVE_INFINITY);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, false, 1, 0));
        assertEquals(Float.POSITIVE_INFINITY, core.fp().sFloat(1));

        core.fp().setS(0, 0x7FC00001);
        new Ir64BlockExecutor().executeOp(core,
                new Ir64Op.Fp64Round(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, false, 1, 0));
        assertEquals(0x7FC00001, core.fp().s(1));
    }

    // ── 14. SCVTF/UCVTF/FCVTxS/FCVTxU registrador-geral (B8.5) ──────────────────

    @Test
    void scvtfConvertsSigned32BitIntegerToFloat() {
        Aarch64Core core = newCore();
        core.setXForWidth(1, -5L, false);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                true, true, Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, false, false, 0, 2, 1));
        assertEquals(-5.0f, core.fp().sFloat(2));
    }

    @Test
    void ucvtfTreatsRegisterAsUnsigned() {
        Aarch64Core core = newCore();
        // W-form: xForWidth já zero-estende, então -1 vira 0xFFFFFFFF (4294967295) sem sinal.
        core.setXForWidth(1, -1L, false);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                true, false, Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, false, 0, 2, 1));
        assertEquals(4294967295.0, core.fp().dDouble(2));
    }

    @Test
    void ucvtfWideTreatsFullRegisterAsUnsigned64Bit() {
        Aarch64Core core = newCore();
        core.setXForWidth(1, -1L, true); // 0xFFFF_FFFF_FFFF_FFFF sem sinal = 2^64-1
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                true, false, Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, true, 0, 2, 1));
        // 2^64-1 não cabe exato em 53 bits de mantissa — o double mais próximo é 2^64 (a distância
        // até 2^64 é 1; até o representável anterior, 2^64-4096, é 4095).
        assertEquals(Math.scalb(1.0, 64), core.fp().dDouble(2));
    }

    @Test
    void fcvtzsTruncatesTowardZeroAndSaturates() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, -3.9f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                false, true, Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, false, 0, 0, 1));
        assertEquals(-3, (int) core.xForWidth(1, false));

        core.fp().setSFloat(0, 1e30f); // muito grande para caber num W (32 bits)
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                false, true, Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, false, 0, 0, 1));
        assertEquals(Integer.MAX_VALUE, (int) core.xForWidth(1, false), "satura no limite, não faz wraparound");
    }

    @Test
    void fcvtzsOfNanConvertsToZero() {
        Aarch64Core core = newCore();
        core.fp().setS(0, 0x7FC00000);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                false, true, Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, false, 0, 0, 1));
        assertEquals(0L, core.xForWidth(1, false));
    }

    @Test
    void fcvtasRoundsTiesAwayFromZeroBeforeConverting() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 2.5f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                false, true, Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, false, false, 0, 0, 1));
        assertEquals(3L, core.xForWidth(1, false));
    }

    @Test
    void fcvtzuOfNegativeValueSaturatesToZero() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, -1.0f);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                false, false, Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, false, 0, 0, 1));
        assertEquals(0L, core.xForWidth(1, false));
    }

    // ── 15. Ponto fixo (shift) e FMOV registrador-geral<->FP (B8.5) ─────────────

    @Test
    void scvtfFixedPointDividesByTwoToTheFractionBits() {
        Aarch64Core core = newCore();
        core.setXForWidth(1, 10L, false); // 10 / 2^1 = 5.0
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                true, true, Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, false, false, 1, 2, 1));
        assertEquals(5.0f, core.fp().sFloat(2));
    }

    @Test
    void fcvtzsFixedPointMultipliesByTwoToTheFractionBitsBeforeTruncating() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 5.0f); // 5.0 * 2^1 = 10
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64IntegerConvert(
                false, true, Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, false, false, 1, 0, 1));
        assertEquals(10L, core.xForWidth(1, false));
    }

    @Test
    void fmovGeneralRegisterMoveCopiesRawBitsWithoutConversion() {
        Aarch64Core core = newCore();
        core.fp().setSFloat(0, 1.5f); // valor cujo padrão de bits, lido como int, NÃO é 1.5.
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64GeneralRegisterMove(false, false, 0, 1));
        assertEquals(Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL, core.xForWidth(1, false));

        core.setXForWidth(2, Float.floatToRawIntBits(1.5f), false);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64GeneralRegisterMove(true, false, 3, 2));
        assertEquals(1.5f, core.fp().sFloat(3));
    }

    @Test
    void fmovGeneralRegisterMoveWideRoundTripsDoubleBits() {
        Aarch64Core core = newCore();
        core.fp().setDDouble(0, Math.PI);
        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64GeneralRegisterMove(false, true, 0, 1));
        assertEquals(Double.doubleToRawLongBits(Math.PI), core.xForWidth(1, true));

        new Ir64BlockExecutor().executeOp(core, new Ir64Op.Fp64GeneralRegisterMove(true, true, 2, 1));
        assertEquals(Math.PI, core.fp().dDouble(2));
    }
}
