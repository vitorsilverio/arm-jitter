package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdShiftImmediateOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.10 — `Thumb2MveShiftImmediateDecoder`: deslocamentos por imediato, shift-and-insert e `VSHLL`
/// T1 (`target/isa-decode/mve.decode`, linhas 601-655, 38 encodings). Raws construídos bit a bit com
/// o MESMO layout do Javadoc da classe.
class Thumb2MveShiftImmediateDecoderTest {
    private static final int PREFIX_BYTE = 0b001;
    private static final int PREFIX_HALFWORD = 0b01;

    /// Família `VSHLI`/`VQSHLI_S`/`VQSHLI_U`/`VQSHLUI`/`VSHRI_S`/`VSHRI_U`/`VRSHRI_S`/`VRSHRI_U`/
    /// `VSRI`/`VSLI` (`bits[27:24]=1111`). `prefix6` = `bits[21:16]` já combinados (prefixo de
    /// largura + valor cru do campo imediato).
    private static int rawShift(int u, int nibble, int prefix6, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        return (0b111 << 29) | (u << 28) | (0b1111 << 24) | (1 << 23) | (qdHigh << 22) | (prefix6 << 16)
                | (qdLow << 13) | (nibble << 8) | (1 << 6) | (qmHigh << 5) | (1 << 4) | (qmLow << 1);
    }

    private static int prefixByte(int shift3) {
        return (PREFIX_BYTE << 3) | (shift3 & 0x7);
    }

    private static int prefixHalfword(int shift4) {
        return (PREFIX_HALFWORD << 4) | (shift4 & 0xF);
    }

    private static int prefixWord(int shift5) {
        return (1 << 5) | (shift5 & 0x1F);
    }

    /// Família `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` T1 (`bits[27:24]=1110`, `bit21=1` fixo).
    private static int rawVshll(int u, int top, int esz, int shift, int qd, int qm) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        int qmHigh = (qm >>> 3) & 1;
        int qmLow = qm & 0x7;
        int widthAndShift = esz == 0 ? (0b01 << 19) | ((shift & 0x7) << 16) : (1 << 20) | ((shift & 0xF) << 16);
        return (0b111 << 29) | (u << 28) | (0b1110 << 24) | (1 << 23) | (qdHigh << 22) | (1 << 21) | widthAndShift
                | (qdLow << 13) | (top << 12) | (0b1111 << 8) | (1 << 6) | (qmHigh << 5) | (qmLow << 1);
    }

    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveShiftImmediateDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    private static IrOp.MveVectorShiftImmediate decodeShift(int raw) {
        return assertInstanceOf(IrOp.MveVectorShiftImmediate.class, tryDecode(raw).liftedOp());
    }

    private static IrOp.MveVectorShiftWidenImmediateInterleaved decodeVshll(int raw) {
        return assertInstanceOf(IrOp.MveVectorShiftWidenImmediateInterleaved.class, tryDecode(raw).liftedOp());
    }

    // ── Achado 1: N - shift nas formas _shr/VSRI ────────────────────────────────────────────────

    @Test
    void vshriHalfwordFieldOneMeansShiftFifteen() {
        // VSHRI_S, halfword, campo cru = 1 -> shift = 16 - 1 = 15.
        IrOp.MveVectorShiftImmediate op = decodeShift(rawShift(0, 0b0000, prefixHalfword(1), 0, 1));
        assertEquals(1, op.esz());
        assertEquals(15, op.shift());
    }

    @Test
    void vshriByteFieldZeroMeansShiftEight() {
        IrOp.MveVectorShiftImmediate op = decodeShift(rawShift(0, 0b0000, prefixByte(0), 0, 1));
        assertEquals(0, op.esz());
        assertEquals(8, op.shift());
    }

    @Test
    void vrshriWordUsesNShiftToo() {
        // word, campo cru = 5 -> shift = 32 - 5 = 27.
        IrOp.MveVectorShiftImmediate op = decodeShift(rawShift(1, 0b0010, prefixWord(5), 0, 1));
        assertEquals(2, op.esz());
        assertEquals(27, op.shift());
        assertEquals(AdvSimdShiftImmediateOp.URSHR, op.op());
    }

    @Test
    void vshliUsesRawShiftDirectly() {
        IrOp.MveVectorShiftImmediate op = decodeShift(rawShift(0, 0b0101, prefixByte(3), 0, 1));
        assertEquals(3, op.shift());
        assertEquals(AdvSimdShiftImmediateOp.SHL, op.op());
    }

    // ── Achado 3: largura por PREFIXO, não campo size ───────────────────────────────────────────

    @Test
    void widthComesFromPrefixNotASizeField() {
        assertEquals(0, decodeShift(rawShift(0, 0b0101, prefixByte(2), 0, 1)).esz());
        assertEquals(1, decodeShift(rawShift(0, 0b0101, prefixHalfword(2), 0, 1)).esz());
        assertEquals(2, decodeShift(rawShift(0, 0b0101, prefixWord(2), 0, 1)).esz());
    }

    @Test
    void reservedPrefixBits21_19ZeroIsRejected() {
        // bits[21:19] = 000 -> mesmo frame de Vimm_1r (B16.13); devolve null, não unimplemented.
        assertNull(tryDecode(rawShift(0, 0b0101, 0, 0, 1)));
    }

    // ── Opcode nibble → AdvSimdShiftImmediateOp (reuso de NeonShiftImmediateDecoder) ────────────

    @Test
    void decodesAllTenOpsByNibbleAndU() {
        assertEquals(AdvSimdShiftImmediateOp.SHL, decodeShift(rawShift(0, 0b0101, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.SLI, decodeShift(rawShift(1, 0b0101, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.SQSHL, decodeShift(rawShift(0, 0b0111, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.UQSHL, decodeShift(rawShift(1, 0b0111, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.SQSHLU, decodeShift(rawShift(1, 0b0110, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.SSHR, decodeShift(rawShift(0, 0b0000, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.USHR, decodeShift(rawShift(1, 0b0000, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.SRSHR, decodeShift(rawShift(0, 0b0010, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.URSHR, decodeShift(rawShift(1, 0b0010, prefixByte(1), 0, 1)).op());
        assertEquals(AdvSimdShiftImmediateOp.SRI, decodeShift(rawShift(1, 0b0100, prefixByte(1), 0, 1)).op());
    }

    @Test
    void sqshluDoesNotExistWithUZero() {
        assertNull(tryDecode(rawShift(0, 0b0110, prefixByte(1), 0, 1)));
    }

    @Test
    void sriDoesNotExistWithUZero() {
        assertNull(tryDecode(rawShift(0, 0b0100, prefixByte(1), 0, 1)));
    }

    @Test
    void nibbleSsraAndSrsraDoNotExistInMveEvenThoughNeonHasThem() {
        // opc=0001/0011 mapeiam SSRA/USRA/SRSRA/URSRA na tabela reusada de
        // NeonShiftImmediateDecoder (NEON A32) mas não existem nesta família MVE — confirmado
        // contra o mve.decode real: nenhuma linha usa esse nibble com bit4=1 neste frame.
        assertNull(tryDecode(rawShift(0, 0b0001, prefixByte(1), 0, 1)));
        assertNull(tryDecode(rawShift(1, 0b0001, prefixByte(1), 0, 1)));
        assertNull(tryDecode(rawShift(0, 0b0011, prefixByte(1), 0, 1)));
        assertNull(tryDecode(rawShift(1, 0b0011, prefixByte(1), 0, 1)));
    }

    // ── VSHLL T1 / VMOVL ─────────────────────────────────────────────────────────────────────────

    @Test
    void decodesVshllBottomSignedAndUnsigned() {
        IrOp.MveVectorShiftWidenImmediateInterleaved s = decodeVshll(rawVshll(0, 0, 0, 3, 0, 1));
        assertEquals(true, s.signed());
        assertEquals(false, s.top());
        assertEquals(0, s.esz());
        assertEquals(3, s.shift());
        IrOp.MveVectorShiftWidenImmediateInterleaved u = decodeVshll(rawVshll(1, 1, 1, 5, 2, 3));
        assertEquals(false, u.signed());
        assertEquals(true, u.top());
        assertEquals(1, u.esz());
        assertEquals(5, u.shift());
    }

    @Test
    void vmovlIsVshllWithZeroShift() {
        // VMOVL não tem Kind próprio: é VSHLL_BS/BU com shift=0.
        IrOp.MveVectorShiftWidenImmediateInterleaved op = decodeVshll(rawVshll(0, 0, 0, 0, 0, 1));
        assertEquals(0, op.shift());
    }

    @Test
    void vshllQdEqualsQmIsNotUndef() {
        // Achado: do_2shift_vec real só checa mve_check_qreg_bank(qd|qm), nunca qd != qm.
        assertInstanceOf(IrOp.MveVectorShiftWidenImmediateInterleaved.class,
                tryDecode(rawVshll(0, 0, 0, 3, 4, 4)).liftedOp());
    }

    // ── G8: não engolir o espaço de B16.11 (narrowing shifts) nem B16.12 (VCVT_*_fixed) ─────────

    @Test
    void doesNotClaimNarrowingShiftSpaceBit7Bit0() {
        // Mesmo bits[27:24]=1110/bit21=1 de VSHLL, mas bit7=1/bit0=1 (VSHRNB real): B16.11, ainda
        // não implementada — tem que continuar null, não virar VSHLL por engano.
        int raw = rawVshll(0, 0, 0, 3, 0, 1) | (1 << 7) | 1;
        assertNull(tryDecode(raw));
    }

    @Test
    void vshllRejectsBit7AloneAndBit0Alone() {
        int base = rawVshll(0, 0, 0, 3, 0, 1);
        assertNull(tryDecode(base | (1 << 7)));
        assertNull(tryDecode(base | 1));
    }

    @Test
    void vshllRejectsBit21Zero() {
        // bit21=0: mesmo frame das narrowing shifts (@2_shr_b/h usam esse prefixo livre) — VSHLL
        // exige bit21=1 literal.
        int raw = rawVshll(0, 0, 0, 3, 0, 1) & ~(1 << 21);
        assertNull(tryDecode(raw));
    }

    @Test
    void vshllRejectsNonFifteenNibble() {
        int raw = (rawVshll(0, 0, 0, 3, 0, 1) & ~(0b1111 << 8)) | (0b1110 << 8);
        assertNull(tryDecode(raw));
    }

    @Test
    void vshllRejectsBit6ZeroAndBit4One() {
        int base = rawVshll(0, 0, 0, 3, 0, 1);
        assertNull(tryDecode(base & ~(1 << 6)));
        assertNull(tryDecode(base | (1 << 4)));
    }

    @Test
    void vshllRejectsReservedWidthPrefix() {
        // bits[20:19] = 00: nem byte (precisa bit19=1) nem halfword (precisa bit20=1) — não existe
        // forma "w" para VSHLL.
        int raw = rawVshll(0, 0, 0, 3, 0, 1) & ~(0b11 << 19);
        assertNull(tryDecode(raw));
    }

    @Test
    void rejectsQdGreaterThanSevenOnVshll() {
        assertNull(tryDecode(rawVshll(0, 0, 0, 3, 8, 1)));
    }

    @Test
    void rejectsQmGreaterThanSevenOnVshll() {
        assertNull(tryDecode(rawVshll(0, 0, 0, 3, 0, 9)));
    }

    @Test
    void doesNotClaimVcvtFixedNibbleSpace() {
        // nibble=11xx (VCVT_*_fixed, B16.12) nunca bate nos 6 valores conhecidos.
        assertNull(tryDecode(rawShift(0, 0b1100, prefixByte(1), 0, 1)));
    }

    // ── Q > 7 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void rejectsQdGreaterThanSeven() {
        assertNull(tryDecode(rawShift(0, 0b0101, prefixByte(1), 8, 1)));
    }

    @Test
    void rejectsQmGreaterThanSeven() {
        assertNull(tryDecode(rawShift(0, 0b0101, prefixByte(1), 0, 9)));
    }

    // ── Pipeline completo: nunca vira NOCP ──────────────────────────────────────────────────────

    @Test
    void fullPipelineNeverDecodesAsNocp() {
        int r = rawShift(0, 0b0101, prefixByte(1), 0, 1);
        dev.vitorsilverio.armjitter.support.TestAddressSpace memory =
                new dev.vitorsilverio.armjitter.support.TestAddressSpace(16);
        memory.put16(0, r >>> 16);
        memory.put16(2, r & 0xFFFF);
        DecodedInstruction decoded = new ThumbDecoder(ArmArchitecture.ARMV8_1M_MVE).decode(memory, 0);
        assertInstanceOf(IrOp.MveVectorShiftImmediate.class, decoded.liftedOp());
    }
}
