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
    FIQ,
    /// `HVC` (B9.8.2, ARMv7VE-adjacent — ver `ArmFeature#HYPERVISOR_CALL`): entra em Hyp mode.
    HVC,
    /// `SMC` (B9.8.3 — ver `ArmFeature#SECURE_MONITOR_CALL`): entra em Monitor mode.
    SMC
}
