package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.13a — 1-op misc/`VDUP`/movimentos lane↔GPR/reduções/imediato modificado, fim-a-fim (decode +
/// lift + executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Cobre os itens do Aceite que
/// exigem execução: predicação preserva a lane inativa, `FPSCR.QC` só em lane ativa (`VQABS`/
/// `VQNEG`), `%qn` de `VDUP`, `VMOV_to_2gp`/`VMOV_from_2gp` NÃO predicados, semântica `a`/máscara-
/// -toda-zero de `VADDV`/`VADDLV`, acumulação sempre-ligada de `VABAV`, e os 4 comportamentos de
/// `Vimm_1r`. Mesmo padrão de {@code MveVector2opExecutionTest}.
class MveMiscReduceExecutionTest {
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

    // ── 1-op misc (VREV64/VQABS/VQNEG) ──────────────────────────────────────────────────────────

    private static int oneOpRaw(int group, int size, int nibble, int sub2, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111111111 << 23) | (qdHigh << 22) | (0b11 << 20) | (size << 18) | (group << 16) | (qdLow << 13)
                | (nibble << 8) | (qmHigh << 5) | (sub2 << 6) | (qmLow << 1);
    }

    @Test
    void vrev64ReordersWordsWithinEachDoubleword() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L);
        // VREV64, esz=2 (word), Qd=1, Qm=2.
        int r = oneOpRaw(0b00, 2, 0b0000, 0b01, 1, 2);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(2L, core.vfp().element(1, 0, 2), "lane 0 e 1 trocadas dentro do primeiro D");
        assertEquals(1L, core.vfp().element(1, 1, 2));
        assertEquals(4L, core.vfp().element(1, 2, 2), "lane 2 e 3 trocadas dentro do segundo D");
        assertEquals(3L, core.vfp().element(1, 3, 2));
    }

    @Test
    void sqabsSetsQcOnlyWhenActiveLaneSaturates() {
        ArmCore core = newCore();
        core.vfp().setQ(2, (0x8000_0000L) | (0x8000_0000L << 32), 0L); // word0/word1 = INT_MIN.
        core.vfp().setQ(1, 0L, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00F0); // só a lane 1 (word) ativa.
        // VQABS, esz=2 (word), Qd=1, Qm=2.
        int r = oneOpRaw(0b00, 2, 0b0111, 0b01, 1, 2);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.vfp().element(1, 0, 2), "lane 0 mascarada preserva o valor atual");
        assertEquals(0x7FFF_FFFFL, core.vfp().element(1, 1, 2), "lane 1 ativa satura em INT_MAX");
        assertTrue(core.fpscr().qc());
    }

    // ── VDUP ─────────────────────────────────────────────────────────────────────────────────────

    private static int vdupRaw(int b, int e, int qd, int rt) {
        int qHigh = (qd >>> 3) & 1;
        int qLow = qd & 0x7;
        return (0b1110_1110 << 24) | (1 << 23) | (b << 22) | (0b10 << 20) | (qLow << 17) | (qHigh << 7) | (rt << 12)
                | (0b1011 << 8) | (e << 5);
    }

    @Test
    void vdupBroadcastsRtToAllActiveByteLanes() {
        ArmCore core = newCore();
        core.setRegister(3, 0x7F);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0xFF00); // só a metade alta (bytes 8-15) ativa.
        int r = vdupRaw(1, 0, 2, 3); // B=1,E=0 -> byte; Qd=2 (via %qn); Rt=3.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.vfp().element(2, 0, 0), "byte 0 mascarado preserva o valor atual");
        assertEquals(0x7F, core.vfp().element(2, 8, 0), "byte 8 ativo recebe Rt");
    }

    // ── VMOV_to_2gp / VMOV_from_2gp ──────────────────────────────────────────────────────────────

    private static int moveLanesRaw(int variant, int qd, int rt2, int idx, int rt) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b111011000 << 23) | (qdHigh << 22) | (variant << 20) | (rt2 << 16) | (qdLow << 13) | (0b1111 << 8)
                | (idx << 4) | rt;
    }

    @Test
    void vmovToTwoGpReadsLanesZeroAndTwoWhenIdxZero() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L);
        int r = moveLanesRaw(0b00, 1, 6, 0, 5); // idx=0 -> lanes {0,2}.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1, core.register(5), "Rt = lane 0");
        assertEquals(3, core.register(6), "Rt2 = lane 2");
    }

    @Test
    void vmovFromTwoGpIsNotPredicated() {
        ArmCore core = newCore();
        core.setRegister(5, 0x11);
        core.setRegister(6, 0x22);
        core.vfp().setQ(1, 0L, 0L);
        // Todas as lanes mascaradas OFF — a instrução não é predicada, então executa mesmo assim.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0);
        int r = moveLanesRaw(0b01, 1, 6, 1, 5); // idx=1 -> lanes {1,3}.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x11, core.vfp().element(1, 1, 2));
        assertEquals(0x22, core.vfp().element(1, 3, 2));
    }

    // ── VADDV / VADDLV ───────────────────────────────────────────────────────────────────────────

    private static int addVRaw(int u, int size, int rdaloRaw, int a, int qm) {
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (0b1111 << 20) | (size << 18) | (0b01 << 16)
                | (rdaloRaw << 13) | (0b1111 << 8) | (a << 5) | (qm << 1);
    }

    @Test
    void vaddvNoAccumulateStartsFromZeroAndOnlySumsActiveLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L); // words 1,2,3,4.
        core.setRegister(6, 0xFFFF); // valor anterior, deve ser IGNORADO (a=0).
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x0F0F); // lanes 0 e 2 ativas (words = 1 e 3).
        int r = addVRaw(0, 2, 3, 0, 3); // size=word, rda=6, a=0, Qm=3.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(4, core.register(6), "1 + 3 = 4, começando do zero, ignorando lanes mascaradas");
    }

    @Test
    void vaddvAccumulateAddsOntoCurrentRda() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 5L, 0L); // word0 = 5.
        core.setRegister(6, 10);
        int r = addVRaw(0, 2, 3, 1, 3); // a=1, todas as lanes ativas (VPR default).
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(15, core.register(6));
    }

    private static int addLvRaw(int u, int rdahiRaw, int rdaloRaw, int a, int qm) {
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | (0b1001 << 16)
                | (rdaloRaw << 13) | (0b1111 << 8) | (a << 5) | (qm << 1);
    }

    @Test
    void vaddlvAccumulatesIntoRdahiRdaloPair() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L); // words 1,2,3,4.
        core.setRegister(4, 0); // RdaLo.
        core.setRegister(5, 0); // RdaHi.
        int r = addLvRaw(0, 0b010, 2, 0, 2); // rdahi=5, rdalo=4, a=0, Qm=2.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(10, core.register(4), "1+2+3+4 = 10, cabe em RdaLo");
        assertEquals(0, core.register(5));
    }

    // ── VABAV ────────────────────────────────────────────────────────────────────────────────────

    private static int abavRaw(int u, int size, int qn, int rda, int qm) {
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (0b10 << 22) | (size << 20) | (qnLow << 17) | (rda << 12)
                | (0b1111 << 8) | (qnHigh << 7) | (qmHigh << 5) | (qmLow << 1) | 1;
    }

    @Test
    void vabavAlwaysAccumulatesOntoCurrentRda() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 10L, 0L); // Qn word0 = 10.
        core.vfp().setQ(3, 3L, 0L); // Qm word0 = 3.
        core.setRegister(6, 100);
        int r = abavRaw(0, 2, 2, 6, 3); // size=word, Qn=2, Rda=6, Qm=3.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(107, core.register(6), "100 + |10-3| = 107, sempre acumula (sem bit a)");
    }

    // ── Vimm_1r ──────────────────────────────────────────────────────────────────────────────────

    private static int vimmRaw(int cmode, int op, int qd, int imm8) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int immHigh = (imm8 >>> 7) & 1;
        int immMid = (imm8 >>> 4) & 0x7;
        int immLow = imm8 & 0xF;
        return (0b111 << 29) | (immHigh << 28) | (0b11111 << 23) | (qdHigh << 22) | (immMid << 16) | (qdLow << 13)
                | (cmode << 8) | (0b1 << 6) | (op << 5) | (0b1 << 4) | immLow;
    }

    @Test
    void vmovImmediateOverwritesAllActiveWords() {
        ArmCore core = newCore();
        core.vfp().setQ(2, -1L, -1L);
        // cmode=0b0000 (par -> MOV), op=0, imm8=0xAB -> replica no byte 0 de cada word de 32 bits.
        int r = vimmRaw(0b0000, 0, 2, 0xAB);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xABL, core.vfp().element(2, 0, 2) & 0xFFL);
    }

    @Test
    void vorrImmediatePreservesBitsOutsideTheMaskAndMasksByWord() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0L, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0xFF00); // só a palavra alta (byte 8, dentro do 2º D) ativa.
        // cmode=0b0001 (ímpar<12), op=0 -> VORR; imm8=0x01 no byte 0 de cada word.
        int r = vimmRaw(0b0001, 0, 2, 0x01);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0L, core.vfp().element(2, 0, 2), "word 0 mascarada preserva zero");
        assertEquals(1L, core.vfp().element(2, 2, 2), "word 2 (no D alto) recebe o OR");
    }
}
