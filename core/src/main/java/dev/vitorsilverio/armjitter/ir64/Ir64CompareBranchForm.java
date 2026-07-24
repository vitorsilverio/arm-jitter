package dev.vitorsilverio.armjitter.ir64;

/// Forma de {@link Ir64Op.CompareBranch64}: `CBZ`/`CBNZ` comparam o registrador inteiro (largura
/// `W` ou `X`) contra zero; `TBZ`/`TBNZ` testam um único bit (posição `0`-`63`, sempre lido do
/// registrador `X` completo — não há noção de largura "W"/"X" para um único bit).
public enum Ir64CompareBranchForm {
    /// `CBZ`/`CBNZ Rt, label` — compara o registrador inteiro contra zero.
    CBZ_CBNZ,
    /// `TBZ`/`TBNZ Rt, #bit, label` — testa um único bit de `Rt`.
    TBZ_TBNZ
}
