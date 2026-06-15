package dev.vitorsilverio.armjitter.ir;

/// Tipo de deslocamento usado pelo barrel shifter ARM.
public enum ShiftType {
    /// Deslocamento lógico à esquerda.
    LSL,
    /// Deslocamento lógico à direita.
    LSR,
    /// Deslocamento aritmético à direita.
    ASR,
    /// Rotação à direita.
    ROR
}
