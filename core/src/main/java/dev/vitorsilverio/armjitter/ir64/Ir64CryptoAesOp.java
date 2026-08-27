package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoAes} (`AESE`/`AESD`/`AESMC`/`AESIMC`, B8.11 — ARMv8-A
/// Cryptographic Extension, opcional mas presente no Cortex-A53 do raspi3, alvo real desta task).
public enum Ir64CryptoAesOp {
    /// Rodada de cifra (`ARM DDI 0487`, `AESE`): `Rd = ShiftRows(SubBytes(Rd XOR Rn))` — lê `Rd`
    /// ATUAL como primeiro operando (não é uma operação unária de `Rn`).
    AESE,
    /// Rodada de decifra (`AESD`): `Rd = InvShiftRows(InvSubBytes(Rd XOR Rn))`.
    AESD,
    /// `AESMC`: `Rd = MixColumns(Rn)` — puramente função de `Rn`, `Rd` atual é ignorado.
    AESMC,
    /// `AESIMC`: `Rd = InvMixColumns(Rn)` — puramente função de `Rn`.
    AESIMC
}
