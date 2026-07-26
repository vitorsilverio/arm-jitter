package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
// Ir64Op.MultiplyAccumulate/Ir64Op.Divide (B6.3.3) são referenciados via Ir64Op.* (mesmo padrão
// já usado neste arquivo para os demais subtipos aninhados).
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
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

    // ── B6.2: loads/stores (offsets 0x94+, apêndice do mesmo corpus.s/objdump.txt) ──────────

    @Test
    void ldrXUnsignedOffset() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x94);
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertFalse(op.signExtend());
        assertTrue(op.wide());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
    }

    @Test
    void ldrXUnsignedOffsetScaledBy8() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x98);
        assertEquals(16L, op.immediate(), "imm12=2 escalado por 8 (tamanho X)");
    }

    @Test
    void strXUnsignedOffset() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x9c);
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertTrue(op.wide());
        assertEquals(16L, op.immediate());
    }

    @Test
    void ldrW32Bit() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xa0);
        assertEquals(Ir64MemSize.WORD, op.size());
        assertFalse(op.wide());
        assertFalse(op.signExtend());
        assertEquals(8L, op.immediate(), "imm12=2 escalado por 4 (tamanho W)");
    }

    @Test
    void ldrbZeroExtend() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xa4);
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertFalse(op.signExtend());
        assertFalse(op.wide());
        assertEquals(1L, op.immediate());
    }

    @Test
    void ldrhZeroExtend() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xa8);
        assertEquals(Ir64MemSize.HALF, op.size());
        assertFalse(op.signExtend());
        assertEquals(2L, op.immediate());
    }

    @Test
    void ldrsbSignExtendToX() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xac);
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertTrue(op.signExtend());
        assertTrue(op.wide());
    }

    @Test
    void ldrsbSignExtendToW() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xb0);
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertTrue(op.signExtend());
        assertFalse(op.wide());
    }

    @Test
    void ldrshSignExtendToX() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xb4);
        assertEquals(Ir64MemSize.HALF, op.size());
        assertTrue(op.signExtend());
        assertTrue(op.wide());
    }

    @Test
    void ldrshSignExtendToW() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xb8);
        assertEquals(Ir64MemSize.HALF, op.size());
        assertTrue(op.signExtend());
        assertFalse(op.wide());
    }

    @Test
    void ldrswSignExtendToXOnly() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xbc);
        assertEquals(Ir64MemSize.WORD, op.size());
        assertTrue(op.signExtend());
        assertTrue(op.wide());
    }

    @Test
    void ldurUnscaledNegativeOffset() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xc0);
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(-8L, op.immediate(), "LDUR/STUR: imm9 cru, sem escala");
    }

    @Test
    void sturUnscaledNegativeOffset() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0xc4);
        assertEquals(-8L, op.immediate());
    }

    @Test
    void ldurWordUnscaled() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xc8);
        assertEquals(Ir64MemSize.WORD, op.size());
        assertEquals(-4L, op.immediate());
        assertFalse(op.signExtend());
    }

    @Test
    void ldrPreIndex() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xcc);
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void strPreIndex() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0xd0);
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void ldrPostIndex() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xd4);
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void strPostIndex() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0xd8);
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void ldrbPostIndexByte() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xdc);
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(1L, op.immediate());
    }

    @Test
    void ldrRegisterOffsetPlainLsl() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xe0);
        assertEquals(Ir64AddressingMode.REGISTER_OFFSET, op.addressingMode());
        assertEquals(6, op.rm());
        assertEquals(Ir64ExtendType.LSL, op.extendType());
        assertEquals(0, op.shiftAmount());
    }

    @Test
    void ldrRegisterOffsetLsl3() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xe4);
        assertEquals(Ir64ExtendType.LSL, op.extendType());
        assertEquals(3, op.shiftAmount());
    }

    @Test
    void ldrRegisterOffsetSxtwNoShift() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xe8);
        assertEquals(Ir64ExtendType.SXTW, op.extendType());
        assertEquals(0, op.shiftAmount());
    }

    @Test
    void ldrRegisterOffsetSxtwShift3() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xec);
        assertEquals(Ir64ExtendType.SXTW, op.extendType());
        assertEquals(3, op.shiftAmount());
    }

    @Test
    void ldrRegisterOffsetSxtxShift3() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xf0);
        assertEquals(Ir64ExtendType.SXTX, op.extendType());
        assertEquals(3, op.shiftAmount());
    }

    @Test
    void ldrWordRegisterOffsetUxtwShift2() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0xf4);
        assertFalse(op.wide());
        assertEquals(Ir64ExtendType.UXTW, op.extendType());
        assertEquals(2, op.shiftAmount());
    }

    @Test
    void stpPreIndexDoubleword() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0xf8);
        assertFalse(op.load());
        assertEquals(29, op.rt());
        assertEquals(30, op.rt2());
        assertEquals(31, op.rn());
        assertTrue(op.wide());
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(-16L, op.immediate());
    }

    @Test
    void ldpPostIndexDoubleword() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0xfc);
        assertTrue(op.load());
        assertEquals(29, op.rt());
        assertEquals(30, op.rt2());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(16L, op.immediate());
    }

    @Test
    void stpSignedOffset() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x100);
        assertFalse(op.load());
        assertEquals(0, op.rt());
        assertEquals(1, op.rt2());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(16L, op.immediate());
    }

    @Test
    void ldpWordSignedOffset() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x104);
        assertTrue(op.load());
        assertFalse(op.wide());
        assertEquals(8L, op.immediate(), "imm7=2 escalado por 4 (par de 32 bits)");
    }

    @Test
    void ldpPreIndexDoubleword() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x108);
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(16L, op.immediate());
    }

    @Test
    void stpPostIndexDoubleword() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x10c);
        assertFalse(op.load());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(16L, op.immediate());
    }

    @Test
    void ldrLiteralResolvesAbsoluteAddress() {
        Ir64Op.LoadLiteral64 op = (Ir64Op.LoadLiteral64) DECODER.decode(memory, 0x110);
        assertEquals(7, op.rt());
        assertEquals(0x114L, op.address(), "instructionAddress(0x110) + imm19*4 -> litlabel(0x114)");
        assertTrue(op.wide());
        assertFalse(op.signExtend());
    }

    // ── B6.3.1: logical (immediate) — apêndice do mesmo corpus.s/objdump.txt, offsets 0x118+ ──

    @Test
    void andImmediateSingleBitElement64() {
        // and x0, x1, #0x1: elemento de 64 bits (N=1), um único bit setado.
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x118);
        assertEquals(Ir64AluOp.AND, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(0x1L, op.immediate());
        assertTrue(op.wide());
        assertFalse(op.setFlags());
        assertFalse(op.dstIsStackPointer(), "AND (imediato) nunca tem forma SP");
        assertFalse(op.src1IsStackPointer());
    }

    @Test
    void orrImmediateReplicatedPattern() {
        // orr x2, x3, #0x5555555555555555: elemento de 2 bits (01), replicado.
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x11c);
        assertEquals(Ir64AluOp.ORR, op.opcode());
        assertEquals(2, op.dst());
        assertEquals(3, op.src1());
        assertEquals(0x5555555555555555L, op.immediate());
    }

    @Test
    void eorImmediateElement4Bits() {
        // eor x4, x5, #0x1111111111111111: elemento de 4 bits (0001), replicado.
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x120);
        assertEquals(Ir64AluOp.EOR, op.opcode());
        assertEquals(4, op.dst());
        assertEquals(5, op.src1());
        assertEquals(0x1111111111111111L, op.immediate());
    }

    @Test
    void andsImmediateSetsFlagsAndNonTrivialRotation() {
        // ands x6, x7, #0xfffffffffffffffe: corrida de 63 uns rotacionada por 1 (immr != 0).
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x124);
        assertEquals(Ir64AluOp.AND, op.opcode());
        assertEquals(6, op.dst());
        assertEquals(7, op.src1());
        assertEquals(0xffff_ffff_ffff_fffeL, op.immediate());
        assertTrue(op.setFlags());
    }

    @Test
    void andImmediate32BitSmallElement() {
        // and w8, w9, #0xaaaaaaaa: elemento de 2 bits (10), forma W. O decoder produz o padrão
        // replicado até os 64 bits (mesma convenção do "wmask" do manual/QEMU, não truncado por
        // largura aqui) — o EXECUTOR (logicalWithFlags) é quem aplica a máscara de 32 bits no
        // resultado final; os 32 bits baixos de `immediate()` já são o valor 0xaaaaaaaa esperado.
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x128);
        assertEquals(8, op.dst());
        assertEquals(9, op.src1());
        assertEquals(0xaaaa_aaaa_aaaa_aaaaL, op.immediate());
        assertEquals(0xaaaa_aaaaL, op.immediate() & 0xFFFF_FFFFL, "baixos 32 bits = imediato W esperado");
        assertFalse(op.wide());
    }

    @Test
    void andsImmediate32BitNonTrivialRotation() {
        // ands w10, w11, #0x80000001: corrida de 2 uns (bits 31 e 0) rotacionada dentro de 32 bits.
        Ir64Op.Alu64 op = (Ir64Op.Alu64) DECODER.decode(memory, 0x12c);
        assertEquals(10, op.dst());
        assertEquals(11, op.src1());
        assertEquals(0x8000_0001_8000_0001L, op.immediate());
        assertEquals(0x8000_0001L, op.immediate() & 0xFFFF_FFFFL, "baixos 32 bits = imediato W esperado");
        assertFalse(op.wide());
        assertTrue(op.setFlags());
    }

    @Test
    void logicalImmediateNReservedWithNarrowWidthThrows() {
        // N=1 com sf=0: UNDEFINED (Fatos de referência #2 da task B6.3.1) — não existe assembly
        // válido para isso (o assembler real nunca emite essa combinação), então o vetor é
        // construído à mão a partir do formato, mesmo precedente de outros testes de reservado
        // neste projeto (ex. Ir64BlockExecutorTest#cbzNarrowIgnoresHighBits).
        int word = (0 << 31) /* sf=0 */ | (0b00 << 29) /* opc=AND */ | (0b100100 << 23)
                | (1 << 22) /* N=1 */ | (0 << 16) /* immr */ | (0 << 10) /* imms */
                | (1 << 5) /* Rn */ | 0 /* Rd */;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B6.3.1: ALU shifted register — offsets 0x130+ ──────────────────────────────────────

    @Test
    void addShiftedRegisterLslZero() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x130);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertEquals(Ir64ShiftType.LSL, op.shiftType());
        assertEquals(0, op.shiftAmount());
        assertTrue(op.wide());
        assertFalse(op.setFlags());
    }

    @Test
    void addShiftedRegisterLslNonZero() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x134);
        assertEquals(Ir64ShiftType.LSL, op.shiftType());
        assertEquals(4, op.shiftAmount());
    }

    @Test
    void subShiftedRegisterLsr() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x138);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(3, op.dst());
        assertEquals(4, op.src1());
        assertEquals(5, op.src2());
        assertEquals(Ir64ShiftType.LSR, op.shiftType());
        assertEquals(6, op.shiftAmount());
        assertFalse(op.setFlags());
    }

    @Test
    void subsShiftedRegisterAsrSetsFlags() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x13c);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(Ir64ShiftType.ASR, op.shiftType());
        assertEquals(8, op.shiftAmount());
        assertTrue(op.setFlags());
    }

    @Test
    void addsShiftedRegister32BitLslZero() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x140);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertFalse(op.wide());
        assertTrue(op.setFlags());
        assertEquals(9, op.dst());
        assertEquals(10, op.src1());
        assertEquals(11, op.src2());
    }

    @Test
    void addShiftedRegister32BitLsr() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x144);
        assertFalse(op.wide());
        assertEquals(Ir64ShiftType.LSR, op.shiftType());
        assertEquals(3, op.shiftAmount());
    }

    @Test
    void subShiftedRegister32BitAsr() {
        Ir64Op.AluShiftedRegister op = (Ir64Op.AluShiftedRegister) DECODER.decode(memory, 0x148);
        assertFalse(op.wide());
        assertEquals(Ir64ShiftType.ASR, op.shiftType());
        assertEquals(7, op.shiftAmount());
    }

    @Test
    void addShiftedRegisterRorReservedThrows() {
        // st=11 (ROR) é reservado para ADD/SUB (Fatos de referência #4) — construído à mão, sem
        // assembly válido correspondente.
        int word = 0x8b020020 | (0b11 << 22); // pega "add x0,x1,x2" e força st=11
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void addShiftedRegisterNarrowWidthAmountBit5SetThrows() {
        // sf=0 com quantidade >= 32 (bit5 do imm6 setado) é UNDEFINED (Fatos de referência #4).
        int word = 0x0b4e0dac | (1 << 15); // pega "add w12,w13,w14,lsr#3" e seta o bit5 do imm6
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B6.3.1: ALU extended register — offsets 0x14c+ ─────────────────────────────────────

    @Test
    void addExtendedRegisterUxtb() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x14c);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertEquals(Ir64AluExtendType.UXTB, op.extendType());
        assertEquals(0, op.shiftAmount());
        assertTrue(op.wide());
        assertFalse(op.setFlags());
        assertTrue(op.dstIsStackPointer(), "ADD sem S: Rd|SP (checado por índice no executor)");
    }

    @Test
    void addExtendedRegisterUxth() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x150);
        assertEquals(Ir64AluExtendType.UXTH, op.extendType());
    }

    @Test
    void addExtendedRegisterUxtw() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x154);
        assertEquals(Ir64AluExtendType.UXTW, op.extendType());
    }

    @Test
    void addExtendedRegisterUxtx() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x158);
        assertEquals(Ir64AluExtendType.UXTX, op.extendType());
    }

    @Test
    void addExtendedRegisterSxtb() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x15c);
        assertEquals(Ir64AluExtendType.SXTB, op.extendType());
    }

    @Test
    void addExtendedRegisterSxth() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x160);
        assertEquals(Ir64AluExtendType.SXTH, op.extendType());
    }

    @Test
    void addExtendedRegisterSxtw() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x164);
        assertEquals(Ir64AluExtendType.SXTW, op.extendType());
    }

    @Test
    void addExtendedRegisterSxtx() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x168);
        assertEquals(Ir64AluExtendType.SXTX, op.extendType());
    }

    @Test
    void addExtendedRegisterSpAsRnAndRdNoShift() {
        // add sp, sp, x1: Rn=31 (SP) e Rd=31 (SP, permitido pois é ADD sem S).
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x16c);
        assertEquals(31, op.dst());
        assertEquals(31, op.src1());
        assertEquals(1, op.src2());
        assertEquals(0, op.shiftAmount());
        assertTrue(op.dstIsStackPointer());
    }

    @Test
    void addExtendedRegisterSpWithShiftAmount() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x170);
        assertEquals(31, op.dst());
        assertEquals(31, op.src1());
        assertEquals(3, op.shiftAmount());
    }

    @Test
    void addExtendedRegisterSpAsRnOnly() {
        // add sp, x4, x5: Rd=31 (SP), Rn=4 (normal) — prova que dstIsStackPointer não implica
        // src1 também é SP (são checados de forma independente pelo executor).
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x174);
        assertEquals(31, op.dst());
        assertEquals(4, op.src1());
        assertEquals(5, op.src2());
        assertTrue(op.dstIsStackPointer());
    }

    @Test
    void addsExtendedRegisterSpAsRnDstNeverStackPointer() {
        // adds x2, sp, x3: Rn=31 (SP), mas dstIsStackPointer é false (ADDS sempre tem destino
        // normal, nunca SP, mesmo se o índice fosse 31).
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x178);
        assertEquals(2, op.dst());
        assertEquals(31, op.src1());
        assertEquals(3, op.src2());
        assertTrue(op.setFlags());
        assertFalse(op.dstIsStackPointer());
    }

    @Test
    void addsExtendedRegisterXzrDestinationIsNormalNotStackPointer() {
        // adds xzr, x1, x2, uxtx (disassembla como "cmn"): Rd=31, mas é XZR normal (setFlags=true
        // implica dstIsStackPointer=false) — o resultado é descartado, não vai para SP.
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x17c);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(31, op.dst());
        assertTrue(op.setFlags());
        assertFalse(op.dstIsStackPointer());
    }

    @Test
    void subsExtendedRegisterXzrDestination() {
        // subs xzr, x1, x2, uxtx (disassembla como "cmp").
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x180);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(31, op.dst());
        assertTrue(op.setFlags());
        assertFalse(op.dstIsStackPointer());
    }

    @Test
    void addExtendedRegister32BitWithShiftAmount() {
        Ir64Op.AluExtendedRegister op = (Ir64Op.AluExtendedRegister) DECODER.decode(memory, 0x184);
        assertFalse(op.wide());
        assertEquals(Ir64AluExtendType.UXTB, op.extendType());
        assertEquals(2, op.shiftAmount());
    }

    @Test
    void addExtendedRegisterShiftAmountAboveMaxThrows() {
        // sa > 4 é UNDEFINED (Fatos de referência #5) — campo tem 3 bits (cabe até 7), mas 5-7
        // são reservados; construído à mão, sem assembly válido correspondente.
        int word = 0x8b220020 | (5 << 10); // pega "add x0,x1,w2,uxtb" e força sa=5
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B6.3.2: CSEL/CSINC/CSINV/CSNEG (+ aliases) — offsets 0x188+ ────────────────────────────

    @Test
    void csel() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x188);
        assertEquals(Ir64ConditionalSelectOp.CSEL, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertTrue(op.wide());
        assertEquals(Ir64Condition.EQ, op.condition());
    }

    @Test
    void csinc() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x18c);
        assertEquals(Ir64ConditionalSelectOp.CSINC, op.opcode());
        assertEquals(3, op.dst());
        assertEquals(4, op.src1());
        assertEquals(5, op.src2());
        assertEquals(Ir64Condition.NE, op.condition());
    }

    @Test
    void csinv() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x190);
        assertEquals(Ir64ConditionalSelectOp.CSINV, op.opcode());
        assertEquals(6, op.dst());
        assertEquals(7, op.src1());
        assertEquals(8, op.src2());
        assertEquals(Ir64Condition.CS, op.condition());
    }

    @Test
    void csneg() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x194);
        assertEquals(Ir64ConditionalSelectOp.CSNEG, op.opcode());
        assertEquals(9, op.dst());
        assertEquals(10, op.src1());
        assertEquals(11, op.src2());
        assertEquals(Ir64Condition.CC, op.condition());
    }

    @Test
    void cselNarrowW() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x198);
        assertFalse(op.wide());
        assertEquals(20, op.dst());
        assertEquals(21, op.src1());
        assertEquals(22, op.src2());
        assertEquals(Ir64Condition.GT, op.condition());
    }

    @Test
    void csetAliasIsCsincWithXzrOperandsAndInvertedCondition() {
        // cset x12, eq: alias de CSINC x12, xzr, xzr, invert(eq)=ne — o assembler já inverteu a
        // condição e igualou src1==src2==31; o decoder não reconhece o alias, só produz o
        // ConditionalSelect genérico com esses campos (Fatos de referência #1 da task).
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x19c);
        assertEquals(Ir64ConditionalSelectOp.CSINC, op.opcode());
        assertEquals(12, op.dst());
        assertEquals(31, op.src1());
        assertEquals(31, op.src2());
        assertEquals(Ir64Condition.NE, op.condition());
    }

    @Test
    void csetmAliasIsCsinvWithXzrOperands() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x1a0);
        assertEquals(Ir64ConditionalSelectOp.CSINV, op.opcode());
        assertEquals(13, op.dst());
        assertEquals(31, op.src1());
        assertEquals(31, op.src2());
        assertEquals(Ir64Condition.EQ, op.condition());
    }

    @Test
    void cincAliasIsCsincWithMatchingSrc1Src2() {
        // cinc x14, x15, eq: alias de CSINC x14, x15, x15, invert(eq)=ne.
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x1a4);
        assertEquals(Ir64ConditionalSelectOp.CSINC, op.opcode());
        assertEquals(14, op.dst());
        assertEquals(15, op.src1());
        assertEquals(15, op.src2());
        assertEquals(Ir64Condition.NE, op.condition());
    }

    @Test
    void cinvAliasIsCsinvWithMatchingSrc1Src2() {
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x1a8);
        assertEquals(Ir64ConditionalSelectOp.CSINV, op.opcode());
        assertEquals(16, op.dst());
        assertEquals(17, op.src1());
        assertEquals(17, op.src2());
        assertEquals(Ir64Condition.EQ, op.condition());
    }

    @Test
    void cnegAliasIsCsnegWithMatchingSrc1Src2() {
        // cneg x18, x19, eq: alias de CSNEG x18, x19, x19, invert(eq)=ne.
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x1ac);
        assertEquals(Ir64ConditionalSelectOp.CSNEG, op.opcode());
        assertEquals(18, op.dst());
        assertEquals(19, op.src1());
        assertEquals(19, op.src2());
        assertEquals(Ir64Condition.NE, op.condition());
    }

    // ── B6.3.2: SBFM/UBFM/BFM (+ aliases) — offsets 0x1b0+ ─────────────────────────────────────

    @Test
    void sbfxAliasImmsGreaterEqualImmr() {
        // sbfm x0, x1, #4, #10 (disassembla como "sbfx x0, x1, #4, #7"): si=10 >= ri=4.
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1b0);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src());
        assertEquals(4, op.immr());
        assertEquals(10, op.imms());
        assertTrue(op.wide());
    }

    @Test
    void sbfizAliasImmsLessThanImmr() {
        // sbfm x2, x3, #10, #4 (disassembla como "sbfiz x2, x3, #54, #5"): si=4 < ri=10 — os
        // campos crus decodificados continuam immr=10/imms=4, sem pré-cálculo (D2 da task).
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1b4);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(2, op.dst());
        assertEquals(3, op.src());
        assertEquals(10, op.immr());
        assertEquals(4, op.imms());
    }

    @Test
    void ubfxAliasImmsGreaterEqualImmr() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1b8);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(4, op.dst());
        assertEquals(5, op.src());
        assertEquals(4, op.immr());
        assertEquals(10, op.imms());
    }

    @Test
    void ubfizAliasImmsLessThanImmr() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1bc);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(6, op.dst());
        assertEquals(7, op.src());
        assertEquals(10, op.immr());
        assertEquals(4, op.imms());
    }

    @Test
    void bfxilAliasImmsGreaterEqualImmr() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1c0);
        assertEquals(Ir64BitfieldOp.BFM, op.opcode());
        assertEquals(8, op.dst());
        assertEquals(9, op.src());
        assertEquals(4, op.immr());
        assertEquals(10, op.imms());
    }

    @Test
    void bfiAliasImmsLessThanImmr() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1c4);
        assertEquals(Ir64BitfieldOp.BFM, op.opcode());
        assertEquals(10, op.dst());
        assertEquals(11, op.src());
        assertEquals(10, op.immr());
        assertEquals(4, op.imms());
    }

    @Test
    void lslAliasIsUbfmWithComplementImmr() {
        // lsl x12, x13, #5 = UBFM x12, x13, #(-5 mod 64)=59, #(63-5)=58.
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1c8);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(12, op.dst());
        assertEquals(13, op.src());
        assertEquals(59, op.immr());
        assertEquals(58, op.imms());
    }

    @Test
    void lsrAliasIsUbfmWithShiftAsImmr() {
        // lsr x14, x15, #5 = UBFM x14, x15, #5, #63.
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1cc);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(14, op.dst());
        assertEquals(15, op.src());
        assertEquals(5, op.immr());
        assertEquals(63, op.imms());
    }

    @Test
    void asrAliasIsSbfmWithShiftAsImmr() {
        // asr x16, x17, #5 = SBFM x16, x17, #5, #63.
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1d0);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(16, op.dst());
        assertEquals(17, op.src());
        assertEquals(5, op.immr());
        assertEquals(63, op.imms());
    }

    @Test
    void ubfxExplicitLsbWidth() {
        // ubfx x18, x19, #8, #16 = UBFM x18, x19, #8, #(8+16-1)=23.
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1d4);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(18, op.dst());
        assertEquals(19, op.src());
        assertEquals(8, op.immr());
        assertEquals(23, op.imms());
    }

    @Test
    void sbfxExplicitLsbWidth() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1d8);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(20, op.dst());
        assertEquals(21, op.src());
        assertEquals(8, op.immr());
        assertEquals(23, op.imms());
    }

    @Test
    void bfiExplicitLsbWidth() {
        // bfi x22, x23, #8, #16 = BFM x22, x23, #(-8 mod 64)=56, #(16-1)=15.
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1dc);
        assertEquals(Ir64BitfieldOp.BFM, op.opcode());
        assertEquals(22, op.dst());
        assertEquals(23, op.src());
        assertEquals(56, op.immr());
        assertEquals(15, op.imms());
    }

    @Test
    void bfxilExplicitLsbWidth() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1e0);
        assertEquals(Ir64BitfieldOp.BFM, op.opcode());
        assertEquals(24, op.dst());
        assertEquals(25, op.src());
        assertEquals(8, op.immr());
        assertEquals(23, op.imms());
    }

    @Test
    void uxtbAliasIsUbfmNarrowZeroToSeven() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1e4);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(26, op.dst());
        assertEquals(27, op.src());
        assertEquals(0, op.immr());
        assertEquals(7, op.imms());
        assertFalse(op.wide());
    }

    @Test
    void uxthAliasIsUbfmNarrowZeroToFifteen() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1e8);
        assertEquals(Ir64BitfieldOp.UBFM, op.opcode());
        assertEquals(28, op.dst());
        assertEquals(29, op.src());
        assertEquals(0, op.immr());
        assertEquals(15, op.imms());
        assertFalse(op.wide());
    }

    @Test
    void sxtbAliasIsSbfmWideZeroToSeven() {
        // sxtb x30, w0: destino X implica encoding sf=1 mesmo a fonte sendo "w0" na sintaxe —
        // SXTB Xd,Wn é alias de SBFM Xd,Xn,#0,#7 (mesmo índice de registrador, visão X completa).
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1ec);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(30, op.dst());
        assertEquals(0, op.src());
        assertEquals(0, op.immr());
        assertEquals(7, op.imms());
        assertTrue(op.wide());
    }

    @Test
    void sxthAliasIsSbfmWideZeroToFifteen() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1f0);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(1, op.dst());
        assertEquals(2, op.src());
        assertEquals(0, op.immr());
        assertEquals(15, op.imms());
        assertTrue(op.wide());
    }

    @Test
    void sxtwAliasIsSbfmWideZeroToThirtyOne() {
        Ir64Op.Bitfield op = (Ir64Op.Bitfield) DECODER.decode(memory, 0x1f4);
        assertEquals(Ir64BitfieldOp.SBFM, op.opcode());
        assertEquals(3, op.dst());
        assertEquals(4, op.src());
        assertEquals(0, op.immr());
        assertEquals(31, op.imms());
        assertTrue(op.wide());
    }

    @Test
    void csnegWithXzrAsSrc2() {
        // csneg x25, x26, xzr, eq: vetor real (assemblado, não inventado) para exercitar CSNEG
        // com src2=XZR na execução (ver Ir64BlockExecutorTest — CSNEG(XZR) dá 0, negação de zero).
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x1f8);
        assertEquals(Ir64ConditionalSelectOp.CSNEG, op.opcode());
        assertEquals(25, op.dst());
        assertEquals(26, op.src1());
        assertEquals(31, op.src2());
        assertEquals(Ir64Condition.EQ, op.condition());
    }

    @Test
    void bitfieldExtrOpcReservedThrows() {
        // opc=11 (EXTR, mesmo subgrupo, fora do escopo fechado do épico — ver Armadilhas da task
        // B6.3.2): construído à mão a partir do formato, sem assembly correspondente aqui.
        int word = 0x93442820 | (0b11 << 29); // pega "sbfx x0,x1,#4,#7" e força opc=11
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void bitfieldNReservedMismatchedWithSfThrows() {
        // N deve ser igual a sf (mesma regra de Logical (immediate)) — N=0 com sf=1 é UNDEFINED;
        // construído à mão a partir do formato, sem assembly válido correspondente.
        int word = 0x93442820 & ~(1 << 22); // pega "sbfx x0,x1,#4,#7" (sf=1) e força N=0
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B6.3.3: MADD/MSUB (+ MUL/MNEG aliases), SDIV/UDIV — offsets 0x1fc+ ─────────────────────

    @Test
    void madd() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x1fc);
        assertFalse(op.subtract());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertEquals(3, op.accumulator());
        assertTrue(op.wide());
    }

    @Test
    void msub() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x200);
        assertTrue(op.subtract());
        assertEquals(4, op.dst());
        assertEquals(5, op.src1());
        assertEquals(6, op.src2());
        assertEquals(7, op.accumulator());
        assertTrue(op.wide());
    }

    @Test
    void mulAliasIsMultiplyAccumulateWithXzrAccumulator() {
        // mul x8, x9, x10: alias sem case de decode dedicado — chega como MADD com accumulator=31
        // (XZR), ver Fatos de referência #1 da task.
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x204);
        assertFalse(op.subtract());
        assertEquals(8, op.dst());
        assertEquals(9, op.src1());
        assertEquals(10, op.src2());
        assertEquals(31, op.accumulator());
    }

    @Test
    void mnegAliasIsMultiplyAccumulateSubtractWithXzrAccumulator() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x208);
        assertTrue(op.subtract());
        assertEquals(11, op.dst());
        assertEquals(12, op.src1());
        assertEquals(13, op.src2());
        assertEquals(31, op.accumulator());
    }

    @Test
    void maddNarrowWidth() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x20c);
        assertFalse(op.subtract());
        assertEquals(14, op.dst());
        assertEquals(15, op.src1());
        assertEquals(16, op.src2());
        assertEquals(17, op.accumulator());
        assertFalse(op.wide());
    }

    @Test
    void msubNarrowWidth() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x210);
        assertTrue(op.subtract());
        assertEquals(18, op.dst());
        assertEquals(19, op.src1());
        assertEquals(20, op.src2());
        assertEquals(21, op.accumulator());
        assertFalse(op.wide());
    }

    @Test
    void mulAliasNarrowWidth() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x214);
        assertEquals(22, op.dst());
        assertEquals(23, op.src1());
        assertEquals(24, op.src2());
        assertEquals(31, op.accumulator());
        assertFalse(op.wide());
    }

    @Test
    void mnegAliasNarrowWidth() {
        Ir64Op.MultiplyAccumulate op =
                (Ir64Op.MultiplyAccumulate) DECODER.decode(memory, 0x218);
        assertTrue(op.subtract());
        assertEquals(25, op.dst());
        assertEquals(26, op.src1());
        assertEquals(27, op.src2());
        assertEquals(31, op.accumulator());
        assertFalse(op.wide());
    }

    @Test
    void sdivWide() {
        Ir64Op.Divide op =
                (Ir64Op.Divide) DECODER.decode(memory, 0x21c);
        assertTrue(op.signed());
        assertEquals(28, op.dst());
        assertEquals(29, op.src1());
        assertEquals(30, op.src2());
        assertTrue(op.wide());
    }

    @Test
    void udivWide() {
        Ir64Op.Divide op =
                (Ir64Op.Divide) DECODER.decode(memory, 0x220);
        assertFalse(op.signed());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertTrue(op.wide());
    }

    @Test
    void sdivNarrow() {
        Ir64Op.Divide op =
                (Ir64Op.Divide) DECODER.decode(memory, 0x224);
        assertTrue(op.signed());
        assertEquals(3, op.dst());
        assertEquals(4, op.src1());
        assertEquals(5, op.src2());
        assertFalse(op.wide());
    }

    @Test
    void udivNarrow() {
        Ir64Op.Divide op =
                (Ir64Op.Divide) DECODER.decode(memory, 0x228);
        assertFalse(op.signed());
        assertEquals(6, op.dst());
        assertEquals(7, op.src1());
        assertEquals(8, op.src2());
        assertFalse(op.wide());
    }

    // ── B6.3.4: LDXR/LDAXR/STXR/STLXR — offsets 0x22c+ ─────────────────────────────────────────

    @Test
    void ldxrWord() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x22c);
        assertEquals(0, op.rt());
        assertEquals(1, op.rn());
        assertEquals(Ir64MemSize.WORD, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void ldxrDoubleword() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x230);
        assertEquals(2, op.rt());
        assertEquals(3, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void ldxrByte() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x234);
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void ldxrHalf() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x238);
        assertEquals(6, op.rt());
        assertEquals(7, op.rn());
        assertEquals(Ir64MemSize.HALF, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void ldaxrWordSetsAcquireRelease() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x23c);
        assertEquals(8, op.rt());
        assertEquals(9, op.rn());
        assertEquals(Ir64MemSize.WORD, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void ldaxrDoubleword() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x240);
        assertEquals(10, op.rt());
        assertEquals(11, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void ldaxrByte() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x244);
        assertEquals(12, op.rt());
        assertEquals(13, op.rn());
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void ldaxrHalf() {
        Ir64Op.LoadExclusive op = (Ir64Op.LoadExclusive) DECODER.decode(memory, 0x248);
        assertEquals(14, op.rt());
        assertEquals(15, op.rn());
        assertEquals(Ir64MemSize.HALF, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void stxrWord() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x24c);
        assertEquals(16, op.rs());
        assertEquals(17, op.rt());
        assertEquals(18, op.rn());
        assertEquals(Ir64MemSize.WORD, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void stxrDoubleword() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x250);
        assertEquals(19, op.rs());
        assertEquals(20, op.rt());
        assertEquals(21, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void stxrByte() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x254);
        assertEquals(22, op.rs());
        assertEquals(23, op.rt());
        assertEquals(24, op.rn());
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void stxrHalf() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x258);
        assertEquals(25, op.rs());
        assertEquals(26, op.rt());
        assertEquals(27, op.rn());
        assertEquals(Ir64MemSize.HALF, op.size());
        assertFalse(op.acquireRelease());
    }

    @Test
    void stlxrWordSetsAcquireRelease() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x25c);
        assertEquals(28, op.rs());
        assertEquals(29, op.rt());
        assertEquals(30, op.rn());
        assertEquals(Ir64MemSize.WORD, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void stlxrDoubleword() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x260);
        assertEquals(0, op.rs());
        assertEquals(1, op.rt());
        assertEquals(2, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void stlxrByte() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x264);
        assertEquals(3, op.rs());
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void stlxrHalf() {
        Ir64Op.StoreExclusive op = (Ir64Op.StoreExclusive) DECODER.decode(memory, 0x268);
        assertEquals(6, op.rs());
        assertEquals(7, op.rt());
        assertEquals(8, op.rn());
        assertEquals(Ir64MemSize.HALF, op.size());
        assertTrue(op.acquireRelease());
    }

    @Test
    void exclusivePairAndAtomicFormsStayUnsupported() {
        // LDXP/STXP/CAS/LDAR/STLR (mesmo subgrupo SUBCLASS_EXCLUSIVE_ATOMIC, valores diferentes
        // do campo `form` bits[23:21]) NÃO estão no escopo desta task (ver Armadilhas da
        // b6.3.4-aarch64-exclusive-monitor.md) — regressão negativa confirmando que o `case` novo
        // não "vazou" para essas formas. Vetores construídos à mão a partir do formato (mesmo
        // precedente de outros testes de reservado neste arquivo), pois não fazem parte do escopo
        // do corpus real.
        // Base real do corpus (ldxr w0, [x1]) com o campo `form` (bits[23:21]) ZERADO antes de
        // fixar cada valor fora de escopo — a base já tem form=010 (LDXR), então um OR simples
        // sem limpar antes produziria combinações erradas.
        int ldxrWordFormCleared = 0x885f7c20 & ~(0b111 << 21);
        int[] otherForms = {
                ldxrWordFormCleared | (0b001 << 21), // STXP (form=001)
                ldxrWordFormCleared | (0b011 << 21), // LDXP (form=011)
                ldxrWordFormCleared | (0b100 << 21), // STLR (form=100)
                ldxrWordFormCleared | (0b101 << 21), // CAS-ish/reservado (form=101)
                ldxrWordFormCleared | (0b110 << 21), // LDAR (form=110)
                ldxrWordFormCleared | (0b111 << 21), // reservado (form=111)
        };
        for (int encoding : otherForms) {
            TestAddressSpace raw = new TestAddressSpace(4);
            raw.put32(0, encoding);
            AddressSpace64 scratch = AddressSpace64.wrapping(raw);
            assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0),
                    () -> "form fora de escopo deveria ser unsupported: 0x" + Integer.toHexString(encoding));
        }
    }
}
