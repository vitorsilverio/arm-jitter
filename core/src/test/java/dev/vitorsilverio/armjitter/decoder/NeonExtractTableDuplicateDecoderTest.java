package dev.vitorsilverio.armjitter.decoder;

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

/// NEON `VEXT`/`VTBL`/`VTBX`/`VDUP_scalar` A32, sub-grupo `size==0b11` FORA do sub-layout
/// "2-reg-misc" (task B13.14, ver Javadoc de {@link NeonExtractTableDuplicateDecoder}).
///
/// Execução pelo núcleo vetorial COMPARTILHADO com o lado A64 ({@code AdvSimdLanes.extract}/
/// {@code tableLookup}) para `VEXT`/`VTBL`/`VTBX`; `VDUP_scalar` fica direto no executor.
///
/// Encodings golden conferidos com `arm-none-eabi-as -mfpu=neon -march=armv8-a` do devkitARM
/// (ver `## Resultado` da task para o log completo).
class NeonExtractTableDuplicateDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonExtractTableDuplicate",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst(NEON_FEATURES));

    private static List<DecoderExtension> neonFirst(ArmArchitecture features) {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonExtractTableDuplicateDecoder(features));
        extensions.addAll(ArmArchitecture.ARMV7A.decoderExtensions());
        return extensions;
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

    // ── Encoding golden (assembler real, arm-none-eabi-as -mfpu=neon -march=armv8-a) ──

    @Test
    void extEncodingsMatchTheAssembler() {
        assertEquals(0xf2b10302, encExt(0, 1, 0, 3, 2)); // vext.8 d0,d1,d2,#3
        assertEquals(0xf2b20544, encExt(1, 2, 0, 5, 4)); // vext.8 q0,q1,q2,#5 (vn=q1's base D=2, vm=q2's base D=4)
    }

    @Test
    void tblTbxEncodingsMatchTheAssembler() {
        assertEquals(0xf3b10802, encTbl(0, 1, 0, 0, 2, false)); // vtbl.8 d0,{d1},d2
        assertEquals(0xf3b10b05, encTbl(0, 1, 3, 0, 5, false)); // vtbl.8 d0,{d1,d2,d3,d4},d5
        assertEquals(0xf3b10943, encTbl(0, 1, 1, 0, 3, true));  // vtbx.8 d0,{d1,d2},d3
    }

    @Test
    void dupScalarEncodingsMatchTheAssembler() {
        assertEquals(0xf3b70c01, encDup(0, 3, 0, 1, false)); // vdup.8  d0,d1[3]
        assertEquals(0xf3b60c01, encDup(1, 1, 0, 1, false)); // vdup.16 d0,d1[1]
        assertEquals(0xf3b40c01, encDup(2, 0, 0, 1, false)); // vdup.32 d0,d1[0]
        assertEquals(0xf3bf0c41, encDup(0, 7, 0, 1, true));  // vdup.8  q0,d1[7]
    }

    // `1111 001 0 1 . 11 nnnn dddd imm:4 . q:1 . 0 mmmm`
    private static int encExt(int q, int vn, int vd, int imm, int vm) {
        return 0xF2B0_0000
                | ((vd >> 4) << 22)
                | ((vn & 0xF) << 16)
                | ((vd & 0xF) << 12)
                | ((imm & 0xF) << 8)
                | ((vn >> 4) << 7)
                | (q << 6)
                | ((vm >> 4) << 5)
                | (vm & 0xF);
    }

    // `1111 001 1 1 . 11 nnnn dddd 10 len:2 . op:1 . 0 mmmm`
    private static int encTbl(int unused, int vn, int len, int vd, int vm, boolean tbx) {
        return 0xF3B0_0800
                | ((vd >> 4) << 22)
                | ((vn & 0xF) << 16)
                | ((vd & 0xF) << 12)
                | ((len & 0x3) << 8)
                | ((vn >> 4) << 7)
                | ((tbx ? 1 : 0) << 6)
                | ((vm >> 4) << 5)
                | (vm & 0xF);
    }

    // `1111 001 1 1 . 11 imm4:4 dddd 11 000 q:1 . 0 mmmm`
    private static int encDup(int esz, int index, int vd, int vm, boolean q) {
        int imm4 = (index << (esz + 1)) | (1 << esz);
        return 0xF3B0_0C00
                | ((vd >> 4) << 22)
                | ((imm4 & 0xF) << 16)
                | ((vd & 0xF) << 12)
                | ((q ? 1 : 0) << 6)
                | ((vm >> 4) << 5)
                | (vm & 0xF);
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ──

    @Test
    void withoutTheFeatureEveryEncodingStaysUnimplemented() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, encExt(0, 1, 0, 3, 2)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, encTbl(0, 1, 0, 0, 2, false)).kind());
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, encDup(0, 3, 0, 1, false)).kind());
    }

    @Test
    void featureGateReturnsNullBeforeFrameCheck() {
        assertNull(new NeonExtractTableDuplicateDecoder(ArmArchitecture.ARMV7A)
                .tryDecode(encExt(0, 1, 0, 3, 2), 0, Condition.AL));
    }

    // ── Decodifica com operandos corretos ──

    @Test
    void extDecodesImmAndRegistersInBytes() {
        IrOp.NeonExtract ext = (IrOp.NeonExtract) liftedOf(encExt(0, 1, 0, 3, 2));
        assertEquals(false, ext.quad());
        assertEquals(3, ext.imm());
        assertEquals(0, ext.vd());
        assertEquals(1, ext.vn());
        assertEquals(2, ext.vm());

        IrOp.NeonExtract extQ = (IrOp.NeonExtract) liftedOf(encExt(1, 2, 0, 5, 4));
        assertEquals(true, extQ.quad());
        assertEquals(5, extQ.imm());
        assertEquals(2, extQ.vn());
        assertEquals(4, extQ.vm());
    }

    @Test
    void extWithoutQAndImmAboveSevenIsUndefined() {
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(encExt(0, 1, 0, 8, 2)).kind());
    }

    @Test
    void tblDecodesLenAndOp() {
        IrOp.NeonTableLookup tbl1 = (IrOp.NeonTableLookup) liftedOf(encTbl(0, 1, 0, 0, 2, false));
        assertEquals(false, tbl1.tbx());
        assertEquals(0, tbl1.len());
        assertEquals(0, tbl1.vd());
        assertEquals(1, tbl1.vn());
        assertEquals(2, tbl1.vm());

        IrOp.NeonTableLookup tbl4 = (IrOp.NeonTableLookup) liftedOf(encTbl(0, 1, 3, 0, 5, false));
        assertEquals(3, tbl4.len());

        IrOp.NeonTableLookup tbx = (IrOp.NeonTableLookup) liftedOf(encTbl(0, 1, 1, 0, 3, true));
        assertEquals(true, tbx.tbx());
        assertEquals(1, tbx.len());
    }

    @Test
    void tblRejectsTableCrossingD31() {
        // vn=30,len=3 -> vn+len=33 > 31 (D31 é o último registrador real).
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(encTbl(0, 30, 3, 0, 5, false)).kind());
        // vn=28,len=3 -> vn+len=31, no limite, ainda válido.
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(encTbl(0, 28, 3, 0, 5, false)).kind());
    }

    @Test
    void dupScalarSizeComesFromThePatternNotAField() {
        IrOp.NeonDuplicateScalar byteForm = (IrOp.NeonDuplicateScalar) liftedOf(encDup(0, 3, 0, 1, false));
        assertEquals(0, byteForm.esz());
        assertEquals(3, byteForm.index());
        assertEquals(false, byteForm.quad());

        IrOp.NeonDuplicateScalar halfForm = (IrOp.NeonDuplicateScalar) liftedOf(encDup(1, 1, 0, 1, false));
        assertEquals(1, halfForm.esz());
        assertEquals(1, halfForm.index());

        IrOp.NeonDuplicateScalar wordForm = (IrOp.NeonDuplicateScalar) liftedOf(encDup(2, 0, 0, 1, false));
        assertEquals(2, wordForm.esz());
        assertEquals(0, wordForm.index());

        IrOp.NeonDuplicateScalar quadForm = (IrOp.NeonDuplicateScalar) liftedOf(encDup(0, 7, 0, 1, true));
        assertEquals(true, quadForm.quad());
        assertEquals(7, quadForm.index());
    }

    @Test
    void dupScalarReservedImm4StaysUnimplemented() {
        // imm4=0000 (esz não definido por nenhum bit) — reservado.
        int reservedImm4 = 0xF3B0_0C00 | (1 << 12) | (1 << 0);
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(reservedImm4).kind());
    }

    // ── Execução ──

    @Test
    void vextConcatenatesRnLowRmHighAndExtractsWindow() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0807_0605_0403_0201L); // Vn = {01,02,03,04,05,06,07,08}
        core.vfp().setD(2, 0x100F_0E0D_0C0B_0A09L); // Vm = {09,0A,0B,0C,0D,0E,0F,10}
        run(core, encExt(0, 1, 0, 3, 2)); // vext.8 d0,d1,d2,#3
        // janela de 8 bytes começando no byte 3 de [Vn:Vm] = {04,05,06,07,08,09,0A,0B}
        assertEquals(0x0B0A_0908_0706_0504L, core.vfp().d(0));
    }

    @Test
    void vtblIndexOutOfRangeProducesZeroVtbxPreservesVd() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0000_0000_0403_0201L); // tabela de 1 registrador: {01,02,03,04,0,0,0,0}
        core.vfp().setD(2, 0x0000_0000_0000_0008L); // índice 8: fora da tabela (len=0 -> só 8 bytes, 0-7)
        core.vfp().setD(0, 0xAAAA_AAAA_AAAA_AAAAL); // Vd pré-existente (para VTBX preservar)

        run(core, encTbl(0, 1, 0, 0, 2, false)); // vtbl.8 d0,{d1},d2
        assertEquals(0x0000_0000_0000_0000L, core.vfp().d(0) & 0xFFL);

        core.vfp().setD(0, 0xAAAA_AAAA_AAAA_AAAAL);
        run(core, encTbl(0, 1, 0, 0, 2, true)); // vtbx.8 d0,{d1},d2
        assertEquals(0xAAL, core.vfp().d(0) & 0xFFL);
    }

    @Test
    void vtblWithFourTableRegistersReadsAcrossAllFour() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0807_0605_0403_0201L); // reg0: bytes 01..08
        core.vfp().setD(2, 0x100F_0E0D_0C0B_0A09L); // reg1: bytes 09..10
        core.vfp().setD(3, 0x1817_1615_1413_1211L); // reg2: bytes 11..18
        core.vfp().setD(4, 0x201F_1E1D_1C1B_1A19L); // reg3: bytes 19..20
        // índices 0, 8, 16, 24, 31, 32(fora, len=3 -> 32 bytes válidos 0..31), 0, 0
        core.vfp().setD(5, 0x0000_201F_1810_0800L);
        run(core, encTbl(0, 1, 3, 0, 5, false)); // vtbl.8 d0,{d1-d4},d5
        long result = core.vfp().d(0);
        assertEquals(0x01L, result & 0xFFL);         // índice 0 -> byte 01
        assertEquals(0x09L, (result >>> 8) & 0xFFL); // índice 8 -> byte 09
        assertEquals(0x11L, (result >>> 16) & 0xFFL); // índice 16 -> byte 11
        assertEquals(0x19L, (result >>> 24) & 0xFFL); // índice 24 -> byte 19
        assertEquals(0x20L, (result >>> 32) & 0xFFL); // índice 31 -> byte 20 (último válido)
        assertEquals(0x00L, (result >>> 40) & 0xFFL); // índice 32 -> fora da tabela -> 0
    }

    @Test
    void vdupScalarReplicatesElementAcrossAllLanes() {
        ArmCore core = newCore();
        core.vfp().setD(1, 0x0807_0605_0403_0201L); // {01,02,03,04,05,06,07,08}
        run(core, encDup(0, 3, 0, 1, false)); // vdup.8 d0,d1[3] -> elemento 04
        assertEquals(0x0404_0404_0404_0404L, core.vfp().d(0));

        core.vfp().setD(1, 0x0000_0000_0002_0001L); // halfwords {0001,0002,...}
        run(core, encDup(1, 1, 0, 1, false)); // vdup.16 d0,d1[1] -> elemento 0002
        assertEquals(0x0002_0002_0002_0002L, core.vfp().d(0));

        core.vfp().setD(1, 0x0000_0007_0000_0009L); // words {00000009,00000007}
        run(core, encDup(2, 0, 0, 1, false)); // vdup.32 d0,d1[0] -> elemento 00000009
        assertEquals(0x0000_0009_0000_0009L, core.vfp().d(0));

        core.vfp().setD(0, 0);
        core.vfp().setD(1, 0);
        core.vfp().setD(2, 0);
        core.vfp().setD(3, 0);
        core.vfp().setD(1, 0x0706_0504_0302_0100L); // byte 7 (topo do D) = 0x07
        run(core, encDup(0, 7, 0, 1, true)); // vdup.8 q0,d1[7] -> replica byte 0x07 em D0 e D1
        assertEquals(0x0707_0707_0707_0707L, core.vfp().d(0));
        assertEquals(0x0707_0707_0707_0707L, core.vfp().d(1));
    }
}
