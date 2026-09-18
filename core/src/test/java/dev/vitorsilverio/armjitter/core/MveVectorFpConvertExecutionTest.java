package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B16.12 — `VCVT` (int↔fp, ponto fixo, modo de arredondamento) e `VRINT*`, fim-a-fim (decode + lift
/// + executor interpretado) sobre o preset real `ARMV8_1M_MVE`. Complementa
/// `Thumb2MveFpConvertDecoderTest` (só decode): aqui o foco é o RESULTADO numérico produzido pelo
/// núcleo compartilhado (`AdvSimdLanes#fpUnaryMasked`/`#convertFixedPointMasked`) — os achados de
/// arredondamento (achado 4 corrigido: `VCVT_FS` trunca SEMPRE, `VCVTA/N/P/M` usam o modo do
/// encoding), a contagem `N - shift` por largura, saturação fp→int e predicação por lane.
class MveVectorFpConvertExecutionTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;

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

    // ── Construção de raws (MESMO layout de Thumb2MveFpConvertDecoderTest) ────────────────────────

    private static int rawFixed(int u, boolean half, int rawShiftField, boolean toInt, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        int shiftField = half ? (0b1_0000 | (rawShiftField & 0xF)) : (rawShiftField & 0x1F);
        int bit9 = half ? 0 : 1;
        return (0b111 << 29) | (u << 28) | (0b1111 << 24) | (1 << 23) | (qdHigh << 22) | (1 << 21)
                | (shiftField << 16) | (qdLow << 13) | (0b11 << 10) | (bit9 << 9) | ((toInt ? 1 : 0) << 8)
                | (0b01 << 6) | (qmHigh << 5) | (1 << 4) | (qmLow << 1);
    }

    private static int rawOneOpPlain(int size, boolean toInt, boolean unsignedForm, int qd, int qm) {
        int bits12_7 = 0b001100 | ((toInt ? 1 : 0) << 1) | (unsignedForm ? 1 : 0);
        return rawOneOpBase(size, 0b11, bits12_7, qd, qm);
    }

    private static int rawOneOpRmode(int size, int rm, boolean unsignedForm, int qd, int qm) {
        int bits12_7 = (rm << 1) | (unsignedForm ? 1 : 0);
        return rawOneOpBase(size, 0b11, bits12_7, qd, qm);
    }

    private static int rawOneOpVrint(int size, int mode, int qd, int qm) {
        int bits12_7 = (0b001 << 3) | mode;
        return rawOneOpBase(size, 0b10, bits12_7, qd, qm);
    }

    private static int rawOneOpBase(int size, int bits17_16, int bits12_7, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b1111 << 28) | (0b1111 << 24) | (1 << 23) | (qdHigh << 22) | (0b11 << 20) | (size << 18)
                | (bits17_16 << 16) | (qdLow << 13) | (bits12_7 << 7) | (1 << 6) | (qmHigh << 5) | (qmLow << 1);
    }

    private static float single(ArmCore core, int q, int lane) {
        return Float.intBitsToFloat((int) core.vfp().element(q, lane, ESZ_SINGLE));
    }

    private static void setSingle(ArmCore core, int q, int lane, float value) {
        core.vfp().setElement(q, lane, ESZ_SINGLE, Float.floatToRawIntBits(value) & 0xFFFF_FFFFL);
    }

    private static void setInt(ArmCore core, int q, int lane, int value) {
        core.vfp().setElement(q, lane, ESZ_SINGLE, value & 0xFFFF_FFFFL);
    }

    private static int intAt(ArmCore core, int q, int lane) {
        return (int) core.vfp().element(q, lane, ESZ_SINGLE);
    }

    private static void setHalf(ArmCore core, int q, int lane, float value) {
        core.vfp().setElement(q, lane, ESZ_HALF, AdvSimdLanes.halfBits(value));
    }

    private static float halfAt(ArmCore core, int q, int lane) {
        return AdvSimdLanes.halfToFloat(core.vfp().element(q, lane, ESZ_HALF));
    }

    // ── VCVT_SF/VCVT_FS (@1op): round-trip int<->fp ────────────────────────────────────────────────

    @Test
    void vcvtSfConvertsSignedIntegerToFloat() {
        ArmCore core = newCore();
        setInt(core, 2, 0, -5);
        int r = rawOneOpPlain(ESZ_SINGLE, false, false, 1, 2); // VCVT_SF, Qd=1, Qm=2.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(-5.0f, single(core, 1, 0));
    }

    // ── Achado 4 corrigido: VCVT_FS SEMPRE trunca; VCVTNS arredonda ties-to-even ───────────────────

    @Test
    void vcvtFsAlwaysTruncatesTowardZero() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 3.9f);
        int r = rawOneOpPlain(ESZ_SINGLE, true, false, 1, 2); // VCVT_FS.
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(3, intAt(core, 1, 0), "trunca para zero, não arredonda");
    }

    @Test
    void vcvtnsRoundsTiesToEvenDistinctFromTruncation() {
        // 3.5 é o par que distingue truncamento (3) de ties-to-even (4, par mais próximo) — mesmo
        // operando nos dois lados, achado 4 da task.
        ArmCore truncCore = newCore();
        setSingle(truncCore, 2, 0, 3.5f);
        put32(truncCore, CODE_BASE, rawOneOpPlain(ESZ_SINGLE, true, false, 1, 2)); // VCVT_FS.
        truncCore.step();
        assertEquals(3, intAt(truncCore, 1, 0), "VCVT_FS trunca 3.5 -> 3");

        ArmCore nearestCore = newCore();
        setSingle(nearestCore, 2, 0, 3.5f);
        put32(nearestCore, CODE_BASE, rawOneOpRmode(ESZ_SINGLE, 0b01, false, 1, 2)); // VCVTNS.
        nearestCore.step();
        assertEquals(4, intAt(nearestCore, 1, 0), "VCVTNS arredonda 3.5 -> 4 (par mais próximo)");
    }

    // ── VCVTA/N/P/M distinguidos por 2.5 e -2.5 (Aceite da task) ────────────────────────────────────

    private static int convertWithRoundingMode(int rm, float value) {
        ArmCore core = newCore();
        setSingle(core, 2, 0, value);
        put32(core, CODE_BASE, rawOneOpRmode(ESZ_SINGLE, rm, false, 1, 2));
        core.step();
        return intAt(core, 1, 0);
    }

    @Test
    void vcvtaRoundsTiesAwayFromZero() {
        assertEquals(3, convertWithRoundingMode(0b00, 2.5f));
        assertEquals(-3, convertWithRoundingMode(0b00, -2.5f));
    }

    @Test
    void vcvtnRoundsTiesToEven() {
        assertEquals(2, convertWithRoundingMode(0b01, 2.5f));
        assertEquals(-2, convertWithRoundingMode(0b01, -2.5f));
    }

    @Test
    void vcvtpRoundsTowardPositiveInfinity() {
        assertEquals(3, convertWithRoundingMode(0b10, 2.5f));
        assertEquals(-2, convertWithRoundingMode(0b10, -2.5f));
    }

    @Test
    void vcvtmRoundsTowardNegativeInfinity() {
        assertEquals(2, convertWithRoundingMode(0b11, 2.5f));
        assertEquals(-3, convertWithRoundingMode(0b11, -2.5f));
    }

    // ── VRINT*: mesmas 4 direções, resultado em PONTO FLUTUANTE (não inteiro) ──────────────────────

    @Test
    void vrintaRoundsToFloatTiesAway() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 2.5f);
        put32(core, CODE_BASE, rawOneOpVrint(ESZ_SINGLE, 0b010, 1, 2)); // VRINTA.
        core.step();
        assertEquals(3.0f, single(core, 1, 0));
    }

    @Test
    void vrintzTruncatesToFloat() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, -2.9f);
        put32(core, CODE_BASE, rawOneOpVrint(ESZ_SINGLE, 0b011, 1, 2)); // VRINTZ.
        core.step();
        assertEquals(-2.0f, single(core, 1, 0));
    }

    // ── Saturação fp->int (Aceite da task) ──────────────────────────────────────────────────────────

    @Test
    void vcvtFsSaturatesOutOfRangeValueToIntMax() {
        ArmCore core = newCore();
        setSingle(core, 2, 0, 1.0e10f);
        put32(core, CODE_BASE, rawOneOpPlain(ESZ_SINGLE, true, false, 1, 2)); // VCVT_FS, assinado.
        core.step();
        assertEquals(Integer.MAX_VALUE, intAt(core, 1, 0));
    }

    // ── Ponto fixo (@vcvt/@vcvt_f16): N - shift por LARGURA (Aceite: 16 e 32) ─────────────────────

    @Test
    void fixedPointFractionBitsDependsOnWidthForSameRawShift() {
        // rawShiftField=12 nos dois lados: single -> fractionBits=32-12=20; half -> fractionBits=16-12=4.
        // Escala 2^4=16 é exata em ambos, então o valor de saída expõe a largura usada.
        ArmCore singleCore = newCore();
        setInt(singleCore, 2, 0, 1 << 20); // 2^20, escalado por 2^-20 -> 1.0.
        put32(singleCore, CODE_BASE, rawFixed(0, false, 12, false, 1, 2)); // VCVT_SF_fixed.
        singleCore.step();
        assertEquals(1.0f, single(singleCore, 1, 0), "single: fractionBits = 32 - 12 = 20");

        ArmCore halfCore = newCore();
        halfCore.vfp().setElement(2, 0, ESZ_HALF, 16); // inteiro 16 na lane half.
        put32(halfCore, CODE_BASE, rawFixed(0, true, 12, false, 1, 2)); // VCVT_SH_fixed.
        halfCore.step();
        assertEquals(1.0f, halfAt(halfCore, 1, 0), "half: fractionBits = 16 - 12 = 4 -> 16/2^4 = 1.0");
    }

    @Test
    void fixedPointToIntDirectionAlwaysTruncates() {
        ArmCore core = newCore();
        // VCVT_FS_fixed: fractionBits=32-28=4; 1.9375*16=31 -> trunca para 31 (não haveria diferença
        // para arredondamento aqui, então usa-se um valor cuja parte fracionária de fato trunca).
        setSingle(core, 2, 0, 1.999f);
        put32(core, CODE_BASE, rawFixed(0, false, 28, true, 1, 2)); // VCVT_FS_fixed, fractionBits=4.
        core.step();
        assertEquals(31, intAt(core, 1, 0), "1.999 * 16 = 31.984 -> trunca para 31");
    }

    // ── Predicação por lane (mesmo gancho de MveVectorFpTwoOpExecutionTest) ────────────────────────

    @Test
    void plainConvertPredicationPreservesInactiveLane() {
        ArmCore core = newCore();
        setInt(core, 2, 0, 7);
        setInt(core, 2, 1, 70);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só a lane 0 ativa.
        put32(core, CODE_BASE, rawOneOpPlain(ESZ_SINGLE, false, false, 1, 2)); // VCVT_SF.

        core.step();

        assertEquals(7.0f, single(core, 1, 0), "lane 0 ativa");
        assertEquals(0xFFFF_FFFFL, core.vfp().element(1, 1, ESZ_SINGLE), "lane 1 mascarada: preserva");
    }

    @Test
    void fixedPointConvertPredicationPreservesInactiveLane() {
        ArmCore core = newCore();
        setInt(core, 2, 0, 1 << 4);
        setInt(core, 2, 1, 1 << 4);
        core.vfp().setQ(1, 0xFFFF_FFFF_FFFF_FFFFL, 0L);
        core.vpr().setMask01(1);
        core.vpr().setMask23(1);
        core.vpr().setP0(0x000F); // só a lane 0 ativa.
        put32(core, CODE_BASE, rawFixed(0, false, 28, false, 1, 2)); // VCVT_SF_fixed, fractionBits=4.

        core.step();

        assertEquals(1.0f, single(core, 1, 0), "lane 0 ativa: 16 / 2^4 = 1.0");
        assertEquals(0xFFFF_FFFFL, core.vfp().element(1, 1, ESZ_SINGLE), "lane 1 mascarada: preserva");
    }
}
