package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/// B17.17 — o decoder isolado recusa o que não é dos prefixos `0x84`/`0x85`/`0xA4`/`0xA5` (na cadeia real o
/// `Aarch64SveDecoder` só o chama para eles, então só um teste direto alcança o `default`).
class Aarch64SveLoadDecoderTest {
    private final Aarch64SveLoadDecoder decoder = new Aarch64SveLoadDecoder(Aarch64Architecture.ARMV9_0_A);

    @Test
    void foreignPrefixesAreRefused() {
        assertNull(decoder.decode(0x65000000, 0));
        assertNull(decoder.decode(0xE4000000, 0)); // prefixo de store (B17.18)
        assertNull(decoder.decode(0xC4000000, 0)); // gather de 64 bits (B17.19)
    }
}
