package dev.vitorsilverio.armjitter.advsimd;

/// Operação "Cryptographic two-register SHA" — núcleo COMPARTILHADO (RFC B13.2, D1) migrado de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaTwoRegisterOp} (B8.11b) na task B13.15, que
/// lhe dá o primeiro consumidor A32 (`SHA1H`/`SHA1SU1`/`SHA256SU0`, `neon-dp.decode` "2-reg-misc",
/// `size` fixo em `0b10` — QEMU `DO_2M_CRYPTO`). Mirror EXATO (3 valores nos dois lados).
public enum AdvSimdCryptoShaOp {
    /// `SHA1H`: rotação de estado SHA1 (`ROR` de 2 bits na palavra 0 de `Vm`) — pura função de
    /// `Vm`, `Vd` atual é ignorado.
    SHA1H,
    /// `SHA1SU1`: segunda metade da atualização de agenda de mensagem SHA1 (lê/escreve `Vd`).
    SHA1SU1,
    /// `SHA256SU0`: primeira metade da atualização de agenda de mensagem SHA256 (lê/escreve `Vd`).
    SHA256SU0
}
