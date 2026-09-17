package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.7 (sub-família 2) — `VCMUL0/90/180/270`, `VQDMLADH`/`VQDMLSDH` (+ `X`/`R`) e `VQDMULLB`/
/// `VQDMULLT`, fim-a-fim (decode + lift + executor interpretado) sobre o preset real `ARMV8_1M_MVE`.
/// Foco nos achados que corrigem suposições ingênuas: `VCMUL` usa o MESMO elemento de `Qn` para as
/// duas metades do par (real × complexo, não complexo × complexo), `VQDMLADH`/`VQDMLSDH` escrevem SÓ
/// METADE das lanes (a outra metade fica intocada), e `VQDMULLB`/`T` satura de verdade (`FPSCR.QC`).
class MveVectorComplexDualAccumulateExecutionTest {
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

    /// MESMO layout do Javadoc de `Thumb2MveComplexDualAccumulateDecoder`/
    /// `Thumb2MveComplexDualAccumulateDecoderTest`.
    private static int raw(int bit28, int field2120, int qd, int qn, int qm, int bit16, int nibble, int bit0) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (bit28 << 28) | (0b1110 << 24) | (qdHigh << 22) | (field2120 << 20) | (qnLow << 17)
                | (bit16 << 16) | (qdLow << 13) | (nibble << 8) | (qnHigh << 7) | (qmHigh << 5) | (qmLow << 1) | bit0;
    }

    private static final int NIBBLE_ADD_ROT = 0b1110;
    private static final int NIBBLE_DOUBLING_WIDEN = 0b1111;

    // ── VCMUL0/90: MESMO elemento de Qn contribui para as duas metades do par ──────────────────────

    @Test
    void vcmul0MultipliesSameNElementByBothMHalves() {
        ArmCore core = newCore();
        core.vfp().setQ(2, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL, 0L); // Qn word0=2.0, word1 irrelevante.
        long mBits = (Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL) << 32 | (Float.floatToRawIntBits(3.0f)
                & 0xFFFF_FFFFL);
        core.vfp().setQ(3, mBits, 0L); // Qm word0=3.0, word1=4.0.
        // VCMUL0: bit28=1(esz=2,single),field2120=0b11,bit16=0,bit0=0,Qd=1,Qn=2,Qm=3.
        int r = raw(1, 0b11, 1, 2, 3, 0, NIBBLE_ADD_ROT, 0);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(6.0f, Float.intBitsToFloat((int) core.vfp().element(1, 0, 2)), "n[0]*m[0]=2*3");
        assertEquals(8.0f, Float.intBitsToFloat((int) core.vfp().element(1, 1, 2)), "n[0]*m[1]=2*4 (MESMO n[0])");
    }

    @Test
    void vcmul90UsesNHighHalfAndNegatesMHigh() {
        ArmCore core = newCore();
        long nBits = (Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL) << 32 | (Float.floatToRawIntBits(10.0f)
                & 0xFFFF_FFFFL);
        core.vfp().setQ(2, nBits, 0L); // Qn word0=10.0(não usado), word1=2.0(usado).
        long mBits = (Float.floatToRawIntBits(4.0f) & 0xFFFF_FFFFL) << 32 | (Float.floatToRawIntBits(3.0f)
                & 0xFFFF_FFFFL);
        core.vfp().setQ(3, mBits, 0L); // Qm word0=3.0, word1=4.0.
        // VCMUL90: bit28=1,field2120=0b11,bit16=0,bit0=1,Qd=1,Qn=2,Qm=3.
        int r = raw(1, 0b11, 1, 2, 3, 0, NIBBLE_ADD_ROT, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(-8.0f, Float.intBitsToFloat((int) core.vfp().element(1, 0, 2)), "n[1]*(-m[1])=2*-4");
        assertEquals(6.0f, Float.intBitsToFloat((int) core.vfp().element(1, 1, 2)), "n[1]*m[0]=2*3");
    }

    // ── VQDMLADH/VQDMLSDH: só METADE das lanes é escrita ────────────────────────────────────────────

    @Test
    void vqdmladhWritesOnlyEvenLanesLeavingOddUntouched() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 100L, 0L); // Qn word0=100, word1=0.
        core.vfp().setQ(3, 100L, 0L); // Qm word0=100, word1=0.
        core.vfp().setQ(1, 0xAAAA_AAAA_AAAA_AAAAL, 0xAAAA_AAAA_AAAA_AAAAL); // Qd sentinela em todas as lanes.
        // VQDMLADH: bit28=0(add),size=2(word),bit16=0(sem exchange),bit0=0(sem rounding),Qd=1,Qn=2,Qm=3.
        int r = raw(0, 2, 1, 2, 3, 0, NIBBLE_ADD_ROT, 0);
        put32(core, CODE_BASE, r);

        core.step();

        // (100*100 + 0*0)*2 = 20000; high = 20000 >> 32 = 0 (esz=2/word, esize=32).
        assertEquals(0L, core.vfp().element(1, 0, 2), "lane par (não-exchange) escrita");
        assertEquals(0xAAAA_AAAAL, core.vfp().element(1, 1, 2), "lane ímpar intocada (exchange escreveria aqui)");
    }

    @Test
    void vqdmladhComputesHighHalfWithoutOverflowUsingByteWidth() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 100L, 0L); // Qn: byte0=100,byte1=0.
        core.vfp().setQ(3, 100L, 0L); // Qm: byte0=100,byte1=0.
        // VQDMLADH: bit28=0(add),size=0(byte),bit16=0,bit0=0,Qd=1,Qn=2,Qm=3.
        int r = raw(0, 0, 1, 2, 3, 0, NIBBLE_ADD_ROT, 0);
        put32(core, CODE_BASE, r);

        core.step();

        // (100*100+0*0)*2=20000=0x4E20; do_sat_bhw ao intervalo int16 (cabe); >>8 = 0x4E = 78.
        assertEquals(78L, core.vfp().element(1, 0, 0));
    }

    @Test
    void vqdmlsdhSubtractsAndExchangeWritesOddLane() {
        ArmCore core = newCore();
        // Word size, lanes n=[10,3,0,0], m=[2,0,0,0] -> exchange usa (n[1]*m[0] - n[0]*m[1]).
        long nBits = (3L << 32) | 10L;
        core.vfp().setQ(2, nBits, 0L);
        core.vfp().setQ(3, 2L, 0L);
        // VQDMLSDH X: bit28=1(sub),size=2,bit16=1(exchange),bit0=0,Qd=1,Qn=2,Qm=3.
        int r = raw(1, 2, 1, 2, 3, 1, NIBBLE_ADD_ROT, 0);
        put32(core, CODE_BASE, r);

        core.step();

        // e=1(ímpar): a=n[1]=3,b=m[0]=2,c=n[0]=10,d=m[1]=0 -> (3*2 - 10*0)*2 = 12; high=12>>32=0.
        assertEquals(0L, core.vfp().element(1, 1, 2), "lane ímpar (exchange) escrita");
    }

    // ── VQDMULLB/T: alarga saturando, indexação intercalada `le*2+top` ──────────────────────────────

    @Test
    void vqdmullbWidensInterleavedBottomLanesWithoutSaturating() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 3L, 0L); // Qn halfword[0]=3.
        core.vfp().setQ(3, 4L, 0L); // Qm halfword[0]=4.
        // VQDMULLB: bit28=0(esz=1,halfword fonte),field2120=0b11,bit16=0(bottom),bit0=1,Qd=1,Qn=2,Qm=3.
        int r = raw(0, 0b11, 1, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 1);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(24L, core.vfp().element(1, 0, 2), "2*3*4=24, sem saturar");
        assertFalse(core.fpscr().qc());
    }

    @Test
    void vqdmullbSaturatesAtMinTimesMinAndSetsQc() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x8000L, 0L); // Qn halfword[0] = -32768 (INT16_MIN).
        core.vfp().setQ(3, 0x8000L, 0L); // Qm halfword[0] = -32768.
        int r = raw(0, 0b11, 1, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 1);
        put32(core, CODE_BASE, r);

        core.step();

        // (-32768)*(-32768)=1073741824; *2=2147483648 > INT32_MAX -> satura a INT32_MAX.
        assertEquals(0x7FFF_FFFFL, core.vfp().element(1, 0, 2));
        assertTrue(core.fpscr().qc());
    }
}
