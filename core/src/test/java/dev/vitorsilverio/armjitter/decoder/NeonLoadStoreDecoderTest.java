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

/// NEON load/store A32 (task B13.3): as 5 linhas de `neon-ls.decode` → `IrOp.NeonLoadStore*` →
/// execução por {@link dev.vitorsilverio.armjitter.codegen.executor.IrNeonExecutor}.
///
/// Todos os encodings golden abaixo foram conferidos com
/// `arm-none-eabi-as -mfpu=neon -mcpu=cortex-a8` do devkitARM (precedente B9.6).
class NeonLoadStoreDecoderTest {
    private static final ArmArchitecture NEON_FEATURES =
            ArmArchitecture.extending(ArmArchitecture.ARMV7A, "ARMv7-TestNeonLs",
                    ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    private static final ArmArchitecture NEON_ARCH = NEON_FEATURES.withDecoderExtensions(neonFirst());

    private static List<DecoderExtension> neonFirst() {
        List<DecoderExtension> extensions = new ArrayList<>();
        extensions.add(new NeonLoadStoreDecoder(NEON_FEATURES));
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

    private static IrOp liftedOp(int word) {
        return liftSingleOp(decode(word));
    }

    // ── Zero-diff: nenhum preset declara ADVANCED_SIMD ────────────────────────────────────────

    @Test
    void withoutAdvancedSimdEveryFormStaysUnimplemented() {
        for (int word : new int[] {0xF421_070F, 0xF4A1_006F, 0xF4A1_0C0F}) {
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARMV7A, word).kind());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(ArmArchitecture.ARM11_MPCORE, word).kind());
        }
    }

    // ── Decode: multiple structures ──────────────────────────────────────────────────────────

    @Test
    void vld1SingleRegisterMultiple() {
        // vld1.8 {d0}, [r1]  (itype 7 = {1,1,1})
        assertEquals(InstructionKind.LIFTED_IR_OP, decode(0xF421_070F).kind());
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 0, 1, 15, 0, 1, 1, 1), liftedOp(0xF421_070F));
    }

    @Test
    void vld4WithDoubleSpacing() {
        // vld4.32 {d0,d2,d4,d6}, [r1]  (itype 1 = {1,4,2})
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 0, 1, 15, 2, 1, 4, 2), liftedOp(0xF421_018F));
    }

    @Test
    void vld2FourRegistersUsesRealQemuItype3Table() {
        // vld2.16 {d0-d3}, [r1]  — itype 3. A spec da task dizia {4,1,2}; o QEMU real é {2,2,2}.
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 0, 1, 15, 1, 2, 2, 2), liftedOp(0xF421_034F));
    }

    @Test
    void vst3Multiple() {
        // vst3.8 {d0,d1,d2}, [r1]  (itype 4 = {1,3,1}, l=0)
        assertEquals(new IrOp.NeonLoadStoreMultiple(false, 0, 1, 15, 0, 1, 3, 1), liftedOp(0xF401_040F));
    }

    @Test
    void multipleWritebackImmediateAndRegisterCarryRawRm() {
        // vld1.32 {d0,d1}, [r1]!    -> rm = 13 (imediato)
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 0, 1, 13, 2, 2, 1, 1), liftedOp(0xF421_0A8D));
        // vld1.32 {d0,d1}, [r1], r3 -> rm = 3
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 0, 1, 3, 2, 2, 1, 1), liftedOp(0xF421_0A83));
    }

    @Test
    void multipleDoublewordElement() {
        // vld1.64 {d0}, [r1]  (size = 3)
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 0, 1, 15, 3, 1, 1, 1), liftedOp(0xF421_07CF));
    }

    @Test
    void multipleAddressesD16WithD32Feature() {
        // vld1.16 {d16}, [r1]
        assertEquals(new IrOp.NeonLoadStoreMultiple(true, 16, 1, 15, 1, 1, 1, 1), liftedOp(0xF461_074F));
    }

    // ── Decode: single structure to one lane ─────────────────────────────────────────────────

    @Test
    void vld1SingleLane() {
        // vld1.8 {d0[3]}, [r1]
        assertEquals(new IrOp.NeonLoadStoreSingle(true, 0, 1, 15, 0, 1, 1, 3), liftedOp(0xF4A1_006F));
    }

    @Test
    void vld2SingleLaneDoubleSpacing() {
        // vld2.32 {d0[1],d2[1]}, [r1]  (stride 2)
        assertEquals(new IrOp.NeonLoadStoreSingle(true, 0, 1, 15, 2, 2, 2, 1), liftedOp(0xF4A1_09CF));
    }

    @Test
    void vst1SingleLane() {
        // vst1.8 {d0[3]}, [r1]
        assertEquals(new IrOp.NeonLoadStoreSingle(false, 0, 1, 15, 0, 1, 1, 3), liftedOp(0xF481_006F));
    }

    // ── Decode: single structure to all lanes ────────────────────────────────────────────────

    @Test
    void vld1AllLanes() {
        // vld1.8 {d0[]}, [r1]
        assertEquals(new IrOp.NeonLoadAllLanes(0, 1, 15, 0, 1, 1, false), liftedOp(0xF4A1_0C0F));
    }

    @Test
    void vld1AllLanesTwoRegistersSetsQuad() {
        // vld1.8 {d0[],d1[]}, [r1]  (bit t = 1 -> replica em DOIS D)
        assertEquals(new IrOp.NeonLoadAllLanes(0, 1, 15, 0, 1, 2, true), liftedOp(0xF4A1_0C2F));
    }

    @Test
    void vld4AllLanes() {
        // vld4.16 {d0[],d1[],d2[],d3[]}, [r1]
        assertEquals(new IrOp.NeonLoadAllLanes(0, 1, 15, 1, 4, 1, false), liftedOp(0xF4A1_0F4F));
    }

    // ── UNDEFINED ────────────────────────────────────────────────────────────────────────────

    @Test
    void itypeAboveTenIsUndefined() {
        // itype = 11 no espaço multiple
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF421_0B0F).kind());
    }

    @Test
    void doublewordWithInterleaveIsUndefined() {
        // itype 8 = {1,2,1} com size = 3: (interleave | spacing) != 1
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF421_08CF).kind());
    }

    @Test
    void baseRegisterPcIsUndefined() {
        // vld1.8 {d0}, [pc]  (rn = 15) — UNPREDICTABLE no HW
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF42F_070F).kind());
    }

    @Test
    void registerBeyondD31IsUndefined() {
        // vld4.32 {d26,d28,d30,d32...}, [r1]  — último D = 26 + 2*3 = 32 (> D31), sem wrap
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF461_A18F).kind());
    }

    @Test
    void invalidAlignFieldMultipleIsUndefined() {
        // itype 4 ({1,3,1}) com align = 2 (>= 2 é inválido para itype & 0xc == 4)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF421_042F).kind());
    }

    @Test
    void invalidAlignFieldSingleLaneIsUndefined() {
        // vld1.8 {d0[0]} com o bit align setado (nregs=1, size=0: align deve ser 0)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF4A1_007F).kind());
    }

    @Test
    void allLanesSizeThreeWithoutFourRegistersIsUndefined() {
        // bits[11:10]=11, selem=1, size=3, a=1  — size==3 exige nregs==4
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF4A1_0CEF).kind());
    }

    @Test
    void allLanesByteWithAlignAndSingleRegisterIsUndefined() {
        // selem=1, size=0, a=1  — QEMU rejeita
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF4A1_0C1F).kind());
    }

    @Test
    void allLanesThreeRegistersWithAlignIsUndefined() {
        // selem=3, a=1 — QEMU rejeita (VLD3 não tem alinhamento)
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF4A1_0E1F).kind());
    }

    @Test
    void storeToAllLanesIsUndefined() {
        // bits[11:10]=11 mas bit21=0 (não existe "store to all lanes")
        assertEquals(InstructionKind.UNIMPLEMENTED, decode(0xF481_0C0F).kind());
    }

    // ── Execução ─────────────────────────────────────────────────────────────────────────────

    private static ArmCore newCore(TestAddressSpace memory) {
        return new ArmCore(memory, SwiDispatcher.empty(), NEON_ARCH);
    }

    /// Memória cujo byte no endereço `a` vale `a & 0xFF`.
    private static TestAddressSpace bytePatternMemory(int size) {
        TestAddressSpace memory = new TestAddressSpace(size);
        for (int a = 0; a + 4 <= size; a += 4) {
            memory.put32(a, a | ((a + 1) << 8) | ((a + 2) << 16) | ((a + 3) << 24));
        }
        return memory;
    }

    private static void run(ArmCore core, int word) {
        IrOp op = liftedOp(word);
        new IrBlockExecutor(NEON_ARCH).executeOp(core, op, 0);
    }

    @Test
    void vld1MultipleLoadsConsecutiveBytesIntoTwoRegisters() {
        TestAddressSpace memory = bytePatternMemory(64);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF421_0A0F); // vld1.8 {d0,d1}, [r1]  (16 bytes)
        assertEquals(0x0706_0504_0302_0100L, core.vfp().d(0));
        assertEquals(0x0F0E_0D0C_0B0A_0908L, core.vfp().d(1));
    }

    @Test
    void vld2MultipleDeinterleavesBytes() {
        TestAddressSpace memory = bytePatternMemory(64);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF421_080F); // vld2.8 {d0,d1}, [r1]
        // d0 recebe os bytes de índice par, d1 os de índice ímpar
        assertEquals(0x0E0C_0A08_0604_0200L, core.vfp().d(0));
        assertEquals(0x0F0D_0B09_0705_0301L, core.vfp().d(1));
    }

    @Test
    void vld4WithDoubleSpacingDeinterleavesWords() {
        TestAddressSpace memory = bytePatternMemory(64);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF421_018F); // vld4.32 {d0,d2,d4,d6}, [r1]  (32 bytes)
        // estrutura de 4 words: d0 <- [0],[16]; d2 <- [4],[20]; d4 <- [8],[24]; d6 <- [12],[28]
        assertEquals(0x1312_1110_0302_0100L, core.vfp().d(0));
        assertEquals(0x1716_1514_0706_0504L, core.vfp().d(2));
        assertEquals(0x1B1A_1918_0B0A_0908L, core.vfp().d(4));
        assertEquals(0x1F1E_1D1C_0F0E_0D0CL, core.vfp().d(6));
        // registradores fora do alcance intactos
        assertEquals(0L, core.vfp().d(1));
    }

    @Test
    void vst2MultipleInterleavesToMemory() {
        TestAddressSpace memory = new TestAddressSpace(64);
        ArmCore core = newCore(memory);
        core.vfp().setD(0, 0x8080_8080_8080_8080L);
        core.vfp().setD(1, 0x1111_1111_1111_1111L);
        core.setRegister(1, 0x00);
        run(core, 0xF401_080F); // vst2.8 {d0,d1}, [r1]
        for (int i = 0; i < 8; i++) {
            assertEquals(0x80, memory.read8(2 * i), "byte par " + i);
            assertEquals(0x11, memory.read8(2 * i + 1), "byte ímpar " + i);
        }
    }

    @Test
    void multipleImmediateWritebackAddsTransferByteCount() {
        ArmCore core = newCore(bytePatternMemory(0x80));
        core.setRegister(1, 0x40);
        run(core, 0xF421_0A8D); // vld1.32 {d0,d1}, [r1]!  -> 2*1*8 = 16 bytes
        assertEquals(0x50, core.register(1));
    }

    @Test
    void multipleRegisterWritebackAddsRegisterValue() {
        ArmCore core = newCore(bytePatternMemory(0x80));
        core.setRegister(1, 0x40);
        core.setRegister(3, 0x100);
        run(core, 0xF421_0A83); // vld1.32 {d0,d1}, [r1], r3
        assertEquals(0x140, core.register(1));
    }

    @Test
    void multipleWithoutWritebackLeavesBaseUnchanged() {
        ArmCore core = newCore(bytePatternMemory(64));
        core.setRegister(1, 0x10);
        run(core, 0xF421_080F); // vld2.8 {d0,d1}, [r1]  (rm = 15)
        assertEquals(0x10, core.register(1));
    }

    @Test
    void singleLaneLoadTouchesOnlyOneLane() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xBEEF);
        ArmCore core = newCore(memory);
        core.vfp().setD(0, 0x1111_2222_3333_4444L);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_048F); // vld1.16 {d0[2]}, [r1]
        assertEquals(0x1111_BEEF_3333_4444L, core.vfp().d(0));
    }

    @Test
    void singleLaneStoreWritesOnlyOneElement() {
        TestAddressSpace memory = new TestAddressSpace(16);
        ArmCore core = newCore(memory);
        core.vfp().setD(0, 0x0000_0000_0000_CD00L); // lane 1 (byte) = 0xCD (bits 15:8)
        core.setRegister(1, 0x00);
        run(core, 0xF481_002F); // vst1.8 {d0[1]}, [r1]
        assertEquals(0xCD, memory.read8(0));
        assertEquals(0x00, memory.read8(1));
    }

    @Test
    void singleLaneDoubleSpacingLoadsTwoRegisters() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xAABB_CCDD);
        memory.put32(4, 0x1122_3344);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_09CF); // vld2.32 {d0[1],d2[1]}, [r1]  (stride 2)
        assertEquals(0xAABB_CCDD_0000_0000L, core.vfp().d(0));
        assertEquals(0x1122_3344_0000_0000L, core.vfp().d(2));
        assertEquals(0L, core.vfp().d(1)); // não tocado
    }

    @Test
    void vld1rReplicatesByteAcrossAllLanes() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0x0000_005A);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0C0F); // vld1.8 {d0[]}, [r1]
        assertEquals(0x5A5A_5A5A_5A5A_5A5AL, core.vfp().d(0));
    }

    @Test
    void vld1rReplicatesHalfword() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x1234);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0C4F); // vld1.16 {d0[]}, [r1]
        assertEquals(0x1234_1234_1234_1234L, core.vfp().d(0));
    }

    @Test
    void vld1rReplicatesWord() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xDEAD_BEEF);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0C8F); // vld1.32 {d0[]}, [r1]
        assertEquals(0xDEAD_BEEF_DEAD_BEEFL, core.vfp().d(0));
    }

    @Test
    void vld1rWithTBitReplicatesIntoTwoRegisters() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0x0000_0077);
        ArmCore core = newCore(memory);
        core.vfp().setD(1, 0xDEAD_DEAD_DEAD_DEADL);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0C2F); // vld1.8 {d0[],d1[]}, [r1]
        assertEquals(0x7777_7777_7777_7777L, core.vfp().d(0));
        assertEquals(0x7777_7777_7777_7777L, core.vfp().d(1));
    }

    @Test
    void vld2rReplicatesIntoTwoRegisters() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x2010);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0D0F); // vld2.8 {d0[],d1[]}, [r1]
        assertEquals(0x1010_1010_1010_1010L, core.vfp().d(0));
        assertEquals(0x2020_2020_2020_2020L, core.vfp().d(1));
    }

    @Test
    void vld4rReplicatesFourHalfwordRegisters() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x0001);
        memory.put16(2, 0x0002);
        memory.put16(4, 0x0003);
        memory.put16(6, 0x0004);
        ArmCore core = newCore(memory);
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0F4F); // vld4.16 {d0[],d1[],d2[],d3[]}, [r1]
        assertEquals(0x0001_0001_0001_0001L, core.vfp().d(0));
        assertEquals(0x0002_0002_0002_0002L, core.vfp().d(1));
        assertEquals(0x0003_0003_0003_0003L, core.vfp().d(2));
        assertEquals(0x0004_0004_0004_0004L, core.vfp().d(3));
    }

    @Test
    void allLanesImmediateWriteback() {
        ArmCore core = newCore(bytePatternMemory(32));
        core.setRegister(1, 0x00);
        run(core, 0xF4A1_0C4D); // vld1.16 {d0[]}, [r1]!  -> 1*2 = 2 bytes
        assertEquals(0x02, core.register(1));
    }
}
