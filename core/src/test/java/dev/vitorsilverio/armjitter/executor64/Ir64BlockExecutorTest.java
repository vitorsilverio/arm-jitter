package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Vetores de instrução ponta a ponta (decode real + execução) para os casos que a task B6.1
/// pede explicitamente: XZR como destino, `ADRP` alinhando 4 KiB, `MOVK` compondo um endereço de
/// 64 bits e `CBZ` com `W` vs `X`. As palavras usadas aqui vêm do mesmo corpus assemblado por
/// `aarch64-none-elf-as` usado em {@code Aarch64DecoderCorpusTest} (mesmos offsets).
class Ir64BlockExecutorTest {
    private static Aarch64Core newCore(int memorySizeBytes) {
        TestAddressSpace raw = new TestAddressSpace(memorySizeBytes);
        AddressSpace64 memory = AddressSpace64.wrapping(raw);
        return new Aarch64Core(memory);
    }

    private static void putWord(Aarch64Core core, long address, int word) {
        core.memory().write32(address, word);
    }

    @Test
    void movzXzrDestinationDiscardsWrite() {
        Aarch64Core core = newCore(16);
        // movz xzr, #1 — Rd=31 no encoding é XZR aqui (MOVZ não tem forma SP).
        putWord(core, 0, 0xd280003f);
        core.setX(0, 0xDEAD_BEEFL); // sentinela em X0, só para garantir que nada vazou

        new Ir64BlockExecutor().step(core);

        assertEquals(0L, core.x(31), "escrita em XZR deve ser descartada");
        assertEquals(4L, core.pc());
        assertEquals(0xDEAD_BEEFL, core.x(0), "X0 não deveria ser afetado por Rd=31");
    }

    @Test
    void adrpAlignsBaseTo4KiBPage() {
        Aarch64Core core = newCore(16);
        // adrp x2, _start assemblado em um endereço NÃO alinhado a 4 KiB (0x1008): o resultado
        // deve ser a base da PÁGINA (0x1000) + o deslocamento decodificado, não o endereço exato
        // da instrução.
        long instructionAddress = 0x1008;
        core.setProgramCounter(instructionAddress);
        TestAddressSpace raw = new TestAddressSpace(0x2000);
        raw.put32((int) instructionAddress, 0x90000002); // adrp x2, _start (offset=0 páginas)
        Aarch64Core pageCore = new Aarch64Core(AddressSpace64.wrapping(raw));
        pageCore.setProgramCounter(instructionAddress);

        new Ir64BlockExecutor().step(pageCore);

        assertEquals(0x1000L, pageCore.x(2), "ADRP deve alinhar a base a 4 KiB antes de somar");
        assertEquals(instructionAddress + 4, pageCore.pc());
    }

    @Test
    void movkComposes64BitAddressAcrossFourInstructions() {
        Aarch64Core core = newCore(32);
        // movz x0, #0x0001, movk x0,#0x0002 lsl#16, movk x0,#0x0003 lsl#32, movk x0,#0x0004 lsl#48
        // monta 0x0004_0003_0002_0001 em X0, uma quarta halfword de cada vez.
        putWord(core, 0x00, 0xd2800020); // movz x0, #1
        putWord(core, 0x04, 0xf2a00040); // movk x0, #2, lsl #16
        putWord(core, 0x08, 0xf2c00060); // movk x0, #3, lsl #32
        putWord(core, 0x0c, 0xf2e00080); // movk x0, #4, lsl #48

        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.run(core, 4);

        assertEquals(0x0004_0003_0002_0001L, core.x(0));
        assertEquals(0x10L, core.pc());
    }

    @Test
    void cbzWideComparesFull64Bits() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xb4000040); // cbz x0, +8 (imm19=1 -> offset 4*1=4... use explicit below)
        // Construímos o cenário manualmente para não depender de um imm19 específico: X0 tem um
        // valor cuja metade baixa é zero mas a alta não — só a forma X (wide) deve tratar isso
        // como diferente de zero.
        core.setX(0, 0x1_0000_0000L);

        new Ir64BlockExecutor().step(core);

        // cbz x0 (wide=true): x0 != 0 (bit 32 setado) -> branch NÃO tomado -> PC apenas avança.
        assertEquals(4L, core.pc());
    }

    @Test
    void cbzNarrowIgnoresHighBits() {
        Aarch64Core core = newCore(16);
        // cbz w0, label: mesma palavra do corpus, mas em W (sf=0) — offset 0x70 do corpus real:
        // 0x34000101 é "cbz w1, label1"; aqui usamos w0 para focar no bit de largura.
        // Construímos a palavra a partir do formato: sf=0,011010,op=0,imm19,Rt=0.
        int imm19 = 2; // offset = 2*4 = 8 bytes
        int word = (0 << 31) | (0b011010 << 25) | (0 << 24) | (imm19 << 5) | 0;
        putWord(core, 0, word);
        // X0 tem os 32 bits baixos zerados mas os altos setados: em W, deve contar como zero.
        core.setX(0, 0xFFFF_FFFF_0000_0000L);

        new Ir64BlockExecutor().step(core);

        assertEquals(8L, core.pc(), "cbz w0 deve considerar só os 32 bits baixos (zero) e desviar");
    }

    @Test
    void unconditionalBranchTakenUpdatesPc() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x14000002); // b +8
        new Ir64BlockExecutor().step(core);
        assertEquals(8L, core.pc());
    }

    @Test
    void blSetsLinkRegister() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x94000002); // bl +8
        new Ir64BlockExecutor().step(core);
        assertEquals(8L, core.pc());
        assertEquals(4L, core.x(30));
    }

    @Test
    void addSetsFlagsCorrectly() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xb10000e6); // adds x6, x7, #0 (do corpus real)
        core.setX(7, 0);
        new Ir64BlockExecutor().step(core);
        assertEquals(0L, core.x(6));
        assertTrue(core.pstate().zero());
        assertFalse(core.pstate().negative());
    }

    @Test
    void retReturnsToLinkRegisterValue() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xd65f03c0); // ret (implícito x30)
        core.setX(30, 0x200L);
        new Ir64BlockExecutor().step(core);
        assertEquals(0x200L, core.pc());
    }

    // ── B6.2: loads/stores ──────────────────────────────────────────────────────────────────

    @Test
    void strThenLdrDoublewordUnsignedOffsetRoundTrip() {
        Aarch64Core core = newCore(64);
        core.setX(1, 8); // base
        core.setX(2, 0x1122_3344_5566_7788L);
        putWord(core, 0, 0xf9000422); // str x2, [x1, #8]
        putWord(core, 4, 0xf9400423); // ldr x3, [x1, #8]

        new Ir64BlockExecutor().run(core, 2);

        assertEquals(0x1122_3344_5566_7788L, core.x(3));
    }

    @Test
    void ldrsbSignExtendsByteToX() {
        Aarch64Core core = newCore(32);
        core.setX(1, 16); // longe do endereço da própria instrução (0), evita colisão
        core.memory().write8(16, 0xFF); // -1 como byte
        putWord(core, 0, 0x39800020); // ldrsb x0, [x1]

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(0));
    }

    @Test
    void ldrsbSignExtendsByteToWZeroesUpperBits() {
        Aarch64Core core = newCore(32);
        core.setX(1, 16);
        core.memory().write8(16, 0xFF);
        core.setX(0, 0xFFFF_FFFF_FFFF_FFFFL);
        putWord(core, 0, 0x39c00020); // ldrsb w0, [x1]

        new Ir64BlockExecutor().step(core);

        assertEquals(0x0000_0000_FFFF_FFFFL, core.x(0),
                "LDRSB para W estende sinal para 32 bits e zera os 32 altos");
    }

    @Test
    void ldrbZeroExtendsRegardlessOfHighBits() {
        Aarch64Core core = newCore(32);
        core.setX(1, 16);
        core.memory().write8(16, 0xFF);
        core.setX(0, 0xFFFF_FFFF_FFFF_FFFFL);
        putWord(core, 0, 0x39400020); // ldrb w0, [x1]

        new Ir64BlockExecutor().step(core);

        assertEquals(0xFFL, core.x(0), "LDRB zero-estende, nunca estende sinal");
    }

    @Test
    void lduExpandStureUnscaledNegativeOffset() {
        Aarch64Core core = newCore(64);
        core.setX(1, 32);
        core.setX(2, 0xABCDEF0123456789L);
        putWord(core, 0, 0xf81f8022); // stur x2, [x1, #-8]
        putWord(core, 4, 0xf85f8023); // ldur x3, [x1, #-8]

        new Ir64BlockExecutor().run(core, 2);

        assertEquals(0xABCDEF0123456789L, core.x(3));
    }

    @Test
    void ldrPreIndexWritesBackAddress() {
        Aarch64Core core = newCore(64);
        core.setX(1, 8);
        core.memory().write64(16, 0x1234L);
        putWord(core, 0, 0xf8408c20); // ldr x0, [x1, #8]!

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1234L, core.x(0));
        assertEquals(16L, core.x(1), "pre-index escreve o endereço final de volta em Rn");
    }

    @Test
    void ldrPostIndexUsesOldAddressThenWritesBack() {
        Aarch64Core core = newCore(64);
        core.setX(1, 8);
        core.memory().write64(8, 0x5678L);
        putWord(core, 0, 0xf8408420); // ldr x0, [x1], #8

        new Ir64BlockExecutor().step(core);

        assertEquals(0x5678L, core.x(0), "post-index transfere no endereço ANTIGO de Rn");
        assertEquals(16L, core.x(1), "post-index escreve Rn+imm depois da transferência");
    }

    @Test
    void ldrRegisterOffsetPlainNoExtend() {
        Aarch64Core core = newCore(64);
        core.setX(1, 8);
        core.setX(2, 16);
        core.memory().write64(24, 0x9999L);
        putWord(core, 0, 0xf8626820); // ldr x0, [x1, x2]

        new Ir64BlockExecutor().step(core);

        assertEquals(0x9999L, core.x(0));
    }

    @Test
    void ldrRegisterOffsetLsl3Scale() {
        Aarch64Core core = newCore(64);
        core.setX(1, 0);
        core.setX(2, 2); // 2 << 3 = 16
        core.memory().write64(16, 0x7777L);
        putWord(core, 0, 0xf8627820); // ldr x0, [x1, x2, lsl #3]

        new Ir64BlockExecutor().step(core);

        assertEquals(0x7777L, core.x(0));
    }

    @Test
    void ldrRegisterOffsetSxtwSignExtendsNegative32BitIndex() {
        Aarch64Core core = newCore(64);
        core.setX(1, 32);
        core.setX(2, 0xFFFF_FFFF_FFFF_FFFFL); // W2 = -1 (0xFFFFFFFF), sign-extended = -1 em X
        core.memory().write64(24, 0x4242L); // 32 + (-1*8) = 24
        putWord(core, 0, 0xf862d820); // ldr x0, [x1, w2, sxtw #3]

        new Ir64BlockExecutor().step(core);

        assertEquals(0x4242L, core.x(0));
    }

    @Test
    void ldrRegisterOffsetUxtwZeroExtendsIndex() {
        Aarch64Core core = newCore(64);
        core.setX(1, 0);
        core.setX(2, 0xFFFF_FFFF_0000_0002L); // W2 = 2 (altos ignorados por UXTW)
        core.memory().write32(8, 0x99887766); // 0 + (2<<2) = 8 (tamanho W, shift=2)
        putWord(core, 0, 0xb8625820); // ldr w0, [x1, w2, uxtw #2]

        new Ir64BlockExecutor().step(core);

        assertEquals(0x99887766L, core.x(0));
    }

    @Test
    void stpPreIndexPrologueIdiom() {
        Aarch64Core core = newCore(64);
        core.setSp(32);
        core.setX(29, 0x1111L);
        core.setX(30, 0x2222L);
        putWord(core, 0, 0xa9bf7bfd); // stp x29, x30, [sp, #-16]!

        new Ir64BlockExecutor().step(core);

        assertEquals(16L, core.sp(), "pre-index escreve sp-16 de volta em SP");
        assertEquals(0x1111L, core.memory().read64(16));
        assertEquals(0x2222L, core.memory().read64(24));
    }

    @Test
    void ldpPostIndexEpilogueIdiom() {
        Aarch64Core core = newCore(64);
        core.setSp(16);
        core.memory().write64(16, 0x1111L);
        core.memory().write64(24, 0x2222L);
        putWord(core, 0, 0xa8c17bfd); // ldp x29, x30, [sp], #16

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1111L, core.x(29));
        assertEquals(0x2222L, core.x(30));
        assertEquals(32L, core.sp(), "post-index escreve sp+16 de volta em SP");
    }

    @Test
    void stpOffsetNoWriteback() {
        Aarch64Core core = newCore(64);
        core.setX(0, 100);
        core.setX(1, 200);
        core.setSp(0);
        putWord(core, 0, 0xa90107e0); // stp x0, x1, [sp, #16]

        new Ir64BlockExecutor().step(core);

        assertEquals(100L, core.memory().read64(16));
        assertEquals(200L, core.memory().read64(24));
        assertEquals(0L, core.sp(), "endereçamento OFFSET nunca escreve de volta em Rn");
    }

    @Test
    void ldpWordPairRoundTrip() {
        Aarch64Core core = newCore(64);
        core.setSp(0);
        core.memory().write32(8, 0x1234_5678);
        core.memory().write32(12, 0x9ABC_DEF0);
        putWord(core, 0, 0x294107e0); // ldp w0, w1, [sp, #8]

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1234_5678L, core.x(0));
        assertEquals(0x9ABC_DEF0L, core.x(1));
    }

    @Test
    void ldrLiteralReadsAbsoluteAddress() {
        Aarch64Core core = newCore(0x200);
        core.memory().write64(0x114, 0x1234_5678_9ABC_DEF0L);
        putWord(core, 0x110, 0x58000027); // ldr x7, litlabel (litlabel = 0x114)
        core.setProgramCounter(0x110);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1234_5678_9ABC_DEF0L, core.x(7));
    }

    @Test
    void ldrswLiteralSignExtendsWordToX() {
        Aarch64Core core = newCore(0x40);
        core.memory().write32(0x10, 0xFFFF_FFFF); // -1 como word
        // ldrsw literal: opc=10, imm19 aponta para 0x10 a partir de address=0x00 (offset 4 words)
        int imm19 = (0x10 - 0x00) / 4;
        int word = (0b10 << 30) | (0b011 << 27) | (imm19 << 5) | 5; // Rt=5
        putWord(core, 0, word);

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(5));
    }

    @Test
    void ldrXzrDestinationDiscardsLoadedValue() {
        Aarch64Core core = newCore(32);
        core.setX(1, 0);
        core.memory().write64(0, 0xDEAD_BEEFL);
        putWord(core, 0, 0xf940003f); // ldr xzr, [x1]

        new Ir64BlockExecutor().step(core);

        assertEquals(0L, core.x(31));
    }
}
