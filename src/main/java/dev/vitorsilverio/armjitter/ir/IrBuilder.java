package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;

/// Converte instruções decodificadas em representação intermediaria.
public interface IrBuilder {
    /// Adiciona uma instrução decodificada ao bloco em construção.
    void lift(DecodedInstruction instruction, IrBlock.Builder block);
}
