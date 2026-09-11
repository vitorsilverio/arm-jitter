package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B19.29 — `FJCVTZS` (`FEAT_JSCVT`). Encoding base `0x1E7E0000` (`FJCVTZS W0, D0`) conferido
/// contra a base conhecida do opcode (`sf=0`, `type=DOUBLE`, `opcode(21:16)=0b111110`,
/// `suffix(15:10)=000000`); `Rn`/`Rd` somam `(Rn<<5)|Rd` sobre essa base, mesmo esquema de
/// `@rr` usado pelo restante do grupo "Conversion between floating-point and integer".
class Aarch64JscvtDecoderTest {
    private static final Aarch64Decoder DEFAULT_DECODER = new Aarch64Decoder(); // ARMv8.0-A
    private static final Aarch64Decoder JSCVT_DECODER = new Aarch64Decoder(Aarch64Architecture.ARMV8_3_A);

    private static final int FJCVTZS_W0_D0 = 0x1E7E0000;
    private static final int FJCVTZS_W0_D1 = 0x1E7E0020;

    private static Ir64Op decode(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    @Test
    void gatedByJavascriptConvertFeature() {
        assertThrows(UnsupportedOperationException.class, () -> decode(DEFAULT_DECODER, FJCVTZS_W0_D0));
    }

    @Test
    void decodesRegisters() {
        Ir64Op.Fp64JavascriptConvert op = (Ir64Op.Fp64JavascriptConvert) decode(JSCVT_DECODER, FJCVTZS_W0_D1);
        assertEquals(0, op.rd());
        assertEquals(1, op.rn());
    }

    @Test
    void singlePrecisionFormDoesNotExist() {
        // type(23:22) forçado para SINGLE (00) em vez de DOUBLE (01, bit22 zerado) — não existe
        // forma de precisão simples para FJCVTZS (parte fixa do encoding, não escolha genérica).
        int singlePrecisionType = FJCVTZS_W0_D0 & ~(1 << 22);
        assertThrows(UnsupportedOperationException.class, () -> decode(JSCVT_DECODER, singlePrecisionType));
    }

    @Test
    void sixtyFourBitFormDoesNotExist() {
        // sf(31) forçado para 1 — FJCVTZS só existe para Wd (32 bits), não Xd.
        int wideForm = FJCVTZS_W0_D0 | (1 << 31);
        assertThrows(UnsupportedOperationException.class, () -> decode(JSCVT_DECODER, wideForm));
    }
}
