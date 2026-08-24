package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpArithmeticPairwise} (AdvSIMD "three same" de ponto
/// flutuante, pareado, B8.9) — concatena `Rn:Rm` e combina pares adjacentes, mesmo esquema de
/// {@link Ir64VectorPairwiseOp} (inteiro), só precisão simples/dupla. Não cobre a forma escalar
/// (`FADDP_s`/... reduz `Rn.2d`/`Rn.2s` a um elemento) — fora desta task.
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
