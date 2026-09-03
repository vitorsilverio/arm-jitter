package dev.vitorsilverio.armjitter.advsimd;

/// Operação de deslocamento por imediato ESTREITANTE do núcleo vetorial COMPARTILHADO
/// ({@link AdvSimdLanes#shiftNarrowImmediate}) — a fonte tem elementos de `esz+1` bytes, o destino
/// recebe elementos de `esz` bytes. Mirror exato de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftNarrowOp} (B8.8): o encoding A32
/// ("2-reg-and-shift", `opc=1000`/`1001`, B13.8) e o A64 ("AdvSIMD shift by immediate") derivam a
/// MESMA aritmética e as MESMAS 8 famílias — só o encoding e a IR diferem (RFC B13.2, D1).
public enum AdvSimdShiftNarrowOp {
    /// `Rn >>> shift` truncado para `esz` bytes — sem saturar, sem arredondar (`VSHRN`).
    SHRN,
    /// Como {@link #SHRN}, com ARREDONDAMENTO (não assinado) antes de truncar (`VRSHRN`).
    RSHRN,
    /// `SignedSaturate(sext(Rn) >> shift)` — entrada e saída assinadas (`VQSHRN` com fonte assinada).
    SQSHRN,
    /// `UnsignedSaturate(Rn >>> shift)` — entrada e saída não assinadas (`VQSHRN` com fonte não
    /// assinada).
    UQSHRN,
    /// `UnsignedSaturate(sext(Rn) >> shift)` — entrada ASSINADA, saída NÃO assinada (`VQSHRUN`).
    SQSHRUN,
    /// Como {@link #SQSHRN}, com ARREDONDAMENTO antes de saturar (`VQRSHRN` com fonte assinada).
    SQRSHRN,
    /// Como {@link #UQSHRN}, com ARREDONDAMENTO antes de saturar (`VQRSHRN` com fonte não assinada).
    UQRSHRN,
    /// Como {@link #SQSHRUN}, com ARREDONDAMENTO antes de saturar (`VQRSHRUN`).
    SQRSHRUN
}
