package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdFpUnaryOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.12 — `Thumb2MveFpConvertDecoder`: `VCVT` (int↔fp, ponto fixo, modo de arredondamento) e
/// `VRINT*`, `target/isa-decode/mve.decode`, linhas 791-832 (26 encodings). Raws construídos bit a
/// bit com o MESMO layout do Javadoc da classe; cobertura EXAUSTIVA das 26 linhas (8 `_fixed` + 18
/// `@1op`), não amostragem.
class Thumb2MveFpConvertDecoderTest {
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;

    // ── Construção de raws ──────────────────────────────────────────────────────────────────────

    /// As 8 linhas `@vcvt`/`@vcvt_f16`. `rawShiftField` é o valor CRU do campo (não `N - raw`).
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

    /// `VCVT_SF`/`VCVT_UF`/`VCVT_FS`/`VCVT_FU` (`@1op`, `bits[12:9]=0011`).
    private static int rawOneOpPlain(int size, boolean toInt, boolean unsignedForm, int qd, int qm) {
        int bits12_7 = 0b001100 | ((toInt ? 1 : 0) << 1) | (unsignedForm ? 1 : 0);
        return rawOneOpBase(size, 0b11, bits12_7, qd, qm);
    }

    /// `VCVTA/N/P/M{S,U}` (`@1op`, `bits[12:10]=000`, `rm`=`bits[9:8]`, `u`=`bit7`).
    private static int rawOneOpRmode(int size, int rm, boolean unsignedForm, int qd, int qm) {
        int bits12_7 = (rm << 1) | (unsignedForm ? 1 : 0);
        return rawOneOpBase(size, 0b11, bits12_7, qd, qm);
    }

    /// `VRINTN/X/A/Z/M/P` (`@1op`, `bits[12:10]=001`, `mode`=`bits[9:7]`).
    private static int rawOneOpVrint(int size, int mode, int qd, int qm) {
        // bits[12:10]=001, bits[9:7]=mode -> combinado em 6 bits (bit12..bit7).
        int bits12_7 = (0b001 << 3) | mode;
        return rawOneOpBase(size, 0b10, bits12_7, qd, qm);
    }

    /// Base comum às 18 linhas `@1op`: `bits[12:7]` já combinado pelo chamador (6 bits, MSB=bit12).
    private static int rawOneOpBase(int size, int bits17_16, int bits12_7, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b1111 << 28) | (0b1111 << 24) | (1 << 23) | (qdHigh << 22) | (0b11 << 20) | (size << 18)
                | (bits17_16 << 16) | (qdLow << 13) | (bits12_7 << 7) | (1 << 6) | (qmHigh << 5) | (qmLow << 1);
    }

    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveFpConvertDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    private static IrOp.MveVectorFpConvertFixed decodeFixed(int raw) {
        return assertInstanceOf(IrOp.MveVectorFpConvertFixed.class, tryDecode(raw).liftedOp());
    }

    private static IrOp.MveVectorFpConvert decodeOneOp(int raw) {
        return assertInstanceOf(IrOp.MveVectorFpConvert.class, tryDecode(raw).liftedOp());
    }

    // ── As 8 linhas `@vcvt`/`@vcvt_f16` (ponto fixo↔ponto flutuante) ──────────────────────────────

    private record FixedCase(String name, int u, boolean half, boolean toInt, boolean signed) {
    }

    private static final List<FixedCase> FIXED_CASES = List.of(
            new FixedCase("VCVT_SH_fixed", 0, true, false, true),
            new FixedCase("VCVT_UH_fixed", 1, true, false, false),
            new FixedCase("VCVT_HS_fixed", 0, true, true, true),
            new FixedCase("VCVT_HU_fixed", 1, true, true, false),
            new FixedCase("VCVT_SF_fixed", 0, false, false, true),
            new FixedCase("VCVT_UF_fixed", 1, false, false, false),
            new FixedCase("VCVT_FS_fixed", 0, false, true, true),
            new FixedCase("VCVT_FU_fixed", 1, false, true, false));

    @Test
    void decodesAllEightFixedPointEncodings() {
        for (FixedCase fc : FIXED_CASES) {
            int rawShift = fc.half ? 5 : 9;
            int raw = rawFixed(fc.u, fc.half, rawShift, fc.toInt, 2, 5);
            IrOp.MveVectorFpConvertFixed op = decodeFixed(raw);
            assertEquals(!fc.toInt, op.toFloat(), fc.name);
            assertEquals(fc.signed, op.signed(), fc.name);
            assertEquals(fc.half ? ESZ_HALF : ESZ_SINGLE, op.esz(), fc.name);
            int width = fc.half ? 16 : 32;
            assertEquals(width - rawShift, op.fractionBits(), fc.name);
            assertEquals(2, op.qd());
            assertEquals(5, op.qm());
        }
    }

    @Test
    void halfFractionBitsUsesWidthSixteen() {
        IrOp.MveVectorFpConvertFixed op = decodeFixed(rawFixed(0, true, 0, false, 0, 1));
        assertEquals(16, op.fractionBits()); // raw==0 -> N-0 -> 16 (desloca ao máximo, válido).
    }

    @Test
    void singleFractionBitsUsesWidthThirtyTwo() {
        IrOp.MveVectorFpConvertFixed op = decodeFixed(rawFixed(0, false, 0, false, 0, 1));
        assertEquals(32, op.fractionBits());
    }

    @Test
    void rejectsFixedPointQdOrQmGreaterThanSeven() {
        assertNull(tryDecode(rawFixed(0, true, 1, false, 8, 1)));
        assertNull(tryDecode(rawFixed(0, true, 1, false, 0, 9)));
    }

    // ── As 18 linhas `@1op`: VCVT simples (4) ──────────────────────────────────────────────────────

    @Test
    void decodesFourPlainVcvtEncodings() {
        assertEquals(AdvSimdFpUnaryOp.SCVTF, decodeOneOp(rawOneOpPlain(ESZ_SINGLE, false, false, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.UCVTF, decodeOneOp(rawOneOpPlain(ESZ_SINGLE, false, true, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTZS, decodeOneOp(rawOneOpPlain(ESZ_SINGLE, true, false, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTZU, decodeOneOp(rawOneOpPlain(ESZ_SINGLE, true, true, 2, 5)).op());
    }

    @Test
    void plainVcvtWorksForHalfPrecisionToo() {
        IrOp.MveVectorFpConvert op = decodeOneOp(rawOneOpPlain(ESZ_HALF, false, false, 2, 5));
        assertEquals(AdvSimdFpUnaryOp.SCVTF, op.op());
        assertEquals(ESZ_HALF, op.esz());
        assertEquals(2, op.qd());
        assertEquals(5, op.qm());
    }

    // ── As 18 linhas `@1op`: VCVT com modo de arredondamento explícito (8) ─────────────────────────

    @Test
    void decodesAllEightRoundingModeEncodings() {
        assertEquals(AdvSimdFpUnaryOp.FCVTAS, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b00, false, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTAU, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b00, true, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTNS, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b01, false, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTNU, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b01, true, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTPS, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b10, false, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTPU, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b10, true, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTMS, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b11, false, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.FCVTMU, decodeOneOp(rawOneOpRmode(ESZ_SINGLE, 0b11, true, 2, 5)).op());
    }

    // ── As 18 linhas `@1op`: VRINT* (6) ─────────────────────────────────────────────────────────────

    @Test
    void decodesAllSixVrintEncodings() {
        assertEquals(AdvSimdFpUnaryOp.RINTN, decodeOneOp(rawOneOpVrint(ESZ_SINGLE, 0b000, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.RINTX, decodeOneOp(rawOneOpVrint(ESZ_SINGLE, 0b001, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.RINTA, decodeOneOp(rawOneOpVrint(ESZ_SINGLE, 0b010, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.RINTZ, decodeOneOp(rawOneOpVrint(ESZ_SINGLE, 0b011, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.RINTM, decodeOneOp(rawOneOpVrint(ESZ_SINGLE, 0b101, 2, 5)).op());
        assertEquals(AdvSimdFpUnaryOp.RINTP, decodeOneOp(rawOneOpVrint(ESZ_SINGLE, 0b111, 2, 5)).op());
    }

    @Test
    void rejectsReservedVrintModes() {
        assertNull(tryDecode(rawOneOpVrint(ESZ_SINGLE, 0b100, 2, 5)));
        assertNull(tryDecode(rawOneOpVrint(ESZ_SINGLE, 0b110, 2, 5)));
    }

    // ── G8: size/Q>7 ─────────────────────────────────────────────────────────────────────────────

    @Test
    void rejectsSizeZeroAndThree() {
        assertNull(tryDecode(rawOneOpPlain(0, false, false, 2, 5)));
        assertNull(tryDecode(rawOneOpPlain(3, false, false, 2, 5)));
    }

    @Test
    void rejectsOneOpQdOrQmGreaterThanSeven() {
        assertNull(tryDecode(rawOneOpPlain(ESZ_SINGLE, false, false, 8, 5)));
        assertNull(tryDecode(rawOneOpPlain(ESZ_SINGLE, false, false, 2, 9)));
    }

    // ── Colisão com Thumb2MveShiftImmediateDecoder (Armadilha 2 da task) ────────────────────────────

    @Test
    void bit4DisambiguatesFromShiftImmediateFamily() {
        // Mesma "territorialidade" bits[31:23]/bits[11:10] de VQSHLUI/VSHLI (B16.10) — bit4=1 lá,
        // bit4=0 aqui; um raw desta família nunca decodifica como B16.10 e vice-versa.
        int raw = rawOneOpPlain(ESZ_SINGLE, false, false, 2, 5);
        assertEquals(0, (raw >>> 4) & 1);
        assertNull(new Thumb2MveShiftImmediateDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL));
    }

    // ── Gate: MVE_FLOAT ausente ──────────────────────────────────────────────────────────────────

    @Test
    void rejectsWithoutMveFloat() {
        int raw = rawOneOpPlain(ESZ_SINGLE, false, false, 2, 5);
        assertNull(new Thumb2MveFpConvertDecoder(ArmArchitecture.ARMV7A).tryDecode(raw, 0, Condition.AL));
    }

    // ── Pipeline completo: nunca vira NOCP ──────────────────────────────────────────────────────

    @Test
    void fullPipelineNeverDecodesAsNocpForFixedPoint() {
        int r = rawFixed(0, true, 1, false, 2, 5);
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertInstanceOf(IrOp.MveVectorFpConvertFixed.class, decoded.liftedOp());
    }

    @Test
    void fullPipelineNeverDecodesAsNocpForOneOp() {
        int r = rawOneOpPlain(ESZ_SINGLE, false, false, 2, 5);
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertInstanceOf(IrOp.MveVectorFpConvert.class, decoded.liftedOp());
    }
}
