package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorAcrossLanes} (`ADDV`/`SADDLV`/`UADDLV`/`SMAXV`/`UMAXV`/`SMINV`/
/// `UMINV`, B8.7) — reduz TODOS os elementos de `Rn` a um único escalar, escrito em `Rd` (tamanho
/// {@link Ir64Op.VectorAcrossLanes#esz}, ou `esz+1` para as variantes "long" `SADDLV`/`UADDLV`).
public enum Ir64VectorAcrossLanesOp {
    /// Soma de todos os elementos (resultado no mesmo tamanho, trunca).
    ADDV,
    /// Soma de todos os elementos, cada um sign-extendido antes de somar (resultado em `esz+1`).
    SADDLV,
    /// Soma de todos os elementos, cada um zero-extendido antes de somar (resultado em `esz+1`).
    UADDLV,
    /// Máximo assinado entre todos os elementos.
    SMAXV,
    /// Máximo não assinado entre todos os elementos.
    UMAXV,
    /// Mínimo assinado entre todos os elementos.
    SMINV,
    /// Mínimo não assinado entre todos os elementos.
    UMINV
}
