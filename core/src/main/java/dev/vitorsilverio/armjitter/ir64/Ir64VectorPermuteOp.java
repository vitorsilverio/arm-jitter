package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorPermute} (`UZP1`/`UZP2`/`TRN1`/`TRN2`/`ZIP1`/`ZIP2`, B8.10) —
/// combina os elementos de `Rn`/`Rm` numa ordem fixa diferente de {@link Ir64VectorPairwiseOp}
/// (que soma/reduz pares; aqui os elementos são apenas REORGANIZADOS, sem aritmética).
public enum Ir64VectorPermuteOp {
    /// Elementos de índice PAR de `Rn` seguidos dos de índice par de `Rm` ("unzip", metade baixa).
    UZP1,
    /// Elementos de índice ÍMPAR de `Rn` seguidos dos de índice ímpar de `Rm` ("unzip", metade alta).
    UZP2,
    /// Elementos de índice PAR de `Rn` intercalados com os de índice par de `Rm` ("transpose",
    /// metade baixa).
    TRN1,
    /// Elementos de índice ÍMPAR de `Rn` intercalados com os de índice ímpar de `Rm`
    /// ("transpose", metade alta).
    TRN2,
    /// Intercala a METADE BAIXA de `Rn` com a metade baixa de `Rm`, elemento a elemento ("zip").
    ZIP1,
    /// Intercala a METADE ALTA de `Rn` com a metade alta de `Rm`, elemento a elemento ("zip").
    ZIP2
}
