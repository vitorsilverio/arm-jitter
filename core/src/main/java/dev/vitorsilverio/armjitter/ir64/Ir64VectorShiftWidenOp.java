package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorShiftWidenImmediate} (AdvSIMD "shift by immediate" alargando,
/// B8.8) — `Rn` tem elementos de `esz` bytes (metade selecionada por `q`), `Rd` recebe elementos de
/// `esz+1` bytes, SEMPRE preenchendo os 128 bits inteiros (sem saturar — o valor alargado sempre
/// cabe no container maior). Sem forma escalar real (só vetorial).
public enum Ir64VectorShiftWidenOp {
    /// `sext(Rn) << shift` (shift `0`-`(8<<esz)-1`).
    SSHLL,
    /// `zext(Rn) << shift`.
    USHLL
}
