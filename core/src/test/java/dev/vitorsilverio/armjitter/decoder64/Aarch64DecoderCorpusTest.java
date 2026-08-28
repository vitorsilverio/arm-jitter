package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
// Ir64Op.MultiplyAccumulate/Ir64Op.Divide (B6.3.3) são referenciados via Ir64Op.* (mesmo padrão
// já usado neste arquivo para os demais subtipos aninhados).
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
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
    void nopHintAtLabel1() {
        // B6.6.7: o nop no offset 0x90 (label1) é uma "hint" do grupo System instructions —
        // antes de B6.6.7, hints inteiros eram fora de escopo (este teste chamava isso de
        // `unsupportedEncodingThrows`); agora `NOP`/`YIELD`/`WFE`/`SEV`/`SEVL` decodificam como
        // NOP puro (ver {@link #nopHintYieldWfeSevSevl}, apêndice do corpus).
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x90);
        assertEquals(Ir64SystemInstructionOp.NOP_HINT, op.opcode());
    }

    @Test
    void clrexDecodesNow() {
        // clrex (offset 0x37c) — B8.3: antes desta task ficava fora do subconjunto coberto (ver
        // histórico), agora fecha o monitor de exclusividade.
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x37c);
        assertEquals(Ir64SystemInstructionOp.CLEAR_EXCLUSIVE, op.opcode());
    }

    @Test
    void brkDecodesNow() {
        // brk #0x0 (offset 0x380) — B8.3: `opc=001` (grupo "Exception generating") agora reconhecido.
        Ir64Op.Breakpoint op = (Ir64Op.Breakpoint) DECODER.decode(memory, 0x380);
        assertEquals(0, op.immediate());
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
    void exclusiveAtomicFormSpaceFullyDecodedByB81() {
        // Retirado pela B8.1: este teste fixava LDXP/STXP/CAS/CASP/LDAR/STLR como "fora de
        // escopo" (b6.3.4-aarch64-exclusive-monitor.md) — exatamente o espaço que a B8.1
        // implementou (ver Aarch64Decoder#decodeExclusive). Substituído por uma regressão
        // POSITIVA: as 8 combinações do campo `form` (bits[23:21]), cruzadas com bit31 onde ele
        // desambigua (STXP/LDXP vs. CASP), decodificam sem lançar — nenhuma sobra sem tratamento
        // no subgrupo SUBCLASS_EXCLUSIVE_ATOMIC.
        int ldxrWordFormCleared = 0x885f7c20 & ~(0b111 << 21);
        int[] allForms = {
                ldxrWordFormCleared | (0b000 << 21), // STXR
                ldxrWordFormCleared | (0b001 << 21), // CASP (bit31=0 na base)
                ldxrWordFormCleared | (0b010 << 21), // LDXR
                ldxrWordFormCleared | (0b011 << 21), // CASP (bit31=0 na base)
                ldxrWordFormCleared | (0b100 << 21), // STLR
                ldxrWordFormCleared | (0b101 << 21), // CAS
                ldxrWordFormCleared | (0b110 << 21), // LDAR
                ldxrWordFormCleared | (0b111 << 21), // CAS
                ldxrWordFormCleared | (1 << 31) | (0b001 << 21), // STXP (bit31=1)
                ldxrWordFormCleared | (1 << 31) | (0b011 << 21), // LDXP (bit31=1)
        };
        for (int encoding : allForms) {
            TestAddressSpace raw = new TestAddressSpace(4);
            raw.put32(0, encoding);
            AddressSpace64 scratch = AddressSpace64.wrapping(raw);
            assertDoesNotThrow(() -> DECODER.decode(scratch, 0),
                    () -> "0x" + Integer.toHexString(encoding) + " deveria decodificar (B8.1)");
        }
    }

    // ── B6.6.1: MRS/MSR (register) — offsets 0x26c+ ────────────────────────────────────────────

    @Test
    void mrsSctlrEl1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x26c);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.SCTLR_EL1, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void msrSctlrEl1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x270);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.SCTLR_EL1, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void mrsTtbr0El1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x274);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.TTBR0_EL1, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void msrTtbr0El1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x278);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.TTBR0_EL1, op.register());
        assertEquals(3, op.rt());
    }

    @Test
    void mrsVbarEl1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x27c);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.VBAR_EL1, op.register());
        assertEquals(4, op.rt());
    }

    @Test
    void msrVbarEl1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x280);
        assertFalse(op.read());
        assertEquals(Aarch64SystemRegisterId.VBAR_EL1, op.register());
        assertEquals(5, op.rt());
    }

    // ── B6.6.3: SYS/SYS(L) — TLBI VMALLE1(IS) + barreiras DSB/ISB/DMB — offsets 0x284+ ─────────

    @Test
    void tlbiVmalle1() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x284);
        assertEquals(Ir64SystemInstructionOp.TLBI_ALL, op.opcode());
    }

    @Test
    void tlbiVmalle1Is() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x288);
        assertEquals(Ir64SystemInstructionOp.TLBI_ALL, op.opcode());
    }

    @Test
    void dsbSy() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x28c);
        assertEquals(Ir64SystemInstructionOp.BARRIER, op.opcode());
    }

    @Test
    void isb() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x290);
        assertEquals(Ir64SystemInstructionOp.BARRIER, op.opcode());
    }

    @Test
    void dmbSy() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x294);
        assertEquals(Ir64SystemInstructionOp.BARRIER, op.opcode());
    }

    @Test
    void tlbiVae1PerVaFormDecodesNow() {
        // `tlbi vae1, x0` (B8.3: sem TLB modelada, QUALQUER TLBI do regime EL1 vira "invalidar
        // tudo" — mesma simplificação "sem per-ASID/per-VA" do precedente 32-bit, só que agora
        // aceita o encoding real em vez de rejeitá-lo) — encoding real via aarch64-none-elf-as.
        int word = 0xd5088720;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(scratch, 0);
        assertEquals(Ir64SystemInstructionOp.TLBI_ALL, op.opcode());
    }

    @Test
    void tlbiAlle2DecodesAsInvalidateAll() {
        // `tlbi alle2` (B10.9: regime EL2, `op1=0b100`) — encoding real via aarch64-none-elf-as.
        assertTlbiAllAtWord(0xd50c871f);
    }

    @Test
    void tlbiVae2DecodesAsInvalidateAll() {
        // `tlbi vae2, x0` (B10.9: mesmo `op1=0b100` de `alle2`, per-VA — encoding real).
        assertTlbiAllAtWord(0xd50c8720);
    }

    @Test
    void tlbiIpas2e1DecodesAsInvalidateAll() {
        // `tlbi ipas2e1, x0` (B10.9: stage-2, MESMO `op1=0b100` de EL2 no hardware real —
        // conferido contra `tlbi_el1_cp_reginfo` real do QEMU, `target/arm/tcg/tlb-insns.c` —,
        // só `CRm=0b0100` distingue de `alle2`/`vae2` acima; este emulador não diferencia).
        assertTlbiAllAtWord(0xd50c8420);
    }

    @Test
    void tlbiIpas2le1DecodesAsInvalidateAll() {
        // `tlbi ipas2le1, x0` (B10.9) — encoding real via aarch64-none-elf-as.
        assertTlbiAllAtWord(0xd50c84a0);
    }

    @Test
    void tlbiVmalls12e1DecodesAsInvalidateAll() {
        // `tlbi vmalls12e1` (B10.9: combinada stage-1+stage-2, ainda `op1=0b100`/`CRm=0b0111`,
        // mesmo grupo de `alle1`) — encoding real via aarch64-none-elf-as.
        assertTlbiAllAtWord(0xd50c87df);
    }

    @Test
    void tlbiAlle3DecodesAsInvalidateAll() {
        // `tlbi alle3` (B10.9: regime EL3, `op1=0b110`) — encoding real via aarch64-none-elf-as.
        assertTlbiAllAtWord(0xd50e871f);
    }

    @Test
    void tlbiVae3DecodesAsInvalidateAll() {
        // `tlbi vae3, x0` (B10.9) — encoding real via aarch64-none-elf-as.
        assertTlbiAllAtWord(0xd50e8720);
    }

    private static void assertTlbiAllAtWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(scratch, 0);
        assertEquals(Ir64SystemInstructionOp.TLBI_ALL, op.opcode());
    }

    @Test
    void syslFormThrows() {
        // `sysl x0, #0, c8, c7, #0` (`L=1`, mesmos CRn/CRm/op2 do TLBI VMALLE1) — fora do escopo,
        // esta task só reconhece a forma `SYS` (`L=0`) — encoding real via aarch64-none-elf-as.
        int word = 0xd5288700;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void systemRegisterCombinationOutsideCoveredListThrows() {
        // mrs x0, sctlr_el1 (offset 0x26c) com CRn forçado para 3 (op1=0/CRm=0/op2=0): nenhum
        // registrador da lista coberta usa essa combinação — não implementado ainda, não é
        // UNDEFINED real (Fatos de referência #2/Armadilhas da task).
        int word = 0xd5381000 | (3 << 12); // CRn=1 -> CRn=3 (nenhum registrador coberto usa CRn=3)
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void systemRegisterOp0ZeroIsWfet() {
        // op0=0b00 (bits20:19) é o subgrupo hint/barreira/"wait with timeout"/MSR-imediato, não
        // SYS/MRS/MSR — mesmo prefixo fixo(31:22). Zerar op0 do encoding de `mrs x0,sctlr_el1`
        // cai em CRn=0b0001/op2=0b000 (`WFET`, B8.3) — antes desta task, `op0=0` inteiro era fora
        // de escopo; agora só combinações realmente reservadas dentro dele continuam UNDEFINED.
        // B11.6: FEAT_WFxT agora é gateada — o decoder DEFAULT (ARMv8.0-A) rejeita este encoding.
        int word = 0xd5381000 & ~(0b11 << 19); // zera op0
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void systemRegisterOp0OneSysFormIsOutOfScope() {
        // op0=1 (SYS/SYSL — TLBI/IC/DC/AT) é uma família de instruções diferente de MRS/MSR de
        // registrador nomeado; fora do escopo desta task (Fatos de referência #2).
        int word = (0xd5381000 & ~(0b11 << 19)) | (0b01 << 19);
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B6.5.3: FADD/FSUB/FMUL/FDIV/FNEG/FABS/FMOV(reg)/FMOV(imm)/FCMP/FCMPE/FCVT ──────────────
    // ── offsets 0x298+, apêndice do mesmo corpus.s/corpus.bin/corpus.objdump.txt ────────────────

    @Test
    void faddSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x298);
        assertEquals(Ir64Op.Fp64Operation.ADD, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(0, op.vd());
        assertEquals(1, op.vn());
        assertEquals(2, op.vm());
    }

    @Test
    void faddDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x29c);
        assertEquals(Ir64Op.Fp64Operation.ADD, op.op());
        assertTrue(op.doublePrecision());
        assertEquals(0, op.vd());
        assertEquals(1, op.vn());
        assertEquals(2, op.vm());
    }

    @Test
    void fsubSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2a0);
        assertEquals(Ir64Op.Fp64Operation.SUB, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(3, op.vd());
        assertEquals(4, op.vn());
        assertEquals(5, op.vm());
    }

    @Test
    void fsubDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2a4);
        assertEquals(Ir64Op.Fp64Operation.SUB, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fmulSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2a8);
        assertEquals(Ir64Op.Fp64Operation.MUL, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(6, op.vd());
        assertEquals(7, op.vn());
        assertEquals(8, op.vm());
    }

    @Test
    void fmulDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2ac);
        assertEquals(Ir64Op.Fp64Operation.MUL, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fdivSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2b0);
        assertEquals(Ir64Op.Fp64Operation.DIV, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(9, op.vd());
        assertEquals(10, op.vn());
        assertEquals(11, op.vm());
    }

    @Test
    void fdivDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2b4);
        assertEquals(Ir64Op.Fp64Operation.DIV, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fnegSingle() {
        // fneg s12, s13: 1-source (unário) — o operando único vem em `vm` (Rn do encoding),
        // `vn` não é usado por esta forma (Fp64Alu javadoc).
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2b8);
        assertEquals(Ir64Op.Fp64Operation.NEG, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(12, op.vd());
        assertEquals(13, op.vm());
    }

    @Test
    void fnegDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2bc);
        assertEquals(Ir64Op.Fp64Operation.NEG, op.op());
        assertTrue(op.doublePrecision());
        assertEquals(12, op.vd());
        assertEquals(13, op.vm());
    }

    @Test
    void fabsSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2c0);
        assertEquals(Ir64Op.Fp64Operation.ABS, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(14, op.vd());
        assertEquals(15, op.vm());
    }

    @Test
    void fabsDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2c4);
        assertEquals(Ir64Op.Fp64Operation.ABS, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fmovRegisterSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2c8);
        assertEquals(Ir64Op.Fp64Operation.MOV, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(16, op.vd());
        assertEquals(17, op.vm());
    }

    @Test
    void fmovRegisterDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x2cc);
        assertEquals(Ir64Op.Fp64Operation.MOV, op.op());
        assertTrue(op.doublePrecision());
        assertEquals(16, op.vd());
        assertEquals(17, op.vm());
    }

    @Test
    void fmovImmediateSingleOne() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2d0);
        assertFalse(op.doublePrecision());
        assertEquals(18, op.vd());
        assertEquals(1.0f, Float.intBitsToFloat((int) op.immediateBits()));
    }

    @Test
    void fmovImmediateSingleTwo() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2d4);
        assertEquals(2.0f, Float.intBitsToFloat((int) op.immediateBits()));
    }

    @Test
    void fmovImmediateSingleNegativeOne() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2d8);
        assertEquals(-1.0f, Float.intBitsToFloat((int) op.immediateBits()));
    }

    @Test
    void fmovImmediateSingleEighth() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2dc);
        assertEquals(0.125f, Float.intBitsToFloat((int) op.immediateBits()));
    }

    @Test
    void fmovImmediateDoubleOne() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2e0);
        assertTrue(op.doublePrecision());
        assertEquals(18, op.vd());
        assertEquals(1.0, Double.longBitsToDouble(op.immediateBits()));
    }

    @Test
    void fmovImmediateDoubleTwo() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2e4);
        assertEquals(2.0, Double.longBitsToDouble(op.immediateBits()));
    }

    @Test
    void fmovImmediateDoubleNegativeOne() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2e8);
        assertEquals(-1.0, Double.longBitsToDouble(op.immediateBits()));
    }

    @Test
    void fmovImmediateDoubleEighth() {
        Ir64Op.Fp64MoveImmediate op = (Ir64Op.Fp64MoveImmediate) DECODER.decode(memory, 0x2ec);
        assertEquals(0.125, Double.longBitsToDouble(op.immediateBits()));
    }

    @Test
    void fcmpSingle() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x2f0);
        assertFalse(op.doublePrecision());
        assertFalse(op.compareWithZero());
        assertFalse(op.signalOnQuietNaN());
        assertEquals(22, op.vn());
        assertEquals(23, op.vm());
    }

    @Test
    void fcmpDouble() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x2f4);
        assertTrue(op.doublePrecision());
        assertFalse(op.compareWithZero());
        assertEquals(22, op.vn());
        assertEquals(23, op.vm());
    }

    @Test
    void fcmpSingleWithZero() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x2f8);
        assertFalse(op.doublePrecision());
        assertTrue(op.compareWithZero());
        assertFalse(op.signalOnQuietNaN());
        assertEquals(24, op.vn());
    }

    @Test
    void fcmpDoubleWithZero() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x2fc);
        assertTrue(op.doublePrecision());
        assertTrue(op.compareWithZero());
        assertEquals(24, op.vn());
    }

    @Test
    void fcmpeSingle() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x300);
        assertFalse(op.doublePrecision());
        assertFalse(op.compareWithZero());
        assertTrue(op.signalOnQuietNaN());
        assertEquals(25, op.vn());
        assertEquals(26, op.vm());
    }

    @Test
    void fcmpeDouble() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x304);
        assertTrue(op.doublePrecision());
        assertTrue(op.signalOnQuietNaN());
        assertEquals(25, op.vn());
        assertEquals(26, op.vm());
    }

    @Test
    void fcmpeSingleWithZero() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x308);
        assertFalse(op.doublePrecision());
        assertTrue(op.compareWithZero());
        assertTrue(op.signalOnQuietNaN());
        assertEquals(27, op.vn());
    }

    @Test
    void fcmpeDoubleWithZero() {
        Ir64Op.Fp64Compare op = (Ir64Op.Fp64Compare) DECODER.decode(memory, 0x30c);
        assertTrue(op.doublePrecision());
        assertTrue(op.compareWithZero());
        assertTrue(op.signalOnQuietNaN());
        assertEquals(27, op.vn());
    }

    @Test
    void fcvtSingleToDouble() {
        Ir64Op.Fp64Convert op = (Ir64Op.Fp64Convert) DECODER.decode(memory, 0x310);
        assertEquals(Ir64Op.Fp64Conversion.F32_TO_F64, op.conversion());
        assertEquals(28, op.vd());
        assertEquals(28, op.vm());
    }

    @Test
    void fcvtDoubleToSingle() {
        Ir64Op.Fp64Convert op = (Ir64Op.Fp64Convert) DECODER.decode(memory, 0x314);
        assertEquals(Ir64Op.Fp64Conversion.F64_TO_F32, op.conversion());
        assertEquals(29, op.vd());
        assertEquals(29, op.vm());
    }

    @Test
    void scalarFpTypeReservedHalfPrecisionThrows() {
        // type=10 (meia-precisão, fora de escopo — Fatos de referência #4): construído a partir
        // do vetor real "fadd s0,s1,s2" (0x298) com o campo `type` forçado para 10, sem assembly
        // válido correspondente (o assembler recusa FP16 sem a extensão).
        int word = (memory.read32(0x298) & ~(FP_TYPE_MASK << FP_TYPE_SHIFT)) | (0b10 << FP_TYPE_SHIFT);
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void scalarFpTypeReservedBothBitsThrows() {
        // type=11 (reservado): mesmo vetor-base, campo forçado para 11.
        int word = memory.read32(0x298) | (0b11 << FP_TYPE_SHIFT);
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    @Test
    void advancedSimdVectorFormNowDecodesSinceB89() {
        // `fadd v0.4s, v1.4s, v2.4s` (Advanced SIMD vetorial, MESMO bit26=1 da classe escalar
        // decodificada pelas tasks B6.5.x/B8.4/B8.5, mas prefixo(28:24) DIFERENTE — "01110" em vez
        // de "11110") — encoding real via aarch64-none-elf-as. Até a B8.9 esta asserção era
        // NEGATIVA (fora de escopo, herdado de B6.5.3); agora `FADD_v`/... (AdvSIMD "three same" de
        // ponto flutuante) fazem parte do escopo implementado, ver `decodeVectorFpThreeSameOpcode`.
        int word = 0x4e22d420;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        Ir64Op op = DECODER.decode(scratch, 0);
        assertInstanceOf(Ir64Op.VectorFpArithmeticThreeSame.class, op);
    }

    @Test
    void floatingPointToIntegerConversionNowDecodesSinceB85() {
        // `scvtf s1, w1` (conversão inteiro->FP escalar, mesmo prefixo(28:24)="11110"/bit21=1 da
        // classe decodificada aqui) — encoding real via aarch64-none-elf-as. Até a B8.5 esta
        // asserção era NEGATIVA (fora de escopo, herdado de B6.5.2); agora `SCVTF`/`UCVTF`/
        // `FCVTxS`/`FCVTxU` (registrador-geral) fazem parte do escopo implementado, ver
        // `decodeFpIntegerConvertOrGeneralRegisterMove`.
        int word = 0x1e220021;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(scratch, 0);
        assertTrue(op.toFloat());
        assertTrue(op.signed());
        assertFalse(op.doublePrecision());
        assertFalse(op.wide());
        assertEquals(0, op.fixedPointFractionBits());
        assertEquals(1, op.fpReg());
        assertEquals(1, op.gpReg());
    }

    @Test
    void floatingPointConditionalSelectNowDecodesSinceB85() {
        // `fcsel s6, s7, s8, eq` — até a B8.5 esta asserção era NEGATIVA (fora de escopo, herdado
        // de B6.5.2); agora `FCSEL` faz parte do escopo implementado.
        int word = 0x1e280ce6;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        Ir64Op.Fp64ConditionalSelect op = (Ir64Op.Fp64ConditionalSelect) DECODER.decode(scratch, 0);
        assertFalse(op.doublePrecision());
        assertEquals(6, op.vd());
        assertEquals(7, op.vn());
        assertEquals(8, op.vm());
        assertEquals(Ir64Condition.EQ, op.condition());
    }

    @Test
    void floatingPointSquareRootNowDecodesSinceB84() {
        // `fsqrt s3, s4` — MESMO padrão fixo de 1-source ("10000" em bits[14:10]) das formas já
        // cobertas por B6.5.3 (FMOV/FABS/FNEG/FCVT), opcode(20:15)=3. Até a B8.4 esta asserção era
        // NEGATIVA (fora de escopo, herdado de B6.5.2) — agora `FSQRT` faz parte do escopo
        // implementado (ver `fsqrtSingle`/`fsqrtDouble`, que usam o corpus real em vez deste vetor
        // manual); mantido como regressão de que o `switch` de opcode reconhece exatamente este
        // valor.
        int word = 0x1e21c083;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(scratch, 0);
        assertEquals(Ir64Op.Fp64Operation.SQRT, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(3, op.vd());
        assertEquals(4, op.vm());
    }

    @Test
    void dataProcessingRegisterClassUnaffectedByBit26Branch() {
        // Regressão explícita (Aceite da task B6.5.3): um vetor de "Data Processing — Register"
        // (bit26=0, B6.3.1-B6.3.4) continua decodificando exatamente como antes — o novo `if` de
        // bit26 no topo de decodeDataProcessingRegister não pode ter alterado nenhum resultado já
        // coberto pelos testes de csel()/madd()/etc. acima. Redecodifica um vetor já testado
        // (csel x0,x1,x2,eq, offset 0x188) como sanity extra deste apêndice.
        Ir64Op.ConditionalSelect op = (Ir64Op.ConditionalSelect) DECODER.decode(memory, 0x188);
        assertEquals(Ir64ConditionalSelectOp.CSEL, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
    }

    private static final int FP_TYPE_SHIFT = 22;
    private static final int FP_TYPE_MASK = 0b11;

    // ── B6.6.7: identidade da CPU, timer genérico, HVC/SMC, WFI/hints (apêndice do mesmo
    // ── corpus.s/.bin/.objdump.txt, offsets 0x318-0x380) ────────────────────────────────────

    @Test
    void systemRegisterCurrentEl() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x318);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.CURRENT_EL, op.register());
        assertEquals(0, op.rt());
    }

    @Test
    void systemRegisterMpidrEl1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x31c);
        assertEquals(Aarch64SystemRegisterId.MPIDR_EL1, op.register());
        assertEquals(1, op.rt());
    }

    @Test
    void systemRegisterMidrEl1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x320);
        assertEquals(Aarch64SystemRegisterId.MIDR_EL1, op.register());
        assertEquals(2, op.rt());
    }

    @Test
    void systemRegisterIdAa64Pfr0El1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x324);
        assertEquals(Aarch64SystemRegisterId.ID_AA64PFR0_EL1, op.register());
    }

    @Test
    void systemRegisterIdAa64Isar0El1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x328);
        assertEquals(Aarch64SystemRegisterId.ID_AA64ISAR0_EL1, op.register());
    }

    @Test
    void systemRegisterIdAa64Mmfr0El1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x32c);
        assertEquals(Aarch64SystemRegisterId.ID_AA64MMFR0_EL1, op.register());
    }

    @Test
    void systemRegisterIdAa64Dfr0El1() {
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x330);
        assertEquals(Aarch64SystemRegisterId.ID_AA64DFR0_EL1, op.register());
    }

    @Test
    void systemRegisterTpidrEl1ReadAndWrite() {
        Ir64Op.SystemRegister read = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x334);
        assertTrue(read.read());
        assertEquals(Aarch64SystemRegisterId.TPIDR_EL1, read.register());
        assertEquals(7, read.rt());
        Ir64Op.SystemRegister write = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x338);
        assertFalse(write.read());
        assertEquals(Aarch64SystemRegisterId.TPIDR_EL1, write.register());
        assertEquals(8, write.rt());
    }

    @Test
    void systemRegisterGenericTimer() {
        assertEquals(Aarch64SystemRegisterId.CNTFRQ_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x33c)).register());
        assertEquals(Aarch64SystemRegisterId.CNTPCT_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x340)).register());
        assertEquals(Aarch64SystemRegisterId.CNTP_TVAL_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x344)).register());
        assertEquals(Aarch64SystemRegisterId.CNTP_TVAL_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x348)).register());
        assertEquals(Aarch64SystemRegisterId.CNTP_CTL_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x34c)).register());
        assertEquals(Aarch64SystemRegisterId.CNTP_CTL_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x350)).register());
        assertEquals(Aarch64SystemRegisterId.CNTP_CVAL_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x354)).register());
        assertEquals(Aarch64SystemRegisterId.CNTP_CVAL_EL0,
                ((Ir64Op.SystemRegister) DECODER.decode(memory, 0x358)).register());
    }

    @Test
    void hvcAndSmc() {
        assertInstanceOf(Ir64Op.PrivilegedCall.class, DECODER.decode(memory, 0x35c));
        assertInstanceOf(Ir64Op.PrivilegedCall.class, DECODER.decode(memory, 0x360));
    }

    @Test
    void wfiDecodesAsSystemInstruction() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x364);
        assertEquals(Ir64SystemInstructionOp.WFI, op.opcode());
    }

    @Test
    void nopHintYieldWfeSevSevl() {
        for (long offset : new long[] {0x368, 0x36c, 0x370, 0x374, 0x378}) {
            Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, offset);
            assertEquals(Ir64SystemInstructionOp.NOP_HINT, op.opcode());
        }
    }

    // ── B6.10: CTR_EL0/DCZID_EL0 (identidade de cache) — apêndice do mesmo corpus.s/.bin/
    // ── .objdump.txt, offsets 0x448-0x44c, terceiro gap achado pela F11 ────────────────────────

    @Test
    void systemRegisterCtrEl0() {
        // mrs x3, CTR_EL0 (offset 0x448).
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x448);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.CTR_EL0, op.register());
        assertEquals(3, op.rt());
    }

    @Test
    void systemRegisterDczidEl0() {
        // mrs x4, DCZID_EL0 (offset 0x44c).
        Ir64Op.SystemRegister op = (Ir64Op.SystemRegister) DECODER.decode(memory, 0x44c);
        assertTrue(op.read());
        assertEquals(Aarch64SystemRegisterId.DCZID_EL0, op.register());
        assertEquals(4, op.rt());
    }

    // ── B6.11: LSLV/LSRV/ASRV/RORV (deslocamento variável) — apêndice do mesmo corpus.s/.bin/
    // ── .objdump.txt, offsets 0x450-0x470, QUARTO gap achado pela F11 (incl. o vetor literal ─────
    // ── `lsl x2, x2, x3` = 0x9ac32042, achado em 0x38fd4 do kernel8.img real) ───────────────────

    private static void assertShiftVariable(
            Ir64Op.ShiftVariable op, int dst, int src1, int src2,
            Ir64LogicalShiftType shiftType, boolean wide) {
        assertEquals(dst, op.dst());
        assertEquals(src1, op.src1());
        assertEquals(src2, op.src2());
        assertEquals(shiftType, op.shiftType());
        assertEquals(wide, op.wide());
    }

    @Test
    void lslvWide() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x450);
        assertShiftVariable(op, 1, 2, 3, Ir64LogicalShiftType.LSL, true);
    }

    @Test
    void lsrvWide() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x454);
        assertShiftVariable(op, 4, 5, 6, Ir64LogicalShiftType.LSR, true);
    }

    @Test
    void asrvWide() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x458);
        assertShiftVariable(op, 7, 8, 9, Ir64LogicalShiftType.ASR, true);
    }

    @Test
    void rorvWide() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x45c);
        assertShiftVariable(op, 10, 11, 12, Ir64LogicalShiftType.ROR, true);
    }

    @Test
    void lslvNarrow() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x460);
        assertShiftVariable(op, 13, 14, 15, Ir64LogicalShiftType.LSL, false);
    }

    @Test
    void lsrvNarrow() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x464);
        assertShiftVariable(op, 16, 17, 18, Ir64LogicalShiftType.LSR, false);
    }

    @Test
    void asrvNarrow() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x468);
        assertShiftVariable(op, 19, 20, 21, Ir64LogicalShiftType.ASR, false);
    }

    @Test
    void rorvNarrow() {
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x46c);
        assertShiftVariable(op, 22, 23, 24, Ir64LogicalShiftType.ROR, false);
    }

    @Test
    void lslvLiteralVectorFromKernel8Img() {
        // lsl x2, x2, x3 (0x9ac32042) — vetor LITERAL da F11 (0x38fd4 do kernel8.img real).
        Ir64Op.ShiftVariable op = (Ir64Op.ShiftVariable) DECODER.decode(memory, 0x470);
        assertShiftVariable(op, 2, 2, 3, Ir64LogicalShiftType.LSL, true);
    }

    // ── B6.8: CCMP/CCMN (registrador e imediato) — apêndice do mesmo corpus.s/.bin/.objdump.txt,
    // ── offsets 0x384-0x3a4, incl. o vetor literal `ccmp x18,#0,#0xd,pl` da F11 ─────────────────

    @Test
    void ccmpRegisterWide() {
        // ccmp x1, x2, #3, eq (offset 0x384).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x384);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(1, op.rn());
        assertFalse(op.immediateForm());
        assertEquals(2, op.rm());
        assertEquals(-1, op.immediate());
        assertTrue(op.wide());
        assertEquals(Ir64Condition.EQ, op.condition());
        assertEquals(0x3, op.nzcv());
    }

    @Test
    void ccmpRegisterNarrow() {
        // ccmp w3, w4, #5, ne (offset 0x388).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x388);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertFalse(op.wide());
        assertEquals(3, op.rn());
        assertEquals(4, op.rm());
        assertEquals(Ir64Condition.NE, op.condition());
        assertEquals(0x5, op.nzcv());
    }

    @Test
    void ccmpImmediateWide() {
        // ccmp x5, #10, #7, cs (offset 0x38c).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x38c);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(5, op.rn());
        assertTrue(op.immediateForm());
        assertEquals(10, op.immediate());
        assertEquals(-1, op.rm());
        assertTrue(op.wide());
        assertEquals(Ir64Condition.CS, op.condition());
        assertEquals(0x7, op.nzcv());
    }

    @Test
    void ccmpImmediateNarrow() {
        // ccmp w6, #21, #2, cc (offset 0x390).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x390);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertFalse(op.wide());
        assertEquals(6, op.rn());
        assertTrue(op.immediateForm());
        assertEquals(21, op.immediate());
        assertEquals(Ir64Condition.CC, op.condition());
        assertEquals(0x2, op.nzcv());
    }

    @Test
    void ccmnRegisterWide() {
        // ccmn x7, x8, #1, mi (offset 0x394).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x394);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(7, op.rn());
        assertFalse(op.immediateForm());
        assertEquals(8, op.rm());
        assertTrue(op.wide());
        assertEquals(Ir64Condition.MI, op.condition());
        assertEquals(0x1, op.nzcv());
    }

    @Test
    void ccmnRegisterNarrow() {
        // ccmn w9, w10, #12, pl (offset 0x398).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x398);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertFalse(op.wide());
        assertEquals(9, op.rn());
        assertEquals(10, op.rm());
        assertEquals(Ir64Condition.PL, op.condition());
        assertEquals(0xc, op.nzcv());
    }

    @Test
    void ccmnImmediateWide() {
        // ccmn x11, #31, #15, vs (offset 0x39c).
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x39c);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertEquals(11, op.rn());
        assertTrue(op.immediateForm());
        assertEquals(31, op.immediate());
        assertTrue(op.wide());
        assertEquals(Ir64Condition.VS, op.condition());
        assertEquals(0xf, op.nzcv());
    }

    @Test
    void ccmnImmediateNarrowAllZero() {
        // ccmn w12, #0, #0, vc (offset 0x3a0) — vetor de canto: imediato e nzcv ambos zero.
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x3a0);
        assertEquals(Ir64AluOp.ADD, op.opcode());
        assertFalse(op.wide());
        assertEquals(12, op.rn());
        assertTrue(op.immediateForm());
        assertEquals(0, op.immediate());
        assertEquals(Ir64Condition.VC, op.condition());
        assertEquals(0x0, op.nzcv());
    }

    @Test
    void ccmpLiteralVectorFromF11PolyglotKernelHeader() {
        // ccmp x18, #0, #0xd, pl (offset 0x3a4) — a PRIMEIRA instrução real de praticamente todo
        // `kernel8.img` distribuído (truque polyglot EFI "MZ"), o achado que abriu a task B6.8.
        Ir64Op.ConditionalCompare op = (Ir64Op.ConditionalCompare) DECODER.decode(memory, 0x3a4);
        assertEquals(Ir64AluOp.SUB, op.opcode());
        assertEquals(18, op.rn());
        assertTrue(op.immediateForm());
        assertEquals(0, op.immediate());
        assertTrue(op.wide());
        assertEquals(Ir64Condition.PL, op.condition());
        assertEquals(0xd, op.nzcv());
    }

    // ── B6.9: Logical (shifted register) — apêndice do mesmo corpus.s/.bin/.objdump.txt, offsets
    // ── 0x3a8-0x444, incl. o vetor literal `mov x21, x0` (0xaa0003f5) que motivou a task ─────────

    private static void assertLogical(
            Ir64Op.LogicalShiftedRegister op, Ir64AluOp opcode, int dst, int src1, int src2,
            Ir64LogicalShiftType shiftType, int shiftAmount, boolean invert, boolean wide,
            boolean setFlags) {
        assertEquals(opcode, op.opcode());
        assertEquals(dst, op.dst());
        assertEquals(src1, op.src1());
        assertEquals(src2, op.src2());
        assertEquals(shiftType, op.shiftType());
        assertEquals(shiftAmount, op.shiftAmount());
        assertEquals(invert, op.invert());
        assertEquals(wide, op.wide());
        assertEquals(setFlags, op.setFlags());
    }

    @Test
    void andShiftedLsl() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3a8);
        assertLogical(op, Ir64AluOp.AND, 1, 2, 3, Ir64LogicalShiftType.LSL, 4, false, true, false);
    }

    @Test
    void andShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3ac);
        assertLogical(op, Ir64AluOp.AND, 1, 2, 3, Ir64LogicalShiftType.LSR, 4, false, true, false);
    }

    @Test
    void andShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3b0);
        assertLogical(op, Ir64AluOp.AND, 1, 2, 3, Ir64LogicalShiftType.ASR, 4, false, true, false);
    }

    @Test
    void andShiftedRor() {
        // and x1, x2, x3, ror #4 — ROR só existe nesta forma (RESERVADO em AluShiftedRegister).
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3b4);
        assertLogical(op, Ir64AluOp.AND, 1, 2, 3, Ir64LogicalShiftType.ROR, 4, false, true, false);
    }

    @Test
    void orrShiftedLsl() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3b8);
        assertLogical(op, Ir64AluOp.ORR, 4, 5, 6, Ir64LogicalShiftType.LSL, 8, false, true, false);
    }

    @Test
    void orrShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3bc);
        assertLogical(op, Ir64AluOp.ORR, 4, 5, 6, Ir64LogicalShiftType.LSR, 8, false, true, false);
    }

    @Test
    void orrShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3c0);
        assertLogical(op, Ir64AluOp.ORR, 4, 5, 6, Ir64LogicalShiftType.ASR, 8, false, true, false);
    }

    @Test
    void orrShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3c4);
        assertLogical(op, Ir64AluOp.ORR, 4, 5, 6, Ir64LogicalShiftType.ROR, 8, false, true, false);
    }

    @Test
    void eorShiftedLsl() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3c8);
        assertLogical(op, Ir64AluOp.EOR, 7, 8, 9, Ir64LogicalShiftType.LSL, 12, false, true, false);
    }

    @Test
    void eorShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3cc);
        assertLogical(op, Ir64AluOp.EOR, 7, 8, 9, Ir64LogicalShiftType.LSR, 12, false, true, false);
    }

    @Test
    void eorShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3d0);
        assertLogical(op, Ir64AluOp.EOR, 7, 8, 9, Ir64LogicalShiftType.ASR, 12, false, true, false);
    }

    @Test
    void eorShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3d4);
        assertLogical(op, Ir64AluOp.EOR, 7, 8, 9, Ir64LogicalShiftType.ROR, 12, false, true, false);
    }

    @Test
    void andsShiftedLsl() {
        // ands: opc=11 -> Ir64AluOp.AND com setFlags=true (D2, mesma decisão de B6.3.1 imediato).
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3d8);
        assertLogical(op, Ir64AluOp.AND, 10, 11, 12, Ir64LogicalShiftType.LSL, 16, false, true, true);
    }

    @Test
    void andsShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3dc);
        assertLogical(op, Ir64AluOp.AND, 10, 11, 12, Ir64LogicalShiftType.LSR, 16, false, true, true);
    }

    @Test
    void andsShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3e0);
        assertLogical(op, Ir64AluOp.AND, 10, 11, 12, Ir64LogicalShiftType.ASR, 16, false, true, true);
    }

    @Test
    void andsShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3e4);
        assertLogical(op, Ir64AluOp.AND, 10, 11, 12, Ir64LogicalShiftType.ROR, 16, false, true, true);
    }

    @Test
    void bicShiftedLsl() {
        // bic: mesmo opcode AND, invert=true (bit n=1) — Rm invertido ANTES de combinar.
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3e8);
        assertLogical(op, Ir64AluOp.AND, 13, 14, 15, Ir64LogicalShiftType.LSL, 4, true, true, false);
    }

    @Test
    void bicShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3ec);
        assertLogical(op, Ir64AluOp.AND, 13, 14, 15, Ir64LogicalShiftType.LSR, 4, true, true, false);
    }

    @Test
    void bicShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3f0);
        assertLogical(op, Ir64AluOp.AND, 13, 14, 15, Ir64LogicalShiftType.ASR, 4, true, true, false);
    }

    @Test
    void bicShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3f4);
        assertLogical(op, Ir64AluOp.AND, 13, 14, 15, Ir64LogicalShiftType.ROR, 4, true, true, false);
    }

    @Test
    void ornShiftedLsl() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3f8);
        assertLogical(op, Ir64AluOp.ORR, 16, 17, 18, Ir64LogicalShiftType.LSL, 8, true, true, false);
    }

    @Test
    void ornShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x3fc);
        assertLogical(op, Ir64AluOp.ORR, 16, 17, 18, Ir64LogicalShiftType.LSR, 8, true, true, false);
    }

    @Test
    void ornShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x400);
        assertLogical(op, Ir64AluOp.ORR, 16, 17, 18, Ir64LogicalShiftType.ASR, 8, true, true, false);
    }

    @Test
    void ornShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x404);
        assertLogical(op, Ir64AluOp.ORR, 16, 17, 18, Ir64LogicalShiftType.ROR, 8, true, true, false);
    }

    @Test
    void eonShiftedLsl() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x408);
        assertLogical(op, Ir64AluOp.EOR, 19, 20, 21, Ir64LogicalShiftType.LSL, 12, true, true, false);
    }

    @Test
    void eonShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x40c);
        assertLogical(op, Ir64AluOp.EOR, 19, 20, 21, Ir64LogicalShiftType.LSR, 12, true, true, false);
    }

    @Test
    void eonShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x410);
        assertLogical(op, Ir64AluOp.EOR, 19, 20, 21, Ir64LogicalShiftType.ASR, 12, true, true, false);
    }

    @Test
    void eonShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x414);
        assertLogical(op, Ir64AluOp.EOR, 19, 20, 21, Ir64LogicalShiftType.ROR, 12, true, true, false);
    }

    @Test
    void bicsShiftedLsl() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x418);
        assertLogical(op, Ir64AluOp.AND, 22, 23, 24, Ir64LogicalShiftType.LSL, 16, true, true, true);
    }

    @Test
    void bicsShiftedLsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x41c);
        assertLogical(op, Ir64AluOp.AND, 22, 23, 24, Ir64LogicalShiftType.LSR, 16, true, true, true);
    }

    @Test
    void bicsShiftedAsr() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x420);
        assertLogical(op, Ir64AluOp.AND, 22, 23, 24, Ir64LogicalShiftType.ASR, 16, true, true, true);
    }

    @Test
    void bicsShiftedRor() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x424);
        assertLogical(op, Ir64AluOp.AND, 22, 23, 24, Ir64LogicalShiftType.ROR, 16, true, true, true);
    }

    @Test
    void andShiftedNarrow() {
        // and w1, w2, w3, lsl #4 (!wide) — mesma regra de zero-extensão de AluShiftedRegister.
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x428);
        assertLogical(op, Ir64AluOp.AND, 1, 2, 3, Ir64LogicalShiftType.LSL, 4, false, false, false);
    }

    @Test
    void orrShiftedNarrow() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x42c);
        assertLogical(op, Ir64AluOp.ORR, 4, 5, 6, Ir64LogicalShiftType.LSR, 8, false, false, false);
    }

    @Test
    void eorShiftedNarrow() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x430);
        assertLogical(op, Ir64AluOp.EOR, 7, 8, 9, Ir64LogicalShiftType.ASR, 12, false, false, false);
    }

    @Test
    void andsShiftedNarrow() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x434);
        assertLogical(op, Ir64AluOp.AND, 10, 11, 12, Ir64LogicalShiftType.ROR, 16, false, false, true);
    }

    @Test
    void movRegisterAliasIsOrrWithXzr() {
        // mov x21, x0 (0xaa0003f5) — vetor LITERAL da F11 (0x13ba9e8 do kernel8.img real).
        // D3/D4: nenhum case dedicado, o caminho geral de ORR com Rn=XZR já é correto.
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x438);
        assertLogical(op, Ir64AluOp.ORR, 21, 31, 0, Ir64LogicalShiftType.LSL, 0, false, true, false);
    }

    @Test
    void mvnRegisterAliasIsOrnWithXzr() {
        // mvn x22, x1 (== orn x22, xzr, x1) — mesmo alias, com invert=true.
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x43c);
        assertLogical(op, Ir64AluOp.ORR, 22, 31, 1, Ir64LogicalShiftType.LSL, 0, true, true, false);
    }

    @Test
    void movRegisterAliasNarrow() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x440);
        assertLogical(op, Ir64AluOp.ORR, 23, 31, 2, Ir64LogicalShiftType.LSL, 0, false, false, false);
    }

    @Test
    void mvnRegisterAliasNarrow() {
        Ir64Op.LogicalShiftedRegister op =
                (Ir64Op.LogicalShiftedRegister) DECODER.decode(memory, 0x444);
        assertLogical(op, Ir64AluOp.ORR, 24, 31, 3, Ir64LogicalShiftType.LSL, 0, true, false, false);
    }

    @Test
    void logicalShiftedRegisterUndefinedNarrowShiftAmountBit5() {
        // sf=0 com quantidade de shift >= 32 (bit5 setado) é UNDEFINED — mesma regra de
        // AluShiftedRegister (B6.3.1), não representável pelo assembler (rejeitaria o operando),
        // então testado com uma palavra construída à mão a partir do encoding real de
        // `and w1, w2, w3, lsl #4` (0x0a031041) só com o campo `sa` forçado para `100000` (32) —
        // mesmo precedente de `logicalImmediateNReservedWithNarrowWidthThrows` acima.
        int word = 0x0a031041 | (1 << 15); // bit5 do campo sa (shift 10) é o bit 15 da palavra.
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B6.12: manutenção de cache IC/DC (NOP, sem cache emulado) — apêndice do mesmo
    // ── corpus.s/.bin/.objdump.txt, offsets 0x474-0x498, QUINTO gap achado pela F11 (incl. o
    // ── vetor literal `dc ivac, x0` = 0xd5087620 achado em 0x39000 do kernel8.img real) ─────────

    private static void assertCacheMaintenanceNoop(long offset) {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, offset);
        assertEquals(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP, op.opcode());
    }

    @Test
    void icIalluis() {
        assertCacheMaintenanceNoop(0x474);
    }

    @Test
    void icIallu() {
        assertCacheMaintenanceNoop(0x478);
    }

    @Test
    void icIvau() {
        assertCacheMaintenanceNoop(0x47c);
    }

    @Test
    void dcIvacLiteralVectorFromF11Kernel() {
        // dc ivac, x0 (0xd5087620 com rt=1 no corpus real) — a instrução que travou o boot da F11
        // em 0x39000 do kernel8.img real (0xd5087620 exato, rt=x0), motivou esta task.
        assertCacheMaintenanceNoop(0x480);
    }

    @Test
    void dcIsw() {
        assertCacheMaintenanceNoop(0x484);
    }

    @Test
    void dcCvac() {
        assertCacheMaintenanceNoop(0x488);
    }

    @Test
    void dcCsw() {
        assertCacheMaintenanceNoop(0x48c);
    }

    @Test
    void dcCvau() {
        assertCacheMaintenanceNoop(0x490);
    }

    @Test
    void dcCivac() {
        assertCacheMaintenanceNoop(0x494);
    }

    @Test
    void dcCisw() {
        assertCacheMaintenanceNoop(0x498);
    }

    @Test
    void dcZvaStaysUnsupported() {
        // dc zva, x0 (0xd50b7420, CRm=0b0100/op2=1 — DELIBERADAMENTE fora de
        // SYSTEM_INSTRUCTION_CACHE_OPS, ver Aarch64Decoder#decodeSystemInstructionSys):
        // tem efeito observável real (zera memória) e já é anunciada como indisponível via
        // DCZID_EL0.DZP=1 (B6.10); um guest que a emita mesmo assim deve lançar, não virar NOP
        // silencioso. Não representável pelo corpus (`.s`/`.objdump.txt`) porque nunca é gerada
        // por este emulador — construída à mão a partir do encoding real (ARM DDI 0487 C5.4.11).
        int word = 0xd50b7420;
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(scratch, 0));
    }

    // ── B10.6: AT S1E1R/S1E1W/S1E0R/S1E0W — mesmo CRn=0b0111 da manutenção de cache acima
    // ── (CRm=0b1000 distingue), construídos à mão a partir do encoding real (ARM DDI 0487 C6.2.23)
    // ── — não representáveis pelo corpus (nunca gerados por este emulador). Achado real desta
    // ── task: ANTES do carve-out, essas palavras caíam incorretamente em CACHE_MAINTENANCE_NOP
    // ── (ver javadoc de Aarch64Decoder#decodeSystemInstructionSys) — os testes abaixo confirmam
    // ── que agora viram Ir64Op.AddressTranslate de verdade.

    private static Ir64Op decodeAt(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        AddressSpace64 scratch = AddressSpace64.wrapping(raw);
        return DECODER.decode(scratch, 0);
    }

    @Test
    void atS1e1rX0() {
        // at s1e1r, x0 (0xd5087800: L=0,op0=1,op1=0,CRn=7,CRm=8,op2=0,rt=0)
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(0xd5087800);
        assertEquals(Aarch64AddressTranslateForm.S1E1R, op.form());
        assertEquals(0, op.rt());
    }

    @Test
    void atS1e1wX0() {
        // at s1e1w, x0 (0xd5087820: op2=1)
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(0xd5087820);
        assertEquals(Aarch64AddressTranslateForm.S1E1W, op.form());
    }

    @Test
    void atS1e0rX0() {
        // at s1e0r, x0 (0xd5087840: op2=2)
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(0xd5087840);
        assertEquals(Aarch64AddressTranslateForm.S1E0R, op.form());
    }

    @Test
    void atS1e0wXzr() {
        // at s1e0w, xzr (0xd508787f: op2=3, rt=31)
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(0xd508787f);
        assertEquals(Aarch64AddressTranslateForm.S1E0W, op.form());
        assertEquals(31, op.rt(), "rt=31 é XZR, mesma convenção de SystemRegister");
    }

    @Test
    void icIalluisContinuaCacheMaintenanceNoopAposOCarveOutDeAt() {
        // Regressão do achado real: ic ialluis (CRm=1, != AT CRm=8) precisa continuar caindo no
        // bucket genérico de cache maintenance, não ser afetada pelo carve-out de AT que também
        // vive em CRn=0b0111.
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) decodeAt(0xd5087100);
        assertEquals(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP, op.opcode());
    }

    // ── B10.6b/B10.6c: AT S1E2R/S1E2W/S1E3R/S1E3W — regimes EL2/EL3 puros, sem stage-2
    // ── (TTBR0_EL2/TTBR0_EL3 novos). Palavras construídas à mão a partir do encoding base de
    // ── atS1e1rX0 (não representáveis pelo corpus).

    @Test
    void atS1e2rX0() {
        // at s1e2r, x0 (op1=4/0b100, CRm=8, op2=0)
        int word = 0xd5087800 | (0b100 << 16);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S1E2R, op.form());
        assertEquals(0, op.rt());
    }

    @Test
    void atS1e2wX0() {
        // at s1e2w, x0 (op1=4, CRm=8, op2=1)
        int word = 0xd5087820 | (0b100 << 16);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S1E2W, op.form());
    }

    @Test
    void atS1e3rX0() {
        // at s1e3r, x0 (op1=6/0b110, CRm=8, op2=0)
        int word = 0xd5087800 | (0b110 << 16);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S1E3R, op.form());
        assertEquals(0, op.rt());
    }

    @Test
    void atS1e3wXzr() {
        // at s1e3w, xzr (op1=6, CRm=8, op2=1, rt=31)
        int word = 0xd508781f | (0b110 << 16) | (0b1 << 5);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S1E3W, op.form());
        assertEquals(31, op.rt());
    }

    @Test
    void atS1e3rReservedOp2StaysUnsupported() {
        // op1=6/0b110, op2=2 (reservado no regime EL3 — não existe S12E3*, EL3 não tem stage-2).
        int word = 0xd5087800 | (0b110 << 16) | (0b010 << 5);
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(word));
    }

    // ── B10.8: AT S12E1R/S12E1W/S12E0R/S12E0W — MESMO op1=4/0b100 de AT S1E2R/S1E2W acima,
    // ── distinguidas só por op2 (conferido contra cpregs-at.c real do QEMU: AT_S1E2R/AT_S1E2W =
    // ── op2 0/1; AT_S12E1R/S12E1W/S12E0R/S12E0W = op2 4/5/6/7 — op2 2/3 reservados). Palavras
    // ── construídas à mão a partir do encoding base de atS1e1rX0 (não representáveis pelo corpus).

    @Test
    void atS12e1rX0() {
        // at s12e1r, x0 (op1=4, CRm=8, op2=4, rt=0)
        int word = 0xd5087800 | (0b100 << 16) | (0b100 << 5);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S12E1R, op.form());
        assertEquals(0, op.rt());
    }

    @Test
    void atS12e1wX0() {
        // at s12e1w, x0 (op1=4, CRm=8, op2=5)
        int word = 0xd5087800 | (0b100 << 16) | (0b101 << 5);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S12E1W, op.form());
    }

    @Test
    void atS12e0rX0() {
        // at s12e0r, x0 (op1=4, CRm=8, op2=6)
        int word = 0xd5087800 | (0b100 << 16) | (0b110 << 5);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S12E0R, op.form());
    }

    @Test
    void atS12e0wXzr() {
        // at s12e0w, xzr (op1=4, CRm=8, op2=7, rt=31)
        int word = 0xd508787f | (0b100 << 16) | (0b111 << 5);
        Ir64Op.AddressTranslate op = (Ir64Op.AddressTranslate) decodeAt(word);
        assertEquals(Aarch64AddressTranslateForm.S12E0W, op.form());
        assertEquals(31, op.rt());
    }

    @Test
    void atS1e2ReservedOp2StaysUnsupported() {
        // op1=4/0b100, op2=2/3: reservado no regime EL2 (entre S1E2W=1 e S12E1R=4) — não pode ser
        // confundido nem com S1E2*/S12E* pelo switch de decodeAddressTranslateEl2.
        assertThrows(UnsupportedOperationException.class,
                () -> decodeAt(0xd5087800 | (0b100 << 16) | (0b010 << 5)));
        assertThrows(UnsupportedOperationException.class,
                () -> decodeAt(0xd5087800 | (0b100 << 16) | (0b011 << 5)));
    }

    @Test
    void atOp1EqualsFourOp2TwoStaysUnsupported() {
        // op2=2/3 (reservado dentro de op1=4, nem S1E2* nem S12E*) deve continuar unsupported.
        int word = 0xd5087800 | (0b100 << 16) | (0b010 << 5);
        assertThrows(UnsupportedOperationException.class, () -> decodeAt(word));
    }

    // ── B8.1: LDR/STR/LDP/STP restantes, LDXP/STXP, CAS/CASP, LDAR/STLR — apêndice do mesmo
    // ── corpus.s/.bin/.objdump.txt, offsets 0x49c-0x52c ─────────────────────────────────────────

    @Test
    void stnpNoAllocHintDoubleword() {
        // stnp x0, x1, [x2]: mesmo endereçamento funcional de STP offset (sem writeback) —
        // este emulador não modela cache/hints.
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x49c);
        assertFalse(op.load());
        assertEquals(0, op.rt());
        assertEquals(1, op.rt2());
        assertEquals(2, op.rn());
        assertTrue(op.wide());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
        assertFalse(op.signExtend());
    }

    @Test
    void ldnpNoAllocHintDoubleword() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x4a0);
        assertTrue(op.load());
        assertEquals(3, op.rt());
        assertEquals(4, op.rt2());
        assertEquals(5, op.rn());
        assertTrue(op.wide());
    }

    @Test
    void stnpNoAllocHintWord() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x4a4);
        assertFalse(op.load());
        assertFalse(op.wide());
    }

    @Test
    void ldnpNoAllocHintWord() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x4a8);
        assertTrue(op.load());
        assertFalse(op.wide());
    }

    @Test
    void ldpswOffset() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x4ac);
        assertTrue(op.load());
        assertEquals(0, op.rt());
        assertEquals(1, op.rt2());
        assertEquals(2, op.rn());
        assertTrue(op.signExtend());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
    }

    @Test
    void ldpswPreIndex() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x4b0);
        assertTrue(op.signExtend());
        assertEquals(Ir64AddressingMode.PRE_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void ldpswPostIndex() {
        Ir64Op.LoadStorePair op = (Ir64Op.LoadStorePair) DECODER.decode(memory, 0x4b4);
        assertTrue(op.signExtend());
        assertEquals(Ir64AddressingMode.POST_INDEX, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    private static void assertPrfmNoop(long offset) {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, offset);
        assertEquals(Ir64SystemInstructionOp.NOP_HINT, op.opcode());
    }

    @Test
    void prfmScaledUimm() {
        assertPrfmNoop(0x4b8);
    }

    @Test
    void prfumUnscaled() {
        assertPrfmNoop(0x4bc);
    }

    @Test
    void prfmRegisterOffset() {
        assertPrfmNoop(0x4c0);
    }

    @Test
    void ldtrUnprivilegedDoubleword() {
        // ldtr x0, [x1]: mesmo endereçamento funcional de LDUR (bug real corrigido pela B8.1 —
        // antes deste fix, idx=10+bit21=0 caía no ramo REGISTER_OFFSET, tratando o imm9 como
        // Rm/option/S).
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x4c4);
        assertEquals(0, op.rt());
        assertEquals(1, op.rn());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
        assertTrue(op.wide());
        assertFalse(op.signExtend());
    }

    @Test
    void sttrUnprivilegedDoubleword() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x4c8);
        assertEquals(2, op.rt());
        assertEquals(3, op.rn());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
    }

    @Test
    void ldtrUnprivilegedWord() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x4cc);
        assertFalse(op.wide());
        assertFalse(op.signExtend());
    }

    @Test
    void sttrUnprivilegedWord() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x4d0);
        assertFalse(op.wide());
    }

    @Test
    void ldtrsbSignExtendToX() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x4d4);
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertTrue(op.signExtend());
        assertTrue(op.wide());
    }

    @Test
    void ldtrshSignExtendToX() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x4d8);
        assertEquals(Ir64MemSize.HALF, op.size());
        assertTrue(op.signExtend());
        assertTrue(op.wide());
    }

    @Test
    void ldtrswSignExtendToX() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x4dc);
        assertEquals(Ir64MemSize.WORD, op.size());
        assertTrue(op.signExtend());
        assertTrue(op.wide());
    }

    @Test
    void ldxpDoubleword() {
        Ir64Op.LoadExclusivePair op = (Ir64Op.LoadExclusivePair) DECODER.decode(memory, 0x4e0);
        assertEquals(0, op.rt());
        assertEquals(1, op.rt2());
        assertEquals(2, op.rn());
        assertTrue(op.wide());
        assertFalse(op.acquireRelease());
    }

    @Test
    void stxpDoublewordPair() {
        // stxp w3, x4, x5, [x6]: Rs=status É SEMPRE W (w3), independente da largura do par
        // (x4/x5 aqui) — o decoder deriva `wide` só de `sz` (doubleword, sz=3).
        Ir64Op.StoreExclusivePair op = (Ir64Op.StoreExclusivePair) DECODER.decode(memory, 0x4e4);
        assertEquals(3, op.rs());
        assertEquals(4, op.rt());
        assertEquals(5, op.rt2());
        assertEquals(6, op.rn());
        assertTrue(op.wide());
    }

    @Test
    void ldaxpDoubleword() {
        Ir64Op.LoadExclusivePair op = (Ir64Op.LoadExclusivePair) DECODER.decode(memory, 0x4e8);
        assertEquals(7, op.rt());
        assertEquals(8, op.rt2());
        assertEquals(9, op.rn());
        assertTrue(op.acquireRelease());
    }

    @Test
    void stlxpDoubleword() {
        Ir64Op.StoreExclusivePair op = (Ir64Op.StoreExclusivePair) DECODER.decode(memory, 0x4ec);
        assertEquals(10, op.rs());
        assertEquals(11, op.rt());
        assertEquals(12, op.rt2());
        assertEquals(13, op.rn());
        assertTrue(op.acquireRelease());
    }

    @Test
    void ldxpWord() {
        Ir64Op.LoadExclusivePair op = (Ir64Op.LoadExclusivePair) DECODER.decode(memory, 0x4f0);
        assertEquals(14, op.rt());
        assertEquals(15, op.rt2());
        assertEquals(16, op.rn());
        assertFalse(op.wide());
    }

    @Test
    void stxpWord() {
        Ir64Op.StoreExclusivePair op = (Ir64Op.StoreExclusivePair) DECODER.decode(memory, 0x4f4);
        assertEquals(17, op.rs());
        assertEquals(18, op.rt());
        assertEquals(19, op.rt2());
        assertEquals(20, op.rn());
        assertFalse(op.wide());
    }

    @Test
    void ldarDoubleword() {
        // ldar x0, [x1]: reaproveita Load64 diretamente (sem monitor de exclusividade) — ver
        // Aarch64Decoder#decodeOrderedSingle.
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x4f8);
        assertEquals(0, op.rt());
        assertEquals(1, op.rn());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
        assertTrue(op.wide());
        assertFalse(op.signExtend());
    }

    @Test
    void stlrDoubleword() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x4fc);
        assertEquals(2, op.rt());
        assertEquals(3, op.rn());
        assertTrue(op.wide());
    }

    @Test
    void ldarWord() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x500);
        assertFalse(op.wide());
        assertEquals(Ir64MemSize.WORD, op.size());
    }

    @Test
    void stlrWord() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x504);
        assertFalse(op.wide());
        assertEquals(Ir64MemSize.WORD, op.size());
    }

    @Test
    void ldarbByte() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x508);
        assertEquals(Ir64MemSize.BYTE, op.size());
        assertFalse(op.signExtend());
    }

    @Test
    void stlrbByte() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x50c);
        assertEquals(Ir64MemSize.BYTE, op.size());
    }

    @Test
    void ldarhHalf() {
        Ir64Op.Load64 op = (Ir64Op.Load64) DECODER.decode(memory, 0x510);
        assertEquals(Ir64MemSize.HALF, op.size());
    }

    @Test
    void stlrhHalf() {
        Ir64Op.Store64 op = (Ir64Op.Store64) DECODER.decode(memory, 0x514);
        assertEquals(Ir64MemSize.HALF, op.size());
    }

    @Test
    void casWord() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) DECODER.decode(memory, 0x518);
        assertEquals(0, op.rs());
        assertEquals(1, op.rt());
        assertEquals(2, op.rn());
        assertEquals(Ir64MemSize.WORD, op.size());
    }

    @Test
    void casDoubleword() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) DECODER.decode(memory, 0x51c);
        assertEquals(3, op.rs());
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
    }

    @Test
    void casbByte() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) DECODER.decode(memory, 0x520);
        assertEquals(Ir64MemSize.BYTE, op.size());
    }

    @Test
    void cashHalf() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) DECODER.decode(memory, 0x524);
        assertEquals(Ir64MemSize.HALF, op.size());
    }

    @Test
    void caspWordPair() {
        Ir64Op.CompareAndSwapPair op = (Ir64Op.CompareAndSwapPair) DECODER.decode(memory, 0x528);
        assertEquals(12, op.rs());
        assertEquals(14, op.rt());
        assertEquals(16, op.rn());
        assertFalse(op.wide());
    }

    @Test
    void caspaDoublewordPair() {
        Ir64Op.CompareAndSwapPair op = (Ir64Op.CompareAndSwapPair) DECODER.decode(memory, 0x52c);
        assertEquals(18, op.rs());
        assertEquals(20, op.rt());
        assertEquals(22, op.rn());
        assertTrue(op.wide());
    }

    // ── B8.2 ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void adc() {
        Ir64Op.AluWithCarry op = (Ir64Op.AluWithCarry) DECODER.decode(memory, 0x530);
        assertFalse(op.subtract());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertTrue(op.wide());
        assertFalse(op.setFlags());
    }

    @Test
    void adcsSetsFlags() {
        Ir64Op.AluWithCarry op = (Ir64Op.AluWithCarry) DECODER.decode(memory, 0x534);
        assertFalse(op.subtract());
        assertEquals(3, op.dst());
        assertEquals(4, op.src1());
        assertEquals(5, op.src2());
        assertTrue(op.setFlags());
    }

    @Test
    void sbc() {
        Ir64Op.AluWithCarry op = (Ir64Op.AluWithCarry) DECODER.decode(memory, 0x538);
        assertTrue(op.subtract());
        assertEquals(6, op.dst());
        assertEquals(7, op.src1());
        assertEquals(8, op.src2());
        assertFalse(op.setFlags());
    }

    @Test
    void sbcsSetsFlags() {
        Ir64Op.AluWithCarry op = (Ir64Op.AluWithCarry) DECODER.decode(memory, 0x53c);
        assertTrue(op.subtract());
        assertEquals(9, op.dst());
        assertTrue(op.setFlags());
    }

    @Test
    void adcNarrow() {
        Ir64Op.AluWithCarry op = (Ir64Op.AluWithCarry) DECODER.decode(memory, 0x540);
        assertEquals(12, op.dst());
        assertEquals(13, op.src1());
        assertEquals(14, op.src2());
        assertFalse(op.wide());
    }

    @Test
    void sbcNarrow() {
        Ir64Op.AluWithCarry op = (Ir64Op.AluWithCarry) DECODER.decode(memory, 0x544);
        assertTrue(op.subtract());
        assertEquals(15, op.dst());
        assertFalse(op.wide());
    }

    @Test
    void extrWide() {
        Ir64Op.Extract op = (Ir64Op.Extract) DECODER.decode(memory, 0x548);
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertEquals(4, op.lsb());
        assertTrue(op.wide());
    }

    @Test
    void extrNarrow() {
        Ir64Op.Extract op = (Ir64Op.Extract) DECODER.decode(memory, 0x54c);
        assertEquals(3, op.dst());
        assertEquals(4, op.src1());
        assertEquals(5, op.src2());
        assertEquals(8, op.lsb());
        assertFalse(op.wide());
    }

    @Test
    void rbitWide() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x550);
        assertEquals(Ir64OneSourceOp.RBIT, op.opcode());
        assertEquals(0, op.dst());
        assertEquals(1, op.src());
        assertTrue(op.wide());
    }

    @Test
    void rbitNarrow() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x554);
        assertEquals(Ir64OneSourceOp.RBIT, op.opcode());
        assertEquals(2, op.dst());
        assertEquals(3, op.src());
        assertFalse(op.wide());
    }

    @Test
    void rev16Wide() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x558);
        assertEquals(Ir64OneSourceOp.REV16, op.opcode());
        assertEquals(4, op.dst());
        assertEquals(5, op.src());
        assertTrue(op.wide());
    }

    @Test
    void rev16Narrow() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x55c);
        assertEquals(Ir64OneSourceOp.REV16, op.opcode());
        assertFalse(op.wide());
    }

    @Test
    void rev32() {
        // "rev32 x8,x9" — mesmo opcode de "rev w,w" (Ir64OneSourceOp#REV32), aqui na forma X.
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x560);
        assertEquals(Ir64OneSourceOp.REV32, op.opcode());
        assertEquals(8, op.dst());
        assertEquals(9, op.src());
        assertTrue(op.wide());
    }

    @Test
    void revWideIsRev64() {
        // "rev x10,x11" (opcode=0b000011, sf=1) é o REV64 do encoding — só existe na forma X.
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x564);
        assertEquals(Ir64OneSourceOp.REV64, op.opcode());
        assertEquals(10, op.dst());
        assertEquals(11, op.src());
    }

    @Test
    void revNarrowIsRev32Opcode() {
        // "rev w12,w13" — MESMO opcode de "rev32 x,x" (0b000010), aqui na forma W (única forma
        // possível: REV64 não existe com sf=0).
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x568);
        assertEquals(Ir64OneSourceOp.REV32, op.opcode());
        assertEquals(12, op.dst());
        assertEquals(13, op.src());
        assertFalse(op.wide());
    }

    @Test
    void clzWide() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x56c);
        assertEquals(Ir64OneSourceOp.CLZ, op.opcode());
        assertEquals(14, op.dst());
        assertEquals(15, op.src());
    }

    @Test
    void clzNarrow() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x570);
        assertEquals(Ir64OneSourceOp.CLZ, op.opcode());
        assertFalse(op.wide());
    }

    @Test
    void clsWide() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x574);
        assertEquals(Ir64OneSourceOp.CLS, op.opcode());
        assertEquals(18, op.dst());
        assertEquals(19, op.src());
    }

    @Test
    void clsNarrow() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x578);
        assertEquals(Ir64OneSourceOp.CLS, op.opcode());
        assertFalse(op.wide());
    }

    @Test
    void cntWide() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x57c);
        assertEquals(Ir64OneSourceOp.CNT, op.opcode());
        assertEquals(22, op.dst());
        assertEquals(23, op.src());
    }

    @Test
    void cntNarrow() {
        Ir64Op.DataProcessing1Source op = (Ir64Op.DataProcessing1Source) DECODER.decode(memory, 0x580);
        assertEquals(Ir64OneSourceOp.CNT, op.opcode());
        assertFalse(op.wide());
    }

    @Test
    void smaddl() {
        Ir64Op.MultiplyAccumulateLong op = (Ir64Op.MultiplyAccumulateLong) DECODER.decode(memory, 0x584);
        assertFalse(op.subtract());
        assertTrue(op.signed());
        assertEquals(0, op.dst());
        assertEquals(1, op.src1());
        assertEquals(2, op.src2());
        assertEquals(3, op.accumulator());
    }

    @Test
    void smsubl() {
        Ir64Op.MultiplyAccumulateLong op = (Ir64Op.MultiplyAccumulateLong) DECODER.decode(memory, 0x588);
        assertTrue(op.subtract());
        assertTrue(op.signed());
        assertEquals(4, op.dst());
        assertEquals(7, op.accumulator());
    }

    @Test
    void umaddl() {
        Ir64Op.MultiplyAccumulateLong op = (Ir64Op.MultiplyAccumulateLong) DECODER.decode(memory, 0x58c);
        assertFalse(op.subtract());
        assertFalse(op.signed());
        assertEquals(8, op.dst());
        assertEquals(11, op.accumulator());
    }

    @Test
    void umsubl() {
        Ir64Op.MultiplyAccumulateLong op = (Ir64Op.MultiplyAccumulateLong) DECODER.decode(memory, 0x590);
        assertTrue(op.subtract());
        assertFalse(op.signed());
        assertEquals(12, op.dst());
        assertEquals(15, op.accumulator());
    }

    @Test
    void smulh() {
        Ir64Op.MultiplyHigh op = (Ir64Op.MultiplyHigh) DECODER.decode(memory, 0x594);
        assertTrue(op.signed());
        assertEquals(16, op.dst());
        assertEquals(17, op.src1());
        assertEquals(18, op.src2());
    }

    @Test
    void umulh() {
        Ir64Op.MultiplyHigh op = (Ir64Op.MultiplyHigh) DECODER.decode(memory, 0x598);
        assertFalse(op.signed());
        assertEquals(19, op.dst());
        assertEquals(20, op.src1());
        assertEquals(21, op.src2());
    }

    @Test
    void rmif() {
        // B11.7: FEAT_FlagM (ARMv8.4-A) gateada — o DECODER default (ARMv8.0-A) agora rejeita;
        // decodificação bem-sucedida migrou para Aarch64FlagManipulationDecoderTest.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x59c));
    }

    @Test
    void setf8() {
        // B11.7: mesmo gate de FEAT_FlagM que rmif() acima.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5a0));
    }

    @Test
    void setf16() {
        // B11.7: mesmo gate de FEAT_FlagM que rmif() acima.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5a4));
    }

    @Test
    void cfinv() {
        // B11.9: gateado por FEAT_FlagM (Aarch64Feature.FLAG_MANIPULATION) — decoder default
        // (ARMv8.0-A) não tem a feature. Decode com sucesso migrou para
        // Aarch64FlagManipulation2DecoderTest.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5a8));
    }

    @Test
    void xaflag() {
        // B11.9: gateado por FEAT_FlagM2 (Aarch64Feature.FLAG_MANIPULATION_2) — mesmo motivo.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5ac));
    }

    @Test
    void axflag() {
        // B11.9: gateado por FEAT_FlagM2 (Aarch64Feature.FLAG_MANIPULATION_2) — mesmo motivo.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5b0));
    }

    // ── B8.3: WFET/WFIT/CLREX/SB/BRK/HLT/MSR(immediate)/TLBI per-VA (offsets 0x5b4+) ──────────

    @Test
    void wfet() {
        // B11.6: FEAT_WFxT (ARMv8.7-A) agora é gateada — o decoder DEFAULT (ARMv8.0-A) rejeita.
        // Decodificação bem-sucedida com ARMV8_7_A: ver Aarch64AdvSimdWfxtDecoderTest.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5b4));
    }

    @Test
    void wfit() {
        // B11.6: mesmo gate de #wfet — ver Aarch64AdvSimdWfxtDecoderTest para o caso positivo.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5b8));
    }

    @Test
    void clrex() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5bc);
        assertEquals(Ir64SystemInstructionOp.CLEAR_EXCLUSIVE, op.opcode());
    }

    @Test
    void sb() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5c0);
        assertEquals(Ir64SystemInstructionOp.BARRIER, op.opcode());
    }

    @Test
    void brk() {
        Ir64Op.Breakpoint op = (Ir64Op.Breakpoint) DECODER.decode(memory, 0x5c4);
        assertEquals(0x1234, op.immediate());
    }

    @Test
    void hlt() {
        assertInstanceOf(Ir64Op.UndefinedInstructionTrap.class, DECODER.decode(memory, 0x5c8));
    }

    @Test
    void msrUao() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5cc);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void msrPan() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5d0);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void msrSpsel() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5d4);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void msrSsbs() {
        // "ssbs" é o mnemônico real do assembler para o campo que o QEMU (e este decoder) chama
        // internamente de SBSS.
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5d8);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void msrTco() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5dc);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void msrDit() {
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5e0);
        assertEquals(Ir64SystemInstructionOp.PSTATE_FIELD_NOP, op.opcode());
    }

    @Test
    void msrAllint() {
        // B11.8: FEAT_NMI (ARMv8.8-A) gateada — o DECODER default (ARMv8.0-A) agora rejeita;
        // decodificação bem-sucedida migrou para Aarch64NmiDecoderTest.
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5e4));
    }

    @Test
    void msrDaifSet() {
        Ir64Op.InterruptMask op = (Ir64Op.InterruptMask) DECODER.decode(memory, 0x5e8);
        assertTrue(op.set());
        assertEquals(0xF, op.mask());
    }

    @Test
    void msrDaifClear() {
        Ir64Op.InterruptMask op = (Ir64Op.InterruptMask) DECODER.decode(memory, 0x5ec);
        assertFalse(op.set());
        assertEquals(0xF, op.mask());
    }

    @Test
    void tlbiVaePerVaFormDecodesAsInvalidateAll() {
        // tlbi vae1, x0 — B8.3 amplia TLBI para "qualquer forma do regime EL1", ver
        // decodeSystemInstructionSys.
        Ir64Op.SystemInstruction op = (Ir64Op.SystemInstruction) DECODER.decode(memory, 0x5f0);
        assertEquals(Ir64SystemInstructionOp.TLBI_ALL, op.opcode());
    }

    // ── B8.3: fora de escopo (docs/isa-nao-aplicavel.tsv) — continuam UNIMPLEMENTED de verdade ──

    @Test
    void braaPauthBranchUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5f4));
    }

    @Test
    void blraaPauthBranchUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5f8));
    }

    @Test
    void retaaPauthBranchUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x5fc));
    }

    @Test
    void eretaaPauthBranchUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x600));
    }

    @Test
    void cbccCompareBranchUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> DECODER.decode(memory, 0x604));
    }

    // ── B8.4: FP escalar aritmética — offsets 0x608+, apêndice do mesmo corpus.s/corpus.bin/ ──────
    // ── corpus.objdump.txt ───────────────────────────────────────────────────────────────────────

    @Test
    void fmaxSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x608);
        assertEquals(Ir64Op.Fp64Operation.MAX, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(4, op.vd());
        assertEquals(5, op.vn());
        assertEquals(6, op.vm());
    }

    @Test
    void fmaxDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x60c);
        assertEquals(Ir64Op.Fp64Operation.MAX, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fminSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x610);
        assertEquals(Ir64Op.Fp64Operation.MIN, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(7, op.vd());
        assertEquals(8, op.vn());
        assertEquals(9, op.vm());
    }

    @Test
    void fminDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x614);
        assertEquals(Ir64Op.Fp64Operation.MIN, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fmaxnmSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x618);
        assertEquals(Ir64Op.Fp64Operation.MAXNM, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(10, op.vd());
        assertEquals(11, op.vn());
        assertEquals(12, op.vm());
    }

    @Test
    void fmaxnmDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x61c);
        assertEquals(Ir64Op.Fp64Operation.MAXNM, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fminnmSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x620);
        assertEquals(Ir64Op.Fp64Operation.MINNM, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(13, op.vd());
        assertEquals(14, op.vn());
        assertEquals(15, op.vm());
    }

    @Test
    void fminnmDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x624);
        assertEquals(Ir64Op.Fp64Operation.MINNM, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fnmulSingle() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x628);
        assertEquals(Ir64Op.Fp64Operation.NMUL, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(1, op.vd());
        assertEquals(2, op.vn());
        assertEquals(3, op.vm());
    }

    @Test
    void fnmulDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x62c);
        assertEquals(Ir64Op.Fp64Operation.NMUL, op.op());
        assertTrue(op.doublePrecision());
    }

    @Test
    void fsqrtSingle() {
        // fsqrt s16, s17 — 1-source (unário), operando único em `vm` (Rn do encoding), mesma
        // convenção de fnegSingle/fabsSingle.
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x630);
        assertEquals(Ir64Op.Fp64Operation.SQRT, op.op());
        assertFalse(op.doublePrecision());
        assertEquals(16, op.vd());
        assertEquals(17, op.vm());
    }

    @Test
    void fsqrtDouble() {
        Ir64Op.Fp64Alu op = (Ir64Op.Fp64Alu) DECODER.decode(memory, 0x634);
        assertEquals(Ir64Op.Fp64Operation.SQRT, op.op());
        assertTrue(op.doublePrecision());
        assertEquals(16, op.vd());
        assertEquals(17, op.vm());
    }

    @Test
    void fmaddSingle() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x638);
        assertFalse(op.doublePrecision());
        assertFalse(op.negateAddend());
        assertFalse(op.negateProduct());
        assertEquals(18, op.vd());
        assertEquals(19, op.vn());
        assertEquals(20, op.vm());
        assertEquals(21, op.va());
    }

    @Test
    void fmaddDouble() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x63c);
        assertTrue(op.doublePrecision());
        assertFalse(op.negateAddend());
        assertFalse(op.negateProduct());
        assertEquals(18, op.vd());
        assertEquals(19, op.vn());
        assertEquals(20, op.vm());
        assertEquals(21, op.va());
    }

    @Test
    void fmsubSingle() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x640);
        assertFalse(op.doublePrecision());
        assertFalse(op.negateAddend());
        assertTrue(op.negateProduct());
        assertEquals(22, op.vd());
        assertEquals(23, op.vn());
        assertEquals(24, op.vm());
        assertEquals(25, op.va());
    }

    @Test
    void fmsubDouble() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x644);
        assertTrue(op.doublePrecision());
        assertFalse(op.negateAddend());
        assertTrue(op.negateProduct());
    }

    @Test
    void fnmaddSingle() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x648);
        assertFalse(op.doublePrecision());
        assertTrue(op.negateAddend());
        assertTrue(op.negateProduct());
        assertEquals(26, op.vd());
        assertEquals(27, op.vn());
        assertEquals(28, op.vm());
        assertEquals(29, op.va());
    }

    @Test
    void fnmaddDouble() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x64c);
        assertTrue(op.doublePrecision());
        assertTrue(op.negateAddend());
        assertTrue(op.negateProduct());
    }

    @Test
    void fnmsubSingle() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x650);
        assertFalse(op.doublePrecision());
        assertTrue(op.negateAddend());
        assertFalse(op.negateProduct());
        assertEquals(30, op.vd());
        assertEquals(31, op.vn());
        assertEquals(0, op.vm());
        assertEquals(1, op.va());
    }

    @Test
    void fnmsubDouble() {
        Ir64Op.Fp64MultiplyAdd op = (Ir64Op.Fp64MultiplyAdd) DECODER.decode(memory, 0x654);
        assertTrue(op.doublePrecision());
        assertTrue(op.negateAddend());
        assertFalse(op.negateProduct());
    }

    // ── B8.5: FCSEL/FCCMP(E) ──────────────────────────────────────────────────────────────────

    @Test
    void fcselDouble() {
        Ir64Op.Fp64ConditionalSelect op = (Ir64Op.Fp64ConditionalSelect) DECODER.decode(memory, 0x65c);
        assertTrue(op.doublePrecision());
        assertEquals(1, op.vd());
        assertEquals(2, op.vn());
        assertEquals(3, op.vm());
        assertEquals(Ir64Condition.NE, op.condition());
    }

    @Test
    void fccmpSingle() {
        // fccmp s1, s2, #0xd, pl — nzcv=0xd quando a condição é falsa.
        Ir64Op.Fp64ConditionalCompare op = (Ir64Op.Fp64ConditionalCompare) DECODER.decode(memory, 0x660);
        assertFalse(op.doublePrecision());
        assertFalse(op.signalOnQuietNaN());
        assertEquals(1, op.vn());
        assertEquals(2, op.vm());
        assertEquals(Ir64Condition.PL, op.condition());
        assertEquals(0xd, op.nzcv());
    }

    @Test
    void fccmpDouble() {
        Ir64Op.Fp64ConditionalCompare op = (Ir64Op.Fp64ConditionalCompare) DECODER.decode(memory, 0x664);
        assertTrue(op.doublePrecision());
        assertEquals(4, op.vn());
        assertEquals(5, op.vm());
        assertEquals(Ir64Condition.GT, op.condition());
        assertEquals(0x3, op.nzcv());
    }

    @Test
    void fccmpe() {
        // fccmpe s1, s2, #0x5, lt — só o bit `E` muda (sem efeito observável, ver Javadoc).
        Ir64Op.Fp64ConditionalCompare op = (Ir64Op.Fp64ConditionalCompare) DECODER.decode(memory, 0x668);
        assertTrue(op.signalOnQuietNaN());
        assertEquals(Ir64Condition.LT, op.condition());
        assertEquals(0x5, op.nzcv());
    }

    // ── B8.5: FRINTx (1-source) ───────────────────────────────────────────────────────────────

    @Test
    void frintnSingle() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x66c);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
        assertFalse(op.doublePrecision());
        assertEquals(0, op.vd());
        assertEquals(1, op.vn());
    }

    @Test
    void frintnDouble() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x670);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
        assertTrue(op.doublePrecision());
    }

    @Test
    void frintp() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x674);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY, op.direction());
    }

    @Test
    void frintm() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x678);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY, op.direction());
    }

    @Test
    void frintz() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x67c);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.direction());
    }

    @Test
    void frinta() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x680);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, op.direction());
    }

    @Test
    void frintx() {
        // frintx: MESMA direção de frintn — ver Javadoc de Ir64Op.Fp64Round (FPCR.RMode não
        // modelado em A64).
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x684);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
    }

    @Test
    void frinti() {
        Ir64Op.Fp64Round op = (Ir64Op.Fp64Round) DECODER.decode(memory, 0x688);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
    }

    // ── B8.5: conversão FP<->inteiro (registrador geral), sem escala ─────────────────────────

    @Test
    void scvtfDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x690);
        assertTrue(op.toFloat());
        assertTrue(op.signed());
        assertTrue(op.doublePrecision());
        assertTrue(op.wide());
        assertEquals(0, op.fixedPointFractionBits());
        assertEquals(1, op.fpReg());
        assertEquals(1, op.gpReg());
    }

    @Test
    void ucvtfSingleW() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x694);
        assertTrue(op.toFloat());
        assertFalse(op.signed());
        assertFalse(op.doublePrecision());
        assertFalse(op.wide());
        assertEquals(2, op.fpReg());
        assertEquals(2, op.gpReg());
    }

    @Test
    void fcvtnsSingleW() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x69c);
        assertFalse(op.toFloat());
        assertTrue(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.rounding());
        assertEquals(3, op.fpReg());
        assertEquals(3, op.gpReg());
    }

    @Test
    void fcvtnu() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6a0);
        assertFalse(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.rounding());
    }

    @Test
    void fcvtps() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6a4);
        assertTrue(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY, op.rounding());
    }

    @Test
    void fcvtpu() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6a8);
        assertFalse(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY, op.rounding());
    }

    @Test
    void fcvtms() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6ac);
        assertTrue(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY, op.rounding());
    }

    @Test
    void fcvtmu() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6b0);
        assertFalse(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY, op.rounding());
    }

    @Test
    void fcvtzsSingleW() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6b4);
        assertTrue(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.rounding());
        assertEquals(0, op.fixedPointFractionBits());
    }

    @Test
    void fcvtzu() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6b8);
        assertFalse(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.rounding());
    }

    @Test
    void fcvtas() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6bc);
        assertTrue(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, op.rounding());
    }

    @Test
    void fcvtau() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6c0);
        assertFalse(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_AWAY, op.rounding());
    }

    @Test
    void fcvtzsDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6c4);
        assertFalse(op.toFloat());
        assertTrue(op.signed());
        assertTrue(op.doublePrecision());
        assertTrue(op.wide());
        assertEquals(13, op.fpReg());
        assertEquals(13, op.gpReg());
    }

    @Test
    void fcvtzuDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6c8);
        assertFalse(op.signed());
        assertTrue(op.wide());
    }

    // ── B8.5: conversão FP<->ponto-fixo (registrador geral), COM escala ──────────────────────

    @Test
    void scvtfFixedSingleW() {
        // scvtf s15, w15, #3 — shift = 32 - raw (raw=29 no encoding real).
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6cc);
        assertTrue(op.toFloat());
        assertTrue(op.signed());
        assertFalse(op.wide());
        assertEquals(3, op.fixedPointFractionBits());
        assertEquals(15, op.fpReg());
        assertEquals(15, op.gpReg());
    }

    @Test
    void scvtfFixedDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6d0);
        assertTrue(op.toFloat());
        assertTrue(op.wide());
        assertEquals(10, op.fixedPointFractionBits());
        assertEquals(16, op.fpReg());
        assertEquals(16, op.gpReg());
    }

    @Test
    void ucvtfFixedSingleW() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6d4);
        assertFalse(op.signed());
        assertEquals(5, op.fixedPointFractionBits());
    }

    @Test
    void ucvtfFixedDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6d8);
        assertFalse(op.signed());
        assertTrue(op.wide());
        assertEquals(20, op.fixedPointFractionBits());
    }

    @Test
    void fcvtzsFixedSingleW() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6dc);
        assertFalse(op.toFloat());
        assertTrue(op.signed());
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.rounding());
        assertEquals(7, op.fixedPointFractionBits());
        assertEquals(19, op.fpReg());
        assertEquals(19, op.gpReg());
    }

    @Test
    void fcvtzuFixedSingleW() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6e0);
        assertFalse(op.signed());
        assertEquals(12, op.fixedPointFractionBits());
    }

    @Test
    void fcvtzsFixedDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6e4);
        assertTrue(op.wide());
        assertEquals(30, op.fixedPointFractionBits());
    }

    @Test
    void fcvtzuFixedDoubleX() {
        Ir64Op.Fp64IntegerConvert op = (Ir64Op.Fp64IntegerConvert) DECODER.decode(memory, 0x6e8);
        assertTrue(op.wide());
        assertFalse(op.signed());
        assertEquals(40, op.fixedPointFractionBits());
    }

    // ── B8.5: FMOV registrador-geral<->FP (cópia crua de bits) ───────────────────────────────

    @Test
    void fmovToGpSingle() {
        // fmov w23, s23 — FP->GP (toFloat=false).
        Ir64Op.Fp64GeneralRegisterMove op = (Ir64Op.Fp64GeneralRegisterMove) DECODER.decode(memory, 0x6ec);
        assertFalse(op.toFloat());
        assertFalse(op.wide());
        assertEquals(23, op.fpReg());
        assertEquals(23, op.gpReg());
    }

    @Test
    void fmovToFpSingle() {
        // fmov s24, w24 — GP->FP (toFloat=true).
        Ir64Op.Fp64GeneralRegisterMove op = (Ir64Op.Fp64GeneralRegisterMove) DECODER.decode(memory, 0x6f0);
        assertTrue(op.toFloat());
        assertFalse(op.wide());
        assertEquals(24, op.fpReg());
        assertEquals(24, op.gpReg());
    }

    @Test
    void fmovToGpDouble() {
        // fmov x25, d25 — FP->GP, 64 bits.
        Ir64Op.Fp64GeneralRegisterMove op = (Ir64Op.Fp64GeneralRegisterMove) DECODER.decode(memory, 0x6f4);
        assertFalse(op.toFloat());
        assertTrue(op.wide());
        assertEquals(25, op.fpReg());
        assertEquals(25, op.gpReg());
    }

    @Test
    void fmovToFpDouble() {
        // fmov d26, x26 — GP->FP, 64 bits.
        Ir64Op.Fp64GeneralRegisterMove op = (Ir64Op.Fp64GeneralRegisterMove) DECODER.decode(memory, 0x6f8);
        assertTrue(op.toFloat());
        assertTrue(op.wide());
        assertEquals(26, op.fpReg());
        assertEquals(26, op.gpReg());
    }
}
