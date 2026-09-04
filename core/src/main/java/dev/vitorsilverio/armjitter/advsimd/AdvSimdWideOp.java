package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "three different" LARGA (widening "Wide" form, B13.10/B8.7) — `Rn`/`Rd` já têm
/// elementos LARGOS (`esz+1`), só `Rm` é estreito (`esz`). Mirror exato de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp} (A64, B8.7).
public enum AdvSimdWideOp {
    /// `Rn + sext(Rm)`.
    SADDW,
    /// `Rn + zext(Rm)`.
    UADDW,
    /// `Rn - sext(Rm)`.
    SSUBW,
    /// `Rn - zext(Rm)`.
    USUBW
}
