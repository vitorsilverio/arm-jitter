package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpThreeSameOp;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// NEON 3-reg-same de PONTO FLUTUANTE A32 (task B13.6): `VADD.F32`/`VSUB.F32`/`VMUL.F32`/`VMLA.F32`/
/// `VMLS.F32`/`VFMA.F32`/`VFMS.F32`/`VABD.F32`/`VMAX.F32`/`VMIN.F32`/`VMAXNM.F32`/`VMINNM.F32`/
/// `VCEQ.F32`/`VCGE.F32`/`VCGT.F32`/`VACGE.F32`/`VACGT.F32`/`VRECPS.F32`/`VRSQRTS.F32` +
/// pairwise `VPADD.F32`/`VPMAX.F32`/`VPMIN.F32` da seção `@3same_fp`/`@3same_fp_q0` de
/// `neon-dp.decode` → `IrOp.NeonFpThreeSame` / `IrOp.NeonFpPairwise` → execução pelo núcleo vetorial
/// COMPARTILHADO com o lado A64 ({@code AdvSimdLanes.fpThreeSame}/`fpPairwise`).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon-vfpv4 -mcpu=cortex-a8`
/// (`-march=armv8-a` para `VMAXNM`/`VMINNM`) do devkitARM.
class NeonThreeSameFpDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonFp",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonDataProcessingDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 0 D a sz Vn Vd opc N Q M op Vm` — `size` é o campo de 2 bits [21:20] = `{a, sz}`;
    /// para F32 `sz=0`, então `size == a << 1`.
    private static int neon3s(int u, int size, int opc, int op, boolean quad, int vd, int vn, int vm) {
        return 0xF200_0000
                | (u << 24) | (size << 20) | (opc << 8) | (op << 4) | (quad ? 1 << 6 : 0)
                | ((vd >> 4) << 22) | ((vd & 0xF) << 12)
                | ((vn >> 4) << 7) | ((vn & 0xF) << 16)
                | ((vm >> 4) << 5) | (vm & 0xF);
    }

    private static DecodedInstruction decode(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    private static DecodedInstruction decode(int word) {
        return decode(NEON_ARCH, word);
    }

    private static IrOp liftSingleOp(DecodedInstruction instruction) {
        IrBlock.Builder block = IrBlock.builder(instruction.address());
        new StandardIrBuilder().lift(instruction, block);
        return block.sealed().operations().get(0);
    }

    private static IrOp liftedOf(int word) {
        DecodedInstruction decoded = decode(word);
        assertEquals(InstructionKind.LIFTED_IR_OP, decoded.kind());
        return liftSingleOp(decoded);
    }

    private static ArmCore newCore() {
        return new ArmCore(new TestAddressSpace(64), SwiDispatcher.empty(), NEON_ARCH);
    }

    private static void run(ArmCore core, int word) {
        new IrBlockExecutor(NEON_ARCH).executeOp(core, liftSingleOp(decode(word)), 0);
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

    // ── Encoding golden (assembler real) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xF201_0C12, neon3s(0, 0, 0b1100, 1, false, 0, 1, 2));  // vfma.f32   d0,d1,d2
        assertEquals(0xF222_0C54, neon3s(0, 2, 0b1100, 1, true, 0, 2, 4));   // vfms.f32   q0,q1,q2
        assertEquals(0xF206_5D07, neon3s(0, 0, 0b1101, 0, false, 5, 6, 7));  // vadd.f32   d5,d6,d7
        assertEquals(0xF22A_8D4C, neon3s(0, 2, 0b1101, 0, true, 8, 10, 12)); // vsub.f32   q4,q5,q6
        assertEquals(0xF301_0D02, neon3s(1, 0, 0b1101, 0, false, 0, 1, 2));  // vpadd.f32  d0,d1,d2
        assertEquals(0xF324_3D05, neon3s(1, 2, 0b1101, 0, false, 3, 4, 5));  // vabd.f32   d3,d4,d5
        assertEquals(0xF201_0D12, neon3s(0, 0, 0b1101, 1, false, 0, 1, 2));  // vmla.f32   d0,d1,d2
        assertEquals(0xF222_0D54, neon3s(0, 2, 0b1101, 1, true, 0, 2, 4));   // vmls.f32   q0,q1,q2
        assertEquals(0xF30A_9D1B, neon3s(1, 0, 0b1101, 1, false, 9, 10, 11)); // vmul.f32  d9,d10,d11
        assertEquals(0xF201_0E02, neon3s(0, 0, 0b1110, 0, false, 0, 1, 2));  // vceq.f32   d0,d1,d2
        assertEquals(0xF304_0E48, neon3s(1, 0, 0b1110, 0, true, 0, 4, 8));   // vcge.f32   q0,q2,q4
        assertEquals(0xF302_1E13, neon3s(1, 0, 0b1110, 1, false, 1, 2, 3));  // vacge.f32  d1,d2,d3
        assertEquals(0xF321_0E02, neon3s(1, 2, 0b1110, 0, false, 0, 1, 2));  // vcgt.f32   d0,d1,d2
        assertEquals(0xF322_0E54, neon3s(1, 2, 0b1110, 1, true, 0, 2, 4));   // vacgt.f32  q0,q1,q2
        assertEquals(0xF201_0F02, neon3s(0, 0, 0b1111, 0, false, 0, 1, 2));  // vmax.f32   d0,d1,d2
        assertEquals(0xF224_3F05, neon3s(0, 2, 0b1111, 0, false, 3, 4, 5));  // vmin.f32   d3,d4,d5
        assertEquals(0xF301_0F02, neon3s(1, 0, 0b1111, 0, false, 0, 1, 2));  // vpmax.f32  d0,d1,d2
        assertEquals(0xF327_6F08, neon3s(1, 2, 0b1111, 0, false, 6, 7, 8));  // vpmin.f32  d6,d7,d8
        assertEquals(0xF201_0F12, neon3s(0, 0, 0b1111, 1, false, 0, 1, 2));  // vrecps.f32 d0,d1,d2
        assertEquals(0xF222_0F54, neon3s(0, 2, 0b1111, 1, true, 0, 2, 4));   // vrsqrts.f32 q0,q1,q2
        assertEquals(0xF301_0F12, neon3s(1, 0, 0b1111, 1, false, 0, 1, 2));  // vmaxnm.f32 d0,d1,d2
        assertEquals(0xF324_3F15, neon3s(1, 2, 0b1111, 1, false, 3, 4, 5));  // vminnm.f32 d3,d4,d5
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                neon3s(0, 0, 0b1100, 1, false, 0, 1, 2), // vfma
                neon3s(0, 0, 0b1101, 0, false, 0, 1, 2), // vadd
                neon3s(1, 0, 0b1101, 0, false, 0, 1, 2), // vpadd
                neon3s(0, 0, 0b1110, 0, false, 0, 1, 2), // vceq
                neon3s(1, 0, 0b1111, 1, false, 0, 1, 2), // vmaxnm
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Decode: cada família → AdvSimdFpThreeSameOp/AdvSimdFpPairwiseOp certo ──

    @Test
    void threeSameFamiliesDecode() {
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.FMLA, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b1100, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.FMLS, true, 2, 0, 2, 4),
                liftedOf(neon3s(0, 2, 0b1100, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.ADD, false, 2, 5, 6, 7),
                liftedOf(neon3s(0, 0, 0b1101, 0, false, 5, 6, 7)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.SUB, true, 2, 8, 10, 12),
                liftedOf(neon3s(0, 2, 0b1101, 0, true, 8, 10, 12)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.ABD, false, 2, 3, 4, 5),
                liftedOf(neon3s(1, 2, 0b1101, 0, false, 3, 4, 5)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MLA, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b1101, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MLS, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b1101, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MUL, false, 2, 9, 10, 11),
                liftedOf(neon3s(1, 0, 0b1101, 1, false, 9, 10, 11)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.CMEQ, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b1110, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.CMGE, false, 2, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1110, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.CMGT, false, 2, 0, 1, 2),
                liftedOf(neon3s(1, 2, 0b1110, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.FACGE, false, 2, 1, 2, 3),
                liftedOf(neon3s(1, 0, 0b1110, 1, false, 1, 2, 3)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.FACGT, false, 2, 0, 1, 2),
                liftedOf(neon3s(1, 2, 0b1110, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MAX, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b1111, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MIN, false, 2, 3, 4, 5),
                liftedOf(neon3s(0, 2, 0b1111, 0, false, 3, 4, 5)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.RECPS, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b1111, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.RSQRTS, true, 2, 0, 2, 4),
                liftedOf(neon3s(0, 2, 0b1111, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MAXNM, false, 2, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1111, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpThreeSame(AdvSimdFpThreeSameOp.MINNM, false, 2, 3, 4, 5),
                liftedOf(neon3s(1, 2, 0b1111, 1, false, 3, 4, 5)));
    }

    @Test
    void pairwiseFamiliesDecode() {
        assertEquals(new IrOp.NeonFpPairwise(AdvSimdFpPairwiseOp.ADD, 2, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1101, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpPairwise(AdvSimdFpPairwiseOp.MAX, 2, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1111, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonFpPairwise(AdvSimdFpPairwiseOp.MIN, 2, 6, 7, 8),
                liftedOf(neon3s(1, 2, 0b1111, 0, false, 6, 7, 8)));
    }

    // ── UNDEFINED / G8 ──

    @Test
    void halfPrecisionFormsAreUnimplemented() {
        // sz (bit20) = 1 → F16, task futura (irmã da B19.5). `size` com bit baixo 1 = sz=1.
        int[] fp16 = {
                neon3s(0, 1, 0b1100, 1, false, 0, 1, 2), // vfma.f16
                neon3s(0, 1, 0b1101, 0, false, 0, 1, 2), // vadd.f16
                neon3s(1, 1, 0b1101, 0, false, 0, 1, 2), // vpadd.f16
                neon3s(0, 1, 0b1110, 0, false, 0, 1, 2), // vceq.f16
                neon3s(1, 3, 0b1111, 1, false, 0, 1, 2), // vminnm.f16
                neon3s(0, 3, 0b1111, 1, false, 0, 1, 2), // vrsqrts.f16
        };
        for (int w : fp16) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
        }
    }

    @Test
    void unallocatedFpCombinationsAreUnimplemented() {
        // opc=1110 U=0 a=1 (não existe); opc=1110 U=0 bit4=1; opc=1101 U=1 a=1 bit4=1.
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 2, 0b1110, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1110, 1, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 2, 0b1101, 1, false, 0, 1, 2)).kind());
        // opc=1111 U=1 op=0 é pairwise (VPMAX/VPMIN); nada de "three same" U=1 op=0 aqui.
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1100, 0, false, 0, 1, 2)).kind()); // cripto
    }

    @Test
    void quadFormWithOddRegisterIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1101, 0, true, 1, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1111, 1, true, 0, 3, 2)).kind());
    }

    @Test
    void pairwiseHasNoQuadForm() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 0, 0b1101, 0, true, 0, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 0, 0b1111, 0, true, 0, 2, 4)).kind());
    }

    // ── Fundido × NÃO fundido (decisão 3) ──

    @Test
    void vmlaIsNotFusedButVfmaIs() {
        float a = 1.0f + 0x1p-12f; // exatamente representável; a*a precisa arredondar
        long operand = twoLanes(a, a);
        long acc = twoLanes(-1.0f, -1.0f);

        ArmCore vmla = newCore();
        vmla.vfp().setD(0, acc);
        vmla.vfp().setD(1, operand);
        vmla.vfp().setD(2, operand);
        run(vmla, neon3s(0, 0, 0b1101, 1, false, 0, 1, 2)); // vmla.f32 d0,d1,d2
        float expectedNotFused = a * a + (-1.0f);
        assertEquals(expectedNotFused, lane0(vmla.vfp().d(0)));

        ArmCore vfma = newCore();
        vfma.vfp().setD(0, acc);
        vfma.vfp().setD(1, operand);
        vfma.vfp().setD(2, operand);
        run(vfma, neon3s(0, 0, 0b1100, 1, false, 0, 1, 2)); // vfma.f32 d0,d1,d2
        float expectedFused = Math.fma(a, a, -1.0f);
        assertEquals(expectedFused, lane0(vfma.vfp().d(0)));

        assertNotEquals(expectedFused, expectedNotFused, "os operandos foram escolhidos para diferir");
    }

    // ── Execução: núcleo compartilhado com o A64 ──

    @Test
    void addSubMulAbdOnBothLanes() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(1.5f, -3.0f));
        core.vfp().setD(2, twoLanes(2.0f, 4.0f));
        run(core, neon3s(0, 0, 0b1101, 0, false, 0, 1, 2)); // vadd.f32
        assertEquals(3.5f, lane0(core.vfp().d(0)));
        assertEquals(1.0f, lane1(core.vfp().d(0)));

        run(core, neon3s(0, 2, 0b1101, 0, false, 3, 1, 2)); // vsub.f32 d3,d1,d2
        assertEquals(-0.5f, lane0(core.vfp().d(3)));
        assertEquals(-7.0f, lane1(core.vfp().d(3)));

        run(core, neon3s(1, 0, 0b1101, 1, false, 4, 1, 2)); // vmul.f32 d4,d1,d2
        assertEquals(3.0f, lane0(core.vfp().d(4)));
        assertEquals(-12.0f, lane1(core.vfp().d(4)));

        run(core, neon3s(1, 2, 0b1101, 0, false, 5, 1, 2)); // vabd.f32 d5,d1,d2
        assertEquals(0.5f, lane0(core.vfp().d(5)));
        assertEquals(7.0f, lane1(core.vfp().d(5)));
    }

    @Test
    void quadFormExecutesFourLanes() {
        ArmCore core = newCore();
        core.vfp().setD(2, twoLanes(1.0f, 2.0f));
        core.vfp().setD(3, twoLanes(3.0f, 4.0f));
        core.vfp().setD(4, twoLanes(10.0f, 20.0f));
        core.vfp().setD(5, twoLanes(30.0f, 40.0f));
        run(core, neon3s(0, 0, 0b1101, 0, true, 0, 2, 4)); // vadd.f32 q0,q1,q2
        assertEquals(11.0f, lane0(core.vfp().d(0)));
        assertEquals(22.0f, lane1(core.vfp().d(0)));
        assertEquals(33.0f, lane0(core.vfp().d(1)));
        assertEquals(44.0f, lane1(core.vfp().d(1)));
    }

    @Test
    void addPropagatesNaNAndKeepsZeroSign() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(Float.NaN, -0.0f));
        core.vfp().setD(2, twoLanes(1.0f, 0.0f));
        run(core, neon3s(0, 0, 0b1101, 0, false, 0, 1, 2)); // vadd.f32
        assertEquals(Float.NaN, lane0(core.vfp().d(0)));
        // (-0.0) + (+0.0) = +0.0
        assertEquals(0x0000_0000, Float.floatToRawIntBits(lane1(core.vfp().d(0))));
    }

    @Test
    void maxMinPropagateNaNWhileMaxnmMinnmDoNot() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(Float.NaN, -0.0f));
        core.vfp().setD(2, twoLanes(1.0f, 0.0f));

        run(core, neon3s(0, 0, 0b1111, 0, false, 0, 1, 2)); // vmax.f32
        assertEquals(Float.NaN, lane0(core.vfp().d(0)));
        // VMAX(-0,+0) = +0
        assertEquals(0x0000_0000, Float.floatToRawIntBits(lane1(core.vfp().d(0))));

        run(core, neon3s(0, 2, 0b1111, 0, false, 3, 1, 2)); // vmin.f32 d3,d1,d2
        // VMIN(-0,+0) = -0
        assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(lane1(core.vfp().d(3))));

        run(core, neon3s(1, 0, 0b1111, 1, false, 4, 1, 2)); // vmaxnm.f32 d4,d1,d2 — só 1 NaN → o outro
        assertEquals(1.0f, lane0(core.vfp().d(4)));

        core.vfp().setD(5, twoLanes(Float.NaN, Float.NaN));
        run(core, neon3s(1, 0, 0b1111, 1, false, 4, 5, 5)); // ambos NaN → NaN
        assertEquals(Float.NaN, lane0(core.vfp().d(4)));
    }

    @Test
    void compareLanesBecomeAllOnesOrZeroAndNaNIsAlwaysFalse() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(2.0f, Float.NaN));
        core.vfp().setD(2, twoLanes(2.0f, 2.0f));
        run(core, neon3s(0, 0, 0b1110, 0, false, 0, 1, 2)); // vceq.f32
        assertEquals(0xFFFF_FFFFL, core.vfp().d(0) & 0xFFFF_FFFFL);
        assertEquals(0x0000_0000L, (core.vfp().d(0) >>> 32) & 0xFFFF_FFFFL);

        core.vfp().setD(1, twoLanes(3.0f, 1.0f));
        run(core, neon3s(1, 2, 0b1110, 0, false, 3, 1, 2)); // vcgt.f32 d3,d1,d2 (2.0)
        assertEquals(0xFFFF_FFFFL, core.vfp().d(3) & 0xFFFF_FFFFL);
        assertEquals(0x0000_0000L, (core.vfp().d(3) >>> 32) & 0xFFFF_FFFFL);
    }

    @Test
    void absoluteCompareComparesMagnitudes() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(-3.0f, -1.0f));
        core.vfp().setD(2, twoLanes(2.0f, 2.0f));
        run(core, neon3s(1, 0, 0b1110, 1, false, 0, 1, 2)); // vacge.f32 — |−3| >= |2| true, |−1| >= |2| false
        assertEquals(0xFFFF_FFFFL, core.vfp().d(0) & 0xFFFF_FFFFL);
        assertEquals(0x0000_0000L, (core.vfp().d(0) >>> 32) & 0xFFFF_FFFFL);
    }

    @Test
    void recpsAndRsqrtsUseTheSimplifiedForm() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(3.0f, 3.0f));
        core.vfp().setD(2, twoLanes(0.5f, 0.5f));
        run(core, neon3s(0, 0, 0b1111, 1, false, 0, 1, 2)); // vrecps.f32 : 2.0 - 3*0.5 = 0.5
        assertEquals(0.5f, lane0(core.vfp().d(0)));
        run(core, neon3s(0, 2, 0b1111, 1, false, 3, 1, 2)); // vrsqrts.f32 : (3.0 - 3*0.5)/2 = 0.75
        assertEquals(0.75f, lane0(core.vfp().d(3)));
    }

    @Test
    void pairwiseCombinesAdjacentLanesFromNThenM() {
        ArmCore core = newCore();
        core.vfp().setD(1, twoLanes(1.0f, 2.0f));
        core.vfp().setD(2, twoLanes(10.0f, 40.0f));
        run(core, neon3s(1, 0, 0b1101, 0, false, 0, 1, 2)); // vpadd.f32 d0,d1,d2
        assertEquals(3.0f, lane0(core.vfp().d(0)));   // d1[0]+d1[1]
        assertEquals(50.0f, lane1(core.vfp().d(0)));  // d2[0]+d2[1]

        core.vfp().setD(1, twoLanes(1.0f, 7.0f));
        core.vfp().setD(2, twoLanes(4.0f, 3.0f));
        run(core, neon3s(1, 0, 0b1111, 0, false, 5, 1, 2)); // vpmax.f32 d5,d1,d2
        assertEquals(7.0f, lane0(core.vfp().d(5)));
        assertEquals(4.0f, lane1(core.vfp().d(5)));

        run(core, neon3s(1, 2, 0b1111, 0, false, 6, 1, 2)); // vpmin.f32 d6,d1,d2
        assertEquals(1.0f, lane0(core.vfp().d(6)));
        assertEquals(3.0f, lane1(core.vfp().d(6)));
    }

    @Test
    void stillUnimplementedInPlainArmv7a() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(ArmArchitecture.ARMV7A, neon3s(0, 0, 0b1101, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(ArmArchitecture.ARM11_MPCORE, neon3s(1, 0, 0b1111, 1, false, 0, 1, 2)).kind());
    }
}
