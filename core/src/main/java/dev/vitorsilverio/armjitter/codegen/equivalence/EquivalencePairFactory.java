package dev.vitorsilverio.armjitter.codegen.equivalence;

/// Fornece pares de cores com estado inicial idêntico para testes de equivalência.
@FunctionalInterface
public interface EquivalencePairFactory {
    /// Cria um par novo; cada chamada deve devolver cores independentes.
    EquivalencePair create();
}
