package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorShiftNarrowImmediate} (AdvSIMD "shift by immediate" estreitando,
/// B8.8) — `Rn` tem elementos de `esz+1` bytes, `Rd` recebe elementos de `esz` bytes (metade
/// selecionada por `q`, mesma convenção "SIMD&FP destructive write" de
/// {@link Ir64Op.VectorArithmeticNarrow}). {@link #SHRN}/{@link #RSHRN} NÃO têm forma escalar real
/// (só vetorial); os demais aceitam escalar com `esz` `0`-`2` (nunca `3` — não existe estreitamento
/// de `Q` para `D`).
public enum Ir64VectorShiftNarrowOp {
    /// `Rn >>> shift` truncado para `esz` bytes — sem saturar, sem arredondar.
    SHRN,
    /// Como {@link #SHRN}, com ARREDONDAMENTO (não assinado) antes de truncar.
    RSHRN,
    /// `SignedSaturate(sext(Rn) >> shift)` — entrada e saída assinadas.
    SQSHRN,
    /// `UnsignedSaturate(Rn >>> shift)` — entrada e saída não assinadas.
    UQSHRN,
    /// `UnsignedSaturate(sext(Rn) >> shift)` — entrada ASSINADA, saída NÃO assinada.
    SQSHRUN,
    /// Como {@link #SQSHRN}, com ARREDONDAMENTO antes de saturar.
    SQRSHRN,
    /// Como {@link #UQSHRN}, com ARREDONDAMENTO antes de saturar.
    UQRSHRN,
    /// Como {@link #SQSHRUN}, com ARREDONDAMENTO antes de saturar.
    SQRSHRUN
}
