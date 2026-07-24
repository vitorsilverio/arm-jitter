package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.swi.CpuState;

/// Callback Java para tratar qualquer `BKPT` recebendo também o imediato solicitado (B7.5).
/// Mesmo formato de {@link dev.vitorsilverio.armjitter.swi.SwiFallbackHandler}.
@FunctionalInterface
public interface BkptFallbackHandler {
    /// Recebe o imediato do `BKPT` e o estado atual, devolvendo o estado após o tratamento.
    CpuState handle(int immediate, CpuState state);
}
