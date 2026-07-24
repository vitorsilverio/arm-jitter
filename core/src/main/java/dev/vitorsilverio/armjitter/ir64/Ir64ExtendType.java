package dev.vitorsilverio.armjitter.ir64;

/// Extensão aplicada ao registrador de deslocamento (`Rm`) na forma
/// {@link Ir64AddressingMode#REGISTER_OFFSET} de load/store (`ARM DDI 0487 C4.1.3`, campo
/// `option` de 3 bits em `[15:13]`). Só as 4 combinações válidas para load/store de registrador
/// geral existem aqui — as demais (`000`/`001`/`100`/`101`) são reservadas e nunca decodificadas
/// (ver {@code Aarch64Decoder}).
public enum Ir64ExtendType {
    /// `UXTW`: `Rm` lido como `W` (32 bits) e zero-estendido para 64 antes do deslocamento.
    UXTW,
    /// `LSL` (ou nenhuma extensão explícita): `Rm` usado como `X` completo. Mesmo encoding de
    /// `option=011` nas duas formas — a diferença entre "`[Xn, Xm]`" e "`[Xn, Xm, lsl #n]`" é só
    /// se a quantidade de deslocamento (`S`) está setada.
    LSL,
    /// `SXTW`: `Rm` lido como `W` e SINAL-estendido para 64 antes do deslocamento.
    SXTW,
    /// `SXTX`: `Rm` usado como `X` completo (sinal-estender um valor já de 64 bits é um no-op;
    /// existe só para o mnemônico distinto de `LSL`).
    SXTX
}
