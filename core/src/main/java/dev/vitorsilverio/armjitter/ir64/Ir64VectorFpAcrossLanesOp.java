package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpAcrossLanes} (`FMAXNMV`/`FMINNMV`/`FMAXV`/`FMINV`, B8.10 —
/// formas `_s`; B19.5.3 — formas `_h`, `FEAT_FP16`). Reduz os elementos de `Rn` a um único
/// escalar em `Rd`: `4S` é o único arranjo real de precisão simples (`U`=1/`Q`=1 fixos, sem forma
/// doubleword nem escalar D-only), mas meia precisão tem `4H`/`8H` (`Q` livre). Irmã de
/// {@link Ir64VectorAcrossLanesOp} (inteiro, B8.7) — família de encoding separada, por isso um
/// `Ir64Op`/enum próprios em vez de estender aquele.
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
