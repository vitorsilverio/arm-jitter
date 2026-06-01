package dev.vitorsilverio.armjitter.ir;

/// Tipo de deslocamento usado pelo barrel shifter ARM.
public enum ShiftType {
    /// Logical shift left.
    LSL,
    /// Logical shift right.
    LSR,
    /// Arithmetic shift right.
    ASR,
    /// Rotate right.
    ROR
}
