package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.9c: resíduo G8 do espaço que `Aarch64Decoder#decodeDataProcessingScalarFpSimd` /
/// `decodeAdvancedSimdInteger` recebem — palavras que o decoder aceitava e o `objdump` 2.46 do
/// devkitA64 dá como `undefined`. Uma palavra por causa corrigida, ao lado da vizinha legítima (o
/// mesmo encoding com o campo reservado no valor certo), e a impressão do conjunto aceito do espaço
/// inteiro como guarda contra G8 novo.
class AdvSimdFpResidueG8Test {
    /// Todas as features menos SME — com `FEAT_SME` o `decode` embrulharia o op AdvSIMD em
    /// `StreamingRestricted`. Mesmo preset `all` de `e15.9c-scripts/AdvSimdRmOracle.java`.
    private static final Aarch64Architecture ALL_BUT_SME = Aarch64Architecture.of("all",
            Arrays.stream(Aarch64Feature.values())
                    .filter(f -> !f.name().startsWith("SME") && !f.name().startsWith("SCALABLE_MATRIX"))
                    .toArray(Aarch64Feature[]::new));

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        TestAddressSpace raw = new TestAddressSpace(4);
        raw.put32(0, word);
        return decoder.decode(AddressSpace64.wrapping(raw), 0);
    }

    /// Coluna 1 = `undefined` no `objdump`; coluna 2 = a vizinha, como o `objdump` a desmonta.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            9e2708cb | 1e2708cb | fmul s11, s6, s7 — M(bit31)=1 fora das conversões
            9e260108 | 9e660108 | fmov x8, d8 — FMOV X↔S
            5e4603fe | 5e0603fe | sha1c — SHA three-register com size≠00
            7e1d0110 | 5e1d0110 | sha1c — SHA three-register com U=1
            5e5604ac | 5e1604ac | mov h12, v5.h[5] — scalar copy com size≠00
            7e03075c | 5e03075c | mov b28, v26.b[1] — scalar copy com U=1
            2e470153 | 2e070153 | ext v19.8b — EXT com op2≠00
            0ed41926 | 4ed41926 | uzp1 v6.2d — permute .1d
            0e9112a6 | 4e9112a6 | luti2 v6.16b — LUTI com Q=0
            0f880720 | 0f080720 | sshr v0.8b, #8 — shift com bit23=1
            0f400698 | 4f400698 | sshr v24.2d, #64 — shift .1d
            0e33b423 | 0e73b423 | sqdmulh v3.4h — SQDMULH byte
            0ee30777 | 0ea30777 | shadd v23.2s — SHADD doubleword
            0ee08aa9 | 4ee08aa9 | cmgt v9.2d, #0 — two-reg misc .1d
            6ee04863 | 6ea04863 | clz v3.4s — CLZ doubleword
            2ee068c5 | 2ea068c5 | uadalp v5.1d, v6.2s — UADALP doubleword
            0ee2a4c4 | 0ea2a4c4 | smaxp v4.2s — SMAXP doubleword
            0e67c4dd | 4e67c4dd | fmaxnm v29.2d — three same (FP) .1d
            0fce12e3 | 4fce12e3 | fmla v3.2d, v23.2d, v14.d[0] — by element .1d
            0eb1b954 | 4eb1b954 | addv s20, v10.4s — across lanes .2s
            0e30bb08 | 0e31bb08 | addv b8, v24.8b — ADDV com Rm=10000
            0e313b23 | 0e303b23 | saddlv h3, v25.8b — SADDLV com Rm=10001
            4eb1cbb8 | 4eb0cbb8 | fminnmv h24, v29.8h — across lanes (FP) com Rm=10001
            6e70ca54 | 6e30ca54 | fmaxnmv s20, v18.4s — across lanes (FP) com sz=1
            0e39aa6e | 0e79aa6e | fcvtns v14.4h — two-reg misc (FP16) com bit22=0
            2f06f46c | 6f06f46c | fmov v12.2d — FMOV imediato .1d
            2f00ffa9 | 0f00ffa9 | fmov v9.4h — FMOV imediato de meia precisão com op=1
            4e79e95f | 4e21e95f | frint32z v31.4s — FRINT32Z de meia precisão
            0ee16adf | 0ea16adf | bfcvtn v31.4h — BFCVTN com sz=1
            5e70d8dd | 5e30d8dd | faddp h29, v6.2h — scalar pairwise _h com bit22=1
            5e31ca85 | 5e30ca85 | fmaxnmp h5, v20.2h — scalar pairwise (FP) com Rm=10001
            7e216b25 | 7e616b25 | fcvtxn s5, d25 — FCVTXN escalar com size≠01
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_BUT_SME);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }

    @Test
    void bfloat16ConvertNarrowNeedsTheFeature() {
        Aarch64Decoder withoutBf16 = new Aarch64Decoder(Aarch64Architecture.of("sem BF16", Aarch64Feature.FP16));
        int bfcvtn = 0x0ea16adf; // bfcvtn v31.4h, v22.4s
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(withoutBf16, bfcvtn));
    }

    private static final int[] PREFIXES = {0b0_1110, 0b1_1110, 0b0_1111, 0b1_1111};
    private static final int PREFIX_SHIFT = 24;
    private static final int HIGH_BITS_SHIFT = 29;
    private static final int SIZE_BITS_SHIFT = 21;
    private static final int RM_SHIFT = 16;
    private static final int OPCODE_SHIFT = 10;
    private static final int REGISTER_PAIR_BITS = 10;
    /// Conferidos contra o `objdump` 2.46 do devkitA64 em 2026-10-03 (E15.9c): nenhuma das palavras
    /// aceitas é `undefined`.
    private static final int ACCEPTED_COUNT = 69_428;
    private static final String ACCEPTED_SHA256 = "43a6f6a52e32b9bf5c54e55b8f542eab6264d345e2fc2db69a3e3f2a7a227e96";

    /// Guarda permanente do G8 neste espaço (decisão do usuário na E15.9c): enumera o mesmo espaço de
    /// `e15.9c-scripts/AdvSimdRmOracle.java` (os 4 prefixos de bits[28:24]; bits 31..29, 23..21,
    /// 20:16 e 15:10 enumerados; `Rn`/`Rd` da mesma semente) e compara contagem + SHA-256 das palavras
    /// ACEITAS. Se falhar, o conjunto aceito mudou: rodar o oráculo e
    /// `e15.9b-scripts/objdump-residuo.sh` — 0 `undefined` (ou cada sobra justificada) — e só então
    /// atualizar as duas constantes.
    @Test
    void acceptedSpaceMatchesTheObjdumpVerifiedFingerprint() throws Exception {
        Aarch64Decoder decoder = new Aarch64Decoder(ALL_BUT_SME);
        SplittableRandom random = new SplittableRandom(0xE159CL);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
        int accepted = 0;
        for (int prefix : PREFIXES) {
            for (int high = 0; high < 8; high++) {
                for (int size = 0; size < 8; size++) {
                    for (int rm = 0; rm < 32; rm++) {
                        for (int opcode = 0; opcode < 64; opcode++) {
                            int regs = random.nextInt(1 << REGISTER_PAIR_BITS);
                            int word = (high << HIGH_BITS_SHIFT) | (prefix << PREFIX_SHIFT) | (size << SIZE_BITS_SHIFT)
                                    | (rm << RM_SHIFT) | (opcode << OPCODE_SHIFT) | regs;
                            try {
                                decodeWord(decoder, word);
                            } catch (UnsupportedOperationException e) {
                                continue;
                            }
                            accepted++;
                            digest.update(buffer.clear().putInt(word).array());
                        }
                    }
                }
            }
        }
        assertEquals(ACCEPTED_COUNT, accepted);
        assertEquals(ACCEPTED_SHA256, HexFormat.of().formatHex(digest.digest()));
    }
}
