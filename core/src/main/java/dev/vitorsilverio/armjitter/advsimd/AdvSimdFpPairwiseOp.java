package dev.vitorsilverio.armjitter.advsimd;

/// Operação "pairwise" de PONTO FLUTUANTE do núcleo vetorial COMPARTILHADO — concatena `Rn:Rm`
/// (`Rn` primeiro) e combina pares de elementos ADJACENTES nessa sequência, produzindo `lanes`
/// resultados (metade vinda de `Rn`, metade de `Rm`). Espelho exato de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp}: A64 `FADDP_v`/`FMAXP_v`/
/// `FMINP_v`/`FMAXNMP_v`/`FMINNMP_v` (B8.9) e NEON de 32 bits `VPADD.F32`/`VPMAX.F32`/`VPMIN.F32`
/// (B13.6, só forma `D`).
public enum AdvSimdFpPairwiseOp {
    /// `par[2i] + par[2i+1]` (IEEE 754) — `VPADD.F32` / `FADDP_v`.
    ADD,
    /// `FPMax` do par (propaga `NaN`) — `VPMAX.F32` / `FMAXP_v`.
    MAX,
    /// `FPMin` do par (propaga `NaN`) — `VPMIN.F32` / `FMINP_v`.
    MIN,
    /// `FPMaxNum` do par (só `NaN` quando os dois são `NaN`) — `FMAXNMP_v` (só A64).
    MAXNM,
    /// `FPMinNum` do par — `FMINNMP_v` (só A64).
    MINNM
}
