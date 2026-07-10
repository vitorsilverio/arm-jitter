package dev.vitorsilverio.armjitter.core;

/// Estado de espera da CPU usado por integrações de HALT/STOP do dispositivo.
public enum CpuSleepState {
    /// CPU executa instruções normalmente.
    RUNNING,
    /// CPU parada até uma interrupção acordar o core.
    HALTED,
    /// CPU em parada profunda até uma interrupção acordar o core.
    STOPPED
}
