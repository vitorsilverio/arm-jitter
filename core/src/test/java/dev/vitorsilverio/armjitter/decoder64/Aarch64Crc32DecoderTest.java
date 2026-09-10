package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.17 — `CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}` (`FEAT_CRC32`). Vetores golden conferidos com
/// `aarch64-linux-gnu-as`/`objdump` (WSL, `.arch armv8.1-a`, `crc32b/h/w/x w0, w1, w2/x2`).
class Aarch64Crc32DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder CRC32_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_1_A);

    // -- golden: aarch64-linux-gnu-as/objdump, `crc32{b,h,w,x}/crc32c{b,h,w,x} w0, w1, w2/x2` --
    private static final int CRC32B_W0_W1_W2 = 0x1AC24020;
    private static final int CRC32H_W0_W1_W2 = 0x1AC24420;
    private static final int CRC32W_W0_W1_W2 = 0x1AC24820;
    private static final int CRC32X_W0_W1_X2 = 0x9AC24C20;
    private static final int CRC32CB_W0_W1_W2 = 0x1AC25020;
    private static final int CRC32CH_W0_W1_W2 = 0x1AC25420;
    private static final int CRC32CW_W0_W1_W2 = 0x1AC25820;
    private static final int CRC32CX_W0_W1_X2 = 0x9AC25C20;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByCrc32Feature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CRC32B_W0_W1_W2));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, CRC32CB_W0_W1_W2));
    }

    @Test
    void decodesRegistersAndWidthForEachSize() {
        Ir64Op.Crc32 b = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32B_W0_W1_W2);
        assertEquals(0, b.rd());
        assertEquals(1, b.rn());
        assertEquals(2, b.rm());
        assertEquals(8, b.dataWidthBits());
        assertFalse(b.castagnoli());

        Ir64Op.Crc32 h = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32H_W0_W1_W2);
        assertEquals(16, h.dataWidthBits());

        Ir64Op.Crc32 w = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32W_W0_W1_W2);
        assertEquals(32, w.dataWidthBits());

        Ir64Op.Crc32 x = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32X_W0_W1_X2);
        assertEquals(64, x.dataWidthBits());
    }

    @Test
    void castagnoliVariantSetsFlagButSameFields() {
        Ir64Op.Crc32 cb = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32CB_W0_W1_W2);
        assertTrue(cb.castagnoli());
        assertEquals(8, cb.dataWidthBits());

        Ir64Op.Crc32 ch = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32CH_W0_W1_W2);
        assertTrue(ch.castagnoli());
        assertEquals(16, ch.dataWidthBits());

        Ir64Op.Crc32 cw = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32CW_W0_W1_W2);
        assertTrue(cw.castagnoli());
        assertEquals(32, cw.dataWidthBits());

        Ir64Op.Crc32 cx = (Ir64Op.Crc32) decode(CRC32_DECODER, CRC32CX_W0_W1_X2);
        assertTrue(cx.castagnoli());
        assertEquals(64, cx.dataWidthBits());
    }

    @Test
    void doublewordFormRequiresSfSetReservedOtherwise() {
        // CRC32X mede sf=1; forçar sf=0 com size=0b11 (X) é encoding reservado (nenhuma forma
        // válida tem essa combinação) — G8: recusar, não confundir com CRC32W.
        int reserved = CRC32X_W0_W1_X2 & ~(1 << 31);
        assertThrows(UnsupportedOperationException.class, () -> decode(CRC32_DECODER, reserved));
    }

    @Test
    void nonDoublewordFormsRejectSfSet() {
        // CRC32B/H/W medem sf=0; forçar sf=1 sem mudar size é encoding reservado.
        int reserved = CRC32B_W0_W1_W2 | (1 << 31);
        assertThrows(UnsupportedOperationException.class, () -> decode(CRC32_DECODER, reserved));
    }
}
