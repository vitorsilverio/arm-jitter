package dev.vitorsilverio.armjitter.ir64;

/// Operação de {@link Ir64Op.CryptoShaThreeRegister} ("Cryptographic three-register SHA", B8.11b —
/// mesma ARMv8-A Cryptographic Extension de {@link Ir64CryptoAesOp}, opcional mas presente no
/// Cortex-A53 do raspi3).
public enum Ir64CryptoShaThreeRegisterOp {
    /// `SHA1C`: passo de compressão SHA1 usando `Ch` (escolha) como função de rodada.
    SHA1C,
    /// `SHA1P`: passo de compressão SHA1 usando `Parity` (paridade) como função de rodada.
    SHA1P,
    /// `SHA1M`: passo de compressão SHA1 usando `Maj` (maioria) como função de rodada.
    SHA1M,
    /// `SHA1SU0`: atualização de agenda de mensagem SHA1 (`XOR` de 3 palavras de 64 bits) — não usa
    /// o laço de 4 rodadas das outras 3 (semântica própria, ver o executor de `executor64`).
    SHA1SU0,
    /// `SHA256H`: passo de compressão SHA256, metade "ABEF" do estado.
    SHA256H,
    /// `SHA256H2`: passo de compressão SHA256, metade "CDGH" do estado.
    SHA256H2,
    /// `SHA256SU1`: atualização de agenda de mensagem SHA256 (3 registradores).
    SHA256SU1
}
