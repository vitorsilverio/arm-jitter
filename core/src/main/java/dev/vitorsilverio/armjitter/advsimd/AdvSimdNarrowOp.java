package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "three different" ESTREITANDO ("half narrowing", B13.10/B8.7) — `Rn`/`Rm` têm
/// elementos LARGOS (`esz+1`), `Rd` recebe elementos ESTREITOS (`esz`), a metade ALTA da soma/
/// diferença larga. Mirror exato de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp} (A64, B8.7).
public enum AdvSimdNarrowOp {
    /// `(Rn + Rm) >> esize` (bits altos da soma larga, sem arredondamento).
    ADDHN,
    /// `(Rn + Rm + (1 << (esize-1))) >> esize` (com arredondamento).
    RADDHN,
    /// `(Rn - Rm) >> esize` (bits altos da diferença larga, sem arredondamento).
    SUBHN,
    /// `(Rn - Rm + (1 << (esize-1))) >> esize` (com arredondamento).
    RSUBHN
}
