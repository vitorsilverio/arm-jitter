package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B14.5 — `VRINT{A,N,P,M}`/`VCVT{A,N,P,M}{S,U}` (`sz=2`/`sz=3`, espaço VFP incondicional,
/// `bits[31:28]=0xF`, `vfp-uncond.decode`). Oráculo: QEMU `target/arm/tcg/translate-vfp.c`
/// (`trans_VRINT`/`trans_VCVT`, tabela `fp_decode_rm`). Ver `VfpDecoder#decodeRoundOrConvert` e a
/// task `b14.5-vrint-vcvt-modo-explicito.md`. Mesma base de arquitetura de teste de
/// `VfpUnconditionalSelectMaxMinTest` (B14.4).
class VfpUnconditionalRoundConvertTest {
    private static final ArmArchitecture VFP_TEST_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K_THUMB2, "ARMv7-TestVfpUncondRound", ArmFeature.VFPV2);
    private static final ArmArchitecture VFP_TEST_ARCH = VFP_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(VFP_TEST_FEATURES));

    private static final ArmArchitecture VFP_V8_TEST_FEATURES =
            ArmArchitecture.extending(VFP_TEST_FEATURES, "ARMv8-TestVfpUncondRound", ArmFeature.ARMV8_FP);
    private static final ArmArchitecture VFP_V8_TEST_ARCH = VFP_V8_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_V8_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32Extensions(VFP_V8_TEST_FEATURES));

    private static List<DecoderExtension> thumb32Extensions(ArmArchitecture features) {
        return List.of(new Thumb2VfpDecoder(features), new Thumb2CoprocessorDecoder());
    }

    // ── Encoders manuais (vfp-uncond.decode) ──────────────────────────────────────────────────

    private static int nibbleOf(int combined, boolean doublePrecision) {
        return doublePrecision ? combined & 0xF : combined >>> 1;
    }

    private static int extOf(int combined, boolean doublePrecision) {
        return doublePrecision ? (combined >>> 4) & 1 : combined & 1;
    }

    private static int size(boolean doublePrecision) {
        return doublePrecision ? 0xB : 0xA;
    }

    /// `VRINT` (`sz=2`/`sz=3`): `1111 1110 1.11 10 rm:2 vd(4) size 01.0 vm(4)`.
    private static int vrintWord(int rm, boolean doublePrecision, int vd, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20) | (1 << 19);
        word |= extOf(vd, doublePrecision) << 22;
        word |= (rm & 0x3) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= 1 << 6;
        word |= extOf(vm, doublePrecision) << 5;
        word |= nibbleOf(vm, doublePrecision);
        return word;
    }

    /// `VCVT` (`sz=2`/`sz=3`): `1111 1110 1.11 11 rm:2 vd(4) size op:1 1.0 vm(4)` — `Vd` SEMPRE
    /// simples (`vd`/`vm` passados aqui já são o índice de registrador, mas `Vd` é codificado como
    /// `S`).
    private static int vcvtWord(int rm, boolean signed, boolean doublePrecision, int vd, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20) | (1 << 19) | (1 << 18);
        word |= extOf(vd, false) << 22;
        word |= (rm & 0x3) << 16;
        word |= nibbleOf(vd, false) << 12;
        word |= size(doublePrecision) << 8;
        word |= (signed ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= extOf(vm, doublePrecision) << 5;
        word |= nibbleOf(vm, doublePrecision);
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

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), VFP_V8_TEST_ARCH);
    }

    private static final AdvSimdLanes.RoundingMode[] RM_TABLE = {
            AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN,
            AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, AdvSimdLanes.RoundingMode.TOWARD_NEGATIVE_INFINITY,
    };

    // ── 1. Decodificação/lift corretos sob ARMV8_FP (A32 e T32), tabela `rm` completa ─────────

    @Test
    void vrintSingleDecodesToVfpRoundAllFourRoundingModes() {
        for (int rm = 0; rm < 4; rm++) {
            DecodedInstruction decoded = decodeArm(VFP_V8_TEST_ARCH, vrintWord(rm, false, 2, 1));
            assertEquals(InstructionKind.VFP_ROUND, decoded.kind());
            IrOp op = liftSingleOp(decoded);
            assertEquals(new IrOp.VfpRound(RM_TABLE[rm], false, 2, 1, Condition.AL), op);
        }
    }

    @Test
    void vrintDoubleDecodesToVfpRound() {
        IrOp op = liftSingleOp(decodeArm(VFP_V8_TEST_ARCH, vrintWord(0b10, true, 5, 1)));
        assertEquals(new IrOp.VfpRound(AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, true, 5, 1, Condition.AL), op);
    }

    @Test
    void vcvtSingleDecodesToVfpConvertRoundedAllFourRoundingModesSignedAndUnsigned() {
        for (int rm = 0; rm < 4; rm++) {
            DecodedInstruction signedDecoded = decodeArm(VFP_V8_TEST_ARCH, vcvtWord(rm, true, false, 2, 1));
            assertEquals(InstructionKind.VFP_CONVERT_ROUNDED, signedDecoded.kind());
            assertEquals(new IrOp.VfpConvertRounded(RM_TABLE[rm], true, false, 2, 1, Condition.AL),
                    liftSingleOp(signedDecoded));

            DecodedInstruction unsignedDecoded = decodeArm(VFP_V8_TEST_ARCH, vcvtWord(rm, false, false, 2, 1));
            assertEquals(new IrOp.VfpConvertRounded(RM_TABLE[rm], false, false, 2, 1, Condition.AL),
                    liftSingleOp(unsignedDecoded));
        }
    }

    @Test
    void vcvtDoubleSourceDecodesWithSingleDestination() {
        // VCVTMS.S32.F64 S4, D1: origem D1 (doublePrecision=true), destino SEMPRE S.
        IrOp op = liftSingleOp(decodeArm(VFP_V8_TEST_ARCH, vcvtWord(0b11, true, true, 4, 1)));
        assertEquals(new IrOp.VfpConvertRounded(AdvSimdLanes.RoundingMode.TOWARD_NEGATIVE_INFINITY, true, true, 4, 1,
                Condition.AL), op);
    }

    @Test
    void vrintRoundTripsArmAndThumb2() {
        int word = vrintWord(1, false, 3, 2);
        DecodedInstruction armDecoded = decodeArm(VFP_V8_TEST_ARCH, word);
        DecodedInstruction thumbDecoded = decodeThumb32(VFP_V8_TEST_ARCH, word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    @Test
    void vcvtRoundTripsArmAndThumb2() {
        int word = vcvtWord(2, false, true, 3, 2);
        DecodedInstruction armDecoded = decodeArm(VFP_V8_TEST_ARCH, word);
        DecodedInstruction thumbDecoded = decodeThumb32(VFP_V8_TEST_ARCH, word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    // ── 2. Não-misdecode: sob ARMV7A/MPCore (sem ARMV8_FP), e vizinhos VSEL/VMAXNM intactos ────

    @Test
    void vrintVcvtUnimplementedWithoutArmv8Fp() {
        for (int rm = 0; rm < 4; rm++) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, vrintWord(rm, false, 2, 1)).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_TEST_ARCH, vcvtWord(rm, true, true, 2, 1)).kind());
        }
    }

    @Test
    void vrintVcvtDoNotCollideWithVselOrMaxNmMinNmDecodePaths() {
        // VSEL (bit23=0) e VMAXNM/VMINNM (bit23=1,bits21:20=00) continuam decodificando como antes
        // — a nova ramificação só entra quando bits21:20=11.
        int vsel = (0xF << 28) | (0xE << 24) | (0 << 20) | (0xA << 8);
        assertEquals(InstructionKind.VFP_SELECT, decodeArm(VFP_V8_TEST_ARCH, vsel).kind());
        int maxnm = (0xF << 28) | (0xE << 24) | (1 << 23) | (0xA << 8);
        assertEquals(InstructionKind.VFP_ALU, decodeArm(VFP_V8_TEST_ARCH, maxnm).kind());
    }

    // ── 3. Execução: VRINT arredonda mantendo ponto flutuante, direção da INSTRUÇÃO ────────────

    @Test
    void vrintTiesAwayVersusTiesEvenDifferAtHalfBoundaries() {
        ArmCore core = newCore();
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        // 1.5/-1.5 NÃO divergem (o vizinho par já é o mesmo que "afasta de zero" nesses dois
        // casos) — só 0.5/2.5 (e seus negativos) provam a diferença entre as duas tabelas.
        float[] divergingHalves = {0.5f, -0.5f, 2.5f, -2.5f};
        for (float value : divergingHalves) {
            core.vfp().setSFloat(0, value);
            executor.executeOp(core, new IrOp.VfpRound(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, false, 1, 0,
                    Condition.AL), 0);
            float tiesAway = core.vfp().sFloat(1);
            executor.executeOp(core, new IrOp.VfpRound(AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN, false, 1, 0,
                    Condition.AL), 0);
            float tiesEven = core.vfp().sFloat(1);
            assertEquals(true, tiesAway != tiesEven, "ties-away e ties-even deveriam divergir em " + value);
            assertEquals((float) AdvSimdLanes.roundForConversion(value, AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY),
                    tiesAway);
            assertEquals((float) AdvSimdLanes.roundForConversion(value, AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN),
                    tiesEven);
        }
        for (float value : new float[] {1.5f, -1.5f}) {
            core.vfp().setSFloat(0, value);
            executor.executeOp(core, new IrOp.VfpRound(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, false, 1, 0,
                    Condition.AL), 0);
            assertEquals((float) AdvSimdLanes.roundForConversion(value, AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY),
                    core.vfp().sFloat(1));
        }
    }

    @Test
    void vrintPassesNanAndInfinityThroughUnchanged() {
        ArmCore core = newCore();
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        core.vfp().setSFloat(0, Float.NaN);
        executor.executeOp(core, new IrOp.VfpRound(AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, false, 1, 0,
                Condition.AL), 0);
        assertEquals(true, Float.isNaN(core.vfp().sFloat(1)));

        core.vfp().setDDouble(0, Double.POSITIVE_INFINITY);
        executor.executeOp(core, new IrOp.VfpRound(AdvSimdLanes.RoundingMode.TOWARD_ZERO, true, 1, 0, Condition.AL), 0);
        assertEquals(Double.POSITIVE_INFINITY, core.vfp().dDouble(1));
    }

    @Test
    void vrintDoublePrecisionMatchesAdvSimdLanesRoundForConversionAllDirections() {
        ArmCore core = newCore();
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        double[] values = {3.25, -3.25, 100.0, -0.0};
        for (double value : values) {
            for (AdvSimdLanes.RoundingMode direction : AdvSimdLanes.RoundingMode.values()) {
                core.vfp().setDDouble(0, value);
                executor.executeOp(core, new IrOp.VfpRound(direction, true, 1, 0, Condition.AL), 0);
                assertEquals(AdvSimdLanes.roundForConversion(value, direction), core.vfp().dDouble(1));
            }
        }
    }

    @Test
    void vrintDoesNotModifyFpscr() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 1.5f);
        core.fpscr().setValue(0x1234);
        int before = core.fpscr().value();
        new IrBlockExecutor(VFP_V8_TEST_ARCH).executeOp(core,
                new IrOp.VfpRound(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, false, 1, 0, Condition.AL), 0);
        assertEquals(before, core.fpscr().value());
    }

    // ── 4. Execução: VCVT converte para inteiro de 32 bits, satura, NaN->0 ─────────────────────

    @Test
    void vcvtSignedMatchesAdvSimdLanesRoundAndSaturateAllDirections() {
        ArmCore core = newCore();
        IrBlockExecutor executor = new IrBlockExecutor(VFP_V8_TEST_ARCH);
        double[] values = {2.5, -2.5, 0.5, -0.5, 1.5, -1.5, 1e30, -1e30};
        for (double value : values) {
            for (AdvSimdLanes.RoundingMode direction : AdvSimdLanes.RoundingMode.values()) {
                core.vfp().setDDouble(0, value);
                executor.executeOp(core, new IrOp.VfpConvertRounded(direction, true, true, 1, 0, Condition.AL), 0);
                int expected = (int) AdvSimdLanes.saturateToInteger(AdvSimdLanes.roundForConversion(value, direction),
                        true, false);
                assertEquals(expected, core.vfp().s(1));
            }
        }
    }

    @Test
    void vcvtUnsignedMatchesAdvSimdLanesRoundAndSaturate() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, -5.0f);
        new IrBlockExecutor(VFP_V8_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertRounded(AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN, false, false, 1, 0,
                        Condition.AL), 0);
        int expected = (int) AdvSimdLanes.saturateToInteger(
                AdvSimdLanes.roundForConversion(-5.0, AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN), false, false);
        assertEquals(expected, core.vfp().s(1));
        assertEquals(0, core.vfp().s(1)); // NaN/negativo sem sinal -> 0 (saturação inferior).
    }

    @Test
    void vcvtNanConvertsToZero() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, Float.NaN);
        new IrBlockExecutor(VFP_V8_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertRounded(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, true, false, 1, 0,
                        Condition.AL), 0);
        assertEquals(0, core.vfp().s(1));
    }

    @Test
    void vcvtDoesNotModifyFpscr() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 1.5f);
        core.fpscr().setValue(0x5678);
        int before = core.fpscr().value();
        new IrBlockExecutor(VFP_V8_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertRounded(AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN, true, false, 1, 0,
                        Condition.AL), 0);
        assertEquals(before, core.fpscr().value());
    }

    // ── 5. Fechamento G8: `bit19=0`/`bit6=0`/`bit7=1` em VRINT são combinações reservadas ──────

    @Test
    void reservedBitCombinationsAreUnimplementedNeverMisdecoded() {
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        int vrintBit7Set = vrintWord(0, false, 2, 1) | (1 << 7); // VRINT com bit7=1 (reservado).
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vrintBit7Set).kind());
        int bit6Clear = vrintWord(0, false, 2, 1) & ~(1 << 6);
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, bit6Clear).kind());
        int bit19Clear = vrintWord(0, false, 2, 1) & ~(1 << 19);
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, bit19Clear).kind());
    }

    private static DecodedInstruction notNullDecode(VfpDecoder decoder, int word) {
        DecodedInstruction decoded = decoder.tryDecode(word, 0, Condition.AL);
        assertNotNull(decoded, "tryDecode devolveu null apesar de claimsEncodingSpace=true (G8)");
        return decoded;
    }

    @Test
    void vrintVcvtWithInvalidD16RegisterIsExplicitlyUnimplementedNeverNull() {
        VfpDecoder decoder = new VfpDecoder(VFP_V8_TEST_ARCH);
        int vrintD16 = vrintWord(0, true, 16, 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vrintD16).kind());
        int vcvtD16Source = vcvtWord(0, true, true, 0, 16);
        assertEquals(InstructionKind.UNIMPLEMENTED, notNullDecode(decoder, vcvtD16Source).kind());
    }

    // ── 6. Caminho de execução de BLOCO (Kind-switch de `IrBlockExecutor#execute`) ─────────────

    @Test
    void vrintExecutesThroughPrimaryBlockDispatchNotOnlyExecuteOpFallback() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 2.5f);
        IrBlock.Builder builder = IrBlock.builder(0);
        builder.add(new IrOp.VfpRound(AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN, false, 1, 0, Condition.AL));
        builder.add(new IrOp.Cycle(1));
        builder.add(new IrOp.Fetch(4, 4));
        IrBlock block = builder.endPc(4).sealed();
        new IrBlockExecutor(VFP_V8_TEST_ARCH).execute(block, core);
        assertEquals(2.0f, core.vfp().sFloat(1));
    }

    @Test
    void vcvtExecutesThroughPrimaryBlockDispatchNotOnlyExecuteOpFallback() {
        ArmCore core = newCore();
        core.vfp().setSFloat(0, 7.5f);
        IrBlock.Builder builder = IrBlock.builder(0);
        builder.add(new IrOp.VfpConvertRounded(AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, true, false, 1, 0,
                Condition.AL));
        builder.add(new IrOp.Cycle(1));
        builder.add(new IrOp.Fetch(4, 4));
        IrBlock block = builder.endPc(4).sealed();
        new IrBlockExecutor(VFP_V8_TEST_ARCH).execute(block, core);
        assertEquals(8, core.vfp().s(1));
    }

    @Test
    void claimsEncodingSpaceIsTrueForRoundConvertRegionEvenWithoutFeature() {
        int word = vrintWord(0, false, 2, 1);
        assertEquals(true, new VfpDecoder(VFP_TEST_ARCH).claimsEncodingSpace(word));
        assertEquals(true, new VfpDecoder(VFP_V8_TEST_ARCH).claimsEncodingSpace(word));
    }
}
