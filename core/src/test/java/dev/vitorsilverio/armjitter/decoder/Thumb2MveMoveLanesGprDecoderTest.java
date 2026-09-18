package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B16.13a — `Thumb2MveMoveLanesGprDecoder`: `VMOV_to_2gp`/`VMOV_from_2gp`
/// (`target/isa-decode/mve.decode`, linhas 206-208, 2 encodings). Raw montado bit a bit:
/// `bits[31:23]=1110_1100_0`, `Qd`(22,15:13), `bits[21:20]`=variante, `Rt2`(19:16), `bit12=0`,
/// `bits[11:8]=1111`, `bits[7:5]=000`, `idx`(4), `Rt`(3:0).
class Thumb2MveMoveLanesGprDecoderTest {
    private static int raw(int variant, int qd, int rt2, int idx, int rt) {
        int qdHigh = (qd >>> 3) & 1;
        int qdLow = qd & 0x7;
        return (0b111011000 << 23)
                | (qdHigh << 22)
                | (variant << 20)
                | (rt2 << 16)
                | (qdLow << 13)
                | (0b1111 << 8)
                | (idx << 4)
                | rt;
    }

    private static DecodedInstruction tryDecode(int raw) {
        return new Thumb2MveMoveLanesGprDecoder(ArmArchitecture.ARMV8_1M_MVE).tryDecode(raw, 0, Condition.AL);
    }

    @Test
    void decodesVmovToTwoGp() {
        int r = raw(0b00, 3, 4, 1, 5);
        IrOp.MveMoveLanesGpr op = assertInstanceOf(IrOp.MveMoveLanesGpr.class, tryDecode(r).liftedOp());
        assertEquals(true, op.toGpr());
        assertEquals(3, op.qd());
        assertEquals(1, op.idx());
        assertEquals(5, op.rt());
        assertEquals(4, op.rt2());
    }

    @Test
    void decodesVmovFromTwoGp() {
        int r = raw(0b01, 7, 2, 0, 6);
        IrOp.MveMoveLanesGpr op = assertInstanceOf(IrOp.MveMoveLanesGpr.class, tryDecode(r).liftedOp());
        assertEquals(false, op.toGpr());
        assertEquals(7, op.qd());
        assertEquals(0, op.idx());
        assertEquals(6, op.rt());
        assertEquals(2, op.rt2());
    }

    @Test
    void toGprRejectsRtEqualsRt2() {
        int r = raw(0b00, 1, 4, 0, 4);
        assertNull(tryDecode(r));
    }

    @Test
    void fromGprAllowsRtEqualsRt2() {
        // Achado real: só VMOV_to_2gp recusa Rt==Rt2 no QEMU real (trans_VMOV_from_2gp não checa).
        int r = raw(0b01, 1, 4, 0, 4);
        IrOp.MveMoveLanesGpr op = assertInstanceOf(IrOp.MveMoveLanesGpr.class, tryDecode(r).liftedOp());
        assertEquals(4, op.rt());
        assertEquals(4, op.rt2());
    }

    @Test
    void rejectsRtOrRt2EqualsSpOrPc() {
        assertNull(tryDecode(raw(0b00, 1, 2, 0, 13)));
        assertNull(tryDecode(raw(0b00, 1, 2, 0, 15)));
        assertNull(tryDecode(raw(0b00, 1, 13, 0, 2)));
        assertNull(tryDecode(raw(0b00, 1, 15, 0, 2)));
    }

    @Test
    void rejectsInvalidVariant() {
        assertNull(tryDecode(raw(0b10, 1, 2, 0, 3)));
        assertNull(tryDecode(raw(0b11, 1, 2, 0, 3)));
    }

    @Test
    void requiresMveIntegerFeature() {
        int r = raw(0b00, 1, 2, 0, 3);
        assertNull(new Thumb2MveMoveLanesGprDecoder(ArmArchitecture.ARMV7A).tryDecode(r, 0, Condition.AL));
    }

    @Test
    void rejectsMalformedLiteralBits() {
        int good = raw(0b00, 1, 2, 0, 3);
        assertNull(tryDecode(good | (1 << 12)), "bit12 tem que ser 0");
        assertNull(tryDecode(good ^ (1 << 8)), "bits[11:8] têm que ser 1111");
        assertNull(tryDecode(good ^ (1 << 5)), "bits[7:5] têm que ser 000");
    }

    @Test
    void rejectsQdInHighBank() {
        // Qd=8 (Q8, fora de Q0-Q7 sob MVE_INTEGER).
        assertNull(tryDecode(raw(0b00, 8, 2, 0, 3)));
    }
}
