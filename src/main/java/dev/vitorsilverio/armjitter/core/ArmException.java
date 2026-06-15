package dev.vitorsilverio.armjitter.core;

/// Tipos de exceção ARM que podem interromper a execução normal.
public enum ArmException {
    /// Reset de CPU.
    RESET,
    /// Instrução indefinida.
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
