package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticNarrow} (`ADDHN`/`RADDHN`/`SUBHN`/`RSUBHN`, B8.7) —
/// `Rn`/`Rm` têm elementos LARGOS (`esz+1`), `Rd` recebe elementos ESTREITOS (`esz`, metade baixa
/// escrita quando `q=false`/forma sem `2`, alta quando `q=true`/forma `*2` — a outra metade de
/// `Rd` fica intocada quando `q=true`, ZERADA quando `q=false`, mesma disciplina "SIMD&FP
/// destructive write").
public enum Ir64VectorNarrowOp {
    /// `(Rn + Rm) >> esz` (bits altos da soma larga, sem arredondamento).
    ADDHN,
    /// `(Rn + Rm + (1 << (esz-1))) >> esz` (com arredondamento).
    RADDHN,
    /// `(Rn - Rm) >> esz` (bits altos da diferença larga, sem arredondamento).
    SUBHN,
    /// `(Rn - Rm + (1 << (esz-1))) >> esz` (com arredondamento).
    RSUBHN
}
