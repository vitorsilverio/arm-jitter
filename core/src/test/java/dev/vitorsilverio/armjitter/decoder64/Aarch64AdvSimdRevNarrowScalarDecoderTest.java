package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B8.20 — 3 lacunas pequenas deixadas de fora por B8.18/B8.19: `REV64`/`REV32`/`REV16`
/// (AdvSIMD "two-register miscellaneous", grupo `Rm=00000`), `XTN`/`SHLL`/`URECPE`/`URSQRTE`
/// (grupo "narrow/widen unário", `Rm=00001`) e `SQDMULL`/`SQDMLAL`/`SQDMLSL` ESCALARES sem índice
/// (AdvSIMD "three different" escalar). Corpus REAL via `aarch64-none-elf-as`/`objdump`
/// (devkitA64).
class Aarch64AdvSimdRevNarrowScalarDecoderTest {
    private static final Aarch64Decoder DECODER = new Aarch64Decoder();

    private static Ir64Op decodeWord(int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return DECODER.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── REV64/REV32/REV16 ───────────────────────────────────────────────────────────────────────

    @Test
    void rev64Byte16b() {
        // 4e200820: rev64 v0.16b, v1.16b
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4e200820);
        assertEquals(Ir64VectorUnaryOp.REV64, op.op());
        assertEquals(false, op.scalar());
        assertEquals(true, op.q());
        assertEquals(0, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void rev64Halfword8h() {
        // 4e600820: rev64 v0.8h, v1.8h
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4e600820);
        assertEquals(Ir64VectorUnaryOp.REV64, op.op());
        assertEquals(1, op.esz());
    }

    @Test
    void rev64Word4s() {
        // 4ea00820: rev64 v0.4s, v1.4s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4ea00820);
        assertEquals(Ir64VectorUnaryOp.REV64, op.op());
        assertEquals(2, op.esz());
    }

    @Test
    void rev32Byte16b() {
        // 6e200820: rev32 v0.16b, v1.16b
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x6e200820);
        assertEquals(Ir64VectorUnaryOp.REV32, op.op());
        assertEquals(0, op.esz());
    }

    @Test
    void rev32Halfword8h() {
        // 6e600820: rev32 v0.8h, v1.8h
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x6e600820);
        assertEquals(Ir64VectorUnaryOp.REV32, op.op());
        assertEquals(1, op.esz());
    }

    @Test
    void rev16Byte16b() {
        // 4e201820: rev16 v0.16b, v1.16b
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4e201820);
        assertEquals(Ir64VectorUnaryOp.REV16, op.op());
        assertEquals(0, op.esz());
    }

    @Test
    void rev64RejectsDoubleword() {
        // 4ee00820 seria "rev64 v0.2d,v1.2d" (esz=3) — sem forma doubleword real (grupo de 1
        // elemento, no-op), reservado (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4ee00820));
    }

    @Test
    void rev32RejectsWord() {
        // 6ea00820 seria "rev32 v0.4s,v1.4s" (esz=2) — grupo de 32 bits com 1 elemento word, no-op,
        // reservado (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x6ea00820));
    }

    @Test
    void rev16RejectsHalfword() {
        // 4e601820 seria "rev16 v0.8h,v1.8h" (esz=1) — grupo de 16 bits com 1 elemento halfword,
        // no-op, reservado (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x4e601820));
    }

    @Test
    void rev16RejectsUBit() {
        // 6e201820: `U=1`/opcode=0b00011 — não existe forma `U=1` real neste opcode (reservado, G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x6e201820));
    }

    // ── XTN / SHLL / URECPE / URSQRTE ───────────────────────────────────────────────────────────

    @Test
    void xtnByte8b() {
        // 0e212820: xtn v0.8b, v1.8h
        Ir64Op.VectorArithmeticNarrowUnary op = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x0e212820);
        assertEquals(Ir64VectorNarrowUnaryOp.XTN, op.op());
        assertEquals(false, op.scalar());
        assertEquals(false, op.q());
        assertEquals(0, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void xtnHalfword4h() {
        // 0e612820: xtn v0.4h, v1.4s
        Ir64Op.VectorArithmeticNarrowUnary op = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x0e612820);
        assertEquals(Ir64VectorNarrowUnaryOp.XTN, op.op());
        assertEquals(1, op.esz());
    }

    @Test
    void xtn2Word16b() {
        // 4e212820: xtn2 v0.16b, v1.8h
        Ir64Op.VectorArithmeticNarrowUnary op = (Ir64Op.VectorArithmeticNarrowUnary) decodeWord(0x4e212820);
        assertEquals(Ir64VectorNarrowUnaryOp.XTN, op.op());
        assertEquals(true, op.q());
    }

    @Test
    void shllByteTo8h() {
        // 2e213820: shll v0.8h, v1.8b, #8
        Ir64Op.VectorShiftWidenImmediate op = (Ir64Op.VectorShiftWidenImmediate) decodeWord(0x2e213820);
        assertEquals(Ir64VectorShiftWidenOp.USHLL, op.op());
        assertEquals(false, op.q());
        assertEquals(0, op.esz());
        assertEquals(8, op.shift());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void shllHalfwordTo4s() {
        // 2e613820: shll v0.4s, v1.4h, #16
        Ir64Op.VectorShiftWidenImmediate op = (Ir64Op.VectorShiftWidenImmediate) decodeWord(0x2e613820);
        assertEquals(1, op.esz());
        assertEquals(16, op.shift());
    }

    @Test
    void shll2WordTo2d() {
        // 6e213820: shll2 v0.8h, v1.16b, #8
        Ir64Op.VectorShiftWidenImmediate op = (Ir64Op.VectorShiftWidenImmediate) decodeWord(0x6e213820);
        assertEquals(true, op.q());
        assertEquals(0, op.esz());
        assertEquals(8, op.shift());
    }

    @Test
    void urecpeWord2s() {
        // 0ea1c820: urecpe v0.2s, v1.2s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x0ea1c820);
        assertEquals(Ir64VectorUnaryOp.URECPE, op.op());
        assertEquals(false, op.q());
        assertEquals(2, op.esz());
    }

    @Test
    void urecpeWord4s() {
        // 4ea1c820: urecpe v0.4s, v1.4s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x4ea1c820);
        assertEquals(Ir64VectorUnaryOp.URECPE, op.op());
        assertEquals(true, op.q());
    }

    @Test
    void ursqrteWord2s() {
        // 2ea1c820: ursqrte v0.2s, v1.2s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x2ea1c820);
        assertEquals(Ir64VectorUnaryOp.URSQRTE, op.op());
    }

    @Test
    void ursqrteWord4s() {
        // 6ea1c820: ursqrte v0.4s, v1.4s
        Ir64Op.VectorArithmeticUnary op = (Ir64Op.VectorArithmeticUnary) decodeWord(0x6ea1c820);
        assertEquals(Ir64VectorUnaryOp.URSQRTE, op.op());
        assertEquals(true, op.q());
    }

    // ── SQDMULL/SQDMLAL/SQDMLSL escalares sem índice ────────────────────────────────────────────

    @Test
    void sqdmullScalarHalfwordToWord() {
        // 5e62d020: sqdmull s0, h1, h2
        Ir64Op.VectorArithmeticWidening op = (Ir64Op.VectorArithmeticWidening) decodeWord(0x5e62d020);
        assertEquals(Ir64VectorWideningOp.SQDMULL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
        assertEquals(2, op.rm());
    }

    @Test
    void sqdmullScalarWordToDoubleword() {
        // 5ea2d020: sqdmull d0, s1, s2
        Ir64Op.VectorArithmeticWidening op = (Ir64Op.VectorArithmeticWidening) decodeWord(0x5ea2d020);
        assertEquals(Ir64VectorWideningOp.SQDMULL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
    }

    @Test
    void sqdmlalScalarHalfwordToWord() {
        // 5e629020: sqdmlal s0, h1, h2
        Ir64Op.VectorArithmeticWidening op = (Ir64Op.VectorArithmeticWidening) decodeWord(0x5e629020);
        assertEquals(Ir64VectorWideningOp.SQDMLAL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(1, op.esz());
    }

    @Test
    void sqdmlslScalarWordToDoubleword() {
        // 5ea2b020: sqdmlsl d0, s1, s2
        Ir64Op.VectorArithmeticWidening op = (Ir64Op.VectorArithmeticWidening) decodeWord(0x5ea2b020);
        assertEquals(Ir64VectorWideningOp.SQDMLSL, op.op());
        assertEquals(true, op.scalar());
        assertEquals(2, op.esz());
    }

    @Test
    void sqdmullScalarRejectsByte() {
        // 5e22d020 seria "sqdmull h0,b1,b2" (esz=0) — sem forma byte real (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e22d020));
    }

    @Test
    void sqdmullScalarRejectsDoubleword() {
        // 5ee2d020 seria esz=3 — sem forma doubleword→128bit escalar (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5ee2d020));
    }

    @Test
    void smullHasNoScalarForm() {
        // 5e62c020 seria "smull s0,h1,h2" (opcode SMULL=0b11000, escalar) — SMULL só existe
        // vetorial, sem forma escalar real (G8).
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(0x5e62c020));
    }
}
