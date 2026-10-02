package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;

/// Decoder do "SVE2 Crypto Extensions" (B17.24, `sve.decode` linhas 1939-1952): `AESE`/`AESD`/`AESMC`/`AESIMC`
/// (`FEAT_SVE_AES`), `SM4E`/`SM4EKEY` (`FEAT_SVE_SM4`) e `RAX1` (`FEAT_SVE_SHA3`) — as 7 linhas do prefixo `0x45`
/// com `bit 21 = 1`, o MESMO espaço reivindicado por {@link Aarch64Sve2IntegerDecoder#decodePrefix45} (narrowing,
/// B17.21b) e {@link Aarch64Sve2MiscDecoder#decodePrefix45} (`MATCH`/`HISTCNT`/`LUTI*`, B17.22) — mask/value de
/// cada linha medidos byte a byte (nenhum dos 7 colide com o que os dois decoders acima já reivindicam; chamar
/// este decoder DEPOIS dos outros dois, mesma disciplina).
///
/// Cada `MASK`/`VALUE` é o `decodetree` transcrito diretamente (nenhum campo compartilhado é deduzido por
/// analogia), conferido contra `aarch64-none-elf-as -march=armv9.5-a+sve2+sve-aes+sve-sm4+sve-sha3`. O que não
/// bate nenhum dos 7 devolve `null` (G8).
final class Aarch64SveCryptoDecoder {
    private static final int REGISTER5_MASK = 0b11111;
    private static final int RD_SHIFT = 0;
    /// `AESE`/`AESD`/`SM4E` (`@rdn_rm_e0`): o segundo operando mora em `bits[9:5]` — POSIÇÃO DIFERENTE de
    /// `SM4EKEY`/`RAX1` (`@rd_rn_rm_e0`, `bits[20:16]`), apesar de ambas as formas usarem 2 registradores-fonte no
    /// encoding; não generalizar um único `shift` para os dois formatos.
    private static final int RDN_RM_SHIFT = 5;
    private static final int RRR_RN_SHIFT = 5;
    private static final int RRR_RM_SHIFT = 16;

    private static final long AESMC_MASK = 0xFFFFFFE0L;
    private static final long AESMC_VALUE = 0x4520E000L;
    private static final long AESIMC_MASK = 0xFFFFFFE0L;
    private static final long AESIMC_VALUE = 0x4520E400L;
    private static final long AESE_MASK = 0xFFFFFC00L;
    private static final long AESE_VALUE = 0x4522E000L;
    private static final long AESD_MASK = 0xFFFFFC00L;
    private static final long AESD_VALUE = 0x4522E400L;
    private static final long SM4E_MASK = 0xFFFFFC00L;
    private static final long SM4E_VALUE = 0x4523E000L;
    private static final long SM4EKEY_MASK = 0xFFE0FC00L;
    private static final long SM4EKEY_VALUE = 0x4520F000L;
    private static final long RAX1_MASK = 0xFFE0FC00L;
    private static final long RAX1_VALUE = 0x4520F400L;

    private final Aarch64Architecture architecture;

    Aarch64SveCryptoDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// `AESE`/`AESD`/`AESMC`/`AESIMC`/`SM4E`/`SM4EKEY`/`RAX1` — prefixo `0x45`, `bit 21 = 1` (mesmo espaço de
    /// {@link Aarch64Sve2IntegerDecoder#decodePrefix45}/{@link Aarch64Sve2MiscDecoder#decodePrefix45}; chamar
    /// depois dos dois).
    Ir64Op decodePrefix45(int word, long address) {
        int rd = (word >>> RD_SHIFT) & REGISTER5_MASK;
        if ((word & AESMC_MASK) == AESMC_VALUE) {
            return aes(Ir64CryptoAesOp.AESMC, rd, rd, address);
        }
        if ((word & AESIMC_MASK) == AESIMC_VALUE) {
            return aes(Ir64CryptoAesOp.AESIMC, rd, rd, address);
        }
        if ((word & AESE_MASK) == AESE_VALUE) {
            return aes(Ir64CryptoAesOp.AESE, rd, rdnRm(word), address);
        }
        if ((word & AESD_MASK) == AESD_VALUE) {
            return aes(Ir64CryptoAesOp.AESD, rd, rdnRm(word), address);
        }
        if ((word & SM4E_MASK) == SM4E_VALUE) {
            return architecture.has(Aarch64Feature.SVE_SM4)
                    ? new SveIntegerOp64.CryptoSm4Encrypt(rd, rdnRm(word), address)
                    : null;
        }
        if ((word & SM4EKEY_MASK) == SM4EKEY_VALUE) {
            return architecture.has(Aarch64Feature.SVE_SM4)
                    ? new SveIntegerOp64.CryptoSm4KeyUpdate(rd, rrrRn(word), rrrRm(word), address)
                    : null;
        }
        if ((word & RAX1_MASK) == RAX1_VALUE) {
            return architecture.has(Aarch64Feature.SVE_SHA3)
                    ? new SveIntegerOp64.CryptoRax1(rd, rrrRn(word), rrrRm(word), address)
                    : null;
        }
        return null;
    }

    /// Segundo operando de `AESE`/`AESD`/`SM4E` (`@rdn_rm_e0`), `bits[9:5]`.
    private int rdnRm(int word) {
        return (word >>> RDN_RM_SHIFT) & REGISTER5_MASK;
    }

    /// `Zn` de `SM4EKEY`/`RAX1` (`@rd_rn_rm_e0`), `bits[9:5]`.
    private int rrrRn(int word) {
        return (word >>> RRR_RN_SHIFT) & REGISTER5_MASK;
    }

    /// `Zm` de `SM4EKEY`/`RAX1` (`@rd_rn_rm_e0`), `bits[20:16]`.
    private int rrrRm(int word) {
        return (word >>> RRR_RM_SHIFT) & REGISTER5_MASK;
    }

    private Ir64Op aes(Ir64CryptoAesOp op, int rd, int rn, long address) {
        return architecture.has(Aarch64Feature.SVE_AES) ? new SveIntegerOp64.CryptoAes(op, rd, rn, address) : null;
    }
}
