package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/// B17.15 — o decoder isolado (sem a cadeia do `Aarch64SveDecoder`) recusa o que não é do grupo: na cadeia real os
/// decoders da B17.13/B17.14 já consomem o espaço com o bit 21 ligado, então só um teste direto o alcança.
class Aarch64SveFpCompareReduceDecoderTest {
    private final Aarch64SveFpCompareReduceDecoder decoder =
            new Aarch64SveFpCompareReduceDecoder(Aarch64Architecture.ARMV9_0_A);

    @Test
    void bit21SetIsTheMultiplyAddSpaceNotACompare() {
        assertNull(decoder.decodePrefix65(0x65a04861, 0)); // FMLA predicado: opcode 010 mas bit 21 = 1
    }

    @Test
    void unallocatedCompareOpcodesAreRefused() {
        assertNull(decoder.decodePrefix65(0x6580a061, 0)); // 15:13 = 101
        assertNull(decoder.decodePrefix65(0x6580e061, 0)); // 111 com bit 4 = 0
        assertNull(decoder.decodePrefix65(0x65800061, 0)); // 000
    }
}
