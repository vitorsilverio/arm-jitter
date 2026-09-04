package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdModifiedImmediateOp;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// NEON "1-reg-and-modified-immediate" A32 (task B13.9): `VMOV`/`VMVN`/`VORR`/`VBIC` imediato →
/// `IrOp.NeonModifiedImmediate` → execução direta sobre a palavra `D` (o núcleo COMPARTILHADO
/// {@code AdvSimdModifiedImmediate} só expande o imediato; não há lane a percorrer aqui, ao
/// contrário de `AdvSimdLanes`).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon -march=armv8-a` do devkitARM.
class NeonModifiedImmediateDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonModifiedImmediate",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonModifiedImmediateDecoder(features));
        extensions.add(new NeonShiftImmediateDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 i 1 D 000 imm3 Vd:4 cmode:4 0 Q op 1 imm4` — `imm8 = i:imm3:imm4`.
    private static int enc(int i, int imm3, int cmode, boolean quad, boolean op, int vd, int imm4) {
        return 0xF280_0010
                | (i << 24)
                | ((vd >>> 4) << 22)
                | (imm3 << 16)
                | ((vd & 0xF) << 12)
                | (cmode << 8)
                | (quad ? 1 << 6 : 0)
                | (op ? 1 << 5 : 0)
                | (imm4 & 0xF);
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
        assertEquals(0xf387001f, enc(1, 7, 0b0000, false, false, 0, 0xF));  // vmov.i32 d0,#0xFF
        assertEquals(0xf387021f, enc(1, 7, 0b0010, false, false, 0, 0xF)); // vmov.i32 d0,#0xFF00
        assertEquals(0xf387041f, enc(1, 7, 0b0100, false, false, 0, 0xF)); // vmov.i32 d0,#0xFF0000
        assertEquals(0xf387061f, enc(1, 7, 0b0110, false, false, 0, 0xF)); // vmov.i32 d0,#0xFF000000
        assertEquals(0xf387081f, enc(1, 7, 0b1000, false, false, 0, 0xF)); // vmov.i16 d0,#0xFF
        assertEquals(0xf3870a1f, enc(1, 7, 0b1010, false, false, 0, 0xF)); // vmov.i16 d0,#0xFF00
        assertEquals(0xf3870c1f, enc(1, 7, 0b1100, false, false, 0, 0xF)); // vmov.i32 d0,#0xFFFF
        assertEquals(0xf3870d1f, enc(1, 7, 0b1101, false, false, 0, 0xF)); // vmov.i32 d0,#0xFFFFFF
        assertEquals(0xf3820e1b, enc(1, 2, 0b1110, false, false, 0, 0xB)); // vmov.i8 d0,#0xAB
        assertEquals(0xf3820e3a, enc(1, 2, 0b1110, false, true, 0, 0xA));  // vmov.i64 d0,#0xFF00FF00FF00FF00
        assertEquals(0xf2870f18, enc(0, 7, 0b1111, false, false, 0, 8));   // vmov.f32 d0,#1.5
        assertEquals(0xf387003f, enc(1, 7, 0b0000, false, true, 0, 0xF));  // vmvn.i32 d0,#0xFF
        assertEquals(0xf387031f, enc(1, 7, 0b0011, false, false, 0, 0xF)); // vorr.i32 d0,#0xFF00
        assertEquals(0xf387033f, enc(1, 7, 0b0011, false, true, 0, 0xF));  // vbic.i32 d0,#0xFF00
        assertEquals(0xf387005f, enc(1, 7, 0b0000, true, false, 0, 0xF)); // vmov.i32 q0,#0xFF
        assertEquals(0xf387295f, enc(1, 7, 0b1001, true, false, 2, 0xF)); // vorr.i16 q1,#0xFF
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                enc(1, 7, 0b0000, false, false, 0, 0xF), // vmov.i32
                enc(1, 7, 0b0000, false, true, 0, 0xF),  // vmvn.i32
                enc(1, 7, 0b0011, false, false, 0, 0xF), // vorr.i32
                enc(1, 7, 0b0011, false, true, 0, 0xF),  // vbic.i32
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    @Test
    void featureGateReturnsNullBeforeFrameCheck() {
        int word = enc(1, 7, 0b0000, false, false, 0, 0xF);
        assertNull(new NeonModifiedImmediateDecoder(ArmArchitecture.ARMV7A).tryDecode(word, 0, Condition.AL));
    }

    // ── Decode: cada família → operação + imm64 corretos ──

    @Test
    void everyExpansionGroupDecodesToTheRightImm64() {
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x0000_00FF_0000_00FFL, 0),
                liftedOf(enc(1, 7, 0b0000, false, false, 0, 0xF))); // .i32 #0xFF
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x0000_FF00_0000_FF00L, 0),
                liftedOf(enc(1, 7, 0b0010, false, false, 0, 0xF))); // .i32 #0xFF00
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x00FF_0000_00FF_0000L, 0),
                liftedOf(enc(1, 7, 0b0100, false, false, 0, 0xF))); // .i32 #0xFF0000
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0xFF00_0000_FF00_0000L, 0),
                liftedOf(enc(1, 7, 0b0110, false, false, 0, 0xF))); // .i32 #0xFF000000
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x00FF_00FF_00FF_00FFL, 0),
                liftedOf(enc(1, 7, 0b1000, false, false, 0, 0xF))); // .i16 #0xFF
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0xFF00_FF00_FF00_FF00L, 0),
                liftedOf(enc(1, 7, 0b1010, false, false, 0, 0xF))); // .i16 #0xFF00
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x0000_FFFF_0000_FFFFL, 0),
                liftedOf(enc(1, 7, 0b1100, false, false, 0, 0xF))); // .i32 #0xFFFF (shifted ones, 8)
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x00FF_FFFF_00FF_FFFFL, 0),
                liftedOf(enc(1, 7, 0b1101, false, false, 0, 0xF))); // .i32 #0xFFFFFF (shifted ones, 16)
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0xABAB_ABAB_ABAB_ABABL, 0),
                liftedOf(enc(1, 2, 0b1110, false, false, 0, 0xB))); // .i8 #0xAB
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0xFF00_FF00_FF00_FF00L, 0),
                liftedOf(enc(1, 2, 0b1110, false, true, 0, 0xA))); // .i64 #0xFF00FF00FF00FF00
        assertEquals(new IrOp.NeonModifiedImmediate(AdvSimdModifiedImmediateOp.MOV, false, 0x3FC0_0000_3FC0_0000L, 0),
                liftedOf(enc(0, 7, 0b1111, false, false, 0, 8))); // .f32 #1.5
    }

    @Test
    void classificationPicksTheRightOperation() {
        assertEquals(AdvSimdModifiedImmediateOp.MVN,
                ((IrOp.NeonModifiedImmediate) liftedOf(enc(1, 7, 0b0000, false, true, 0, 0xF))).op()); // vmvn.i32
        assertEquals(AdvSimdModifiedImmediateOp.ORR,
                ((IrOp.NeonModifiedImmediate) liftedOf(enc(1, 7, 0b0011, false, false, 0, 0xF))).op()); // vorr.i32
        assertEquals(AdvSimdModifiedImmediateOp.BIC,
                ((IrOp.NeonModifiedImmediate) liftedOf(enc(1, 7, 0b0011, false, true, 0, 0xF))).op()); // vbic.i32
        // cmode=1110,op=1 é MOV (VMOV.I64), NÃO MVN — única exceção à regra "op=1 inverte".
        assertEquals(AdvSimdModifiedImmediateOp.MOV,
                ((IrOp.NeonModifiedImmediate) liftedOf(enc(1, 2, 0b1110, false, true, 0, 0xA))).op());
    }

    @Test
    void quadFormNamesTheDPairAndStaysWithinIt() {
        IrOp.NeonModifiedImmediate op = (IrOp.NeonModifiedImmediate) liftedOf(enc(1, 7, 0b1001, true, false, 2, 0xF));
        assertEquals(AdvSimdModifiedImmediateOp.ORR, op.op());
        assertEquals(2, op.vd());
        assertEquals(true, op.quad());
    }

    // ── Execução ──

    @Test
    void movOverwritesAndMvnOverwritesWithComplement() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0x1234_5678_9ABC_DEF0L);
        run(core, enc(1, 7, 0b0000, false, false, 0, 0xF)); // vmov.i32 d0,#0xFF
        assertEquals(0x0000_00FF_0000_00FFL, core.vfp().d(0));

        core.vfp().setD(0, 0x1234_5678_9ABC_DEF0L);
        run(core, enc(1, 7, 0b0000, false, true, 0, 0xF)); // vmvn.i32 d0,#0xFF
        assertEquals(~0x0000_00FF_0000_00FFL, core.vfp().d(0));
    }

    @Test
    void orrAndBicReadTheCurrentDestination() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0x1234_0000_5678_0000L);
        run(core, enc(1, 7, 0b0011, false, false, 0, 0xF)); // vorr.i32 d0,#0xFF00
        assertEquals(0x1234_FF00_5678_FF00L, core.vfp().d(0));

        core.vfp().setD(0, 0xFFFF_FFFF_FFFF_FFFFL);
        run(core, enc(1, 7, 0b0011, false, true, 0, 0xF)); // vbic.i32 d0,#0xFF00
        assertEquals(0xFFFF_00FF_FFFF_00FFL, core.vfp().d(0));
    }

    @Test
    void quadFormAffectsOnlyTheNamedDPair() {
        ArmCore core = newCore();
        core.vfp().setD(2, 0x1111_1111_1111_1111L);
        core.vfp().setD(3, 0x2222_2222_2222_2222L);
        core.vfp().setD(4, 0x3333_3333_3333_3333L);
        run(core, enc(1, 7, 0b0000, true, false, 2, 0xF)); // vmov.i32 q1,#0xFF (D2:D3)
        assertEquals(0x0000_00FF_0000_00FFL, core.vfp().d(2));
        assertEquals(0x0000_00FF_0000_00FFL, core.vfp().d(3));
        assertEquals(0x3333_3333_3333_3333L, core.vfp().d(4)); // fora do par, intocado
    }

    // ── UNDEFINED / G8 ──

    @Test
    void reservedCmode1111Op1IsUnimplemented() {
        int word = enc(1, 7, 0b1111, false, true, 0, 0xF); // cmode=1111,op=1 — reservado em AArch32
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(word).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, word).kind());
    }

    @Test
    void quadFormWithOddRegisterIsUndefined() {
        int word = enc(1, 7, 0b0000, true, false, 1, 0xF); // Q form, vd=1 (ímpar)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(word).kind());
    }

    // ── Contrato com a B13.7 ──

    /// `immh==0` (`L=0 && immH=000`) é o buraco que {@link NeonShiftImmediateDecoder} recusa DE
    /// PROPÓSITO. Com os DOIS decoders registrados (ordem: este primeiro), a mesma palavra agora
    /// decodifica como `NeonModifiedImmediate`; e {@link NeonShiftImmediateDecoder} sozinho, na
    /// MESMA palavra, continua devolvendo `null` (contrato preservado, sem editar aquele teste).
    @Test
    void claimsExactlyTheHoleThatNeonShiftImmediateDecoderLeaves() {
        int vimm1r = enc(1, 7, 0b0000, false, false, 0, 0xF); // immh==0
        assertNull(new NeonShiftImmediateDecoder(NEON_FEATURES).tryDecode(vimm1r, 0, Condition.AL));
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(vimm1r).kind());
        assertNotNull(new NeonModifiedImmediateDecoder(NEON_FEATURES).tryDecode(vimm1r, 0, Condition.AL));

        // Fora do buraco (immh != 0, ex.: L=1/immH=000 -> immh=1000, esz=3) este decoder devolve
        // null e o espaço volta a ser da B13.7 (vshr.s64 d0,d1,#64, mesmo golden de B13.7).
        int realShift = 0xF2BF_0091; // vshr.s64 d0,d1,#1 (golden da NeonShiftImmediateDecoderTest)
        assertNull(new NeonModifiedImmediateDecoder(NEON_FEATURES).tryDecode(realShift, 0, Condition.AL));
    }
}
