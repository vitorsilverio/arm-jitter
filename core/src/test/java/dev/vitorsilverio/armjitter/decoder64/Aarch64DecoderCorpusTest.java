package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Oráculo de decode (G1, aplicado à fatia B6.1): `corpus.bin` é o binário REAL produzido por
/// `aarch64-none-elf-as`/`objdump` (devkitA64) a partir de `corpus.s` — ver os dois arquivos
/// versionados em `src/test/resources/aarch64/` e a disassembly de referência em
/// `corpus.objdump.txt`. Este teste decodifica cada palavra do corpus com {@link Aarch64Decoder} e
/// compara campo a campo contra o que o assembler/disassembler real produziu — nenhum encoding é
/// inventado à mão.
class Aarch64DecoderCorpusTest {
    private static AddressSpace64 memory;
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    @BeforeAll
    static void loadCorpus() throws IOException {
        byte[] bytes;
        try (InputStream in = Aarch64DecoderCorpusTest.class.getResourceAsStream("/aarch64/corpus.bin")) {
            assertNotNull(in, "corpus.bin ausente em src/test/resources/aarch64/");
            bytes = in.readAllBytes();
        }
        TestAddressSpace raw = new TestAddressSpace(bytes.length);
        for (int i = 0; i + 3 < bytes.length; i += 4) {
            int word = (bytes[i] & 0xFF) | ((bytes[i + 1] & 0xFF) << 8)
                    | ((bytes[i + 2] & 0xFF) << 16) | ((bytes[i + 3] & 0xFF) << 24);
            raw.put32(i, word);
        }
        memory = AddressSpace64.wrapping(raw);
    }

    // Offsets e mnemônicos correspondem exatamente a `corpus.objdump.txt`.

    @Test
    void adrX0Self() {
        Ir64Op.PcRelative op = (Ir64Op.PcRelative) DECODER.decode(memory, 0x00);
        assertEquals(0, op.dst());
        assertEquals(0x00L, op.instructionAddress());
        assertEquals(0L, op.immediate());
        assertFalse(op.page());
    }

    @Test
    void adrX1ToLabel1() {
        Ir64Op.PcRelative op = (Ir64Op.PcRelative) DECODER.decode(memory, 0x04);
        assertEquals(1, op.dst());
        assertEquals(0x90L - 0x04L, op.immediate());
        assertFalse(op.page());
    }

    @Test
    void adrpAligns4KiB() {
        // adrp x2, _start (offset 0x08): _start=0 está na mesma página 4KiB que a instrução
        // (0x08 & ~0xFFF == 0), então o resultado esperado é 0 — mas o campo importante deste
        // vetor é justamente que o executor (não o decoder) faz esse alinhamento; aqui só
        // verificamos que o decoder marca `page=true` e não pré-alinha `instructionAddress`.
        Ir64Op.PcRelative op = (Ir64Op.PcRelative) DECODER.decode(memory, 0x08);
        assertEquals(2, op.dst());
        assertTrue(op.page());
        assertEquals(0x08L, op.instructionAddress());
        assertEquals(0L, op.immediate());
    }

    @Test
    void addImmediate() {
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x10);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(4, op.dst());
        assertEquals(5, op.src1());
        assertEquals(0x123L, op.immediate());
        assertTrue(op.wide());
        assertFalse(op.setFlags());
        assertTrue(op.dstIsStackPointer());
        assertTrue(op.src1IsStackPointer());
    }

    @Test
    void addImmediateLsl12() {
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x14);
        assertEquals(0x123000L, op.immediate());
    }

    @Test
    void addsClearsDstStackPointerVariant() {
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x18);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(6, op.dst());
        assertEquals(7, op.src1());
        assertEquals(0L, op.immediate());
        assertTrue(op.setFlags());
        // ADDS: destino NUNCA é SP (mesmo Rd=31 seria ZR); Rn continua Rn|SP.
        assertFalse(op.dstIsStackPointer());
        assertTrue(op.src1IsStackPointer());
    }

    @Test
    void subImmediate() {
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x20);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(8, op.dst());
        assertEquals(9, op.src1());
        assertEquals(7L, op.immediate());
        assertTrue(op.wide());
    }

    @Test
    void addImmediate32Bit() {
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x2c);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertFalse(op.wide());
        assertEquals(12, op.dst());
        assertEquals(13, op.src1());
        assertEquals(5L, op.immediate());
    }

    @Test
    void movzComposesLowHalf() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x34);
        assertEquals(Ir64MoveWideOp.MOVZ, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(0x1234, op.immediate16());
        assertEquals(0, op.shift());
        assertTrue(op.wide());
    }

    @Test
    void movzShift16() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x38);
        assertEquals(16, op.shift());
    }

    @Test
    void movzShift32() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x3c);
        assertEquals(32, op.shift());
    }

    @Test
    void movzShift48() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x40);
        assertEquals(48, op.shift());
    }

    @Test
    void movn() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x44);
        assertEquals(Ir64MoveWideOp.MOVN, op.opcode());
        assertEquals(1, op.dst());
        assertEquals(0xabcd, op.immediate16());
    }

    @Test
    void movkComposes64BitAddress() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x48);
        assertEquals(Ir64MoveWideOp.MOVK, op.opcode());
        assertEquals(2, op.dst());
        assertEquals(0x5678, op.immediate16());
        assertEquals(16, op.shift());
        assertTrue(op.wide());
    }

    @Test
    void movz32Bit() {
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x4c);
        assertEquals(3, op.dst());
        assertFalse(op.wide());
    }

    @Test
    void movzXzrAsDestination() {
        // movz xzr, #1 (offset 0x50): decoder ainda copia o campo cru Rd=31; é o EXECUTOR quem
        // descarta a escrita — ver Aarch64BlockExecutorTest.
        Ir64Op.MoveWide op = (Ir64Op.MoveWide) DECODER.decode(memory, 0x50);
        assertEquals(31, op.dst());
        assertEquals(1, op.immediate16());
    }

    @Test
    void unconditionalBranchB() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x54);
        assertEquals(Ir64BranchForm.IMMEDIATE, op.form());
        assertEquals(0x90L, op.target());
        assertFalse(op.link());
        assertEquals(Ir64Condition.AL, op.condition());
    }

    @Test
    void unconditionalBranchBl() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x58);
        assertEquals(0x90L, op.target());
        assertTrue(op.link());
    }

    @Test
    void conditionalBranchEq() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x5c);
        assertEquals(0x90L, op.target());
        assertEquals(Ir64Condition.EQ, op.condition());
        assertFalse(op.link());
    }

    @Test
    void conditionalBranchNe() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x60);
        assertEquals(Ir64Condition.NE, op.condition());
    }

    @Test
    void conditionalBranchGe() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x64);
        assertEquals(Ir64Condition.GE, op.condition());
    }

    @Test
    void cbzWide() {
        Ir64Op.CompareBranch64 op = (Ir64Op.CompareBranch64) DECODER.decode(memory, 0x68);
        assertEquals(Ir64CompareBranchForm.CBZ_CBNZ, op.form());
        assertEquals(0, op.rn());
        assertTrue(op.wide());
        assertFalse(op.branchIfNonZero());
        assertEquals(0x90L, op.target());
    }

    @Test
    void cbnzWide() {
        Ir64Op.CompareBranch64 op = (Ir64Op.CompareBranch64) DECODER.decode(memory, 0x6c);
        assertTrue(op.branchIfNonZero());
    }

    @Test
    void cbzNarrowW() {
        // cbz w1, label1 (offset 0x70): forma W (`wide=false`).
        Ir64Op.CompareBranch64 op = (Ir64Op.CompareBranch64) DECODER.decode(memory, 0x70);
        assertEquals(1, op.rn());
        assertFalse(op.wide());
    }

    @Test
    void tbzLowBit() {
        Ir64Op.CompareBranch64 op = (Ir64Op.CompareBranch64) DECODER.decode(memory, 0x74);
        assertEquals(Ir64CompareBranchForm.TBZ_TBNZ, op.form());
        assertEquals(2, op.rn());
        assertEquals(5, op.bitPosition());
        assertFalse(op.branchIfNonZero());
        assertEquals(0x90L, op.target());
    }

    @Test
    void tbnzHighBit() {
        // tbnz x2, #40, label1 (offset 0x78): posição >= 32 exige o bit `b5` do encoding.
        Ir64Op.CompareBranch64 op = (Ir64Op.CompareBranch64) DECODER.decode(memory, 0x78);
        assertEquals(2, op.rn());
        assertEquals(40, op.bitPosition());
        assertTrue(op.branchIfNonZero());
    }

    @Test
    void branchRegisterBr() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x7c);
        assertEquals(Ir64BranchForm.REGISTER, op.form());
        assertEquals(9, op.registerOperand());
        assertFalse(op.link());
    }

    @Test
    void branchRegisterBlr() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x80);
        assertEquals(10, op.registerOperand());
        assertTrue(op.link());
    }

    @Test
    void ret() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x84);
        assertEquals(Ir64BranchForm.REGISTER, op.form());
        assertEquals(30, op.registerOperand());
        assertFalse(op.link());
    }

    @Test
    void retExplicitRegister() {
        Ir64Op.Branch64 op = (Ir64Op.Branch64) DECODER.decode(memory, 0x88);
        assertEquals(11, op.registerOperand());
    }

    @Test
    void svc() {
        Ir64Op.Svc op = (Ir64Op.Svc) DECODER.decode(memory, 0x8c);
        assertEquals(0x1234, op.immediate());
    }

    @Test
    void unsupportedEncodingThrows() {
        // O nop no offset 0x90 (label1) é uma "hint" do grupo System instructions — fora da
        // fatia B6.1 (não é ADR/ADD/MOVZ/branch/SVC).
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x90));
    }
}
