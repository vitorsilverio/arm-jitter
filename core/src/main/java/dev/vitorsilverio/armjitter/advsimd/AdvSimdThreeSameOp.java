package dev.vitorsilverio.armjitter.advsimd;

/// Operação "three same" (dois operandos vetoriais do MESMO arranjo, resultado no mesmo arranjo)
/// do núcleo vetorial COMPARTILHADO entre AArch64 (`ADD_v`/`SUB_v`/`CM**_v`/..., B8.7) e NEON de
/// 32 bits (`VADD`/`VSUB`/`VCGT`/`VAND`/..., B13.4) — RFC B13.2, decisão D1.
///
/// Cobre o subconjunto **inteiro não saturante, sem deslocamento por registrador** de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp} (mesmos nomes). As 16 operações
/// saturantes/de deslocamento (`SQADD`..`SQRDMLSH`) NÃO estão aqui: a migração delas é B13.5, e
/// enquanto isso o executor A64 as resolve no `switch` local
/// (`Ir64VectorArithmeticExecutor#sharedThreeSameOp` devolve `null` para elas). Cada operação
/// existe em exatamente UM lugar — o que está aqui saiu do `switch` A64.
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
    BIF
}
