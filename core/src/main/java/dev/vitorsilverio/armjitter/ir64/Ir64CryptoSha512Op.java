package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoSha512ThreeRegister} (`FEAT_SHA512`, ARMv8.2-A, B19.10) — mesma
/// extensão criptográfica opcional de {@link Ir64CryptoShaThreeRegisterOp} (SHA1/SHA256), mas
/// operando em elementos de **64 bits** (SHA-512 usa palavras de 64, não 32).
public enum Ir64CryptoSha512Op {
    /// `SHA512H`: passo de compressão SHA-512, metade "ab"/"cd" do estado (ver executor).
    SHA512H,
    /// `SHA512H2`: passo de compressão SHA-512, a outra metade.
    SHA512H2,
    /// `SHA512SU1`: atualização de agenda de mensagem SHA-512 (3 registradores).
    SHA512SU1
}
