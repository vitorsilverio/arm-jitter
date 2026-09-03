package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// NEON "2-reg-and-shift" com deslocamento por IMEDIATO A32 (task B13.7): `VSHR`/`VSRA`/`VRSHR`/
/// `VRSRA`/`VSRI`/`VSHL`/`VSLI`/`VQSHLU`/`VQSHL` (14 famílias × 4 larguras) →
/// `IrOp.NeonShiftImmediate` → execução pelo núcleo vetorial COMPARTILHADO com o lado A64
/// ({@code AdvSimdLanes.shiftImmediate}).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon -march=armv8-a` do devkitARM.
class NeonShiftImmediateDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonShift",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonShiftImmediateDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 1 D immH:3 immL:3 Vd:4 opc:4 L Q M 1 Vm:4` — `immh` de 4 bits inclui `L` (bit7)
    /// como seu bit mais alto.
    private static int enc(int u, int immh4, int imml3, int opc, boolean quad, int vd, int vm) {
        int l = (immh4 >> 3) & 1;
        int immH = immh4 & 0x7;
        return 0xF280_0010
                | (u << 24)
                | (immH << 19) | (imml3 << 16)
                | ((vd & 0xF) << 12) | ((vd >> 4) << 22)
                | (opc << 8)
                | (l << 7) | (quad ? 1 << 6 : 0)
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
        assertEquals(0xF28F0011, enc(0, 1, 7, 0b0000, false, 0, 1));  // vshr.s8   d0,d1,#1
        assertEquals(0xF2880011, enc(0, 1, 0, 0b0000, false, 0, 1));  // vshr.s8   d0,d1,#8
        assertEquals(0xF38D0011, enc(1, 1, 5, 0b0000, false, 0, 1));  // vshr.u8   d0,d1,#3
        assertEquals(0xF29F0011, enc(0, 3, 7, 0b0000, false, 0, 1));  // vshr.s16  d0,d1,#1
        assertEquals(0xF2900011, enc(0, 2, 0, 0b0000, false, 0, 1));  // vshr.s16  d0,d1,#16
        assertEquals(0xF2BF0011, enc(0, 7, 7, 0b0000, false, 0, 1));  // vshr.s32  d0,d1,#1
        assertEquals(0xF2A00011, enc(0, 4, 0, 0b0000, false, 0, 1));  // vshr.s32  d0,d1,#32
        assertEquals(0xF2BF0091, enc(0, 15, 7, 0b0000, false, 0, 1)); // vshr.s64  d0,d1,#1
        assertEquals(0xF2800091, enc(0, 8, 0, 0b0000, false, 0, 1));  // vshr.s64  d0,d1,#64
        assertEquals(0xF28E0111, enc(0, 1, 6, 0b0001, false, 0, 1));  // vsra.s8   d0,d1,#2
        assertEquals(0xF28F0211, enc(0, 1, 7, 0b0010, false, 0, 1));  // vrshr.s8  d0,d1,#1
        assertEquals(0xF28E0311, enc(0, 1, 6, 0b0011, false, 0, 1));  // vrsra.s8  d0,d1,#2
        assertEquals(0xF38F0411, enc(1, 1, 7, 0b0100, false, 0, 1));  // vsri.8    d0,d1,#1
        assertEquals(0xF2880511, enc(0, 1, 0, 0b0101, false, 0, 1));  // vshl.i8   d0,d1,#0
        assertEquals(0xF28F0511, enc(0, 1, 7, 0b0101, false, 0, 1));  // vshl.i8   d0,d1,#7
        assertEquals(0xF3890511, enc(1, 1, 1, 0b0101, false, 0, 1));  // vsli.8    d0,d1,#1
        assertEquals(0xF3890611, enc(1, 1, 1, 0b0110, false, 0, 1));  // vqshlu.s8 d0,d1,#1
        assertEquals(0xF2890711, enc(0, 1, 1, 0b0111, false, 0, 1));  // vqshl.s8  d0,d1,#1
        assertEquals(0xF3890711, enc(1, 1, 1, 0b0111, false, 0, 1));  // vqshl.u8  d0,d1,#1
        // Q forms
        assertEquals(0xF28F0052, enc(0, 1, 7, 0b0000, true, 0, 2));   // vshr.s8   q0,q1,#1
        assertEquals(0xF3B94056, enc(1, 7, 1, 0b0000, true, 4, 6));   // vshr.u32  q2,q3,#7
        assertEquals(0xF2A80752, enc(0, 5, 0, 0b0111, true, 0, 2));   // vqshl.s32 q0,q1,#8
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                enc(0, 1, 7, 0b0000, false, 0, 1),  // vshr.s8
                enc(0, 1, 6, 0b0001, false, 0, 1),  // vsra.s8
                enc(1, 1, 7, 0b0100, false, 0, 1),  // vsri.8
                enc(0, 1, 0, 0b0101, false, 0, 1),  // vshl.i8
                enc(1, 1, 1, 0b0110, false, 0, 1),  // vqshlu.s8
                enc(0, 1, 1, 0b0111, false, 0, 1),  // vqshl.s8
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    // ── Decode: cada família → AdvSimdShiftImmediateOp + esz + shift certos ──

    @Test
    void everyFamilyDecodesWithTheRightOperationEszAndShift() {
        // opc 0000: VSHR
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SSHR, false, 0, 1, 0, 1),
                liftedOf(enc(0, 1, 7, 0b0000, false, 0, 1)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.USHR, false, 0, 3, 0, 1),
                liftedOf(enc(1, 1, 5, 0b0000, false, 0, 1)));
        // opc 0001: VSRA
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SSRA, false, 1, 4, 2, 3),
                liftedOf(enc(0, 3, 4, 0b0001, false, 2, 3)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.USRA, false, 2, 8, 4, 5),
                liftedOf(enc(1, 7, 0, 0b0001, false, 4, 5)));
        // opc 0010: VRSHR
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SRSHR, false, 0, 1, 0, 1),
                liftedOf(enc(0, 1, 7, 0b0010, false, 0, 1)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.URSHR, false, 3, 40, 6, 7),
                liftedOf(enc(1, 11, 0, 0b0010, false, 6, 7)));
        // opc 0011: VRSRA
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SRSRA, false, 0, 2, 0, 1),
                liftedOf(enc(0, 1, 6, 0b0011, false, 0, 1)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.URSRA, false, 1, 5, 2, 3),
                liftedOf(enc(1, 3, 3, 0b0011, false, 2, 3)));
        // opc 0100 U=1: VSRI
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SRI, false, 0, 1, 0, 1),
                liftedOf(enc(1, 1, 7, 0b0100, false, 0, 1)));
        // opc 0101: VSHL (U=0) / VSLI (U=1) — deslocamento à ESQUERDA
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SHL, false, 0, 0, 0, 1),
                liftedOf(enc(0, 1, 0, 0b0101, false, 0, 1)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SHL, false, 0, 7, 0, 1),
                liftedOf(enc(0, 1, 7, 0b0101, false, 0, 1)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SLI, false, 1, 1, 0, 1),
                liftedOf(enc(1, 2, 1, 0b0101, false, 0, 1)));
        // opc 0110 U=1: VQSHLU (esquerda)
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SQSHLU, false, 0, 1, 0, 1),
                liftedOf(enc(1, 1, 1, 0b0110, false, 0, 1)));
        // opc 0111: VQSHL S/U (esquerda)
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.SQSHL, false, 0, 1, 0, 1),
                liftedOf(enc(0, 1, 1, 0b0111, false, 0, 1)));
        assertEquals(new IrOp.NeonShiftImmediate(AdvSimdShiftImmediateOp.UQSHL, false, 2, 8, 6, 7),
                liftedOf(enc(1, 5, 0, 0b0111, false, 6, 7)));
    }

    @Test
    void rightShiftAmountSpansOneToEsizeOnEveryWidth() {
        assertEquals(1, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 1, 7, 0b0000, false, 0, 1))).shift());   // .s8 #1
        assertEquals(8, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 1, 0, 0b0000, false, 0, 1))).shift());   // .s8 #8
        assertEquals(1, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 3, 7, 0b0000, false, 0, 1))).shift());   // .s16 #1
        assertEquals(16, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 2, 0, 0b0000, false, 0, 1))).shift());  // .s16 #16
        assertEquals(1, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 7, 7, 0b0000, false, 0, 1))).shift());   // .s32 #1
        assertEquals(32, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 4, 0, 0b0000, false, 0, 1))).shift());  // .s32 #32
        assertEquals(1, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 15, 7, 0b0000, false, 0, 1))).shift());  // .s64 #1
        assertEquals(64, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 8, 0, 0b0000, false, 0, 1))).shift());  // .s64 #64
    }

    @Test
    void leftShiftAmountSpansZeroToEsizeMinusOne() {
        assertEquals(0, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 1, 0, 0b0101, false, 0, 1))).shift());   // .i8 #0
        assertEquals(7, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 1, 7, 0b0101, false, 0, 1))).shift());   // .i8 #7
        assertEquals(0, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 2, 0, 0b0101, false, 0, 1))).shift());   // .i16 #0
        assertEquals(15, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 3, 7, 0b0101, false, 0, 1))).shift());  // .i16 #15
        assertEquals(0, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 4, 0, 0b0101, false, 0, 1))).shift());   // .i32 #0
        assertEquals(63, ((IrOp.NeonShiftImmediate) liftedOf(enc(0, 15, 7, 0b0101, false, 0, 1))).shift()); // .i64 #63
    }

    // ── Execução: núcleo compartilhado com o A64 ──

    @Test
    void signedVersusUnsignedRightShiftOnAByteWithSignBitSet() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x80L); // lane0 = 0x80 (-128 assinado)
        run(core, enc(0, 1, 7, 0b0000, false, 0, 1)); // vshr.s8 d0,d1,#1
        assertEquals(0xC0L, core.vfp().d(0) & 0xFFL);

        run(core, enc(1, 1, 7, 0b0000, false, 0, 1)); // vshr.u8 d0,d1,#1
        assertEquals(0x40L, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void roundingRightShiftAddsHalfBeforeShifting() {
        ArmCore core = newCore();
        core.vfp().setD(5, 3L);
        run(core, enc(0, 7, 7, 0b0010, false, 4, 5)); // vrshr.s32 d4,d5,#1  → (3+1)>>1 = 2
        assertEquals(2L, core.vfp().d(4) & 0xFFFF_FFFFL);
    }

    @Test
    void sraAccumulatesIntoTheCurrentDestination() {
        ArmCore core = newCore();
        core.vfp().setD(0, 10L);   // acumulador
        core.vfp().setD(1, 0x80L); // -128
        run(core, enc(0, 1, 7, 0b0001, false, 0, 1)); // vsra.s8 d0,d1,#1 → 10 + (-64) = -54 = 0xCA
        assertEquals(0xCAL, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void rsraRoundsThenAccumulates() {
        ArmCore core = newCore();
        core.vfp().setD(0, 100L);
        core.vfp().setD(1, 3L);
        run(core, enc(0, 7, 7, 0b0011, false, 0, 1)); // vrsra.s32 d0,d1,#1 → 100 + ((3+1)>>1) = 102
        assertEquals(102L, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    @Test
    void sriPreservesHighBitsAndSliPreservesLowBits() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0xFFL);
        core.vfp().setD(1, 0x80L);
        run(core, enc(1, 1, 7, 0b0100, false, 0, 1)); // vsri.8 d0,d1,#1 → keep top 1 bit of 0xFF, insert 0x80>>1
        assertEquals(0xC0L, core.vfp().d(0) & 0xFFL);

        core.vfp().setD(0, 0xFFL);
        core.vfp().setD(1, 0x01L);
        run(core, enc(1, 1, 1, 0b0101, false, 0, 1)); // vsli.8 d0,d1,#1 → keep low 1 bit of 0xFF, insert 0x01<<1
        assertEquals(0x03L, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void qshlSaturatesSignedAndUnsigned() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x40L); // 64
        run(core, enc(0, 1, 1, 0b0111, false, 0, 1)); // vqshl.s8 d0,d1,#1 → 128 saturates to 0x7F
        assertEquals(0x7FL, core.vfp().d(0) & 0xFFL);

        core.vfp().setD(1, 0xC0L); // -64
        run(core, enc(0, 1, 1, 0b0111, false, 0, 1)); // -64<<1 = -128, in range → 0x80
        assertEquals(0x80L, core.vfp().d(0) & 0xFFL);

        core.vfp().setD(1, 0x80L); // 128 unsigned
        run(core, enc(1, 1, 1, 0b0111, false, 0, 1)); // vqshl.u8 d0,d1,#1 → 256 saturates to 0xFF
        assertEquals(0xFFL, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void qshluWithNegativeSourceSaturatesToZero() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0xFFL); // -1 assinado
        run(core, enc(1, 1, 1, 0b0110, false, 0, 1)); // vqshlu.s8 d0,d1,#1 → -2 saturates to 0
        assertEquals(0x00L, core.vfp().d(0) & 0xFFL);

        core.vfp().setD(1, 0x40L); // 64
        run(core, enc(1, 1, 1, 0b0110, false, 0, 1)); // 64<<1 = 128, unsigned byte → 0x80
        assertEquals(0x80L, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void doublewordWidthShifts() {
        ArmCore core = newCore();
        core.vfp().setD(1, 1L);
        run(core, enc(0, 13, 0, 0b0101, false, 0, 1)); // vshl.i64 d0,d1,#40
        assertEquals(1L << 40, core.vfp().d(0));

        core.vfp().setD(1, 0x8000_0000_0000_0000L); // MSB set
        run(core, enc(1, 15, 7, 0b0000, false, 0, 1)); // vshr.u64 d0,d1,#1
        assertEquals(0x4000_0000_0000_0000L, core.vfp().d(0));
    }

    @Test
    void quadFormShiftsAllFourWordLanes() {
        ArmCore core = newCore();
        core.vfp().setD(2, (16L & 0xFFFF_FFFFL) | (32L << 32));
        core.vfp().setD(3, (64L & 0xFFFF_FFFFL) | (128L << 32));
        run(core, enc(1, 7, 1, 0b0000, true, 0, 2)); // vshr.u32 q0,q1,#7
        assertEquals(0L, core.vfp().d(0) & 0xFFFF_FFFFL);
        assertEquals(0L, (core.vfp().d(0) >>> 32) & 0xFFFF_FFFFL);
        assertEquals(0L, core.vfp().d(1) & 0xFFFF_FFFFL);
        assertEquals(1L, (core.vfp().d(1) >>> 32) & 0xFFFF_FFFFL); // 128 >> 7 = 1
    }

    // ── UNDEFINED / G8 ──

    @Test
    void quadFormWithOddRegisterIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 7, 0b0000, true, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 7, 0b0000, true, 0, 3)).kind());
    }

    @Test
    void unallocatedSlotsAreUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 7, 0b0100, false, 0, 1)).kind()); // opc=0100 U=0
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 7, 0b0110, false, 0, 1)).kind()); // opc=0110 U=0
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 7, 0b1011, false, 0, 1)).kind()); // opc=1011
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(1, 1, 7, 0b1011, false, 0, 1)).kind()); // opc=1011 U=1
    }

    @Test
    void b138UnallocatedAndF16StayUnimplemented() {
        // opc=1011 é UNALLOCATED real; opc=1100/1101 são VCVT F16 (task irmã, depende de B19.5.1).
        for (int opc : new int[] {0b1011, 0b1100, 0b1101}) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 7, 7, opc, false, 0, 1)).kind(),
                    "opc=" + Integer.toBinaryString(opc) + " U=0");
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(1, 7, 7, opc, false, 0, 1)).kind(),
                    "opc=" + Integer.toBinaryString(opc) + " U=1");
            // e a mesma palavra sem a feature continua UNIMPLEMENTED (zero-diff)
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(ArmArchitecture.ARMV7A, enc(0, 7, 7, opc, false, 0, 1)).kind());
        }
    }

    /// `immh == 0` (`L=0 && immH=000`) é o `Vimm_1r` (B13.9), que mora no MESMO frame. Este decoder
    /// NÃO o reivindica: `tryDecode` devolve `null` (não `unimplemented`), deixando o espaço livre
    /// para a B13.9 registrar o decoder dela. A palavra continua caindo no `UNIMPLEMENTED` de
    /// `ArmDecoder#decodeUnconditional`.
    @Test
    void immhZeroIsNotClaimedByThisDecoder() {
        int vimm1r = enc(0, 0, 0, 0b0101, false, 0, 1); // L=0, immH=000
        assertNull(new NeonShiftImmediateDecoder(NEON_FEATURES).tryDecode(vimm1r, 0, Condition.AL));
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(vimm1r).kind());
        // mas L=1/immH=000 (immh=1000, esz=3) É desta seção
        int realShift = enc(0, 8, 0, 0b0000, false, 0, 1); // vshr.s64 d0,d1,#64
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(realShift).kind());
    }

    @Test
    void featureGateReturnsNullBeforeFrameCheck() {
        int word = enc(0, 1, 7, 0b0000, false, 0, 1);
        assertNull(new NeonShiftImmediateDecoder(ArmArchitecture.ARMV7A).tryDecode(word, 0, Condition.AL));
    }

    // ══════════════════════════ B13.8: estreitamento / alargamento / VCVT fixo↔float ═════════════

    /// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon -march=armv8-a` (devkitARM).
    /// O parâmetro `quad` de {@link #enc} preenche o bit6, que aqui é OPCODE (estreitamento/
    /// alargamento) ou largura real (`VCVT`).
    @Test
    void b138EncodingsMatchTheAssembler() {
        // narrowing
        assertEquals(0xf28c0812, enc(0, 1, 4, 0b1000, false, 0, 2));  // vshrn.i16   d0,q1,#4
        assertEquals(0xf2980812, enc(0, 3, 0, 0b1000, false, 0, 2));  // vshrn.i32   d0,q1,#8
        assertEquals(0xf2b00812, enc(0, 6, 0, 0b1000, false, 0, 2));  // vshrn.i64   d0,q1,#16
        assertEquals(0xf28c0852, enc(0, 1, 4, 0b1000, true, 0, 2));   // vrshrn.i16  d0,q1,#4
        assertEquals(0xf38d0812, enc(1, 1, 5, 0b1000, false, 0, 2));  // vqshrun.s16 d0,q1,#3
        assertEquals(0xf38d0852, enc(1, 1, 5, 0b1000, true, 0, 2));   // vqrshrun.s16 d0,q1,#3
        assertEquals(0xf28d0912, enc(0, 1, 5, 0b1001, false, 0, 2));  // vqshrn.s16  d0,q1,#3
        assertEquals(0xf38d0912, enc(1, 1, 5, 0b1001, false, 0, 2));  // vqshrn.u16  d0,q1,#3
        assertEquals(0xf29b0952, enc(0, 3, 3, 0b1001, true, 0, 2));   // vqrshrn.s32 d0,q1,#5
        assertEquals(0xf28f2916, enc(0, 1, 7, 0b1001, false, 2, 6));  // vqshrn.s16  d2,q3,#1
        // widening
        assertEquals(0xf28b0a11, enc(0, 1, 3, 0b1010, false, 0, 1));  // vshll.s8    q0,d1,#3
        assertEquals(0xf38b0a11, enc(1, 1, 3, 0b1010, false, 0, 1));  // vshll.u8    q0,d1,#3
        assertEquals(0xf2950a11, enc(0, 2, 5, 0b1010, false, 0, 1));  // vshll.s16   q0,d1,#5
        assertEquals(0xf2a74a13, enc(0, 4, 7, 0b1010, false, 4, 3));  // vshll.s32   q2,d3,#7
        assertEquals(0xf3880a11, enc(1, 1, 0, 0b1010, false, 0, 1));  // vmovl.u8    q0,d1  (VSHLL #0)
        assertEquals(0xf2908a12, enc(0, 2, 0, 0b1010, false, 8, 2));  // vmovl.s16   q4,d2
        // vcvt fixed<->float f32
        assertEquals(0xf2bc0e11, enc(0, 7, 4, 0b1110, false, 0, 1));  // vcvt.f32.s32 d0,d1,#4
        assertEquals(0xf3b80e11, enc(1, 7, 0, 0b1110, false, 0, 1));  // vcvt.f32.u32 d0,d1,#8
        assertEquals(0xf2bf0f11, enc(0, 7, 7, 0b1111, false, 0, 1));  // vcvt.s32.f32 d0,d1,#1
        assertEquals(0xf3b80f11, enc(1, 7, 0, 0b1111, false, 0, 1));  // vcvt.u32.f32 d0,d1,#8
        assertEquals(0xf2bc0e52, enc(0, 7, 4, 0b1110, true, 0, 2));   // vcvt.f32.s32 q0,q1,#4
        assertEquals(0xf3b04f56, enc(1, 6, 0, 0b1111, true, 4, 6));   // vcvt.u32.f32 q2,q3,#16
    }

    @Test
    void b138WithoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                enc(0, 1, 4, 0b1000, false, 0, 2),  // vshrn.i16
                enc(1, 1, 5, 0b1000, false, 0, 2),  // vqshrun.s16
                enc(0, 1, 3, 0b1010, false, 0, 1),  // vshll.s8
                enc(0, 7, 4, 0b1110, false, 0, 1),  // vcvt.f32.s32
                enc(1, 7, 0, 0b1111, false, 0, 1),  // vcvt.u32.f32
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    @Test
    void b138NarrowingDecodesWithRightOpEszShift() {
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SHRN, 0, 4, 0, 2),
                liftedOf(enc(0, 1, 4, 0b1000, false, 0, 2)));      // vshrn.i16 d0,q1,#4
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SHRN, 2, 16, 0, 2),
                liftedOf(enc(0, 6, 0, 0b1000, false, 0, 2)));      // vshrn.i64 d0,q1,#16
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.RSHRN, 0, 4, 0, 2),
                liftedOf(enc(0, 1, 4, 0b1000, true, 0, 2)));       // vrshrn.i16
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SQSHRUN, 0, 3, 0, 2),
                liftedOf(enc(1, 1, 5, 0b1000, false, 0, 2)));      // vqshrun.s16
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SQRSHRUN, 0, 3, 0, 2),
                liftedOf(enc(1, 1, 5, 0b1000, true, 0, 2)));       // vqrshrun.s16
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SQSHRN, 0, 3, 0, 2),
                liftedOf(enc(0, 1, 5, 0b1001, false, 0, 2)));      // vqshrn.s16
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.SQRSHRN, 1, 5, 0, 2),
                liftedOf(enc(0, 3, 3, 0b1001, true, 0, 2)));       // vqrshrn.s32
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.UQSHRN, 0, 3, 0, 2),
                liftedOf(enc(1, 1, 5, 0b1001, false, 0, 2)));      // vqshrn.u16
        assertEquals(new IrOp.NeonShiftNarrowImmediate(AdvSimdShiftNarrowOp.UQRSHRN, 0, 3, 0, 2),
                liftedOf(enc(1, 1, 5, 0b1001, true, 0, 2)));       // vqrshrn.u16
    }

    @Test
    void b138WideningAndVcvtDecode() {
        assertEquals(new IrOp.NeonShiftWidenImmediate(AdvSimdShiftWidenOp.SSHLL, 0, 3, 0, 1),
                liftedOf(enc(0, 1, 3, 0b1010, false, 0, 1)));      // vshll.s8 q0,d1,#3
        assertEquals(new IrOp.NeonShiftWidenImmediate(AdvSimdShiftWidenOp.USHLL, 0, 0, 0, 1),
                liftedOf(enc(1, 1, 0, 0b1010, false, 0, 1)));      // vmovl.u8 q0,d1  (shift 0)
        assertEquals(new IrOp.NeonShiftWidenImmediate(AdvSimdShiftWidenOp.SSHLL, 2, 7, 4, 3),
                liftedOf(enc(0, 4, 7, 0b1010, false, 4, 3)));      // vshll.s32 q2,d3,#7
        assertEquals(new IrOp.NeonConvertFixedPoint(false, 2, 4, true, true, 0, 1),
                liftedOf(enc(0, 7, 4, 0b1110, false, 0, 1)));      // vcvt.f32.s32 d0,d1,#4
        assertEquals(new IrOp.NeonConvertFixedPoint(false, 2, 8, true, false, 0, 1),
                liftedOf(enc(1, 7, 0, 0b1110, false, 0, 1)));      // vcvt.f32.u32 d0,d1,#8
        assertEquals(new IrOp.NeonConvertFixedPoint(false, 2, 1, false, true, 0, 1),
                liftedOf(enc(0, 7, 7, 0b1111, false, 0, 1)));      // vcvt.s32.f32 d0,d1,#1
        assertEquals(new IrOp.NeonConvertFixedPoint(true, 2, 16, false, false, 4, 6),
                liftedOf(enc(1, 6, 0, 0b1111, true, 4, 6)));       // vcvt.u32.f32 q2,q3,#16
    }

    // ── UNDEFINED / G8 (B13.8) ──

    @Test
    void b138UndefinedCases() {
        // VCVT F16 e opc UNALLOCATED
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 7, 0, 0b1100, false, 0, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 7, 0, 0b1101, false, 0, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 7, 7, 0b1011, false, 0, 1)).kind());
        // alargamento com Q=1 (bit6) — UNALLOCATED
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 3, 0b1010, true, 0, 1)).kind());
        // estreitamento com esz=3 (immh4=8 ⇒ L=1) — sem linha no .decode
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 8, 0, 0b1000, false, 0, 2)).kind());
        // alargamento com esz=3 idem
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 8, 0, 0b1010, false, 0, 2)).kind());
        // VCVT F32 com immH que não dá esz=2 (immh4=1 ⇒ esz=0)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 0, 0b1110, false, 0, 1)).kind());
        // estreitamento: fonte Q ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 4, 0b1000, false, 0, 3)).kind());
        // alargamento: destino Q ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 3, 0b1010, false, 1, 1)).kind());
        // VCVT F32 Q: registrador ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 7, 4, 0b1110, true, 1, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 7, 4, 0b1110, true, 0, 3)).kind());
    }

    // ── Execução: núcleo compartilhado com o A64 (B13.8) ──

    @Test
    void narrowingShiftRightTruncatesRoundsAndSaturates() {
        ArmCore core = newCore();
        // VSHRN.i16 d0,q1,#4 — fonte Q1 = D2:D3, lane0 (halfword) = 0x00F0
        core.vfp().setD(2, 0x00F0L);
        run(core, enc(0, 1, 4, 0b1000, false, 0, 2));
        assertEquals(0x0FL, core.vfp().d(0) & 0xFFL);

        // VRSHRN.i16 #4 — 0x1F (31), (31+8)>>4 = 2 (arredonda; sem arredondar seria 1)
        core.vfp().setD(2, 0x1FL);
        run(core, enc(0, 1, 4, 0b1000, true, 0, 2));
        assertEquals(2L, core.vfp().d(0) & 0xFFL);

        // VQSHRUN.s16 #4 — fonte 0x8000 (-32768 assinado) satura em 0
        core.vfp().setD(2, 0x8000L);
        run(core, enc(1, 1, 4, 0b1000, false, 0, 2));
        assertEquals(0x00L, core.vfp().d(0) & 0xFFL);

        // VQSHRN.s16 #1 — 0x7FFF (32767) >> 1 = 16383 satura em 0x7F
        core.vfp().setD(2, 0x7FFFL);
        run(core, enc(0, 1, 7, 0b1001, false, 0, 2));
        assertEquals(0x7FL, core.vfp().d(0) & 0xFFL);

        // VQSHRN.u16 #1 — 0xFFFF (65535) >>> 1 = 32767 satura em 0xFF
        core.vfp().setD(2, 0xFFFFL);
        run(core, enc(1, 1, 7, 0b1001, false, 0, 2));
        assertEquals(0xFFL, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void wideningShiftSignAndZeroExtends() {
        ArmCore core = newCore();
        // VSHLL.s8 q0,d3,#4 — fonte D3 lane0 = 0xFF (-1) → sext << 4 = 0xFFF0 (halfword)
        core.vfp().setD(3, 0xFFL);
        run(core, enc(0, 1, 4, 0b1010, false, 0, 3));
        assertEquals(0xFFF0L, core.vfp().d(0) & 0xFFFFL);

        // VSHLL.u8 q0,d3,#4 — zext << 4 = 0x0FF0
        core.vfp().setD(3, 0xFFL);
        run(core, enc(1, 1, 4, 0b1010, false, 0, 3));
        assertEquals(0x0FF0L, core.vfp().d(0) & 0xFFFFL);
    }

    /// Prova do buffer (Armadilha 5): `Vd`==`Vm` no alargamento — a lane larga escrita cobriria
    /// lanes estreitas ainda não lidas.
    @Test
    void wideningWithDestinationAliasingSource() {
        ArmCore core = newCore();
        // VMOVL.u8 q0,d0 (VSHLL.u8 #0) — Q0 = D0:D1, fonte D0. Bytes de D0: 1,2,3,4,0,0,0,0.
        core.vfp().setD(0, 0x04030201L);
        run(core, enc(1, 1, 0, 0b1010, false, 0, 0));
        assertEquals(0x0004_0003_0002_0001L, core.vfp().d(0)); // hw lanes 0-3
        assertEquals(0L, core.vfp().d(1));                       // hw lanes 4-7
    }

    @Test
    void vcvtFixedToFloatAndBack() {
        ArmCore core = newCore();
        // VCVT.F32.S32 d0,d1,#4 — 32 / 2^4 = 2.0f
        core.vfp().setD(1, 32L);
        run(core, enc(0, 7, 4, 0b1110, false, 0, 1));
        assertEquals(Float.floatToRawIntBits(2.0f), (int) (core.vfp().d(0) & 0xFFFF_FFFFL));

        // VCVT.U32.F32 d0,d1,#8 — 1.5f * 2^8 = 384.0 → 384 (toward zero)
        core.vfp().setD(1, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL);
        run(core, enc(1, 7, 0, 0b1111, false, 0, 1));
        assertEquals(384L, core.vfp().d(0) & 0xFFFF_FFFFL);

        // VCVT.U32.F32 d0,d1,#1 — +Inf satura em 0xFFFFFFFF
        core.vfp().setD(1, Float.floatToRawIntBits(Float.POSITIVE_INFINITY) & 0xFFFF_FFFFL);
        run(core, enc(1, 7, 7, 0b1111, false, 0, 1));
        assertEquals(0xFFFF_FFFFL, core.vfp().d(0) & 0xFFFF_FFFFL);

        // VCVT.S32.F32 d0,d1,#1 — NaN → 0
        core.vfp().setD(1, 0x7FC0_0000L);
        run(core, enc(0, 7, 7, 0b1111, false, 0, 1));
        assertEquals(0L, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    @Test
    void vcvtQuadFormAllFourLanes() {
        ArmCore core = newCore();
        // VCVT.F32.S32 q0,q1,#1 — Q1 = D2:D3 = ints {2,4,6,8}, /2^1 → {1.0,2.0,3.0,4.0}
        core.vfp().setD(2, (2L & 0xFFFF_FFFFL) | (4L << 32));
        core.vfp().setD(3, (6L & 0xFFFF_FFFFL) | (8L << 32));
        run(core, enc(0, 7, 7, 0b1110, true, 0, 2));
        assertEquals(Float.floatToRawIntBits(1.0f), (int) (core.vfp().d(0) & 0xFFFF_FFFFL));
        assertEquals(Float.floatToRawIntBits(2.0f), (int) ((core.vfp().d(0) >>> 32) & 0xFFFF_FFFFL));
        assertEquals(Float.floatToRawIntBits(3.0f), (int) (core.vfp().d(1) & 0xFFFF_FFFFL));
        assertEquals(Float.floatToRawIntBits(4.0f), (int) ((core.vfp().d(1) >>> 32) & 0xFFFF_FFFFL));
    }
}
