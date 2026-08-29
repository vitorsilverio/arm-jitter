package dev.vitorsilverio.armjitter.advsimd;

/// Operação "pairwise" do núcleo vetorial COMPARTILHADO — concatena `Rn:Rm` (`Rn` primeiro) e
/// combina pares de elementos ADJACENTES nessa sequência de `2 * lanes`, produzindo `lanes`
/// resultados (metade vinda de `Rn`, metade de `Rm`). Espelho de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp}: A64 `ADDP_v`/`SMAXP_v`/
/// `SMINP_v`/`UMAXP_v`/`UMINP_v` (B8.7) e NEON de 32 bits `VPADD`/`VPMAX`/`VPMIN` (B13.4, só forma
/// `D`).
public enum AdvSimdPairwiseOp {
    /// `par[2i] + par[2i+1]` truncado ao tamanho do elemento (`VPADD` / `ADDP_v`).
    ADD,
    /// Máximo assinado do par (`VPMAX.S` / `SMAXP_v`).
    SMAX,
    /// Máximo não assinado do par (`VPMAX.U` / `UMAXP_v`).
    UMAX,
    /// Mínimo assinado do par (`VPMIN.S` / `SMINP_v`).
    SMIN,
    /// Mínimo não assinado do par (`VPMIN.U` / `UMINP_v`).
    UMIN
}
