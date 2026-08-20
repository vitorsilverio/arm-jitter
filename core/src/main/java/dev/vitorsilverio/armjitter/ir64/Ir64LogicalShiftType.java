package dev.vitorsilverio.armjitter.ir64;

/// Tipo de deslocamento aplicado ao segundo operando de {@link Ir64Op.LogicalShiftedRegister}
/// (`ARM DDI 0487 C6.2.9` variante registrador, campo `st` de 2 bits `[23:22]`, B6.9). Ao
/// contrário de {@link Ir64ShiftType} (usado por {@link Ir64Op.AluShiftedRegister}, só `ADD`/
/// `SUB`, onde `11` é RESERVADO), aqui as 4 combinações são válidas — `Logical (shifted
/// register)` é a única forma da ISA A64 com `ROR` de registrador completo. Não reaproveitar
/// {@link Ir64ShiftType} para este propósito (ver B6.9 Decisão D1): os dois enums têm
/// contratos diferentes (3 vs. 4 valores válidos) e switches exaustivos sobre um não devem
/// precisar tratar um caso inalcançável do outro.
public enum Ir64LogicalShiftType {
    /// `LSL` (`00`): deslocamento lógico à esquerda.
    LSL,
    /// `LSR` (`01`): deslocamento lógico à direita.
    LSR,
    /// `ASR` (`10`): deslocamento aritmético à direita (preserva o sinal).
    ASR,
    /// `ROR` (`11`): rotação à direita — válida apenas nesta forma, reservada em
    /// {@link Ir64ShiftType}.
    ROR
}
