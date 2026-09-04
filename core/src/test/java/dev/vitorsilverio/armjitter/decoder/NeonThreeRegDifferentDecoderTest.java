package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdWideningOp;
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

/// NEON "three-reg-different-lengths" A32 (task B13.10): `VADDL`/`VSUBL`/`VABAL`/`VABDL`/`VMLAL`/
/// `VMLSL`/`VMULL`/`VQDMLAL`/`VQDMLSL`/`VQDMULL`/`VMULL.P8` (forma Long), `VADDW`/`VSUBW` (forma
/// Wide), `VADDHN`/`VRADDHN`/`VSUBHN`/`VRSUBHN` (forma Narrow) → `IrOp.NeonWidening`/`NeonWide`/
/// `NeonNarrow` → execução pelo núcleo vetorial COMPARTILHADO com o lado A64
/// ({@code AdvSimdLanes.widening}/`wide`/`narrow`).
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon -march=armv8-a` do devkitARM.
class NeonThreeRegDifferentDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonThreeRegDifferent",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonThreeRegDifferentDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
    }

    /// `1111 001 U 1 D size:2 Vn:4 Vd:4 opc:4 N 0 M 0 Vm:4`.
    private static int enc(int u, int size, int vn, int vd, int opc, int vm) {
        return 0xF280_0000
                | (u << 24)
                | ((vd >> 4) << 22)
                | (size << 20)
                | ((vn & 0xF) << 16)
                | ((vd & 0xF) << 12)
                | (opc << 8)
                | ((vn >> 4) << 7)
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

    // ── Encoding golden (assembler real) ──

    @Test
    void b1310EncodingsMatchTheAssembler() {
        assertEquals(0xf2800001, enc(0, 0, 0, 0, 0b0000, 1));  // vaddl.s8   q0,d0,d1
        assertEquals(0xf3934004, enc(1, 1, 3, 4, 0b0000, 4));  // vaddl.u16  q2,d3,d4
        assertEquals(0xf2820102, enc(0, 0, 2, 0, 0b0001, 2));  // vaddw.s8   q0,q1,d2
        assertEquals(0xf3a20102, enc(1, 2, 2, 0, 0b0001, 2));  // vaddw.u32  q0,q1,d2
        assertEquals(0xf2910202, enc(0, 1, 1, 0, 0b0010, 2));  // vsubl.s16  q0,d1,d2
        assertEquals(0xf3a10202, enc(1, 2, 1, 0, 0b0010, 2));  // vsubl.u32  q0,d1,d2
        assertEquals(0xf2820302, enc(0, 0, 2, 0, 0b0011, 2));  // vsubw.s8   q0,q1,d2
        assertEquals(0xf3920302, enc(1, 1, 2, 0, 0b0011, 2));  // vsubw.u16  q0,q1,d2
        assertEquals(0xf2820404, enc(0, 0, 2, 0, 0b0100, 4));  // vaddhn.i16 d0,q1,q2
        assertEquals(0xf3920404, enc(1, 1, 2, 0, 0b0100, 4));  // vraddhn.i32 d0,q1,q2
        assertEquals(0xf2810502, enc(0, 0, 1, 0, 0b0101, 2));  // vabal.s8   q0,d1,d2
        assertEquals(0xf3910502, enc(1, 1, 1, 0, 0b0101, 2));  // vabal.u16  q0,d1,d2
        assertEquals(0xf2820604, enc(0, 0, 2, 0, 0b0110, 4));  // vsubhn.i16 d0,q1,q2
        assertEquals(0xf3920604, enc(1, 1, 2, 0, 0b0110, 4));  // vrsubhn.i32 d0,q1,q2
        assertEquals(0xf2810702, enc(0, 0, 1, 0, 0b0111, 2));  // vabdl.s8   q0,d1,d2
        assertEquals(0xf3a10702, enc(1, 2, 1, 0, 0b0111, 2));  // vabdl.u32  q0,d1,d2
        assertEquals(0xf2810802, enc(0, 0, 1, 0, 0b1000, 2));  // vmlal.s8   q0,d1,d2
        assertEquals(0xf3910802, enc(1, 1, 1, 0, 0b1000, 2));  // vmlal.u16  q0,d1,d2
        assertEquals(0xf2910902, enc(0, 1, 1, 0, 0b1001, 2));  // vqdmlal.s16 q0,d1,d2
        assertEquals(0xf2a10a02, enc(0, 2, 1, 0, 0b1010, 2));  // vmlsl.s32  q0,d1,d2
        assertEquals(0xf3810a02, enc(1, 0, 1, 0, 0b1010, 2));  // vmlsl.u8   q0,d1,d2
        assertEquals(0xf2a10b02, enc(0, 2, 1, 0, 0b1011, 2));  // vqdmlsl.s32 q0,d1,d2
        assertEquals(0xf2810c02, enc(0, 0, 1, 0, 0b1100, 2));  // vmull.s8   q0,d1,d2
        assertEquals(0xf3910c02, enc(1, 1, 1, 0, 0b1100, 2));  // vmull.u16  q0,d1,d2
        assertEquals(0xf2a10d02, enc(0, 2, 1, 0, 0b1101, 2));  // vqdmull.s32 q0,d1,d2
        assertEquals(0xf2810e02, enc(0, 0, 1, 0, 0b1110, 2));  // vmull.p8   q0,d1,d2
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        int[] words = {
                enc(0, 0, 0, 0, 0b0000, 1),  // vaddl.s8
                enc(0, 0, 2, 0, 0b0001, 2),  // vaddw.s8
                enc(0, 0, 2, 0, 0b0100, 4),  // vaddhn.i16
                enc(0, 0, 1, 0, 0b1110, 2),  // vmull.p8
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, w).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, w).kind());
        }
    }

    @Test
    void featureGateReturnsNullBeforeFrameCheck() {
        int word = enc(0, 0, 0, 0, 0b0000, 1);
        assertNull(new NeonThreeRegDifferentDecoder(ArmArchitecture.ARMV7A).tryDecode(word, 0, Condition.AL));
    }

    // ── size==0b11 e bit6==1 deixam o espaço livre (`null`, não `unimplemented`) ──

    @Test
    void size3IsNotClaimedByThisDecoder() {
        // bits[21:20]=11: VEXT/two-reg-misc/VTBL/dup-scalar (B13.12-14), ainda sem dono.
        int word = enc(0, 0b11, 0, 0, 0b0000, 1);
        assertNull(new NeonThreeRegDifferentDecoder(NEON_FEATURES).tryDecode(word, 0, Condition.AL));
    }

    @Test
    void twoRegsPlusScalarSpaceIsNotClaimedByThisDecoder() {
        // bit6=1: "2-regs-plus-scalar" (B13.11), ainda sem dono.
        int word = enc(0, 0, 0, 0, 0b0000, 1) | (1 << 6);
        assertNull(new NeonThreeRegDifferentDecoder(NEON_FEATURES).tryDecode(word, 0, Condition.AL));
    }

    // ── Decodifica com op/esz/registrador corretos ──

    @Test
    void longFormDecodesWithRightOpEszAndRegisters() {
        IrOp.NeonWidening addl = (IrOp.NeonWidening) liftedOf(enc(0, 0, 0, 0, 0b0000, 1));
        assertEquals(AdvSimdWideningOp.SADDL, addl.op());
        assertEquals(0, addl.esz());
        assertEquals(0, addl.vd());
        assertEquals(0, addl.vn());
        assertEquals(1, addl.vm());

        IrOp.NeonWidening mullP = (IrOp.NeonWidening) liftedOf(enc(0, 0, 1, 0, 0b1110, 2));
        assertEquals(AdvSimdWideningOp.PMULL, mullP.op());

        IrOp.NeonWidening qdmull = (IrOp.NeonWidening) liftedOf(enc(0, 2, 1, 0, 0b1101, 2));
        assertEquals(AdvSimdWideningOp.SQDMULL, qdmull.op());
        assertEquals(2, qdmull.esz());
    }

    @Test
    void wideFormDecodesWithRightOpEszAndRegisters() {
        IrOp.NeonWide addw = (IrOp.NeonWide) liftedOf(enc(0, 0, 2, 0, 0b0001, 2));
        assertEquals(AdvSimdWideOp.SADDW, addw.op());
        assertEquals(0, addw.esz());
        assertEquals(0, addw.vd());
        assertEquals(2, addw.vn());
        assertEquals(2, addw.vm());

        IrOp.NeonWide subwU = (IrOp.NeonWide) liftedOf(enc(1, 1, 2, 0, 0b0011, 2));
        assertEquals(AdvSimdWideOp.USUBW, subwU.op());
    }

    @Test
    void narrowFormDecodesWithRightOpEszAndRegisters() {
        IrOp.NeonNarrow addhn = (IrOp.NeonNarrow) liftedOf(enc(0, 1, 2, 0, 0b0100, 4));
        assertEquals(AdvSimdNarrowOp.ADDHN, addhn.op());
        assertEquals(1, addhn.esz());
        assertEquals(0, addhn.vd());
        assertEquals(2, addhn.vn());
        assertEquals(4, addhn.vm());

        IrOp.NeonNarrow raddhn = (IrOp.NeonNarrow) liftedOf(enc(1, 2, 2, 0, 0b0100, 4));
        assertEquals(AdvSimdNarrowOp.RADDHN, raddhn.op());

        IrOp.NeonNarrow rsubhn = (IrOp.NeonNarrow) liftedOf(enc(1, 2, 2, 0, 0b0110, 4));
        assertEquals(AdvSimdNarrowOp.RSUBHN, rsubhn.op());
    }

    // ── Registrador ímpar UNDEFINED por forma ──

    @Test
    void longFormOddQDestinationIsUndefined() {
        // vd=1 (ímpar) em vez de 0.
        int word = enc(0, 0, 0, 1, 0b0000, 1);
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(word).kind());
    }

    @Test
    void wideFormOddQOperandsAreUndefined() {
        int oddVd = enc(0, 0, 2, 1, 0b0001, 2);   // vd ímpar
        int oddVn = enc(0, 0, 3, 0, 0b0001, 2);   // vn ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVd).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVn).kind());
    }

    @Test
    void narrowFormOddQOperandsAreUndefined() {
        int oddVn = enc(0, 1, 3, 0, 0b0100, 4);   // vn ímpar
        int oddVm = enc(0, 1, 2, 0, 0b0100, 5);   // vm ímpar
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVn).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(oddVm).kind());
    }

    @Test
    void unsignedOnlyOpcodesWithUEqualsOneStayUnimplemented() {
        // VQDMLAL/VQDMLSL/VQDMULL/VMULL.P8 não têm forma U=1.
        int[] words = {
                enc(1, 1, 1, 0, 0b1001, 2), // "vqdmlal.u16"
                enc(1, 2, 1, 0, 0b1011, 2), // "vqdmlsl.u32"
                enc(1, 2, 1, 0, 0b1101, 2), // "vqdmull.u32"
                enc(1, 0, 1, 0, 0b1110, 2), // "vmull.p8" com U=1
                enc(0, 0, 1, 0, 0b1111, 2), // opc reservado
        };
        for (int w : words) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(w).kind());
        }
    }

    // ── Execução: um padrão conhecido por família ──

    @Test
    void addlSignedVersusUnsignedOnByteWithSignBitSet() {
        // vaddl.s8 q0,d0,d1: 0x80(assinado=-128) + 0x01 = -127 = 0xFF81 (halfword)
        ArmCore core = newCore();
        core.vfp().setD(0, 0x80L);
        core.vfp().setD(1, 0x01L);
        run(core, enc(0, 0, 0, 0, 0b0000, 1)); // vaddl.s8 q0,d0,d1
        assertEquals(0xFF81L, core.vfp().d(0) & 0xFFFFL);

        // vaddl.u8 (mesmos bytes, size0, U=1): 0x80 + 0x01 = 0x81 (não assinado)
        core.vfp().setD(0, 0x80L);
        core.vfp().setD(1, 0x01L);
        run(core, enc(1, 0, 0, 0, 0b0000, 1)); // vaddl.u8 q0,d0,d1
        assertEquals(0x0081L, core.vfp().d(0) & 0xFFFFL);
    }

    @Test
    void addwWideningPlusNarrowOperand() {
        // vaddw.s32 q0,q1,d2: Rn(q1, elemento largo)=100, Rm(d2, elemento estreito)=3 -> 103
        ArmCore core = newCore();
        core.vfp().setD(2, 100L);  // q1 = d2:d3, elemento 0 (word) = 100
        core.vfp().setD(4, 3L);    // d4 = Vm estreito, elemento 0 = 3
        run(core, enc(0, 2, 2, 0, 0b0001, 4)); // vaddw.s32 q0,q1,d2
        assertEquals(103L, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    @Test
    void addhnVersusRaddhnRoundingChangesResult() {
        // size=2 -> esz(dest)=2 (word), fonte doubleword (esz+1=3) — mnemonic real seria
        // "vaddhn.i64" (o sufixo denota a largura da FONTE, confirmado contra o objdump real:
        // "vaddhn.i16"/size=0 tem fonte halfword). Valores escolhidos para trocar o carry entre
        // arredondado/não: a=b=0x7FFF_FFFF.
        ArmCore core = newCore();
        core.vfp().setD(2, 0x7FFF_FFFFL); // q1 elemento0 (word)
        core.vfp().setD(4, 0x7FFF_FFFFL); // q2 elemento0 (word)
        run(core, enc(0, 2, 2, 0, 0b0100, 4)); // vaddhn (size=2), sem arredondar
        // soma = 0xFFFF_FFFE, sem overflow de 32 bits -> >>32 = 0
        assertEquals(0L, core.vfp().d(0) & 0xFFFF_FFFFL);

        core.vfp().setD(0, 0L);
        run(core, enc(1, 2, 2, 0, 0b0100, 4)); // vraddhn (size=2), arredonda
        // soma + (1<<31) = 0xFFFF_FFFE + 0x8000_0000 = 0x1_7FFF_FFFE -> >>32 = 1
        assertEquals(1L, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    @Test
    void abalAccumulatesAbsoluteDifference() {
        // vabal.s8 q0,d1,d2: acumula |sext(Rn)-sext(Rm)| no Rd (halfword) já pré-preenchido.
        ArmCore core = newCore();
        core.vfp().setD(0, 10L);   // acumulador (halfword lane0) = 10
        core.vfp().setD(1, 0x03L); // Rn lane0 = 3
        core.vfp().setD(2, 0x08L); // Rm lane0 = 8
        run(core, enc(0, 0, 1, 0, 0b0101, 2)); // vabal.s8 q0,d1,d2
        assertEquals(15L, core.vfp().d(0) & 0xFFFFL); // 10 + |3-8| = 15
    }

    @Test
    void mullPolynomialMatchesHandComputedGf2Product() {
        // vmull.p8 q0,d1,d2: 0x03 (x+1) * 0x05 (x^2+1) em GF(2) = x^3+x^2+x+1 = 0x0F.
        ArmCore core = newCore();
        core.vfp().setD(1, 0x03L);
        core.vfp().setD(2, 0x05L);
        run(core, enc(0, 0, 1, 0, 0b1110, 2)); // vmull.p8 q0,d1,d2
        assertEquals(0x0FL, core.vfp().d(0) & 0xFFFFL);
    }

    @Test
    void qdmullSaturatesAtMaxPositive() {
        // vqdmull.s16 q0,d1,d2: 2*sext(0x8000)*sext(0x8000) = 2*(-32768)^2 = 0x8000_0000, que
        // excede INT32_MAX por 1 -> satura em 0x7FFF_FFFF.
        ArmCore core = newCore();
        core.vfp().setD(1, 0x8000L);
        core.vfp().setD(2, 0x8000L);
        run(core, enc(0, 1, 1, 0, 0b1101, 2)); // vqdmull.s16 q0,d1,d2
        assertEquals(0x7FFF_FFFFL, core.vfp().d(0) & 0xFFFF_FFFFL);
    }

    // ── Aliasing (E10): Vd==Vn e Vd==Vm ──

    @Test
    void longFormAliasingDestinationWithSource() {
        // vaddl.s8 q0,d0,d1 com Vd(q0=d0:d1) sobrepondo Vn=d0 (E POR TABELA Vm=d1, a outra metade
        // do próprio Vd): as 8 lanes largas (halfword) de saída cobrem d0 (lanes 0-3) e d1 (lanes
        // 4-7); as lanes 0-3 são escritas a partir das lanes 0-3 (bytes) da FONTE (Vn=d0/Vm=d1) —
        // escrever a lane larga 0 (halfword, bytes 0-1 do Vd) não pode corromper a leitura do byte
        // 1 (lane1 da fonte) antes dela ser lida — buffer da E10/migração D1.
        ArmCore core = newCore();
        core.vfp().setD(0, 0x0201L); // Vn=Vd(d0): byte0=1, byte1=2
        core.vfp().setD(1, 0x0000L); // Vm=Vd(d1): bytes = 0
        run(core, enc(0, 0, 0, 0, 0b0000, 1)); // vaddl.s8 q0,d0,d1 (Vd=q0 -> d0,d1; Vn=d0; Vm=d1)
        // lane0 (byte0) = 1+0 = 1; lane1 (byte1) = 2+0 = 2 — ambas escritas em d0 (halfwords 0/1)
        // sem que a leitura do byte1 tenha sido corrompida pela escrita da lane0.
        assertEquals(0x0002_0001L, core.vfp().d(0) & 0xFFFF_FFFFL);
        // lanes 2-7 (bytes 2-7 de Vn/Vm, todos 0) -> resultado 0 em d0 (halfwords 2-3) e d1 inteiro.
        assertEquals(0L, core.vfp().d(1));
    }

    @Test
    void narrowFormAliasingDestinationWithWideSource() {
        // vaddhn.i16 d0,q0,q1 com Vd=d0 sobrepondo a metade baixa de Vn(q0=d0:d1): escrever a lane
        // estreita 0 (byte0 de d0) não pode corromper a leitura da lane larga 1 (d1, halfword)
        // ainda não lida.
        ArmCore core = newCore();
        core.vfp().setD(0, 0x00FFL); // Vn=q0 elemento0 (d0, halfword) = 0x00FF
        core.vfp().setD(1, 0x0001L); // Vn=q0 elemento1 (d1, halfword) = 0x0001
        core.vfp().setD(2, 0x0000L); // Vm=q1 elemento0 (d2) = 0
        core.vfp().setD(3, 0x0000L); // Vm=q1 elemento1 (d3) = 0
        run(core, enc(0, 0, 0, 0, 0b0100, 2)); // vaddhn.i16 d0,q0,q1 (Vn=q0=d0:d1, Vd=d0)
        // lane0 (esz=0,byte) = high byte de (0x00FF+0)=0x00FF -> 0x00; lane1 = high byte de
        // (0x0001+0)=0x0001 -> 0x00
        assertEquals(0x0000L, core.vfp().d(0) & 0xFFFFL);
    }
}
