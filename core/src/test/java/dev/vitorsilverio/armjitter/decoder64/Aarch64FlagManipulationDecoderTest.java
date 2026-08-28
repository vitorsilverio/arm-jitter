package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `RMIF`/`SETF8`/`SETF16` (`FEAT_FlagM`, `ARMv8.4-A`, B11.7) — terceiro gate real de
/// {@link Aarch64Architecture#has} no {@link Aarch64Decoder} (o primeiro foi `FEAT_RDM`, B11.4; o
/// segundo `FEAT_WFxT`, B11.6). Diferente de `FEAT_RDM`, estas 3 instruções já decodificavam com
/// SUCESSO incondicionalmente desde B8.2 — o gate agora faz o decoder DEFAULT (`ARMv8.0-A`) passar a
/// rejeitá-las (comportamento correto: um Cortex-A53 real não tem `FEAT_FlagM`), continuando a
/// aceitar só com `ARMv8.4-A`+. Corpus REAL já existente em
/// `src/test/resources/aarch64/corpus.bin` (offsets `0x59c`/`0x5a0`/`0x5a4`, mesmas palavras que
/// `Aarch64DecoderCorpusTest#rmif`/`#setf8`/`#setf16` validavam antes desta task).
class Aarch64FlagManipulationDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder FLAGM_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_4_A);

    private static final int RMIF_WORD = 0xba020405;
    private static final int SETF8_WORD = 0x3a00082d;
    private static final int SETF16_WORD = 0x3a00484d;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutFlagM() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, RMIF_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, SETF8_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, SETF16_WORD));
    }

    @Test
    void rmifDecodesWithFlagM() {
        Ir64Op.RotateIntoFlags op = (Ir64Op.RotateIntoFlags) decodeWord(FLAGM_DECODER, RMIF_WORD);
        assertEquals(0, op.rn());
        assertEquals(4, op.shift());
        assertEquals(5, op.mask());
    }

    @Test
    void setf8DecodesWithFlagM() {
        Ir64Op.EvaluateIntoFlags op = (Ir64Op.EvaluateIntoFlags) decodeWord(FLAGM_DECODER, SETF8_WORD);
        assertEquals(1, op.rn());
        assertEquals(8, op.sizeBits());
    }

    @Test
    void setf16DecodesWithFlagM() {
        Ir64Op.EvaluateIntoFlags op = (Ir64Op.EvaluateIntoFlags) decodeWord(FLAGM_DECODER, SETF16_WORD);
        assertEquals(2, op.rn());
        assertEquals(16, op.sizeBits());
    }
}
