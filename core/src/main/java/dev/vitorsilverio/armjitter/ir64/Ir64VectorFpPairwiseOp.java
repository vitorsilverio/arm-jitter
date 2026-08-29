package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpArithmeticPairwise} (AdvSIMD "three same" de ponto
/// flutuante, pareado, B8.9) — concatena `Rn:Rm` e combina pares adjacentes, mesmo esquema de
/// {@link Ir64VectorPairwiseOp} (inteiro), só precisão simples/dupla. Cobre também a forma ESCALAR
/// (`FADDP_s`/`FMAXP_s`/`FMINP_s`/`FMAXNMP_s`/`FMINNMP_s`, B19.2 — `scalar=true` no record: reduz
/// `Rn.2s`/`Rn.2d` a um único S/D).
public enum Ir64VectorFpPairwiseOp {
    /// `Rn[2i] + Rn[2i+1]` (baixo) / `Rm[2i] + Rm[2i+1]` (alto).
    ADD,
    /// `FPMax` do par.
    MAX,
    /// `FPMin` do par.
    MIN,
    /// `FPMaxNum` do par.
    MAXNM,
    /// `FPMinNum` do par.
    MINNM
}
