package dev.vitorsilverio.armjitter.core;

/// Tipos de excecao ARM que podem interromper a execucao normal.
public enum ArmException {
    /// Reset de CPU.
    RESET,
    /// Instrucao indefinida.
    UNDEFINED,
    /// Software interrupt.
    SWI,
    /// Prefetch abort.
    PREFETCH_ABORT,
    /// Data abort.
    DATA_ABORT,
    /// Interrupt request.
    IRQ,
    /// Fast interrupt request.
    FIQ
}
