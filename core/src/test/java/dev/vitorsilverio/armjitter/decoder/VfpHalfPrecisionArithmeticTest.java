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
}
