package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoSm3ThreeRegister} (`FEAT_SM3`, ARMv8.2-A, B19.10) — hash chinês
/// SM3 (GB/T 32905-2016), mesma extensão criptográfica opcional de {@link Ir64CryptoSha512Op}
/// (`FEAT_SHA512`) e da família `SM4` (`FEAT_SM4`, ver {@link Ir64Op.CryptoSm4Encrypt}/
/// {@link Ir64Op.CryptoSm4KeyUpdate}).
public enum Ir64CryptoSm3Op {
    /// `SM3PARTW1`: atualização de agenda de mensagem, primeira metade.
    PARTW1,
    /// `SM3PARTW2`: atualização de agenda de mensagem, segunda metade.
    PARTW2
}
