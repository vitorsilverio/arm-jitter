package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B14.6b — as 5 formas `sz=1` do espaço incondicional (`VSEL_hp`/`VMAXNM_hp`/`VMINNM_hp`/
/// `VRINT_hp`/`VCVT_hp`) e as 22 linhas `_hp` implementadas do espaço condicional (`vfp.decode`;
/// `VRINTR_hp`/`VRINTZ_hp`/`VRINTX_hp` seguem `UNIMPLEMENTED`, mesmo "Não inclui" de B14.5/B14.6).
/// Ver `VfpDecoder#decodeHalfPrecisionConditionalSpace`/`#decodeUnconditionalHalfPrecision` e a
/// task `b14.6-fp16-32bit-vmovx-vins.md` (seção "Pendências para B14.6b"). Mesma base de
/// arquitetura de teste de `VfpUnconditionalMovxVinsTest` (B14.6a).
class VfpHalfPrecisionArithmeticTest {
    private static final ArmArchitecture VFP_V8_TEST_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K_THUMB2, "ARMv8-TestVfpHpNoFp16", ArmFeature.VFPV2, ArmFeature.ARMV8_FP,
            ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE);
    private static final ArmArchitecture VFP_V8_TEST_ARCH = VFP_V8_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_V8_TEST_FEATURES), new CoprocessorDecoder()));

    private static final ArmArchitecture FP16_TEST_FEATURES =
            ArmArchitecture.extending(VFP_V8_TEST_FEATURES, "ARMv8-TestVfpHpFp16", ArmFeature.FP16_ARITHMETIC);
    private static final ArmArchitecture FP16_TEST_ARCH = FP16_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(FP16_TEST_FEATURES), new CoprocessorDecoder()));

    /// Sem `VFP_FUSED_MULTIPLY_ACCUMULATE` — cobre o ramo `!architecture.has(...)` de
    /// `VFMA_hp`/`VFMS_hp`/`VFNMA_hp`/`VFNMS_hp` em `decodeHalfPrecisionDataProcessing`.
    private static final ArmArchitecture FP16_NO_FUSED_TEST_FEATURES = ArmArchitecture.extending(
            ArmArchitecture.ARMV6K_THUMB2, "ARMv8-TestVfpHpNoFused", ArmFeature.VFPV2, ArmFeature.ARMV8_FP,
            ArmFeature.FP16_ARITHMETIC);
    private static final ArmArchitecture FP16_NO_FUSED_TEST_ARCH = FP16_NO_FUSED_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(FP16_NO_FUSED_TEST_FEATURES), new CoprocessorDecoder()));

    // ── Encoder manual (vfp.decode / vfp-uncond.decode, layout `_hp`) ─────────────────────────

    private static int nibbleOf(int combined) {
        return combined >>> 1;
    }

    private static int extOf(int combined) {
        return combined & 1;
    }

    /// Espaço condicional `bits2724=0xE`, família de 3 registradores (`VMLA_hp`…`VDIV_hp`).
    private static int threeRegHalfWord(int cond, int op1, boolean bit6, int vn, int vd, int vm) {
        int word = (cond << 28) | (0xE << 24) | (0x9 << 8);
        word |= ((op1 >>> 2) & 1) << 23;
        word |= ((op1 >>> 1) & 1) << 21;
        word |= (op1 & 1) << 20;
        word |= nibbleOf(vn) << 16;
        word |= extOf(vn) << 7;
        word |= nibbleOf(vd) << 12;
        word |= extOf(vd) << 22;
        word |= (bit6 ? 1 : 0) << 6;
        word |= nibbleOf(vm);
        word |= extOf(vm) << 5;
        return word;
    }

    /// Espaço condicional, família imediato/2-operando/compare/convert (`op1==0b111`, isto é,
    /// bit23=1, bit21=1, bit20=1 — bit22 é a extensão de `Vd`, NÃO faz parte de `op1`).
    private static int twoOperandHalfWord(int cond, int opc2, boolean bit7, int vd, int vm) {
        int word = (cond << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (1 << 21) | (1 << 20) | (1 << 6);
        word |= extOf(vd) << 22;
        word |= nibbleOf(vd) << 12;
        word |= (opc2 & 0xF) << 16;
        word |= (bit7 ? 1 : 0) << 7;
        word |= nibbleOf(vm);
        word |= extOf(vm) << 5;
        return word;
    }

    private static int vmovImmHalfWord(int cond, int vd, int imm8) {
        int word = (cond << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd) << 22;
        word |= nibbleOf(vd) << 12;
        word |= ((imm8 >>> 4) & 0xF) << 16;
        word |= imm8 & 0xF;
        return word;
    }

    private static int vldrVstrHalfWord(int cond, boolean load, boolean add, int rn, int vd, int imm8) {
        int word = (cond << 28) | (0xD << 24) | (0x9 << 8);
        word |= (add ? 1 : 0) << 23;
        word |= extOf(vd) << 22;
        word |= (load ? 1 : 0) << 20;
        word |= (rn & 0xF) << 16;
        word |= nibbleOf(vd) << 12;
        word |= imm8 & 0xFF;
        return word;
    }

    /// Espaço incondicional (`cond` sempre `1111`), `VSEL_hp`.
    private static int vselHalfWord(int cc, int vn, int vd, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (0x9 << 8);
        word |= cc << 20;
        word |= extOf(vd) << 22;
        word |= nibbleOf(vd) << 12;
        word |= nibbleOf(vn) << 16;
        word |= extOf(vn) << 7;
        word |= nibbleOf(vm);
        word |= extOf(vm) << 5;
        return word;
    }

    private static int maxNmMinNmHalfWord(boolean isMin, int vn, int vd, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23);
        word |= extOf(vd) << 22;
        word |= nibbleOf(vd) << 12;
        word |= nibbleOf(vn) << 16;
        word |= extOf(vn) << 7;
        word |= (isMin ? 1 : 0) << 6;
        word |= nibbleOf(vm);
        word |= extOf(vm) << 5;
        return word;
    }

    private static int roundHalfWord(int rm, int vd, int vm) {
        int word = (0xF << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (0x3 << 20) | (1 << 19) | (1 << 6);
        word |= rm << 16;
        word |= extOf(vd) << 22;
        word |= nibbleOf(vd) << 12;
        word |= nibbleOf(vm);
        word |= extOf(vm) << 5;
        return word;
    }

    private static int convertRoundedHalfWord(int rm, boolean signed, int vd, int vm) {
        int word = roundHalfWord(rm, vd, vm) | (1 << 18);
        if (signed) {
            word |= 1 << 7;
        }
        return word;
    }

    private static DecodedInstruction decodeArm(ArmArchitecture arch, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(arch).decode(memory, 0);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), FP16_TEST_ARCH);
    }

    private static float half(float value) {
        return AdvSimdLanes.halfToFloat(AdvSimdLanes.halfBits(value));
    }

    private static void setHalf(ArmCore core, int reg, float value) {
        core.vfp().setS(reg, (int) AdvSimdLanes.halfBits(value) & 0xFFFF);
    }

    private static float readHalf(ArmCore core, int reg) {
        return AdvSimdLanes.halfToFloat(core.vfp().s(reg) & 0xFFFF);
    }

    // ── 1. Decode/lift — espaço condicional (aritmética/unárias/compare/imediato/load/store) ──

    @Test
    void vaddHpDecodesToVfpAluHalf() {
        int word = threeRegHalfWord(0xE, 0b011, false, 1, 2, 3); // op1=011 -> ADD (bit6=0).
        DecodedInstruction decoded = decodeArm(FP16_TEST_ARCH, word);
        assertEquals(InstructionKind.VFP_ALU_HALF, decoded.kind());
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.ADD, 2, 1, 3, Condition.AL), liftSingleOp(decoded));
    }

    @Test
    void vsubHpIsAddWithBit6Set() {
        int word = threeRegHalfWord(0xE, 0b011, true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.SUB, 2, 1, 3, Condition.AL),
                liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vfmaHpRequiresFusedMultiplyAccumulateFeature() {
        int word = threeRegHalfWord(0xE, 0b110, false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.FMA, 2, 1, 3, Condition.AL),
                liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vabsHpNegHpSqrtHpDecodeToVfpAluHalfUnary() {
        int abs = twoOperandHalfWord(0xE, 0x0, true, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.ABS, 2, -1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, abs)));
        int neg = twoOperandHalfWord(0xE, 0x1, false, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.NEG, 2, -1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, neg)));
        int sqrt = twoOperandHalfWord(0xE, 0x1, true, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.SQRT, 2, -1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, sqrt)));
    }

    /// `VMOV_reg_hp` não existe (opc2=0x0,bit7=0 é reservado, ver Contexto da task).
    @Test
    void reservedOpc0Bit7FalseIsUnimplemented() {
        int word = twoOperandHalfWord(0xE, 0x0, false, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    @Test
    void vmovImmHpDecodesToVfpMoveImmediateHalf() {
        int word = vmovImmHalfWord(0xE, 4, 0x3F);
        DecodedInstruction decoded = decodeArm(FP16_TEST_ARCH, word);
        assertEquals(InstructionKind.VFP_MOVE_IMMEDIATE_HALF, decoded.kind());
        IrOp.VfpMoveImmediateHalf lifted = (IrOp.VfpMoveImmediateHalf) liftSingleOp(decoded);
        assertEquals(4, lifted.vd());
    }

    @Test
    void vcmpHpDecodesToVfpCompareHalf() {
        int word = twoOperandHalfWord(0xE, 0x4, false, 2, 3);
        assertEquals(new IrOp.VfpCompareHalf(false, false, 2, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vcmpHpWithZeroDecodesCompareWithZero() {
        int word = twoOperandHalfWord(0xE, 0x5, false, 2, 3);
        assertEquals(new IrOp.VfpCompareHalf(true, false, 2, -1, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vcvtIntHpDecodesToVfpConvertSignedUnsigned() {
        int signed = twoOperandHalfWord(0xE, 0x8, true, 2, 3);
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F16, 2, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, signed)));
        int unsigned = twoOperandHalfWord(0xE, 0x8, false, 2, 3);
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F16, 2, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, unsigned)));
    }

    @Test
    void vcvtHpIntDecodesToVfpConvertSignedUnsigned() {
        int signed = twoOperandHalfWord(0xE, 0xD, true, 2, 3);
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F16_TO_S32, 2, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, signed)));
        int unsigned = twoOperandHalfWord(0xE, 0xC, true, 2, 3);
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F16_TO_U32, 2, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, unsigned)));
    }

    /// `VCVTR_hp_int` (`bit7=rz=0`) fica fora de escopo — mesma decisão de `decodeImmediateOrTwoOperandFamily`.
    @Test
    void vcvtHpIntWithoutRzIsUnimplemented() {
        int word = twoOperandHalfWord(0xE, 0xD, false, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    @Test
    void vrintrHpVrintzHpVrintxHpRemainUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, twoOperandHalfWord(0xE, 0x6, false, 2, 3)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, twoOperandHalfWord(0xE, 0x7, false, 2, 3)).kind());
    }

    @Test
    void vldrHpVstrHpDecodeToVfpLoadStoreHalf() {
        int loadWord = vldrVstrHalfWord(0xE, true, true, 1, 2, 4);
        DecodedInstruction loadDecoded = decodeArm(FP16_TEST_ARCH, loadWord);
        assertEquals(InstructionKind.VFP_LOAD_HALF, loadDecoded.kind());
        assertEquals(new IrOp.VfpLoadHalf(2, 1, -1, 16, Condition.AL), liftSingleOp(loadDecoded));

        int storeWord = vldrVstrHalfWord(0xE, false, false, 1, 2, 4);
        DecodedInstruction storeDecoded = decodeArm(FP16_TEST_ARCH, storeWord);
        assertEquals(InstructionKind.VFP_STORE_HALF, storeDecoded.kind());
        assertEquals(new IrOp.VfpStoreHalf(2, 1, -1, -16, Condition.AL), liftSingleOp(storeDecoded));
    }

    // ── 2. Decode/lift — espaço incondicional (`VSEL_hp`/`VMAXNM_hp`/`VMINNM_hp`/`VRINT_hp`/`VCVT_hp`) ──

    @Test
    void vselHpDecodesToVfpSelectHalf() {
        int word = vselHalfWord(2, 1, 2, 3); // cc=2 -> GE.
        assertEquals(new IrOp.VfpSelectHalf(2, 1, 3, Condition.GE, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vmaxnmHpVminnmHpDecodeToVfpAluHalf() {
        int max = maxNmMinNmHalfWord(false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.MAXNM, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, max)));
        int min = maxNmMinNmHalfWord(true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.MINNM, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, min)));
    }

    @Test
    void vrintHpDecodesToVfpRoundHalf() {
        int word = roundHalfWord(0b01, 2, 3); // rm=01 -> ties-even.
        assertEquals(new IrOp.VfpRoundHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_EVEN, 2, 3, Condition.AL),
                liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vcvtHpDecodesToVfpConvertRoundedHalf() {
        int word = convertRoundedHalfWord(0b10, true, 2, 3); // +inf, signed.
        assertEquals(new IrOp.VfpConvertRoundedHalf(AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, true, 2, 3, Condition.AL),
                liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    // ── 3. G8: sem `FP16_ARITHMETIC`, tudo cai em UNIMPLEMENTED (nunca null/misdecode) ─────────

    @Test
    void halfPrecisionConditionalSpaceUnimplementedWithoutFeature() {
        int word = threeRegHalfWord(0xE, 0b011, false, 1, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_V8_TEST_ARCH, word).kind());
    }

    @Test
    void halfPrecisionUnconditionalSpaceUnimplementedWithoutFeature() {
        int word = vselHalfWord(2, 1, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(VFP_V8_TEST_ARCH, word).kind());
    }

    @Test
    void claimsEncodingSpaceIsTrueForHalfPrecisionConditionalSpaceEvenWithoutFeature() {
        int word = threeRegHalfWord(0xE, 0b011, false, 1, 2, 3);
        assertTrue(new VfpDecoder(VFP_V8_TEST_ARCH).claimsEncodingSpace(word));
        assertTrue(new VfpDecoder(FP16_TEST_ARCH).claimsEncodingSpace(word));
    }

    /// Não-regressão: `VADD_sp` (precisão simples de verdade) continua decodificando IGUAL sob
    /// `FP16_TEST_ARCH` — o gate `_hp` não pode roubar o espaço `size=1010`.
    @Test
    void singlePrecisionAluUnaffectedByHalfPrecisionGate() {
        int word = threeRegHalfWord(0xE, 0b011, false, 1, 2, 3) & ~(0xF << 8) | (0xA << 8);
        DecodedInstruction decoded = decodeArm(FP16_TEST_ARCH, word);
        assertEquals(InstructionKind.VFP_ALU, decoded.kind());
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 1, 3, Condition.AL), liftSingleOp(decoded));
    }

    // ── 4. Execução: aritmética real em `binary16`, ponte por `AdvSimdLanes` ───────────────────

    @Test
    void executeVfpAluHalfAddsInHalfPrecision() {
        ArmCore core = newCore();
        setHalf(core, 1, 1.0f);
        setHalf(core, 3, 2.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpAluHalf(IrOp.VfpOperation.ADD, 2, 1, 3, Condition.AL), 0);
        assertEquals(half(3.0f), readHalf(core, 2));
        assertEquals(0, core.vfp().s(2) & 0xFFFF_0000, "bits altos devem ficar zerados");
    }

    @Test
    void executeVfpAluHalfNegAbsManipulateSignBitOnly() {
        ArmCore core = newCore();
        setHalf(core, 3, -1.5f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpAluHalf(IrOp.VfpOperation.ABS, 2, -1, 3, Condition.AL), 0);
        assertEquals(half(1.5f), readHalf(core, 2));
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpAluHalf(IrOp.VfpOperation.NEG, 4, -1, 3, Condition.AL), 0);
        assertEquals(half(1.5f), readHalf(core, 4));
    }

    @Test
    void executeVfpAluHalfFlushToZeroFlushesSubnormalInput() {
        ArmCore core = newCore();
        core.fpscr().setValue(dev.vitorsilverio.armjitter.core.FpscrRegister.FLUSH_TO_ZERO_FLAG);
        core.vfp().setS(1, 0x0200); // subnormal half (expoente=0, mantissa=0x200).
        setHalf(core, 3, 0.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpAluHalf(IrOp.VfpOperation.ADD, 2, 1, 3, Condition.AL), 0);
        assertEquals(0, core.vfp().s(2) & 0xFFFF, "operando subnormal deveria ser flushado (DAZ) antes de somar");
    }

    @Test
    void executeVfpCompareHalfSetsNzcv() {
        ArmCore core = newCore();
        setHalf(core, 1, 1.0f);
        setHalf(core, 2, 2.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpCompareHalf(false, false, 1, 2, Condition.AL), 0);
        assertTrue(core.fpscr().n(), "1.0 < 2.0 deveria setar N");
    }

    @Test
    void executeVfpSelectHalfChoosesByCpsr() {
        ArmCore core = newCore();
        setHalf(core, 1, 5.0f);
        setHalf(core, 2, 9.0f);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro.
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpSelectHalf(3, 1, 2, Condition.EQ, Condition.AL), 0);
        assertEquals(half(5.0f), readHalf(core, 3));
    }

    @Test
    void executeVfpMoveImmediateHalfWritesLowHalfZeroExtended() {
        ArmCore core = newCore();
        core.vfp().setS(2, -1);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpMoveImmediateHalf(2, 0x3C00, Condition.AL), 0);
        assertEquals(0x3C00, core.vfp().s(2));
    }

    @Test
    void executeVfpRoundHalfRoundsTowardPositiveInfinity() {
        ArmCore core = newCore();
        setHalf(core, 1, 1.25f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpRoundHalf(AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, 2, 1, Condition.AL), 0);
        assertEquals(half(2.0f), readHalf(core, 2));
    }

    @Test
    void executeVfpConvertRoundedHalfConvertsToInteger() {
        ArmCore core = newCore();
        setHalf(core, 1, 1.5f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertRoundedHalf(AdvSimdLanes.RoundingMode.TOWARD_POSITIVE_INFINITY, true, 2, 1, Condition.AL), 0);
        assertEquals(2, core.vfp().s(2));
    }

    @Test
    void executeVfpConvertHalfIntBridgesBothDirections() {
        ArmCore core = newCore();
        core.setRegister(0, 0); // não usado diretamente, apenas para não deixar sujo.
        core.vfp().setS(1, 4); // int32 = 4.
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F16, 2, 1, Condition.AL), 0);
        assertEquals(half(4.0f), readHalf(core, 2));

        setHalf(core, 3, 7.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpConvert(IrOp.VfpConversion.F16_TO_S32, 4, 3, Condition.AL), 0);
        assertEquals(7, core.vfp().s(4));
    }

    @Test
    void executeVfpConvertFixedHalfRoundTrips() {
        ArmCore core = newCore();
        setHalf(core, 1, 2.5f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertFixedHalf(true, false, false, 1, 1, Condition.AL), 0);
        assertEquals(5, core.vfp().s(1) & 0xFFFF); // 2.5 * 2^1 = 5.
        core.vfp().setS(1, 5);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertFixedHalf(false, false, false, 1, 1, Condition.AL), 0);
        assertEquals(half(2.5f), readHalf(core, 1));
    }

    @Test
    void executeVfpLoadHalfStoreHalfRoundTrip() {
        ArmCore core = newCore();
        setHalf(core, 1, 3.5f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpStoreHalf(1, 0, -1, 8, Condition.AL), 0);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpLoadHalf(2, 0, -1, 8, Condition.AL), 0);
        assertEquals(half(3.5f), readHalf(core, 2));
        assertEquals(0, core.vfp().s(2) & 0xFFFF_0000);
    }

    // ── 5. "Não inclui": `AsmNativePolicy` recusa todos os novos `IrOp` `_hp` ──────────────────

    @Test
    void asmNativePolicyRefusesAllHalfPrecisionOps() {
        Condition c = Condition.AL;
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpAluHalf(IrOp.VfpOperation.ADD, 0, 1, 2, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpMoveImmediateHalf(0, 0, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpCompareHalf(false, false, 0, 1, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpSelectHalf(0, 1, 2, Condition.EQ, c)));
        assertEquals(false, AsmNativePolicy.supports(
                new IrOp.VfpRoundHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, 0, 1, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpConvertRoundedHalf(
                AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, true, 0, 1, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpConvertFixedHalf(true, false, false, 8, 0, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpLoadHalf(0, 1, -1, 0, c)));
        assertEquals(false, AsmNativePolicy.supports(new IrOp.VfpStoreHalf(0, 1, -1, 0, c)));
    }

    // ── 6. Fechamento de cobertura JaCoCo (rodada dedicada, a pedido do usuário) ────────────────
    // Os testes 1-5 acima provaram DECODE/EXECUÇÃO representativos; esta seção fecha os gaps reais
    // que `mvn -pl core -am test jacoco:report` (HTML linha a linha) expôs: `op1`/`opc2` só
    // parcialmente exercitados em `decodeHalfPrecisionDataProcessing`/
    // `decodeHalfImmediateOrTwoOperandFamily`, combinações reservadas nunca decodificadas,
    // `computeHalfArithmeticBits` só testado para `ADD`, os 8 `execute*Half` novos nunca vistos com
    // condição de bloco falsa, e — o mais sério — o switch por `int Kind` PRIMÁRIO de
    // `IrBlockExecutor#execute` nunca exercitado para nenhum dos 9 `Kind` novos (todos os testes
    // 1-5 chamavam `executeOp` diretamente, que usa o OUTRO switch, por tipo selado).

    @Test
    void vmlaHpVmlsHpDecodeToVfpAluHalf() {
        int mla = threeRegHalfWord(0xE, 0b000, false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.MLA, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, mla)));
        int mls = threeRegHalfWord(0xE, 0b000, true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.MLS, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, mls)));
    }

    @Test
    void vmulHpVnmulHpDecodeToVfpAluHalf() {
        int mul = threeRegHalfWord(0xE, 0b010, false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.MUL, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, mul)));
        int nmul = threeRegHalfWord(0xE, 0b010, true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.NMUL, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, nmul)));
    }

    @Test
    void vdivHpDecodesAndBit6SetIsUndefined() {
        int div = threeRegHalfWord(0xE, 0b100, false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.DIV, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, div)));
        int reserved = threeRegHalfWord(0xE, 0b100, true, 1, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, reserved).kind());
    }

    @Test
    void vnmlsHpVnmlaHpDecodeToVfpAluHalf() {
        int nmls = threeRegHalfWord(0xE, 0b001, false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.NMLS, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, nmls)));
        int nmla = threeRegHalfWord(0xE, 0b001, true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.NMLA, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, nmla)));
    }

    @Test
    void vfnmsHpVfnmaHpDecodeToVfpAluHalf() {
        int fnms = threeRegHalfWord(0xE, 0b101, false, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.FNMS, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, fnms)));
        int fnma = threeRegHalfWord(0xE, 0b101, true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.FNMA, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, fnma)));
    }

    /// `VFMS_hp` (`op1=0b110`, `bit6=1`) com a feature PRESENTE — `vfmaHpRequiresFusedMultiplyAccumulateFeature`
    /// só cobre `bit6=0` (`VFMA_hp`).
    @Test
    void vfmsHpDecodesToVfpAluHalf() {
        int word = threeRegHalfWord(0xE, 0b110, true, 1, 2, 3);
        assertEquals(new IrOp.VfpAluHalf(IrOp.VfpOperation.FMS, 2, 1, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    /// Cobre o ramo `!architecture.has(VFP_FUSED_MULTIPLY_ACCUMULATE)` para as 4 formas fundidas
    /// `_hp` (`VFMA`/`VFMS`/`VFNMA`/`VFNMS`), espelhando a mesma checagem de `sp`/`dp`.
    @Test
    void fusedHalfFormsUndefinedWithoutFusedMultiplyAccumulateFeature() {
        int fma = threeRegHalfWord(0xE, 0b110, false, 1, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_NO_FUSED_TEST_ARCH, fma).kind());
        int fnma = threeRegHalfWord(0xE, 0b101, true, 1, 2, 3);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_NO_FUSED_TEST_ARCH, fnma).kind());
    }

    /// `decodeHalfPrecisionConditionalSpace`: `bits2724=0xE` com `bit4=1` (forma MRC/MSR-shape) não
    /// tem `_hp` — precisa recusar, nunca decodificar como se fosse CDP-shape.
    @Test
    void bit4SetUnderHalfPrecisionConditionalSpaceIsUnimplemented() {
        int word = threeRegHalfWord(0xE, 0b011, false, 1, 2, 3) | (1 << 4);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    /// `decodeUnconditionalHalfPrecision`: `bits[21:20] ∈ {01,10}` não têm forma definida.
    @Test
    void reservedBits2120UnderHalfPrecisionUnconditionalSpaceIsUnimplemented() {
        int bits01 = (0xF << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (1 << 20);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, bits01).kind());
        int bits10 = (0xF << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (2 << 20);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, bits10).kind());
    }

    /// `decodeUnconditionalHalfPrecision`: `bits[21:20]=11` com `bit19=0` não é `VMOVX`/`VINS`
    /// (essas usam `size=1010`, não `1001`) nem `VRINT_hp`/`VCVT_hp` (`bit19=1`) — reservado.
    @Test
    void reservedBit19ZeroUnderHalfPrecisionRoundConvertSpaceIsUnimplemented() {
        int word = (0xF << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (0x3 << 20);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    @Test
    void vrintHpBit6ClearIsReserved() {
        int word = (0xF << 28) | (0xE << 24) | (0x9 << 8) | (1 << 23) | (0x3 << 20) | (1 << 19);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    @Test
    void vrintHpBit7SetIsReserved() {
        int word = roundHalfWord(0b00, 2, 3) | (1 << 7);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    @Test
    void vcvtHpUnsignedDecodesToVfpConvertRoundedHalf() {
        int word = convertRoundedHalfWord(0b11, false, 2, 3);
        assertEquals(new IrOp.VfpConvertRoundedHalf(AdvSimdLanes.RoundingMode.TOWARD_NEGATIVE_INFINITY, false, 2, 3, Condition.AL),
                liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vcmpeHpDecodesSignalOnQuietNaN() {
        int word = twoOperandHalfWord(0xE, 0x4, true, 2, 3);
        assertEquals(new IrOp.VfpCompareHalf(false, true, 2, 3, Condition.AL), liftSingleOp(decodeArm(FP16_TEST_ARCH, word)));
    }

    @Test
    void vcvtFixHpDecodesToVfpConvertFixedHalf() {
        int word = twoOperandHalfWord(0xE, 0xA, true, 2, 3); // op=0,u=0,sx=1(32 bits), imm=vm=3.
        DecodedInstruction decoded = decodeArm(FP16_TEST_ARCH, word);
        assertEquals(InstructionKind.VFP_CONVERT_FIXED_HALF, decoded.kind());
        IrOp.VfpConvertFixedHalf lifted = (IrOp.VfpConvertFixedHalf) liftSingleOp(decoded);
        assertEquals(false, lifted.toFixedPoint());
        assertEquals(false, lifted.unsignedFixedPoint());
        assertEquals(true, lifted.fixedPointIs32Bit());
        assertEquals(2, lifted.vd());
        assertEquals(29, lifted.fractionBits()); // 32 - imm(3).
    }

    /// Ramo OPOSTO de `vcvtFixHpDecodesToVfpConvertFixedHalf`: `opc2=0xF` -> `toFixedPoint=true`,
    /// `unsignedFixedPoint=true`; `bit7=false` -> `fixedPointIs32Bit=false`.
    @Test
    void vcvtFixHpDecodesOppositeFlagCombination() {
        int word = twoOperandHalfWord(0xE, 0xF, false, 2, 3);
        IrOp.VfpConvertFixedHalf lifted = (IrOp.VfpConvertFixedHalf) liftSingleOp(decodeArm(FP16_TEST_ARCH, word));
        assertEquals(true, lifted.toFixedPoint());
        assertEquals(true, lifted.unsignedFixedPoint());
        assertEquals(false, lifted.fixedPointIs32Bit());
    }

    /// `decodeHalfPrecisionLoadStore`: `bit21=1` (formaria `VLDM`/`VSTM` decrement-before) não
    /// existe em meia precisão.
    @Test
    void vldmVstmDbReservedUnderHalfPrecisionIsUnimplemented() {
        int word = vldrVstrHalfWord(0xE, true, true, 1, 2, 4) | (1 << 21);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(FP16_TEST_ARCH, word).kind());
    }

    /// `vfpExpandImmHalf`: cobre `sign=true`/`notBit6=false` (imm8 com bit7 e bit6 setados) — o
    /// teste original só exercitava `sign=false`/`notBit6=true`.
    @Test
    void vmovImmHpExpandsSignAndBit6Correctly() {
        int word = vmovImmHalfWord(0xE, 4, 0xC0); // bit7=1 (sign), bit6=1.
        IrOp.VfpMoveImmediateHalf lifted = (IrOp.VfpMoveImmediateHalf) liftSingleOp(decodeArm(FP16_TEST_ARCH, word));
        assertEquals(0xB000, lifted.immediateBits());
    }

    // ── 7. Execução: fecha `computeHalfArithmeticBits` (só `ADD`/`ABS`/`NEG` tinham teste) ──────

    private static void execAlu(ArmCore core, IrOp.VfpOperation op, int vd, int vn, int vm) {
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpAluHalf(op, vd, vn, vm, Condition.AL), 0);
    }

    @Test
    void executeVfpAluHalfCoversRemainingArithmeticOperations() {
        ArmCore core = newCore();
        setHalf(core, 1, 4.0f); // vn
        setHalf(core, 2, 2.0f); // vm

        execAlu(core, IrOp.VfpOperation.SUB, 3, 1, 2);
        assertEquals(half(2.0f), readHalf(core, 3));

        execAlu(core, IrOp.VfpOperation.MUL, 3, 1, 2);
        assertEquals(half(8.0f), readHalf(core, 3));

        execAlu(core, IrOp.VfpOperation.DIV, 3, 1, 2);
        assertEquals(half(2.0f), readHalf(core, 3));

        execAlu(core, IrOp.VfpOperation.NMUL, 3, 1, 2);
        assertEquals(half(-8.0f), readHalf(core, 3));

        setHalf(core, 4, 4.0f);
        execAlu(core, IrOp.VfpOperation.SQRT, 5, -1, 4);
        assertEquals(half(2.0f), readHalf(core, 5));

        setHalf(core, 6, 1.0f);
        execAlu(core, IrOp.VfpOperation.MLA, 6, 1, 2); // 1 + 4*2 = 9.
        assertEquals(half(9.0f), readHalf(core, 6));

        setHalf(core, 7, 1.0f);
        execAlu(core, IrOp.VfpOperation.MLS, 7, 1, 2); // 1 - 4*2 = -7.
        assertEquals(half(-7.0f), readHalf(core, 7));

        setHalf(core, 8, 1.0f);
        execAlu(core, IrOp.VfpOperation.NMLA, 8, 1, 2); // -1 - 4*2 = -9.
        assertEquals(half(-9.0f), readHalf(core, 8));

        setHalf(core, 9, 1.0f);
        execAlu(core, IrOp.VfpOperation.NMLS, 9, 1, 2); // -1 + 4*2 = 7.
        assertEquals(half(7.0f), readHalf(core, 9));

        setHalf(core, 10, 1.0f);
        execAlu(core, IrOp.VfpOperation.FMA, 10, 1, 2); // fma(4,2,1) = 9.
        assertEquals(half(9.0f), readHalf(core, 10));

        setHalf(core, 11, 1.0f);
        execAlu(core, IrOp.VfpOperation.FMS, 11, 1, 2); // fma(-4,2,1) = -7.
        assertEquals(half(-7.0f), readHalf(core, 11));

        setHalf(core, 12, 1.0f);
        execAlu(core, IrOp.VfpOperation.FNMA, 12, 1, 2); // fma(-4,2,-1) = -9.
        assertEquals(half(-9.0f), readHalf(core, 12));

        setHalf(core, 13, 1.0f);
        execAlu(core, IrOp.VfpOperation.FNMS, 13, 1, 2); // fma(4,2,-1) = 7.
        assertEquals(half(7.0f), readHalf(core, 13));

        execAlu(core, IrOp.VfpOperation.MAXNM, 14, 1, 2);
        assertEquals(half(4.0f), readHalf(core, 14));

        execAlu(core, IrOp.VfpOperation.MINNM, 15, 1, 2);
        assertEquals(half(2.0f), readHalf(core, 15));
    }

    // ── 8. Execução: ramos de `executeVfpCompareHalf`/`executeVfpSelectHalf` ainda não vistos ───

    @Test
    void executeVfpCompareHalfCoversUnorderedEqualGreaterAndCompareWithZero() {
        ArmCore core = newCore();
        core.vfp().setS(1, 0x7E00); // NaN half (expoente todo 1, mantissa != 0).
        setHalf(core, 2, 1.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpCompareHalf(false, false, 1, 2, Condition.AL), 0);
        assertTrue(core.fpscr().v(), "NaN deveria setar V (unordered)");

        // NaN só no SEGUNDO operando (cobre o lado direito do `||` de `unordered`).
        setHalf(core, 8, 1.0f);
        core.vfp().setS(9, 0x7E00);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpCompareHalf(false, false, 8, 9, Condition.AL), 0);
        assertTrue(core.fpscr().v(), "NaN no segundo operando também deveria setar V (unordered)");

        setHalf(core, 3, 2.0f);
        setHalf(core, 4, 2.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpCompareHalf(false, false, 3, 4, Condition.AL), 0);
        assertTrue(core.fpscr().z(), "iguais deveria setar Z");

        setHalf(core, 5, 5.0f);
        setHalf(core, 6, 2.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpCompareHalf(false, false, 5, 6, Condition.AL), 0);
        assertTrue(core.fpscr().c() && !core.fpscr().n() && !core.fpscr().z(), "maior deveria só setar C");

        setHalf(core, 7, 0.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpCompareHalf(true, false, 7, -1, Condition.AL), 0);
        assertTrue(core.fpscr().z(), "compara com zero: 0.0 == 0.0");
    }

    @Test
    void executeVfpSelectHalfChoosesVmWhenSelectConditionFalse() {
        ArmCore core = newCore();
        setHalf(core, 1, 5.0f);
        setHalf(core, 2, 9.0f);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> NE falso.
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core, new IrOp.VfpSelectHalf(3, 1, 2, Condition.NE, Condition.AL), 0);
        assertEquals(half(9.0f), readHalf(core, 3));
    }

    /// Fecha `fixedToDouble`/`doubleToFixed` com `unsignedFixedPoint=true` nas duas direções (só
    /// `false` tinha teste).
    @Test
    void executeVfpConvertFixedHalfUnsignedBothDirections() {
        ArmCore core = newCore();
        setHalf(core, 1, 5.0f);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertFixedHalf(true, true, false, 1, 1, Condition.AL), 0);
        assertEquals(10, core.vfp().s(1) & 0xFFFF); // 5.0 * 2^1 = 10.

        core.vfp().setS(1, 10);
        new IrBlockExecutor(FP16_TEST_ARCH).executeOp(core,
                new IrOp.VfpConvertFixedHalf(false, true, false, 1, 1, Condition.AL), 0);
        assertEquals(half(5.0f), readHalf(core, 1));
    }

    // ── 9. Guard de condição de BLOCO falsa nos 8 `execute*Half` novos (além de `VfpAluHalf`,
    // já coberto em "4. Execução") ────────────────────────────────────────────────────────────

    @Test
    void allNewExecuteMethodsSkipWhenBlockConditionFalse() {
        ArmCore core = newCore();
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> NE falso.
        Condition falseCond = Condition.NE;
        IrBlockExecutor executor = new IrBlockExecutor(FP16_TEST_ARCH);
        int fpscrBefore = core.fpscr().value();

        core.vfp().setS(5, -1);
        executor.executeOp(core, new IrOp.VfpAluHalf(IrOp.VfpOperation.ADD, 5, 1, 2, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        executor.executeOp(core, new IrOp.VfpMoveImmediateHalf(5, 0x3C00, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        executor.executeOp(core, new IrOp.VfpCompareHalf(false, false, 1, 2, falseCond), 0);
        assertEquals(fpscrBefore, core.fpscr().value(), "compare com condição falsa não deveria tocar FPSCR");

        executor.executeOp(core, new IrOp.VfpSelectHalf(5, 1, 2, Condition.EQ, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        executor.executeOp(core, new IrOp.VfpRoundHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, 5, 1, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        executor.executeOp(core, new IrOp.VfpConvertRoundedHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, true, 5, 1, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        executor.executeOp(core, new IrOp.VfpConvertFixedHalf(true, false, false, 8, 5, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        executor.executeOp(core, new IrOp.VfpLoadHalf(5, 0, -1, 0, falseCond), 0);
        assertEquals(-1, core.vfp().s(5));

        core.memory().write32(20, 0);
        setHalf(core, 6, 3.0f);
        executor.executeOp(core, new IrOp.VfpStoreHalf(6, 0, -1, 20, falseCond), 0);
        assertEquals(0, core.memory().read16(20), "store com condição falsa não deveria escrever memória");
    }

    // ── 10. Fechamento do switch PRIMÁRIO por `int Kind` de `IrBlockExecutor#execute` — os testes
    // 1-9 usam `executeOp` (switch por tipo selado); nenhum dos 9 `Kind` novos tinha sido visto
    // pelo caminho de despacho de BLOCO de verdade até esta rodada ────────────────────────────

    @Test
    void allNewHalfPrecisionOpsExecuteThroughPrimaryBlockDispatch() {
        ArmCore core = newCore();
        setHalf(core, 1, 3.0f);
        setHalf(core, 2, 4.0f);
        core.cpsr().setNzcv(false, true, false, false); // Z=1 -> EQ verdadeiro (usado pelo VfpSelectHalf abaixo).

        IrBlock.Builder builder = IrBlock.builder(0);
        builder.add(new IrOp.VfpAluHalf(IrOp.VfpOperation.ADD, 3, 1, 2, Condition.AL));
        builder.add(new IrOp.VfpMoveImmediateHalf(4, 0x3C00, Condition.AL));
        builder.add(new IrOp.VfpCompareHalf(false, false, 1, 2, Condition.AL));
        builder.add(new IrOp.VfpSelectHalf(5, 1, 2, Condition.EQ, Condition.AL));
        builder.add(new IrOp.VfpRoundHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, 6, 1, Condition.AL));
        builder.add(new IrOp.VfpConvertRoundedHalf(AdvSimdLanes.RoundingMode.NEAREST_TIES_AWAY, true, 7, 1, Condition.AL));
        builder.add(new IrOp.VfpConvertFixedHalf(true, false, false, 1, 1, Condition.AL));
        builder.add(new IrOp.VfpStoreHalf(2, 0, -1, 16, Condition.AL));
        builder.add(new IrOp.VfpLoadHalf(8, 0, -1, 16, Condition.AL));
        builder.add(new IrOp.Cycle(9));
        builder.add(new IrOp.Fetch(4, 36));
        IrBlock block = builder.endPc(36).sealed();
        new IrBlockExecutor(FP16_TEST_ARCH).execute(block, core);

        assertEquals(half(7.0f), readHalf(core, 3));
        assertEquals(0x3C00, core.vfp().s(4));
        assertEquals(half(3.0f), readHalf(core, 5), "EQ verdadeiro deveria escolher vn (3.0)");
        assertEquals(half(3.0f), readHalf(core, 6));
        assertEquals(3, core.vfp().s(7));
        assertEquals(6, core.vfp().s(1) & 0xFFFF, "3.0 * 2^1 = 6 (fixed-point)");
        assertEquals(half(4.0f), readHalf(core, 8), "load após store deveria ler o mesmo valor gravado");
    }

    // ── 11. Fechamento de `isTerminal`: bloco de 2 instruções `_hp` via lifter REAL (não só
    // `liftSingleOp`) — mesma rigor de `VfpUnconditionalMovxVinsTest` (B14.6a) ──────────────────

    @Test
    void vaddHpDoesNotTerminateBlockSecondInstructionStillLifted() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, threeRegHalfWord(0xE, 0b011, false, 1, 2, 3));
        memory.put32(4, threeRegHalfWord(0xE, 0b011, true, 1, 2, 3));
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(FP16_TEST_ARCH), new StandardIrBuilder());
        IrBlock block = lifter.lift(memory, 0, 2, 0);
        assertEquals(8, block.endPc(), "bloco deveria conter as DUAS instruções, não terminar na primeira");
        long aluHalfOps = block.operations().stream().filter(op -> op instanceof IrOp.VfpAluHalf).count();
        assertEquals(2, aluHalfOps);
    }
}
