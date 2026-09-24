package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Casca fina que reusa {@link NeonSharedDecoder} para o espaço Thumb-2 de 32 bits (B13.22): o
/// próprio cabeçalho de `neon-shared.decode` (QEMU real) diz que o encoding é **idêntico** entre A32
/// e T32 — ao contrário de `neon-dp`/`neon-ls` (B13.16, que precisam transformar o `raw` antes de
/// delegar), aqui não há bit nenhum para mover. Mesmo raciocínio de {@link Thumb2VfpDecoder} reusando
/// {@link VfpDecoder} sem transformação: o `raw` Thumb-2 já chega com o MESMO layout de bits que
/// {@link NeonSharedDecoder} espera (bits[31:28] fixo `1111`/`1110`, nunca lido como condição).
///
/// A única correção necessária é a marca de {@link InstructionSet}: {@link NeonSharedDecoder} sempre
/// devolve {@link InstructionSet#ARM} — esta casca reescreve para {@link InstructionSet#THUMB}.
///
/// **Sem checagem de `null` do delegate** (ao contrário de {@link Thumb2VfpDecoder}, que precisa
/// dela porque {@link VfpDecoder} pode mesmo devolver `null`): {@link NeonSharedDecoder} NUNCA
/// devolve `null` — a B13.21 fechou o "null debt" do arquivo (23 linhas de `neon-shared.decode`)
/// fazendo qualquer `raw` que não bata em nenhuma linha cair em `unimplemented(...)` explícito (G8).
/// Um `if (decoded == null)` aqui seria código morto (achado do JaCoCo desta task, B13.22) — quem
/// registra este decoder numa {@link ArmArchitecture} precisa colocá-lo por ÚLTIMO na lista de
/// {@link DecoderExtension}, senão nenhum decoder depois dele é alcançado (ver o Javadoc de
/// {@link ArmArchitecture#ARMV7A_NEON}).
public final class Thumb2NeonSharedDecoder implements DecoderExtension {
    private final NeonSharedDecoder delegate;

    public Thumb2NeonSharedDecoder(ArmArchitecture architecture) {
        this.delegate = new NeonSharedDecoder(architecture);
    }

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        return delegate.tryDecode(raw, address, condition).withInstructionSet(InstructionSet.THUMB);
    }
}
