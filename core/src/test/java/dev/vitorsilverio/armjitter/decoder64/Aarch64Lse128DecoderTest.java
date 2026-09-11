package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.25 — `LDCLRP`/`LDSETP`/`SWPP` (`FEAT_LSE128`, ARMv9.4-A). Vetores golden conferidos com
/// `aarch64-linux-gnu-as`/`objdump` (WSL Ubuntu, `-march=armv9.4-a+lse128`): `ldclrp x2, x3, [x5]`,
/// `ldclrp x0, x1, [sp]`, `ldsetp x2, x3, [x5]`, `swpp x2, x3, [x5]`, `ldclrpa/l/al x2, x3, [x5]`.
class Aarch64Lse128DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder ARMV8_9_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_9_A);
    private static final Aarch64Decoder LSE128_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_4_A);

    private static final int LDCLRP = 0x192310a2;
    private static final int LDCLRP_SP = 0x192113e0;
    private static final int LDSETP = 0x192330a2;
    private static final int SWPP = 0x192380a2;
    private static final int LDCLRPA = 0x19a310a2;
    private static final int LDCLRPL = 0x196310a2;
    private static final int LDCLRPAL = 0x19e310a2;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByLse128FeatureNotByMemoryCopySet() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, LDCLRP));
        // ARMv8.9-A estende ARMV8_8_A (que já tem FEAT_MOPS) mas NÃO tem FEAT_LSE128 — a correção
        // de versão do Javadoc de `Aarch64Feature.LSE128` (E12) fica confirmada aqui: a versão
        // certa recusa em ARMv8.9-A, só aceitando a partir de ARMv9.4-A.
        assertThrows(UnsupportedOperationException.class, () -> decode(ARMV8_9_DECODER, LDCLRP));
        assertThrows(UnsupportedOperationException.class, () -> decode(ARMV8_9_DECODER, LDSETP));
        assertThrows(UnsupportedOperationException.class, () -> decode(ARMV8_9_DECODER, SWPP));
    }

    @Test
    void decodesLdclrpWithCorrectRegisters() {
        Ir64Op.AtomicMemoryOpPair op = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, LDCLRP);
        assertEquals(2, op.rt());
        assertEquals(3, op.rt2());
        assertEquals(5, op.rn());
        assertEquals(Ir64AtomicOp.CLR, op.operation());
        assertFalse(op.acquire());
        assertFalse(op.release());
    }

    @Test
    void decodesLdclrpWithStackPointerBase() {
        Ir64Op.AtomicMemoryOpPair op = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, LDCLRP_SP);
        assertEquals(0, op.rt());
        assertEquals(1, op.rt2());
        assertEquals(31, op.rn()); // 31 = SP neste encoding (base de endereço)
    }

    @Test
    void decodesLdsetpAndSwpp() {
        Ir64Op.AtomicMemoryOpPair set = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, LDSETP);
        assertEquals(Ir64AtomicOp.SET, set.operation());
        assertEquals(2, set.rt());
        assertEquals(3, set.rt2());

        Ir64Op.AtomicMemoryOpPair swp = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, SWPP);
        assertEquals(Ir64AtomicOp.SWP, swp.operation());
        assertEquals(2, swp.rt());
        assertEquals(3, swp.rt2());
    }

    @Test
    void decodesAcquireReleaseBitsIndependently() {
        Ir64Op.AtomicMemoryOpPair a = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, LDCLRPA);
        assertTrue(a.acquire());
        assertFalse(a.release());

        Ir64Op.AtomicMemoryOpPair l = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, LDCLRPL);
        assertFalse(l.acquire());
        assertTrue(l.release());

        Ir64Op.AtomicMemoryOpPair al = (Ir64Op.AtomicMemoryOpPair) decode(LSE128_DECODER, LDCLRPAL);
        assertTrue(al.acquire());
        assertTrue(al.release());
    }

    @Test
    void rejectsXzrAndSameRegisterPair() {
        // Encoding cru com rt=31 (XZR): `ldclrp x2, x3, [x5]` com bits[4:0]=11111 em vez de rt=2.
        int rtIsXzr = (LDCLRP & ~0b1_1111) | 0b1_1111;
        assertThrows(UnsupportedOperationException.class, () -> decode(LSE128_DECODER, rtIsXzr));

        // rt2=31: zera bits[20:16] e seta todos (0x1F << 16).
        int rt2IsXzr = LDCLRP | (0b1_1111 << 16);
        assertThrows(UnsupportedOperationException.class, () -> decode(LSE128_DECODER, rt2IsXzr));

        // rt == rt2: força rt2=2 (mesmo valor de rt).
        int sameRegister = (LDCLRP & ~(0b1_1111 << 16)) | (2 << 16);
        assertThrows(UnsupportedOperationException.class, () -> decode(LSE128_DECODER, sameRegister));
    }

    @Test
    void reservedOpcodeFieldStaysUnsupported() {
        // bits[15:10] fora de {000100, 001100, 100000} — ex.: 000000 — é reservado (G8).
        int reserved = LDCLRP & ~(0b11_1111 << 10);
        assertThrows(UnsupportedOperationException.class, () -> decode(LSE128_DECODER, reserved));
    }
}
