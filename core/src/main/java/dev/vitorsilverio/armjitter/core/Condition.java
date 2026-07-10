package dev.vitorsilverio.armjitter.core;

/// Condicoes ARM avaliadas contra os flags NZCV do CPSR.
public enum Condition {
    /// Equal: Z setado.
    EQ,
    /// Not equal: Z limpo.
    NE,
    /// Carry set.
    CS,
    /// Carry clear.
    CC,
    /// Minus/negative.
    MI,
    /// Plus/non-negative.
    PL,
    /// Overflow set.
    VS,
    /// Overflow clear.
    VC,
    /// Unsigned higher.
    HI,
    /// Unsigned lower or same.
    LS,
    /// Signed greater or equal.
    GE,
    /// Signed less than.
    LT,
    /// Signed greater than.
    GT,
    /// Signed less or equal.
    LE,
    /// Always.
    AL
}
