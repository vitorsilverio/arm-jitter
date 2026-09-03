package dev.vitorsilverio.armjitter.advsimd;

/// Operação de deslocamento por imediato ALARGANTE do núcleo vetorial COMPARTILHADO
/// ({@link AdvSimdLanes#shiftWidenImmediate}) — a fonte tem elementos de `esz` bytes, o destino
/// recebe elementos de `esz+1` bytes, SEMPRE preenchendo o registrador inteiro (sem saturar — o
/// valor alargado sempre cabe no container maior). Mirror exato de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp} (B8.8): o encoding A32
/// ("2-reg-and-shift", `opc=1010`, B13.8) e o A64 compartilham aritmética e famílias (RFC B13.2, D1).
public enum AdvSimdShiftWidenOp {
    /// `sext(Rn) << shift` (`VSHLL` com fonte assinada).
    SSHLL,
    /// `zext(Rn) << shift` (`VSHLL` com fonte não assinada).
    USHLL
}
