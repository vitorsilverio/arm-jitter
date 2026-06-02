package dev.vitorsilverio.armjitter.core;

/// Estado de espera da CPU usado por integracoes de HALT/STOP do dispositivo.
public enum CpuSleepState {
    /// CPU executa instrucoes normalmente.
    RUNNING,
    /// CPU parada ate uma interrupcao acordar o core.
    HALTED,
    /// CPU em parada profunda ate uma interrupcao acordar o core.
    STOPPED
}
