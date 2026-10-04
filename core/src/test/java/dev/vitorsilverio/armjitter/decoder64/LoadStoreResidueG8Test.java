package dev.vitorsilverio.armjitter.decoder64;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.ALL;
import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.decodeWord;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// E15.12: guarda permanente do G8 na classe Loads and Stores (padrão da E15.9c/E15.11) — contagem + SHA-256 das
/// palavras ACEITAS, com todas as features, no espaço de `e15.12-scripts/LoadStoreOracle.java` com os
/// registradores FIXOS em vez de sorteados: os 9 bits livres de `bits[31:21]` × `bits[15:10]` enumerados,
/// `bits[20:16]` ∈ {0, 31}, `Rt` ∈ {31, 0, 1}, `Rn=0`.
///
/// Conferido em 2026-10-04: nenhuma palavra aceita fica sem padrão no `a64.decode` do QEMU
/// (`e15.12-scripts/QemuReference.java`); as únicas `undefined` no `objdump` 2.46 do devkitA64 são os
/// `CONSTRAINED UNPREDICTABLE` de registrador (`LDP` com `Rt==Rt2`/writeback sobre `Rn`, `CPY*`/`SET*` com
/// registrador repetido ou `31`), que o QEMU aceita. Se falhar, o conjunto aceito mudou: refazer as duas
/// conferências e só então atualizar as constantes.
class LoadStoreResidueG8Test {
    private static final int CLASS_BITS = 1 << 27;
    private static final int[] FREE_TOP_BITS = {31, 30, 29, 28, 26, 24, 23, 22, 21};
    private static final int MIDDLE_SHIFT = 10;
    private static final int MIDDLE_BITS = 6;
    private static final int RM_SHIFT = 16;
    private static final int ZR = 31;
    private static final int ACCEPTED_COUNT = 97_433;
    private static final String ACCEPTED_SHA256 = "61a848795552df9a8d5e9ee4fdbd7780eb919edabbbc38d0d3e69e6a14c95e88";

    /// As palavras do espaço, na ordem do oráculo.
    static void forEachWord(IntConsumer action) {
        for (int top = 0; top < 1 << FREE_TOP_BITS.length; top++) {
            int high = CLASS_BITS;
            for (int i = 0; i < FREE_TOP_BITS.length; i++) {
                high |= ((top >>> i) & 1) << FREE_TOP_BITS[i];
            }
            for (int middle = 0; middle < 1 << MIDDLE_BITS; middle++) {
                for (int rm : new int[] {0, ZR}) {
                    for (int rt : new int[] {ZR, 0, 1}) {
                        action.accept(high | middle << MIDDLE_SHIFT | rm << RM_SHIFT | rt);
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
