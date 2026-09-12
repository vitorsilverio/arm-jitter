package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.18 (`FEAT_FRINTTS`): `FRINT32Z`/`FRINT32X`/`FRINT64Z`/`FRINT64X`, escalar+vetorial —
/// decoder. Corpus REAL via `aarch64-linux-gnu-as -march=armv8.5-a` (WSL/Ubuntu), conferido bit a
/// bit contra `objdump -d` (ver `## Resultado` da task).
class Aarch64FrintTsDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_5_A);
    private static final Aarch64Decoder NO_FEATURE_DECODER =
            new Aarch64Decoder(Aarch64Architecture.ARMV8_4_A);

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    private static Ir64Op decodeWord(int word) {
        return decodeWord(DECODER, word);
    }

    // ── Escalar (`_s`) ───────────────────────────────────────────────────────────────────────────

    @Test
    void frint32zSingle() {
        // 1e284020: frint32z s0, s1
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e284020);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.direction());
        assertEquals(false, op.rangeIs64Bit());
        assertEquals(false, op.doublePrecision());
        assertEquals(0, op.vd());
        assertEquals(1, op.vn());
    }

    @Test
    void frint32xSingle() {
        // 1e28c062: frint32x s2, s3
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e28c062);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
        assertEquals(false, op.rangeIs64Bit());
        assertEquals(false, op.doublePrecision());
        assertEquals(2, op.vd());
        assertEquals(3, op.vn());
    }

    @Test
    void frint64zSingle() {
        // 1e2940a4: frint64z s4, s5
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e2940a4);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.direction());
        assertEquals(true, op.rangeIs64Bit());
        assertEquals(false, op.doublePrecision());
        assertEquals(4, op.vd());
        assertEquals(5, op.vn());
    }

    @Test
    void frint64xSingle() {
        // 1e29c0e6: frint64x s6, s7
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e29c0e6);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
        assertEquals(true, op.rangeIs64Bit());
        assertEquals(false, op.doublePrecision());
        assertEquals(6, op.vd());
        assertEquals(7, op.vn());
    }

    @Test
    void frint32zDouble() {
        // 1e684020: frint32z d0, d1
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e684020);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.direction());
        assertEquals(false, op.rangeIs64Bit());
        assertEquals(true, op.doublePrecision());
    }

    @Test
    void frint32xDouble() {
        // 1e68c062: frint32x d2, d3
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e68c062);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
        assertEquals(false, op.rangeIs64Bit());
        assertEquals(true, op.doublePrecision());
    }

    @Test
    void frint64zDouble() {
        // 1e6940a4: frint64z d4, d5
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e6940a4);
        assertEquals(Ir64Op.Fp64RoundingDirection.TOWARD_ZERO, op.direction());
        assertEquals(true, op.rangeIs64Bit());
        assertEquals(true, op.doublePrecision());
    }

    @Test
    void frint64xDouble() {
        // 1e69c0e6: frint64x d6, d7
        Ir64Op.Fp64RoundRangeLimited op = (Ir64Op.Fp64RoundRangeLimited) decodeWord(0x1e69c0e6);
        assertEquals(Ir64Op.Fp64RoundingDirection.NEAREST_TIES_EVEN, op.direction());
        assertEquals(true, op.rangeIs64Bit());
        assertEquals(true, op.doublePrecision());
    }

    @Test
    void scalarFormsRequireFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x1e284020));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x1e28c062));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x1e2940a4));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x1e29c0e6));
    }

    // ── Vetorial (`_v`) ──────────────────────────────────────────────────────────────────────────

    @Test
    void frint32zVector2s() {
        // 0e21e820: frint32z v0.2s, v1.2s
        Ir64Op.VectorFpArithmeticUnary op = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x0e21e820);
        assertEquals(Ir64VectorFpUnaryOp.RINT32Z, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void frint32xVector4s() {
        // 6e21e862: frint32x v2.4s, v3.4s
        Ir64Op.VectorFpArithmeticUnary op = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e21e862);
        assertEquals(Ir64VectorFpUnaryOp.RINT32X, op.op());
        assertEquals(false, op.scalar());
        assertEquals(true, op.q());
        assertEquals(2, op.esz());
        assertEquals(2, op.rd());
        assertEquals(3, op.rn());
    }

    @Test
    void frint64zVector2d() {
        // 4e61f8a4: frint64z v4.2d, v5.2d
        Ir64Op.VectorFpArithmeticUnary op = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x4e61f8a4);
        assertEquals(Ir64VectorFpUnaryOp.RINT64Z, op.op());
        assertEquals(false, op.scalar());
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(4, op.rd());
        assertEquals(5, op.rn());
    }

    @Test
    void frint64xVector2d() {
        // 6e61f8e6: frint64x v6.2d, v7.2d
        Ir64Op.VectorFpArithmeticUnary op = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6e61f8e6);
        assertEquals(Ir64VectorFpUnaryOp.RINT64X, op.op());
        assertEquals(false, op.scalar());
        assertEquals(true, op.q());
        assertEquals(3, op.esz());
        assertEquals(6, op.rd());
        assertEquals(7, op.rn());
    }

    @Test
    void vectorFormsRequireFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x0e21e820));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x6e21e862));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x4e61f8a4));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(NO_FEATURE_DECODER, 0x6e61f8e6));
    }

    @Test
    void vectorSqrtStillDecodesInSameOpcodeSlot() {
        // MESMO opcode(0b1_1111) de FRINT64*, `a`(bit23)=1 em vez de 0 — não deve colidir (E8).
        // 6ee1f8a4: fsqrt v4.2d, v5.2d (mesmos Rd/Rn de frint64zVector2d, `a` forçado a 1 via bit23).
        Ir64Op.VectorFpArithmeticUnary op = (Ir64Op.VectorFpArithmeticUnary) decodeWord(0x6ee1f8a4);
        assertEquals(Ir64VectorFpUnaryOp.SQRT, op.op());
    }
}
