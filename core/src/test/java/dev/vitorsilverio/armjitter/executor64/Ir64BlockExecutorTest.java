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

    // ── B6.3.1: logical (immediate) ─────────────────────────────────────────────────────────

    @Test
    void andsImmediateNeverSetsCarryOrOverflow() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xf27ff8e6); // ands x6, x7, #0xfffffffffffffffe (do corpus real)
        core.setX(7, 0xFFFF_FFFF_FFFF_FFFFL); // qualquer operando, C/V nunca vêm de aritmética aqui

        new Ir64BlockExecutor().step(core);

        assertEquals(0xFFFF_FFFF_FFFF_FFFEL, core.x(6));
        assertFalse(core.pstate().carry(), "AND/ORR/EOR (imediato) nunca setam C");
        assertFalse(core.pstate().overflow(), "AND/ORR/EOR (imediato) nunca setam V");
    }

    @Test
    void andImmediateNeverTouchesStackPointer() {
        Aarch64Core core = newCore(16);
        core.setSp(0x7FFF_0000L);
        putWord(core, 0, 0x92400020); // and x0, x1, #0x1
        core.setX(1, 0x3);

        new Ir64BlockExecutor().step(core);

        assertEquals(1L, core.x(0));
        assertEquals(0x7FFF_0000L, core.sp(), "AND (imediato) nunca toca SP");
    }

    // ── B6.3.1: ALU shifted register ────────────────────────────────────────────────────────

    @Test
    void addShiftedRegisterLslNonZeroShiftsSecondOperand() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8b021020); // add x0, x1, x2, lsl #4 (do corpus real)
        core.setX(1, 1);
        core.setX(2, 1);

        new Ir64BlockExecutor().step(core);

        assertEquals(1L + (1L << 4), core.x(0));
    }

    @Test
    void subShiftedRegisterLsr() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xcb451883); // sub x3, x4, x5, lsr #6 (do corpus real)
        core.setX(4, 1000);
        core.setX(5, 640);

        new Ir64BlockExecutor().step(core);

        assertEquals(1000L - (640L >>> 6), core.x(3));
    }

    @Test
    void subsShiftedRegisterAsrSetsFlags() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xeb8820e6); // subs x6, x7, x8, asr #8 (do corpus real)
        core.setX(7, 0);
        core.setX(8, 0x100);

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(6));
        assertTrue(core.pstate().negative());
        assertFalse(core.pstate().zero());
    }

    @Test
    void addsShiftedRegister32BitZeroFlag() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x2b0b0149); // adds w9, w10, w11 (do corpus real)
        core.setX(10, 5);
        core.setX(11, 0xFFFF_FFFBL); // -5 como W

        new Ir64BlockExecutor().step(core);

        assertEquals(0L, core.x(9));
        assertTrue(core.pstate().zero());
    }

    @Test
    void subShiftedRegister32BitAsrIsArithmeticNotLogical() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x4b911e0f); // sub w15, w16, w17, asr #7 (do corpus real)
        core.setX(16, 0);
        core.setX(17, 0x8000_0000L); // INT_MIN como W: ASR deve preservar o sinal

        new Ir64BlockExecutor().step(core);

        int expected = 0 - (0x8000_0000 >> 7); // >> em int já é aritmético em Java
        assertEquals(expected & 0xFFFF_FFFFL, core.x(15));
    }

    // ── B6.3.1: ALU extended register ──────────────────────────────────────────────────────

    @Test
    void addExtendedRegisterUxtbReadsOnlyLowByteOfRm() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8b220020); // add x0, x1, w2, uxtb (do corpus real)
        core.setX(1, 0x100);
        core.setX(2, 0x1234_5678L); // só o byte baixo (0x78) deve contar

        new Ir64BlockExecutor().step(core);

        assertEquals(0x100L + 0x78L, core.x(0));
    }

    @Test
    void addExtendedRegisterSpAsBothRnAndRd() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8b2163ff); // add sp, sp, x1 (do corpus real)
        core.setSp(0x1000);
        core.setX(1, 0x10);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1010L, core.sp());
    }

    @Test
    void addExtendedRegisterSpAsDstOnlyReadsNormalRn() {
        // add sp, x4, x5: Rn=x4 é um registrador NORMAL (não SP) — prova que dstIsStackPointer
        // não implica src1 também ser SP (são resolvidos de forma independente pelo executor,
        // cada um checando o próprio índice contra 31).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8b25609f); // add sp, x4, x5 (do corpus real)
        core.setSp(0xDEAD);
        core.setX(4, 0x2000);
        core.setX(5, 0x30);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x2030L, core.sp());
        assertEquals(0x2000L, core.x(4), "Rn (x4) não deveria ser afetado");
    }

    @Test
    void addExtendedRegisterNonSpDestinationNeverWritesStackPointer() {
        // add x0, x1, x2, uxtx: dstIsStackPointer é true (ADD sem S), mas dst=0 (não 31) —
        // deve escrever em X0, nunca em SP (a checagem correta é por ÍNDICE, não só pela flag;
        // ver a diferença deliberada com o executor de B6.1/Alu64 documentada no código).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x8b226020); // add x0, x1, x2, uxtx (do corpus real)
        core.setSp(0x9999);
        core.setX(1, 100);
        core.setX(2, 23);

        new Ir64BlockExecutor().step(core);

        assertEquals(123L, core.x(0));
        assertEquals(0x9999L, core.sp(), "SP não deveria ser tocado quando dst != 31");
    }

    @Test
    void addsExtendedRegisterSpAsRnReadsStackPointerCorrectly() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xab2363e2); // adds x2, sp, x3 (do corpus real)
        core.setSp(100);
        core.setX(3, 23);

        new Ir64BlockExecutor().step(core);

        assertEquals(123L, core.x(2));
        assertFalse(core.pstate().zero());
    }

    @Test
    void addsExtendedRegisterXzrDestinationDiscardsResultButKeepsFlags() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xab22603f); // adds xzr, x1, x2, uxtx ("cmn", do corpus real)
        core.setX(1, 5);
        core.setX(2, -5L);

        new Ir64BlockExecutor().step(core);

        assertEquals(0L, core.x(31));
        assertTrue(core.pstate().zero(), "flags devem refletir 5 + (-5) mesmo com resultado descartado");
    }

    @Test
    void subsExtendedRegisterXzrDestinationComparesWithoutWriting() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xeb22603f); // subs xzr, x1, x2, uxtx ("cmp", do corpus real)
        core.setX(1, 5);
        core.setX(2, 5);

        new Ir64BlockExecutor().step(core);

        assertEquals(0L, core.x(31));
        assertTrue(core.pstate().zero());
    }

    @Test
    void addExtendedRegister32BitWithShiftZeroesUpperBits() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x0b220820); // add w0, w1, w2, uxtb #2 (do corpus real)
        core.setX(0, 0xFFFF_FFFF_0000_0000L);
        core.setX(1, 0x10);
        core.setX(2, 0xAABB_CCFFL); // só o byte baixo (0xFF) deve contar, deslocado 2

        new Ir64BlockExecutor().step(core);

        assertEquals(0x10L + (0xFFL << 2), core.x(0));
    }

    // ── B6.3.2: CSEL/CSINC/CSINV/CSNEG (+ aliases) ─────────────────────────────────────────────

    @Test
    void cselTakesSrc1WhenConditionTrue() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9a820020); // csel x0, x1, x2, eq (do corpus real)
        core.setX(1, 0x1111L);
        core.setX(2, 0x2222L);
        core.pstate().setNzcv(false, true, false, false); // Z=1 -> eq verdadeira

        new Ir64BlockExecutor().step(core);

        assertEquals(0x1111L, core.x(0), "condição verdadeira: dst = src1");
    }

    @Test
    void cselTakesSrc2WhenConditionFalse() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9a820020); // csel x0, x1, x2, eq
        core.setX(1, 0x1111L);
        core.setX(2, 0x2222L);
        core.pstate().setNzcv(false, false, false, false); // Z=0 -> eq falsa

        new Ir64BlockExecutor().step(core);

        assertEquals(0x2222L, core.x(0), "condição falsa (CSEL): dst = f(src2) = src2 (identidade)");
    }

    @Test
    void csincAddsOneToSrc2WhenConditionFalse() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9a851483); // csinc x3, x4, x5, ne (do corpus real)
        core.setX(4, 100);
        core.setX(5, 200);
        core.pstate().setNzcv(false, true, false, false); // Z=1 -> ne falsa

        new Ir64BlockExecutor().step(core);

        assertEquals(201L, core.x(3), "CSINC: condição falsa -> dst = src2 + 1");
    }

    @Test
    void csinvInvertsSrc2WhenConditionFalse() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xda8820e6); // csinv x6, x7, x8, cs (do corpus real)
        core.setX(7, 100);
        core.setX(8, 0L);
        core.pstate().setNzcv(false, false, false, false); // C=0 -> cs falsa

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(6), "CSINV: condição falsa -> dst = ~src2, ~0 = -1");
    }

    @Test
    void csnegNegatesSrc2WhenConditionFalse() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xda8b3549); // csneg x9, x10, x11, cc (do corpus real)
        core.setX(10, 100);
        core.setX(11, 5);
        core.pstate().setNzcv(false, false, true, false); // C=1 -> cc falsa

        new Ir64BlockExecutor().step(core);

        assertEquals(-5L, core.x(9), "CSNEG: condição falsa -> dst = -src2");
    }

    @Test
    void csincWithXzrSrc2GivesOne() {
        // cset x12, eq (alias, CSINC dst=12,src1=31,src2=31,cond=ne): condição falsa (Z=1,
        // ne falsa) -> dst = XZR + 1 = 1 — caso especial citado nos Testes mínimos da task.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9a9f17ec);
        core.pstate().setNzcv(false, true, false, false);

        new Ir64BlockExecutor().step(core);

        assertEquals(1L, core.x(12));
    }

    @Test
    void csinvWithXzrSrc2GivesAllBitsSet() {
        // csetm x13, ne (alias, CSINV dst=13,src1=31,src2=31,cond=eq): condição falsa (Z=0,
        // eq falsa) -> dst = ~XZR = todos os bits setados.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xda9f03ed);
        core.pstate().setNzcv(false, false, false, false);

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(13));
    }

    @Test
    void csnegWithXzrSrc2GivesZero() {
        // csneg x25, x26, xzr, eq (vetor real dedicado, ver Aarch64DecoderCorpusTest): condição
        // falsa (Z=0, eq falsa) -> dst = -XZR = 0 (negação de zero) — caso especial dos Testes
        // mínimos da task.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xda9f0759);
        core.pstate().setNzcv(false, false, false, false);

        new Ir64BlockExecutor().step(core);

        assertEquals(0L, core.x(25));
    }

    @Test
    void cselNarrowZeroesUpperBitsOfDestination() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x1a96c2b4); // csel w20, w21, w22, gt (do corpus real)
        core.setX(20, 0xFFFF_FFFF_0000_0000L); // sentinela nos 32 bits altos
        core.setX(21, 0x1111_1111L);
        core.setX(22, 0xFFFF_FFFF_2222_2222L); // só os 32 bits baixos (0x22222222) devem contar
        core.pstate().setNzcv(false, true, false, false); // GT (!Z && N==V) falsa: Z=1

        new Ir64BlockExecutor().step(core);

        assertEquals(0x2222_2222L, core.x(20), "W: resultado zero-estendido para os 64 bits");
    }

    @Test
    void conditionalSelectNeverModifiesNzcv() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9a820020); // csel x0, x1, x2, eq
        core.setX(1, 1);
        core.setX(2, 2);
        core.pstate().setNzcv(true, true, true, true); // NZCV = 1111 antes

        new Ir64BlockExecutor().step(core);

        assertEquals(0xF, core.pstate().nzcv(), "CSEL só LÊ os flags, nunca escreve");
    }

    // ── B6.3.2: SBFM/BFM/UBFM (+ aliases) ──────────────────────────────────────────────────────

    @Test
    void sbfxSignExtendsFieldWhenSignBitSet() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x93442820); // sbfx x0, x1, #4, #7 (immr=4, imms=10, si >= ri)
        core.setX(1, 0xFFFF_FFFF_FFFF_FFF0L); // bits[4:10] todos 1 -> sinal setado

        new Ir64BlockExecutor().step(core);

        assertEquals(-1L, core.x(0), "campo todo-1 com sinal setado estende para todos os 64 bits");
    }

    @Test
    void sbfxDoesNotSignExtendWhenSignBitClear() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x93442820); // sbfx x0, x1, #4, #7
        core.setX(1, 0x10L); // só bit4 setado -> campo extraído = 0b0000001, sinal limpo

        new Ir64BlockExecutor().step(core);

        assertEquals(1L, core.x(0));
    }

    @Test
    void ubfxNeverSignExtendsUnlikeSbfx() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xd34428a4); // ubfx x4, x5, #4, #7 (mesmos immr/imms de sbfx acima)
        core.setX(5, 0xFFFF_FFFF_FFFF_FFF0L); // mesmo operando do teste sbfx com sinal setado

        new Ir64BlockExecutor().step(core);

        assertEquals(0x7FL, core.x(4), "UBFM nunca estende sinal, ao contrário de SBFM (mesmo campo)");
    }

    @Test
    void bfxilPreservesDestinationBitsOutsideField() {
        // bfxil x8, x9, #4, #7 (immr=4, imms=10, si >= ri -> pos=0, len=7): prova que BFM
        // PRESERVA os bits fora do campo copiado (diferente de SBFM/UBFM, que zeram/estendem) —
        // teste dedicado pedido nos Testes mínimos da task, Rd pré-populado com valor não-trivial.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xb3442928);
        core.setX(8, 0xFFFF_FFFF_FFFF_FFFFL); // Rd pré-existente: todos os bits setados
        core.setX(9, 0x10L); // campo extraído (bits[4:10]) = 0b0000001 = 1

        new Ir64BlockExecutor().step(core);

        assertEquals(0xFFFF_FFFF_FFFF_FF81L, core.x(8),
                "bits[0:6] = campo (0x01), bits[7:63] preservados de Rd (todos 1)");
    }

    @Test
    void lslAliasShiftsLeftDiscardingHighBits() {
        // lsl x12, x13, #5 (UBFM immr=59, imms=58, si < ri -> pos=5, len=59): equivalente a um
        // shift lógico à esquerda real, incluindo o descarte dos bits altos.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xd37be9ac);
        core.setX(13, 0xFFFF_FFFF_FFFF_FFFFL);

        new Ir64BlockExecutor().step(core);

        assertEquals(0xFFFF_FFFF_FFFF_FFE0L, core.x(12), "LSL x13,#5 com x13 todo 1: só os 5 bits baixos zeram");
    }

    @Test
    void lslAliasSimpleShiftMatchesPlainLeftShift() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xd37be9ac); // lsl x12, x13, #5
        core.setX(13, 1L);

        new Ir64BlockExecutor().step(core);

        assertEquals(1L << 5, core.x(12));
    }

    @Test
    void sbfizAliasSignExtendsAboveInsertedFieldAndZeroesBelow() {
        // sbfm x2, x3, #10, #4 (disassembla "sbfiz x2, x3, #54, #5"): si < ri -> pos=54, len=5.
        // Campo negativo (bit4 do campo de 5 bits setado) deve estender o sinal ACIMA do campo
        // inserido; os bits ABAIXO da posição de inserção devem ser zero (diferente de LSL/LSR,
        // que não têm sign-fill).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x934a1062);
        core.setX(3, 0b10000L); // 5 bits baixos = 10000 (bit4 setado -> negativo)

        new Ir64BlockExecutor().step(core);

        long expected = (-16L) << 54;
        assertEquals(expected, core.x(2));
    }

    @Test
    void sbfizAliasPositiveFieldNoSignFillBelowPosition() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x934a1062); // sbfiz x2, x3, #54, #5
        core.setX(3, 1L); // 5 bits baixos = 00001 (positivo)

        new Ir64BlockExecutor().step(core);

        assertEquals(1L << 54, core.x(2));
    }

    @Test
    void uxtbAliasZeroExtendsLowByteAndZeroesUpperBitsOfDestination() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x53001f7a); // uxtb w26, w27
        core.setX(26, 0xFFFF_FFFF_FFFF_FFFFL); // sentinela: deve ser totalmente sobrescrito
        core.setX(27, 0x1234_5678L);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x78L, core.x(26));
    }

    @Test
    void sxtbAliasSignExtendsNegativeByteToFull64Bits() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x93401c1e); // sxtb x30, w0
        core.setX(0, 0xABL); // byte baixo = 0xAB (bit7 setado -> negativo como i8)

        new Ir64BlockExecutor().step(core);

        assertEquals(-85L, core.x(30), "0xAB como i8 é -85, estendido para os 64 bits completos");
    }

    @Test
    void bitfieldNeverModifiesNzcv() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x93442820); // sbfx x0, x1, #4, #7
        core.setX(1, 0xFF);
        core.pstate().setNzcv(true, true, true, true);

        new Ir64BlockExecutor().step(core);

        assertEquals(0xF, core.pstate().nzcv(), "bitfield nunca toca NZCV");
    }

    // ── B6.3.3: MADD/MSUB (+ MUL/MNEG aliases), SDIV/UDIV ──────────────────────────────────────

    @Test
    void maddAddsProductToAccumulator() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9b020c20); // madd x0, x1, x2, x3 (do corpus real)
        core.setX(1, 6);
        core.setX(2, 7);
        core.setX(3, 100);

        new Ir64BlockExecutor().step(core);

        assertEquals(142L, core.x(0), "MADD: dst = Ra + Rn*Rm = 100 + 6*7");
    }

    @Test
    void msubSubtractsProductFromAccumulator() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9b069ca4); // msub x4, x5, x6, x7 (do corpus real)
        core.setX(5, 6);
        core.setX(6, 7);
        core.setX(7, 100);

        new Ir64BlockExecutor().step(core);

        assertEquals(58L, core.x(4), "MSUB: dst = Ra - Rn*Rm = 100 - 6*7");
    }

    @Test
    void mulAliasIsMaddWithXzrAccumulator() {
        // mul x8, x9, x10 (do corpus real): mesmo encoding de MADD com Ra=31 (XZR) — sem case de
        // decode dedicado (D2 da task); o caminho geral já produz o resultado certo porque XZR lê
        // 0.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9b0a7d28);
        core.setX(9, 6);
        core.setX(10, 7);

        new Ir64BlockExecutor().step(core);

        assertEquals(42L, core.x(8), "MUL: dst = 0 + Rn*Rm (acumulador XZR)");
    }

    @Test
    void mnegAliasIsMsubWithXzrAccumulator() {
        // mneg x11, x12, x13 (do corpus real): mesmo encoding de MSUB com Ra=31 (XZR).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9b0dfd8b);
        core.setX(12, 6);
        core.setX(13, 7);

        new Ir64BlockExecutor().step(core);

        assertEquals(-42L, core.x(11), "MNEG: dst = 0 - Rn*Rm (acumulador XZR)");
    }

    @Test
    void maddNarrowZeroExtendsResultToUpperBitsOfDestination() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x1b1045ee); // madd w14, w15, w16, w17 (do corpus real)
        core.setX(14, 0xFFFF_FFFF_0000_0000L); // sentinela nos 32 bits altos do destino
        core.setX(15, 5);
        core.setX(16, 6);
        core.setX(17, 1000);

        new Ir64BlockExecutor().step(core);

        assertEquals(1030L, core.x(14), "W: resultado (5*6+1000) zero-estendido para os 64 bits");
    }

    @Test
    void sdivDivisorZeroGivesZeroWithoutThrowing() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9ade0fbc); // sdiv x28, x29, x30 (do corpus real)
        core.setX(29, 100);
        core.setX(30, 0);

        assertDoesNotThrow(() -> new Ir64BlockExecutor().step(core),
                "A64 NUNCA lança exceção arquitetural para divisão por zero (ver Armadilhas da task)");
        assertEquals(0L, core.x(28), "SDIV: divisor 0 -> resultado 0");
    }

    @Test
    void udivDivisorZeroGivesZeroWithoutThrowing() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9ac20820); // udiv x0, x1, x2 (do corpus real)
        core.setX(1, 100);
        core.setX(2, 0);

        assertDoesNotThrow(() -> new Ir64BlockExecutor().step(core));
        assertEquals(0L, core.x(0), "UDIV: divisor 0 -> resultado 0");
    }

    @Test
    void sdivWideMinValueDividedByNegativeOneTruncatesWithoutThrowing() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9ade0fbc); // sdiv x28, x29, x30
        core.setX(29, Long.MIN_VALUE);
        core.setX(30, -1);

        assertDoesNotThrow(() -> new Ir64BlockExecutor().step(core),
                "overflow de divisão trunca (mesma convenção de complemento-de-dois de Java), nunca lança");
        assertEquals(Long.MIN_VALUE, core.x(28));
    }

    @Test
    void sdivNarrowMinValueDividedByNegativeOneTruncatesWithoutThrowing() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x1ac50c83); // sdiv w3, w4, w5 (do corpus real)
        core.setX(4, 0x8000_0000L); // Integer.MIN_VALUE como padrão de bits de 32 bits
        core.setX(5, 0xFFFF_FFFFL); // -1 como padrão de bits de 32 bits

        assertDoesNotThrow(() -> new Ir64BlockExecutor().step(core));
        assertEquals(0x8000_0000L, core.x(3),
                "W: Integer.MIN_VALUE truncado, zero-estendido para os 64 bits do destino");
    }

    @Test
    void udivInterpretsOperandsAsUnsigned() {
        // udiv w6, w7, w8 (do corpus real): w7 tem o bit mais alto setado — como valor COM sinal
        // seria negativo, mas UDIV deve interpretar sem sinal (Long.divideUnsigned/
        // Integer.divideUnsigned, nunca `/` comum — Armadilhas da task).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x1ac808e6);
        core.setX(7, 0x8000_0000L);
        core.setX(8, 2);

        new Ir64BlockExecutor().step(core);

        assertEquals(0x4000_0000L, core.x(6), "UDIV: 0x80000000 dividido sem sinal por 2");
    }

    @Test
    void multiplyAccumulateAndDivideNeverModifyNzcv() {
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x9b020c20); // madd x0, x1, x2, x3
        core.setX(1, 2);
        core.setX(2, 3);
        core.setX(3, 4);
        core.pstate().setNzcv(true, true, true, true);

        new Ir64BlockExecutor().step(core);

        assertEquals(0xF, core.pstate().nzcv(), "MADD/MSUB/SDIV/UDIV nunca tocam NZCV");
    }

    @Test
    void fmovFaddFcmpBCondMinimalFloatBlock() {
        // "hello float" mínimo de A64 (B6.5.3, mesmo espírito do teste 3 de
        // b3.5-vfp-decoder.md, mas sem o passo VMRS intermediário: FCMP já escreve PSTATE.NZCV
        // diretamente, ver Ir64Op.Fp64Compare javadoc): FMOV s0,#1.0; FADD s0,s0,s0 (-> 2.0);
        // FCMP s0,#0.0 (2.0 > 0.0); B.gt salta por cima do FMOV s1,#2.0. Palavras reais
        // assembladas via aarch64-none-elf-as/objdump (devkitA64), não inventadas à mão.
        Aarch64Core core = newCore(32);
        putWord(core, 0x00, 0x1e2e1000); // fmov s0, #1.0
        putWord(core, 0x04, 0x1e202800); // fadd s0, s0, s0
        putWord(core, 0x08, 0x1e202008); // fcmp s0, #0.0
        putWord(core, 0x0c, 0x5400004c); // b.gt target (target = 0x14)
        putWord(core, 0x10, 0x1e201001); // fmov s1, #2.0 (NÃO deveria executar, pulado pelo B.gt)
        putWord(core, 0x14, 0x1e221002); // fmov s2, #4.0 (target)

        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.step(core); // fmov s0, #1.0
        assertEquals(1.0f, core.fp().sFloat(0));

        executor.step(core); // fadd s0, s0, s0
        assertEquals(2.0f, core.fp().sFloat(0));

        executor.step(core); // fcmp s0, #0.0
        assertFalse(core.pstate().negative(), "2.0 > 0.0: N=0");
        assertFalse(core.pstate().zero(), "2.0 != 0.0: Z=0");
        assertTrue(core.pstate().carry(), "sem underflow/NaN: C=1 (mesma tabela de FCMP maior-que)");
        assertFalse(core.pstate().overflow());

        executor.step(core); // b.gt target
        assertEquals(0x14L, core.pc(), "B.gt deveria ser tomado (2.0 > 0.0)");

        executor.step(core); // fmov s2, #4.0 (no target, s1 nunca é tocado)
        assertEquals(4.0f, core.fp().sFloat(2));
        assertEquals(0.0f, core.fp().sFloat(1), "fmov s1,#2.0 foi pulado pelo B.gt");
    }

    // ── B6.8: CCMP/CCMN (registrador e imediato) — palavras do mesmo corpus real de
    // ── Aarch64DecoderCorpusTest (aarch64-none-elf-as/objdump, devkitA64), mesmos offsets. ─────

    @Test
    void ccmpRegisterConditionTrueMatchesSubs() {
        // ccmp x1, x2, #3, eq: condição verdadeira -> NZCV = flags(x1 - x2), mesmo cálculo de
        // SUBS (Testes mínimos #1 da task).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xfa420023);
        core.setX(1, 10);
        core.setX(2, 3);
        core.pstate().setNzcv(false, true, false, false); // Z=1 -> eq verdadeira

        new Ir64BlockExecutor().step(core);

        assertFalse(core.pstate().negative());
        assertFalse(core.pstate().zero(), "10-3=7 != 0");
        assertTrue(core.pstate().carry(), "10 >= 3: sem borrow");
        assertFalse(core.pstate().overflow());
    }

    @Test
    void ccmpConditionFalseUsesRawNzcvWithoutReadingOperands() {
        // ccmp x1, x2, #3, eq (mesma palavra): condição falsa -> NZCV = os 4 bits crus do
        // imediato (0b0011), NUNCA o cálculo de x1-x2 — x1/x2 são escolhidos IGUAIS de propósito
        // (5-5=0 daria N=0,Z=1,C=1,V=0, DIFERENTE do raw 0b0011=N=0,Z=0,C=1,V=1), prova de que a
        // implementação realmente ramifica em vez de sempre calcular e descartar (Armadilhas da
        // task, Testes mínimos #2).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xfa420023);
        core.setX(1, 5);
        core.setX(2, 5);
        core.pstate().setNzcv(false, false, false, false); // Z=0 -> eq falsa

        new Ir64BlockExecutor().step(core);

        assertEquals(0x3, core.pstate().nzcv(), "NZCV = imediato cru do encoding, não o cálculo");
    }

    @Test
    void ccmpImmediateFormConditionTrueMatchesSubs() {
        // ccmp x5, #10, #7, cs: forma imediato, condição verdadeira -> NZCV = flags(x5 - #10)
        // (Testes mínimos #3).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xfa4a28a7);
        core.setX(5, 15);
        core.pstate().setNzcv(false, false, true, false); // C=1 -> cs verdadeira

        new Ir64BlockExecutor().step(core);

        assertFalse(core.pstate().negative());
        assertFalse(core.pstate().zero(), "15-10=5 != 0");
        assertTrue(core.pstate().carry());
        assertFalse(core.pstate().overflow());
    }

    @Test
    void ccmpRegisterNarrowConditionTrueMatchesSubs32Bit() {
        // ccmp w3, w4, #5, ne: forma W -> cálculo de flags em 32 bits (Testes mínimos #4).
        // w3=3, w4=5 -> 3-5=-2 em complemento-de-dois de 32 bits (0xFFFFFFFE): N=1, sem overflow,
        // com borrow (carry=false, convenção ARM: C=0 quando HÁ borrow).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0x7a441065);
        core.setX(3, 3);
        core.setX(4, 5);
        core.pstate().setNzcv(false, false, false, false); // Z=0 -> ne verdadeira

        new Ir64BlockExecutor().step(core);

        assertTrue(core.pstate().negative(), "3-5=-2: bit alto do resultado de 32 bits setado");
        assertFalse(core.pstate().zero());
        assertFalse(core.pstate().carry(), "3 < 5: houve borrow");
        assertFalse(core.pstate().overflow());
    }

    @Test
    void ccmnRegisterConditionTrueMatchesAdds() {
        // ccmn x7, x8, #1, mi: CCMN -> NZCV = flags(x7 + x8), mesmo cálculo de ADDS.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xba4840e1);
        core.setX(7, 1);
        core.setX(8, 2);
        core.pstate().setNzcv(true, false, false, false); // N=1 -> mi verdadeira

        new Ir64BlockExecutor().step(core);

        assertFalse(core.pstate().negative(), "1+2=3: bit de sinal não setado");
        assertFalse(core.pstate().zero());
        assertFalse(core.pstate().carry());
        assertFalse(core.pstate().overflow());
    }

    @Test
    void ccmpDoesNotWriteAnyRegister() {
        // ccmp x1, x2, #3, eq: CCMP/CCMN nunca escrevem Rd (só flags) — Armadilhas da task.
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xfa420023);
        core.setX(1, 10);
        core.setX(2, 3);
        core.pstate().setNzcv(false, true, false, false);

        new Ir64BlockExecutor().step(core);

        assertEquals(10L, core.x(1), "CCMP não escreve Rn");
        assertEquals(3L, core.x(2), "CCMP não escreve Rm");
    }

    @Test
    void ccmpLiteralVectorFromF11PolyglotKernelHeader() {
        // ccmp x18, #0, #0xd, pl: a PRIMEIRA instrução real de praticamente todo `kernel8.img`
        // distribuído (truque polyglot EFI "MZ"), o achado que abriu a task B6.8 (F11,
        // virtual-arm-box) — prova ponta-a-ponta de que o fix resolve o caso real, não só um caso
        // sintético (Testes mínimos #5).
        Aarch64Core core = newCore(16);
        putWord(core, 0, 0xfa405a4d);
        core.setX(18, 5);
        core.pstate().setNzcv(false, false, false, false); // N=0 -> pl verdadeira

        new Ir64BlockExecutor().step(core);

        assertFalse(core.pstate().negative(), "5-0=5: bit de sinal não setado");
        assertFalse(core.pstate().zero());
        assertTrue(core.pstate().carry(), "5 >= 0: sem borrow");
        assertFalse(core.pstate().overflow());
        assertEquals(4L, core.pc(), "CCMP avança o PC normalmente (não é branch)");
    }
}
