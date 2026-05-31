package dev.vitorsilverio.armjitter.swi;

/// Callback Java para tratar uma SWI sem obrigar execucao da BIOS.
@FunctionalInterface
public interface SwiHandler {
    /// Recebe o estado atual e devolve o estado apos a chamada de sistema.
    CpuState handle(CpuState state);
}
