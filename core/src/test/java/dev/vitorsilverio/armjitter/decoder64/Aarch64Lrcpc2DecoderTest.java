package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.19 — `LDAPR_i` (`LDAPUR`/`LDAPURB`/`LDAPURH`/`LDAPURSB`/`LDAPURSH`/`LDAPURSW`) + `STLR_i`
/// (`STLUR`/`STLURB`/`STLURH`), a forma com offset imediato de 9 bits com sinal de `LDAPR`/`STLR`
/// (`FEAT_LRCPC2`, ARMv8.4-A). Reusa o mesmo decode de `LDUR`/`STUR` (campos `size`/`opc`/`imm9` nas
/// mesmas posições). Vetores golden conferidos com `aarch64-linux-gnu-as -march=armv8.4-a` (WSL
/// Ubuntu).
class Aarch64Lrcpc2DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    // ARMv8.3-A já tem FEAT_LRCPC (forma sem offset, B19.1) mas ainda não FEAT_LRCPC2.
    private static final Aarch64Decoder LRCPC_ONLY_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);
    private static final Aarch64Decoder LRCPC2_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_4_A);

    private static final int LDAPUR_X = 0xd9408020;       // ldapur x0, [x1, #8]
    private static final int LDAPURB_W = 0x19408020;      // ldapurb w0, [x1, #8]
    private static final int LDAPURH_W = 0x59408020;      // ldapurh w0, [x1, #8]
    private static final int STLUR_X = 0xd9008020;        // stlur x0, [x1, #8]
    private static final int STLUR_W = 0x990040a4;        // stlur w4, [x5, #4]
    private static final int STLURH_W = 0x590040a4;       // stlurh w4, [x5, #4]
    private static final int STLURB_W = 0x190040a4;       // stlurb w4, [x5, #4]
    private static final int LDAPURSB_X = 0x199f8062;     // ldapursb x2, [x3, #-8]
    private static final int LDAPURSB_W = 0x19df8062;     // ldapursb w2, [x3, #-8]
    private static final int LDAPURSH_X = 0x599f8062;     // ldapursh x2, [x3, #-8]
    private static final int LDAPURSH_W = 0x59df8062;     // ldapursh w2, [x3, #-8]
    private static final int LDAPURSW_X = 0x999f8062;     // ldapursw x2, [x3, #-8]

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByLrcpc2Feature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, LDAPUR_X));
        assertThrows(UnsupportedOperationException.class, () -> decode(LRCPC_ONLY_DECODER, LDAPUR_X));
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, STLUR_X));
        assertThrows(UnsupportedOperationException.class, () -> decode(LRCPC_ONLY_DECODER, STLUR_X));
    }

    @Test
    void decodesLdapurZeroExtendWithPositiveOffset() {
        Ir64Op.Load64 op = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPUR_X);
        assertEquals(0, op.rt());
        assertEquals(1, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertFalse(op.signExtend());
        assertTrue(op.wide());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(8L, op.immediate());
    }

    @Test
    void decodesLdapurbAndLdapurhZeroExtendToW() {
        Ir64Op.Load64 b = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURB_W);
        assertEquals(Ir64MemSize.BYTE, b.size());
        assertFalse(b.signExtend());
        assertFalse(b.wide());
        assertEquals(8L, b.immediate());

        Ir64Op.Load64 h = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURH_W);
        assertEquals(Ir64MemSize.HALF, h.size());
        assertFalse(h.signExtend());
        assertFalse(h.wide());
    }

    @Test
    void decodesStlurWithPositiveAndNarrowerOffsets() {
        Ir64Op.Store64 x = (Ir64Op.Store64) decode(LRCPC2_DECODER, STLUR_X);
        assertEquals(0, x.rt());
        assertEquals(1, x.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, x.size());
        assertTrue(x.wide());
        assertEquals(8L, x.immediate());

        Ir64Op.Store64 w = (Ir64Op.Store64) decode(LRCPC2_DECODER, STLUR_W);
        assertEquals(4, w.rt());
        assertEquals(5, w.rn());
        assertEquals(Ir64MemSize.WORD, w.size());
        assertFalse(w.wide());
        assertEquals(4L, w.immediate());

        Ir64Op.Store64 h = (Ir64Op.Store64) decode(LRCPC2_DECODER, STLURH_W);
        assertEquals(Ir64MemSize.HALF, h.size());
        assertEquals(4L, h.immediate());

        Ir64Op.Store64 b = (Ir64Op.Store64) decode(LRCPC2_DECODER, STLURB_W);
        assertEquals(Ir64MemSize.BYTE, b.size());
        assertEquals(4L, b.immediate());
    }

    @Test
    void decodesLdapurSignExtendFormsWithNegativeOffset() {
        Ir64Op.Load64 sbX = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURSB_X);
        assertEquals(2, sbX.rt());
        assertEquals(3, sbX.rn());
        assertEquals(Ir64MemSize.BYTE, sbX.size());
        assertTrue(sbX.signExtend());
        assertTrue(sbX.wide());
        assertEquals(-8L, sbX.immediate());

        Ir64Op.Load64 sbW = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURSB_W);
        assertEquals(Ir64MemSize.BYTE, sbW.size());
        assertTrue(sbW.signExtend());
        assertFalse(sbW.wide());
        assertEquals(-8L, sbW.immediate());

        Ir64Op.Load64 shX = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURSH_X);
        assertEquals(Ir64MemSize.HALF, shX.size());
        assertTrue(shX.signExtend());
        assertTrue(shX.wide());

        Ir64Op.Load64 shW = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURSH_W);
        assertEquals(Ir64MemSize.HALF, shW.size());
        assertTrue(shW.signExtend());
        assertFalse(shW.wide());

        // LDAPURSW só existe estendendo para X (não há forma "para W", size=WORD+opc=11 é reservado).
        Ir64Op.Load64 sw = (Ir64Op.Load64) decode(LRCPC2_DECODER, LDAPURSW_X);
        assertEquals(Ir64MemSize.WORD, sw.size());
        assertTrue(sw.signExtend());
        assertTrue(sw.wide());
        assertEquals(-8L, sw.immediate());
    }

    @Test
    void orderingHasNoObservableEffect() {
        // Mesma decisão herdada de LDAR/STLR/LDAPR sem offset (Aarch64Feature.LRCPC2, Javadoc):
        // acquire/release é NOP observável neste interpretador single-thread — LDAPUR/STLUR viram
        // Load64/Store64 comuns, sem nenhum estado extra de ordenação a testar.
        Ir64Op op = decode(LRCPC2_DECODER, LDAPUR_X);
        assertTrue(op instanceof Ir64Op.Load64);
    }
}
