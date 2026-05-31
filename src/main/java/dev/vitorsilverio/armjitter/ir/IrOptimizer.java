package dev.vitorsilverio.armjitter.ir;

/// Passes de otimizacao aplicados a um bloco IR antes do codegen.
public interface IrOptimizer {
    /// Otimiza o bloco informado e retorna uma nova instancia ou o proprio bloco.
    IrBlock optimize(IrBlock block);

    /// Otimizador neutro util enquanto os passes reais nao existem.
    static IrOptimizer identity() {
        return block -> block;
    }
}
