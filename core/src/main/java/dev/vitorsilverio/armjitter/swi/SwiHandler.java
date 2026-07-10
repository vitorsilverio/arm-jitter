package dev.vitorsilverio.armjitter.swi;

/// Callback Java para tratar uma SWI sem obrigar execução da BIOS.
@FunctionalInterface
public interface SwiHandler {
    /// Recebe o estado atual e devolve o estado após a chamada de sistema.
    CpuState handle(CpuState state);
}
