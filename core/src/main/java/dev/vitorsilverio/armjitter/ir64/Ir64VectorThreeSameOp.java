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
    MLS,
    /// `SignedSaturate(sext(a)+sext(b))` (B8.8) — forma escalar aceita QUALQUER `esz` (`0`-`3`,
    /// diferente de `ADD_s`/`SUB_s`/`CM**_s`, que são D-only).
    SQADD,
    /// `UnsignedSaturate(a+b)` (B8.8) — mesma observação de escalar de {@link #SQADD}.
    UQADD,
    /// `SignedSaturate(sext(a)-sext(b))` (B8.8).
    SQSUB,
    /// `UnsignedSaturate(a-b)` (B8.8, satura em `0` por baixo).
    UQSUB,
    /// Deslocamento por REGISTRADOR, assinado, truncando (`Elem[m,e,8]` como quantidade — só o
    /// BYTE BAIXO de `b`, não `sext(b,esz)`; `>=0` desloca à esquerda, `<0` à direita) — B8.8. Forma
    /// escalar é D-only.
    SSHL,
    /// Como {@link #SSHL}, mas não assinado (deslocamento à direita lógico) — B8.8. Forma escalar
    /// D-only.
    USHL,
    /// Como {@link #SSHL}, mas com ARREDONDAMENTO no deslocamento à direita (`>=0` continua sem
    /// arredondar, é deslocamento à esquerda puro) — B8.8. Forma escalar D-only.
    SRSHL,
    /// Como {@link #SRSHL}, não assinado — B8.8. Forma escalar D-only.
    URSHL,
    /// Deslocamento por REGISTRADOR com SATURAÇÃO quando `>=0` (esquerda); quando `<0` comporta-se
    /// como {@link #SSHL} (direita, sem saturar) — B8.8. Forma escalar aceita qualquer `esz`.
    SQSHL,
    /// Como {@link #SQSHL}, não assinado — B8.8. Forma escalar aceita qualquer `esz`.
    UQSHL,
    /// Como {@link #SQSHL}, mas o lado `<0` (direita) usa ARREDONDAMENTO (como {@link #SRSHL}) em
    /// vez de truncar puro — B8.8. Forma escalar aceita qualquer `esz`.
    SQRSHL,
    /// Como {@link #SQRSHL}, não assinado — B8.8. Forma escalar aceita qualquer `esz`.
    UQRSHL,
    /// Multiplicação dobrada de alta ordem, saturante: `SignedSaturate((2*sext(a)*sext(b)) >>
    /// esize)` — só `esz` `1`(H)/`2`(S) — B8.8. Forma escalar aceita `H`/`S`.
    SQDMULH,
    /// Como {@link #SQDMULH}, com ARREDONDAMENTO antes do deslocamento — B8.8.
    SQRDMULH,
    /// `SignedSaturate(Rd + RoundingDoublingMultiplyHigh(sext(a), sext(b)))` — `FEAT_RDM`
    /// (`ARMv8.1-A`, B11.4). Acumula sobre o `Rd` ATUAL (mesma disciplina RMW de {@link #MLA}/
    /// {@link #SABA}), com DUAS saturações independentes: a de {@link #SQRDMULH} embutida no
    /// `RoundingDoublingMultiplyHigh` e a da soma final. Só `esz` `1`(H)/`2`(S), mesma restrição de
    /// {@link #SQDMULH}.
    SQRDMLAH,
    /// Como {@link #SQRDMLAH}, mas SUBTRAI do `Rd` ATUAL em vez de somar.
    SQRDMLSH,
    /// `a & b` (B8.18) — AdvSIMD "three same" LÓGICO: vive no MESMO slot `bit10=1` deste enum, mas
    /// discriminado pelo campo que para o resto da tabela é `esz` (aqui não é tamanho de elemento —
    /// lógico bit a bit não distingue lane, sempre executado a `esz=0`, ver
    /// {@link Ir64Op.VectorArithmeticThreeSame#esz}). Sem forma escalar real.
    AND,
    /// `a & ~b` (B8.18).
    BIC,
    /// `a | b` (B8.18).
    ORR,
    /// `a | ~b` (B8.18).
    ORN,
    /// `a ^ b` (B8.18).
    EOR,
    /// "Bitwise select": `(Rd_atual & a) | (~Rd_atual & b)` — `Rd` é a MÁSCARA de controle, lida
    /// ATUAL (B8.18, mesma disciplina RMW de {@link #SABA}/{@link #MLA}).
    BSL,
    /// "Bitwise insert if true": `(a & b) | (Rd_atual & ~b)` — `b`(`Rm`) é a máscara (B8.18).
    BIT,
    /// "Bitwise insert if false": `(a & ~b) | (Rd_atual & b)` — `b`(`Rm`) é a máscara (B8.18).
    BIF
}
