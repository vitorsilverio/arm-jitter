package dev.vitorsilverio.armjitter.core;

/// Condicoes ARM avaliadas contra os flags NZCV do CPSR.
public enum Condition {
    /// Igual: Z setado.
    EQ,
    /// Diferente: Z limpo.
    NE,
    /// Carry setado.
    CS,
    /// Carry limpo.
    CC,
    /// Negativo.
    MI,
    /// Positivo/não-negativo.
    PL,
    /// Overflow setado.
    VS,
    /// Overflow limpo.
    VC,
    /// Maior, sem sinal.
    HI,
    /// Menor ou igual, sem sinal.
    LS,
    /// Maior ou igual, com sinal.
    GE,
    /// Menor que, com sinal.
    LT,
    /// Maior que, com sinal.
    GT,
    /// Menor ou igual, com sinal.
    LE,
    /// Sempre.
    AL
}
