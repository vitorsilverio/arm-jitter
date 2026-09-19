package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

import java.util.List;

/// Adaptador que **transforma e delega** os encodings NEON/Advanced SIMD de `neon-dp.decode`
/// (297 linhas) e `neon-ls.decode` (5 linhas) do espaço Thumb-2 de 32 bits para os decoders A32 do
/// épico B13, em vez de reimplementar qualquer semântica (B13.16). Os dois arquivos QEMU dizem, no
/// próprio cabeçalho, que a diferença entre A32 e T32 é puramente mecânica — este decoder aplica
/// exatamente essa transformação.
///
/// **`neon-dp.decode`** (`NeonDataProcessingDecoder`/`NeonShiftImmediateDecoder`/
/// `NeonModifiedImmediateDecoder`/`NeonThreeRegDifferentDecoder`/`NeonTwoRegMiscDecoder`/
/// `NeonExtractTableDuplicateDecoder`, B13.4-B13.15): A32 é `1111_001p_qqqq_qqqq_qqqq_qqqq_qqqq_qqqq`,
/// T32 é `111p_1111_qqqq_qqqq_qqqq_qqqq_qqqq_qqqq` — o bit `p` (tipicamente `U`) muda do bit24 para
/// o bit28, e os 24 bits baixos são idênticos. Reconhecimento do frame T32:
/// `(raw & 0xEF00_0000) == 0xEF00_0000` (bits[31:29]=`111`, bits[27:24]=`1111`, bit28=`p` livre).
///
/// **`neon-ls.decode`** (`NeonLoadStoreDecoder`, B13.3): A32 é `1111_0100_xxx0_xxxx...`, T32 é
/// `1111_1001_xxx0_xxxx...` — aqui os bits[27:24] trocam de `0100` para `1001` (não é um único bit
/// que se move; o byte alto inteiro é substituído), mas os 24 bits baixos continuam idênticos.
/// Reconhecimento do frame T32: `(raw & 0xFF00_0000) == 0xF900_0000`.
///
/// **`neon-shared.decode` (B13.17-B13.21) NÃO passa por aqui** — o cabeçalho daquele arquivo diz
/// que o encoding já é idêntico entre A32 e T32, então não há transformação a fazer (fica para a
/// task que registrar `NeonSharedDecoder` também como extensão Thumb-2, fora do escopo da B13.16).
///
/// **Ordem de delegação dentro de `neon-dp`**: os 6 decoders reservam espaço uns para os outros
/// devolvendo `null` (`NeonShiftImmediateDecoder`↔`NeonModifiedImmediateDecoder` por `immh==0`,
/// `NeonThreeRegDifferentDecoder`↔(`NeonTwoRegMiscDecoder`+`NeonExtractTableDuplicateDecoder`) por
/// `size==0b11`) — a lista abaixo consulta todos, na mesma ordem em que o épico os introduziu, para
/// que a palavra caia sempre no decoder certo.
///
/// **Gate duplo** (Aceite da B13.16): `THUMB2` já é garantido estruturalmente por
/// `ThumbDecoder#tryDecodeThumb32`, que só consulta {@link ArmArchitecture#thumb32DecoderExtensions()}
/// quando a arquitetura tem essa feature (mesmo padrão de todo `Thumb2*Decoder` existente, nenhum
/// deles re-checa `THUMB2`); `ArmFeature#ADVANCED_SIMD` é checado por CADA decoder A32 delegado
/// (todos já fazem isso desde B13.1-B13.15) — sem a feature, todos devolvem `null` e este adaptador
/// devolve `null` também, preservando o zero-diff (nenhum preset declara `ADVANCED_SIMD` até B13.22).
///
/// O `DecodedInstruction` devolvido carrega o `raw` T32 ORIGINAL (não a palavra transformada) e
/// {@link InstructionSet#THUMB} — nunca a palavra A32 sintética usada só para achar o decoder certo
/// (Armadilha 2 da task: o `raw` alimenta traço/exceções/`Fetch`, que dependem do valor real lido da
/// memória do guest).
public final class Thumb2NeonDecoder implements DecoderExtension {
    // ── `neon-dp`: `p` (bit24 em A32) mora no bit28 em T32; bits[23:0] idênticos ──
    private static final int DP_T32_FRAME_MASK = 0xEF00_0000;
    private static final int DP_T32_FRAME_VALUE = 0xEF00_0000;
    private static final int DP_T32_P_BIT = 28;
    private static final int DP_A32_FIXED_BITS = 0xF200_0000;
    private static final int DP_A32_P_SHIFT = 24;

    // ── `neon-ls`: bits[27:24] inteiros trocam de `0100` (A32) para `1001` (T32) ──
    private static final int LS_T32_FRAME_MASK = 0xFF00_0000;
    private static final int LS_T32_FRAME_VALUE = 0xF900_0000;
    private static final int LS_A32_FIXED_BITS = 0xF400_0000;

    private static final int LOW24_BITS_MASK = 0x00FF_FFFF;

    private final List<DecoderExtension> dataProcessingChain;
    private final DecoderExtension loadStore;

    public Thumb2NeonDecoder(ArmArchitecture architecture) {
        this.dataProcessingChain = List.of(
                new NeonDataProcessingDecoder(architecture),
                new NeonShiftImmediateDecoder(architecture),
                new NeonModifiedImmediateDecoder(architecture),
                new NeonThreeRegDifferentDecoder(architecture),
                new NeonTwoRegMiscDecoder(architecture),
                new NeonExtractTableDuplicateDecoder(architecture));
        this.loadStore = new NeonLoadStoreDecoder(architecture);
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        if ((raw & DP_T32_FRAME_MASK) == DP_T32_FRAME_VALUE) {
            int p = (raw >>> DP_T32_P_BIT) & 1;
            int a32 = DP_A32_FIXED_BITS | (p << DP_A32_P_SHIFT) | (raw & LOW24_BITS_MASK);
            return delegate(dataProcessingChain, a32, address, raw);
        }
        if ((raw & LS_T32_FRAME_MASK) == LS_T32_FRAME_VALUE) {
            int a32 = LS_A32_FIXED_BITS | (raw & LOW24_BITS_MASK);
            return relabel(loadStore.tryDecode(a32, address, Condition.AL), raw);
        }
        return null;
    }

    private static DecodedInstruction delegate(List<DecoderExtension> chain, int a32, int address, int originalRaw) {
        for (DecoderExtension extension : chain) {
            DecodedInstruction decoded = extension.tryDecode(a32, address, Condition.AL);
            if (decoded != null) {
                return relabel(decoded, originalRaw);
            }
        }
        return null;
    }

    private static DecodedInstruction relabel(DecodedInstruction decoded, int originalRaw) {
        if (decoded == null) {
            return null;
        }
        return decoded.withRaw(originalRaw).withInstructionSet(InstructionSet.THUMB);
    }
}
