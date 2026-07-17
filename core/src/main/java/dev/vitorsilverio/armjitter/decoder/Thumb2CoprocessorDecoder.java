package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;

/// Casca fina que reusa o {@link CoprocessorDecoder} (`MCR`/`MRC`) para o espaço Thumb-2 de 32
/// bits (B2.7 PR3): o `raw32` das formas `MCR`/`MRC` Thumb-2 tem o MESMO layout de bits que o
/// encoding ARM clássico que `CoprocessorDecoder` já decodifica — ARM DDI 0406C A5.3, `hw1` fixo
/// `1110 1110` (`0xEE__`), confirmado no QEMU `target/arm/tcg/t32.decode` (`MCR`/`MRC`, `@mcr`):
/// `1110 1110 ooo L nnnn dddd pppp qqq1 mmmm`, idêntico ao `cccc 1110 ooo L NNNN dddd pppp qqq1
/// MMMM` do ARM clássico com o nibble de condição fixo em `1110` em vez de variável — e
/// `CoprocessorDecoder#tryDecode` já ignora os bits 31:28 ao checar o encoding
/// (`raw & 0x0F00_0010`), então delegar `raw32` diretamente funciona sem nenhum ajuste de bits.
///
/// A única correção necessária é a marca de {@link InstructionSet}: `CoprocessorDecoder` sempre
/// devolve {@link InstructionSet#ARM} (correto para seu único chamador hoje, {@code ARMV5TE}) —
/// esta casca reescreve para {@link InstructionSet#THUMB} via
/// {@link DecodedInstruction#withInstructionSet}, necessário para que
/// `StandardIrBlockLifter#instructionWidth`/`isThumb32` e o resto do pipeline (chave de bloco,
/// detecção de superblock) classifiquem a instrução corretamente como Thumb-2. Anexado via
/// {@link ArmArchitecture#thumb32DecoderExtensions()} — só ativo quando {@link ArmFeature#THUMB2}
/// está habilitado (o chamador, {@link ThumbDecoder}, só invoca as extensões de 32 bits com essa
/// feature ligada); sem gate adicional, mesmo padrão do `CoprocessorDecoder` original (que também
/// não tem gate próprio além de estar anexado à arquitetura certa) — pré-requisito real de
/// B4.0.3/B4.0.4 (musl v7 lê TLS via `mrc` em Thumb).
public final class Thumb2CoprocessorDecoder implements DecoderExtension {
    private final CoprocessorDecoder delegate = new CoprocessorDecoder();

    @Override
    public DecodedInstruction tryDecode(int raw, int address, Condition condition) {
        DecodedInstruction decoded = delegate.tryDecode(raw, address, condition);
        if (decoded == null) {
            return null;
        }
        return decoded.withInstructionSet(InstructionSet.THUMB);
    }
}
