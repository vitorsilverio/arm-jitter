package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.FpRoundingMode;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// B22.7 — resíduos VFP do ARMv8-A de 32 bits: `VRINTR`/`VRINTZ`/`VRINTX` (`sp`/`dp`/`hp`, espaço
/// condicional), `VCVTR` (`rz=0`), `VCVTB`/`VCVTT` de meia precisão + `BF16` e `VJCVT`. Oráculo: QEMU
/// `target/arm/tcg/translate-vfp.c` (`trans_VRINTR`/`trans_VRINTZ`/`trans_VRINTX`, `trans_VCVT_*`,
/// `trans_VJCVT`) e `vfp_helper.c` (`HELPER(vjcvt)`).
class VfpB227ResidualsTest {
    private static final int COND_AL = 0xE;
    private static final int COND_NE = 0x1;

    private static final int SIZE_HALF = 0x9;
    private static final int SIZE_SINGLE = 0xA;
    private static final int SIZE_DOUBLE = 0xB;

    private static final int OPC2_CVT_FROM_HALF = 0x2;
    private static final int OPC2_CVT_TO_HALF = 0x3;
    private static final int OPC2_VRINTR_OR_Z = 0x6;
    private static final int OPC2_VRINTX = 0x7;
    private static final int OPC2_VJCVT = 0x9;
    private static final int OPC2_VCVT_TO_U32 = 0xC;
    private static final int OPC2_VCVT_TO_S32 = 0xD;

    private static final int HALF_2_5 = 0x4100;
    private static final int HALF_2_0 = 0x4000;
    private static final int HALF_3_0 = 0x4200;
    private static final int HALF_1_0 = 0x3C00;
    private static final int HALF_1_5 = 0x3E00;

    // ── Arquiteturas de teste ──────────────────────────────────────────────────────────────────

    private static final ArmArchitecture VFP_BASE_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K_THUMB2, "ARMv7-TestB227Vfp", ArmFeature.VFPV2);

    private static ArmArchitecture withFeatures(String name, ArmFeature... extra) {
        ArmArchitecture features = ArmArchitecture.extending(VFP_BASE_FEATURES, name, extra);
        return features.withDecoderExtensions(List.of(new VfpDecoder(features), new CoprocessorDecoder()))
                .withThumb32DecoderExtensions(thumb32(features));
    }

    private static List<DecoderExtension> thumb32(ArmArchitecture features) {
        return List.of(new Thumb2VfpDecoder(features), new Thumb2CoprocessorDecoder());
    }

    /// VFP puro, sem nenhuma das features novas (nem `ARMV8_FP`).
    private static final ArmArchitecture VFP_ONLY = withFeatures("B227-VfpOnly");
    /// Só a extensão VFPv3 de conversão de meia precisão.
    private static final ArmArchitecture HALF_CONV_ONLY =
            withFeatures("B227-HalfConv", ArmFeature.HALF_PRECISION_FP);
    /// Só `FEAT_BF16` (sem meia precisão nenhuma).
    private static final ArmArchitecture BF16_ONLY = withFeatures("B227-Bf16", ArmFeature.BFLOAT16);
    /// ARMv8.0 de 32 bits (`ARMV8_FP` + `FP16_ARITHMETIC`), SEM `FEAT_JSCVT`/`FEAT_BF16`.
    private static final ArmArchitecture V80 = ArmArchitecture.ARMV8A_32;
    /// ARMv8.6 de 32 bits: `V80` + `FEAT_JSCVT` + `FEAT_BF16`.
    private static final ArmArchitecture V86 = ArmArchitecture.ARMV8_6A_32;

    // ── Encoder manual (`vfp.decode`, espaço condicional) ─────────────────────────────────────

    /// Campo de registrador `S`: `Vd:D` — `nibble = s >> 1`, `ext = s & 1`.
    private static int sField(int s) {
        return (s >> 1) | ((s & 1) << 4);
    }

    /// Campo de registrador `D`: `D:Vd` — `nibble = d & 15`, `ext = d >> 4`.
    private static int dField(int d) {
        return (d & 0xF) | ((d >> 4) << 4);
    }

    /// `---- 1110 1.11 opc2 vd size bit7 1 . 0 vm` (família de 2 operandos/compare/convert).
    private static int word(int cond, int opc2, int size, boolean bit7, int vdField, int vmField) {
        int word = (cond << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= ((vdField >> 4) & 1) << 22;
        word |= opc2 << 16;
        word |= (vdField & 0xF) << 12;
        word |= size << 8;
        word |= (bit7 ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= ((vmField >> 4) & 1) << 5;
        word |= vmField & 0xF;
        return word;
    }

    private static DecodedInstruction decodeArm(ArmArchitecture arch, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(arch).decode(memory, 0);
    }

    private static DecodedInstruction decodeThumb32(ArmArchitecture arch, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, word >>> 16);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(arch).decode(memory, 0);
    }

    private static IrOp lift(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static ArmCore newCore(ArmArchitecture arch) {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), arch);
    }

    /// Decodifica, eleva e executa `word` sob `arch`, depois de `setup` preparar o core.
    private static ArmCore run(ArmArchitecture arch, int word, Consumer<ArmCore> setup) {
        ArmCore core = newCore(arch);
        setup.accept(core);
        DecodedInstruction decoded = decodeArm(arch, word);
        assertNotEquals(InstructionKind.UNIMPLEMENTED, decoded.kind(), "decodificou como UNIMPLEMENTED");
        new IrBlockExecutor(arch).executeOp(core, lift(decoded), 0);
        return core;
    }

    private static Consumer<ArmCore> fpscrMode(FpRoundingMode mode) {
        return core -> core.fpscr().setValue(mode.ordinal() << FpscrRegister.ROUNDING_MODE_SHIFT);
    }

    private static InstructionKind kindOf(ArmArchitecture arch, int word) {
        return decodeArm(arch, word).kind();
    }

    // ── 1. VRINTZ / VRINTR / VRINTX sp/dp: decode + lift ───────────────────────────────────────

    @Test
    void vrintzDecodesToVfpRoundTowardZeroNeverFpscr() {
        DecodedInstruction decoded = decodeArm(V80, word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_SINGLE, true, sField(0), sField(1)));
        assertEquals(InstructionKind.VFP_ROUND, decoded.kind());
        assertEquals(new IrOp.VfpRound(dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.RoundingMode.TOWARD_ZERO,
                false, 0, 1, Condition.AL), lift(decoded));
    }

    @Test
    void vrintrAndVrintxDecodeToVfpRoundWithNullDirectionMeaningFpscr() {
        IrOp vrintr = lift(decodeArm(V80, word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_SINGLE, false, sField(2), sField(3))));
        IrOp vrintx = lift(decodeArm(V80, word(COND_AL, OPC2_VRINTX, SIZE_DOUBLE, false, dField(4), dField(5))));
        assertEquals(new IrOp.VfpRound(null, false, 2, 3, Condition.AL), vrintr);
        assertEquals(new IrOp.VfpRound(null, true, 4, 5, Condition.AL), vrintx);
    }

    @Test
    void vrintDoubleFormCarriesDoublePrecisionAndDRegisters() {
        IrOp op = lift(decodeArm(V80, word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_DOUBLE, true, dField(3), dField(7))));
        assertEquals(new IrOp.VfpRound(dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.RoundingMode.TOWARD_ZERO,
                true, 3, 7, Condition.AL), op);
    }

    // ── 2. VRINT*: execução ────────────────────────────────────────────────────────────────────

    @Test
    void vrintzTruncatesRegardlessOfFpscrRoundingMode() {
        int word = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_SINGLE, true, sField(0), sField(1));
        for (FpRoundingMode mode : FpRoundingMode.values()) {
            ArmCore core = run(V80, word, fpscrMode(mode).andThen(c -> c.vfp().setSFloat(1, -2.7f)));
            assertEquals(-2.0f, core.vfp().sFloat(0), "modo " + mode);
        }
    }

    @Test
    void vrintrFollowsCurrentFpscrRoundingModeNotAnEncodedOne() {
        int word = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_SINGLE, false, sField(0), sField(1));
        assertEquals(2.0f, run(V80, word, fpscrMode(FpRoundingMode.ROUND_TO_NEAREST)
                .andThen(c -> c.vfp().setSFloat(1, 2.5f))).vfp().sFloat(0), "RN: empate vai para par");
        assertEquals(3.0f, run(V80, word, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, 2.1f))).vfp().sFloat(0), "RP");
        assertEquals(-3.0f, run(V80, word, fpscrMode(FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, -2.1f))).vfp().sFloat(0), "RM");
        assertEquals(-2.0f, run(V80, word, fpscrMode(FpRoundingMode.ROUND_TOWARD_ZERO)
                .andThen(c -> c.vfp().setSFloat(1, -2.7f))).vfp().sFloat(0), "RZ");
    }

    /// `VRINTX` = `VRINTR` no valor (o `IXC` cumulativo não é modelado por nenhuma família VFP).
    @Test
    void vrintxRoundsLikeVrintrUnderFpscrMode() {
        int word = word(COND_AL, OPC2_VRINTX, SIZE_SINGLE, false, sField(0), sField(1));
        assertEquals(3.0f, run(V80, word, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, 2.1f))).vfp().sFloat(0));
        assertEquals(2.0f, run(V80, word, fpscrMode(FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, 2.9f))).vfp().sFloat(0));
    }

    @Test
    void vrintDoublePrecisionRoundsWholeDoubleAndKeepsFpscrIntact() {
        int rint = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_DOUBLE, false, dField(2), dField(3));
        ArmCore core = run(V80, rint, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setDDouble(3, 1.0e10 + 0.25)));
        assertEquals(1.0e10 + 1.0, core.vfp().dDouble(2));
        assertEquals(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY.ordinal() << FpscrRegister.ROUNDING_MODE_SHIFT,
                core.fpscr().value(), "VRINT não mexe no FPSCR");
        int rintz = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_DOUBLE, true, dField(2), dField(3));
        assertEquals(-7.0, run(V80, rintz, c -> c.vfp().setDDouble(3, -7.9)).vfp().dDouble(2));
    }

    @Test
    void vrintPassesNanAndInfinityThrough() {
        int word = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_SINGLE, true, sField(0), sField(1));
        assertTrue(Float.isNaN(run(V80, word, c -> c.vfp().setSFloat(1, Float.NaN)).vfp().sFloat(0)));
        assertEquals(Float.NEGATIVE_INFINITY,
                run(V80, word, c -> c.vfp().setSFloat(1, Float.NEGATIVE_INFINITY)).vfp().sFloat(0));
    }

    @Test
    void vrintWithFalseConditionLeavesDestinationUntouched() {
        int word = word(COND_NE, OPC2_VRINTR_OR_Z, SIZE_SINGLE, true, sField(0), sField(1));
        ArmCore core = run(V80, word, c -> {
            c.vfp().setSFloat(0, 42.0f);
            c.vfp().setSFloat(1, 2.9f);
            c.cpsr().setNzcv(false, true, false, false); // Z=1 -> NE falso.
        });
        assertEquals(42.0f, core.vfp().sFloat(0));
    }

    // ── 3. VRINT*_hp ───────────────────────────────────────────────────────────────────────────

    @Test
    void vrintHalfFormsDecodeAndExecute() {
        int rintr = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_HALF, false, sField(0), sField(1));
        int rintz = word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_HALF, true, sField(0), sField(1));
        int rintx = word(COND_AL, OPC2_VRINTX, SIZE_HALF, false, sField(0), sField(1));
        assertEquals(InstructionKind.VFP_ROUND_HALF, kindOf(V80, rintr));
        assertEquals(new IrOp.VfpRoundHalf(null, 0, 1, Condition.AL), lift(decodeArm(V80, rintr)));
        assertEquals(HALF_2_0, run(V80, rintr, fpscrMode(FpRoundingMode.ROUND_TO_NEAREST)
                .andThen(c -> c.vfp().setS(1, HALF_2_5))).vfp().s(0));
        assertEquals(HALF_3_0, run(V80, rintx, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setS(1, HALF_2_5))).vfp().s(0));
        assertEquals(HALF_2_0, run(V80, rintz, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setS(1, HALF_2_5))).vfp().s(0));
    }

    /// `VRINTX_hp` só existe com `bit7=0`; `VRINTZ_hp`/`VRINTR_hp` não pertencem a `opc2=0x7`, `bit7=1`.
    @Test
    void reservedHalfOpc2SevenBit7IsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V80, word(COND_AL, OPC2_VRINTX, SIZE_HALF, true, sField(0), sField(1))));
    }

    // ── 4. Gates de feature (G8): recusa explícita, nunca misdecode ───────────────────────────

    @Test
    void vrintRzxSpDpAreUnimplementedWithoutArmv8Fp() {
        for (int size : new int[] {SIZE_SINGLE, SIZE_DOUBLE}) {
            for (int opc2 : new int[] {OPC2_VRINTR_OR_Z, OPC2_VRINTX}) {
                for (boolean bit7 : new boolean[] {false, true}) {
                    if (opc2 == OPC2_VRINTX && bit7) {
                        continue; // `opc2=7, bit7=1` é o VCVT dp<->sp, que existe em qualquer VFP.
                    }
                    assertEquals(InstructionKind.UNIMPLEMENTED,
                            kindOf(VFP_ONLY, word(COND_AL, opc2, size, bit7, sField(0), sField(1))),
                            "opc2=" + opc2 + " size=" + size + " bit7=" + bit7);
                }
            }
        }
    }

    // ── 5. VCVTR: `rz=0` arredonda pelo FPSCR.RMode ───────────────────────────────────────────

    @Test
    void vcvtrDecodesToVfpConvertRoundedWithNullDirection() {
        DecodedInstruction signed = decodeArm(V80, word(COND_AL, OPC2_VCVT_TO_S32, SIZE_SINGLE, false, sField(0), sField(1)));
        DecodedInstruction unsigned = decodeArm(V80, word(COND_AL, OPC2_VCVT_TO_U32, SIZE_DOUBLE, false, sField(2), dField(3)));
        assertEquals(InstructionKind.VFP_CONVERT_ROUNDED, signed.kind());
        assertEquals(new IrOp.VfpConvertRounded(null, true, false, 0, 1, Condition.AL), lift(signed));
        assertEquals(new IrOp.VfpConvertRounded(null, false, true, 2, 3, Condition.AL), lift(unsigned));
    }

    @Test
    void vcvtrRoundsByFpscrModeAndVcvtStillTruncates() {
        int vcvtr = word(COND_AL, OPC2_VCVT_TO_S32, SIZE_SINGLE, false, sField(0), sField(1));
        int vcvt = word(COND_AL, OPC2_VCVT_TO_S32, SIZE_SINGLE, true, sField(0), sField(1));
        assertEquals(3, run(V80, vcvtr, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, 2.1f))).vfp().s(0));
        assertEquals(-3, run(V80, vcvtr, fpscrMode(FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, -2.1f))).vfp().s(0));
        assertEquals(2, run(V80, vcvtr, fpscrMode(FpRoundingMode.ROUND_TO_NEAREST)
                .andThen(c -> c.vfp().setSFloat(1, 2.5f))).vfp().s(0), "RN: empate vai para par");
        // `rz=1` (VCVT) ignora o FPSCR e sempre trunca.
        assertEquals(2, run(V80, vcvt, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setSFloat(1, 2.9f))).vfp().s(0));
        assertEquals(InstructionKind.VFP_CONVERT, kindOf(V80, vcvt));
    }

    @Test
    void vcvtrDoublePrecisionUnsignedSaturatesNegativeAndNanToZero() {
        int unsigned = word(COND_AL, OPC2_VCVT_TO_U32, SIZE_DOUBLE, false, sField(0), dField(1));
        assertEquals(0, run(V80, unsigned, c -> c.vfp().setDDouble(1, -5.0)).vfp().s(0));
        assertEquals(0, run(V80, unsigned, c -> c.vfp().setDDouble(1, Double.NaN)).vfp().s(0));
        assertEquals(-1, run(V80, unsigned, c -> c.vfp().setDDouble(1, 1e20)).vfp().s(0), "satura em 2^32-1");
        assertEquals(3, run(V80, unsigned, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setDDouble(1, 2.000001))).vfp().s(0));
    }

    @Test
    void vcvtrHalfFormDecodesAndRoundsByFpscr() {
        int vcvtrHalf = word(COND_AL, OPC2_VCVT_TO_S32, SIZE_HALF, false, sField(0), sField(1));
        assertEquals(InstructionKind.VFP_CONVERT_ROUNDED_HALF, kindOf(V80, vcvtrHalf));
        assertEquals(new IrOp.VfpConvertRoundedHalf(null, true, 0, 1, Condition.AL), lift(decodeArm(V80, vcvtrHalf)));
        assertEquals(3, run(V80, vcvtrHalf, fpscrMode(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY)
                .andThen(c -> c.vfp().setS(1, HALF_2_5))).vfp().s(0));
        assertEquals(2, run(V80, vcvtrHalf, fpscrMode(FpRoundingMode.ROUND_TO_NEAREST)
                .andThen(c -> c.vfp().setS(1, HALF_2_5))).vfp().s(0));
    }

    // ── 6. VCVTB/VCVTT meia precisão ───────────────────────────────────────────────────────────

    @Test
    void halfToSingleSelectsBottomOrTopHalf() {
        Consumer<ArmCore> setup = c -> c.vfp().setS(1, (HALF_1_0 << 16) | HALF_2_5);
        ArmCore bottom = run(V80, word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_SINGLE, false, sField(0), sField(1)), setup);
        ArmCore top = run(V80, word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_SINGLE, true, sField(0), sField(1)), setup);
        assertEquals(2.5f, bottom.vfp().sFloat(0));
        assertEquals(1.0f, top.vfp().sFloat(0));
    }

    @Test
    void halfToDoubleWritesWholeDRegister() {
        Consumer<ArmCore> setup = c -> c.vfp().setS(3, (HALF_1_5 << 16) | HALF_2_5);
        ArmCore core = run(V80, word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_DOUBLE, true, dField(2), sField(3)), setup);
        assertEquals(1.5, core.vfp().dDouble(2));
        assertEquals(IrOp.HalfPrecisionConversion.F16_TO_F64,
                ((IrOp.VfpConvertHalfPrecision) lift(decodeArm(V80,
                        word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_DOUBLE, true, dField(2), sField(3))))).conversion());
    }

    @Test
    void singleToHalfWritesOnlySelectedHalfPreservingTheOther() {
        Consumer<ArmCore> setup = c -> {
            c.vfp().setS(0, 0xDEAD_BEEF);
            c.vfp().setSFloat(1, 1.5f);
        };
        ArmCore bottom = run(V80, word(COND_AL, OPC2_CVT_TO_HALF, SIZE_SINGLE, false, sField(0), sField(1)), setup);
        ArmCore top = run(V80, word(COND_AL, OPC2_CVT_TO_HALF, SIZE_SINGLE, true, sField(0), sField(1)), setup);
        assertEquals(0xDEAD_0000 | HALF_1_5, bottom.vfp().s(0));
        assertEquals((HALF_1_5 << 16) | 0xBEEF, top.vfp().s(0));
    }

    @Test
    void doubleToHalfRoundsOnceNotViaFloat() {
        // 1 + 2^-11 + 2^-40: acima do empate de binary16 (1 + 2^-11) por 2^-40. Direto: arredonda
        // para CIMA (0x3C01). Via `float` (23 bits de fração) o 2^-40 some, vira EXATAMENTE o empate e
        // ties-to-even devolve 0x3C00 — o double rounding que esta família precisa evitar.
        double justAboveTie = 1.0 + 0x1p-11 + 0x1p-40;
        ArmCore core = run(V80, word(COND_AL, OPC2_CVT_TO_HALF, SIZE_DOUBLE, false, sField(0), dField(1)), c -> {
            c.vfp().setS(0, 0xFFFF_0000);
            c.vfp().setDDouble(1, justAboveTie);
        });
        assertEquals(0xFFFF_3C01, core.vfp().s(0));
    }

    @Test
    void bfloat16NarrowingWritesSelectedHalfWithRoundToNearestEven() {
        int bottom = word(COND_AL, OPC2_CVT_TO_HALF, SIZE_HALF, false, sField(0), sField(1));
        int top = word(COND_AL, OPC2_CVT_TO_HALF, SIZE_HALF, true, sField(0), sField(1));
        assertEquals(InstructionKind.VFP_CONVERT_HALF_PRECISION, kindOf(V86, bottom));
        assertEquals(new IrOp.VfpConvertHalfPrecision(IrOp.HalfPrecisionConversion.F32_TO_BF16, true, 0, 1,
                Condition.AL), lift(decodeArm(V86, top)));
        Consumer<ArmCore> one = c -> {
            c.vfp().setS(0, 0x1111_1111);
            c.vfp().setSFloat(1, 1.0f);
        };
        assertEquals(0x1111_3F80, run(V86, bottom, one).vfp().s(0));
        assertEquals(0x3F80_1111, run(V86, top, one).vfp().s(0));
        // 0x3F808000 é empate exato e o bit baixo de 0x3F80 é 0 -> fica; 0x3F818000 -> sobe para par.
        assertEquals(0x0000_3F80 | 0x1111_0000, run(V86, bottom, c -> {
            c.vfp().setS(0, 0x1111_0000);
            c.vfp().setS(1, 0x3F80_8000);
        }).vfp().s(0));
        assertEquals(0x1111_3F82, run(V86, bottom, c -> {
            c.vfp().setS(0, 0x1111_0000);
            c.vfp().setS(1, 0x3F81_8000);
        }).vfp().s(0));
    }

    @Test
    void halfPrecisionConversionsAreGatedByFeature() {
        int f16FromSingle = word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_SINGLE, false, sField(0), sField(1));
        int f16ToDouble = word(COND_AL, OPC2_CVT_TO_HALF, SIZE_DOUBLE, false, sField(0), dField(1));
        int bf16 = word(COND_AL, OPC2_CVT_TO_HALF, SIZE_HALF, false, sField(0), sField(1));
        // Sem HALF_PRECISION_FP nem FP16_ARITHMETIC: as conversões `sp`/`dp` são recusadas.
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(VFP_ONLY, f16FromSingle));
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(VFP_ONLY, f16ToDouble));
        // Só a extensão VFPv3 de conversão de meia precisão: decodifica, mas SEM BF16 (`sz=1001`).
        assertEquals(InstructionKind.VFP_CONVERT_HALF_PRECISION, kindOf(HALF_CONV_ONLY, f16FromSingle));
        assertEquals(InstructionKind.VFP_CONVERT_HALF_PRECISION, kindOf(HALF_CONV_ONLY, f16ToDouble));
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(HALF_CONV_ONLY, bf16));
        // ARMv8.0 (FP16_ARITHMETIC, sem FEAT_BF16): BF16 recusado.
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(V80, bf16));
        // Só FEAT_BF16 (sem FP16_ARITHMETIC): BF16 decodifica; a aritmética `_hp` continua recusada.
        assertEquals(InstructionKind.VFP_CONVERT_HALF_PRECISION, kindOf(BF16_ONLY, bf16));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(BF16_ONLY, word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_HALF, true, sField(0), sField(1))));
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(BF16_ONLY, f16FromSingle));
    }

    @Test
    void halfConversionRejectsOutOfRangeDRegisterWithoutD32() {
        // `Dd` = D16 exige VFPv3-D32; o preset de teste não o declara -> recusa (G8), não `D0`.
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V80, word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_DOUBLE, false, dField(16), sField(1))));
    }

    @Test
    void halfConversionsCoverTopHalfOfEveryFormAndFalseCondition() {
        // VCVTT.F64.F16 / VCVTT.F32.F16 leem a metade ALTA.
        Consumer<ArmCore> setup = c -> c.vfp().setS(1, (HALF_1_5 << 16) | HALF_2_5);
        assertEquals(1.5, run(V80, word(COND_AL, OPC2_CVT_FROM_HALF, SIZE_DOUBLE, true, dField(2), sField(1)), setup)
                .vfp().dDouble(2));
        // VCVTT.F16.F64 grava a metade ALTA e preserva a baixa.
        ArmCore top = run(V80, word(COND_AL, OPC2_CVT_TO_HALF, SIZE_DOUBLE, true, sField(0), dField(1)), c -> {
            c.vfp().setS(0, 0x0000_ABCD);
            c.vfp().setDDouble(1, 1.5);
        });
        assertEquals((HALF_1_5 << 16) | 0xABCD, top.vfp().s(0));
        // condição falsa: nada muda.
        ArmCore skipped = run(V80, word(COND_NE, OPC2_CVT_TO_HALF, SIZE_SINGLE, false, sField(0), sField(1)), c -> {
            c.vfp().setS(0, 0x1234_5678);
            c.vfp().setSFloat(1, 1.5f);
            c.cpsr().setNzcv(false, true, false, false);
        });
        assertEquals(0x1234_5678, skipped.vfp().s(0));
    }

    @Test
    void vcvtrHalfUnsignedDecodesAndSaturatesNegativeToZero() {
        int word = word(COND_AL, OPC2_VCVT_TO_U32, SIZE_HALF, false, sField(0), sField(1));
        assertEquals(new IrOp.VfpConvertRoundedHalf(null, false, 0, 1, Condition.AL), lift(decodeArm(V80, word)));
        assertEquals(0, run(V80, word, c -> c.vfp().setS(1, 0xBC00)).vfp().s(0), "-1.0 sem sinal satura em 0");
    }

    @Test
    void outOfRangeDRegistersWithoutD32AreUnimplementedForToHalfVjcvtAndVrint() {
        // D16 exige VFPv3-D32, ausente nos presets de teste.
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V80, word(COND_AL, OPC2_CVT_TO_HALF, SIZE_DOUBLE, false, sField(0), dField(16))));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V86, word(COND_AL, OPC2_VJCVT, SIZE_DOUBLE, true, sField(0), dField(16))));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V80, word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_DOUBLE, true, dField(16), dField(1))));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V80, word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_DOUBLE, true, dField(1), dField(16))));
    }

    // ── 7. VJCVT ───────────────────────────────────────────────────────────────────────────────

    private static final int VJCVT_WORD = word(COND_AL, OPC2_VJCVT, SIZE_DOUBLE, true, sField(0), dField(1));

    @Test
    void vjcvtDecodesToVfpJavascriptConvertOnlyWithJscvt() {
        DecodedInstruction decoded = decodeArm(V86, VJCVT_WORD);
        assertEquals(InstructionKind.VFP_JAVASCRIPT_CONVERT, decoded.kind());
        assertEquals(new IrOp.VfpJavascriptConvert(0, 1, Condition.AL), lift(decoded));
        // ARMv8.0 (sem FEAT_JSCVT), VFP puro e a variante sp são recusados.
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(V80, VJCVT_WORD));
        assertEquals(InstructionKind.UNIMPLEMENTED, kindOf(VFP_ONLY, VJCVT_WORD));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V86, word(COND_AL, OPC2_VJCVT, SIZE_SINGLE, true, sField(0), sField(1))));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                kindOf(V86, word(COND_AL, OPC2_VJCVT, SIZE_DOUBLE, false, sField(0), dField(1))));
    }

    @Test
    void vjcvtReducesModuloTwoPowThirtyTwoAndFlagsExactnessInFpscrZ() {
        // exato
        ArmCore exact = run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, -3.0));
        assertEquals(-3, exact.vfp().s(0));
        assertTrue(exact.fpscr().z());
        // truncamento (inexato)
        ArmCore fraction = run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, 3.75));
        assertEquals(3, fraction.vfp().s(0));
        assertTrue(!fraction.fpscr().z());
        // overflow: reduz módulo 2^32 (NÃO satura, NÃO zera), Z=0
        ArmCore wrapped = run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, 4294967301.0));
        assertEquals(5, wrapped.vfp().s(0));
        assertTrue(!wrapped.fpscr().z());
        assertEquals(Integer.MIN_VALUE, run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, 2147483648.0)).vfp().s(0));
        // NaN/infinito -> 0, Z=0
        ArmCore nan = run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, Double.NaN));
        assertEquals(0, nan.vfp().s(0));
        assertTrue(!nan.fpscr().z());
        assertEquals(0, run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, Double.NEGATIVE_INFINITY)).vfp().s(0));
        // -0.0 é inexato para JavaScript
        assertTrue(!run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, -0.0)).fpscr().z());
        assertTrue(run(V86, VJCVT_WORD, c -> c.vfp().setDDouble(1, 0.0)).fpscr().z());
    }

    /// `FPSCR.{N,Z,C,V} = 0,Z,0,0` e nada mais muda (RMode, cumulativos...).
    @Test
    void vjcvtClearsNcvAndPreservesEveryOtherFpscrBit() {
        int others = (FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY.ordinal() << FpscrRegister.ROUNDING_MODE_SHIFT)
                | FpscrRegister.INEXACT_CUMULATIVE_FLAG | FpscrRegister.FLUSH_TO_ZERO_FLAG;
        int allNzcv = FpscrRegister.NEGATIVE_FLAG | FpscrRegister.CARRY_FLAG | FpscrRegister.OVERFLOW_FLAG;
        ArmCore core = run(V86, VJCVT_WORD, c -> {
            c.fpscr().setValue(allNzcv | others);
            c.vfp().setDDouble(1, 7.0);
        });
        assertEquals(others | FpscrRegister.ZERO_FLAG, core.fpscr().value());
    }

    @Test
    void vjcvtWithFalseConditionDoesNothing() {
        int word = word(COND_NE, OPC2_VJCVT, SIZE_DOUBLE, true, sField(0), dField(1));
        ArmCore core = run(V86, word, c -> {
            c.vfp().setS(0, 0x1234);
            c.vfp().setDDouble(1, 9.0);
            c.cpsr().setNzcv(false, true, false, false);
        });
        assertEquals(0x1234, core.vfp().s(0));
        assertEquals(0, core.fpscr().value());
    }

    // ── 8. T32, bloco, política ASM e lifter ───────────────────────────────────────────────────

    @Test
    void newFamiliesDecodeIdenticallyInThumb2() {
        int[] words = {
            word(COND_AL, OPC2_VRINTR_OR_Z, SIZE_SINGLE, false, sField(2), sField(3)),
            word(COND_AL, OPC2_VCVT_TO_S32, SIZE_DOUBLE, false, sField(0), dField(1)),
            word(COND_AL, OPC2_CVT_TO_HALF, SIZE_SINGLE, true, sField(0), sField(1)),
            VJCVT_WORD,
            word(COND_AL, OPC2_CVT_TO_HALF, SIZE_HALF, false, sField(0), sField(1)),
        };
        for (int word : words) {
            DecodedInstruction arm = decodeArm(V86, word);
            DecodedInstruction thumb = decodeThumb32(V86, word);
            assertNotEquals(InstructionKind.UNIMPLEMENTED, arm.kind());
            assertEquals(InstructionSet.THUMB, thumb.instructionSet());
            assertEquals(lift(arm), lift(thumb.withInstructionSet(InstructionSet.ARM)),
                    String.format("0x%08X", word));
        }
    }

    @Test
    void newOpsExecuteThroughPrimaryBlockDispatch() {
        ArmCore core = newCore(V86);
        core.vfp().setDDouble(1, 4294967301.0);
        core.vfp().setSFloat(4, 1.5f);
        IrBlock.Builder builder = IrBlock.builder(0);
        builder.add(new IrOp.VfpJavascriptConvert(0, 1, Condition.AL));
        builder.add(new IrOp.VfpConvertHalfPrecision(IrOp.HalfPrecisionConversion.F32_TO_F16, false, 6, 4,
                Condition.AL));
        builder.add(new IrOp.VfpRound(null, false, 5, 4, Condition.AL));
        builder.add(new IrOp.Cycle(1));
        builder.add(new IrOp.Fetch(4, 4));
        new IrBlockExecutor(V86).execute(builder.endPc(4).sealed(), core);
        assertEquals(5, core.vfp().s(0));
        assertEquals(HALF_1_5, core.vfp().s(6));
        assertEquals(2.0f, core.vfp().sFloat(5), "VRINTR sob RN: 1.5 -> 2.0");
    }

    @Test
    void asmNativePolicyRefusesTheNewOps() {
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpJavascriptConvert(0, 1, Condition.AL)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpConvertHalfPrecision(
                IrOp.HalfPrecisionConversion.F16_TO_F32, false, 0, 1, Condition.AL)));
    }

    @Test
    void newFamiliesDoNotTerminateTheBlock() {
        TestAddressSpace memory = new TestAddressSpace(12);
        memory.put32(0, VJCVT_WORD);
        memory.put32(4, word(COND_AL, OPC2_CVT_TO_HALF, SIZE_SINGLE, false, sField(0), sField(1)));
        memory.put32(8, word(COND_AL, OPC2_VRINTX, SIZE_SINGLE, false, sField(0), sField(1)));
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(V86), new StandardIrBuilder());
        IrBlock block = lifter.lift(memory, 0, 3, 0);
        assertEquals(12, block.endPc(), "as 3 instruções cabem no MESMO bloco");
    }
}
