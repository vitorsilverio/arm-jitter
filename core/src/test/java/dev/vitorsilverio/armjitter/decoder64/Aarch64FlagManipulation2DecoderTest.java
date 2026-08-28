package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64FlagConversionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `CFINV` (`FEAT_FlagM`, `ARMv8.4-A`) + `XAFLAG`/`AXFLAG` (`FEAT_FlagM2`, `ARMv8.5-A`) — quinto gate
/// real de {@link Aarch64Architecture#has} no {@link Aarch64Decoder} (B11.9; os anteriores foram
/// `FEAT_RDM`/B11.4, `FEAT_WFxT`/B11.6, `FEAT_FlagM`(`RMIF`/`SETF8`/`SETF16`)/B11.7,
/// `FEAT_NMI`/B11.8). As 3 instruções já decodificavam com SUCESSO incondicionalmente desde B8.2 —
/// **achado real desta task**: `CFINV` nunca tinha sido gateado por B11.7 (que só tocou
/// `decodeAddSubtractCarryOrFlags`, um método diferente de onde `CFINV` vive), corrigido aqui junto
/// com `XAFLAG`/`AXFLAG` por compartilharem o MESMO `switch` em
/// `Aarch64Decoder#decodeFlagOrPstateImmediate`. Corpus REAL já existente em
/// `src/test/resources/aarch64/corpus.bin` (offsets `0x5a8`/`0x5ac`/`0x5b0`, mesmas palavras que
/// `Aarch64DecoderCorpusTest#cfinv`/`#xaflag`/`#axflag` validavam antes desta task).
class Aarch64FlagManipulation2DecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder();
    private static final Aarch64Decoder FLAGM_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_4_A);
    private static final Aarch64Decoder FLAGM2_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_5_A);

    private static final int CFINV_WORD = 0xd500401f;
    private static final int XAFLAG_WORD = 0xd500403f;
    private static final int AXFLAG_WORD = 0xd500405f;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void rejectedByDefaultArchitectureWithoutFlagM() {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, CFINV_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, XAFLAG_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(DEFAULT_DECODER, AXFLAG_WORD));
    }

    @Test
    void armv84AcceptsCfinvButRejectsFlagM2() {
        // ARMv8.4-A tem FEAT_FlagM (CFINV) mas NÃO FEAT_FlagM2 (XAFLAG/AXFLAG) — prova que as duas
        // features são distintas, não uma implicando a outra ao contrário.
        Ir64Op.ConvertFlags op = (Ir64Op.ConvertFlags) decodeWord(FLAGM_DECODER, CFINV_WORD);
        assertEquals(Ir64FlagConversionOp.INVERT_CARRY, op.opcode());
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FLAGM_DECODER, XAFLAG_WORD));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FLAGM_DECODER, AXFLAG_WORD));
    }

    @Test
    void cfinvDecodesWithFlagM2() {
        Ir64Op.ConvertFlags op = (Ir64Op.ConvertFlags) decodeWord(FLAGM2_DECODER, CFINV_WORD);
        assertEquals(Ir64FlagConversionOp.INVERT_CARRY, op.opcode());
    }

    @Test
    void xaflagDecodesWithFlagM2() {
        Ir64Op.ConvertFlags op = (Ir64Op.ConvertFlags) decodeWord(FLAGM2_DECODER, XAFLAG_WORD);
        assertEquals(Ir64FlagConversionOp.EXTERNAL_TO_ARM, op.opcode());
    }

    @Test
    void axflagDecodesWithFlagM2() {
        Ir64Op.ConvertFlags op = (Ir64Op.ConvertFlags) decodeWord(FLAGM2_DECODER, AXFLAG_WORD);
        assertEquals(Ir64FlagConversionOp.ARM_TO_EXTERNAL, op.opcode());
    }
}
