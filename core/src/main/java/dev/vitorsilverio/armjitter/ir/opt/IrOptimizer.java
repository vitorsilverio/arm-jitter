package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.ir.IrBlock;

/// Transforma um {@link IrBlock} imutável em outro semanticamente equivalente, porém mais eficiente.
///
/// Implementações devem preservar totalmente a semântica ARM do bloco: mesmo estado de CPU,
/// mesmos ciclos internos, mesma contagem de acessos à memória.
@FunctionalInterface
public interface IrOptimizer {
    IrBlock optimize(IrBlock block);

    /// Otimizador que devolve o bloco intacto.
    static IrOptimizer identity() {
        return block -> block;
    }

    /// Combina dois otimizadores em sequência: {@code this} primeiro, depois {@code next}.
    default IrOptimizer then(IrOptimizer next) {
        return block -> next.optimize(this.optimize(block));
    }
}
