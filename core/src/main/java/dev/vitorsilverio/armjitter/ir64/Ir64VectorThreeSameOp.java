package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.VectorArithmeticThreeSame} (AdvSIMD "three same", B8.7) — inteiro,
/// mesmo tamanho de elemento nos 3 operandos (`Rd`/`Rn`/`Rm`). Cobre também a forma ESCALAR
/// (`ADD_s`/`SUB_s`/`CM**_s`, sempre `esz=3`/`q=false` — ver {@link Ir64Op.VectorArithmeticThreeSame}),
/// que reaproveita este mesmo record/enum em vez de um tipo próprio.
public enum Ir64VectorThreeSameOp {
    /// `a + b` truncado ao tamanho do elemento.
    ADD,
    /// `a - b` truncado ao tamanho do elemento.
    SUB,
    /// `a > b` (assinado) — elemento vira todos-1 ou `0`.
    CMGT,
    /// `a > b` (não assinado, "higher") — elemento vira todos-1 ou `0`.
    CMHI,
    /// `a >= b` (assinado) — elemento vira todos-1 ou `0`.
    CMGE,
    /// `a >= b` (não assinado, "higher or same") — elemento vira todos-1 ou `0`.
    CMHS,
    /// `(a & b) != 0` — elemento vira todos-1 ou `0`.
    CMTST,
    /// `a == b` — elemento vira todos-1 ou `0`.
    CMEQ,
    /// Soma "halving" assinada: `(sext(a)+sext(b)) >> 1` (aritmético).
    SHADD,
    /// Soma "halving" não assinada: `(a+b) >>> 1` (lógico).
    UHADD,
    /// Subtração "halving" assinada: `(sext(a)-sext(b)) >> 1` (aritmético).
    SHSUB,
    /// Subtração "halving" não assinada: `(a-b) >>> 1` (lógico, `a`/`b` já zero-extendidos).
    UHSUB,
    /// Soma "halving" assinada COM arredondamento: `(sext(a)+sext(b)+1) >> 1`.
    SRHADD,
    /// Soma "halving" não assinada COM arredondamento: `(a+b+1) >>> 1`.
    URHADD,
    /// Máximo assinado.
    SMAX,
    /// Máximo não assinado.
    UMAX,
    /// Mínimo assinado.
    SMIN,
    /// Mínimo não assinado.
    UMIN,
    /// `|sext(a)-sext(b)|` (diferença absoluta assinada).
    SABD,
    /// `|a-b|` (diferença absoluta não assinada).
    UABD,
    /// `Rd += |sext(Rn)-sext(Rm)|` (acumula diferença absoluta assinada — lê o `Rd` ATUAL).
    SABA,
    /// `Rd += |Rn-Rm|` (acumula diferença absoluta não assinada — lê o `Rd` ATUAL).
    UABA,
    /// `a * b` truncado — sem variante assinada/não assinada (mesmo padrão de bits nos dois casos).
    MUL,
    /// Multiplicação polinomial (`GF(2)`, `byte` apenas) — XOR de `a<<i` para cada bit `i` setado
    /// de `b`, truncado a 8 bits.
    PMUL,
    /// `Rd += Rn * Rm` (multiply-accumulate — lê o `Rd` ATUAL).
    MLA,
    /// `Rd -= Rn * Rm` (multiply-subtract — lê o `Rd` ATUAL).
    MLS
}
