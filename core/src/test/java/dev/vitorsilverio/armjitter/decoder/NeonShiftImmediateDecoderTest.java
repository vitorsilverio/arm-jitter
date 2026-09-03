package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
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
    void b138TerritoryStaysUnimplemented() {
        // estreitamento (opc=1000/1001), alargamento (opc=1010), VCVT fixo↔float (opc=1100-1111)
        for (int opc : new int[] {0b1000, 0b1001, 0b1010, 0b1100, 0b1101, 0b1110, 0b1111}) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 1, 7, opc, false, 0, 1)).kind(),
                    "opc=" + Integer.toBinaryString(opc) + " U=0");
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(1, 1, 7, opc, false, 0, 1)).kind(),
                    "opc=" + Integer.toBinaryString(opc) + " U=1");
            // e a mesma palavra sem a feature continua UNIMPLEMENTED (zero-diff)
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(ArmArchitecture.ARMV7A, enc(0, 1, 7, opc, false, 0, 1)).kind());
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
}
