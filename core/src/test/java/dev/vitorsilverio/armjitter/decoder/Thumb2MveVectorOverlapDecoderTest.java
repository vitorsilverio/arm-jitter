package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.7 (sub-família 1) — `Thumb2MveVectorOverlapDecoder`: os quatro blocos `{}` sobrepostos
/// (`target/isa-decode/mve.decode`, linhas 223-279, 30 encodings). Raws construídos bit a bit com o
/// MESMO layout do Javadoc da classe/`Thumb2MveVector2opDecoderTest` (B16.6): `bits[31:29]=111`,
/// `bit28=U`, `bits[27:24]=1110`, `bit23=0`, `Qd`(22,15:13), `bits[21:20]`, `bits[19:16]` (ou `Qn`
/// para o catch-all `VMULH`), `bit12`, nibble(11:8)=1110, `bit7`, `bit6=0`, `Qm`(5,3:1), `bit4=0`,
/// `bit0=1`.
class Thumb2MveVectorOverlapDecoderTest {
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

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveVectorOverlapDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── bits[19:16]=1111: VCVTB/T_SH/HS (bit7=0) / VMAXNMA,VMINNMA (bit7=1) ────────────────────────

    @Test
    void decodesCvtPrecisionFourForms() {
        // U=0,bit12=0 -> VCVTB_SH (estreita, top=false).
        IrOp.MveVectorFpConvertPrecision b = decodeCvt(0, 0);
        assertFalse(b.widen());
        assertFalse(b.top());
        // U=0,bit12=1 -> VCVTT_SH (estreita, top=true).
        IrOp.MveVectorFpConvertPrecision t = decodeCvt(0, 1);
        assertFalse(t.widen());
        assertTrue(t.top());
        // U=1,bit12=0 -> VCVTB_HS (alarga, top=false).
        IrOp.MveVectorFpConvertPrecision bh = decodeCvt(1, 0);
        assertTrue(bh.widen());
        assertFalse(bh.top());
        // U=1,bit12=1 -> VCVTT_HS (alarga, top=true).
        IrOp.MveVectorFpConvertPrecision th = decodeCvt(1, 1);
        assertTrue(th.widen());
        assertTrue(th.top());
    }

    private static IrOp.MveVectorFpConvertPrecision decodeCvt(int u, int bit12) {
        int r = raw(u, 0b11, 1, 0b1111, bit12, 0, 2);
        return assertInstanceOf(IrOp.MveVectorFpConvertPrecision.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
    }

    @Test
    void cvtPrecisionRequiresMveFloat() {
        int r = raw(0, 0b11, 1, 0b1111, 0, 0, 2);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r), "ARMV8_1M sem MVE_FLOAT nem decodifica");
    }

    @Test
    void decodesMaxNmaMinNmaWithPrecisionFromU() {
        // U=0,bit12=0 -> VMAXNMA, esz=2 (binary32).
        IrOp.MveVectorFpAbsAccumulate maxSingle = decodeMaxMinNma(0, 0);
        assertTrue(maxSingle.max());
        assertEquals(2, maxSingle.esz());
        // U=1,bit12=0 -> VMAXNMA, esz=1 (binary16).
        IrOp.MveVectorFpAbsAccumulate maxHalf = decodeMaxMinNma(1, 0);
        assertTrue(maxHalf.max());
        assertEquals(1, maxHalf.esz());
        // U=0,bit12=1 -> VMINNMA, esz=2.
        IrOp.MveVectorFpAbsAccumulate minSingle = decodeMaxMinNma(0, 1);
        assertFalse(minSingle.max());
        assertEquals(2, minSingle.esz());
    }

    private static IrOp.MveVectorFpAbsAccumulate decodeMaxMinNma(int u, int bit12) {
        int r = raw(u, 0b11, 3, 0b1111, bit12, 1, 4);
        IrOp.MveVectorFpAbsAccumulate op = assertInstanceOf(IrOp.MveVectorFpAbsAccumulate.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(3, op.qd());
        assertEquals(4, op.qm());
        return op;
    }

    // ── bits[17:16]=01: VSHLL T2 (bit7=0) / VQMOVUNB,T (U=0,bit7=1) / VMOVNB,T (U=1,bit7=1) ────────

    @Test
    void decodesShllT2ByteAndHalfword() {
        // U=0(signed),bits[19:18]=00(byte,shift=8),bit12=0(bottom).
        int rb = raw(0, 0b11, 0, 0b0001, 0, 0, 1);
        IrOp.MveVectorShiftWidenInterleaved b = assertInstanceOf(IrOp.MveVectorShiftWidenInterleaved.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rb).liftedOp());
        assertTrue(b.signed());
        assertEquals(0, b.esz());
        assertFalse(b.top());

        // U=1(unsigned),bits[19:18]=01(halfword,shift=16),bit12=1(top).
        int rt = raw(1, 0b11, 0, 0b0101, 1, 0, 1);
        IrOp.MveVectorShiftWidenInterleaved t = assertInstanceOf(IrOp.MveVectorShiftWidenInterleaved.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rt).liftedOp());
        assertFalse(t.signed());
        assertEquals(1, t.esz());
        assertTrue(t.top());
    }

    @Test
    void shllT2RejectsUnallocatedSize() {
        // bits[19:18]=10 (esz=2): não alocado na forma T2 (só byte/halfword de fonte).
        int r = raw(0, 0b11, 0, 0b1001, 0, 0, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void decodesQmovunSignedAndMovnUnsignedAtSameSlot() {
        // U=0,bit7=1 -> VQMOVUNB (SQXTUN).
        int rqmovun = raw(0, 0b11, 0, 0b0001, 0, 1, 1);
        IrOp.MveVectorNarrowInterleaved qmovun = assertInstanceOf(IrOp.MveVectorNarrowInterleaved.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rqmovun).liftedOp());
        assertEquals(AdvSimdNarrowUnaryOp.SQXTUN, qmovun.op());

        // U=1,bit7=1 -> VMOVNB (XTN, plain, sem saturação) — achado real: NÃO é "VQMOVUN unsigned".
        int rmovn = raw(1, 0b11, 0, 0b0001, 0, 1, 1);
        IrOp.MveVectorNarrowInterleaved movn = assertInstanceOf(IrOp.MveVectorNarrowInterleaved.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rmovn).liftedOp());
        assertEquals(AdvSimdNarrowUnaryOp.XTN, movn.op());
    }

    // ── bits[17:16]=11: VQMOVN_* (bit7=0) / VMAXA,VMINA (U=0,bit7=1 apenas) ─────────────────────────

    @Test
    void decodesQmovnSignedAndUnsigned() {
        int rs = raw(0, 0b11, 0, 0b0011, 0, 0, 1);
        assertEquals(AdvSimdNarrowUnaryOp.SQXTN,
                ((IrOp.MveVectorNarrowInterleaved) tryDecode(ArmArchitecture.ARMV8_1M_MVE, rs).liftedOp()).op());
        int ru = raw(1, 0b11, 0, 0b0011, 0, 0, 1);
        assertEquals(AdvSimdNarrowUnaryOp.UQXTN,
                ((IrOp.MveVectorNarrowInterleaved) tryDecode(ArmArchitecture.ARMV8_1M_MVE, ru).liftedOp()).op());
    }

    @Test
    void decodesMaxAMinAOnlyForUnsigned0() {
        // U=0,bit12=0,bit7=1 -> VMAXA.
        int rmax = raw(0, 0b11, 2, 0b0011, 0, 1, 3);
        IrOp.MveVectorAbsAccumulate max = assertInstanceOf(IrOp.MveVectorAbsAccumulate.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rmax).liftedOp());
        assertTrue(max.max());
        assertEquals(2, max.qd());
        assertEquals(3, max.qm());

        // U=0,bit12=1,bit7=1 -> VMINA.
        int rmin = raw(0, 0b11, 2, 0b0011, 1, 1, 3);
        IrOp.MveVectorAbsAccumulate min = assertInstanceOf(IrOp.MveVectorAbsAccumulate.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, rmin).liftedOp());
        assertFalse(min.max());
    }

    @Test
    void rejectsMaxAWithUnsignedBit() {
        // Achado real: VMAXA/VMINA NÃO têm forma "U=1" (Qm já é sempre assinado por construção) —
        // este slot fica não-alocado, não vira UQXTA inventado.
        int r = raw(1, 0b11, 2, 0b0011, 0, 1, 3);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    // ── bits[17:16] ∈ {00,10} com bits[21:20]="11": não alocado ─────────────────────────────────────

    @Test
    void rejectsUnallocatedSlotsAtReservedSizeField() {
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b11, 0, 0b0000, 0, 0, 0)));
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, raw(0, 0b11, 0, 0b1010, 0, 1, 0)));
    }

    // ── catch-all VMULH/VRMULH (bits[21:20] != 11, bit16=1) ─────────────────────────────────────────

    @Test
    void decodesMulhAndRmulhSignedUnsigned() {
        assertEquals(AdvSimdThreeSameOp.SMULH, mulhOp(0, 0));
        assertEquals(AdvSimdThreeSameOp.UMULH, mulhOp(1, 0));
        assertEquals(AdvSimdThreeSameOp.SRMULH, mulhOp(0, 1));
        assertEquals(AdvSimdThreeSameOp.URMULH, mulhOp(1, 1));
    }

    private static AdvSimdThreeSameOp mulhOp(int u, int bit12) {
        int r = rawMulh(u, 1, 0, 2, bit12, 3);
        IrOp.MveVector2Op op = assertInstanceOf(IrOp.MveVector2Op.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(1, op.esz());
        assertEquals(2, op.qn());
        assertEquals(3, op.qm());
        return op.op();
    }

    // ── Recusa fora da seção (bit0=0, nibble errado, bit6/bit4 setados) ──────────────────────────────

    @Test
    void rejectsBit0ZeroWhichBelongsToB16d6Section() {
        int r = raw(0, 0b11, 0, 0b1111, 0, 0, 0) & ~1;
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, r));
    }

    @Test
    void rejectsWrongNibbleOrGuardBits() {
        int base = raw(0, 0b11, 0, 0b1111, 0, 0, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base ^ (1 << 8))); // nibble != 1110.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base | (1 << 6))); // bit6 setado.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base | (1 << 4))); // bit4 setado.
    }
}
