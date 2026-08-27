package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoShaTwoRegister} ("Cryptographic two-register SHA", B8.11b —
/// mesma ARMv8-A Cryptographic Extension de {@link Ir64CryptoAesOp}).
public enum Ir64CryptoShaTwoRegisterOp {
    /// `SHA1H`: função de rotação de estado SHA1 (`ROR` de 2 bits na palavra 0 de {@code rn}) — pura
    /// função de {@code rn}, {@code rd} atual é ignorado.
    SHA1H,
    /// `SHA1SU1`: segunda metade da atualização de agenda de mensagem SHA1 (lê/escreve {@code rd}).
    SHA1SU1,
    /// `SHA256SU0`: primeira metade da atualização de agenda de mensagem SHA256 (lê/escreve
    /// {@code rd}).
    SHA256SU0
}
