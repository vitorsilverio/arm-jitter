package dev.vitorsilverio.armjitter.ir64;

/// Condição de `CB_cond`/`CB_cond_imm` (`FEAT_CMPBR`, `ARM DDI 0487` `C6.2.53`/`C6.2.54`) — um
/// subconjunto de 6 valores (campo `cc` de 3 bits no encoding, 2 combinações reservadas), bem menor
/// que os 16 códigos de {@link Ir64Condition}. Deliberadamente SEPARADA de {@link Ir64Condition}:
/// esta condição nunca lê `PSTATE.{N,Z,C,V}` (`CB_cond`/`CB_cond_imm` comparam os operandos
/// diretamente, sem tocar `NZCV`) — ver `Ir64Op.CompareAndBranchRegister`/
/// `Ir64Op.CompareAndBranchImmediate`.
///
/// **O mapeamento do campo `cc` de 3 bits é DIFERENTE entre a forma registrador e a forma
/// imediata** (achado confirmado contra `trans_CB_cond`/`trans_CB_cond_imm` do QEMU real,
/// `target/arm/tcg/translate-a64.c`): a forma registrador usa `GE`/`GEU` onde a forma imediata usa
/// `LT`/`LTU` no MESMO valor de `cc` — os decoders de cada forma têm sua PRÓPRIA tabela, nenhuma
/// função de conversão única serve às duas.
public enum Ir64CompareBranchCondition {
    /// Maior que, com sinal.
    GREATER_THAN,
    /// Maior ou igual, com sinal (só na forma registrador).
    GREATER_OR_EQUAL,
    /// Maior que, sem sinal.
    GREATER_THAN_UNSIGNED,
    /// Maior ou igual, sem sinal (só na forma registrador).
    GREATER_OR_EQUAL_UNSIGNED,
    /// Menor que, com sinal (só na forma imediata).
    LESS_THAN,
    /// Menor que, sem sinal (só na forma imediata).
    LESS_THAN_UNSIGNED,
    /// Igual.
    EQUAL,
    /// Diferente.
    NOT_EQUAL;

    /// `true` para as condições com sinal (`GT`/`GE`/`LT`) — o executor estende os operandos com
    /// sinal antes de comparar quando `esz`/`sf` é mais estreito que 64 bits; `false` para as sem
    /// sinal (`GTU`/`GEU`/`LTU`) e para `EQ`/`NE` (a extensão não muda o resultado de igualdade,
    /// desde que aplicada de forma consistente aos dois operandos — mesmo achado documentado em
    /// `trans_CB_cond` via `is_signed_cond`).
    public boolean isSigned() {
        return switch (this) {
            case GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN -> true;
            default -> false;
        };
    }
}
