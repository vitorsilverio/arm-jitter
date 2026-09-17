package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.7 (sub-família 1) — vector 2-op sobreposto (`VCVTB/T_SH/HS`, `VMAXNMA`/`VMINNMA`, `VSHLL` T2,
/// `VQMOVUNB/T`, `VMOVNB/T`, `VQMOVN_*`, `VMAXA`/`VMINA`, `VMULH`/`VRMULH`), fim-a-fim (decode + lift
/// + executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Foco nos achados que corrigem
/// suposições ingênuas: indexação INTERCALADA (fonte para `VSHLL`, destino para `VMOVN*`/`VQMOVN*`),
/// `VMAXA`/`VMINA` com comparação NÃO ASSINADA sobre `|sext(Qm)|`, `VMOVNB` (não `VQMOVUNB`) na forma
/// `U=1`, e predicação preservando lanes inativas.
class MveVectorOverlapExecutionTest {
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

    /// MESMO layout do Javadoc de `Thumb2MveVectorOverlapDecoder`/
    /// `Thumb2MveVectorOverlapDecoderTest`.
    private static int raw(int u, int field2120, int qd, int field1916, int bit12, int bit7, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (qdHigh << 22) | (field2120 << 20) | (field1916 << 16)
                | (qdLow << 13) | (bit12 << 12) | (0b1110 << 8) | (bit7 << 7) | (qmHigh << 5) | (qmLow << 1) | 1;
    }

    private static int rawMulh(int u, int size, int qd, int qn, int bit12, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qnHigh = (qn >>> 3) & 1;
        int qnLow = qn & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (qdHigh << 22) | (size << 20) | (qnLow << 17) | (1 << 16)
                | (qdLow << 13) | (bit12 << 12) | (0b1110 << 8) | (qnHigh << 7) | (qmHigh << 5) | (qmLow << 1) | 1;
    }

    // ── VSHLL T2: indexação intercalada na FONTE ────────────────────────────────────────────────────

    @Test
    void shllBottomReadsEvenBytesInterleavedAndShiftsBySize() {
        ArmCore core = newCore();
        // Qm byte lanes: [0]=0x01,[1]=0xFF(ímpar, deve ser IGNORADO por top=false),[2]=0x02,[3]=0xFF...
        core.vfp().setQ(3, 0xFF02_FF01L, 0L);
        // VSHLL_BS: U=0(signed),bits[19:18]=00(byte,shift=8),bit12=0(bottom),Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b0001, 0, 0, 3);
        put32(core, CODE_BASE, r);

        core.step();

        // le=0 lê byte 0(0x01)->halfword 0x0100; le=1 lê byte 2(0x02)->halfword 0x0200 (byte 1/3
        // ÍMPARES, da forma _T, são ignorados aqui).
        assertEquals(0x0100, core.vfp().element(1, 0, 1));
        assertEquals(0x0200, core.vfp().element(1, 1, 1));
    }

    @Test
    void shllTopReadsOddBytesAndSignExtendsBeforeShift() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x8000L, 0L); // byte1 = 0x80 (assinado = -128).
        // VSHLL_TS: U=0,byte,shift=8,bit12=1(top),Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b0001, 1, 0, 3);
        put32(core, CODE_BASE, r);

        core.step();

        // sext(0x80,8)=-128; -128<<8 truncado a 16 bits = 0x8000.
        assertEquals(0x8000, core.vfp().element(1, 0, 1));
    }

    // ── VMOVN/VQMOVN/VQMOVUN: indexação intercalada no DESTINO ──────────────────────────────────────

    @Test
    void movnBottomWritesEvenHalfwordsPreservingOddOnes() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0x0000_0000_1234_5678L, 0L); // halfwords fonte: [0]=0x5678,[1]=0x1234.
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0xFFFF_FFFF_FFFF_FFFFL); // Qd todo-1 (preservação).
        // VMOVNB: U=1(forma sem saturação),bits[19:18]=00(byte destino),bit7=1,bit12=0(bottom),Qd=1,Qm=3.
        int r = raw(1, 0b11, 1, 0b0001, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0x78, core.vfp().element(1, 0, 0), "le=0 -> destLane=0 recebe byte baixo de 0x5678");
        assertEquals(0xFF, core.vfp().element(1, 1, 0), "destLane=1 (ímpar, forma T) preservado intocado");
        assertEquals(0x34, core.vfp().element(1, 2, 0), "le=1 -> destLane=2 recebe byte baixo de 0x1234");
    }

    @Test
    void qmovunSaturatesNegativeSignedSourceToZeroAndSetsQc() {
        ArmCore core = newCore();
        core.vfp().setQ(3, 0xFFFF_FFFF_0000_00FFL, 0L); // halfword[0]=0x00FF(255),halfword[1]=0xFFFF(-1).
        // VQMOVUNB: U=0,byte-dest(bits[19:18]=00),bit7=1,bit12=0,Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b0001, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(0xFF, core.vfp().element(1, 0, 0), "255 cabe em unsigned8, sem saturar");
        assertTrue(core.fpscr().qc(), "halfword[1]=-1 não cabe em [0,255] -> satura, QC setado");
    }

    // ── VMAXA/VMINA: comparação NÃO ASSINADA sobre |sext(Qm)| acumulando em Qd ─────────────────────

    @Test
    void maxaComparesUnsignedAbsoluteValueAndAccumulatesIntoQd() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 100L, 0L); // Qd atual (word0) = 100.
        core.vfp().setQ(3, (long) (-150) & 0xFFFF_FFFFL, 0L); // Qm (word0) = -150 (signed).
        // VMAXA: U=0,size=2(word,bits[19:18]=10 -> field1916=0b1011),bit7=1,bit12=0,Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b1011, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(150, core.vfp().element(1, 0, 2), "|-150|=150 > 100, vira o novo Qd");
    }

    @Test
    void minaKeepsCurrentQdWhenItsAlreadySmaller() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 5L, 0L);
        core.vfp().setQ(3, (long) (-150) & 0xFFFF_FFFFL, 0L);
        // VMINA: U=0,size=2,bit7=1,bit12=1(min),Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b1011, 1, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(5, core.vfp().element(1, 0, 2), "min(5,150)=5, Qd atual preservado");
    }

    // ── VMAXNMA/VMINNMA: máximo/mínimo do VALOR ABSOLUTO em ponto flutuante ─────────────────────────

    @Test
    void maxnmaTakesAbsoluteValueOfBothOperandsBeforeComparing() {
        ArmCore core = newCore();
        core.vfp().setQ(1, Float.floatToRawIntBits(-3.0f) & 0xFFFF_FFFFL, 0L); // Qd = -3.0f.
        core.vfp().setQ(3, Float.floatToRawIntBits(2.0f) & 0xFFFF_FFFFL, 0L); // Qm = 2.0f.
        // VMAXNMA: U=0(single,esz=2),field1916=1111,bit7=1,bit12=0(max),Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b1111, 0, 1, 3);
        put32(core, CODE_BASE, r);

        core.step();

        float result = Float.intBitsToFloat((int) core.vfp().element(1, 0, 2));
        assertEquals(3.0f, result, "max(|-3.0|,|2.0|)=3.0, não -3.0 nem 2.0");
    }

    // ── VCVTB_SH/VCVTB_HS: conversão binary32<->binary16 ida e volta ────────────────────────────────

    @Test
    void cvtbShNarrowsSingleToHalfAtLaneZero() {
        ArmCore core = newCore();
        core.vfp().setQ(3, Float.floatToRawIntBits(1.5f) & 0xFFFF_FFFFL, 0L); // Qm word0 = 1.5f.
        // VCVTB_SH: U=0(_SH),field1916=1111,bit7=0,bit12=0(bottom),Qd=1,Qm=3.
        int r = raw(0, 0b11, 1, 0b1111, 0, 0, 3);
        put32(core, CODE_BASE, r);

        core.step();

        long half = core.vfp().element(1, 0, 1);
        assertEquals(1.5f, Float.float16ToFloat((short) half), 0.0f);
    }

    @Test
    void cvtbHsWidensHalfToSingleFromInterleavedSourceLane() {
        ArmCore core = newCore();
        long halfBits = Float.floatToFloat16(2.5f) & 0xFFFFL;
        core.vfp().setQ(3, halfBits, 0L); // halfword[0] (lane par, bottom) = 2.5f em binary16.
        // VCVTB_HS: U=1(_HS),field1916=1111,bit7=0,bit12=0(bottom),Qd=1,Qm=3.
        int r = raw(1, 0b11, 1, 0b1111, 0, 0, 3);
        put32(core, CODE_BASE, r);

        core.step();

        float wide = Float.intBitsToFloat((int) core.vfp().element(1, 0, 2));
        assertEquals(2.5f, wide, 0.0f);
    }

    // ── VMULH/VRMULH: alta ordem sem dobra nem saturação ────────────────────────────────────────────

    @Test
    void mulhComputesHighHalfOfSignedProductWithoutDoublingOrSaturating() {
        ArmCore core = newCore();
        core.vfp().setQ(2, 0x4000_0000L, 0L); // Qn word0 = 0x40000000 (2^30).
        core.vfp().setQ(3, 4L, 0L); // Qm word0 = 4.
        // VMULH_S: U=0,size=2(word),Qd=1,Qn=2,Qm=3,bit12=0(sem arredondamento).
        int r = rawMulh(0, 2, 1, 2, 0, 3);
        put32(core, CODE_BASE, r);

        core.step();

        // 0x40000000*4 = 0x100000000 (2^32); >>32 = 1. Sem dobra (diferente de SQDMULH).
        assertEquals(1L, core.vfp().element(1, 0, 2));
    }

    // ── Predicação: lane inativa preserva o Qd atual ────────────────────────────────────────────────

    @Test
    void inactiveLanePreservesCurrentDestinationUnderVpt() {
        ArmCore core = newCore();
        core.vfp().setQ(1, 0xAAAA_AAAAL, 0L); // Qd inicial: word0 = 0xAAAAAAAA (sentinela).
        core.vfp().setQ(3, 5L, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0); // TODAS as lanes desligadas.
        int r = raw(0, 0b11, 1, 0b1011, 0, 1, 3); // VMAXA, size=2.
        put32(core, CODE_BASE, r);

        core.step();

        assertFalse(core.fpscr().qc());
        assertEquals(0xAAAA_AAAAL, core.vfp().element(1, 0, 2), "lane mascarada preserva Qd, ignora Qm");
    }
}
