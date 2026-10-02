package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Operações da Cryptographic Extension do AArch32 (AES, SHA-1, SHA-256).
///
/// Sub-interface selada de {@link NeonOp} (task E15.3): os records desta família vivem aqui, e o
/// `switch` por padrão sobre {@link IrOp} continua exaustivo pela hierarquia selada.
public sealed interface NeonCryptoOp extends NeonOp permits NeonCryptoOp.Aes, NeonCryptoOp.Sha,
        NeonCryptoOp.ShaThree {

    /// NEON/Advanced SIMD de 32 bits — `AESE`/`AESD`/`AESMC`/`AESIMC` (B13.15, ARMv8-A Cryptographic
    /// Extension, `neon-dp.decode` "2-reg-misc" `opc1=0b00`/`opc2` `0110`/`0111`, `size` fixo em
    /// `0b00`, `Q` fixo — sempre 128 bits). Gate: {@link
    /// dev.vitorsilverio.armjitter.arch.ArmFeature#CRYPTO}, SEPARADO de `ADVANCED_SIMD` (um núcleo
    /// pode ter NEON sem a extensão cripto opcional).
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.CryptoOp64.Aes} no ENCODING/IR; a
    /// SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#aes}), RFC B13.2 D1 — migração completa em
    /// B13.15 (o A64 passou a delegar também).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Aes(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoAesOp op,
            /// Registrador de destino (e, para `AESE`/`AESD`, primeiro operando), em índice de `D`
            /// PAR que inicia o `Q` (`0`-`31`).
            int vd,
            /// Registrador fonte, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vm) implements NeonCryptoOp {
        @Override public int kind() { return Kind.NEON_CRYPTO_AES; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonCryptoAes(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits — `SHA1H`/`SHA1SU1`/`SHA256SU0` ("Cryptographic two-register
    /// SHA", B13.15, MESMA extensão de {@link Aes}, `neon-dp.decode` "2-reg-misc"
    /// `opc1=0b01`/`opc2=0b0101` ou `opc1=0b10`/`opc2=0b0111`, `size` fixo em `0b10`, `Q` fixo).
    /// Gate: {@link dev.vitorsilverio.armjitter.arch.ArmFeature#CRYPTO}.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.CryptoOp64.ShaTwoRegister} no
    /// ENCODING/IR; a SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#shaTwoRegister}), RFC B13.2 D1.
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record Sha(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaOp op,
            /// Registrador de destino, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vd,
            /// Registrador fonte, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vm) implements NeonCryptoOp {
        @Override public int kind() { return Kind.NEON_CRYPTO_SHA; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonCryptoSha(core, this); return false; }
    }

    /// NEON/Advanced SIMD de 32 bits — `SHA1C`/`SHA1P`/`SHA1M`/`SHA1SU0`/`SHA256H`/`SHA256H2`/
    /// `SHA256SU1` (B13.23, ARMv8-A Cryptographic Extension, `neon-dp.decode` seção "3-reg-same"
    /// `opc=1100`/`op=0`, discriminadas por `U`/`size`, `Q` fixo — sempre 128 bits, MESMA extensão de
    /// {@link Aes}/{@link Sha}). Gate: {@link
    /// dev.vitorsilverio.armjitter.arch.ArmFeature#CRYPTO}.
    ///
    /// Espelho de {@link dev.vitorsilverio.armjitter.ir64.CryptoOp64.ShaThreeRegister} no
    /// ENCODING/IR; a SEMÂNTICA vem do núcleo COMPARTILHADO ({@link
    /// dev.vitorsilverio.armjitter.advsimd.AdvSimdCrypto#shaThreeRegister}), RFC B13.2 D1 (o A64
    /// passou a delegar também).
    ///
    /// NEON vive no espaço incondicional (`cond=0b1111`): {@link #condition()} é sempre
    /// {@link Condition#AL}.
    record ShaThree(
            /// Operação a executar (núcleo compartilhado).
            dev.vitorsilverio.armjitter.advsimd.AdvSimdCryptoShaThreeRegisterOp op,
            /// Registrador de destino (e primeiro operando, lido em todas as 7 formas), em índice de
            /// `D` PAR que inicia o `Q` (`0`-`31`).
            int vd,
            /// Segundo operando, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vn,
            /// Terceiro operando, em índice de `D` PAR que inicia o `Q` (`0`-`31`).
            int vm) implements NeonCryptoOp {
        @Override public int kind() { return Kind.NEON_CRYPTO_SHA_THREE_REGISTER; }
        @Override public boolean execute(IrBlockExecutor executor, ArmCore core, int blockEndPc) { executor.neonExecutor().executeNeonCryptoShaThree(core, this); return false; }
    }
}
