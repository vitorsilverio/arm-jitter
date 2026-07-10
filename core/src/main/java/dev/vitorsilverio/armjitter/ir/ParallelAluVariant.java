package dev.vitorsilverio.armjitter.ir;

/// Variante (prefixo) de uma operação de aritmética paralela ARMv6
/// ({@link IrOp.ParallelAlu}). Regra de semântica por variante:
///
/// - sem prefixo (`SIGNED`/`UNSIGNED`): resultado com wrap E escrita dos flags GE do CPSR
///   (com sinal: lane ≥ 0; sem sinal: carry na soma, ausência de borrow na subtração);
/// - `Q`/`UQ` (saturadas): resultado saturado na faixa da lane, SEM tocar GE — e, ao contrário
///   de QADD/QSUB (ARMv5TE), também SEM tocar o flag Q sticky;
/// - `SH`/`UH` (halving): resultado deslocado 1 bit à direita (sem saturar), SEM tocar GE.
public enum ParallelAluVariant {
    /// Sem prefixo, operandos com sinal (`SADD16`...): wrap + escreve GE.
    SIGNED(false, false, false, true),
    /// Prefixo `Q`, saturação com sinal (`QADD16`...): não escreve GE nem Q.
    SIGNED_SATURATING(false, true, false, false),
    /// Prefixo `SH`, halving com sinal (`SHADD16`...): resultado >> 1, não escreve GE.
    SIGNED_HALVING(false, false, true, false),
    /// Prefixo `U`, operandos sem sinal (`UADD16`...): wrap + escreve GE (regra de carry/borrow).
    UNSIGNED(true, false, false, true),
    /// Prefixo `UQ`, saturação sem sinal (`UQADD16`...): não escreve GE nem Q.
    UNSIGNED_SATURATING(true, true, false, false),
    /// Prefixo `UH`, halving sem sinal (`UHADD16`...): resultado >> 1, não escreve GE.
    UNSIGNED_HALVING(true, false, true, false);

    private final boolean unsigned;
    private final boolean saturating;
    private final boolean halving;
    private final boolean writesGe;

    ParallelAluVariant(boolean unsigned, boolean saturating, boolean halving, boolean writesGe) {
        this.unsigned = unsigned;
        this.saturating = saturating;
        this.halving = halving;
        this.writesGe = writesGe;
    }

    /// `true` quando as lanes são interpretadas sem sinal (prefixos U/UQ/UH).
    public boolean unsigned() {
        return unsigned;
    }

    /// `true` para as variantes saturadas (prefixos Q/UQ).
    public boolean saturating() {
        return saturating;
    }

    /// `true` para as variantes halving (prefixos SH/UH), que deslocam o resultado 1 bit.
    public boolean halving() {
        return halving;
    }

    /// `true` quando a variante escreve os flags GE do CPSR (apenas as formas sem prefixo).
    public boolean writesGe() {
        return writesGe;
    }
}
