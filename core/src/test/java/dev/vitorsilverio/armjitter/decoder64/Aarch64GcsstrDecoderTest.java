package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.27 — `GCSSTR`/`GCSSTTR` (`FEAT_GCS`, `ARMv9.4-A`). Encoding montado à mão a partir de
/// `target/isa-decode/a64.decode:588` (`11011001 000 11111 000 unpriv:1 11 rn:5 rt:5`) — nenhuma
/// toolchain `aarch64-*-as` com suporte a `+gcs` disponível neste ambiente (extensão recente).
class Aarch64GcsstrDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder GCS_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV9_4_A);

    // `GCSSTR X0, [X1]` — unpriv=0, rn=1, rt=0.
    private static final int GCSSTR_X0_X1 = 0xD91F0C20;
    // `GCSSTR X5, [SP]` — unpriv=0, rn=31 (SP), rt=5.
    private static final int GCSSTR_X5_SP = 0xD91F0FE5;
    // `GCSSTTR X0, [X1]` — unpriv=1 (forma "unprivileged"), rn=1, rt=0.
    private static final int GCSSTTR_X0_X1 = 0xD91F1C20;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByGuardedControlStackFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, GCSSTR_X0_X1));
    }

    @Test
    void decodesAsPlainDoublewordStoreWithoutOffset() {
        Ir64Op.Store64 store = (Ir64Op.Store64) decode(GCS_DECODER, GCSSTR_X0_X1);
        assertEquals(0, store.rt());
        assertEquals(1, store.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, store.size());
        assertTrue(store.wide());
        assertEquals(Ir64AddressingMode.OFFSET, store.addressingMode());
        assertEquals(0L, store.immediate());
    }

    @Test
    void decodesSpAsBaseRegister() {
        Ir64Op.Store64 store = (Ir64Op.Store64) decode(GCS_DECODER, GCSSTR_X5_SP);
        assertEquals(5, store.rt());
        assertEquals(31, store.rn());
    }

    @Test
    void unprivilegedVariantDecodesTheSameFunctionally() {
        // `GCSSTTR` (unpriv=1): mesma simplificação de `LDTR`/`STTR` — sem modelo de EL0/EL1
        // distinto neste emulador, então o resultado funcional é idêntico ao de `GCSSTR`.
        Ir64Op.Store64 store = (Ir64Op.Store64) decode(GCS_DECODER, GCSSTTR_X0_X1);
        assertEquals(0, store.rt());
        assertEquals(1, store.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, store.size());
    }
}
