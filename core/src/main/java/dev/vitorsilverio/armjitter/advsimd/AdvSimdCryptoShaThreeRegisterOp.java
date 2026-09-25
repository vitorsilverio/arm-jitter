package dev.vitorsilverio.armjitter.advsimd;

/// Operação "Cryptographic three-register SHA" — núcleo COMPARTILHADO (RFC B13.2, D1) migrado de
/// {@link dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp} (B8.11b) na task B13.23,
/// que lhe dá o primeiro consumidor A32 (`neon-dp.decode` seção "3-reg-same", `opc=1100`/`op=0`,
/// discriminadas por `U`/`size` — QEMU `DO_SHA1_3`/`DO_SHA256_3`). Mirror EXATO (7 valores nos dois
/// lados).
public enum AdvSimdCryptoShaThreeRegisterOp {
    /// `SHA1C` (`U=0 size=00`): rodada SHA1 "Choose".
    SHA1C,
    /// `SHA1P` (`U=0 size=01`): rodada SHA1 "Parity".
    SHA1P,
    /// `SHA1M` (`U=0 size=10`): rodada SHA1 "Majority".
    SHA1M,
    /// `SHA1SU0` (`U=0 size=11`): primeira metade da atualização de agenda de mensagem SHA1.
    SHA1SU0,
    /// `SHA256H` (`U=1 size=00`): rodada SHA256, metade baixa do estado.
    SHA256H,
    /// `SHA256H2` (`U=1 size=01`): rodada SHA256, metade alta do estado.
    SHA256H2,
    /// `SHA256SU1` (`U=1 size=10`): segunda metade da atualização de agenda de mensagem SHA256.
    SHA256SU1
}
