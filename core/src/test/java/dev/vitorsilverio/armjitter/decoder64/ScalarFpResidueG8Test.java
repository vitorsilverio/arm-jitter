package dev.vitorsilverio.armjitter.decoder64;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.ALL;
import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.decodeWord;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// E15.14: guarda permanente do G8 no FP escalar (padrão da E15.9c/E15.12/E15.13) — contagem + SHA-256 das
/// palavras ACEITAS, com todas as features, no espaço de `e15.14-scripts/FpScalarOracle.java` com os
/// registradores FIXOS em vez de sorteados: `bit31`, `type`, `bit21`, `bits[20:16]` e `bits[15:10]` enumerados,
/// `Rn` ∈ {0, 31}, `Rd` ∈ {`00000`, `01000`, `10000`, `11000`, `00101`}; 3-source com `M`×`type`×`o1`×`o0`
/// enumerados e `Rm`/`Ra`/`Rn`/`Rd` fixos.
///
/// Conferido em 2026-10-06: nenhuma palavra aceita fica sem padrão no `a64.decode` do QEMU
/// (`e15.14-scripts/QemuReference.java`) e nenhuma é `undefined` no `objdump` 2.46 do devkitA64. Se falhar, o
/// conjunto aceito mudou: refazer as duas conferências e só então atualizar as constantes.
class ScalarFpResidueG8Test {
    private static final int SCALAR_FP_PREFIX = 0b001_1110 << 24;
    private static final int THREE_SOURCE_PREFIX = 0b0001_1111 << 24;
    private static final int TOP_BITS = 4;
    private static final int SF_SHIFT = 31;
    private static final int TYPE_BIT21_SHIFT = 21;
    private static final int TYPE_BIT21_MASK = 0b111;
    private static final int TYPE_SHIFT = 22;
    private static final int TYPE_VALUES = 4;
    private static final int O1_SHIFT = 21;
    private static final int O0_SHIFT = 15;
    private static final int RM_SHIFT = 16;
    private static final int RM_VALUES = 32;
    private static final int MIDDLE_SHIFT = 10;
    private static final int MIDDLE_BITS = 6;
    private static final int RN_SHIFT = 5;
    private static final int ZR = 31;
    private static final int[] RD_CHOICES = {0b00000, 0b01000, 0b10000, 0b11000, 0b00101};
    /// `Rm`/`Ra`/`Rn`/`Rd` do 3-source: `v2`, `v3`, `v1`, `v0`.
    private static final int THREE_SOURCE_REGISTERS = 2 << RM_SHIFT | 3 << MIDDLE_SHIFT | 1 << RN_SHIFT;
    private static final int ACCEPTED_COUNT = 37_960;
    private static final String ACCEPTED_SHA256 = "25c73a5ed07a28c9211d3f9dc2b2887dbcbffd4e1c01c900e8b89f80b3a0ef18";

    /// As palavras do espaço, na ordem do oráculo.
    static void forEachWord(IntConsumer action) {
        for (int top = 0; top < 1 << TOP_BITS; top++) {
            int high = SCALAR_FP_PREFIX | (top >>> 3) << SF_SHIFT | (top & TYPE_BIT21_MASK) << TYPE_BIT21_SHIFT;
            for (int rm = 0; rm < RM_VALUES; rm++) {
                for (int middle = 0; middle < 1 << MIDDLE_BITS; middle++) {
                    for (int rn : new int[] {0, ZR}) {
                        for (int rd : RD_CHOICES) {
                            action.accept(high | rm << RM_SHIFT | middle << MIDDLE_SHIFT | rn << RN_SHIFT | rd);
                        }
                    }
                }
            }
        }
        for (int m = 0; m < 2; m++) {
            for (int type = 0; type < TYPE_VALUES; type++) {
                for (int o1 = 0; o1 < 2; o1++) {
                    for (int o0 = 0; o0 < 2; o0++) {
                        action.accept(THREE_SOURCE_PREFIX | m << SF_SHIFT | type << TYPE_SHIFT | o1 << O1_SHIFT
                                | o0 << O0_SHIFT | THREE_SOURCE_REGISTERS);
                    }
                }
            }
        }
    }

    @Test
    void acceptedSpaceMatchesTheVerifiedFingerprint() throws Exception {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
        int[] accepted = {0};
        forEachWord(word -> {
            try {
                decodeWord(decoder, word);
            } catch (UnsupportedOperationException refused) {
                return;
            }
            accepted[0]++;
            digest.update(buffer.clear().putInt(word).array());
        });
        String sha = HexFormat.of().formatHex(digest.digest());
        assertEquals(ACCEPTED_COUNT + " " + ACCEPTED_SHA256, accepted[0] + " " + sha);
    }
}
