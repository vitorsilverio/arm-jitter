package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;

/// Converte instrucoes decodificadas em representacao intermediaria.
public interface IrBuilder {
    /// Adiciona uma instrucao decodificada ao bloco em construcao.
    void lift(DecodedInstruction instruction, IrBlock.Builder block);
}
