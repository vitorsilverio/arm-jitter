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

    @Test
    void absFpAndNegFpFlipTheSignBitOnly() {
        ArmCore core = newCore();
        core.vfp().setQ(2, Float.floatToRawIntBits(-2.5f) & 0xFFFF_FFFFL, 0L);
        int absFp = oneOpRaw(0b01, 2, 0b0111, 0b01, 1, 2); // VABS_fp, esz=single.
        put32(core, CODE_BASE, absFp);
        core.step();
        assertEquals(2.5f, Float.intBitsToFloat((int) core.vfp().element(1, 0, 2)));

        core = newCore();
        core.vfp().setQ(2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL, 0L);
        int negFp = oneOpRaw(0b01, 2, 0b0111, 0b11, 1, 2); // VNEG_fp, esz=single.
        put32(core, CODE_BASE, negFp);
        core.step();
        assertEquals(-2.5f, Float.intBitsToFloat((int) core.vfp().element(1, 0, 2)));
    }

    @Test
    void sqnegSaturatesIntMinToIntMax() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x8000_0000L, 0L); // word0 = INT_MIN.
        int r = oneOpRaw(0b00, 2, 0b0111, 0b11, 1, 2); // VQNEG, esz=word.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x7FFF_FFFFL, core.vfp().element(1, 0, 2));
        assertTrue(core.fpscr().qc());
    }

    @Test
    void clsAndClzComputeExpectedCounts() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 1L, 0L); // word0 = 1.
        int cls = oneOpRaw(0b00, 2, 0b0100, 0b01, 1, 2); // VCLS.
        put32(core, CODE_BASE, cls);
        core.step();
        assertEquals(30, core.vfp().element(1, 0, 2));

        core = newCore();
        core.vfp().setQ(2, 0x10L, 0L); // word0 = 16.
        int clz = oneOpRaw(0b00, 2, 0b0100, 0b11, 1, 2); // VCLZ.
        put32(core, CODE_BASE, clz);
        core.step();
        assertEquals(27, core.vfp().element(1, 0, 2));
    }

    @Test
    void notComplementsBits() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0F0F_0F0FL, 0L);
        int r = oneOpRaw(0b00, 0, 0b0101, 0b11, 1, 2); // VMVN (size sempre 0).
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xF0F0_F0F0L, core.vfp().element(1, 0, 2) & 0xFFFF_FFFFL);
    }

    @Test
    void absAndNegComputeExpectedValues() {
        ArmCore core = newCore();
        core.vfp().setQ(2, -5L & 0xFFFF_FFFFL, 0L);
        int abs = oneOpRaw(0b01, 2, 0b0011, 0b01, 1, 2); // VABS int.
        put32(core, CODE_BASE, abs);
        core.step();
        assertEquals(5L, core.vfp().element(1, 0, 2));

        core = newCore();
        core.vfp().setQ(2, 5L, 0L);
        int neg = oneOpRaw(0b01, 2, 0b0011, 0b11, 1, 2); // VNEG int.
        put32(core, CODE_BASE, neg);
        core.step();
        assertEquals(-5L & 0xFFFF_FFFFL, core.vfp().element(1, 0, 2));
    }

    @Test
    void rev32AndRev16ReorderBytesWithinTheirContainer() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0102_0304L, 0L);
        int rev32 = oneOpRaw(0b00, 0, 0b0000, 0b11, 1, 2); // VREV32, esz=byte.
        put32(core, CODE_BASE, rev32);
        core.step();
        assertEquals(0x0403_0201L, core.vfp().element(1, 0, 2) & 0xFFFF_FFFFL);

        core = newCore();
        core.vfp().setQ(2, 0x0000_0102L, 0L);
        int rev16 = oneOpRaw(0b00, 0, 0b0001, 0b01, 1, 2); // VREV16, esz=byte.
        put32(core, CODE_BASE, rev16);
        core.step();
        assertEquals(0x0000_0201L, core.vfp().element(1, 0, 2) & 0xFFFF_FFFFL);
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

    @Test
    void vdupBroadcastsAtHalfwordAndWordWidths() {
        ArmCore core = newCore();
        core.setRegister(3, 0x1234);
        int half = vdupRaw(0, 1, 2, 3); // B=0,E=1 -> halfword.
        put32(core, CODE_BASE, half);
        core.step();
        assertEquals(0x1234, core.vfp().element(2, 5, 1));

        core = newCore();
        core.setRegister(3, 0x1234_5678);
        int word = vdupRaw(0, 0, 2, 3); // B=0,E=0 -> word.
        put32(core, CODE_BASE, word);
        core.step();
        assertEquals(0x1234_5678L, core.vfp().element(2, 3, 2));
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

    @Test
    void vaddlvAccumulateAddsOntoCurrentRdahiRdalo() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 5L, 0L); // word0 = 5.
        core.setRegister(4, 10); // RdaLo.
        core.setRegister(5, 0); // RdaHi.
        int r = addLvRaw(0, 0b010, 2, 1, 2); // a=1, rdahi=5, rdalo=4, Qm=2.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(15, core.register(4), "10 (RdaLo atual) + 5 = 15");
        assertEquals(0, core.register(5));
    }

    @Test
    void vaddvSkipsMaskedLanesEntirely() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x0000_0002__0000_0001L, 0L); // words 1,2.
        core.setRegister(6, 0);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só a lane 0 (word 1) ativa; lane 1 mascarada.
        int r = addVRaw(0, 2, 3, 1, 3); // a=1, rda=6, Qm=3.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1, core.register(6), "lane 1 (word=2) mascarada não entra na soma");
    }

    @Test
    void vaddvSumsByteAndHalfwordElements() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x0000_0002__0000_0001L, 0L); // words 1,2 -> halfwords 1,0,2,0.
        int halfSum = addVRaw(0, 1, 0, 1, 3); // size=halfword, rda=0.
        put32(core, CODE_BASE, halfSum);
        core.step();
        assertEquals(3, core.register(0), "1+0+2+0 = 3 (halfword)");

        core = newCore();
        core.vfp().setQ(3, 0x0000_0002__0000_0001L, 0L); // bytes 1,0,0,0,2,0,0,0.
        int byteSum = addVRaw(0, 0, 0, 1, 3); // size=byte, rda=0.
        put32(core, CODE_BASE, byteSum);
        core.step();
        assertEquals(3, core.register(0), "1+0+0+0+2+0+0+0 = 3 (byte)");
    }

    @Test
    void vaddvUnsignedFormDoesNotSignExtendNegativeElements() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0xFFFF_FFFFL, 0L); // word0 = -1 assinado, 0xFFFFFFFF não assinado.
        int r = addVRaw(1, 2, 0, 1, 3); // u=1 (unsigned), size=word, rda=0.
        put32(core, CODE_BASE, r);
        core.step();
        assertEquals(0xFFFF_FFFFL, core.register(0) & 0xFFFF_FFFFL, "soma não assinada, sem sign-extend");
    }

    @Test
    void vaddlvUnsignedFormAndMaskedLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L); // words 1,2,3,4.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00FF); // só as lanes 0/1 (words 1,2) ativas.
        int r = addLvRaw(1, 0b010, 0, 1, 3); // u=1, rdahi=5, rdalo=0, a=1, Qm=3.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(3, core.register(0), "1+2 = 3, lanes 2/3 (words 3,4) mascaradas");
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

    @Test
    void vabavUnsignedFormDoesNotSignExtend() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0xFFFF_FFFFL, 0L); // Qn word0 = 0xFFFFFFFF (não assinado: 4294967295).
        core.vfp().setQ(3, 3L, 0L); // Qm word0 = 3.
        core.setRegister(6, 0);
        int r = abavRaw(1, 2, 2, 6, 3); // u=1 (unsigned), size=word.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(-4, core.register(6), "|4294967295-3| trunca em int, sem sign-extend prévio");
    }

    @Test
    void vabavSkipsMaskedLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_000A__0000_0002L, 0L); // Qn: word0=2, word1=10.
        core.vfp().setQ(3, 0x0000_0003__0000_0009L, 0L); // Qm: word0=9, word1=3.
        core.setRegister(6, 0);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00F0); // só a lane 1 (word) ativa: |10-3| = 7.
        int r = abavRaw(0, 2, 2, 6, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(7, core.register(6), "lane 0 mascarada (|2-9|=7 ficaria fora) não entra na soma");
    }

    @Test
    void vabavSignExtendsByteElementsWhenSigned() {
        ArmCore core = newCore();
        // esz=byte: Qn byte0 = -5 (0xFB), Qm byte0 = 2 -> |(-5)-2| = 7 (as byte).
        core.vfp().setQ(2, 0xFBL, 0L);
        core.vfp().setQ(3, 2L, 0L);
        core.setRegister(6, 0);
        int r = abavRaw(0, 0, 2, 6, 3); // size=byte, unsignedForm=false.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(7, core.register(6));
    }

    @Test
    void vabavSignExtendsHalfwordElementsWhenSigned() {
        ArmCore core = newCore();
        // esz=halfword: Qn lane0 = -5 (0xFFFB), Qm lane0 = 2 -> |(-5)-2| = 7.
        core.vfp().setQ(2, 0xFFFBL, 0L);
        core.vfp().setQ(3, 2L, 0L);
        core.setRegister(6, 0);
        int r = abavRaw(0, 1, 2, 6, 3); // size=halfword, unsignedForm=false.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(7, core.register(6));
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

    @Test
    void vbicImmediateClearsTheMaskedBits() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0xFFFF_FFFFL, 0L);
        // cmode=0b0001 (ímpar<12), op=1 -> VBIC; imm8=0x0F limpa o nibble baixo do byte 0.
        int r = vimmRaw(0b0001, 1, 2, 0x0F);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFFFF_FFF0L, core.vfp().element(2, 0, 2) & 0xFFFF_FFFFL);
    }

    @Test
    void vmvnImmediateComplementsTheReplicatedImmediate() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0L, 0L);
        // cmode=0b0000 (par, grupo "replicate2 no byte 0"), op=1 -> nem ORR/BIC (cmode par) nem a
        // exceção cmode=14/op=1 (essa É MOV) -> cai em MVN. imm8=0xFF -> imm64=0x000000FF000000FF,
        // MVN = ~imm64.
        int r = vimmRaw(0b0000, 1, 2, 0xFF);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFFFF_FF00L, core.vfp().element(2, 0, 2) & 0xFFFF_FFFFL);
    }
}
