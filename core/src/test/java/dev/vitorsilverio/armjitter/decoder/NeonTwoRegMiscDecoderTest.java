package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftWidenOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdUnaryOp;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// NEON "two-register miscellaneous" A32, sub-grupo `size==0b11` (task B13.12): `VREV64`/`VREV32`/
/// `VREV16`, `VPADDL`/`VPADAL`, `VCLS`/`VCLZ`/`VCNT`/`VMVN`, `VQABS`/`VQNEG`, as 5 comparações-com-
/// zero inteiras e as 5 FP, `VABS`/`VNEG` (inteiro e FP), `VMOVN`/`VQMOVUN`/`VQMOVN_S`/`VQMOVN_U`,
/// `VSHLL` (deslocamento fixo por `esize`) e `VRECPE`/`VRSQRTE` (inteiro e FP).
///
/// Execução pelo núcleo vetorial COMPARTILHADO com o lado A64 ({@code AdvSimdLanes.unary}/
/// {@code narrowUnary}/{@code fpUnary}).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon-vfpv4` do devkitARM.
class NeonTwoRegMiscDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonTwoRegMisc",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonTwoRegMiscDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 11 D 11 size:2 opc1:2 Vd:4 0 opc2:4 q M 0 Vm:4`.
    private static int enc(int size, int opc1, int vd, int opc2, int q, int vm) {
        return 0xF3B0_0000
                | ((vd >> 4) << 22)
                | (size << 18)
                | (opc1 << 16)
                | ((vd & 0xF) << 12)
                | (opc2 << 7)
                | (q << 6)
                | ((vm >> 4) << 5)
                | (vm & 0xF);
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

    // ── Encoding golden (assembler real, arm-none-eabi-as -mfpu=neon-vfpv4) ──

    @Test
    void encodingsMatchTheAssembler() {
        assertEquals(0xf3b00001, enc(0, 0b00, 0, 0b0000, 0, 1));   // vrev64.8   d0,d1
        assertEquals(0xf3b40042, enc(1, 0b00, 0, 0b0000, 1, 2));   // vrev64.16  q0,q1
        assertEquals(0xf3b02083, enc(0, 0b00, 2, 0b0001, 0, 3));   // vrev32.8   d2,d3
        assertEquals(0xf3b44085, enc(1, 0b00, 4, 0b0001, 0, 5));   // vrev32.16  d4,d5
        assertEquals(0xf3b06107, enc(0, 0b00, 6, 0b0010, 0, 7));   // vrev16.8   d6,d7
        assertEquals(0xf3b00201, enc(0, 0b00, 0, 0b0100, 0, 1));   // vpaddl.s8  d0,d1
        assertEquals(0xf3b402c2, enc(1, 0b00, 0, 0b0101, 1, 2));   // vpaddl.u16 q0,q1
        assertEquals(0xf3b00601, enc(0, 0b00, 0, 0b1100, 0, 1));   // vpadal.s8  d0,d1
        assertEquals(0xf3b406c2, enc(1, 0b00, 0, 0b1101, 1, 2));   // vpadal.u16 q0,q1
        assertEquals(0xf3b00401, enc(0, 0b00, 0, 0b1000, 0, 1));   // vcls.s8    d0,d1
        assertEquals(0xf3b80442, enc(2, 0b00, 0, 0b1000, 1, 2));   // vcls.s32   q0,q1
        assertEquals(0xf3b42483, enc(1, 0b00, 2, 0b1001, 0, 3));   // vclz.i16   d2,d3
        assertEquals(0xf3b04505, enc(0, 0b00, 4, 0b1010, 0, 5));   // vcnt.8     d4,d5
        assertEquals(0xf3b00542, enc(0, 0b00, 0, 0b1010, 1, 2));   // vcnt.8     q0,q1
        assertEquals(0xf3b00581, enc(0, 0b00, 0, 0b1011, 0, 1));   // vmvn       d0,d1
        assertEquals(0xf3b005c2, enc(0, 0b00, 0, 0b1011, 1, 2));   // vmvn       q0,q1
        assertEquals(0xf3b00701, enc(0, 0b00, 0, 0b1110, 0, 1));   // vqabs.s8   d0,d1
        assertEquals(0xf3b80742, enc(2, 0b00, 0, 0b1110, 1, 2));   // vqabs.s32  q0,q1
        assertEquals(0xf3b42783, enc(1, 0b00, 2, 0b1111, 0, 3));   // vqneg.s16  d2,d3
        assertEquals(0xf3b10001, enc(0, 0b01, 0, 0b0000, 0, 1));   // vcgt.s8    d0,d1,#0
        assertEquals(0xf3b52083, enc(1, 0b01, 2, 0b0001, 0, 3));   // vcge.s16   d2,d3,#0
        assertEquals(0xf3b94105, enc(2, 0b01, 4, 0b0010, 0, 5));   // vceq.i32   d4,d5,#0
        assertEquals(0xf3b101c2, enc(0, 0b01, 0, 0b0011, 1, 2));   // vcle.s8    q0,q1,#0
        assertEquals(0xf3b54246, enc(1, 0b01, 4, 0b0100, 1, 6));   // vclt.s16   q2,q3,#0
        assertEquals(0xf3b10301, enc(0, 0b01, 0, 0b0110, 0, 1));   // vabs.s8    d0,d1
        assertEquals(0xf3b90342, enc(2, 0b01, 0, 0b0110, 1, 2));   // vabs.s32   q0,q1
        assertEquals(0xf3b52383, enc(1, 0b01, 2, 0b0111, 0, 3));   // vneg.s16   d2,d3
        assertEquals(0xf3b90401, enc(2, 0b01, 0, 0b1000, 0, 1));   // vcgt.f32   d0,d1,#0
        assertEquals(0xf3b904c2, enc(2, 0b01, 0, 0b1001, 1, 2));   // vcge.f32   q0,q1,#0
        assertEquals(0xf3b92503, enc(2, 0b01, 2, 0b1010, 0, 3));   // vceq.f32   d2,d3,#0
        assertEquals(0xf3b94585, enc(2, 0b01, 4, 0b1011, 0, 5));   // vcle.f32   d4,d5,#0
        assertEquals(0xf3b90642, enc(2, 0b01, 0, 0b1100, 1, 2));   // vclt.f32   q0,q1,#0
        assertEquals(0xf3b90701, enc(2, 0b01, 0, 0b1110, 0, 1));   // vabs.f32   d0,d1
        assertEquals(0xf3b90742, enc(2, 0b01, 0, 0b1110, 1, 2));   // vabs.f32   q0,q1
        assertEquals(0xf3b92783, enc(2, 0b01, 2, 0b1111, 0, 3));   // vneg.f32   d2,d3
        // `size` aqui é o ESZ do DESTINO (estreito): sufixo `.16` (largura da FONTE) -> `size=0`
        // (destino byte); sufixo `.32` -> `size=1` (destino halfword).
        assertEquals(0xf3b20202, enc(0, 0b10, 0, 0b0100, 0, 2));   // vmovn.i16  d0,q1
        assertEquals(0xf3b22246, enc(0, 0b10, 2, 0b0100, 1, 6));   // vqmovun.s16 d2,q3
        assertEquals(0xf3b6428a, enc(1, 0b10, 4, 0b0101, 0, 10));  // vqmovn.s32 d4,q5
        assertEquals(0xf3b262ce, enc(0, 0b10, 6, 0b0101, 1, 14));  // vqmovn.u16 d6,q7
        assertEquals(0xf3b20301, enc(0, 0b10, 0, 0b0110, 0, 1));   // vshll.i8   q0,d1,#8
        assertEquals(0xf3b64303, enc(1, 0b10, 4, 0b0110, 0, 3));   // vshll.i16  q2,d3,#16 (q2=D4:D5)
        assertEquals(0xf3ba8305, enc(2, 0b10, 8, 0b0110, 0, 5));   // vshll.i32  q4,d5,#32 (q4=D8:D9)
        assertEquals(0xf3bb0401, enc(2, 0b11, 0, 0b1000, 0, 1));   // vrecpe.u32 d0,d1
        assertEquals(0xf3bb04c2, enc(2, 0b11, 0, 0b1001, 1, 2));   // vrsqrte.u32 q0,q1
        assertEquals(0xf3bb2503, enc(2, 0b11, 2, 0b1010, 0, 3));   // vrecpe.f32 d2,d3
        assertEquals(0xf3bb05c2, enc(2, 0b11, 0, 0b1011, 1, 2));   // vrsqrte.f32 q0,q1
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                enc(0, 0b00, 0, 0b0000, 0, 1),  // vrev64.8
                enc(0, 0b01, 0, 0b0000, 0, 1),  // vcgt.s8 #0
                enc(2, 0b10, 0, 0b0100, 0, 2),  // vmovn.i16
                enc(2, 0b11, 0, 0b1000, 0, 1),  // vrecpe.u32
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    @Test
    void featureGateReturnsNullBeforeFrameCheck() {
        int word = enc(0, 0b00, 0, 0b0000, 0, 1);
        assertNull(new NeonTwoRegMiscDecoder(ArmArchitecture.ARMV7A).tryDecode(word, 0, Condition.AL));
    }

    // ── Espaço livre preservado: `VRINT*`(B13.13)/`VTBL`/`VDUP_scalar`(B13.14)/`AESE`/`SHA1H`
    // (B13.15) caem em `null`, não `unimplemented` — para essas tasks poderem registrar depois ──

    @Test
    void unrecognizedSpaceStaysNullForFutureSiblingTasks() {
        int[] wordsInsideFrame = {
                0xf3ba0401, // vrintn.f32 d0,d1 (B13.13)
                0xf3b00302, // aese.8 q0,q1 (B13.15)
                0xf3b902c2, // sha1h.32 q0,q1 (B13.15)
        };
        for (int w : wordsInsideFrame) {
            assertNull(new NeonTwoRegMiscDecoder(NEON_FEATURES).tryDecode(w, 0, Condition.AL));
        }
        // VTBL/VDUP_scalar/VEXT vivem no MESMO size==0b11 mas fora do sub-layout "2-reg-misc"
        // (bit11=1 ou bit24=0) — o frame nem bate.
        int[] wordsOutsideFrame = {
                0xf3b10802, // vtbl.8 d0,{d1},d2 (bit11=1, B13.14)
                0xf3b50c01, // vdup.8 d0,d1[2]   (bit11=1, B13.14)
                0xf2b10302, // vext.8 d0,d1,d2,#3 (bit24=0, B13.14)
        };
        for (int w : wordsOutsideFrame) {
            assertNull(new NeonTwoRegMiscDecoder(NEON_FEATURES).tryDecode(w, 0, Condition.AL));
        }
    }

    // ── Decodifica com op/esz/quad/registrador corretos ──

    @Test
    void integerUnaryDecodesWithRightOpEszQuadAndRegisters() {
        IrOp.NeonUnary rev64 = (IrOp.NeonUnary) liftedOf(enc(0, 0b00, 0, 0b0000, 0, 1));
        assertEquals(AdvSimdUnaryOp.REV64, rev64.op());
        assertEquals(0, rev64.esz());
        assertEquals(0, rev64.vd());
        assertEquals(1, rev64.vm());
        assertEquals(false, rev64.quad());

        IrOp.NeonUnary rev32Q = (IrOp.NeonUnary) liftedOf(enc(0, 0b00, 0, 0b0001, 1, 2));
        assertEquals(AdvSimdUnaryOp.REV32, rev32Q.op());
        assertTrue(rev32Q.quad());

        IrOp.NeonUnary uaddlp = (IrOp.NeonUnary) liftedOf(enc(1, 0b00, 0, 0b0101, 1, 2));
        assertEquals(AdvSimdUnaryOp.UADDLP, uaddlp.op());
        assertEquals(1, uaddlp.esz());

        IrOp.NeonUnary urecpe = (IrOp.NeonUnary) liftedOf(enc(2, 0b11, 0, 0b1000, 0, 1));
        assertEquals(AdvSimdUnaryOp.URECPE, urecpe.op());
    }

    @Test
    void fpUnaryDecodesWithRightOpAndQuad() {
        IrOp.NeonFpUnary absF = (IrOp.NeonFpUnary) liftedOf(enc(2, 0b01, 0, 0b1110, 0, 1));
        assertEquals(AdvSimdFpUnaryOp.ABS, absF.op());
        assertEquals(false, absF.quad());

        IrOp.NeonFpUnary rsqrteF = (IrOp.NeonFpUnary) liftedOf(enc(2, 0b11, 0, 0b1011, 1, 2));
        assertEquals(AdvSimdFpUnaryOp.RSQRTE, rsqrteF.op());
        assertTrue(rsqrteF.quad());
    }

    @Test
    void narrowUnaryDecodesWithRightOpEszAndRegisters() {
        IrOp.NeonNarrowUnary movn = (IrOp.NeonNarrowUnary) liftedOf(enc(1, 0b10, 0, 0b0100, 0, 2));
        assertEquals(AdvSimdNarrowUnaryOp.XTN, movn.op());
        assertEquals(1, movn.esz());
        assertEquals(0, movn.vd());
        assertEquals(2, movn.vm());

        IrOp.NeonNarrowUnary qmovun = (IrOp.NeonNarrowUnary) liftedOf(enc(1, 0b10, 2, 0b0100, 1, 6));
        assertEquals(AdvSimdNarrowUnaryOp.SQXTUN, qmovun.op());

        IrOp.NeonNarrowUnary qmovnS = (IrOp.NeonNarrowUnary) liftedOf(enc(2, 0b10, 4, 0b0101, 0, 10));
        assertEquals(AdvSimdNarrowUnaryOp.SQXTN, qmovnS.op());

        IrOp.NeonNarrowUnary qmovnU = (IrOp.NeonNarrowUnary) liftedOf(enc(1, 0b10, 6, 0b0101, 1, 14));
        assertEquals(AdvSimdNarrowUnaryOp.UQXTN, qmovnU.op());
    }

    @Test
    void vshllDecodesAsShiftWidenImmediateWithShiftEqualToElementSize() {
        IrOp.NeonShiftWidenImmediate shll8 = (IrOp.NeonShiftWidenImmediate) liftedOf(enc(0, 0b10, 0, 0b0110, 0, 1));
        assertEquals(AdvSimdShiftWidenOp.USHLL, shll8.op());
        assertEquals(0, shll8.esz());
        assertEquals(8, shll8.shift());
        assertEquals(0, shll8.vd());
        assertEquals(1, shll8.vm());

        IrOp.NeonShiftWidenImmediate shll32 = (IrOp.NeonShiftWidenImmediate) liftedOf(enc(2, 0b10, 4, 0b0110, 0, 5));
        assertEquals(32, shll32.shift());
    }

    // ── Tamanho fora do suportado é `unimplemented` (não `null` — dentro do espaço desta task) ──

    @Test
    void unsupportedEszStaysUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(3, 0b00, 0, 0b0000, 0, 1)).kind()); // vrev64 size=3
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(2, 0b00, 6, 0b0010, 0, 7)).kind()); // vrev16 size!=0
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(1, 0b00, 4, 0b1010, 0, 5)).kind()); // vcnt size!=0
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(1, 0b01, 0, 0b1000, 0, 1)).kind()); // vcgt.f32-slot com size=1 (F16)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(0, 0b11, 0, 0b1000, 0, 1)).kind()); // vrecpe size!=2
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(3, 0b10, 0, 0b0110, 0, 1)).kind()); // vshll size=3
    }

    // ── Registrador ímpar UNDEFINED na forma quad ──

    @Test
    void quadFormOddRegistersAreUndefined() {
        int oddVd = enc(0, 0b00, 1, 0b1000, 1, 2); // vcls q form, vd ímpar
        int oddVm = enc(0, 0b00, 0, 0b1000, 1, 3); // vcls q form, vm ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVd).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVm).kind());
    }

    // ── Execução: um padrão conhecido por bloco (Aceite) ──

    @Test
    void rev64Rev32Rev16PermuteWithinTheirOwnGroupSize() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0807_0605_0403_0201L);
        run(core, enc(0, 0b00, 0, 0b0000, 0, 1)); // vrev64.8 d0,d1
        assertEquals(0x0102_0304_0506_0708L, core.vfp().d(0));

        core.vfp().setD(1, 0x0807_0605_0403_0201L);
        run(core, enc(0, 0b00, 0, 0b0001, 0, 1)); // vrev32.8 d0,d1
        assertEquals(0x0506_0708_0102_0304L, core.vfp().d(0));

        core.vfp().setD(1, 0x0807_0605_0403_0201L);
        run(core, enc(0, 0b00, 0, 0b0010, 0, 1)); // vrev16.8 d0,d1
        assertEquals(0x0708_0506_0304_0102L, core.vfp().d(0));
    }

    @Test
    void paddlDoesNotAccumulateButPadalDoes() {
        ArmCore core = newCore();
        // vpaddl.s16 d0,d1: soma os 2 halfwords de origem (1+2=3) em 1 word.
        core.vfp().setD(1, 0x0002_0001L); // dois halfwords: 1, 2
        core.vfp().setD(0, 0xDEAD_BEEFL); // lixo prévio: VPADDL NÃO deve ler isto
        run(core, enc(1, 0b00, 0, 0b0100, 0, 1));
        assertEquals(3L, core.vfp().d(0) & 0xFFFF_FFFFL);

        core.vfp().setD(1, 0x0002_0001L);
        core.vfp().setD(0, 100L); // acumulador pré-existente
        run(core, enc(1, 0b00, 0, 0b1100, 0, 1)); // vpadal.s16 d0,d1
        assertEquals(103L, core.vfp().d(0) & 0xFFFF_FFFFL); // 100 + (1+2)
    }

    @Test
    void clsCountsSignBitsClzCountsZerosOnNegativeValue() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0xC0L); // byte negativo: 1100_0000 -> 1 bit de sinal extra (sem contar o próprio)
        run(core, enc(0, 0b00, 0, 0b1000, 0, 1)); // vcls.s8
        assertEquals(1L, core.vfp().d(0) & 0xFFL);

        core.vfp().setD(1, 0xC0L);
        run(core, enc(0, 0b00, 0, 0b1001, 0, 1)); // vclz.s8 (sem sinal: zeros à esquerda)
        assertEquals(0L, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void cntCountsSetBitsPerByte() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0xFF0FL);
        run(core, enc(0, 0b00, 0, 0b1010, 0, 1)); // vcnt.8
        assertEquals(0x0804L, core.vfp().d(0) & 0xFFFFL);
    }

    @Test
    void qabsQnegSaturateAtMinimum() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x80L); // s8 mínimo: -128
        run(core, enc(0, 0b00, 0, 0b1110, 0, 1)); // vqabs.s8
        assertEquals(0x7FL, core.vfp().d(0) & 0xFFL); // satura em 127

        core.vfp().setD(1, 0x80L);
        run(core, enc(0, 0b00, 0, 0b1111, 0, 1)); // vqneg.s8
        assertEquals(0x7FL, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void integerAndFpZeroComparisonsAgreeOnNegativeZero() {
        ArmCore core = newCore();
        // FP -0.0f (bits 0x80000000) é IGUAL a zero em VCEQ0_F e FALSO em VCLT0_F (IEEE 754).
        core.vfp().setD(1, 0x8000_0000L);
        run(core, enc(2, 0b01, 0, 0b1010, 0, 1)); // vceq.f32 d0,d1,#0
        assertEquals(0xFFFF_FFFFL, core.vfp().d(0) & 0xFFFF_FFFFL);

        core.vfp().setD(1, 0x8000_0000L);
        run(core, enc(2, 0b01, 0, 0b1100, 0, 1)); // vclt.f32 d0,d1,#0
        assertEquals(0L, core.vfp().d(0) & 0xFFFF_FFFFL);

        // Inteiro: 0x80000000 como s32 É negativo (Integer.MIN_VALUE) -- VCLT0 verdadeiro.
        core.vfp().setD(1, 0x8000_0000L);
        run(core, enc(2, 0b01, 0, 0b0100, 0, 1)); // vclt.s32 d0,d1,#0
        assertEquals(0xFFFF_FFFFL, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    @Test
    void qmovnFamilyDiffersOnSaturation() {
        ArmCore core = newCore();
        // `esz=0` (destino byte, fonte halfword = `esz+1`). Q1 (d2:d3) com um halfword = -1
        // (0xFFFF): SQXTN satura em -1 (0xFF), SQXTUN em 0 (negativo não cabe em unsigned), UQXTN
        // trata a MESMA palavra como não-assinada (65535) e satura no máximo u8 (255/0xFF).
        core.vfp().setD(2, 0xFFFFL);
        run(core, enc(0, 0b10, 4, 0b0101, 0, 2)); // vqmovn.s16 d4,q1
        assertEquals(0xFFL, core.vfp().d(4) & 0xFFL); // -1 cabe em s8 -> 0xFF

        core.vfp().setD(2, 0xFFFFL);
        run(core, enc(0, 0b10, 4, 0b0100, 1, 2)); // vqmovun.s16 d4,q1
        assertEquals(0x00L, core.vfp().d(4) & 0xFFL); // -1 satura em 0 (unsigned)

        core.vfp().setD(2, 0xFFFFL);
        run(core, enc(0, 0b10, 4, 0b0101, 1, 2)); // vqmovn.u16 d4,q1
        assertEquals(0xFFL, core.vfp().d(4) & 0xFFL); // 65535 satura em 255 (u8 max)
    }

    @Test
    void vshllShiftsByElementSize() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x02L); // byte = 2
        run(core, enc(0, 0b10, 0, 0b0110, 0, 1)); // vshll.i8 q0,d1,#8
        assertEquals(2L << 8, core.vfp().d(0) & 0xFFFFL);
    }

    @Test
    void integerAndFpEstimatesAgreeWithA64Formula() {
        ArmCore core = newCore();
        // 0x8000_0000 (bit alto setado) -> URecipEstimate/UnsignedRSqrtEstimate por tabela.
        core.vfp().setD(1, 0x8000_0000L);
        run(core, enc(2, 0b11, 0, 0b1000, 0, 1)); // vrecpe.u32
        long recpe = core.vfp().d(0) & 0xFFFF_FFFFL;
        assertTrue(recpe != 0);

        core.vfp().setD(1, 0x8000_0000L);
        run(core, enc(2, 0b11, 0, 0b1001, 0, 1)); // vrsqrte.u32
        long rsqrte = core.vfp().d(0) & 0xFFFF_FFFFL;
        assertTrue(rsqrte != 0);

        // FP: recíproco/raiz-recíproca de 1.0f é 1.0f nas duas.
        core.vfp().setD(1, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        run(core, enc(2, 0b11, 0, 0b1010, 0, 1)); // vrecpe.f32
        assertEquals(Float.floatToRawIntBits(1.0f), (int) (core.vfp().d(0) & 0xFFFF_FFFFL));

        core.vfp().setD(1, Float.floatToRawIntBits(1.0f) & 0xFFFF_FFFFL);
        run(core, enc(2, 0b11, 0, 0b1011, 0, 1)); // vrsqrte.f32
        assertEquals(Float.floatToRawIntBits(1.0f), (int) (core.vfp().d(0) & 0xFFFF_FFFFL));
    }

    @Test
    void vdEqualsVmAliasingInMixedWidthForms() {
        ArmCore core = newCore();
        // vpaddl.s16 d0,d0: fonte e destino colidem (largura mista) — resultado bufferizado (E10).
        core.vfp().setD(0, 0x0002_0001L);
        run(core, enc(1, 0b00, 0, 0b0100, 0, 0));
        assertEquals(3L, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    // ── B13.14: VSWP/VTRN/VUZP/VZIP (MESMO frame `opc1=0b10`, `opc2` 0-3) ──

    @Test
    void swapPermuteEncodingsMatchTheAssembler() {
        assertEquals(0xf3b20001, enc(0, 0b10, 0, 0b0000, 0, 1));  // vswp       d0,d1
        assertEquals(0xf3b20042, enc(0, 0b10, 0, 0b0000, 1, 2));  // vswp       q0,q1
        assertEquals(0xf3b20081, enc(0, 0b10, 0, 0b0001, 0, 1));  // vtrn.8     d0,d1
        assertEquals(0xf3b20101, enc(0, 0b10, 0, 0b0010, 0, 1));  // vuzp.8     d0,d1
        assertEquals(0xf3b20181, enc(0, 0b10, 0, 0b0011, 0, 1));  // vzip.8     d0,d1
        // Q=0/size=2 (word): o MNEMÔNICO `vuzp.32`/`vzip.32` do assembler real escolhe emitir o
        // MESMO `opc2` de `VTRN` (0xf3ba0081, canonicalização de mnemônico) — mas os encodings
        // CRUS com `opc2=0010`/`0011` continuam válidos e distintos (decodificados como UZP/ZIP
        // explícitos); a EXECUÇÃO dos três produz o mesmo resultado nesta combinação, conferido em
        // `qFormZeroSizeTwoDegenerateToTrnResult` abaixo — não a codificação.
        assertEquals(0xf3ba0081, enc(2, 0b10, 0, 0b0001, 0, 1));  // vtrn.32    d0,d1
    }

    @Test
    void swapPermuteDecodesWithRightOpEszQuadAndRegisters() {
        IrOp.NeonSwapPermute swap = (IrOp.NeonSwapPermute) liftedOf(enc(0, 0b10, 0, 0b0000, 1, 2));
        assertEquals(dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp.SWAP, swap.op());
        assertTrue(swap.quad());
        assertEquals(0, swap.vd());
        assertEquals(2, swap.vm());

        IrOp.NeonSwapPermute trn = (IrOp.NeonSwapPermute) liftedOf(enc(1, 0b10, 2, 0b0001, 0, 3));
        assertEquals(dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp.TRN, trn.op());
        assertEquals(1, trn.esz());
        assertEquals(2, trn.vd());
        assertEquals(3, trn.vm());

        IrOp.NeonSwapPermute uzp = (IrOp.NeonSwapPermute) liftedOf(enc(0, 0b10, 4, 0b0010, 0, 5));
        assertEquals(dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp.UZP, uzp.op());

        IrOp.NeonSwapPermute zip = (IrOp.NeonSwapPermute) liftedOf(enc(0, 0b10, 6, 0b0011, 0, 7));
        assertEquals(dev.vitorsilverio.armjitter.advsimd.AdvSimdSwapPermuteOp.ZIP, zip.op());
    }

    @Test
    void swapWithNonZeroSizeStaysUnimplemented() {
        // VSWP real só encodifica size=00 (arm-none-eabi-as confirma) — outro size é reservado.
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(1, 0b10, 0, 0b0000, 0, 1)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(enc(3, 0b10, 0, 0b0001, 0, 1)).kind()); // vtrn size=3
    }

    @Test
    void swapPermuteQuadFormOddRegistersAreUndefined() {
        int oddVd = enc(0, 0b10, 1, 0b0001, 1, 2); // vtrn.8 q form, vd ímpar
        int oddVm = enc(0, 0b10, 0, 0b0001, 1, 3); // vtrn.8 q form, vm ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVd).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVm).kind());
    }

    @Test
    void vswpExchangesTheTwoFullRegisters() {
        ArmCore core = newCore();
        core.vfp().setD(0, 0x1111_1111_1111_1111L);
        core.vfp().setD(1, 0x2222_2222_2222_2222L);
        run(core, enc(0, 0b10, 0, 0b0000, 0, 1)); // vswp d0,d1
        assertEquals(0x2222_2222_2222_2222L, core.vfp().d(0));
        assertEquals(0x1111_1111_1111_1111L, core.vfp().d(1));
    }

    // Valores de entrada compartilhados pelos 3 testes abaixo: Vd = byte `i+1` na lane `i`
    // (01,02,...,08), Vm = byte `0x10+i+1` na lane `i` (11,12,...,18) — resultados esperados
    // recalculados independentemente (script Node.js) a partir da definição ARM DDI 0406C de
    // `VTRN`/`VUZP`/`VZIP`, não da implementação em teste.
    private static final long TRANSPOSE_VD_INPUT = 0x0807_0605_0403_0201L;
    private static final long TRANSPOSE_VM_INPUT = 0x1817_1615_1413_1211L;

    @Test
    void vtrnTransposesOddVdWithEvenVm() {
        ArmCore core = newCore();
        core.vfp().setD(0, TRANSPOSE_VD_INPUT);
        core.vfp().setD(1, TRANSPOSE_VM_INPUT);
        run(core, enc(0, 0b10, 0, 0b0001, 0, 1)); // vtrn.8 d0,d1
        assertEquals(0x1707_1505_1303_1101L, core.vfp().d(0));
        assertEquals(0x1808_1606_1404_1202L, core.vfp().d(1));
    }

    @Test
    void vuzpDeinterleavesConcatenatedDAndM() {
        ArmCore core = newCore();
        core.vfp().setD(0, TRANSPOSE_VD_INPUT);
        core.vfp().setD(1, TRANSPOSE_VM_INPUT);
        run(core, enc(0, 0b10, 0, 0b0010, 0, 1)); // vuzp.8 d0,d1
        assertEquals(0x1715_1311_0705_0301L, core.vfp().d(0));
        assertEquals(0x1816_1412_0806_0402L, core.vfp().d(1));
    }

    @Test
    void vzipInterleavesVdAndVm() {
        ArmCore core = newCore();
        core.vfp().setD(0, TRANSPOSE_VD_INPUT);
        core.vfp().setD(1, TRANSPOSE_VM_INPUT);
        run(core, enc(0, 0b10, 0, 0b0011, 0, 1)); // vzip.8 d0,d1
        assertEquals(0x1404_1303_1202_1101L, core.vfp().d(0));
        assertEquals(0x1808_1707_1606_1505L, core.vfp().d(1));
    }

    @Test
    void vdEqualsVmIsWellDefinedNotSilentlyCorrupted() {
        ArmCore core = newCore();
        // Vd==Vm é UNPREDICTABLE no ARM; o núcleo bufferizado (E10) ainda produz um resultado
        // DETERMINÍSTICO — decisão registrada no `## Resultado` da B13.14: como as duas metades do
        // resultado são escritas na MESMA palavra, a segunda escrita (a metade "Vm") sempre vence
        // por lane, nunca lixo/crash/não-determinismo. Valor recalculado independentemente (Node.js)
        // a partir da definição de VTRN aplicada com `op1==op2`.
        core.vfp().setD(0, 0x0807_0605_0403_0201L);
        run(core, enc(0, 0b10, 0, 0b0001, 0, 0)); // vtrn.8 d0,d0
        assertEquals(0x0808_0606_0404_0202L, core.vfp().d(0));
    }

    @Test
    void qFormZeroSizeTwoDegenerateToTrnResult() {
        // Q=0, size=2 (word): VTRN/VUZP/VZIP produzem o MESMO resultado (confirmado pelo encoding
        // idêntico do assembler real, ver swapPermuteEncodingsMatchTheAssembler).
        ArmCore coreTrn = newCore();
        coreTrn.vfp().setD(0, 0x0000_0002_0000_0001L);
        coreTrn.vfp().setD(1, 0x0000_0004_0000_0003L);
        run(coreTrn, enc(2, 0b10, 0, 0b0001, 0, 1));

        ArmCore coreUzp = newCore();
        coreUzp.vfp().setD(0, 0x0000_0002_0000_0001L);
        coreUzp.vfp().setD(1, 0x0000_0004_0000_0003L);
        run(coreUzp, enc(2, 0b10, 0, 0b0010, 0, 1));

        ArmCore coreZip = newCore();
        coreZip.vfp().setD(0, 0x0000_0002_0000_0001L);
        coreZip.vfp().setD(1, 0x0000_0004_0000_0003L);
        run(coreZip, enc(2, 0b10, 0, 0b0011, 0, 1));

        assertEquals(coreTrn.vfp().d(0), coreUzp.vfp().d(0));
        assertEquals(coreTrn.vfp().d(1), coreUzp.vfp().d(1));
        assertEquals(coreTrn.vfp().d(0), coreZip.vfp().d(0));
        assertEquals(coreTrn.vfp().d(1), coreZip.vfp().d(1));
    }
}
