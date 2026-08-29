package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.1 — atômicos de memória `FEAT_LSE` (`LDADD`/`LDCLR`/`LDEOR`/`LDSET`/`LDSMAX`/`LDSMIN`/
/// `LDUMAX`/`LDUMIN`/`SWP`, ARMv8.1-A) + `LDAPR`/`LDAPRB`/`LDAPRH` forma registrador
/// (`FEAT_LRCPC`, ARMv8.3-A). Espaço `idx`(bits[11:10])==00 & bit21==1 de
/// {@link Aarch64Decoder#decode} → `decodeLoadStoreSingle` (antes desta task um `throw
/// unsupported` de propósito). Vetores golden conferidos com `aarch64-none-elf-as`/`objdump`
/// (devkitA64, `.arch armv8.3-a`).
class Aarch64AtomicMemoryOpDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder LSE_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_1_A);
    private static final Aarch64Decoder LSE_NO_RCPC_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_2_A);
    private static final Aarch64Decoder RCPC_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);

    // -- golden: aarch64-none-elf-as (.arch armv8.3-a) + objdump --
    private static final int LDADD_W0_W1_X2 = 0xb8200041;
    private static final int LDADDA_X3_X4_X5 = 0xf8a300a4;
    private static final int LDADDL_W6_W7_X8 = 0xb8660107;
    private static final int LDADDAL_X9_X10_X11 = 0xf8e9016a;
    private static final int STADDL_W12_X13 = 0xb86c01bf;       // ldaddl w12, wzr, [x13]
    private static final int LDCLR_W0_W1_X2 = 0xb8201041;
    private static final int LDCLRB_W3_W4_X5 = 0x382310a4;
    private static final int LDCLRH_W6_W7_X8 = 0x78261107;
    private static final int LDEOR_X9_X10_SP = 0xf82923ea;
    private static final int LDSET_W0_W1_X2 = 0xb8203041;
    private static final int LDSMAX_W3_W4_X5 = 0xb82340a4;
    private static final int LDSMIN_X9_X10_X11 = 0xf829516a;
    private static final int LDUMAX_W0_W1_X2 = 0xb8206041;
    private static final int LDUMIN_X3_X4_X5 = 0xf82370a4;
    private static final int SWP_W0_W1_X2 = 0xb8208041;
    private static final int SWPB_W3_W4_X5 = 0x382380a4;
    private static final int SWPAL_X9_X10_X11 = 0xf8e9816a;
    private static final int LDAPR_X0_X1 = 0xf8bfc020;
    private static final int LDAPRB_W2_X3 = 0x38bfc062;
    private static final int LDAPRH_W4_X5 = 0x78bfc0a4;
    private static final int LDADD_WZR_W1_X2 = 0xb83f0041;
    private static final int STADD_W0_X2 = 0xb820005f;          // ldadd w0, wzr, [x2]

    private static final int[] ALL_NINE_LSE = {
        LDADD_W0_W1_X2, LDCLR_W0_W1_X2, LDEOR_X9_X10_SP, LDSET_W0_W1_X2, LDSMAX_W3_W4_X5,
        LDSMIN_X9_X10_X11, LDUMAX_W0_W1_X2, LDUMIN_X3_X4_X5, SWP_W0_W1_X2
    };

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    // ── Gate ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void defaultArchitectureRejectsAllTen() {
        for (int word : ALL_NINE_LSE) {
            assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, word));
        }
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, LDAPR_X0_X1));
    }

    @Test
    void lseGatesTheNineButNotLdapr() {
        for (int word : ALL_NINE_LSE) {
            assertTrue(decode(LSE_DECODER, word) instanceof Ir64Op.AtomicMemoryOp);
        }
        assertThrows(UnsupportedOperationException.class, () -> decode(LSE_NO_RCPC_DECODER, LDAPR_X0_X1));
    }

    @Test
    void lrcpcGatesLdapr() {
        assertTrue(decode(RCPC_DECODER, LDAPR_X0_X1) instanceof Ir64Op.Load64);
    }

    // ── Decode das 9 operações LSE ──────────────────────────────────────────────────────────────

    @Test
    void ldaddWordFields() {
        Ir64Op.AtomicMemoryOp op = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDADD_W0_W1_X2);
        assertEquals(Ir64AtomicOp.ADD, op.operation());
        assertEquals(Ir64MemSize.WORD, op.size());
        assertEquals(0, op.rs());
        assertEquals(1, op.rt());
        assertEquals(2, op.rn());
        assertFalse(op.acquire());
        assertFalse(op.release());
    }

    @Test
    void ldaddAcquireDoubleword() {
        Ir64Op.AtomicMemoryOp op = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDADDA_X3_X4_X5);
        assertEquals(Ir64AtomicOp.ADD, op.operation());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertTrue(op.acquire());
        assertFalse(op.release());
        assertEquals(3, op.rs());
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
    }

    @Test
    void ldaddReleaseAndAcquireRelease() {
        Ir64Op.AtomicMemoryOp rel = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDADDL_W6_W7_X8);
        assertFalse(rel.acquire());
        assertTrue(rel.release());
        Ir64Op.AtomicMemoryOp acqRel = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDADDAL_X9_X10_X11);
        assertTrue(acqRel.acquire());
        assertTrue(acqRel.release());
    }

    @Test
    void staddlIsLdaddWithXzrDestination() {
        Ir64Op.AtomicMemoryOp op = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, STADDL_W12_X13);
        assertEquals(Ir64AtomicOp.ADD, op.operation());
        assertEquals(31, op.rt(), "alias ST<op> = Rt XZR");
        assertEquals(12, op.rs());
        assertEquals(13, op.rn());
    }

    @Test
    void operationByOpcField() {
        assertEquals(Ir64AtomicOp.CLR, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDCLR_W0_W1_X2)).operation());
        assertEquals(Ir64AtomicOp.EOR, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDEOR_X9_X10_SP)).operation());
        assertEquals(Ir64AtomicOp.SET, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDSET_W0_W1_X2)).operation());
        assertEquals(Ir64AtomicOp.SMAX, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDSMAX_W3_W4_X5)).operation());
        assertEquals(Ir64AtomicOp.SMIN, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDSMIN_X9_X10_X11)).operation());
        assertEquals(Ir64AtomicOp.UMAX, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDUMAX_W0_W1_X2)).operation());
        assertEquals(Ir64AtomicOp.UMIN, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDUMIN_X3_X4_X5)).operation());
        assertEquals(Ir64AtomicOp.SWP, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, SWP_W0_W1_X2)).operation());
    }

    @Test
    void sizeByField() {
        assertEquals(Ir64MemSize.BYTE, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDCLRB_W3_W4_X5)).size());
        assertEquals(Ir64MemSize.HALF, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDCLRH_W6_W7_X8)).size());
        assertEquals(Ir64MemSize.WORD, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDADD_W0_W1_X2)).size());
        assertEquals(Ir64MemSize.DOUBLEWORD, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDUMIN_X3_X4_X5)).size());
    }

    @Test
    void swpbAndSwpalCarryO3Bit() {
        assertEquals(Ir64AtomicOp.SWP, ((Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, SWPB_W3_W4_X5)).operation());
        Ir64Op.AtomicMemoryOp swpal = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, SWPAL_X9_X10_X11);
        assertEquals(Ir64AtomicOp.SWP, swpal.operation());
        assertTrue(swpal.acquire());
        assertTrue(swpal.release());
    }

    @Test
    void rsXzrDecodesAsRegister31() {
        Ir64Op.AtomicMemoryOp op = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, LDADD_WZR_W1_X2);
        assertEquals(31, op.rs());
    }

    @Test
    void staddIsLdaddWithXzrDestination() {
        Ir64Op.AtomicMemoryOp op = (Ir64Op.AtomicMemoryOp) decode(LSE_DECODER, STADD_W0_X2);
        assertEquals(31, op.rt());
        assertEquals(0, op.rs());
    }

    // ── LDAPR ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void ldaprDecodesAsPlainLoadNoWriteback() {
        Ir64Op.Load64 op = (Ir64Op.Load64) decode(RCPC_DECODER, LDAPR_X0_X1);
        assertEquals(0, op.rt());
        assertEquals(1, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
        assertFalse(op.signExtend());
        assertTrue(op.wide());
        assertEquals(Ir64AddressingMode.OFFSET, op.addressingMode());
        assertEquals(0L, op.immediate());
    }

    @Test
    void ldaprbAndLdaprhSizes() {
        Ir64Op.Load64 b = (Ir64Op.Load64) decode(RCPC_DECODER, LDAPRB_W2_X3);
        assertEquals(Ir64MemSize.BYTE, b.size());
        assertFalse(b.wide());
        assertEquals(2, b.rt());
        assertEquals(3, b.rn());
        Ir64Op.Load64 h = (Ir64Op.Load64) decode(RCPC_DECODER, LDAPRH_W4_X5);
        assertEquals(Ir64MemSize.HALF, h.size());
        assertFalse(h.wide());
    }

    // ── G8: subespaço não alocado ───────────────────────────────────────────────────────────────

    @Test
    void o3WithLs64OpcodesRejected() {
        // o3=1 + opc ∈ {001,010,011} = LD64B/ST64B/LD64BV/ST64BV0 (FEAT_LS64) — fora de escopo.
        int base = SWP_W0_W1_X2; // o3=1, opc=000
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, base | (0b001 << 12)));
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, base | (0b010 << 12)));
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, base | (0b011 << 12)));
    }

    @Test
    void o3WithReservedOpcodesRejected() {
        int base = SWP_W0_W1_X2;
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, base | (0b101 << 12)));
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, base | (0b110 << 12)));
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, base | (0b111 << 12)));
    }

    @Test
    void malformedLdaprRejected() {
        // LDAPR exige Rs=31, A=1, R=0. Qualquer desvio → UNIMPLEMENTED (não é LDUR/STUR).
        int rsNot31 = LDAPR_X0_X1 & ~(1 << 16); // Rs = 30
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, rsNot31));
        int releaseSet = LDAPR_X0_X1 | (1 << 22); // R = 1
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, releaseSet));
        int acquireClear = LDAPR_X0_X1 & ~(1 << 23); // A = 0
        assertThrows(UnsupportedOperationException.class, () -> decode(RCPC_DECODER, acquireClear));
    }

    @Test
    void atomicSpaceNeverMisdecodedAsLdurStur() {
        // Todos os vetores acima têm bit21=1: o ramo LDUR/STUR (bit21=0) nunca pode produzi-los.
        for (int word : ALL_NINE_LSE) {
            Ir64Op op = decode(RCPC_DECODER, word);
            assertTrue(op instanceof Ir64Op.AtomicMemoryOp);
        }
    }
}
