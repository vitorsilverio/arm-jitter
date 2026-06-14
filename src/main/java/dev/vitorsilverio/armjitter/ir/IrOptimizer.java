package dev.vitorsilverio.armjitter.ir;

/// Passes de otimização aplicados a um bloco IR antes do codegen.
public interface IrOptimizer {
    /// Otimiza o bloco informado e retorna uma nova instância ou o próprio bloco.
    IrBlock optimize(IrBlock block);

    /// Otimizador neutro util enquanto os passes reais não existem.
    static IrOptimizer identity() {
        return block -> block;
    }
}
