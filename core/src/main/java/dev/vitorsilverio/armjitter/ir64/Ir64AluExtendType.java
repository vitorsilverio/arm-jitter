package dev.vitorsilverio.armjitter.ir64;

/// Extensão aplicada ao registrador `Rm` na forma `Add/subtract (extended register)`
/// (`ARM DDI 0487 C6.2.4`/`C6.2.339` variante estendida, campo `option` de 3 bits `[15:13]`) de
/// {@link Ir64Op.AluExtendedRegister}. **NÃO reaproveita** {@link Ir64ExtendType} (usado por
/// load/store, só 4 combinações válidas: `UXTW`/`LSL`/`SXTW`/`SXTX`) — a forma de ALU tem as
/// **8** combinações válidas do campo `option` (tamanho lido de `Rm` × sinal), decisão D3 da
/// task B6.3.1: o enum de load/store tem um contrato documentado de só 4 valores e não deveria
/// ganhar 4 que load/store nunca produz.
public enum Ir64AluExtendType {
    /// `UXTB` (`000`): lê `Rm` como byte (8 bits) e zero-estende.
    UXTB,
    /// `UXTH` (`001`): lê `Rm` como half-word (16 bits) e zero-estende.
    UXTH,
    /// `UXTW` (`010`): lê `Rm` como word (32 bits) e zero-estende.
    UXTW,
    /// `UXTX` (`011`): lê `Rm` como doubleword (64 bits) completo — mesmo encoding do alias
    /// `LSL` quando `Rd`/`Rn` é `SP` (a diferença é só textual/mnemônica, não de bits).
    UXTX,
    /// `SXTB` (`100`): lê `Rm` como byte e estende o sinal.
    SXTB,
    /// `SXTH` (`101`): lê `Rm` como half-word e estende o sinal.
    SXTH,
    /// `SXTW` (`110`): lê `Rm` como word e estende o sinal.
    SXTW,
    /// `SXTX` (`111`): lê `Rm` como doubleword completo (estender o sinal de um valor já de 64
    /// bits é um no-op; existe só pelo mnemônico distinto de `UXTX`).
    SXTX
}
