package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/// B17.19 — o decoder isolado recusa o que não é dos prefixos `0x84`/`0x85`/`0xC4`/`0xC5` (na cadeia real o
/// `Aarch64SveDecoder` só o chama para eles, então só um teste direto alcança a guarda de prefixo).
class Aarch64SveGatherDecoderTest {
    private final Aarch64SveGatherDecoder decoder = new Aarch64SveGatherDecoder(Aarch64Architecture.ARMV9_0_A);

    @Test
    void foreignPrefixesAreRefused() {
        assertNull(decoder.decode(0x65000000, 0));
        assertNull(decoder.decode(0xA4034440, 0)); // load contíguo (B17.17)
        assertNull(decoder.decode(0xE4034440, 0)); // store (B17.18)
    }
}
