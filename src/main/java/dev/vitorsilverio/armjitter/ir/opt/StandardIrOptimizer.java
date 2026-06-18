package dev.vitorsilverio.armjitter.ir.opt;

/// Pipelines de otimização prontos para uso.
public final class StandardIrOptimizer {
    private StandardIrOptimizer() {
    }

    /// Pipeline completo para GBA/ARM7TDMI: constant fold → DCE → flag merge.
    public static IrOptimizer gba() {
        return new ConstantFoldPass()
                .then(new DeadCodeEliminationPass())
                .then(new FlagMergePass());
    }
}
