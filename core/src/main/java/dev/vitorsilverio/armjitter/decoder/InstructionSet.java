package dev.vitorsilverio.armjitter.decoder;

/// Conjunto de instruções ativo para um bloco decodificado.
public enum InstructionSet {
    /// Instruções ARM de 32 bits.
    ARM,
    /// Instruções THUMB de 16 bits.
    THUMB
}
