package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B16.13b — `VMLADAV_S`/`VMLSDAV`/`VMLALDAV_S`/`VRMLALDAVH_S`/`VRMLSLDAVH`/`VMAXV_S`/`VMAXAV`/
/// `VMAXNMV`/`VMAXNMAV`, fim-a-fim (decode + lift + executor interpretado) sobre o preset real
/// `ARMV8_1M_MVE`. Cobre `exchange`/`subtract`/`accumulate`, arredondamento de `VRMLALDAVH`, a
/// semântica sem-bit-`a` de `VMAXV`/`VMAXAV`, o zeramento dos 16 bits altos de `Rda` em formas
/// binary16, e o discriminador de precisão (não sinal) do bloco `U` de `VMAXNM*`. Mesmo padrão de
/// {@code MveMiscReduceExecutionTest}.
class MveDualAccumulateExecutionTest {
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

    private static int embedQn(int qn) {
        return (((qn >>> 3) & 1) << 7) | ((qn & 0x7) << 17);
    }

    private static int embedQm(int qm) {
        return (qm & 0x7) << 1;
    }

    // ── VMLADAV_S / VMLSDAV (32 bits) ───────────────────────────────────────────────────────────────

    private static int mladavSGeneralRaw(int size16, int qn, int x, int a, int qm, int rdaloRaw, boolean subtract) {
        return (0b111 << 29) | (0b1110 << 24) | (0b1111 << 20) | embedQn(qn) | (size16 << 16) | (rdaloRaw << 13)
                | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm) | (subtract ? 1 : 0);
    }

    @Test
    void vmladavSSumsProductsOfActiveLanesAndAccumulates() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L); // Qn words 1,2,3,4.
        core.vfp().setQ(3, 10L | (10L << 32), 10L | (10L << 32)); // Qm words all 10.
        core.setRegister(0, 5); // Rda inicial.
        int r = mladavSGeneralRaw(1, 2, 0, 1, 3, 0, false); // size=word, a=1, rda=0.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(5 + 10 + 20 + 30 + 40, core.register(0));
    }

    @Test
    void vmladavSNoAccumulateStartsFromZero() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 5L, 0L); // Qn word0 = 5.
        core.vfp().setQ(3, 3L, 0L); // Qm word0 = 3.
        core.setRegister(0, 999); // deve ser ignorado.
        int r = mladavSGeneralRaw(1, 2, 0, 0, 3, 0, false);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(15, core.register(0));
    }

    @Test
    void vmlsdavSubtractsOddLaneProducts() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L); // Qn words 1,2,3,4.
        core.vfp().setQ(3, 1L | (1L << 32), 1L | (1L << 32)); // Qm words todos 1.
        core.setRegister(0, 0);
        int r = mladavSGeneralRaw(1, 2, 0, 0, 3, 0, true); // VMLSDAV.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1 - 2 + 3 - 4, core.register(0));
    }

    @Test
    void exchangeSwapsWhichNeighborOfQnIsRead() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0002__0000_0001L, 0x0000_0004__0000_0003L); // Qn words 1,2,3,4.
        core.vfp().setQ(3, 1L | (1L << 32), 1L | (1L << 32)); // Qm words todos 1.
        core.setRegister(0, 0);
        int r = mladavSGeneralRaw(1, 2, 1, 0, 3, 0, true); // VMLSDAV, x=1.
        put32(core, CODE_BASE, r);

        core.step();

        // e0(par) usa n[1]=2; e1(ímpar) usa n[0]=1, subtrai; e2(par) usa n[3]=4; e3(ímpar) usa
        // n[2]=3, subtrai: 2 - 1 + 4 - 3 = 2 (diferente do resultado sem exchange, que foi -2).
        assertEquals(2 - 1 + 4 - 3, core.register(0));
    }

    @Test
    void vmladavSSkipsMaskedLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x0000_0002__0000_0001L, 0L); // Qn words 1,2.
        core.vfp().setQ(3, 1L | (1L << 32), 0L); // Qm words 1,1.
        core.setRegister(0, 0);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só lane 0 ativa.
        int r = mladavSGeneralRaw(1, 2, 0, 0, 3, 0, false);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1, core.register(0), "lane 1 mascarada não entra na soma");
    }

    // ── VMLALDAV_S (64 bits) ────────────────────────────────────────────────────────────────────────

    private static int mlaldavSRaw(int size16, int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b111 << 29) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn) | (size16 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm);
    }

    @Test
    void vmlaldavSAccumulatesInto64BitPair() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 1L | (2L << 16), 0L); // Qn halfwords 1,2 (size=halfword).
        core.vfp().setQ(3, 3L | (3L << 16), 0L); // Qm halfwords 3,3.
        core.setRegister(2, 0); // RdaLo (rdaloRaw=1 -> rda=2).
        core.setRegister(3, 0); // RdaHi (rdahiRaw=1 -> rdahi=3).
        int r = mlaldavSRaw(0, 2, 0, 0, 3, 0b001, 1); // size16=0 -> size=1 (halfword), a=0.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1 * 3 + 2 * 3, core.register(2));
        assertEquals(0, core.register(3));
    }

    @Test
    void vmlaldavSAccumulatesOntoCurrentPairAndSkipsMaskedLanes() {
        ArmCore core = newCore();
        // Qn halfwords 1,2,3,4 (lanes 0-3); Qm halfwords todas 1.
        core.vfp().setQ(2, 1L | (2L << 16) | (3L << 32) | (4L << 48), 0L);
        core.vfp().setQ(3, 1L | (1L << 16) | (1L << 32) | (1L << 48), 0L);
        core.setRegister(2, 100); // RdaLo atual.
        core.setRegister(3, 0); // RdaHi atual.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00FF); // só lanes 0-3 (halfwords 1,2,3,4) ativas.
        int r = mlaldavSRaw(0, 2, 0, 1, 3, 0b001, 1); // a=1.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(100 + 1 + 2 + 3 + 4, core.register(2), "acumula sobre RdaLo atual (a=1)");
    }

    @Test
    void vmlaldavSSkipsMaskedLanesEntirely() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 1L | (2L << 16) | (3L << 32) | (4L << 48), 0L);
        core.vfp().setQ(3, 1L | (1L << 16) | (1L << 32) | (1L << 48), 0L);
        core.setRegister(2, 0);
        core.setRegister(3, 0);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x0003); // só lane 0 (halfword=1, 2 bytes) ativa.
        int r = mlaldavSRaw(0, 2, 0, 0, 3, 0b001, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(1, core.register(2), "lanes 1/2/3 mascaradas não entram na soma");
    }

    // ── VRMLALDAVH_S / VRMLSLDAVH (arredondado, elementos word) ─────────────────────────────────────

    /// `VRMLALDAVH_S` (top byte `1110 1110`, `bits[11:8]=1111`, tail do bloco `S`).
    private static int vrmlaldavhSRaw(int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b1110 << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn) | (0 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1111 << 8) | (a << 5) | embedQm(qm);
    }

    /// `VRMLSLDAVH` (top byte `1111 1110`, `bits[11:8]=1110`, 4º bloco `{}` — encoding DISTINTO de
    /// `VRMLALDAVH_S`, não apenas `VRMLALDAVH_S` com `bit0` trocado; sempre subtrai, `subtract` não é
    /// um bit do encoding).
    private static int vrmlsldavhRaw(int qn, int x, int a, int qm, int rdahiRaw, int rdaloRaw) {
        return (0b1111 << 28) | (0b1110 << 24) | (1 << 23) | (rdahiRaw << 20) | embedQn(qn) | (0 << 16)
                | (rdaloRaw << 13) | (x << 12) | (0b1110 << 8) | (a << 5) | embedQm(qm) | 1;
    }

    @Test
    void vrmlaldavhRoundsProductRightShiftedByEight() {
        ArmCore core = newCore();
        // n=256,m=1 em cada word -> mul=256, rounded=(256>>8)+((256>>7)&1)=1+0=1 por lane ativa.
        core.vfp().setQ(2, 256L | (256L << 32), 256L | (256L << 32));
        core.vfp().setQ(3, 1L | (1L << 32), 1L | (1L << 32));
        core.setRegister(0, 0); // RdaLo.
        core.setRegister(1, 0); // RdaHi.
        int r = vrmlaldavhSRaw(2, 0, 0, 3, 0b000, 0); // rdahi=1, rdalo=0.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(4, core.register(0), "4 lanes word, 1 arredondado cada, soma sempre (VRMLALDAVH)");
        assertEquals(0, core.register(1));
    }

    @Test
    void vrmlaldavhAccumulatesOntoCurrentPairAndSkipsMaskedLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 256L | (256L << 32), 256L | (256L << 32));
        core.vfp().setQ(3, 1L | (1L << 32), 1L | (1L << 32));
        core.setRegister(0, 100); // RdaLo atual.
        core.setRegister(1, 0); // RdaHi atual.
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00FF); // só lanes 0/1 (word), lanes 2/3 mascaradas.
        int r = vrmlaldavhSRaw(2, 0, 1, 3, 0b000, 0); // a=1.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(100 + 1 + 1, core.register(0), "acumula sobre RdaLo atual; lanes 2/3 mascaradas");
    }

    @Test
    void vrmlsldavhNegatesOddLaneProductBeforeRounding() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 256L | (256L << 32), 256L | (256L << 32));
        core.vfp().setQ(3, 1L | (1L << 32), 1L | (1L << 32));
        core.setRegister(0, 999); // deve ser ignorado (a=0).
        core.setRegister(1, 0);
        int r = vrmlsldavhRaw(2, 0, 0, 3, 0b000, 0); // VRMLSLDAVH, a=0.
        put32(core, CODE_BASE, r);

        core.step();

        // lane0(par)=+1, lane1(ímpar)=-1, lane2(par)=+1, lane3(ímpar)=-1 -> soma 0, do zero.
        assertEquals(0, core.register(0));
    }

    // ── VMAXV_S / VMAXAV (sem bit `a`, sempre lê Rda atual) ─────────────────────────────────────────

    private static int embedQmVmaxv(int qm) {
        return (((qm >>> 3) & 1) << 5) | ((qm & 0x7) << 1);
    }

    private static int intMinMaxSRaw(int selector, int size, int rda, int qm, boolean min) {
        return (0b1110 << 28) | (0b1110 << 24) | (0b1110 << 20) | (selector << 16) | (size << 18) | (rda << 12)
                | (0b1111 << 8) | (min ? (1 << 7) : 0) | embedQmVmaxv(qm);
    }

    @Test
    void vmaxvAlwaysReadsCurrentRdaAsSeed() {
        ArmCore core = newCore();
        core.vfp().setQ(2, (3L & 0xFFFFFFFFL) | ((-10 & 0xFFFFFFFFL) << 32), (7L & 0xFFFFFFFFL) | ((2L) << 32));
        core.setRegister(0, 5);
        int r = intMinMaxSRaw(0b10, 2, 0, 2, false); // VMAXV_S, size=word.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(7, core.register(0), "max(5,3,-10,7,2) = 7");
    }

    @Test
    void vminvAlwaysReadsCurrentRdaAsSeed() {
        ArmCore core = newCore();
        core.vfp().setQ(2, (3L & 0xFFFFFFFFL) | ((-10 & 0xFFFFFFFFL) << 32), (7L & 0xFFFFFFFFL) | ((2L) << 32));
        core.setRegister(0, 5);
        int r = intMinMaxSRaw(0b10, 2, 0, 2, true); // VMINV_S.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(-10, core.register(0), "min(5,3,-10,7,2) = -10");
    }

    @Test
    void vmaxvSkipsMaskedLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(2, (3L & 0xFFFFFFFFL) | ((-10 & 0xFFFFFFFFL) << 32), (7L & 0xFFFFFFFFL) | ((2L) << 32));
        core.setRegister(0, 5);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x00FF); // só lanes 0/1 (words 3,-10) ativas; lane 2 (word 7) mascarada.
        int r = intMinMaxSRaw(0b10, 2, 0, 2, false); // VMAXV_S, size=word.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(5, core.register(0), "max(5,3,-10) = 5, lane do word 7 mascarada não conta");
    }

    @Test
    void vmaxavComparesAbsoluteValueOfSignedElementsAgainstUnsignedRda() {
        ArmCore core = newCore();
        core.vfp().setQ(2, (-50 & 0xFFFFFFFFL) | (3L << 32), 0L);
        core.setRegister(0, 2);
        int r = intMinMaxSRaw(0b00, 2, 0, 2, false); // VMAXAV, size=word.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(50, core.register(0), "max(2, |-50|, |3|) = 50");
    }

    // ── VMAXNMV / VMAXNMAV (ponto flutuante, precisão por bloco) ────────────────────────────────────

    private static int fpMinMaxRaw(int topByte, int bits19_16, int rda, int qm) {
        return (topByte << 24) | (0b1110 << 20) | (bits19_16 << 16) | (rda << 12) | (0b1111 << 8)
                | embedQmVmaxv(qm);
    }

    @Test
    void vmaxnmvSingleprecisionComparesFullRegister() {
        ArmCore core = newCore();
        core.vfp().setQ(2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL, 0L);
        core.setRegister(0, Float.floatToRawIntBits(1.0f));
        int r = fpMinMaxRaw(0b1110_1110, 0b1110, 0, 2); // bloco S (esz=2/single), VMAXNMV.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(2.5f, Float.intBitsToFloat(core.register(0)));
    }

    @Test
    void vmaxnmavTakesAbsoluteValueOfSourceOnly() {
        ArmCore core = newCore();
        core.vfp().setQ(2, Float.floatToRawIntBits(-9.0f) & 0xFFFF_FFFFL, 0L);
        core.setRegister(0, Float.floatToRawIntBits(1.0f));
        int r = fpMinMaxRaw(0b1110_1110, 0b1100, 0, 2); // bloco S, VMAXNMAV (absoluteForm).
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(9.0f, Float.intBitsToFloat(core.register(0)));
    }

    @Test
    void vmaxnmvSkipsMaskedLanes() {
        ArmCore core = newCore();
        core.vfp().setQ(2, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL,
                Float.floatToRawIntBits(99.0f) & 0xFFFF_FFFFL);
        core.setRegister(0, Float.floatToRawIntBits(1.0f));
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só lane 0 (2.5f) ativa; o 99.0f (lane 2) fica mascarado.
        int r = fpMinMaxRaw(0b1110_1110, 0b1110, 0, 2); // bloco S (single), VMAXNMV.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(2.5f, Float.intBitsToFloat(core.register(0)), "lane com 99.0f mascarada não conta");
    }

    @Test
    void vmaxnmavHalfPrecisionTakesAbsoluteValueAndSkipsMaskedLanes() {
        ArmCore core = newCore();
        short hNeg3 = (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(-3.0f);
        short h5 = (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(5.0f);
        short hNeg9 = (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(-9.0f);
        long low64 = (hNeg3 & 0xFFFFL) | ((h5 & 0xFFFFL) << 16) | ((hNeg9 & 0xFFFFL) << 32);
        core.vfp().setQ(2, low64, 0L); // halfwords: -3.0 (lane0), 5.0 (lane1), -9.0 (lane2).
        core.setRegister(0,
                0xFFFF_0000 | (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(2.0f));
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x0033); // lanes 0 e 2 ativas (bytes 0-1 e 4-5); lane 1 (5.0) mascarada.
        int r = fpMinMaxRaw(0b1111_1110, 0b1100, 0, 2); // bloco U (half), VMAXNMAV (absoluteForm).
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.register(0) & 0xFFFF_0000, "16 bits altos zerados");
        assertEquals(9.0f, dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfToFloat(core.register(0) & 0xFFFFL),
                "max(2.0, |-3.0|, |-9.0|) = 9.0; lane com 5.0 mascarada não conta");
    }

    @Test
    void halfPrecisionFormZeroesUpperHalfOfRda() {
        ArmCore core = newCore();
        short halfOne = (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(1.0f);
        short halfTwo = (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(2.0f);
        core.vfp().setQ(2, (halfOne & 0xFFFFL) | ((halfTwo & 0xFFFFL) << 16), 0L);
        core.setRegister(0, 0xFFFF_0000 | (short) dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfBits(0.5f));
        int r = fpMinMaxRaw(0b1111_1110, 0b1110, 0, 2); // bloco U (esz=1/half), VMAXNMV.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0, core.register(0) & 0xFFFF_0000, "16 bits altos zerados na forma binary16");
        assertEquals(2.0f, dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes.halfToFloat(core.register(0) & 0xFFFFL));
    }
}
