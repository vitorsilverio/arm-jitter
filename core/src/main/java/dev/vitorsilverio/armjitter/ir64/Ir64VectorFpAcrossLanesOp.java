package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpAcrossLanes} (`FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV`, B8.10) —
/// reduz os 4 elementos SIMPLES (`4S`, único arranjo real; não existe forma doubleword nem
/// forma escalar D-only) de `Rn` a um único escalar simples em `Rd`. Irmã de
/// {@link Ir64VectorAcrossLanesOp} (inteiro, B8.7) — família de encoding separada (`U`=1 fixo,
/// `Q`=1 fixo), por isso um `Ir64Op`/enum próprios em vez de estender aquele.
public enum Ir64VectorFpAcrossLanesOp {
    /// Máximo entre todos os elementos, IEEE `FPMaxNum` (ignora `NaN` se algum operando é numérico).
    FMAXNMV,
    /// Mínimo entre todos os elementos, IEEE `FPMinNum` (ignora `NaN` se algum operando é numérico).
    FMINNMV,
    /// Máximo entre todos os elementos, propaga `NaN`.
    FMAXV,
    /// Mínimo entre todos os elementos, propaga `NaN`.
    FMINV
}
