package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.FpRoundingMode;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes da IR de VFP (B3.4): vetores concretos por operação, executados no interpretador (o
/// oráculo, G1). Decode fica em B3.5 — os `IrOp` são montados à mão, mesmo padrão dos testes de
/// executor de B1.x.
class IrVfpExecutorTest {
    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    private static IrBlockExecutor newExecutor() {
        return new IrBlockExecutor(ArmArchitecture.ARMV6K);
    }

    // ── 1. ADD/SUB/MUL/DIV single e double ──────────────────────────────────────

    @Test
    void addSingleRoundsLikeIeee754Float() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 0.1f);
        core.vfp().setSFloat(1, 0.2f);
        IrOp.VfpAlu add = new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 0, 1, Condition.AL);
        newExecutor().executeOp(core, add, 0);
        // 0.1f + 0.2f em ponto flutuante simples IEEE 754 não é exatamente 0.3f.
        assertEquals(0x3E99999A, Float.floatToRawIntBits(0.1f + 0.2f));
        assertEquals(Float.floatToRawIntBits(0.1f + 0.2f), core.vfp().s(2));
    }

    @Test
    void subMulDivDoublePrecision() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 5.0);
        core.vfp().setDDouble(1, 2.0);

        ArmCore subCore = newCore();
        subCore.vfp().setDDouble(0, 5.0);
        subCore.vfp().setDDouble(1, 2.0);
        newExecutor().executeOp(subCore, new IrOp.VfpAlu(IrOp.VfpOperation.SUB, true, 2, 0, 1, Condition.AL), 0);
        assertEquals(3.0, subCore.vfp().dDouble(2));

        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MUL, true, 3, 0, 1, Condition.AL), 0);
        assertEquals(10.0, core.vfp().dDouble(3));

        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.DIV, true, 4, 0, 1, Condition.AL), 0);
        assertEquals(2.5, core.vfp().dDouble(4));
    }

    // ── 2. MLA/MLS NÃO fundido ───────────────────────────────────────────────────

    @Test
    void mlaIsNotFusedWithMathFma() {
        float a = 1.0000001f;
        float b = 1.0000001f;
        float c = -1.0000002f;

        // Resultado esperado: duas operações float arredondadas separadamente (NÃO Math.fma).
        float unfused = c + (a * b);
        float fused = Math.fma(a, b, c);
        assertNotEquals(Float.floatToRawIntBits(fused), Float.floatToRawIntBits(unfused),
                "vetor de teste precisa distinguir FMA de duas operações separadas");

        ArmCore core = newCore();
        core.vfp().setSFloat(0, a); // vn
        core.vfp().setSFloat(1, b); // vm
        core.vfp().setSFloat(2, c); // vd (acumulador de entrada)
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MLA, false, 2, 0, 1, Condition.AL), 0);

        assertEquals(Float.floatToRawIntBits(unfused), core.vfp().s(2));
        assertNotEquals(Float.floatToRawIntBits(fused), core.vfp().s(2));
    }

    @Test
    void mlsSubtractsTheUnfusedProduct() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 2.0);  // vn
        core.vfp().setDDouble(1, 3.0);  // vm
        core.vfp().setDDouble(2, 10.0); // vd
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MLS, true, 2, 0, 1, Condition.AL), 0);
        assertEquals(4.0, core.vfp().dDouble(2)); // 10 - (2*3)
    }

    /// `VNMLS: vd = -vd + (vn * vm)`. **O negado é o ACUMULADOR, não o produto** — é o que
    /// distingue VNMLS de VMLS, e trocar os dois dá o mesmo resultado só quando `vd` e o produto
    /// têm o mesmo módulo. Os valores aqui (10 e 6) são deliberadamente diferentes.
    @Test
    void nmlsNegatesTheAccumulatorNotTheProduct() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 2.0);  // vn
        core.vfp().setDDouble(1, 3.0);  // vm
        core.vfp().setDDouble(2, 10.0); // vd
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.NMLS, true, 2, 0, 1, Condition.AL), 0);
        assertEquals(-4.0, core.vfp().dDouble(2)); // -10 + (2*3), NÃO 10 - (2*3) = 4
    }

    @Test
    void nmlaNegatesAccumulatorAndProduct() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 2.0);
        core.vfp().setDDouble(1, 3.0);
        core.vfp().setDDouble(2, 10.0);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.NMLA, true, 2, 0, 1, Condition.AL), 0);
        assertEquals(-16.0, core.vfp().dDouble(2)); // -10 - (2*3)
    }

    @Test
    void nmlsSinglePrecisionUsesTheSameRule() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 2.0f);
        core.vfp().setSFloat(1, 3.0f);
        core.vfp().setSFloat(2, 10.0f);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.NMLS, false, 2, 0, 1, Condition.AL), 0);
        assertEquals(-4.0f, core.vfp().sFloat(2));
    }

    // ── 3. NEG/ABS preservam payload de NaN via bit de sinal ────────────────────

    @Test
    void negFlipsOnlyTheSignBitPreservingNanPayload() {
        int nanWithPayload = 0x7FC00001; // NaN quieto com payload não-zero
        ArmCore core = newCore();
        core.vfp().setS(0, nanWithPayload);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.NEG, false, 1, 0, 0, Condition.AL), 0);
        assertEquals(nanWithPayload ^ Integer.MIN_VALUE, core.vfp().s(1));
    }

    @Test
    void negOfPositiveZeroIsNegativeZeroBitwise() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 0.0f);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.NEG, false, 1, 0, 0, Condition.AL), 0);
        assertEquals(Integer.MIN_VALUE, core.vfp().s(1)); // bits de -0.0f
    }

    @Test
    void absClearsTheSignBitPreservingNanPayload() {
        long nanWithPayload = 0xFFF8_0000_0000_0001L; // NaN de sinal negativo, payload não-zero
        ArmCore core = newCore();
        core.vfp().setD(0, nanWithPayload);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.ABS, true, 1, 0, 0, Condition.AL), 0);
        assertEquals(nanWithPayload & Long.MAX_VALUE, core.vfp().d(1));
    }

    // ── 4. SQRT ───────────────────────────────────────────────────────────────

    @Test
    void sqrtOfNegativeIsNaN() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, -1.0);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, true, 1, 0, 0, Condition.AL), 0);
        assertTrue(Double.isNaN(core.vfp().dDouble(1)));
    }

    @Test
    void sqrtSingleIsCorrectlyRoundedBitExact() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 2.0f);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, false, 1, 0, 0, Condition.AL), 0);
        assertEquals(0x3FB504F3, core.vfp().s(1));
    }

    // ── 5. VCMP: os 4 quadrantes ─────────────────────────────────────────────────

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

    private static ArmCore compareSingle(float vd, float vm) {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, vd);
        core.vfp().setSFloat(1, vm);
        newExecutor().executeOp(core,
                new IrOp.VfpCompare(false, false, false, 0, 1, Condition.AL), 0);
        return core;
    }

    private static void assertNzcv(ArmCore core, boolean n, boolean z, boolean c, boolean v) {
        FpscrRegister fpscr = core.fpscr();
        assertEquals(n, fpscr.n(), "N");
        assertEquals(z, fpscr.z(), "Z");
        assertEquals(c, fpscr.c(), "C");
        assertEquals(v, fpscr.v(), "V");
    }

    // ── 6. VCVT: cada membro do enum ─────────────────────────────────────────────

    @Test
    void convertF32ToF64IsExact() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 1.5f);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_F64, 1, 0, Condition.AL), 0);
        assertEquals(1.5, core.vfp().dDouble(1));
    }

    @Test
    void convertF64ToF32Rounds() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 1.5);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_F32, 1, 0, Condition.AL), 0);
        assertEquals(1.5f, core.vfp().sFloat(1));
    }

    @Test
    void convertS32ToF32() {
        ArmCore core = newCore();
        core.vfp().setS(0, -5);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F32, 1, 0, Condition.AL), 0);
        assertEquals(-5.0f, core.vfp().sFloat(1));
    }

    @Test
    void convertS32ToF64() {
        ArmCore core = newCore();
        core.vfp().setS(0, -5);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F64, 1, 0, Condition.AL), 0);
        assertEquals(-5.0, core.vfp().dDouble(1));
    }

    @Test
    void convertU32ToF32TreatsBitsAsUnsigned() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0xFFFFFFFF); // -1 assinado == 4294967295 sem sinal
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F32, 1, 0, Condition.AL), 0);
        assertEquals(4294967295.0f, core.vfp().sFloat(1));
    }

    @Test
    void convertU32ToF64TreatsBitsAsUnsigned() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0xFFFFFFFF);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F64, 1, 0, Condition.AL), 0);
        assertEquals(4294967295.0, core.vfp().dDouble(1));
    }

    @Test
    void convertF32ToS32NanBecomesZero() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, Float.NaN);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_S32, 1, 0, Condition.AL), 0);
        assertEquals(0, core.vfp().s(1));
    }

    @Test
    void convertF32ToS32SaturatesAtMaxValue() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 1e30f);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_S32, 1, 0, Condition.AL), 0);
        assertEquals(Integer.MAX_VALUE, core.vfp().s(1));
    }

    @Test
    void convertF64ToS32TruncatesTowardZero() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 2.9);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_S32, 1, 0, Condition.AL), 0);
        assertEquals(2, core.vfp().s(1));
    }

    @Test
    void convertF32ToU32NegativeBecomesZero() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, -1.5f);
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_U32, 1, 0, Condition.AL), 0);
        assertEquals(0, core.vfp().s(1));
    }

    @Test
    void convertF64ToU32SaturatesAtAllOnes() {
        ArmCore core = newCore();
        core.vfp().setDDouble(0, 4294967040.0); // 0xFFFFFF00, exato em double e float
        newExecutor().executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_U32, 1, 0, Condition.AL), 0);
        assertEquals(0xFFFFFF00, core.vfp().s(1));
    }

    // ── 7. Load/store: double ida-e-volta, VLDM/VSTM IA e DB ────────────────────

    @Test
    void doubleRoundTripsThroughMemoryLowWordAtLowerAddress() {
        ArmCore core = newCore();
        core.setRegister(0, 16);
        core.vfp().setDDouble(1, 3.5);
        IrBlockExecutor executor = newExecutor();

        executor.executeOp(core, new IrOp.VfpStore(true, 1, 0, -1, 8, Condition.AL), 0);
        // Metade baixa (bits 31:0 de 3.5) no endereço menor (16+8=24), alta em 24+4=28.
        long bits = Double.doubleToRawLongBits(3.5);
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        assertEquals((int) bits, memory.read32(24));
        assertEquals((int) (bits >>> 32), memory.read32(28));

        executor.executeOp(core, new IrOp.VfpLoad(true, 2, 0, -1, 8, Condition.AL), 0);
        assertEquals(3.5, core.vfp().dDouble(2));
    }

    @Test
    void vldmIaLoadsConsecutiveSingleRegistersAndWritesBack() {
        ArmCore core = newCore();
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        core.setRegister(0, 0);
        memory.put32(0, Float.floatToRawIntBits(1.0f));
        memory.put32(4, Float.floatToRawIntBits(2.0f));
        memory.put32(8, Float.floatToRawIntBits(3.0f));

        newExecutor().executeOp(core,
                new IrOp.VfpMultipleTransfer(true, false, 0, -1, 4, 3, true, false, Condition.AL), 0);

        assertEquals(1.0f, core.vfp().sFloat(4));
        assertEquals(2.0f, core.vfp().sFloat(5));
        assertEquals(3.0f, core.vfp().sFloat(6));
        assertEquals(12, core.register(0)); // writeback IA: base += 3*4
    }

    @Test
    void vstmDbStoresConsecutiveDoubleRegistersDecrementingFirst() {
        ArmCore core = newCore();
        core.setRegister(13, 32); // SP
        core.vfp().setDDouble(0, 1.0);
        core.vfp().setDDouble(1, 2.0);

        newExecutor().executeOp(core,
                new IrOp.VfpMultipleTransfer(false, true, 13, -1, 0, 2, true, true, Condition.AL), 0);

        assertEquals(16, core.register(13)); // writeback DB: base -= 2*8 = 16
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        assertEquals(1.0, Double.longBitsToDouble(
                (memory.read32(16) & 0xFFFF_FFFFL) | (((long) memory.read32(20)) << 32)));
        assertEquals(2.0, Double.longBitsToDouble(
                (memory.read32(24) & 0xFFFF_FFFFL) | (((long) memory.read32(28)) << 32)));
    }

    // ── 8. VMRS APSR_nzcv ─────────────────────────────────────────────────────────

    @Test
    void vmrsApsrNzcvCopiesFpscrFlagsWithoutTouchingOtherCpsrBits() {
        ArmCore core = newCore();
        core.fpscr().setValue(FpscrRegister.NEGATIVE_FLAG | FpscrRegister.OVERFLOW_FLAG);
        core.cpsr().setGe(0b1010);
        core.cpsr().setSaturation(true);
        core.cpsr().setThumbMode(true); // isThumbMode() checado depois

        newExecutor().executeOp(core, new IrOp.VfpSystemTransfer(true, 15, Condition.AL), 0);

        assertTrue(core.cpsr().negative());
        assertFalse(core.cpsr().zero());
        assertFalse(core.cpsr().carry());
        assertTrue(core.cpsr().overflow());
        // Q/GE/modo Thumb preservados — só NZCV foi tocado.
        CpsrRegister cpsr = core.cpsr();
        assertEquals(0b1010, ge(cpsr));
        assertTrue(cpsr.isThumbMode());
    }

    private static int ge(CpsrRegister cpsr) {
        // GE ocupa bits[19:16] do CPSR (ARMv6 SIMD); reconstituído do valor bruto para o teste.
        return (cpsr.get() >>> 16) & 0xF;
    }

    @Test
    void vmrsRegularCopiesFpscrValueIntoRegister() {
        ArmCore core = newCore();
        core.fpscr().setValue(FpscrRegister.ZERO_FLAG);
        newExecutor().executeOp(core, new IrOp.VfpSystemTransfer(true, 3, Condition.AL), 0);
        assertEquals(FpscrRegister.ZERO_FLAG, core.register(3));
    }

    @Test
    void vmsrWritesRegisterIntoFpscr() {
        ArmCore core = newCore();
        core.setRegister(3, FpscrRegister.CARRY_FLAG);
        newExecutor().executeOp(core, new IrOp.VfpSystemTransfer(false, 3, Condition.AL), 0);
        assertEquals(FpscrRegister.CARRY_FLAG, core.fpscr().value());
    }

    // ── 9. Condição falsa: pula a op, mas Cycle/Fetch são incondicionais (G4) ───

    @Test
    void falseConditionSkipsTheVfpOpButStillConsumesCycleAndFetch() {
        ArmCore core = newCore();
        core.cpsr().setNzcv(false, false, false, false); // Z=0 → EQ falso
        core.vfp().setSFloat(0, 1.0f);
        core.vfp().setSFloat(1, 2.0f);
        core.vfp().setSFloat(2, 9.0f); // sentinela: não deve ser sobrescrito
        core.fpscr().setValue(FpscrRegister.ZERO_FLAG); // sentinela

        IrOp.VfpAlu skippedAdd = new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 0, 1, Condition.EQ);
        IrBlock block = new IrBlock(0, 4, List.of(new IrOp.Fetch(0, 4), skippedAdd, new IrOp.Cycle(3)));

        int cycles = newExecutor().execute(block, core);

        assertEquals(9.0f, core.vfp().sFloat(2)); // banco intacto
        assertEquals(FpscrRegister.ZERO_FLAG, core.fpscr().value()); // FPSCR intacto
        assertEquals(3, cycles); // Cycle consumido mesmo com a op pulada
        assertEquals(4, core.programCounter()); // Fetch/avanço de PC ocorreram normalmente
    }

    // ── 10. RMode (task B3.8) — FPSCR.RMODE afeta o resultado de VDIV ──────────

    @Test
    void divSingleRespectsRoundTowardMinusInfinity() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 1.0f);
        core.vfp().setSFloat(1, 3.0f);
        core.fpscr().setValue(0b10 << FpscrRegister.ROUNDING_MODE_SHIFT); // RM
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.DIV, false, 2, 0, 1, Condition.AL), 0);
        // 1.0f/3.0f round-to-nearest = 0x3eaaaaab; o exato fica abaixo -> RM tem que arredondar
        // para baixo (vizinho de baixo), diferente do default (ver DirectedFpRoundingTest).
        assertEquals(0x3eaaaaaa, core.vfp().s(2));
    }

    @Test
    void divSingleWithDefaultRoundingModeIsUnchangedFromBeforeB38() {
        // G3: comportamento default (RMode=round-to-nearest) não muda.
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 1.0f);
        core.vfp().setSFloat(1, 3.0f);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.DIV, false, 2, 0, 1, Condition.AL), 0);
        assertEquals(Float.floatToRawIntBits(1.0f / 3.0f), core.vfp().s(2));
    }

    @Test
    void systemTransferNoLongerThrowsForNonDefaultFpscrBits() {
        // A escrita direta de VMSR com RMode/FZ/LEN/STRIDE não-default não lança mais
        // UnsupportedOperationException (decisão nº 3 do B3 revisitada pela B3.8).
        ArmCore core = newCore();
        core.setRegister(0, FpscrRegister.ROUNDING_MODE_MASK | FpscrRegister.FLUSH_TO_ZERO_FLAG
                | FpscrRegister.LEN_MASK | FpscrRegister.STRIDE_MASK);
        newExecutor().executeOp(core, new IrOp.VfpSystemTransfer(false, 0, Condition.AL), 0);
        assertEquals(FpRoundingMode.ROUND_TOWARD_ZERO, core.fpscr().roundingMode());
        assertTrue(core.fpscr().flushToZero());
    }

    // ── 11. FZ (flush-to-zero, task B3.8) ───────────────────────────────────────

    @Test
    void flushToZeroFlushesSubnormalResultToSignedZero() {
        ArmCore core = newCore();
        core.fpscr().setValue(FpscrRegister.FLUSH_TO_ZERO_FLAG);
        core.vfp().setSFloat(0, Float.MIN_VALUE); // subnormal
        core.vfp().setSFloat(1, -2.0f);
        // MIN_VALUE * -2 continua subnormal (magnitude só dobra) -> resultado exato != 0, mas FZ
        // ainda assim reduz para zero com o sinal do resultado matemático (negativo).
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MUL, false, 2, 0, 1, Condition.AL), 0);
        assertEquals(-0.0f, core.vfp().sFloat(2));
        assertTrue(1 / core.vfp().sFloat(2) < 0, "zero deveria ter sinal negativo (flush preserva o sinal)");
    }

    @Test
    void flushToZeroFlushesSubnormalInputBeforeComparing() {
        ArmCore core = newCore();
        core.fpscr().setValue(FpscrRegister.FLUSH_TO_ZERO_FLAG);
        core.vfp().setSFloat(0, Float.MIN_VALUE); // subnormal, seria >0 sem FZ
        IrOp.VfpCompare cmp = new IrOp.VfpCompare(false, true, false, 0, 0, Condition.AL); // VCMP Vd, #0
        newExecutor().executeOp(core, cmp, 0);
        assertTrue(core.fpscr().z(), "entrada subnormal deveria ser tratada como zero (denormal-as-zero)");
    }

    @Test
    void withoutFlushToZeroSubnormalResultIsPreservedAsBeforeB38() {
        // G3: comportamento default (FZ=0) não muda — subnormal sobrevive intacto.
        ArmCore core = newCore();
        core.vfp().setSFloat(0, Float.MIN_VALUE);
        core.vfp().setSFloat(1, 1.0f);
        newExecutor().executeOp(core, new IrOp.VfpAlu(IrOp.VfpOperation.MUL, false, 2, 0, 1, Condition.AL), 0);
        assertEquals(Float.MIN_VALUE, core.vfp().sFloat(2));
    }
}
