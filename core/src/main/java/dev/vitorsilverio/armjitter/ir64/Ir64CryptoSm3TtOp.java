package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoSm3ThreeRegisterImm2} (`FEAT_SM3`, ARMv8.2-A, B19.10) — as 4
/// variantes de "passo de compressão" SM3, discriminadas por um campo `op` de 2 bits no encoding
/// real (bits[13:12], **não** o `imm2` operando — ver o javadoc do record e a Armadilha 3 da task).
public enum Ir64CryptoSm3TtOp {
    /// `SM3TT1A`.
    TT1A,
    /// `SM3TT1B`.
    TT1B,
    /// `SM3TT2A`.
    TT2A,
    /// `SM3TT2B`.
    TT2B
}
