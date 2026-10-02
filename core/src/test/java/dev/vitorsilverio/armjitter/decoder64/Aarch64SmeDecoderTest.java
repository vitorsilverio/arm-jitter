package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SmeOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `ZERO`/`ZERO_zt0`/`MOVA`/`MOVAZ` (B18.3). **Toda palavra abaixo foi conferida contra
/// `aarch64-none-elf-as -march=armv9.4-a+sme2p1` (devkitA64)** — nenhuma calculada à mão (Armadilha 3
/// da task).
class Aarch64SmeDecoderTest {
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_2_A, "teste-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SME2P1 = Aarch64Architecture.extending(
            SME2, "teste-SME2p1", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);

    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder SME_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_2_A);
    private static final Aarch64Decoder SME2_DECODER = new Aarch64Decoder(SME2);
    private static final Aarch64Decoder SME2P1_DECODER = new Aarch64Decoder(SME2P1);

    private static final long INSTRUCTION_ADDRESS = 0x40;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(0x100);
        raw.put32((int) INSTRUCTION_ADDRESS, word);
        return decoder.decode(AddressSpace64.wrapping(raw), INSTRUCTION_ADDRESS);
    }

    // ── `### SME Misc` ───────────────────────────────────────────────────────────────────────────

    @Test
    void zeroDecodesTheImmediateMask() {
        // zero {za}  (imm8 = 0xff)
        SmeOp64.Zero op = assertInstanceOf(SmeOp64.Zero.class, decode(SME_DECODER, 0xc00800ff));
        assertEquals(0xff, op.imm8());
        assertEquals(INSTRUCTION_ADDRESS, op.instructionAddress());
    }

    @Test
    void zeroZt0RequiresSme2() {
        assertInstanceOf(SmeOp64.ZeroZt0.class, decode(SME2_DECODER, 0xc0480001));
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xc0480001));
    }

    @Test
    void rejectedGenericallyWithoutSme() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, 0xc00800ff));
    }

    // ── `MOVA_tz`/`MOVA_zt` (predicada, 1 vetor) ────────────────────────────────────────────────

    @Test
    void movaTzHorizontalEsz0() {
        // mov za0h.b[w12, 0], p0/m, z0.b
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0000000));
        assertFalse(op.toVector());
        assertFalse(op.zero());
        assertTrue(op.predicated());
        assertEquals(0, op.pg());
        assertEquals(1, op.count());
        assertEquals(0, op.esz());
        assertEquals(0, op.tile());
        assertFalse(op.vertical());
        assertEquals(0, op.zr());
        assertEquals(12, op.registerIndex());
        assertEquals(0, op.offset());
    }

    @Test
    void movaZtHorizontalEsz0() {
        // mov z0.b, p0/m, za0h.b[w12, 0]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0020000));
        assertTrue(op.toVector());
        assertEquals(0, op.esz());
        assertEquals(0, op.tile());
        assertEquals(0, op.zr());
        assertEquals(12, op.registerIndex());
    }

    @Test
    void movaTzVerticalEszQuadUsesTheExtraDiscriminatorBit() {
        // mov za0v.q[w12, 0], p0/m, z0.q
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0c18000));
        assertEquals(4, op.esz());
        assertTrue(op.vertical());
        assertEquals(0, op.tile());
        assertEquals(0, op.offset(), "esz=Q: off=0 fixo, não há campo");
    }

    @Test
    void movaTzHorizontalEsz1NonZeroFields() {
        // mov za1h.h[w13, 2], p3/m, z5.h
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0402caa));
        assertFalse(op.toVector());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(3, op.pg());
        assertEquals(5, op.zr());
        assertEquals(13, op.registerIndex());
        assertEquals(2, op.offset());
        assertFalse(op.vertical());
    }

    @Test
    void movaZtHorizontalEsz1NonZeroFields() {
        // mov z5.h, p3/m, za1h.h[w13, 2]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0422d45));
        assertTrue(op.toVector());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(3, op.pg());
        assertEquals(5, op.zr());
        assertEquals(13, op.registerIndex());
        assertEquals(2, op.offset());
    }

    @Test
    void movaTzVerticalEsz2() {
        // mov za2v.s[w14, 1], p1/m, z2.s
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc080c449));
        assertFalse(op.toVector());
        assertEquals(2, op.esz());
        assertEquals(2, op.tile());
        assertEquals(1, op.pg());
        assertEquals(2, op.zr());
        assertEquals(14, op.registerIndex());
        assertEquals(1, op.offset());
        assertTrue(op.vertical());
    }

    @Test
    void movaTzHorizontalEsz3() {
        // mov za5h.d[w15, 0], p7/m, z9.d
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0c07d2a));
        assertEquals(3, op.esz());
        assertEquals(5, op.tile());
        assertEquals(7, op.pg());
        assertEquals(9, op.zr());
        assertEquals(15, op.registerIndex());
        assertEquals(0, op.offset());
    }

    @Test
    void movaTzVerticalEsz1() {
        // mov za1v.h[w13, 2], p3/m, z5.h
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc040acaa));
        assertTrue(op.vertical());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(3, op.pg());
        assertEquals(5, op.zr());
        assertEquals(13, op.registerIndex());
        assertEquals(2, op.offset());
    }

    // ── `MOVA_tz2`/`zt2`/`tz4`/`zt4` (multi-vetor de tile, `FEAT_SME2`) ─────────────────────────

    @Test
    void movaPredicatedAloneNeedsOnlySme() {
        assertInstanceOf(SmeOp64.Mova.class, decode(SME_DECODER, 0xc0000000));
    }

    @Test
    void movaGroupRequiresSme2() {
        assertThrows(UnsupportedOperationException.class, () -> decode(SME_DECODER, 0xc0460088));
        assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0460088));
    }

    @Test
    void movaZt2HorizontalEsz1() {
        // mov {z8.h-z9.h}, za1h.h[w12, 0:1]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0460088));
        assertTrue(op.toVector());
        assertFalse(op.zero());
        assertFalse(op.predicated());
        assertEquals(-1, op.pg());
        assertEquals(2, op.count());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertFalse(op.vertical());
        assertEquals(4, op.zr(), "grupo z8-z9: zr = z8/2");
        assertEquals(12, op.registerIndex());
        assertEquals(0, op.offset());
    }

    @Test
    void movaTz2HorizontalEsz1() {
        // mov za1h.h[w12, 0:1], {z8.h-z9.h}
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0440104));
        assertFalse(op.toVector());
        assertEquals(2, op.count());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(4, op.zr());
    }

    @Test
    void movaZt2HorizontalEsz2() {
        // mov {z8.s-z9.s}, za2h.s[w12, 0:1]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0860088));
        assertEquals(2, op.esz());
        assertEquals(2, op.tile());
        assertEquals(4, op.zr());
    }

    @Test
    void movaTz2HorizontalEsz2() {
        // mov za3h.s[w12, 0:1], {z8.s-z9.s}
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0840106));
        assertEquals(2, op.esz());
        assertEquals(3, op.tile());
        assertEquals(4, op.zr());
    }

    @Test
    void movaZt4HorizontalEsz1() {
        // mov {z8.h-z11.h}, za1h.h[w12, 0:3]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0460448));
        assertEquals(4, op.count());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(2, op.zr(), "grupo z8-z11: zr = z8/4");
    }

    @Test
    void movaTz4HorizontalEsz1() {
        // mov za1h.h[w12, 0:3], {z8.h-z11.h}
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0440502));
        assertEquals(4, op.count());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(2, op.zr());
    }

    @Test
    void movaZt4HorizontalEsz2() {
        // mov {z8.s-z11.s}, za2h.s[w12, 0:3]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0860448));
        assertEquals(2, op.esz());
        assertEquals(2, op.tile());
        assertEquals(2, op.zr());
    }

    @Test
    void movaTz4HorizontalEsz2() {
        // mov za2h.s[w12, 0:3], {z8.s-z11.s}
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0840502));
        assertEquals(2, op.esz());
        assertEquals(2, op.tile());
        assertEquals(2, op.zr());
    }

    @Test
    void movaZt2Vertical() {
        // mov {z8.h-z9.h}, za1v.h[w12, 0:1]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0468088));
        assertTrue(op.vertical());
        assertEquals(1, op.esz());
        assertEquals(1, op.tile());
        assertEquals(4, op.zr());
    }

    @Test
    void movaTz4HorizontalEsz3() {
        // mov {z4.d-z7.d}, za3h.d[w12, 0:3]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0c60464));
        assertTrue(op.toVector());
        assertEquals(3, op.esz());
        assertEquals(3, op.tile());
        assertEquals(1, op.zr());
    }

    // ── `MOVA_az2`/`az4`/`za2`/`za4` (array-vetor, `FEAT_SME2`) ─────────────────────────────────

    @Test
    void movaAz2ArrayForm() {
        // mov za.d[w8, 0, vgx2], {z4.d-z5.d}
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0040880));
        assertFalse(op.toVector());
        assertFalse(op.zero());
        assertFalse(op.predicated());
        assertEquals(2, op.count());
        assertEquals(-1, op.esz());
        assertEquals(-1, op.tile(), "forma array-vetor: sem tile");
        assertFalse(op.vertical());
        assertEquals(2, op.zr(), "grupo z4-z5: zr = z4/2");
        assertEquals(8, op.registerIndex(), "W8-W11, não W12-W15");
        assertEquals(0, op.offset());
    }

    @Test
    void movaAz4ArrayForm() {
        // mov za.d[w8, 0, vgx4], {z4.d-z7.d}
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0040c80));
        assertEquals(4, op.count());
        assertEquals(1, op.zr());
        assertEquals(8, op.registerIndex());
    }

    @Test
    void movaZa2ArrayForm() {
        // mov {z4.d-z5.d}, za.d[w8, 0, vgx2]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0060804));
        assertTrue(op.toVector());
        assertEquals(2, op.count());
        assertEquals(2, op.zr());
    }

    @Test
    void movaZa4ArrayForm() {
        // mov {z4.d-z7.d}, za.d[w8, 0, vgx4]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2_DECODER, 0xc0060c04));
        assertTrue(op.toVector());
        assertEquals(4, op.count());
        assertEquals(1, op.zr());
    }

    // ── `### SME Move and Zero` (`FEAT_SME2p1`) ─────────────────────────────────────────────────

    @Test
    void movazRequiresSme2p1() {
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2_DECODER, 0xc0c30200));
        assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0c30200));
    }

    @Test
    void movazZaFormsZeroTheSource() {
        // movaz {z4.d-z5.d}, za.d[w8, 0, vgx2]
        SmeOp64.Mova op2 = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0060a04));
        assertTrue(op2.zero());
        assertTrue(op2.toVector());
        assertEquals(2, op2.count());
        assertEquals(2, op2.zr());

        // movaz {z4.d-z7.d}, za.d[w8, 0, vgx4]
        SmeOp64.Mova op4 = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0060e04));
        assertTrue(op4.zero());
        assertEquals(4, op4.count());
        assertEquals(1, op4.zr());
    }

    @Test
    void movazZtSingleVectorEszQuad() {
        // movaz z0.q, za0h.q[w12, 0]
        SmeOp64.Mova op = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0c30200));
        assertTrue(op.zero());
        assertTrue(op.toVector());
        assertFalse(op.predicated());
        assertEquals(1, op.count());
        assertEquals(4, op.esz());
        assertEquals(0, op.tile());
        assertEquals(0, op.zr());
        assertEquals(12, op.registerIndex());
    }

    @Test
    void movazZtSingleVectorNonZeroFields() {
        // movaz z3.h, za1h.h[w13, 2]
        SmeOp64.Mova h = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0422343));
        assertEquals(1, h.esz());
        assertEquals(1, h.tile());
        assertEquals(3, h.zr());
        assertEquals(13, h.registerIndex());
        assertEquals(2, h.offset());

        // movaz z3.s, za2h.s[w14, 1]
        SmeOp64.Mova s = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0824323));
        assertEquals(2, s.esz());
        assertEquals(2, s.tile());
        assertEquals(3, s.zr());
        assertEquals(14, s.registerIndex());
        assertEquals(1, s.offset());

        // movaz z3.d, za5h.d[w15, 0]
        SmeOp64.Mova d = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0c26343));
        assertEquals(3, d.esz());
        assertEquals(5, d.tile());
        assertEquals(3, d.zr());
        assertEquals(15, d.registerIndex());
        assertEquals(0, d.offset());
    }

    @Test
    void movazZt2And4() {
        // movaz {z8.h-z9.h}, za1h.h[w12, 0:1]
        SmeOp64.Mova h2 = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0460288));
        assertEquals(2, h2.count());
        assertEquals(1, h2.esz());
        assertEquals(1, h2.tile());
        assertEquals(4, h2.zr());

        // movaz {z8.s-z9.s}, za2h.s[w12, 0:1]
        SmeOp64.Mova s2 = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0860288));
        assertEquals(2, s2.esz());
        assertEquals(2, s2.tile());

        // movaz {z8.h-z11.h}, za1h.h[w12, 0:3]
        SmeOp64.Mova h4 = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0460648));
        assertEquals(4, h4.count());
        assertEquals(1, h4.esz());
        assertEquals(1, h4.tile());
        assertEquals(2, h4.zr());

        // movaz {z8.s-z11.s}, za2h.s[w12, 0:3]
        SmeOp64.Mova s4 = assertInstanceOf(SmeOp64.Mova.class, decode(SME2P1_DECODER, 0xc0860648));
        assertEquals(2, s4.esz());
        assertEquals(2, s4.tile());
    }

    @Test
    void reservedPrefixWithinTheSmeSpaceStaysUnimplemented() {
        // Um encoding `0xC0...` fora das 47 linhas (bits reservados != o esperado) continua G8.
        assertThrows(UnsupportedOperationException.class, () -> decode(SME2P1_DECODER, 0xC0100000));
    }
}
