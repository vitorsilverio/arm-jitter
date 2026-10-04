package dev.vitorsilverio.armjitter.decoder64;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.ALL;
import static dev.vitorsilverio.armjitter.decoder64.BranchExceptionRowsTest.decodeWord;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// E15.11: guarda permanente do G8 na classe branch/exceção/sistema (padrão da E15.9c/E15.10) — contagem +
/// SHA-256 das palavras ACEITAS, com todas as features, no espaço de `e15.11-scripts/BranchSystemOracle.java`
/// com os registradores FIXOS em vez de sorteados:
///
/// - a classe inteira: bits 31:29 e 25:12 enumerados, 11:0 em zero;
/// - o espaço de sistema `1101010100`: bits 21:5 enumerados × `Rt` ∈ {31, 0};
/// - exceção: `opc` × `bits[4:0]`, `imm16=0`;
/// - branch registrador: bits 24:10 enumerados × `Rn` ∈ {31, 0} × `op4` ∈ {0, 31, 1}.
///
/// Conferido em 2026-10-04: nenhuma palavra aceita fica sem padrão no `a64.decode` do QEMU
/// (`e15.11-scripts/QemuReference.java`) nem é `undefined` no `objdump` 2.46 do devkitA64. Se falhar, o
/// conjunto aceito mudou: refazer as duas conferências e só então atualizar as constantes. O `objdump` NÃO
/// basta em `op0=00` (imprime `msr s0_...` para palavra não alocada).
class BranchExceptionSystemResidueG8Test {
    private static final int CLASS_BITS = 0b101 << 26;
    private static final int SYSTEM_SPACE = 0xD5000000;
    private static final int EXCEPTION_SPACE = 0xD4000000;
    private static final int BRANCH_REGISTER_SPACE = 0xD6000000;
    private static final int ZR = 31;
    private static final int ACCEPTED_COUNT = 209_252;
    private static final String ACCEPTED_SHA256 = "eb591e37a6fc396b967aca37911f32142eb7c18564335645ce89b58ec857873b";

    /// As palavras do espaço, na ordem do oráculo (com repetição entre os sub-espaços — não muda a impressão).
    static void forEachWord(IntConsumer action) {
        for (int i = 0; i < 1 << 17; i++) {
            action.accept((i >>> 14) << 29 | CLASS_BITS | (i & 0x3FFF) << 12);
        }
        for (int i = 0; i < 1 << 17; i++) {
            action.accept(SYSTEM_SPACE | i << 5 | ZR);
            action.accept(SYSTEM_SPACE | i << 5);
        }
        for (int opc = 0; opc < 8; opc++) {
            for (int low = 0; low < 32; low++) {
                action.accept(EXCEPTION_SPACE | opc << 21 | low);
            }
        }
        for (int i = 0; i < 1 << 15; i++) {
            for (int rn : new int[] {ZR, 0}) {
                for (int op4 : new int[] {0, ZR, 1}) {
                    action.accept(BRANCH_REGISTER_SPACE | i << 10 | rn << 5 | op4);
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
