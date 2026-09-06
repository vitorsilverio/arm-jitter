package dev.vitorsilverio.armjitter.advsimd;

/// Operação AES da ARMv8-A Cryptographic Extension — núcleo COMPARTILHADO (RFC B13.2, D1) migrado
/// de {@link dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp} (B8.11) na task B13.15, que lhe dá o
/// primeiro consumidor A32 (`AESE`/`AESD`/`AESMC`/`AESIMC`, `neon-dp.decode` "2-reg-misc"
/// `opc1=0b00`/`opc2` `0110`/`0111`, `size` fixo em `0b00`). Mirror EXATO (4 valores nos dois
/// lados) — mesma semântica, só o encoding muda entre A64 e A32.
public enum AdvSimdCryptoAesOp {
    /// Rodada de cifra: `Vd = ShiftRows(SubBytes(Vd XOR Vm))` — lê `Vd` ATUAL como primeiro
    /// operando (não é uma operação unária de `Vm`).
    AESE,
    /// Rodada de decifra: `Vd = InvShiftRows(InvSubBytes(Vd XOR Vm))`.
    AESD,
    /// `Vd = MixColumns(Vm)` — puramente função de `Vm`, `Vd` atual é ignorado.
    AESMC,
    /// `Vd = InvMixColumns(Vm)` — puramente função de `Vm`.
    AESIMC
}
