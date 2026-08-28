package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `CAS`/`CASP` (`FEAT_LSE`, `ARMv8.1-A`, B11.11) — sétimo gate real de
/// {@link Aarch64Architecture#has} no {@link Aarch64Decoder} (o primeiro foi `FEAT_RDM`, B11.4).
/// Igual a `FEAT_WFxT` (B11.6): estas 6 formas já decodificavam com SUCESSO incondicionalmente
/// desde B8.1 — o gate agora faz o decoder DEFAULT (`ARMv8.0-A`) passar a rejeitá-las (comportamento
/// correto: um Cortex-A53 real não tem `FEAT_LSE`), continuando a aceitar só com `ARMv8.1-A`+.
/// Corpus REAL já existente em `src/test/resources/aarch64/corpus.bin`
/// (`corpus.objdump.txt` linhas 338-343, offsets `0x518`-`0x52c`).
class Aarch64FeatureGateLseDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder LSE_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_1_A);

    private static final int CAS_W0_W1 = 0x88a07c41;
    private static final int CAS_X3_X4 = 0xc8a37ca4;
    private static final int CASB_W6_W7 = 0x08a67d07;
    private static final int CASH_W9_W10 = 0x48a97d6a;
    private static final int CASP_W12_W13 = 0x082c7e0e;
    private static final int CASPA_X18_X19 = 0x48727ed4;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutLse() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CAS_W0_W1));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CAS_X3_X4));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CASB_W6_W7));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CASH_W9_W10));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CASP_W12_W13));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CASPA_X18_X19));
    }

    @Test
    void casWordDecodesWithLse() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) decodeWord(LSE_DECODER, CAS_W0_W1);
        assertEquals(0, op.rs());
        assertEquals(1, op.rt());
        assertEquals(2, op.rn());
        assertEquals(Ir64MemSize.WORD, op.size());
    }

    @Test
    void casDoublewordDecodesWithLse() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) decodeWord(LSE_DECODER, CAS_X3_X4);
        assertEquals(3, op.rs());
        assertEquals(4, op.rt());
        assertEquals(5, op.rn());
        assertEquals(Ir64MemSize.DOUBLEWORD, op.size());
    }

    @Test
    void casbByteDecodesWithLse() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) decodeWord(LSE_DECODER, CASB_W6_W7);
        assertEquals(Ir64MemSize.BYTE, op.size());
    }

    @Test
    void cashHalfDecodesWithLse() {
        Ir64Op.CompareAndSwap op = (Ir64Op.CompareAndSwap) decodeWord(LSE_DECODER, CASH_W9_W10);
        assertEquals(Ir64MemSize.HALF, op.size());
    }

    @Test
    void caspWordPairDecodesWithLse() {
        Ir64Op.CompareAndSwapPair op = (Ir64Op.CompareAndSwapPair) decodeWord(LSE_DECODER, CASP_W12_W13);
        assertEquals(12, op.rs());
        assertEquals(14, op.rt());
        assertEquals(16, op.rn());
        assertFalse(op.wide());
    }

    @Test
    void caspaDoublewordPairDecodesWithLse() {
        Ir64Op.CompareAndSwapPair op = (Ir64Op.CompareAndSwapPair) decodeWord(LSE_DECODER, CASPA_X18_X19);
        assertEquals(18, op.rs());
        assertEquals(20, op.rt());
        assertEquals(22, op.rn());
        assertTrue(op.wide());
    }
}
