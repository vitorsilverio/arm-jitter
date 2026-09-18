package dev.vitorsilverio.armjitter.advsimd;

/// As 8 condições de comparação do `VCMP` MVE/Helium (perfil M, B16.8, `target/isa-decode/mve.decode`,
/// seção "Comparisons") — o arquivo real EXPANDE as condições `T1`/`T2`/`T3`+`fc` do `VCMP`/`VPT`
/// escalar do VFP em 8 mnemônicos próprios em vez de um campo `cond` genérico (comentário literal:
/// "We expand out the conditions which are split across encodings T1, T2, T3 and the fc bits").
///
/// {@link #CS}/{@link #HI} só existem nas formas INTEIRAS (`DO_VCMP_U`, `target/arm/tcg/mve_helper.c`)
/// — não têm equivalente `_fp`, porque "carry set"/"higher" são nomes de condição de comparação SEM
/// SINAL, e ponto flutuante não tem essa distinção (as 6 condições restantes cobrem FP inteiro).
public enum MveCompareCondition {
    /// `VCMPEQ`/`VCMPEQ_fp` — `DO_EQ`/`float*_eq` (`a == b`).
    EQ,
    /// `VCMPNE`/`VCMPNE_fp` — `DO_NE`/`!float*_eq` (`a != b`).
    NE,
    /// `VCMPGE`/`VCMPGE_fp` — inteira COM SINAL (`DO_GE` via `DO_VCMP_S`) / FP (`DO_GE16`/`DO_GE32`).
    GE,
    /// `VCMPLT`/`VCMPLT_fp` — inteira COM SINAL (`DO_LT`) / FP (`!DO_GE16`/`!DO_GE32`).
    LT,
    /// `VCMPGT`/`VCMPGT_fp` — inteira COM SINAL (`DO_GT`) / FP (`DO_GT16`/`DO_GT32`).
    GT,
    /// `VCMPLE`/`VCMPLE_fp` — inteira COM SINAL (`DO_LE`) / FP (`!DO_GT16`/`!DO_GT32`).
    LE,
    /// `VCMPCS` — inteira SEM SINAL, "carry set" (`DO_GE` via `DO_VCMP_U`, `unsigned >=`). Sem
    /// forma `_fp`.
    CS,
    /// `VCMPHI` — inteira SEM SINAL, "higher" (`DO_GT` via `DO_VCMP_U`, `unsigned >`). Sem forma
    /// `_fp`.
    HI
}
