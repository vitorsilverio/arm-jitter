package dev.vitorsilverio.armjitter.codegen.equivalence;

/// Fornece pares de cores A64 com estado inicial idêntico para testes de equivalência — sibling
/// de {@link EquivalencePairFactory} (32 bits), introduzido na task B6.4.
@FunctionalInterface
public interface EquivalencePairFactory64 {
    /// Cria um par novo; cada chamada deve devolver cores independentes.
    EquivalencePair64 create();
}
