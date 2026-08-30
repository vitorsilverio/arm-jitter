package dev.vitorsilverio.armjitter.advsimd;

/// Operação "three same" de PONTO FLUTUANTE do núcleo vetorial COMPARTILHADO — os 3 operandos
/// (`Rd`/`Rn`/`Rm`) têm o mesmo tamanho de elemento (`esz` `2` = F32 ou `3` = F64; meia-precisão
/// fica fora, `FEAT_FP16`). Espelho de {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp}
/// (A64 `FADD_v`/`FMUL_v`/.../`FRSQRTS_v`, B8.9) MAIS a distinção fundido × NÃO fundido do
/// multiply-accumulate, que só o NEON de 32 bits (A32) tem: `VMLA.F32`/`VMLS.F32` fazem dois
/// arredondamentos ({@link #MLA}/{@link #MLS}), `VFMA.F32`/`VFMS.F32` um só ({@link #FMLA}/
/// {@link #FMLS}, VFPv4). O A64 (`FMLA`/`FMLS` vetorial) só tem a forma fundida, e mapeia para
/// {@link #FMLA}/{@link #FMLS}.
///
/// {@link #DIV} e {@link #MULX} não são produzidos por nenhum encoding NEON A32 de "3-reg-same",
/// mas existem no enum para o A64 (que os tem em {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp})
/// delegar sem `default`.
public enum AdvSimdFpThreeSameOp {
    /// `a + b` (IEEE 754) — `VADD.F32` / `FADD_v`.
    ADD,
    /// `a - b` — `VSUB.F32` / `FSUB_v`.
    SUB,
    /// `a * b` — `VMUL.F32` / `FMUL_v`.
    MUL,
    /// `a / b` — só A64 (`FDIV_v`); nenhum encoding NEON A32.
    DIV,
    /// `FPMax` (propaga `NaN`, prefere `+0.0` sobre `-0.0`) — `VMAX.F32` / `FMAX_v`.
    MAX,
    /// `FPMin` (propaga `NaN`, prefere `-0.0` sobre `+0.0`) — `VMIN.F32` / `FMIN_v`.
    MIN,
    /// `FPMaxNum` (só `NaN` quando os DOIS operandos são `NaN`) — `VMAXNM.F32` (ARMv8-A) /
    /// `FMAXNM_v`.
    MAXNM,
    /// `FPMinNum` — `VMINNM.F32` / `FMINNM_v`.
    MINNM,
    /// Multiplicação "estendida" (`FPMulX`): `0 * Infinito` devolve `2.0` com o sinal do produto,
    /// em vez de `NaN`. Só A64 (`FMULX_v`); nenhum encoding NEON A32 de "3-reg-same".
    MULX,
    /// `Rd = Rn * Rm + Rd` com DOIS arredondamentos (mul, depois add) — `VMLA.F32` NEON, NÃO
    /// fundido (ARM DDI 0406C A8.8.337). Lê o `Rd` ATUAL, elemento a elemento.
    MLA,
    /// `Rd = Rd - Rn * Rm` com DOIS arredondamentos — `VMLS.F32` NEON, NÃO fundido. Lê o `Rd`
    /// ATUAL.
    MLS,
    /// `Rd = fma(Rn, Rm, Rd)` — multiply-accumulate FUNDIDO (arredondamento único), `VFMA.F32`
    /// (VFPv4) e `FMLA_v` do A64. Lê o `Rd` ATUAL.
    FMLA,
    /// `Rd = fma(-Rn, Rm, Rd)` — multiply-subtract FUNDIDO, `VFMS.F32` e `FMLS_v` do A64.
    FMLS,
    /// `a == b` — elemento vira todos-1 ou `0` (`NaN` sempre falso) — `VCEQ.F32` / `FCMEQ_v`.
    CMEQ,
    /// `a >= b` — `VCGE.F32` / `FCMGE_v`.
    CMGE,
    /// `a > b` — `VCGT.F32` / `FCMGT_v`.
    CMGT,
    /// `|a| >= |b|` ("absolute compare greater or equal") — `VACGE.F32` / `FACGE_v`.
    FACGE,
    /// `|a| > |b|` ("absolute compare greater") — `VACGT.F32` / `FACGT_v`.
    FACGT,
    /// `|a - b|` (diferença absoluta) — `VABD.F32` / `FABD_v`.
    ABD,
    /// Passo de Newton-Raphson para recíproco (`FPRecipStep`, `2.0 - a*b`) — `VRECPS.F32` /
    /// `FRECPS_v`.
    RECPS,
    /// Passo de Newton-Raphson para raiz recíproca (`FPRSqrtStep`, `(3.0 - a*b) / 2.0`) —
    /// `VRSQRTS.F32` / `FRSQRTS_v`.
    RSQRTS
}
