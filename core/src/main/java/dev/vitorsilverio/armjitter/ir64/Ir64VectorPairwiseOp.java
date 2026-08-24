package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticPairwise} (`ADDP_v`/`SMAXP_v`/`SMINP_v`/`UMAXP_v`/
/// `UMINP_v`, B8.7) — concatena `Rn:Rm` (`Rn` primeiro) e combina pares de elementos ADJACENTES
/// nessa sequência de `2 * elementos-por-registrador`, produzindo `elementos-por-registrador`
/// resultados (metade de `Rn`, metade de `Rm`).
public enum Ir64VectorPairwiseOp {
    /// `par[2i] + par[2i+1]`.
    ADD,
    /// Máximo assinado do par.
    SMAX,
    /// Máximo não assinado do par.
    UMAX,
    /// Mínimo assinado do par.
    SMIN,
    /// Mínimo não assinado do par.
    UMIN
}
