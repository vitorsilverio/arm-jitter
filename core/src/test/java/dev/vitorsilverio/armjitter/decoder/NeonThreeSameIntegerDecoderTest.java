package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdPairwiseOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
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

/// NEON 3-reg-same INTEIRO A32 (task B13.4): aritmética / comparação / lógica / pairwise da seção
/// "3-reg-same" de `neon-dp.decode` → `IrOp.NeonThreeSame`/`IrOp.NeonPairwise` → execução pelo
/// núcleo vetorial COMPARTILHADO com o lado A64 ({@code AdvSimdLanes}).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon -mcpu=cortex-a8` do devkitARM
/// (precedente B9.6/B13.3).
class NeonThreeSameIntegerDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeon3s",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst());

    private static List<DecoderExtension> neonFirst() {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonDataProcessingDecoder(NEON_FEATURES));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 0 D sz Vn Vd opc N Q M op Vm`.
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

    // ── Encoding golden (assembler real) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xF201_0002, neon3s(0, 0, 0b0000, 0, false, 0, 1, 2));   // vhadd.s8  d0,d1,d2
        assertEquals(0xF312_0044, neon3s(1, 1, 0b0000, 0, true, 0, 2, 4));    // vhadd.u16 q0,q1,q2
        assertEquals(0xF201_0112, neon3s(0, 0, 0b0001, 1, false, 0, 1, 2));   // vand      d0,d1,d2
        assertEquals(0xF231_0112, neon3s(0, 3, 0b0001, 1, false, 0, 1, 2));   // vorn      d0,d1,d2
        assertEquals(0xF31B_A11C, neon3s(1, 1, 0b0001, 1, false, 10, 11, 12)); // vbsl     d10,d11,d12
        assertEquals(0xF211_0302, neon3s(0, 1, 0b0011, 0, false, 0, 1, 2));   // vcgt.s16  d0,d1,d2
        assertEquals(0xF201_A712, neon3s(0, 0, 0b0111, 1, false, 10, 1, 2));  // vaba.s8   d10,d1,d2
        assertEquals(0xF231_0802, neon3s(0, 3, 0b1000, 0, false, 0, 1, 2));   // vadd.i64  d0,d1,d2
        assertEquals(0xF211_0812, neon3s(0, 1, 0b1000, 1, false, 0, 1, 2));   // vtst.16   d0,d1,d2
        assertEquals(0xF301_0912, neon3s(1, 0, 0b1001, 1, false, 0, 1, 2));   // vmul.p8   d0,d1,d2
        assertEquals(0xF211_0B12, neon3s(0, 1, 0b1011, 1, false, 0, 1, 2));   // vpadd.i16 d0,d1,d2
        assertEquals(0xF201_0A02, neon3s(0, 0, 0b1010, 0, false, 0, 1, 2));   // vpmax.s8  d0,d1,d2
        assertEquals(0xF311_0A12, neon3s(1, 1, 0b1010, 1, false, 0, 1, 2));   // vpmin.u16 d0,d1,d2
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                neon3s(0, 0, 0b0000, 0, false, 0, 1, 2), // vhadd
                neon3s(0, 0, 0b0001, 1, false, 0, 1, 2), // vand
                neon3s(0, 1, 0b0011, 0, false, 0, 1, 2), // vcgt
                neon3s(0, 2, 0b1000, 0, false, 0, 1, 2), // vadd
                neon3s(0, 1, 0b1011, 1, false, 0, 1, 2), // vpadd
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Decode: cada família → IrOp certo ──

    @Test
    void arithmeticFamiliesDecode() {
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SHADD, false, 0, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b0000, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UHADD, true, 1, 0, 2, 4),
                liftedOf(neon3s(1, 1, 0b0000, 0, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SRHADD, false, 2, 5, 6, 7),
                liftedOf(neon3s(0, 2, 0b0001, 0, false, 5, 6, 7)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UHSUB, false, 0, 3, 4, 5),
                liftedOf(neon3s(1, 0, 0b0010, 0, false, 3, 4, 5)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SMAX, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b0110, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UMIN, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b0110, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SABD, false, 1, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b0111, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.UABA, false, 0, 10, 1, 2),
                liftedOf(neon3s(1, 0, 0b0111, 1, false, 10, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 3, 0, 1, 2),
                liftedOf(neon3s(0, 3, 0b1000, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.SUB, true, 2, 0, 2, 4),
                liftedOf(neon3s(1, 2, 0b1000, 0, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.MLA, false, 1, 10, 1, 2),
                liftedOf(neon3s(0, 1, 0b1001, 0, false, 10, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.MLS, false, 1, 10, 1, 2),
                liftedOf(neon3s(1, 1, 0b1001, 0, false, 10, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.MUL, false, 2, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b1001, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.PMUL, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1001, 1, false, 0, 1, 2)));
    }

    @Test
    void comparisonFamiliesDecode() {
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.CMGT, false, 1, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b0011, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.CMHI, false, 1, 0, 1, 2),
                liftedOf(neon3s(1, 1, 0b0011, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.CMGE, true, 0, 0, 2, 4),
                liftedOf(neon3s(0, 0, 0b0011, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.CMHS, true, 0, 0, 2, 4),
                liftedOf(neon3s(1, 0, 0b0011, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.CMTST, false, 1, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b1000, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.CMEQ, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1000, 1, false, 0, 1, 2)));
    }

    @Test
    void logicalFamilyDecodesWithEszZeroRegardlessOfSizeField() {
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.AND, false, 0, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b0001, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.BIC, false, 0, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b0001, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ORR, false, 0, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b0001, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ORN, false, 0, 0, 1, 2),
                liftedOf(neon3s(0, 3, 0b0001, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.EOR, true, 0, 0, 2, 4),
                liftedOf(neon3s(1, 0, 0b0001, 1, true, 0, 2, 4)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.BSL, false, 0, 10, 11, 12),
                liftedOf(neon3s(1, 1, 0b0001, 1, false, 10, 11, 12)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.BIT, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 2, 0b0001, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.BIF, false, 0, 0, 1, 2),
                liftedOf(neon3s(1, 3, 0b0001, 1, false, 0, 1, 2)));
    }

    @Test
    void pairwiseFamiliesDecode() {
        assertEquals(new IrOp.NeonPairwise(AdvSimdPairwiseOp.ADD, 1, 0, 1, 2),
                liftedOf(neon3s(0, 1, 0b1011, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonPairwise(AdvSimdPairwiseOp.SMAX, 0, 0, 1, 2),
                liftedOf(neon3s(0, 0, 0b1010, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonPairwise(AdvSimdPairwiseOp.UMAX, 0, 0, 1, 2),
                liftedOf(neon3s(1, 0, 0b1010, 0, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonPairwise(AdvSimdPairwiseOp.SMIN, 2, 0, 1, 2),
                liftedOf(neon3s(0, 2, 0b1010, 1, false, 0, 1, 2)));
        assertEquals(new IrOp.NeonPairwise(AdvSimdPairwiseOp.UMIN, 1, 0, 1, 2),
                liftedOf(neon3s(1, 1, 0b1010, 1, false, 0, 1, 2)));
    }

    @Test
    void doubleRegisterAboveD15IsAddressableWithTheFeature() {
        assertEquals(new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 2, 31, 16, 17),
                liftedOf(neon3s(0, 2, 0b1000, 0, false, 31, 16, 17)));
    }

    // ── UNDEFINED / fora de escopo (G8) ──

    @Test
    void quadFormWithOddRegisterIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(neon3s(0, 2, 0b1000, 0, true, 1, 2, 4)).kind());
    }

    @Test
    void pairwiseInQuadFormIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(neon3s(0, 1, 0b1010, 0, true, 0, 2, 4)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED,
                decode(neon3s(0, 1, 0b1011, 1, true, 0, 2, 4)).kind());
    }

    @Test
    void doublewordSizeIsUndefinedExceptForAddSubAndLogical() {
        // VADD/VSUB .i64 e a lógica (VORN/VBIF) são válidos:
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(neon3s(0, 3, 0b1000, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(neon3s(0, 3, 0b0001, 1, false, 0, 1, 2)).kind());
        // Todo o resto do inteiro é 8/16/32:
        for (int[] opOp : new int[][]{{0b0000, 0}, {0b0001, 0}, {0b0010, 0}, {0b0011, 0}, {0b0011, 1},
                {0b0110, 0}, {0b0110, 1}, {0b0111, 0}, {0b0111, 1}, {0b1000, 1}, {0b1001, 0}, {0b1001, 1}}) {
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(neon3s(0, 3, opOp[0], opOp[1], false, 0, 1, 2)).kind(),
                    "opc=" + opOp[0] + " op=" + opOp[1]);
        }
        // Pairwise .i64 também:
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 3, 0b1010, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 3, 0b1011, 1, false, 0, 1, 2)).kind());
    }

    @Test
    void polynomialMultiplyOnlyExistsInByte() {
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(neon3s(1, 0, 0b1001, 1, false, 0, 1, 2)).kind());
        for (int size = 1; size <= 3; size++) {
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(neon3s(1, size, 0b1001, 1, false, 0, 1, 2)).kind(), "size=" + size);
        }
    }

    @Test
    void outOfScopeEncodingsAreUnimplementedNotMisdecoded() {
        // VQADD/VQSUB/shifts/VQDMULH/VQRDMULH/VQRDMLAH/VQRDMLSH passaram a decodificar em B13.5 —
        // ver NeonThreeSameSaturatingDecoderTest. Aqui só o que continua fora de escopo nesta arch
        // (que NÃO declara ADVANCED_SIMD_RDM):
        // VQRDMLAH (opc=1011 op=1 U=1) / VQRDMLSH (opc=1100 op=1 U=1) sem FEAT_RDM → UNIMPLEMENTED
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 1, 0b1011, 1, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(1, 1, 0b1100, 1, false, 0, 1, 2)).kind());
        // opc=1100 cripto (op=0) / VFMA_fp (op=1 U=0) — B13.15 / B13.6
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1100, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1100, 1, false, 0, 1, 2)).kind());
        // FP three-same (opc=1101/1110/1111) — B13.6
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1101, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1110, 0, false, 0, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(neon3s(0, 0, 0b1111, 1, false, 0, 1, 2)).kind());
    }

    // ── Execução: núcleo compartilhado com o A64 ──

    @Test
    void vaddI16WrapsPerLane() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0001_0002_0003_FFFFL);
        core.vfp().setD(2, 0x0010_0020_0030_0001L);
        run(core, neon3s(0, 1, 0b1000, 0, false, 0, 1, 2));
        assertEquals(0x0011_0022_0033_0000L, core.vfp().d(0));
    }

    @Test
    void signedVsUnsignedDifferOnHighBitOperands() {
        // VMAX.S8 vs VMAX.U8 com 0x80 (=-128 assinado, 128 não assinado) vs 0x01.
        ArmCore signed = newCore();
        signed.vfp().setD(1, 0x0000_0000_0000_0080L);
        signed.vfp().setD(2, 0x0000_0000_0000_0001L);
        run(signed, neon3s(0, 0, 0b0110, 0, false, 0, 1, 2)); // vmax.s8
        assertEquals(0x0000_0000_0000_0001L, signed.vfp().d(0)); // 1 > -128

        ArmCore unsigned = newCore();
        unsigned.vfp().setD(1, 0x0000_0000_0000_0080L);
        unsigned.vfp().setD(2, 0x0000_0000_0000_0001L);
        run(unsigned, neon3s(1, 0, 0b0110, 0, false, 0, 1, 2)); // vmax.u8
        assertEquals(0x0000_0000_0000_0080L, unsigned.vfp().d(0)); // 128 > 1
    }

    @Test
    void vcgtSignedGivesAllOnesOrZeroPerLane() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0005_FFFF_FFFEL); // lane1=5, lane0=-2 (s32)
        core.vfp().setD(2, 0x0000_0003_0000_0000L); // lane1=3, lane0=0
        run(core, neon3s(0, 2, 0b0011, 0, false, 0, 1, 2)); // vcgt.s32
        assertEquals(0xFFFF_FFFF_0000_0000L, core.vfp().d(0)); // 5>3 true; -2>0 false
    }

    @Test
    void vhaddSignedIsArithmeticShift() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_00FEL); // -2 (s8)
        core.vfp().setD(2, 0x0000_0000_0000_00FCL); // -4 (s8)
        run(core, neon3s(0, 0, 0b0000, 0, false, 0, 1, 2)); // vhadd.s8
        assertEquals(0x0000_0000_0000_00FDL, core.vfp().d(0)); // (-2 + -4) >> 1 = -3 = 0xFD
    }

    @Test
    void logicalOpsOperateOnTheWholeDoubleword() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0xF0F0_F0F0_F0F0_F0F0L);
        core.vfp().setD(2, 0x00FF_00FF_00FF_00FFL);
        run(core, neon3s(0, 3, 0b0001, 1, false, 0, 1, 2)); // vorn d0,d1,d2 : d1 | ~d2
        assertEquals(0xFFF0_FFF0_FFF0_FFF0L, core.vfp().d(0));
    }

    @Test
    void vbslReadsDestinationAsControlMask() {
        ArmCore core = newCore();
        core.vfp().setD(10, 0xFF00_FF00_FF00_FF00L); // control
        core.vfp().setD(11, 0xAAAA_AAAA_AAAA_AAAAL);
        core.vfp().setD(12, 0x5555_5555_5555_5555L);
        run(core, neon3s(1, 1, 0b0001, 1, false, 10, 11, 12)); // vbsl d10,d11,d12
        assertEquals(0xAA55_AA55_AA55_AA55L, core.vfp().d(10));
    }

    @Test
    void vabaAccumulatesIntoDestination() {
        ArmCore core = newCore();
        core.vfp().setD(10, 0x0000_0000_0000_000AL); // Rd starts at 10 (lane0, s8)
        core.vfp().setD(1, 0x0000_0000_0000_0007L);
        core.vfp().setD(2, 0x0000_0000_0000_0002L);
        run(core, neon3s(0, 0, 0b0111, 1, false, 10, 1, 2)); // vaba.s8 : Rd += |7-2| = 10+5
        assertEquals(0x0000_0000_0000_000FL, core.vfp().d(10));
    }

    @Test
    void vmulPolynomialByte() {
        ArmCore core = newCore();
        // 0x03 (x+1) * 0x07 (x^2+x+1) em GF(2) = x^3+1 = 0x09, truncado a 8 bits.
        core.vfp().setD(1, 0x0000_0000_0000_0003L);
        core.vfp().setD(2, 0x0000_0000_0000_0007L);
        run(core, neon3s(1, 0, 0b1001, 1, false, 0, 1, 2)); // vmul.p8
        assertEquals(0x0000_0000_0000_0009L, core.vfp().d(0));
    }

    @Test
    void vpaddInterleavesRnThenRm() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0004_0003_0002_0001L); // lanes [1,2,3,4]
        core.vfp().setD(2, 0x0040_0030_0020_0010L); // lanes [16,32,48,64]
        run(core, neon3s(0, 1, 0b1011, 1, false, 0, 1, 2)); // vpadd.i16 d0,d1,d2
        // [1+2, 3+4, 16+32, 48+64] = [3, 7, 48, 112]
        assertEquals(0x0070_0030_0007_0003L, core.vfp().d(0));
    }

    @Test
    void vpmaxSignedInterleavesRnThenRm() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0000_FF01L); // s8 lanes: lane0=1, lane1=-1
        core.vfp().setD(2, 0x0000_0000_0000_0207L); // s8 lanes: lane0=7, lane1=2
        run(core, neon3s(0, 0, 0b1010, 0, false, 0, 1, 2)); // vpmax.s8 d0,d1,d2
        // 8 byte lanes: results[0]=max(1,-1)=1 (de d1), results[4]=max(7,2)=7 (de d2), resto 0.
        assertEquals(0x0000_0007_0000_0001L, core.vfp().d(0));
    }

    @Test
    void quadFormExecutesBothDoublewords() {
        ArmCore core = newCore();
        core.vfp().setD(2, 0x0000_0005_0000_0003L);
        core.vfp().setD(3, 0x0000_0007_0000_0009L);
        core.vfp().setD(4, 0x0000_0001_0000_0001L);
        core.vfp().setD(5, 0x0000_0001_0000_0001L);
        run(core, neon3s(1, 2, 0b1000, 0, true, 0, 2, 4)); // vsub.i32 q0,q1,q2
        assertEquals(0x0000_0004_0000_0002L, core.vfp().d(0));
        assertEquals(0x0000_0006_0000_0008L, core.vfp().d(1));
    }
}
