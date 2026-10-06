package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.CryptoOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoAesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaTwoRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha512Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoShaThreeRegisterOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSm3TtOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.List;

/// E15.15a/E15.15d: toda a criptografia do A64 fora do SVE — antes
/// `Aarch64Decoder#decodeCryptoSha3` (B11.12, B19.10) e os ramos AES/SHA de `decodeAdvancedSimdInteger`
/// (B8.11, B8.11b). Três regiões:
///
/// - **"Cryptographic three-register SHA"** (`SHA1C`…`SHA256SU1`): `01011110 000`, opcode em
///   `bits[15:10]` com `bits[11:10]=00` e `bit15=0`. ISA base (a feature `FEAT_SHA1`/`FEAT_SHA256` não
///   existe em `Aarch64Feature`; o decoder sempre aceitou estas formas).
/// - **"Cryptographic AES"** (`01001110 00 10100`) e **"two-register SHA"** (`01011110 00 10100`): opcode
///   em `bits[16:12]`, `bits[11:10]=10` (E15.15d). Também ISA base no decoder (sem `FEAT_AES`).
/// - **Prefixo `11001110`** (`FEAT_SHA3`/`FEAT_SHA512`/`FEAT_SM3`/`FEAT_SM4`): `op0` em `bits[23:21]`
///   e, conforme o `op0`, `Ra`/`imm6`/`imm2`/opcode em `bits[15:10]`. Cada linha tem a SUA feature —
///   SHA-512 e SM3 dividem o `op0=011` com o `RAX1` do SHA3.
///
/// **Correção da E15.15a:** `EOR3`/`BCAX`/`SM3SS1` têm `Ra` de 5 bits (`bits[14:10]`, só `bit15=0`
/// fixo), como no `a64.decode` do QEMU e no `aarch64-none-elf-as` (`eor3 v0.16b, v1.16b, v2.16b,
/// v20.16b` = `0xce025020`). A versão em cascata lia 4 bits e recusava `Ra ≥ 16`.
final class CryptoRows {
    private static final int RM_SHIFT = 16;
    private static final int RA_SHIFT = 10;
    private static final int XAR_IMM6_SHIFT = 10;
    private static final int XAR_IMM6_MASK = 0b11_1111;
    private static final int SM3TT_IMM2_SHIFT = 12;
    private static final int SM3TT_IMM2_MASK = 0b11;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `RAX1` não tem imediato (rotação à esquerda fixa de 1, aplicada pelo executor).
    private static final int RAX1_UNUSED_ROTATE_AMOUNT = 0;

    private static final Aarch64Feature SHA3 = Aarch64Feature.SHA3;
    private static final Aarch64Feature SHA512 = Aarch64Feature.SHA512;
    private static final Aarch64Feature SM3 = Aarch64Feature.SM3;
    private static final Aarch64Feature SM4 = Aarch64Feature.SM4;

    /// Colunas: "three-register SHA" = `0 1 0 11110 00 0 Rm opcode(15:10) Rn Rd`; prefixo `11001110` =
    /// `11001110 op0(23:21) Rm bits[15:10] Rn Rd`.
    static final List<DecodeRow<Ir64Op>> ROWS = List.of(
            // three-register SHA
            row("0 1 0 11110 00 0 ..... 000000 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA1C)),
            row("0 1 0 11110 00 0 ..... 000100 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA1P)),
            row("0 1 0 11110 00 0 ..... 001000 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA1M)),
            row("0 1 0 11110 00 0 ..... 001100 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA1SU0)),
            row("0 1 0 11110 00 0 ..... 010000 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA256H)),
            row("0 1 0 11110 00 0 ..... 010100 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA256H2)),
            row("0 1 0 11110 00 0 ..... 011000 ..... .....", null, sha(Ir64CryptoShaThreeRegisterOp.SHA256SU1)),
            // AES (prefixo 01110) e two-register SHA (11110): 0 1 0 prefixo 00 10100 opcode(16:12) 10 Rn Rd
            row("0 1 0 01110 00 10100 00100 10 ..... .....", null, aes(Ir64CryptoAesOp.AESE)),
            row("0 1 0 01110 00 10100 00101 10 ..... .....", null, aes(Ir64CryptoAesOp.AESD)),
            row("0 1 0 01110 00 10100 00110 10 ..... .....", null, aes(Ir64CryptoAesOp.AESMC)),
            row("0 1 0 01110 00 10100 00111 10 ..... .....", null, aes(Ir64CryptoAesOp.AESIMC)),
            row("0 1 0 11110 00 10100 00000 10 ..... .....", null, shaTwoRegister(Ir64CryptoShaTwoRegisterOp.SHA1H)),
            row("0 1 0 11110 00 10100 00001 10 ..... .....", null, shaTwoRegister(Ir64CryptoShaTwoRegisterOp.SHA1SU1)),
            row("0 1 0 11110 00 10100 00010 10 ..... .....", null,
                    shaTwoRegister(Ir64CryptoShaTwoRegisterOp.SHA256SU0)),
            // four-register: Ra(14:10)
            row("11001110 000 ..... 0 ..... ..... .....", SHA3, fourRegister(Ir64CryptoSha3Op.EOR3)),
            row("11001110 001 ..... 0 ..... ..... .....", SHA3, fourRegister(Ir64CryptoSha3Op.BCAX)),
            row("11001110 010 ..... 0 ..... ..... .....", SM3, CryptoRows::sm3FourRegister),
            // three-register, imm2: imm2(13:12) opcode(11:10)
            row("11001110 010 ..... 10 ..00 ..... .....", SM3, sm3Tt(Ir64CryptoSm3TtOp.TT1A)),
            row("11001110 010 ..... 10 ..01 ..... .....", SM3, sm3Tt(Ir64CryptoSm3TtOp.TT1B)),
            row("11001110 010 ..... 10 ..10 ..... .....", SM3, sm3Tt(Ir64CryptoSm3TtOp.TT2A)),
            row("11001110 010 ..... 10 ..11 ..... .....", SM3, sm3Tt(Ir64CryptoSm3TtOp.TT2B)),
            // three-register SHA512 / SM3 / SM4 e RAX1
            row("11001110 011 ..... 100011 ..... .....", SHA3, CryptoRows::rax1),
            row("11001110 011 ..... 100000 ..... .....", SHA512, sha512(Ir64CryptoSha512Op.SHA512H)),
            row("11001110 011 ..... 100001 ..... .....", SHA512, sha512(Ir64CryptoSha512Op.SHA512H2)),
            row("11001110 011 ..... 100010 ..... .....", SHA512, sha512(Ir64CryptoSha512Op.SHA512SU1)),
            row("11001110 011 ..... 110000 ..... .....", SM3, sm3(Ir64CryptoSm3Op.PARTW1)),
            row("11001110 011 ..... 110001 ..... .....", SM3, sm3(Ir64CryptoSm3Op.PARTW2)),
            row("11001110 011 ..... 110010 ..... .....", SM4, CryptoRows::sm4KeyUpdate),
            // XAR: imm6(15:10)
            row("11001110 100 ..... ...... ..... .....", SHA3, CryptoRows::xar),
            // two-register SHA512 / SM4: Rm=00000
            row("11001110 110 00000 100000 ..... .....", SHA512, CryptoRows::sha512TwoRegister),
            row("11001110 110 00000 100001 ..... .....", SM4, CryptoRows::sm4Encrypt)
    );

    private CryptoRows() {
    }

    /// Construtor de op desta tabela: nenhuma forma guarda o endereço da instrução.
    @FunctionalInterface
    private interface AddressFree {
        Ir64Op decode(int word);
    }

    private static DecodeRow<Ir64Op> row(String pattern, Aarch64Feature requires, AddressFree build) {
        return DecodeRow.of(pattern, requires, (word, address) -> build.decode(word));
    }

    private static AddressFree sha(Ir64CryptoShaThreeRegisterOp op) {
        return word -> new CryptoOp64.ShaThreeRegister(op, rd(word), rn(word), rm(word));
    }

    private static AddressFree aes(Ir64CryptoAesOp op) {
        return word -> new CryptoOp64.Aes(op, rd(word), rn(word));
    }

    private static AddressFree shaTwoRegister(Ir64CryptoShaTwoRegisterOp op) {
        return word -> new CryptoOp64.ShaTwoRegister(op, rd(word), rn(word));
    }

    private static AddressFree fourRegister(Ir64CryptoSha3Op op) {
        return word -> new CryptoOp64.Sha3FourRegister(op, rd(word), rn(word), rm(word), ra(word));
    }

    private static Ir64Op sm3FourRegister(int word) {
        return new CryptoOp64.Sm3FourRegister(rd(word), rn(word), rm(word), ra(word));
    }

    private static AddressFree sm3Tt(Ir64CryptoSm3TtOp op) {
        return word -> new CryptoOp64.Sm3ThreeRegisterImm2(op, rd(word), rn(word), rm(word),
                (word >>> SM3TT_IMM2_SHIFT) & SM3TT_IMM2_MASK);
    }

    private static Ir64Op rax1(int word) {
        return new CryptoOp64.Sha3TwoSourceRotate(Ir64CryptoSha3Op.RAX1, rd(word), rn(word), rm(word),
                RAX1_UNUSED_ROTATE_AMOUNT);
    }

    private static AddressFree sha512(Ir64CryptoSha512Op op) {
        return word -> new CryptoOp64.Sha512ThreeRegister(op, rd(word), rn(word), rm(word));
    }

    private static AddressFree sm3(Ir64CryptoSm3Op op) {
        return word -> new CryptoOp64.Sm3ThreeRegister(op, rd(word), rn(word), rm(word));
    }

    private static Ir64Op sm4KeyUpdate(int word) {
        return new CryptoOp64.Sm4KeyUpdate(rd(word), rn(word), rm(word));
    }

    private static Ir64Op xar(int word) {
        return new CryptoOp64.Sha3TwoSourceRotate(Ir64CryptoSha3Op.XAR, rd(word), rn(word), rm(word),
                (word >>> XAR_IMM6_SHIFT) & XAR_IMM6_MASK);
    }

    private static Ir64Op sha512TwoRegister(int word) {
        return new CryptoOp64.Sha512TwoRegister(rd(word), rn(word));
    }

    private static Ir64Op sm4Encrypt(int word) {
        return new CryptoOp64.Sm4Encrypt(rd(word), rn(word));
    }

    private static int ra(int word) {
        return (word >>> RA_SHIFT) & REGISTER_MASK;
    }

    private static int rm(int word) {
        return (word >>> RM_SHIFT) & REGISTER_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }
}
