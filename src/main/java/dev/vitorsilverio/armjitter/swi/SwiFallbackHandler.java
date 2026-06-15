package dev.vitorsilverio.armjitter.swi;

/// Callback Java para tratar qualquer SWI recebendo também o número solicitado.
@FunctionalInterface
public interface SwiFallbackHandler {
    /// Recebe o número da SWI e o estado atual, devolvendo o estado após a chamada.
    CpuState handle(int swi, CpuState state);
}
