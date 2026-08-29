package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorFpArithmeticThreeSame} (AdvSIMD "three same" de ponto
/// flutuante, B8.9) — só as formas de precisão SIMPLES/DUPLA (`esz` `2`/`3`, "sd" no
/// `a64.decode` real do QEMU); meia-precisão (`esz=1`, "h") é `FEAT_FP16`, fora do Cortex-A53 do
/// `virtual-arm-box` — ver `docs/isa-nao-aplicavel.tsv`. Cobre a forma VETORIAL (B8.9) e a forma
/// ESCALAR AdvSIMD (`FMULX_s`/`FCMEQ_s`/`FCMGE_s`/`FCMGT_s`/`FACGE_s`/`FACGT_s`/`FABD_s`/`FRECPS_s`/
/// `FRSQRTS_s`, B19.2 — `scalar=true` no record, subconjunto de 9 ops; as demais NÃO têm forma
/// AdvSIMD-escalar real, G8).
public enum Ir64VectorFpThreeSameOp {
    /// `a + b` (IEEE 754).
    ADD,
    /// `a - b`.
    SUB,
    /// `a * b`.
    MUL,
    /// `a / b`.
    DIV,
    /// `FPMax` (propaga `NaN`, prefere `+0.0` sobre `-0.0`).
    MAX,
    /// `FPMin` (propaga `NaN`, prefere `-0.0` sobre `+0.0`).
    MIN,
    /// `FPMaxNum` (só `NaN` quando os DOIS operandos são `NaN`).
    MAXNM,
    /// `FPMinNum`.
    MINNM,
    /// Multiplicação "estendida" (`FPMulX`): `0 * Infinito` (ou vice-versa) devolve `2.0` com o
    /// sinal do produto, em vez de `NaN` como a multiplicação IEEE normal — único caso em que
    /// difere de {@link #MUL}.
    MULX,
    /// `Rd += Rn * Rm` (multiply-accumulate fundido em precisão simples/dupla — lê o `Rd` ATUAL).
    MLA,
    /// `Rd -= Rn * Rm` (multiply-subtract fundido — lê o `Rd` ATUAL).
    MLS,
    /// `a == b` — elemento vira todos-1 ou `0` (`NaN` sempre falso).
    CMEQ,
    /// `a >= b` — elemento vira todos-1 ou `0` (`NaN` sempre falso).
    CMGE,
    /// `a > b` — elemento vira todos-1 ou `0` (`NaN` sempre falso).
    CMGT,
    /// `|a| >= |b|` ("absolute compare greater or equal") — elemento vira todos-1 ou `0`.
    FACGE,
    /// `|a| > |b|` ("absolute compare greater").
    FACGT,
    /// `|a - b|` (diferença absoluta).
    ABD,
    /// Passo de Newton-Raphson para recíproco (`FPRecipStep`, `2.0 - a*b`) — usado com
    /// {@link Ir64VectorFpUnaryOp#RECPE} para refinar uma aproximação.
    RECPS,
    /// Passo de Newton-Raphson para raiz recíproca (`FPRSqrtStep`, `(3.0 - a*b) / 2.0`) — usado
    /// com {@link Ir64VectorFpUnaryOp#RSQRTE}.
    RSQRTS
}
