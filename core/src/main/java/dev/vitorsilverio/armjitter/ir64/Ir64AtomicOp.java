package dev.vitorsilverio.armjitter.ir64;

/// Operação de leitura-modificação-escrita atômica de uma instrução `LDADD`/`LDCLR`/`LDEOR`/
/// `LDSET`/`LDSMAX`/`LDSMIN`/`LDUMAX`/`LDUMIN`/`SWP` da extensão LSE (`FEAT_LSE`, ARMv8.1-A;
/// `ARM DDI 0487 C6.2.{LDADD…SWP}`, B19.1). Campo semântico de {@link Ir64Op.AtomicMemoryOp} —
/// o executor lê `[Rn]`, calcula `<operação>(old, Rs)`, escreve o resultado de volta e devolve
/// `old` (zero-estendido) em `Rt`.
public enum Ir64AtomicOp {
    /// `LDADD` — soma sem sinal (`old + Rs`, com wrap na largura de memória).
    ADD,
    /// `LDCLR` — limpa bits (`old & ~Rs`).
    CLR,
    /// `LDEOR` — ou-exclusivo (`old ^ Rs`).
    EOR,
    /// `LDSET` — seta bits (`old | Rs`).
    SET,
    /// `LDSMAX` — máximo COM sinal (operandos estendidos de sinal a partir da largura de memória).
    SMAX,
    /// `LDSMIN` — mínimo COM sinal.
    SMIN,
    /// `LDUMAX` — máximo SEM sinal.
    UMAX,
    /// `LDUMIN` — mínimo SEM sinal.
    UMIN,
    /// `SWP` — troca pura (o valor novo é `Rs`, `old` não participa da escrita).
    SWP
}
