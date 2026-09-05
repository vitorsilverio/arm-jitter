package dev.vitorsilverio.armjitter.advsimd;

/// Operação AdvSIMD "two-register miscellaneous" de PONTO FLUTUANTE, um só operando (`Rn`) — núcleo
/// COMPARTILHADO (RFC B13.2, D1), subconjunto de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp} migrado na task B13.12.
///
/// **Subconjunto deliberado**: só os 9 valores que o NEON de 32 bits produz nesta task
/// (`VABS_F`/`VNEG_F`/as 5 comparações-com-zero/`VRECPE_F`/`VRSQRTE_F`). O resto do enum A64
/// (`SQRT`/`RINT*`/as conversões `*CVT*`/`FRECPX`/`FCVTXN`) pertence a arredondamento/conversão —
/// fora de escopo aqui (B13.13) — e continua no `switch` local de
/// {@link dev.vitorsilverio.armjitter.executor64.Ir64VectorFpArithmeticExecutor}.
public enum AdvSimdFpUnaryOp {
    /// `|Rn|` — manipula o bit de sinal direto.
    ABS,
    /// `-Rn` — manipula o bit de sinal direto.
    NEG,
    /// `Rn > 0.0` — elemento vira todos-1 ou `0` (`NaN` sempre falso).
    CMGT0,
    /// `Rn >= 0.0`.
    CMGE0,
    /// `Rn == 0.0`.
    CMEQ0,
    /// `Rn <= 0.0`.
    CMLE0,
    /// `Rn < 0.0`.
    CMLT0,
    /// Aproximação inicial de recíproco (`FPRecipEstimate`) — sem tabela de hardware real
    /// modelada, `1.0 / Rn`.
    RECPE,
    /// Aproximação inicial de raiz recíproca (`FPRSqrtEstimate`) — mesma decisão de {@link #RECPE},
    /// `1.0 / sqrt(Rn)`.
    RSQRTE
}
