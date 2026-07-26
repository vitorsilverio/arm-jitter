package dev.vitorsilverio.armjitter.ir64;

/// Tipo de deslocamento aplicado ao segundo operando de {@link Ir64Op.AluShiftedRegister}
/// (`ARM DDI 0487 C6.2.4` variante registrador, campo `shift` de 2 bits `[23:22]`). Só as 3
/// combinações válidas para `ADD`/`SUB` existem aqui — `11` (que seria `ROR` em outras
/// instruções, ex. `Logical (shifted register)`) é RESERVADO para `ADD`/`SUB` e nunca
/// decodificado (ver {@code Aarch64Decoder}). Não confundir com
/// {@link Ir64ExtendType}/{@link Ir64AluExtendType}, usados pela forma `extended register`
/// (um modo de operando totalmente diferente — ver B6.3.1 Fatos de referência #4/#5).
public enum Ir64ShiftType {
    /// `LSL` (`00`): deslocamento lógico à esquerda.
    LSL,
    /// `LSR` (`01`): deslocamento lógico à direita.
    LSR,
    /// `ASR` (`10`): deslocamento aritmético à direita (preserva o sinal).
    ASR
}
