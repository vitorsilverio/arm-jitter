package dev.vitorsilverio.armjitter.advsimd;

/// Operação "1-reg-and-modified-immediate" do núcleo vetorial COMPARTILHADO entre AArch64
/// (`Vimm`/`FMOVI_v_h`, B19.6) e NEON de 32 bits (`VMOV`/`VMVN`/`VORR`/`VBIC` imediato, B13.9) — RFC
/// B13.2, decisão D1 (reuso, não espelhamento), aplicada aqui ANTES de existir a duplicação.
///
/// As quatro instruções reais do manual (não dobradas em duas, ao contrário do QEMU — ver
/// `AdvSimdModifiedImmediate`, Decisão 2 da B13.9).
public enum AdvSimdModifiedImmediateOp {
    /// `Rd = imm64` (sobrescreve).
    MOV,
    /// `Rd = NOT(imm64)` (sobrescreve com o complemento).
    MVN,
    /// `Rd = Rd OR imm64` (lê e escreve `Rd`).
    ORR,
    /// `Rd = Rd AND NOT(imm64)` (lê e escreve `Rd`).
    BIC
}
