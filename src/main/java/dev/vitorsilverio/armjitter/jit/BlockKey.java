package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Chave de cache para um bloco compilado.
public record BlockKey(
        /// Program counter inicial do bloco.
        int pc,
        /// Conjunto de instruções usado para decodificar o bloco.
        InstructionSet instructionSet) {
}
