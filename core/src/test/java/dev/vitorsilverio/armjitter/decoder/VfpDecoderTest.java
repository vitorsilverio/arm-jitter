package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/// B3.5 — decoder VFP (CP10/CP11, ARM e Thumb-2). Oráculo: QEMU `target/arm/tcg/vfp.decode`
/// (ver `VfpDecoder`). Vetores manuais montados bit a bit a partir dos padrões do QEMU citados nos
/// comentários de `VfpDecoder`.
class VfpDecoderTest {
    private static final ArmArchitecture VFP_TEST_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K_THUMB2, "ARMv7-TestVfp", ArmFeature.VFPV2);
    private static final ArmArchitecture VFP_TEST_ARCH = VFP_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_TEST_FEATURES), new CoprocessorDecoder()))
            .withThumb32DecoderExtensions(thumb32ExtensionsWithVfpFirst());

    /// B9.6: mesma base de {@link #VFP_TEST_ARCH}, mais {@link ArmFeature#VFP_FUSED_MULTIPLY_ACCUMULATE}
    /// (VFPv4) — só para os testes de `VFMA`/`VFMS`/`VFNMA`/`VFNMS`; {@link #VFP_TEST_ARCH} (sem a
    /// feature) continua sendo o "MPCore" desta suíte, usado para provar a exclusão.
    private static final ArmArchitecture VFP_FUSED_TEST_FEATURES =
            ArmArchitecture.extending(VFP_TEST_FEATURES, "ARMv7-TestVfpFused",
                    ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE);
    private static final ArmArchitecture VFP_FUSED_TEST_ARCH = VFP_FUSED_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_FUSED_TEST_FEATURES), new CoprocessorDecoder()));

    /// B13.1: mesma base de {@link #VFP_TEST_ARCH}, mais {@link ArmFeature#VFPV3_D32} — só para
    /// provar que o gate de `D16`-`D31` de {@link VfpDecoder#validDoubleRegister} ABRE com a
    /// feature. Nenhum preset real a declara ainda; {@link #VFP_TEST_ARCH} (sem a feature) segue
    /// recusando `D16`+, comportamento idêntico ao anterior à B13.1.
    private static final ArmArchitecture VFP_D32_TEST_FEATURES =
            ArmArchitecture.extending(VFP_TEST_FEATURES, "ARMv7-TestVfpD32", ArmFeature.VFPV3_D32);
    private static final ArmArchitecture VFP_D32_TEST_ARCH = VFP_D32_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_D32_TEST_FEATURES), new CoprocessorDecoder()));

    /// B22.2: mesma base de {@link #VFP_TEST_ARCH}, mais {@link ArmFeature#HALF_PRECISION_FP} — só
    /// para provar que `VMOV_half` decodifica e executa COM a feature. Nenhum preset real a declara;
    /// {@link #VFP_TEST_ARCH} (sem a feature) recusa `VMOV_half` com `UNIMPLEMENTED` (não mais um
    /// `IrOp.Coprocessor` espúrio — a violação de G8 que a B22.2 fechou).
    private static final ArmArchitecture VFP_HALF_TEST_FEATURES =
            ArmArchitecture.extending(VFP_TEST_FEATURES, "ARMv7-TestVfpHalf", ArmFeature.HALF_PRECISION_FP);
    private static final ArmArchitecture VFP_HALF_TEST_ARCH = VFP_HALF_TEST_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_HALF_TEST_FEATURES), new CoprocessorDecoder()));

    private static List<DecoderExtension> thumb32ExtensionsWithVfpFirst() {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new Thumb2VfpDecoder(VFP_TEST_FEATURES));
        extensions.addAll(ArmArchitecture.ARMV6K_THUMB2.thumb32DecoderExtensions());
        return extensions;
    }

    private static final int COND_AL = 0xE;

    // ── Helpers de codificação: nibble/extensão de registrador VFP (ver VfpDecoder#registerNumber) ──

    private static int nibbleOf(int combined, boolean doublePrecision) {
        return doublePrecision ? combined & 0xF : combined >>> 1;
    }

    private static int extOf(int combined, boolean doublePrecision) {
        return doublePrecision ? (combined >>> 4) & 1 : combined & 1;
    }

    private static int size(boolean doublePrecision) {
        return doublePrecision ? 0xB : 0xA;
    }

    /// Aritmética de 3 registradores: VMLA/VMLS/VMUL/VNMUL/VADD/VSUB/VDIV (vfp.decode, ex.
    /// `VADD_sp ---- 1110 0.11 .... .... 1010 .0.0 ....`).
    private static int vfpAluWord(int op1, boolean bit6, boolean doublePrecision, int vd, int vn, int vm) {
        int word = (COND_AL << 28) | (0xE << 24);
        word |= ((op1 >>> 2) & 1) << 23;
        word |= extOf(vd, doublePrecision) << 22;
        word |= ((op1 >>> 1) & 1) << 21;
        word |= (op1 & 1) << 20;
        word |= nibbleOf(vn, doublePrecision) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= extOf(vn, doublePrecision) << 7;
        word |= (bit6 ? 1 : 0) << 6;
        word |= extOf(vm, doublePrecision) << 5;
        word |= nibbleOf(vm, doublePrecision);
        return word;
    }

    /// Família de imediato/2-operando/compare/convert (op1=0b111): `VMOV_reg`/`VABS`/`VNEG`/
    /// `VSQRT`/`VCVT_sp`/`VCVT_dp`/`VCVT_int`/`VCVT_*_int` (vm usado) — bit6=1, opc2 seleciona.
    private static int vfpTwoOperandWord(int opc2, boolean bit7, boolean doublePrecision, int vd, int vm) {
        int word = (COND_AL << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd, doublePrecision) << 22;
        word |= opc2 << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= (bit7 ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= extOf(vm, doublePrecision) << 5;
        word |= nibbleOf(vm, doublePrecision);
        return word;
    }

    /// Família de imediato/2-operando com Vd/Vm de precisões DIFERENTES (`VCVT_sp`/`VCVT_dp`
    /// precisão simples<->dupla, `VCVT_int` inteiro->float, `VCVT_*_int` float->inteiro) — a
    /// versão de {@link #vfpTwoOperandWord} não serve porque ela assume a MESMA precisão nos dois
    /// lados. `size4` é o valor cru do campo `size` (bits[11:8]).
    private static int vfpAsymmetricTwoOperandWord(int opc2, boolean bit7, int size4,
            int vd, boolean vdDouble, int vm, boolean vmDouble) {
        int word = (COND_AL << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd, vdDouble) << 22;
        word |= opc2 << 16;
        word |= nibbleOf(vd, vdDouble) << 12;
        word |= size4 << 8;
        word |= (bit7 ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= extOf(vm, vmDouble) << 5;
        word |= nibbleOf(vm, vmDouble);
        return word;
    }

    /// `VMOV.F32`/`VMOV.F64 Vd,#imm`: bit6=0, imm8 em bits[19:16]/bits[3:0].
    private static int vfpMoveImmWord(boolean doublePrecision, int vd, int imm8) {
        int word = (COND_AL << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd, doublePrecision) << 22;
        word |= ((imm8 >>> 4) & 0xF) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= imm8 & 0xF;
        return word;
    }

    /// `VCMP`/`VCMPE` (com ou sem `#0.0`): opc2=0b0100/0b0101, bit7=E (VCMPE).
    private static int vfpCmpWord(boolean compareWithZero, boolean signalOnQuietNaN, boolean doublePrecision,
            int vd, int vm) {
        return vfpTwoOperandWord(compareWithZero ? 0x5 : 0x4, signalOnQuietNaN, doublePrecision, vd, vm);
    }

    /// `VLDR`/`VSTR`: `---- 1101 u . 0 l rn(4) vd(4) size imm(8)`.
    private static int vfpLoadStoreWord(boolean load, boolean add, boolean doublePrecision, int vd, int rn, int imm8) {
        int word = (COND_AL << 28) | (0xD << 24);
        word |= (add ? 1 : 0) << 23;
        word |= extOf(vd, doublePrecision) << 22;
        word |= (load ? 1 : 0) << 20;
        word |= rn << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= imm8 & 0xFF;
        return word;
    }

    /// `VLDM`/`VSTM` increment-after (P=0,U=1): `---- 1100 1 . w l rn(4) vd(4) size imm(8)`.
    private static int vfpMultipleIaWord(boolean load, boolean writeback, boolean doublePrecision,
            int firstRegister, int rn, int imm8) {
        int word = (COND_AL << 28) | (0xC << 24) | (1 << 23);
        word |= extOf(firstRegister, doublePrecision) << 22;
        word |= (writeback ? 1 : 0) << 21;
        word |= (load ? 1 : 0) << 20;
        word |= rn << 16;
        word |= nibbleOf(firstRegister, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= imm8 & 0xFF;
        return word;
    }

    /// `VLDM`/`VSTM` decrement-before com writeback (P=1,U=0,W=1 — inclui `VPUSH`/`VPOP`):
    /// `---- 1101 0.1 l rn(4) vd(4) size imm(8)`.
    private static int vfpMultipleDbWord(boolean load, boolean doublePrecision, int firstRegister, int rn, int imm8) {
        int word = (COND_AL << 28) | (0xD << 24) | (1 << 21);
        word |= extOf(firstRegister, doublePrecision) << 22;
        word |= (load ? 1 : 0) << 20;
        word |= rn << 16;
        word |= nibbleOf(firstRegister, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= imm8 & 0xFF;
        return word;
    }

    /// `VMOV Rt,Sn`/`VMOV Sn,Rt` (FMRS/FMSR): `---- 1110 000 l vn(4) rt(4) 1010 . 001 0000`.
    private static int vfpCoreTransferWord(boolean load, int rt, int vn) {
        int word = (COND_AL << 28) | (0xE << 24) | (load ? 1 : 0) << 20;
        word |= nibbleOf(vn, false) << 16;
        word |= rt << 12;
        word |= 0xA << 8;
        word |= extOf(vn, false) << 7;
        word |= 0b0010000;
        return word;
    }

    /// `VMSR`/`VMRS FPSCR` (FMXR/FMRX): `---- 1110 111 l reg(4) rt(4) 1010 0001 0000`.
    private static int vfpSystemTransferWord(boolean read, int rt, int reg) {
        int word = (COND_AL << 28) | (0xE << 24) | (0x7 << 21) | (read ? 1 : 0) << 20;
        word |= reg << 16;
        word |= rt << 12;
        word |= 0xA << 8;
        word |= 0x10;
        return word;
    }

    /// `VMOV_64_dp` (FMRRD/FMDRR): `---- 1100 010 op rt2(4) rt(4) 1011 00 . 1 vm(4)`.
    private static int vfpCorePairTransferWord(boolean toArmRegisters, int rt, int rt2, int vm) {
        int word = (COND_AL << 28) | (0xC << 24) | (0b010 << 21) | (toArmRegisters ? 1 : 0) << 20;
        word |= rt2 << 16;
        word |= rt << 12;
        word |= 0xB << 8;
        word |= extOf(vm, true) << 5;
        word |= 1 << 4;
        word |= nibbleOf(vm, true);
        return word;
    }

    /// Genérico `MRC`: `cccc 1110 ooo1 nnnn dddd pppp ooo1 mmmm` (mesmo padrão de `CoprocessorDecoder`).
    private static int mrcWord(int coprocessor) {
        return (COND_AL << 28) | 0x0E10_0010 | (coprocessor << 8);
    }

    private static DecodedInstruction decodeArm(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(VFP_TEST_ARCH).decode(memory, 0);
    }

    private static DecodedInstruction decodeThumb32(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, word >>> 16);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(VFP_TEST_ARCH).decode(memory, 0);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        List<IrOp> ops = block.sealed().operations();
        return ops.get(0);
    }

    // ── 1. Por grupo: encode manual -> decode -> IR esperada, single E double ──────────────

    @Test
    void addSingleDecodesToVfpAlu() {
        int word = vfpAluWord(0b011, false, false, 2, 0, 1);
        DecodedInstruction decoded = decodeArm(word);
        assertEquals(InstructionKind.VFP_ALU, decoded.kind());
        IrOp op = liftSingleOp(decoded);
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.ADD, false, 2, 0, 1, Condition.AL), op);
    }

    @Test
    void subDoubleDecodesToVfpAlu() {
        int word = vfpAluWord(0b011, true, true, 3, 1, 2);
        IrOp op = liftSingleOp(decodeArm(word));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.SUB, true, 3, 1, 2, Condition.AL), op);
    }

    @Test
    void mulNmulSelectedByBit6() {
        IrOp mul = liftSingleOp(decodeArm(vfpAluWord(0b010, false, false, 4, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.MUL, false, 4, 0, 1, Condition.AL), mul);
        IrOp nmul = liftSingleOp(decodeArm(vfpAluWord(0b010, true, false, 4, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.NMUL, false, 4, 0, 1, Condition.AL), nmul);
    }

    @Test
    void mlaMlsSelectedByBit6() {
        IrOp mla = liftSingleOp(decodeArm(vfpAluWord(0b000, false, true, 5, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.MLA, true, 5, 0, 1, Condition.AL), mla);
        IrOp mls = liftSingleOp(decodeArm(vfpAluWord(0b000, true, true, 5, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.MLS, true, 5, 0, 1, Condition.AL), mls);
    }

    /// `op1==0b001`: ao contrário de VMLA/VMLS e VMUL/VNMUL, aqui `bit6==1` é a forma **negada
    /// do acumulador COM produto negado** (VNMLA) e `bit6==0` é VNMLS — ordem invertida em relação
    /// aos vizinhos (ARM ARM A8.8.337).
    @Test
    void nmlsNmlaSelectedByBit6() {
        IrOp nmls = liftSingleOp(decodeArm(vfpAluWord(0b001, false, true, 5, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.NMLS, true, 5, 0, 1, Condition.AL), nmls);
        IrOp nmla = liftSingleOp(decodeArm(vfpAluWord(0b001, true, true, 5, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.NMLA, true, 5, 0, 1, Condition.AL), nmla);
        IrOp nmlsSingle = liftSingleOp(decodeArm(vfpAluWord(0b001, false, false, 2, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.NMLS, false, 2, 0, 1, Condition.AL), nmlsSingle);
    }

    /// Encoding LITERAL emitido pelo gcc do devkitARM, achado no `textured_cube` dos exemplos 3DS
    /// — era ele que caía em instrução indefinida e derrubava 5 exemplos de uma vez.
    @Test
    void vnmlsF64EncodingRealDoDevkitArm() {
        IrOp op = liftSingleOp(decodeArm(0xEE171B0C));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.NMLS, true, 1, 7, 12, Condition.AL), op);
    }

    @Test
    void divSingle() {
        IrOp op = liftSingleOp(decodeArm(vfpAluWord(0b100, false, false, 6, 0, 1)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.DIV, false, 6, 0, 1, Condition.AL), op);
    }

    @Test
    void divWithBit6SetIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpAluWord(0b100, true, false, 6, 0, 1)).kind());
    }

    // ── B13.1: gate de D16-D31 (VFPV3_D32) ──

    @Test
    void doublePrecisionRegisterAboveD15IsUndefinedWithoutVfpv3D32() {
        // VADD.F64 D16, D0, D1 — sem a feature, comportamento idêntico ao anterior à B13.1.
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpAluWord(0b011, false, true, 16, 0, 1)).kind());
    }

    @Test
    void doublePrecisionRegisterAboveD15DecodesWithVfpv3D32() {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, vfpAluWord(0b011, false, true, 16, 0, 1));
        DecodedInstruction decoded = new ArmDecoder(VFP_D32_TEST_ARCH).decode(memory, 0);
        assertEquals(InstructionKind.VFP_ALU, decoded.kind());
        IrOp op = liftSingleOp(decoded);
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.ADD, true, 16, 0, 1, Condition.AL), op);
    }

    // ── B9.6: VFMA/VFMS/VFNMA/VFNMS (fundidas, VFPv4) — mesmo espaço `op1` de VMLA/VDIV acima ──

    private static DecodedInstruction decodeArmFused(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(VFP_FUSED_TEST_ARCH).decode(memory, 0);
    }

    /// `op1=0b110`: `bit6=0` é `VFMA`, `bit6=1` é `VFMS` — MESMO bit6 dos vizinhos `VMLA`/`VMLS`.
    /// Encoding real conferido com `arm-none-eabi-as -mfpu=vfpv4` (devkitARM): `vfma.f32 s0,s1,s2`
    /// → `0xeea00a81`, `vfms.f32 s0,s1,s2` → `0xeea00ac1`.
    @Test
    void vfmaVfmsSelectedByBit6() {
        assertEquals(0xEEA00A81, vfpAluWord(0b110, false, false, 0, 1, 2));
        assertEquals(0xEEA00AC1, vfpAluWord(0b110, true, false, 0, 1, 2));
        IrOp fma = liftSingleOp(decodeArmFused(vfpAluWord(0b110, false, false, 0, 1, 2)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.FMA, false, 0, 1, 2, Condition.AL), fma);
        IrOp fms = liftSingleOp(decodeArmFused(vfpAluWord(0b110, true, false, 0, 1, 2)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.FMS, false, 0, 1, 2, Condition.AL), fms);
    }

    /// `op1=0b101`: ordem invertida (mesmo padrão de `VNMLA`/`VNMLS`, ver `nmlsNmlaSelectedByBit6`)
    /// — `bit6=0` é `VFNMS`, `bit6=1` é `VFNMA`. Encoding real: `vfnma.f32 s0,s1,s2` → `0xee900ac1`,
    /// `vfnms.f32 s0,s1,s2` → `0xee900a81`; `vfma.f64 d0,d1,d2` → `0xeea10b02`, `vfnma.f64 d3,d4,d5`
    /// (Thumb-2) → `0xee943b45`.
    @Test
    void vfnmsVfnmaSelectedByBit6() {
        assertEquals(0xEE900AC1, vfpAluWord(0b101, true, false, 0, 1, 2));
        assertEquals(0xEE900A81, vfpAluWord(0b101, false, false, 0, 1, 2));
        IrOp fnma = liftSingleOp(decodeArmFused(vfpAluWord(0b101, true, false, 0, 1, 2)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.FNMA, false, 0, 1, 2, Condition.AL), fnma);
        IrOp fnms = liftSingleOp(decodeArmFused(vfpAluWord(0b101, false, false, 0, 1, 2)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.FNMS, false, 0, 1, 2, Condition.AL), fnms);
    }

    @Test
    void vfmaDoublePrecisionRealEncoding() {
        assertEquals(0xEEA10B02, vfpAluWord(0b110, false, true, 0, 1, 2));
        IrOp op = liftSingleOp(decodeArmFused(vfpAluWord(0b110, false, true, 0, 1, 2)));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.FMA, true, 0, 1, 2, Condition.AL), op);
    }

    /// Thumb-2: `vfnma.f64 d3,d4,d5` → `0xee943b45` (real, devkitARM), decodificado via
    /// `Thumb2VfpDecoder` (mesma casca fina que reusa `VfpDecoder`).
    @Test
    void vfnmaDoublePrecisionThumb2RealEncoding() {
        int word = vfpAluWord(0b101, true, true, 3, 4, 5);
        assertEquals(0xEE943B45, word);
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, word >>> 16);
        memory.put16(2, word & 0xFFFF);
        ArmArchitecture fusedThumb2 = VFP_FUSED_TEST_FEATURES.withThumb32DecoderExtensions(List.of(
                new Thumb2VfpDecoder(VFP_FUSED_TEST_FEATURES)));
        DecodedInstruction decoded = new ThumbDecoder(fusedThumb2).decode(memory, 0);
        IrOp op = liftSingleOp(decoded);
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.FNMA, true, 3, 4, 5, Condition.AL), op);
    }

    /// B9.6/triagem: sem `VFP_FUSED_MULTIPLY_ACCUMULATE` (arquitetura com só VFPv2, como o ARM11
    /// MPCore real) os 4 encodings caem em `UNDEFINED` — nunca foram reivindicados por nenhum outro
    /// dispatch antes desta task (G8), então a ausência da feature não corrompe silenciosamente em
    /// outra instrução.
    @Test
    void fusedVfpFamilyUndefinedWithoutFeature() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpAluWord(0b110, false, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpAluWord(0b110, true, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpAluWord(0b101, false, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpAluWord(0b101, true, false, 0, 1, 2)).kind());
    }

    @Test
    void copyAbsNegSqrtUnaryOps() {
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.COPY, false, 1, -1, 0, Condition.AL),
                liftSingleOp(decodeArm(vfpTwoOperandWord(0x0, false, false, 1, 0))));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.ABS, false, 1, -1, 0, Condition.AL),
                liftSingleOp(decodeArm(vfpTwoOperandWord(0x0, true, false, 1, 0))));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.NEG, true, 1, -1, 0, Condition.AL),
                liftSingleOp(decodeArm(vfpTwoOperandWord(0x1, false, true, 1, 0))));
        assertEquals(new IrOp.VfpAlu(IrOp.VfpOperation.SQRT, true, 1, -1, 0, Condition.AL),
                liftSingleOp(decodeArm(vfpTwoOperandWord(0x1, true, true, 1, 0))));
    }

    @Test
    void vfpMoveImmediateSingleAndDouble() {
        DecodedInstruction single = decodeArm(vfpMoveImmWord(false, 3, 0x70));
        assertEquals(InstructionKind.VFP_MOVE_IMMEDIATE, single.kind());
        assertEquals(3, single.destinationRegister());
        assertEquals(0x70, single.immediate());
        assertEquals(false, single.signedAccess());

        DecodedInstruction dbl = decodeArm(vfpMoveImmWord(true, 4, 0x3F));
        assertEquals(4, dbl.destinationRegister());
        assertEquals(0x3F, dbl.immediate());
        assertEquals(true, dbl.signedAccess());
    }

    @Test
    void compareRegAndZeroSingleAndDouble() {
        IrOp cmpReg = liftSingleOp(decodeArm(vfpCmpWord(false, false, false, 2, 3)));
        assertEquals(new IrOp.VfpCompare(false, false, false, 2, 3, Condition.AL), cmpReg);

        IrOp cmpZero = liftSingleOp(decodeArm(vfpCmpWord(true, false, true, 2, 0)));
        assertEquals(new IrOp.VfpCompare(true, true, false, 2, -1, Condition.AL), cmpZero);

        IrOp cmpe = liftSingleOp(decodeArm(vfpCmpWord(false, true, false, 2, 3)));
        assertEquals(new IrOp.VfpCompare(false, false, true, 2, 3, Condition.AL), cmpe);
    }

    @Test
    void convertPrecisionSingleDouble() {
        // VCVT_sp (size=single=0xA): fonte simples (Vm), destino dobro (Vd) -> F32_TO_F64.
        IrOp f32ToF64 = liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0x7, true, 0xA, 1, true, 5, false)));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_F64, 1, 5, Condition.AL), f32ToF64);

        // VCVT_dp (size=dobro=0xB): fonte dobro (Vm), destino simples (Vd) -> F64_TO_F32.
        IrOp f64ToF32 = liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0x7, true, 0xB, 1, false, 5, true)));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_F32, 1, 5, Condition.AL), f64ToF32);
    }

    @Test
    void convertIntToFloatSignedUnsigned() {
        // VCVT_int_{sp,dp}: Vm SEMPRE simples (fonte inteira de 32 bits); Vd segue `size` (destino).
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F32, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0x8, true, 0xA, 1, false, 5, false))));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F32, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0x8, false, 0xA, 1, false, 5, false))));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.S32_TO_F64, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0x8, true, 0xB, 1, true, 5, false))));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.U32_TO_F64, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0x8, false, 0xB, 1, true, 5, false))));
    }

    @Test
    void convertFloatToIntSignedUnsignedRequiresRoundTowardZero() {
        // VCVT_{sp,dp}_int: Vd SEMPRE simples (destino inteiro de 32 bits); Vm segue `size` (fonte).
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_S32, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0xD, true, 0xA, 1, false, 5, false))));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F32_TO_U32, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0xC, true, 0xA, 1, false, 5, false))));
        assertEquals(new IrOp.VfpConvert(IrOp.VfpConversion.F64_TO_S32, 1, 5, Condition.AL),
                liftSingleOp(decodeArm(vfpAsymmetricTwoOperandWord(0xD, true, 0xB, 1, false, 5, true))));
        // bit7=0 (rz=0) é VCVTR, fora de escopo: UNDEFINED.
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decodeArm(vfpAsymmetricTwoOperandWord(0xD, false, 0xA, 1, false, 5, false)).kind());
    }

    @Test
    void loadStoreOffsetSignAndPrecision() {
        IrOp load = liftSingleOp(decodeArm(vfpLoadStoreWord(true, true, false, 2, 5, 3)));
        assertEquals(new IrOp.VfpLoad(false, 2, 5, -1, 12, Condition.AL), load);

        IrOp store = liftSingleOp(decodeArm(vfpLoadStoreWord(false, false, true, 2, 5, 3)));
        assertEquals(new IrOp.VfpStore(true, 2, 5, -1, -12, Condition.AL), store);
    }

    /// Regressão: `VLDR`/`VSTR Vd, [pc, #imm]` (idioma padrão do `gcc` para literais `double`/
    /// `float` de literal pool) precisa do MESMO viés `PC+8` que `LDR`/`STR Rd, [pc, #imm]` já
    /// recebem via {@code baseValueOverride} — sem ele, `IrVfpExecutor`/o bytecode ASM liam
    /// `core.register(15)` AO VIVO, que durante a execução do próprio op ainda vale o endereço da
    /// instrução ATUAL (o bloco só grava `registers[PC]` no fim, em
    /// {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor#execute}), não `+8`.
    /// Bug real: duas `VLDR Dx,[pc,#imm]` consecutivas (ex. `sum=0.0`/`sign=1.0` de um laço Leibniz
    /// compilado por `gcc -mfpu=vfp -mfloat-abi=hard`) liam os literais TROCADOS entre si.
    @Test
    void loadStoreWithPcBaseAppliesArmProgramCounterBias() {
        IrOp load = liftSingleOp(decodeArm(vfpLoadStoreWord(true, true, true, 6, 15, 48)));
        assertEquals(new IrOp.VfpLoad(true, 6, 15, 8, 192, Condition.AL), load);

        IrOp store = liftSingleOp(decodeArm(vfpLoadStoreWord(false, true, true, 7, 15, 48)));
        assertEquals(new IrOp.VfpStore(true, 7, 15, 8, 192, Condition.AL), store);
    }

    /// Mesma regressão para `VLDM`/`VSTM Rn=pc` (raro comparado a `VLDR`, mas o mesmo mecanismo de
    /// override se aplica — ver {@link #loadStoreWithPcBaseAppliesArmProgramCounterBias}).
    @Test
    void multipleTransferWithPcBaseAppliesArmProgramCounterBias() {
        IrOp ldmIa = liftSingleOp(decodeArm(vfpMultipleIaWord(true, false, false, 0, 15, 3)));
        assertEquals(new IrOp.VfpMultipleTransfer(true, false, 15, 8, 0, 3, false, false, Condition.AL), ldmIa);
    }

    /// Regressão end-to-end (interpretada): executa `VLDR Dx, [pc, #imm]` de fato via
    /// {@link IrBlockExecutor} contra uma memória com o literal correto em `PC+8+offset` e ZERO no
    /// endereço da instrução atual — se o override regredir, o load volta a ler do endereço da
    /// instrução (`instructionAddress`, que fica com zeros) em vez de `PC+8+offset`, e o teste
    /// falha com `d6==0` em vez do literal esperado.
    @Test
    void executedVldrPcRelativeReadsCorrectLiteralNotCurrentInstructionAddress() {
        TestAddressSpace memory = new TestAddressSpace(64);
        int instructionAddress = 0x10;
        // `VLDR D6, [pc, #16]`: PC = instructionAddress+8 = 0x18; alvo = 0x18+16 = 0x28.
        int word = vfpLoadStoreWord(true, true, true, 6, 15, 4);
        memory.put32(instructionAddress, word);
        long expected = 0x3ff0000000000000L; // 1.0
        memory.put32(0x28, (int) expected);
        memory.put32(0x28 + 4, (int) (expected >>> 32));

        DecodedInstruction decoded = new ArmDecoder(VFP_TEST_ARCH).decode(memory, instructionAddress);
        IrBlock.Builder block = IrBlock.builder(instructionAddress);
        new StandardIrBuilder().lift(decoded, block);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), VFP_TEST_ARCH);
        core.setProgramCounter(instructionAddress);
        new IrBlockExecutor(VFP_TEST_ARCH).execute(block.sealed(), core);

        assertEquals(expected, core.vfp().d(6));
    }

    @Test
    void multipleTransferIaAndDbWithVPushVPopAlias() {
        // VLDM Rn, {S0-S2} (IA, sem writeback).
        IrOp ldmIa = liftSingleOp(decodeArm(vfpMultipleIaWord(true, false, false, 0, 5, 3)));
        assertEquals(new IrOp.VfpMultipleTransfer(true, false, 5, -1, 0, 3, false, false, Condition.AL), ldmIa);

        // VPUSH {D8-D9} == VSTMDB SP!, {D8-D9}: rn=SP(13), imm8=4 (2 registros dupla).
        IrOp push = liftSingleOp(decodeArm(vfpMultipleDbWord(false, true, 8, 13, 4)));
        assertEquals(new IrOp.VfpMultipleTransfer(false, true, 13, -1, 8, 2, true, true, Condition.AL), push);
    }

    @Test
    void oddImm8InDoublePrecisionMultipleTransferIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpMultipleIaWord(true, false, true, 0, 5, 3)).kind());
    }

    @Test
    void coreTransferSingleBothDirections() {
        IrOp toArm = liftSingleOp(decodeArm(vfpCoreTransferWord(true, 2, 5)));
        assertEquals(new IrOp.VfpCoreTransfer(true, 2, 5, false, Condition.AL), toArm);

        IrOp toVfp = liftSingleOp(decodeArm(vfpCoreTransferWord(false, 2, 5)));
        assertEquals(new IrOp.VfpCoreTransfer(false, 2, 5, false, Condition.AL), toVfp);
    }

    @Test
    void corePairTransferDoubleBothDirections() {
        IrOp toArm = liftSingleOp(decodeArm(vfpCorePairTransferWord(true, 1, 2, 8)));
        assertEquals(new IrOp.VfpCorePairTransfer(true, 1, 2, 8, Condition.AL), toArm);

        IrOp toVfp = liftSingleOp(decodeArm(vfpCorePairTransferWord(false, 1, 2, 8)));
        assertEquals(new IrOp.VfpCorePairTransfer(false, 1, 2, 8, Condition.AL), toVfp);
    }

    @Test
    void systemTransferFpscrBothDirections() {
        IrOp vmrs = liftSingleOp(decodeArm(vfpSystemTransferWord(true, 15, 1)));
        assertEquals(new IrOp.VfpSystemTransfer(true, 15, Condition.AL), vmrs);

        IrOp vmsr = liftSingleOp(decodeArm(vfpSystemTransferWord(false, 2, 1)));
        assertEquals(new IrOp.VfpSystemTransfer(false, 2, Condition.AL), vmsr);
    }

    @Test
    void systemTransferNonFpscrRegisterIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpSystemTransferWord(true, 15, 0)).kind());
    }

    // ── 2. VFPExpandImm ──────────────────────────────────────────────────────────────────────

    @Test
    void vfpMoveImmediateExpandsCanonicalVectors() {
        assertEquals(Float.floatToRawIntBits(1.0f), (int) executeMoveImmediate(false, 0x70));
        assertEquals(Float.floatToRawIntBits(-1.0f), (int) executeMoveImmediate(false, 0xF0));
        assertEquals(Float.floatToRawIntBits(0.5f), (int) executeMoveImmediate(false, 0x60));
        // O enunciado da task cita 0x4F para 31.0, mas VFPExpandImm(0x4F,32) = 0x3E780000
        // (~0.2421875) — verificado contra vfp_expand_imm do QEMU; 0x3F é que expande para 31.0f.
        assertEquals(Float.floatToRawIntBits(31.0f), (int) executeMoveImmediate(false, 0x3F));
    }

    private static long executeMoveImmediate(boolean doublePrecision, int imm8) {
        ArmCore core = newCore();
        int word = vfpMoveImmWord(doublePrecision, 0, imm8);
        DecodedInstruction decoded = decodeArm(word);
        IrOp op = liftSingleOp(decoded);
        new IrBlockExecutor(VFP_TEST_ARCH).executeOp(core, op, 0);
        return doublePrecision ? core.vfp().d(0) : core.vfp().s(0);
    }

    // ── 3. Execução ponta-a-ponta: VLDR+VADD+VCMP+VMRS APSR_nzcv+BGE ────────────────────────

    @Test
    void endToEndFloatBlockSetsApsrAndTakesBranch() {
        ArmCore core = newCore();
        core.setRegister(0, 0); // base para VLDR
        core.vfp().setSFloat(1, 1.0f); // S1 = 1.0 (segundo operando de VADD)
        core.memory().write32(0, Float.floatToRawIntBits(2.0f)); // [R0] = 2.0f, alvo do VLDR

        int vldr = vfpLoadStoreWord(true, true, false, 0, 0, 0); // VLDR S0, [R0]
        int vadd = vfpAluWord(0b011, false, false, 0, 0, 1); // VADD S0, S0, S1 (2.0+1.0=3.0)
        int vcmp = vfpCmpWord(true, false, false, 0, 0); // VCMP S0, #0.0
        int vmrs = vfpSystemTransferWord(true, 15, 1); // VMRS APSR_nzcv, FPSCR

        IrBlockExecutor executor = new IrBlockExecutor(VFP_TEST_ARCH);
        for (int word : new int[] {vldr, vadd, vcmp, vmrs}) {
            IrOp op = liftSingleOp(decodeArm(word));
            executor.executeOp(core, op, 0);
        }

        assertEquals(3.0f, core.vfp().sFloat(0));
        // 3.0 comparado com 0.0 -> GT: N=0,Z=0,C=1,V=0 -> BGE (GE = !N==V, aqui N=0,V=0) é tomado.
        assertTrue(core.cpsr().evalCond(Condition.GE));
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), VFP_TEST_ARCH);
    }

    // ── 4. Gate: sem VFPV2 continua no CoprocessorBus; com VFPV2, CP15 continua no bus ──────

    private static final ArmArchitecture VFP_GATED_OFF_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K, "ARMv7-TestVfpGateOff");
    private static final ArmArchitecture VFP_GATED_OFF = VFP_GATED_OFF_FEATURES
            .withDecoderExtensions(List.of(new VfpDecoder(VFP_GATED_OFF_FEATURES), new CoprocessorDecoder()));

    @Test
    void mrcP10FallsBackToCoprocessorBusWithoutVfpv2Feature() {
        DecodedInstruction decoded = new ArmDecoder(VFP_GATED_OFF).decode(wordAsMemory(mrcWord(0b1010)), 0);
        assertEquals(InstructionKind.COPROCESSOR, decoded.kind());
    }

    @Test
    void mrcP15StillGoesToCoprocessorBusWithVfpv2Enabled() {
        DecodedInstruction decoded = new ArmDecoder(VFP_TEST_ARCH).decode(wordAsMemory(mrcWord(0b1111)), 0);
        assertEquals(InstructionKind.COPROCESSOR, decoded.kind());
    }

    private static TestAddressSpace wordAsMemory(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return memory;
    }

    // ── 5. Ida-e-volta ARM x Thumb-2 ─────────────────────────────────────────────────────────

    @Test
    void vaddDoubleRoundTripsArmAndThumb2() {
        int word = vfpAluWord(0b011, false, true, 3, 1, 2);
        DecodedInstruction armDecoded = decodeArm(word);
        DecodedInstruction thumbDecoded = decodeThumb32(word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    @Test
    void vldrRoundTripsArmAndThumb2() {
        int word = vfpLoadStoreWord(true, true, false, 2, 5, 3);
        DecodedInstruction armDecoded = decodeArm(word);
        DecodedInstruction thumbDecoded = decodeThumb32(word);
        assertEquals(InstructionSet.ARM, armDecoded.instructionSet());
        assertEquals(InstructionSet.THUMB, thumbDecoded.instructionSet());
        assertEquals(liftSingleOp(armDecoded), liftSingleOp(thumbDecoded.withInstructionSet(InstructionSet.ARM)));
    }

    // ── 6. UNPREDICTABLE/fora de escopo -> UNDEFINED ────────────────────────────────────────

    @Test
    void vmsrOfNonFpscrRegisterIsUndefined() {
        // FPSID/FPEXC/MVFR* (reg != 0b0001) — fora de escopo (só FPSCR).
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vfpSystemTransferWord(false, 2, 0)).kind());
    }

    @Test
    void vmovImmHalfPrecisionIsOutOfVfpSpaceEntirely() {
        // size=1001 (CP9) com bits[23,21] setados NÃO é o encoding de `VMOV_half` (B22.2 só
        // reivindica `---- 1110 000 l .... rt 1001 . 001 0000`) — este padrão fica fora do gate
        // coproc ∈ {1010,1011} e cai no UNDEFINED genérico (nenhum decoder reivindica CP9 aqui).
        int word = (COND_AL << 28) | 0x0E00_0000 | (1 << 23) | (1 << 21) | (1 << 20) | (0x9 << 8);
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(word).kind());
    }

    // ── 6b. B22.2: VMOV_half — mata o único `⚠️` do projeto (violação de G8 viva) ────────────

    /// `VMOV_half` (`VMOV Sn,Rt`/`VMOV Rt,Sn` de 16 bits): `---- 1110 000 l vn(4) rt(4) 1001 . 001
    /// 0000` — idêntico a {@link #vfpCoreTransferWord}, só `size=1001` (não `1010`).
    private static int vmovHalfWord(boolean load, int rt, int vn) {
        int word = (COND_AL << 28) | (0xE << 24) | (load ? 1 : 0) << 20;
        word |= nibbleOf(vn, false) << 16;
        word |= rt << 12;
        word |= 0x9 << 8;
        word |= extOf(vn, false) << 7;
        word |= 0b0010000;
        return word;
    }

    @Test
    void vmovHalfWithoutFeatureIsUnimplementedNotCoprocessor() {
        // Sem HALF_PRECISION_FP o VfpDecoder REIVINDICA o encoding e o recusa — antes da B22.2 isto
        // caía no CoprocessorDecoder como `MCR`/`MRC` genérico para cp9 (o `⚠️` da tabela, G8).
        DecodedInstruction decoded = decodeArm(vmovHalfWord(true, 2, 5));
        assertEquals(InstructionKind.UNIMPLEMENTED, decoded.kind());
    }

    @Test
    void vmovHalfWithFeatureDecodesToHalfWidthCoreTransfer() {
        IrOp toArm = liftSingleOp(new ArmDecoder(VFP_HALF_TEST_ARCH)
                .decode(wordAsMemory(vmovHalfWord(true, 2, 5)), 0));
        assertEquals(new IrOp.VfpCoreTransfer(true, 2, 5, true, Condition.AL), toArm);

        IrOp toVfp = liftSingleOp(new ArmDecoder(VFP_HALF_TEST_ARCH)
                .decode(wordAsMemory(vmovHalfWord(false, 2, 5)), 0));
        assertEquals(new IrOp.VfpCoreTransfer(false, 2, 5, true, Condition.AL), toVfp);
    }

    @Test
    void vmovHalfExecutionIsRawSixteenBitCopy() {
        ArmCore core = new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), VFP_HALF_TEST_ARCH);
        IrBlockExecutor executor = new IrBlockExecutor(VFP_HALF_TEST_ARCH);

        // l=1: Rt = ZeroExtend(Sn[15:0], 32).
        core.vfp().setS(5, 0xDEAD_BEEF);
        core.setRegister(2, 0x1111_2222);
        executor.executeOp(core, liftSingleOp(new ArmDecoder(VFP_HALF_TEST_ARCH)
                .decode(wordAsMemory(vmovHalfWord(true, 2, 5)), 0)), 0);
        assertEquals(0x0000_BEEF, core.register(2));

        // l=0: Sn[15:0] = Rt[15:0], Sn[31:16] preservado.
        core.vfp().setS(5, 0xCAFE_0000);
        core.setRegister(3, 0x9999_1234);
        executor.executeOp(core, liftSingleOp(new ArmDecoder(VFP_HALF_TEST_ARCH)
                .decode(wordAsMemory(vmovHalfWord(false, 3, 5)), 0)), 0);
        assertEquals(0xCAFE_1234, core.vfp().s(5));
    }

    // ── 7. B9.5: VMOV_64_sp, VMOV_to_gp/from_gp (word) e VCVT_fix ──────────────────────────

    /// `VMOV_64_sp` (par de `S` consecutivos): `---- 1100 010 op rt2(4) rt(4) 1010 00 . 1 vm(4)`
    /// — mesmo layout de {@link #vfpCorePairTransferWord}, mas `size=1010` (não `1011`) e `vm`
    /// endereçado como registrador `S` (não `D`).
    private static int vmov64SpWord(boolean toArmRegisters, int rt, int rt2, int vm) {
        int word = (COND_AL << 28) | (0xC << 24) | (0b010 << 21) | (toArmRegisters ? 1 : 0) << 20;
        word |= rt2 << 16;
        word |= rt << 12;
        word |= 0xA << 8;
        word |= extOf(vm, false) << 5;
        word |= 1 << 4;
        word |= nibbleOf(vm, false);
        return word;
    }

    /// `VMOV_to_gp`/`VMOV_from_gp`, forma word (`size=2` NEON): `---- 1110 0 0 index 1|0 vn(4)
    /// rt(4) 1011 . 00 1 0000` — `vn` é o `D` combinado (0-15), `index` seleciona `S(2*vn+index)`.
    private static int vmovScalarGpWordForm(boolean toArmRegister, int index, int rt, int vn) {
        int word = (COND_AL << 28) | (0xE << 24);
        word |= index << 21;
        word |= (toArmRegister ? 1 : 0) << 20;
        word |= nibbleOf(vn, true) << 16;
        word |= rt << 12;
        word |= 0xB << 8;
        word |= extOf(vn, true) << 7;
        word |= 1 << 4;
        return word;
    }

    /// `VMOV_to_gp`, forma byte (`size=0`, `bit22=1`) — NEON-gated, sempre fora de escopo aqui.
    private static int vmovScalarGpByteForm(int rt) {
        return (COND_AL << 28) | (0xE << 24) | (1 << 22) | (1 << 20) | (rt << 12) | (0xB << 8) | (1 << 4);
    }

    /// `VMOV_to_gp`, forma halfword (`size=1`, `bit22=0,bit5=1`) — NEON-gated, fora de escopo.
    private static int vmovScalarGpHalfwordForm(int rt) {
        return (COND_AL << 28) | (0xE << 24) | (1 << 20) | (rt << 12) | (0xB << 8) | (1 << 5) | (1 << 4);
    }

    /// `VCVT_fix_{sp,dp}`: `opc2` empacota `1_op_1_u` (bits 19-16), `sx`=bit7, `imm`=`%vm_sp`
    /// (5 bits, MESMO layout de campo que um número `S`, aqui reaproveitado como imediato).
    private static int vfpConvertFixedWord(boolean toFixedPoint, boolean unsignedFixedPoint, boolean is32Bit,
            int imm5, boolean doublePrecision, int vd) {
        int word = (COND_AL << 28) | (0xE << 24) | (1 << 23) | (1 << 21) | (1 << 20);
        word |= extOf(vd, doublePrecision) << 22;
        word |= 1 << 19;
        word |= (toFixedPoint ? 1 : 0) << 18;
        word |= 1 << 17;
        word |= (unsignedFixedPoint ? 1 : 0) << 16;
        word |= nibbleOf(vd, doublePrecision) << 12;
        word |= size(doublePrecision) << 8;
        word |= (is32Bit ? 1 : 0) << 7;
        word |= 1 << 6;
        word |= (imm5 & 1) << 5;
        word |= (imm5 >>> 1) & 0xF;
        return word;
    }

    @Test
    void vmov64SpBothDirections() {
        IrOp toArm = liftSingleOp(decodeArm(vmov64SpWord(true, 1, 2, 8)));
        assertEquals(new IrOp.VfpCorePairTransferSingle(true, 1, 2, 8, Condition.AL), toArm);

        IrOp toVfp = liftSingleOp(decodeArm(vmov64SpWord(false, 1, 2, 8)));
        assertEquals(new IrOp.VfpCorePairTransferSingle(false, 1, 2, 8, Condition.AL), toVfp);
    }

    @Test
    void vmovScalarGpWordFormReusesCoreTransfer() {
        // vn=3 (D combinado), index=1 -> S(2*3+1)=S7.
        IrOp toArm = liftSingleOp(decodeArm(vmovScalarGpWordForm(true, 1, 2, 3)));
        assertEquals(new IrOp.VfpCoreTransfer(true, 2, 7, false, Condition.AL), toArm);

        IrOp fromArm = liftSingleOp(decodeArm(vmovScalarGpWordForm(false, 0, 2, 3)));
        assertEquals(new IrOp.VfpCoreTransfer(false, 2, 6, false, Condition.AL), fromArm);
    }

    @Test
    void vmovScalarGpByteAndHalfwordFormsAreNeonGatedOutOfScope() {
        // QEMU translate-vfp.c: size!=MO_32 exige ARM_FEATURE_NEON — nenhum preset deste projeto
        // (nem o ARM11 MPCore real do 3DS) tem NEON, então ficam UNIMPLEMENTED (B9.5).
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vmovScalarGpByteForm(1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vmovScalarGpHalfwordForm(1)).kind());
    }

    @Test
    void vcvtFixSignedToFixedRoundToZero() {
        // op=1 (float->fixo), u=0 (com sinal), sx=1 (32 bits), imm=0 -> fractionBits=32.
        IrOp op = liftSingleOp(decodeArm(vfpConvertFixedWord(true, false, true, 0, false, 1)));
        assertEquals(new IrOp.VfpConvertFixed(false, true, false, true, 32, 1, Condition.AL), op);
    }

    @Test
    void vcvtFixUnsignedFromFixedRoundToNearest() {
        // op=0 (fixo->float), u=1 (sem sinal), sx=0 (16 bits), imm=4 -> fractionBits=12, dupla.
        IrOp op = liftSingleOp(decodeArm(vfpConvertFixedWord(false, true, false, 4, true, 2)));
        assertEquals(new IrOp.VfpConvertFixed(true, false, true, false, 12, 2, Condition.AL), op);
    }
}
