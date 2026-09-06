package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `neon-shared.decode` — `VCMLA`/`VCADD`/`VCMLA_scalar` (task B13.17, `FEAT_FCMA`) → {@link
/// IrOp.NeonComplex}/{@link IrOp.NeonComplexByElement} → execução pelo núcleo vetorial
/// COMPARTILHADO ({@code AdvSimdLanes.fpComplexAdd}/`fpComplexMultiplyAccumulate`/
/// `fpComplexMultiplyAccumulateByElement}).
///
/// Encodings golden conferidos com `arm-none-eabi-as -march=armv8.3-a -mfpu=neon-fp-armv8
/// -mfpu=neon-fp-armv8 .arch_extension fp16` (devkitARM) — o log de disassembly está em `##
/// Resultado` da task.
class NeonSharedDecoderTest {
    private static final ArmArchitecture NEON_SHARED_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonShared",
                    ArmFeature.COMPLEX_NUMBER_ARITHMETIC, ArmFeature.VFPV3_D32, ArmFeature.THUMB2);

    private static final ArmArchitecture NEON_SHARED_ARCH =
            NEON_SHARED_FEATURES.withDecoderExtensions(neonSharedFirst(NEON_SHARED_FEATURES))
                    .withThumb32DecoderExtensions(thumbNeonSharedFirst(NEON_SHARED_FEATURES));

    private static List<DecoderExtension> neonSharedFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonSharedDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    private static List<DecoderExtension> thumbNeonSharedFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonSharedDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.thumb32DecoderExtensions());
        return extensions;
    }

    // ── Encoders (campos conferidos golden contra `arm-none-eabi-as`, ver o javadoc da classe) ──

    /// `VCMLA` (vetorial): `rotCode` `0`-`3` (`#0`/`#90`/`#180`/`#270`); `sizeBit` `0`=F16/`1`=F32.
    private static int vcmlaVector(int rotCode, int sizeBit, boolean quad, int vd, int vn, int vm) {
        return 0xFC00_0800
                | (rotCode << 23) | (1 << 21) | (sizeBit << 20)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `VCADD` (vetorial): `rotBit` `0`=`#90`/`1`=`#270`; `sizeBit` `0`=F16/`1`=F32.
    private static int vcaddVector(int rotBit, int sizeBit, boolean quad, int vd, int vn, int vm) {
        return 0xFC00_0800
                | (rotBit << 24) | (1 << 23) | (sizeBit << 20)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    /// `VCMLA_scalar`, forma F16 (`size=1`): `vm` é um nibble DIRETO (`D0`-`D15`, sem bit de
    /// extensão), `index` `0`/`1`.
    private static int vcmlaScalarHalf(int rotCode, boolean quad, int vd, int vn, int vmNibble, int index) {
        return 0xFE00_0800
                | (rotCode << 20)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | (index << 5)
                | (vmNibble & 0xF);
    }

    /// `VCMLA_scalar`, forma F32 (`size=2`): `vm` padrão `%vm_dp`, `index` sempre `0` (sem bit de
    /// índice no encoding real).
    private static int vcmlaScalarSingle(int rotCode, boolean quad, int vd, int vn, int vm) {
        return 0xFE00_0800
                | (1 << 23) | (rotCode << 20)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | (quad ? 1 << 6 : 0)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    private static DecodedInstruction decodeArm(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decodeArm(int word) {
        return decodeArm(NEON_SHARED_ARCH, word);
    }

    /// Decodifica o MESMO `raw32` como Thumb-2: grava os dois halfwords na ordem `hi`/`lo` que
    /// `ThumbDecoder` usa (`(hi << 16) | lo`, ver `ThumbDecoder#tryDecodeThumb32`).
    private static DecodedInstruction decodeThumb(int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, (word >>> 16) & 0xFFFF);
        memory.put16(2, word & 0xFFFF);
        return new ThumbDecoder(NEON_SHARED_ARCH).decode(memory, 0);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static IrOp liftedOf(int word) {
        DecodedInstruction decoded = decodeArm(word);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        return liftSingleOp(decoded);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), NEON_SHARED_ARCH);
    }

    private static void run(ArmCore core, int word) {
        new IrBlockExecutor(NEON_SHARED_ARCH).executeOp(core, liftSingleOp(decodeArm(word)), 0);
    }

    private static long twoLanes(float lane0, float lane1) {
        return (Float.floatToRawIntBits(lane0) & 0xFFFF_FFFFL)
                | ((long) Float.floatToRawIntBits(lane1) << 32);
    }

    private static float lane0(long word) {
        return Float.intBitsToFloat((int) word);
    }

    private static float lane1(long word) {
        return Float.intBitsToFloat((int) (word >>> 32));
    }

    // ── Encoding golden (assembler real, `arm-none-eabi-as -march=armv8.3-a`) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xFC32_0844, vcmlaVector(0, 1, true, 0, 2, 4));   // vcmla.f32 q0,q1,q2,#0
        assertEquals(0xFCB2_0844, vcmlaVector(1, 1, true, 0, 2, 4));   // vcmla.f32 q0,q1,q2,#90
        assertEquals(0xFD32_0844, vcmlaVector(2, 1, true, 0, 2, 4));   // vcmla.f32 q0,q1,q2,#180
        assertEquals(0xFDB2_0844, vcmlaVector(3, 1, true, 0, 2, 4));   // vcmla.f32 q0,q1,q2,#270
        assertEquals(0xFC21_0802, vcmlaVector(0, 0, false, 0, 1, 2)); // vcmla.f16 d0,d1,d2,#0
        assertEquals(0xFC92_0844, vcaddVector(0, 1, true, 0, 2, 4));   // vcadd.f32 q0,q1,q2,#90
        assertEquals(0xFD92_0844, vcaddVector(1, 1, true, 0, 2, 4));   // vcadd.f32 q0,q1,q2,#270
        assertEquals(0xFC81_0802, vcaddVector(0, 0, false, 0, 1, 2)); // vcadd.f16 d0,d1,d2,#90
        assertEquals(0xFE82_0844, vcmlaScalarSingle(0, true, 0, 2, 4)); // vcmla.f32 q0,q1,d4[0],#0
        assertEquals(0xFE11_0822, vcmlaScalarHalf(1, false, 0, 1, 2, 1)); // vcmla.f16 d0,d1,d2[1],#90
    }

    // ── Zero-diff: nenhum preset declara COMPLEX_NUMBER_ARITHMETIC ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                vcmlaVector(0, 1, true, 0, 2, 4),
                vcaddVector(0, 1, true, 0, 2, 4),
                vcmlaScalarSingle(0, true, 0, 2, 4),
                vcmlaScalarHalf(1, false, 0, 1, 2, 1),
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Espaço livre: siblings ainda sem dono (B13.18-B13.21) caem em UNIMPLEMENTED, não `null` ──

    @Test
    void unclaimedSiblingsStillFallThroughToUnimplemented() {
        // VSDOT: 1111 110 00 . 10 .... .... 1101 . q . 0 .... — opcode 1101, não 1000.
        int vsdot = 0xFC20_0D00;
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vsdot).kind());
    }

    // ── Decode: campos ──

    @Test
    void vcmlaVectorDecodesRotationAndSize() {
        assertEquals(new IrOp.NeonComplex(true, 0, true, 2, 0, 2, 4), liftedOf(vcmlaVector(0, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonComplex(true, 90, true, 2, 0, 2, 4), liftedOf(vcmlaVector(1, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonComplex(true, 180, true, 2, 0, 2, 4), liftedOf(vcmlaVector(2, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonComplex(true, 270, true, 2, 0, 2, 4), liftedOf(vcmlaVector(3, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonComplex(true, 0, false, 1, 0, 1, 2), liftedOf(vcmlaVector(0, 0, false, 0, 1, 2)));
    }

    @Test
    void vcaddVectorDecodesRotationAndSize() {
        assertEquals(new IrOp.NeonComplex(false, 90, true, 2, 0, 2, 4), liftedOf(vcaddVector(0, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonComplex(false, 270, true, 2, 0, 2, 4), liftedOf(vcaddVector(1, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonComplex(false, 90, false, 1, 0, 1, 2), liftedOf(vcaddVector(0, 0, false, 0, 1, 2)));
    }

    @Test
    void vcmlaScalarDecodesIndexAndSize() {
        // F32: índice sempre 0 (um complexo simples ocupa o D inteiro).
        assertEquals(new IrOp.NeonComplexByElement(0, true, 2, 0, 2, 4, 0),
                liftedOf(vcmlaScalarSingle(0, true, 0, 2, 4)));
        // F16: índice extraído do bit5, vm é nibble direto (sem M-ext).
        assertEquals(new IrOp.NeonComplexByElement(90, false, 1, 0, 1, 2, 1),
                liftedOf(vcmlaScalarHalf(1, false, 0, 1, 2, 1)));
        assertEquals(new IrOp.NeonComplexByElement(0, false, 1, 0, 1, 9, 0),
                liftedOf(vcmlaScalarHalf(0, false, 0, 1, 9, 0)));
    }

    // ── Forma Q com índice ímpar é UNDEFINED (mesma disciplina de NeonDataProcessingDecoder) ──

    @Test
    void quadFormWithOddRegisterIsUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vcmlaVector(0, 1, true, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vcaddVector(0, 1, true, 0, 3, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decodeArm(vcmlaScalarSingle(0, true, 0, 1, 4)).kind());
    }

    // ── A MESMA palavra decodifica igual em A32 e T32 (cabeçalho do .decode: encoding compartilhado) ──

    @Test
    void sameWordDecodesIdenticallyInArmAndThumb() {
        int[] words = {
                vcmlaVector(1, 1, true, 0, 2, 4),
                vcaddVector(0, 1, true, 0, 2, 4),
                vcmlaScalarSingle(0, true, 0, 2, 4),
                vcmlaScalarHalf(1, false, 0, 1, 2, 1),
        };
        for (int w : words) {
            DecodedInstruction arm = decodeArm(w);
            DecodedInstruction thumb = decodeThumb(w);
            assertEquals(arm.kind(), thumb.kind());
            assertEquals(InstructionKind.LIFTED_IR_OP, arm.kind());
            assertEquals(liftSingleOp(arm), liftSingleOp(thumb));
        }
    }

    // ── Execução: VCADD (soma complexa com rotação) ──

    @Test
    void vcaddComputesComplexAdditionWithRotation() {
        // a = 2+3i, b = 4+5i.
        ArmCore rot90 = newCore();
        rot90.vfp().setD(1, twoLanes(2.0f, 3.0f));
        rot90.vfp().setD(2, twoLanes(4.0f, 5.0f));
        run(rot90, vcaddVector(0, 1, false, 0, 1, 2));
        assertEquals(-3.0f, lane0(rot90.vfp().d(0))); // re' = a_re - b_im = 2 - 5
        assertEquals(7.0f, lane1(rot90.vfp().d(0)));  // im' = a_im + b_re = 3 + 4

        ArmCore rot270 = newCore();
        rot270.vfp().setD(1, twoLanes(2.0f, 3.0f));
        rot270.vfp().setD(2, twoLanes(4.0f, 5.0f));
        run(rot270, vcaddVector(1, 1, false, 0, 1, 2));
        assertEquals(7.0f, lane0(rot270.vfp().d(0)));  // re' = a_re + b_im = 2 + 5
        assertEquals(-1.0f, lane1(rot270.vfp().d(0))); // im' = a_im - b_re = 3 - 4
    }

    // ── Execução: VCMLA (multiply-accumulate complexo, duas rotações completam o produto cheio) ──

    @Test
    void vcmlaTwoComplementaryRotationsProduceTheFullComplexProduct() {
        // a = 2+3i, b = 4+5i ⇒ a*b = (2*4 - 3*5) + (2*5 + 3*4)i = -7 + 22i.
        ArmCore core = newCore();
        core.vfp().setD(0, twoLanes(0.0f, 0.0f)); // acumulador zerado
        core.vfp().setD(1, twoLanes(2.0f, 3.0f));
        core.vfp().setD(2, twoLanes(4.0f, 5.0f));

        run(core, vcmlaVector(0, 1, false, 0, 1, 2)); // rot=0: += a_re*b_re, += a_re*b_im
        assertEquals(8.0f, lane0(core.vfp().d(0)));
        assertEquals(10.0f, lane1(core.vfp().d(0)));

        run(core, vcmlaVector(1, 1, false, 0, 1, 2)); // rot=90: -= a_im*b_im, += a_im*b_re
        assertEquals(-7.0f, lane0(core.vfp().d(0)));
        assertEquals(22.0f, lane1(core.vfp().d(0)));
    }

    @Test
    void vcmlaRotate180And270NegateTheContribution() {
        ArmCore core180 = newCore();
        core180.vfp().setD(0, twoLanes(0.0f, 0.0f));
        core180.vfp().setD(1, twoLanes(2.0f, 3.0f));
        core180.vfp().setD(2, twoLanes(4.0f, 5.0f));
        run(core180, vcmlaVector(2, 1, false, 0, 1, 2)); // rot=180: -= a_re*b_re, -= a_re*b_im
        assertEquals(-8.0f, lane0(core180.vfp().d(0)));
        assertEquals(-10.0f, lane1(core180.vfp().d(0)));

        ArmCore core270 = newCore();
        core270.vfp().setD(0, twoLanes(0.0f, 0.0f));
        core270.vfp().setD(1, twoLanes(2.0f, 3.0f));
        core270.vfp().setD(2, twoLanes(4.0f, 5.0f));
        run(core270, vcmlaVector(3, 1, false, 0, 1, 2)); // rot=270: += a_im*b_im, -= a_im*b_re
        assertEquals(15.0f, lane0(core270.vfp().d(0)));
        assertEquals(-12.0f, lane1(core270.vfp().d(0)));
    }

    // ── Execução: forma Q (2 pares independentes) ──

    @Test
    void vcaddQuadFormComputesTwoIndependentPairs() {
        ArmCore core = newCore();
        core.vfp().setD(2, twoLanes(2.0f, 3.0f));
        core.vfp().setD(3, twoLanes(-1.0f, -2.0f));
        core.vfp().setD(4, twoLanes(4.0f, 5.0f));
        core.vfp().setD(5, twoLanes(10.0f, 20.0f));
        run(core, vcaddVector(0, 1, true, 0, 2, 4));
        assertEquals(-3.0f, lane0(core.vfp().d(0)));
        assertEquals(7.0f, lane1(core.vfp().d(0)));
        assertEquals(-21.0f, lane0(core.vfp().d(1)));
        assertEquals(8.0f, lane1(core.vfp().d(1)));
    }

    // ── Execução: VCMLA_scalar (b fixo, replicado — aqui só 1 par, forma D) ──

    @Test
    void vcmlaScalarUsesTheFixedComplexOperand() {
        // a = 2+3i (vn=d1), b FIXO = 4+5i (vm=d4[0], size=2/F32 ⇒ índice sempre 0).
        ArmCore core = newCore();
        core.vfp().setD(0, twoLanes(0.0f, 0.0f));
        core.vfp().setD(1, twoLanes(2.0f, 3.0f));
        core.vfp().setD(4, twoLanes(4.0f, 5.0f));
        run(core, vcmlaScalarSingle(0, false, 0, 1, 4));
        assertEquals(8.0f, lane0(core.vfp().d(0)));  // a_re*b_re
        assertEquals(10.0f, lane1(core.vfp().d(0))); // a_re*b_im
    }
}
