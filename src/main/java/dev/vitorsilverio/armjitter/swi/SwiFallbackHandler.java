package dev.vitorsilverio.armjitter.swi;

/// Callback Java para tratar qualquer SWI recebendo tambem o numero solicitado.
@FunctionalInterface
public interface SwiFallbackHandler {
    /// Recebe o numero da SWI e o estado atual, devolvendo o estado apos a chamada.
    CpuState handle(int swi, CpuState state);
}
