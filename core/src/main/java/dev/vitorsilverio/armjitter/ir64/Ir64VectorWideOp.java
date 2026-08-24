package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticWide} (`SADDW`/`UADDW`/`SSUBW`/`USUBW`, B8.7) — `Rn`/
/// `Rd` já têm elementos LARGOS (`esz+1`), só `Rm` é estreito (`esz`, metade baixa/alta selecionada
/// por `q`, mesma convenção de {@link Ir64VectorWideningOp}).
public enum Ir64VectorWideOp {
    /// `Rn + sext(Rm)`.
    SADDW,
    /// `Rn + zext(Rm)`.
    UADDW,
    /// `Rn - sext(Rm)`.
    SSUBW,
    /// `Rn - zext(Rm)`.
    USUBW
}
