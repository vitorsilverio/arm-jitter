package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;

/// Bloco pronto para execução pelo runtime JIT.
@FunctionalInterface
public interface CompiledBlock {
    /// Executa o bloco contra o core e retorna os ciclos consumidos.
    int execute(ArmCore core);
}
