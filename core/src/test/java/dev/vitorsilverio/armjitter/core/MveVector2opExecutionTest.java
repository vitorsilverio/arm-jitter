package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.6 — vector 2-op inteiro, fim-a-fim (decode + lift + executor interpretado) sobre o preset
/// real `ARMV8_1M_MVE`. Cobre os itens do Aceite da task que exigem execução (não só decode):
/// `@2op_rev` com papéis VALOR/CONTAGEM corretos, `FPSCR.QC` só em lane ATIVA, predicação nas 3
/// larguras, indexação intercalada de `VMULL_B*`/`VMULL_T*` (achado que corrige a suposição
/// inicial), encadeamento de carry de `VADC` e o padrão par/ímpar de `VCADD90`. Mesmo padrão de
/// {@code MveLoadStoreTest}.
class MveVector2opExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    /// MESMO layout do Javadoc de `Thumb2MveVector2opDecoder`/`Thumb2MveVector2opDecoderTest`.
    private static int raw(int u, int top24, int qd, int field2120, int qn, int bit16, int bit12, int nibble,
            int bit6, int qm, int bit4) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (u << 28) | (top24 << 24) | (qdHigh << 22) | (field2120 << 20) | (qnLow << 17)
                | (bit16 << 16) | (qdLow << 13) | (bit12 << 12) | (nibble << 8) | (qnHigh << 7) | (bit6 << 6)
                | (qmHigh << 5) | (bit4 << 4) | (qmLow << 1);
    }

    // ── @2op_rev: papéis VALOR/CONTAGEM (Aceite bullet 2) ───────────────────────────────────────

    @Test
    void vshlShiftsTheValueRegisterByTheCountRegisterNotTheOther() {
        ArmCore core = newCore();
        // Posição de bits "%qn" (5) recebe a CONTAGEM (2); posição "%qm" (6) recebe o VALOR (0x11).
        core.vfp().setQ(6, 0x11L, 0L); // registrador na posição "%qm": valor a deslocar.
        core.vfp().setQ(5, 2L, 0L); // registrador na posição "%qn": contagem do deslocamento.
        // VSHL_S, esz=0 (byte), Qd=1.
        int r = raw(0, 0b1111, 1, 0b00, 5, 0, 0, 0b0100, 1, 6, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x11 << 2, core.vfp().element(1, 0, 0), "0x11 deslocado por 2, não o contrário");
    }

    // ── FPSCR.QC só em lane ATIVA (Aceite bullet 3) ─────────────────────────────────────────────

    @Test
    void saturatingAddSetsQcOnlyWhenTheSaturatingLaneIsActive() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x7FFF_FFFFL | (0x7FFF_FFFFL << 32), 0L); // Qn: word0/word1 = INT_MAX.
        core.vfp().setQ(3, 1L | (1L << 32), 0L); // Qm: word0/word1 = 1 (ambos saturam).
        core.vfp().setQ(1, 0L, 0L); // Qd inicial: zero em toda parte (para confirmar preservação).
        // VPT ativo, P0 desliga a lane 0 (bytes 0-3) e liga a lane 1 (bytes 4-7).
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00F0); // bytes 4-7 (lane 1, word) ativos; bytes 0-3 (lane 0) desligados.
        // VQADD_S, esz=2 (word), Qd=1, Qn=2, Qm=3.
        int r = raw(0, 0b1111, 1, 0b10, 2, 0, 0, 0b0000, 1, 3, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.vfp().element(1, 0, 2), "lane 0 mascarada preserva o valor atual (0)");
        assertEquals(0x7FFF_FFFFL, core.vfp().element(1, 1, 2), "lane 1 ativa satura em INT_MAX");
        assertTrue(core.fpscr().qc(), "QC setado — a lane ATIVA saturou");
    }

    @Test
    void saturatingAddNeverSetsQcWhenNoActiveLaneSaturates() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 1L, 0L);
        core.vfp().setQ(3, 1L, 0L); // 1+1=2, sem saturação.
        int r = raw(0, 0b1111, 1, 0b10, 2, 0, 0, 0b0000, 1, 3, 1); // VQADD_S
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(2, core.vfp().element(1, 0, 2));
        assertFalse(core.fpscr().qc());
    }

    // ── Predicação nas 3 larguras de elemento (Aceite bullet 4) ─────────────────────────────────

    @Test
    void predicationPreservesInactiveBytesAtByteWidth() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0101_0101_0101_0101L, 0L);
        core.vfp().setQ(3, 0x0101_0101_0101_0101L, 0L);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L); // Qd inicial: todo-1, para ver a preservação.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só os 4 bytes baixos ativos.
        // VADD, esz=0 (byte), Qd=1, Qn=2, Qm=3.
        int r = raw(0, 0b1111, 1, 0b00, 2, 0, 0, 0b1000, 1, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(2, core.vfp().element(1, i, 0), "byte " + i + " ativo: 1+1=2");
        }
        for (int i = 4; i < 8; i++) {
            assertEquals(0xFF, core.vfp().element(1, i, 0), "byte " + i + " mascarado: preserva 0xFF");
        }
    }

    @Test
    void predicationPreservesInactiveHalfwordsAtHalfwordWidth() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0005_0005_0005_0005L, 0L);
        core.vfp().setQ(3, 0x0003_0003_0003_0003L, 0L);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x0003); // só o halfword 0 (bytes 0-1) ativo.
        // VADD, esz=1 (halfword).
        int r = raw(0, 0b1111, 1, 0b01, 2, 0, 0, 0b1000, 1, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(8, core.vfp().element(1, 0, 1), "halfword 0 ativo: 5+3=8");
        assertEquals(0xFFFF, core.vfp().element(1, 1, 1), "halfword 1 mascarado: preserva");
    }

    @Test
    void predicationPreservesInactiveWordsAtWordWidth() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0007_0000_0007L, 0L);
        core.vfp().setQ(3, 0x0000_0002_0000_0002L, 0L);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só a word 0 ativa.
        // VADD, esz=2 (word).
        int r = raw(0, 0b1111, 1, 0b10, 2, 0, 0, 0b1000, 1, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(9, core.vfp().element(1, 0, 2), "word 0 ativa: 7+2=9");
        assertEquals(0xFFFF_FFFFL, core.vfp().element(1, 1, 2), "word 1 mascarada: preserva");
    }

    // ── VMULL_B*/VMULL_T*: lanes PARES/ÍMPARES intercaladas, NÃO metade contígua (achado real) ───

    @Test
    void wideningMultiplyReadsInterleavedLanesNotContiguousHalves() {
        ArmCore core = newCore();
        for (int lane = 0; lane < 8; lane++) {
            core.vfp().setElement(2, lane, 1, lane + 1); // Qn: halfwords 1..8.
            core.vfp().setElement(3, lane, 1, 100); // Qm: halfword constante 100.
        }
        // VMULL_BS: bit28=0(S), size real=1(halfword), bit12=0(bottom).
        int rBottom = raw(0, 0b1110, 1, 0b01, 2, 1, 0, 0b1110, 0, 3, 0);
        put32(core, CODE_BASE, rBottom);
        core.step();
        // Bottom == lanes PARES da fonte (0,2,4,6 -> valores 1,3,5,7) * 100.
        assertEquals(100, core.vfp().element(1, 0, 2));
        assertEquals(300, core.vfp().element(1, 1, 2));
        assertEquals(500, core.vfp().element(1, 2, 2));
        assertEquals(700, core.vfp().element(1, 3, 2));

        core.setProgramCounter(CODE_BASE);
        // VMULL_TS: bit12=1(top).
        int rTop = raw(0, 0b1110, 4, 0b01, 2, 1, 1, 0b1110, 0, 3, 0);
        put32(core, CODE_BASE, rTop);
        core.step();
        // Top == lanes ÍMPARES da fonte (1,3,5,7 -> valores 2,4,6,8) * 100.
        assertEquals(200, core.vfp().element(4, 0, 2));
        assertEquals(400, core.vfp().element(4, 1, 2));
        assertEquals(600, core.vfp().element(4, 2, 2));
        assertEquals(800, core.vfp().element(4, 3, 2));
    }

    // ── VMULLP: polinomial generalizado além de byte (achado real de largura) ───────────────────

    @Test
    void polynomialWideningMultiplyGeneralizesToHalfwordAndWordSources() {
        ArmCore core = newCore();
        core.vfp().setElement(2, 0, 0, 3); // Qn byte 0 = 0b011.
        core.vfp().setElement(3, 0, 0, 5); // Qm byte 0 = 0b101.
        // VMULLP_B: bits[21:20]="11", bit28=0 -> esz FONTE=0 (byte->halfword).
        int r = raw(0, 0b1110, 1, 0b11, 2, 1, 0, 0b1110, 0, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        // PolynomialMult(3,5) = XOR dos bits de 3 deslocados pelos bits setados de 5 (bit0,bit2):
        // 3<<0 ^ 3<<2 = 0b0011 ^ 0b1100 = 0b1111 = 15.
        assertEquals(15, core.vfp().element(1, 0, 1));
    }

    // ── VADC: carry encadeado entre os 4 elementos de 32 bits ───────────────────────────────────

    @Test
    void carryChainsAcrossTheFourWordElements() {
        ArmCore core = newCore();
        core.vfp().setQ(2, (0xFFFF_FFFFL) | (1L << 32), 2L | (3L << 32)); // Qn: [0xFFFFFFFF,1,2,3]
        core.vfp().setQ(3, 1L, 0L); // Qm: [1,0,0,0]
        core.fpscr().setNzcv(0); // carry de entrada = 0.
        // VADC: bits[21:20]="11", bit28=0(add), bit12=0(não-I).
        int r = raw(0, 0b1110, 1, 0b11, 2, 0, 0, 0b1111, 0, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.vfp().element(1, 0, 2), "word0: 0xFFFFFFFF+1+0 transborda -> 0, carry=1");
        assertEquals(2, core.vfp().element(1, 1, 2), "word1: 1+0+carry(1 do word0) = 2");
        assertEquals(2, core.vfp().element(1, 2, 2), "word2: 2+0+0 = 2");
        assertEquals(3, core.vfp().element(1, 3, 2), "word3: 3+0+0 = 3");
        assertFalse(core.fpscr().c(), "carry final = 0 (word3 não transbordou)");
    }

    // ── VCADD90: par SUBTRAI com a lane ímpar seguinte, ímpar SOMA com a lane par anterior ───────

    @Test
    void complexAdd90CombinesAdjacentLanesWithRotation() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 10L | (20L << 32), 30L | (40L << 32)); // Qn: [10,20,30,40]
        core.vfp().setQ(3, 1L | (2L << 32), 3L | (4L << 32)); // Qm: [1,2,3,4]
        // VCADD90: bit28=1(sub-family), bits[21:20]=size(2,word), bit12=0(90).
        int r = raw(1, 0b1110, 1, 0b10, 2, 0, 0, 0b1111, 0, 3, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(8, core.vfp().element(1, 0, 2), "lane par 0: n[0]-m[1] = 10-2 = 8");
        assertEquals(21, core.vfp().element(1, 1, 2), "lane ímpar 1: n[1]+m[0] = 20+1 = 21");
        assertEquals(26, core.vfp().element(1, 2, 2), "lane par 2: n[2]-m[3] = 30-4 = 26");
        assertEquals(43, core.vfp().element(1, 3, 2), "lane ímpar 3: n[3]+m[2] = 40+3 = 43");
    }
}
