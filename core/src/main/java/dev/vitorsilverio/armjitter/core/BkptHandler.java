package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.swi.CpuState;

/// Callback Java para tratar um `BKPT` sem obrigar um debugger real conectado (B7.5,
/// semihosting). Mesmo formato de {@link dev.vitorsilverio.armjitter.swi.SwiHandler}.
@FunctionalInterface
public interface BkptHandler {
    /// Recebe o estado atual e devolve o estado após o `BKPT`.
    CpuState handle(CpuState state);
}
