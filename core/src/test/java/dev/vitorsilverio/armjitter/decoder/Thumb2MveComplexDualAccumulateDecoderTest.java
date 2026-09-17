package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.7 (sub-família 2) — `Thumb2MveComplexDualAccumulateDecoder`: o `{}` sobreposto de complexos FP
/// (`VCMUL0/90/180/270`) + dual-accumulate inteiros (`VQDMLADH`/`VQDMLSDH` e variantes `X`/`R`) +
/// `VQDMULLB`/`VQDMULLT` (`target/isa-decode/mve.decode`, linhas 325-350, 14 encodings). Raws
/// construídos bit a bit com o layout do Javadoc da classe: `bits[31:29]=111`, `bit28`,
/// `bits[27:24]=1110`, `bit23=0`, `Qd`(22,15:13), `bits[21:20]`, `Qn`(17:19,7), `bit16`, `bit12=0`,
/// nibble(11:8), `bit6=0`, `Qm`(5,3:1), `bit4=0`, `bit0`.
class Thumb2MveComplexDualAccumulateDecoderTest {
    private static final int NIBBLE_ADD_ROT = 0b1110;
    private static final int NIBBLE_DOUBLING_WIDEN = 0b1111;

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

    private static DecodedInstruction tryDecode(ArmArchitecture architecture, int raw) {
        return new Thumb2MveComplexDualAccumulateDecoder(architecture).tryDecode(raw, 0, Condition.AL);
    }

    // ── VCMUL0/90/180/270 (bits[21:20]=0b11, nibble=1110) ───────────────────────────────────────────

    @Test
    void decodesComplexMultiplyRotationFromBit16AndBit0() {
        assertEquals(0, complexMultiply(0, 0, 0).rotation());
        assertEquals(1, complexMultiply(0, 0, 1).rotation());
        assertEquals(2, complexMultiply(0, 1, 0).rotation());
        assertEquals(3, complexMultiply(0, 1, 1).rotation());
    }

    @Test
    void complexMultiplyEszFromBit28() {
        // %size_28: size = bit28 + 1 -> bit28=0 é binary16 (esz=1), bit28=1 é binary32 (esz=2).
        assertEquals(1, complexMultiply(0, 0, 0).esz());
        assertEquals(2, complexMultiply(1, 0, 0).esz());
    }

    private static IrOp.MveVectorFpComplexMultiply complexMultiply(int bit28, int bit16, int bit0) {
        int r = raw(bit28, 0b11, 5, 6, 7, bit16, NIBBLE_ADD_ROT, bit0);
        IrOp.MveVectorFpComplexMultiply op = assertInstanceOf(IrOp.MveVectorFpComplexMultiply.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(5, op.qd());
        assertEquals(6, op.qn());
        assertEquals(7, op.qm());
        return op;
    }

    @Test
    void complexMultiplyRequiresMveFloat() {
        int r = raw(0, 0b11, 5, 6, 7, 0, NIBBLE_ADD_ROT, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r), "ARMV8_1M sem MVE_FLOAT nem decodifica");
    }

    // ── VQDMLADH/VQDMLSDH + X/R (bits[21:20] real, nibble=1110) ─────────────────────────────────────

    @Test
    void decodesDualMultiplyAddHighAddSubExchangeRounded() {
        IrOp.MveVectorDualMultiplyAddHigh plain = dualMultiplyAddHigh(0, 1, 0, 0);
        assertTrue(plain.add());
        assertFalse(plain.exchange());
        assertFalse(plain.rounded());
        assertEquals(1, plain.esz());

        IrOp.MveVectorDualMultiplyAddHigh sub = dualMultiplyAddHigh(1, 1, 0, 0);
        assertFalse(sub.add());

        IrOp.MveVectorDualMultiplyAddHigh exchange = dualMultiplyAddHigh(0, 2, 1, 0);
        assertTrue(exchange.exchange());
        assertEquals(2, exchange.esz());

        IrOp.MveVectorDualMultiplyAddHigh rounded = dualMultiplyAddHigh(0, 0, 0, 1);
        assertTrue(rounded.rounded());
        assertEquals(0, rounded.esz());
    }

    private static IrOp.MveVectorDualMultiplyAddHigh dualMultiplyAddHigh(int u, int size, int bit16, int bit0) {
        int r = raw(u, size, 3, 4, 5, bit16, NIBBLE_ADD_ROT, bit0);
        IrOp.MveVectorDualMultiplyAddHigh op = assertInstanceOf(IrOp.MveVectorDualMultiplyAddHigh.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
        assertEquals(3, op.qd());
        assertEquals(4, op.qn());
        assertEquals(5, op.qm());
        return op;
    }

    @Test
    void dualMultiplyAddHighRequiresMveInteger() {
        int r = raw(0, 1, 3, 4, 5, 0, NIBBLE_ADD_ROT, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r), "ARMV8_1M sem MVE_INTEGER nem decodifica");
    }

    // ── VQDMULLB/VQDMULLT (bits[21:20]=0b11, nibble=1111, bit0=1 fixo) ──────────────────────────────

    @Test
    void decodesDoublingWideningMultiplyBottomAndTop() {
        IrOp.MveVectorDoublingWideningMultiply b = doublingWideningMultiply(0, 0, 1, 2, 3);
        assertFalse(b.top());
        assertEquals(1, b.esz());

        IrOp.MveVectorDoublingWideningMultiply t = doublingWideningMultiply(1, 1, 1, 2, 3);
        assertTrue(t.top());
        assertEquals(2, t.esz());
        assertEquals(1, t.qd());
        assertEquals(2, t.qn());
        assertEquals(3, t.qm());
    }

    private static IrOp.MveVectorDoublingWideningMultiply doublingWideningMultiply(int bit28, int top, int qd,
            int qn, int qm) {
        int r = raw(bit28, 0b11, qd, qn, qm, top, NIBBLE_DOUBLING_WIDEN, 1);
        return assertInstanceOf(IrOp.MveVectorDoublingWideningMultiply.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, r).liftedOp());
    }

    @Test
    void doublingWideningMultiplyRequiresBit0SetAndReservedSize() {
        int wrongBit0 = raw(0, 0b11, 1, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, wrongBit0));
        int wrongSize = raw(0, 0b01, 1, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, wrongSize));
    }

    @Test
    void doublingWideningMultiplyRejectsWordSizeSelfCollision() {
        // esz=2 (word, bit28=1) com Qd==Qn: recusado (achado real de trans_VQDMULLB/T).
        int qdEqualsQn = raw(1, 0b11, 2, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, qdEqualsQn));
        int qdEqualsQm = raw(1, 0b11, 2, 3, 2, 0, NIBBLE_DOUBLING_WIDEN, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, qdEqualsQm));
        // Halfword (esz=1, bit28=0) não tem essa restrição.
        int halfword = raw(0, 0b11, 2, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 1);
        assertInstanceOf(IrOp.MveVectorDoublingWideningMultiply.class,
                tryDecode(ArmArchitecture.ARMV8_1M_MVE, halfword).liftedOp());
    }

    @Test
    void doublingWideningMultiplyRequiresMveInteger() {
        int r = raw(0, 0b11, 1, 2, 3, 0, NIBBLE_DOUBLING_WIDEN, 1);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M, r), "ARMV8_1M sem MVE_INTEGER nem decodifica");
    }

    // ── Recusa fora da seção ─────────────────────────────────────────────────────────────────────────

    @Test
    void rejectsWrongNibbleOrGuardBits() {
        int base = raw(0, 0b11, 0, 0, 0, 0, NIBBLE_ADD_ROT, 0);
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base ^ (1 << 8))); // nibble != 1110/1111.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base | (1 << 12))); // bit12 setado.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base | (1 << 6))); // bit6 setado.
        assertNull(tryDecode(ArmArchitecture.ARMV8_1M_MVE, base | (1 << 4))); // bit4 setado.
    }
}
