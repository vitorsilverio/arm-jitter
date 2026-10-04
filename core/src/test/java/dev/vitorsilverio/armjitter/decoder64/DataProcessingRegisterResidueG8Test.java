package dev.vitorsilverio.armjitter.decoder64;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.ALL;
import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.decodeWord;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// E15.13: guarda permanente do G8 na classe "Data Processing — Register" (padrão da E15.9c/E15.12) — contagem +
/// SHA-256 das palavras ACEITAS, com todas as features, no espaço de `e15.13-scripts/DpRegisterOracle.java` com
/// os registradores FIXOS em vez de sorteados: os 8 bits livres de `bits[31:21]` × `bits[15:10]` enumerados,
/// `Rm` ∈ {0, 1, 31}, `Rn` ∈ {31, 1}, `Rd` ∈ {`01101`, 31, 0}.
///
/// Conferido em 2026-10-04: nenhuma palavra aceita fica sem padrão no `a64.decode` do QEMU
/// (`e15.13-scripts/QemuReference.java`) e nenhuma é `undefined` no `objdump` 2.46 do devkitA64. Se falhar, o
/// conjunto aceito mudou: refazer as duas conferências e só então atualizar as constantes.
class DataProcessingRegisterResidueG8Test {
    private static final int CLASS_BITS = 0b101 << 25;
    private static final int[] FREE_TOP_BITS = {31, 30, 29, 28, 24, 23, 22, 21};
    private static final int MIDDLE_SHIFT = 10;
    private static final int MIDDLE_BITS = 6;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int ZR = 31;
    private static final int SETF_RD = 0b01101;
    private static final int ACCEPTED_COUNT = 91_222;
    private static final String ACCEPTED_SHA256 = "9eb31f1b08090b0fd37f4347f82cedd645c453f8cb34bc5998bb4b16890f4302";

    /// As palavras do espaço, na ordem do oráculo.
    static void forEachWord(IntConsumer action) {
        for (int top = 0; top < 1 << FREE_TOP_BITS.length; top++) {
            int high = CLASS_BITS;
            for (int i = 0; i < FREE_TOP_BITS.length; i++) {
                high |= ((top >>> i) & 1) << FREE_TOP_BITS[i];
            }
            for (int middle = 0; middle < 1 << MIDDLE_BITS; middle++) {
                for (int rm : new int[] {0, 1, ZR}) {
                    for (int rn : new int[] {ZR, 1}) {
                        for (int rd : new int[] {SETF_RD, ZR, 0}) {
                            action.accept(high | middle << MIDDLE_SHIFT | rm << RM_SHIFT | rn << RN_SHIFT | rd);
                        }
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
