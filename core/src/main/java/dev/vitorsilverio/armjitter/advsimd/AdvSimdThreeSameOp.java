package dev.vitorsilverio.armjitter.advsimd;

/// Operação "three same" (dois operandos vetoriais do MESMO arranjo, resultado no mesmo arranjo)
/// do núcleo vetorial COMPARTILHADO entre AArch64 (`ADD_v`/`SUB_v`/`CM**_v`/..., B8.7) e NEON de
/// 32 bits (`VADD`/`VSUB`/`VCGT`/`VAND`/..., B13.4) — RFC B13.2, decisão D1.
///
/// Cobre TODO o conjunto "three same" de {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp}
/// (mesmos nomes): o subconjunto inteiro não saturante veio de B13.4 e as 16 operações saturantes /
/// de deslocamento por registrador (`SQADD`..`SQRDMLSH`) vieram de B13.5. Cada operação existe em
/// exatamente UM lugar — o núcleo compartilhado; o `switch` de
/// `Ir64VectorArithmeticExecutor#executeThreeSame` ficou vazio (só o `default -> throw` de
/// contrato) e `sharedThreeSameOp` mapeia todas.
public enum AdvSimdThreeSameOp {
    /// `a + b` truncado ao tamanho do elemento (`ADD_v` / `VADD` inteiro).
    ADD,
    /// `a - b` truncado ao tamanho do elemento (`SUB_v` / `VSUB` inteiro).
    SUB,
    /// `a > b` (assinado) — elemento vira todos-1 ou `0` (`CMGT_v` / `VCGT.S`).
    CMGT,
    /// `a > b` (não assinado, "higher") — todos-1 ou `0` (`CMHI_v` / `VCGT.U`).
    CMHI,
    /// `a >= b` (assinado) — todos-1 ou `0` (`CMGE_v` / `VCGE.S`).
    CMGE,
    /// `a >= b` (não assinado, "higher or same") — todos-1 ou `0` (`CMHS_v` / `VCGE.U`).
    CMHS,
    /// `(a & b) != 0` — todos-1 ou `0` (`CMTST_v` / `VTST`).
    CMTST,
    /// `a == b` — todos-1 ou `0` (`CMEQ_v` / `VCEQ`).
    CMEQ,
    /// Soma "halving" assinada: `(sext(a)+sext(b)) >> 1` (aritmético) — `VHADD.S`.
    SHADD,
    /// Soma "halving" não assinada: `(a+b) >>> 1` (lógico) — `VHADD.U`.
    UHADD,
    /// Subtração "halving" assinada: `(sext(a)-sext(b)) >> 1` (aritmético) — `VHSUB.S`.
    SHSUB,
    /// Subtração "halving" não assinada: `(a-b) >>> 1` (lógico) — `VHSUB.U`.
    UHSUB,
    /// Soma "halving" assinada COM arredondamento: `(sext(a)+sext(b)+1) >> 1` — `VRHADD.S`.
    SRHADD,
    /// Soma "halving" não assinada COM arredondamento: `(a+b+1) >>> 1` — `VRHADD.U`.
    URHADD,
    /// Máximo assinado (`SMAX_v` / `VMAX.S`).
    SMAX,
    /// Máximo não assinado (`UMAX_v` / `VMAX.U`).
    UMAX,
    /// Mínimo assinado (`SMIN_v` / `VMIN.S`).
    SMIN,
    /// Mínimo não assinado (`UMIN_v` / `VMIN.U`).
    UMIN,
    /// `|sext(a)-sext(b)|` (diferença absoluta assinada) — `SABD_v` / `VABD.S`.
    SABD,
    /// `|a-b|` (diferença absoluta não assinada) — `UABD_v` / `VABD.U`.
    UABD,
    /// `Rd += |sext(Rn)-sext(Rm)|` (acumula diferença absoluta assinada — lê o `Rd` ATUAL) —
    /// `SABA_v` / `VABA.S`.
    SABA,
    /// `Rd += |Rn-Rm|` (acumula diferença absoluta não assinada — lê o `Rd` ATUAL) — `UABA_v` /
    /// `VABA.U`.
    UABA,
    /// `a * b` truncado — sem variante assinada/não assinada (mesmo padrão de bits) — `MUL_v` /
    /// `VMUL` inteiro.
    MUL,
    /// Multiplicação polinomial (`GF(2)`, `byte` apenas) — XOR de `a<<i` para cada bit `i` setado
    /// de `b`, truncado a 8 bits — `PMUL_v` / `VMUL.P8`.
    PMUL,
    /// `Rd += Rn * Rm` (multiply-accumulate — lê o `Rd` ATUAL) — `MLA_v` / `VMLA` inteiro.
    MLA,
    /// `Rd -= Rn * Rm` (multiply-subtract — lê o `Rd` ATUAL) — `MLS_v` / `VMLS` inteiro.
    MLS,
    /// `a & b` (`AND_v` / `VAND`).
    AND,
    /// `a & ~b` (`BIC_v` / `VBIC`).
    BIC,
    /// `a | b` (`ORR_v` / `VORR`).
    ORR,
    /// `a | ~b` (`ORN_v` / `VORN`).
    ORN,
    /// `a ^ b` (`EOR_v` / `VEOR`).
    EOR,
    /// "Bitwise select": `(Rd_atual & a) | (~Rd_atual & b)` — `Rd` é a MÁSCARA de controle, lida
    /// ATUAL — `BSL_v` / `VBSL`.
    BSL,
    /// "Bitwise insert if true": `(a & b) | (Rd_atual & ~b)` — `b`(`Rm`) é a máscara — `BIT_v` /
    /// `VBIT`.
    BIT,
    /// "Bitwise insert if false": `(a & ~b) | (Rd_atual & b)` — `b`(`Rm`) é a máscara — `BIF_v` /
    /// `VBIF`.
    BIF,
    /// `SignedSaturate(sext(a)+sext(b))` — `SQADD_v` / `VQADD.S`. Só o VALOR saturado é observável;
    /// o bit cumulativo `FPSCR.QC`/`FPSR.QC` NÃO é modelado (paridade com o A64 — B13.5).
    SQADD,
    /// `UnsignedSaturate(a+b)` — `UQADD_v` / `VQADD.U`.
    UQADD,
    /// `SignedSaturate(sext(a)-sext(b))` — `SQSUB_v` / `VQSUB.S`.
    SQSUB,
    /// `UnsignedSaturate(a-b)` (satura em `0` por baixo) — `UQSUB_v` / `VQSUB.U`.
    UQSUB,
    /// Deslocamento por REGISTRADOR, assinado, truncando (`Elem[m,e,8]` como quantidade — só o BYTE
    /// BAIXO de `b`; `>=0` desloca à esquerda, `<0` à direita aritmética) — `SSHL_v` / `VSHL.S`
    /// (encoding `@3same_rev`: valor = `Vm`, quantidade = `Vn`).
    SSHL,
    /// Como {@link #SSHL}, não assinado (deslocamento à direita lógico) — `USHL_v` / `VSHL.U`.
    USHL,
    /// Como {@link #SSHL}, com ARREDONDAMENTO no deslocamento à direita (`>=0` é deslocamento à
    /// esquerda puro) — `SRSHL_v` / `VRSHL.S`.
    SRSHL,
    /// Como {@link #SRSHL}, não assinado — `URSHL_v` / `VRSHL.U`.
    URSHL,
    /// Deslocamento por REGISTRADOR com SATURAÇÃO quando `>=0` (esquerda); `<0` comporta-se como
    /// {@link #SSHL} (direita, sem saturar) — `SQSHL_v` / `VQSHL.S`.
    SQSHL,
    /// Como {@link #SQSHL}, não assinado — `UQSHL_v` / `VQSHL.U`.
    UQSHL,
    /// Como {@link #SQSHL}, mas o lado `<0` (direita) usa ARREDONDAMENTO (como {@link #SRSHL}) —
    /// `SQRSHL_v` / `VQRSHL.S`.
    SQRSHL,
    /// Como {@link #SQRSHL}, não assinado — `UQRSHL_v` / `VQRSHL.U`.
    UQRSHL,
    /// Multiplicação dobrada de alta ordem, saturante: `SignedSaturate((2*sext(a)*sext(b)) >> esize)`
    /// — só `esz` `1`(H)/`2`(S) — `SQDMULH_v` / `VQDMULH`.
    SQDMULH,
    /// Como {@link #SQDMULH}, com ARREDONDAMENTO antes do deslocamento — `SQRDMULH_v` / `VQRDMULH`.
    SQRDMULH,
    /// `SignedSaturate(Rd + RoundingDoublingMultiplyHigh(sext(a), sext(b)))` — `FEAT_RDM`
    /// (`ArmFeature.ADVANCED_SIMD_RDM`, `ARMv8.1`). RMW (lê o `Rd` ATUAL, mesma disciplina de
    /// {@link #MLA}/{@link #SABA}), com DUAS saturações independentes (a de {@link #SQRDMULH} interna
    /// e a da soma final). Só `esz` `1`(H)/`2`(S) — `SQRDMLAH_v` / `VQRDMLAH`.
    SQRDMLAH,
    /// Como {@link #SQRDMLAH}, mas SUBTRAI do `Rd` ATUAL — `SQRDMLSH_v` / `VQRDMLSH`.
    SQRDMLSH
}
