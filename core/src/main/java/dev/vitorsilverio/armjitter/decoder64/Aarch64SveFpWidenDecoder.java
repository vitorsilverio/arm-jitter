package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Decoder SVE2/SVE2.1 da B17.23: multiply-add-long `binary16`→`binary32`/`bfloat16`→`binary32`
/// (`FMLALB`/`FMLALT`/`FMLSLB`/`FMLSLT`, `BFMLALB`/`BFMLALT`/`BFMLSLB`/`BFMLSLT`, vetorial `_zzzw` e indexado
/// `_zzxw`) e produto escalar de duas vias `binary16`→`binary32`/`bfloat16`→`binary32` (`FDOT_zzzz`/
/// `FDOT_zzxz`, `BFDOT_zzzz`/`BFDOT_zzxz`), todos no prefixo `0x64`. **Todo padrão medido byte a byte contra
/// `aarch64-none-elf-as` real** — o bit que separa este grupo do FP8 irmão ({@link Aarch64SveFp8MultiplyDecoder})
/// é sempre o bit 10 (`0` aqui, `1` lá) e o que separa `binary16` de `bfloat16` é sempre `bits[23:22]`
/// (`10`/`00` = `binary16`, `11`/`01` = `bfloat16`, vetorial/indexado respectivamente).
final class Aarch64SveFpWidenDecoder {
    private static final int REGISTER_MASK = 0b11111;
    private static final int REGISTER_LOW_MASK = 0b111;
    private static final int TOP_BIT = 10;
    private static final int SUBTRACT_BIT = 13;

    // Multiply-add-long vetorial (`_zzzw`): bit 21 fixo em 1, bits[15:14] fixos em `10`, bits[12:11] fixos em
    // `00`. `top` = bit 10, `subtract` = bit 13.
    private static final long MUL_ADD_VECTOR_MASK = 0xFFE0D800L;
    private static final long MUL_ADD_VECTOR_VALUE_REAL = 0x64A08000L;
    private static final long MUL_ADD_VECTOR_VALUE_BFLOAT16 = 0x64E08000L;
    private static final int MUL_ADD_RM_SHIFT = 16;

    // Multiply-add-long indexado (`_zzxw`): bit 21 fixo em 1, bit 15 fixo em 0, bit 14 fixo em 1, bit 12 fixo
    // em 0. Índice de 3 bits (`%index3_19_11`): alta em bits[20:19], baixa em bit 11.
    private static final long MUL_ADD_INDEXED_MASK = 0xFFE0D000L;
    private static final long MUL_ADD_INDEXED_VALUE_REAL = 0x64A04000L;
    private static final long MUL_ADD_INDEXED_VALUE_BFLOAT16 = 0x64E04000L;
    private static final int INDEX3_HIGH_SHIFT = 19;
    private static final int INDEX3_HIGH_MASK = 0b11;
    private static final int INDEX3_LOW_BIT = 11;

    // Dot-product vetorial (`FDOT_zzzz`/`BFDOT_zzzz`): bit 21 fixo em 1, bits[15:8] fixos em `1000 0000`
    // (bit 10 = 0, ao contrário do irmão FP8).
    private static final long DOT_VECTOR_MASK = 0xFFE0FF00L;
    private static final long DOT_VECTOR_VALUE_REAL = 0x64208000L;
    private static final long DOT_VECTOR_VALUE_BFLOAT16 = 0x64608000L;

    // Dot-product indexado (`FDOT_zzxz`/`BFDOT_zzxz`): índice de 2 bits DIRETO em bits[20:19] (sem metade
    // baixa), bits[15:8] fixos em `0100 0000`.
    private static final long DOT_INDEXED_MASK = 0xFFE0FF00L;
    private static final long DOT_INDEXED_VALUE_REAL = 0x64204000L;
    private static final long DOT_INDEXED_VALUE_BFLOAT16 = 0x64604000L;

    private final Aarch64Architecture architecture;

    Aarch64SveFpWidenDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra do prefixo `0x64`. `null` = não é deste grupo.
    Ir64Op decode(int word, long address) {
        Ir64Op mulAdd = decodeMultiplyAddLong(word, address);
        return mulAdd != null ? mulAdd : decodeDotProduct(word, address);
    }

    private Ir64Op decodeMultiplyAddLong(int word, long address) {
        Ir64Op indexed = decodeMultiplyAddIndexed(word, address);
        return indexed != null ? indexed : decodeMultiplyAddVector(word, address);
    }

    private Ir64Op decodeMultiplyAddVector(int word, long address) {
        boolean bfloat16;
        if ((word & MUL_ADD_VECTOR_MASK) == MUL_ADD_VECTOR_VALUE_REAL) {
            bfloat16 = false;
        } else if ((word & MUL_ADD_VECTOR_MASK) == MUL_ADD_VECTOR_VALUE_BFLOAT16) {
            bfloat16 = true;
        } else {
            return null;
        }
        boolean subtract = bit(word, SUBTRACT_BIT);
        // Achado (medido em `translate-sve.c`): `BFMLALB`/`BFMLALT` exigem só `FEAT_SVE_BF16`; a forma
        // subtrativa `BFMLSLB`/`BFMLSLT` exige `FEAT_SVE2p1`/`FEAT_SME2` (`aa64_sme_sve_bf16` vs
        // `aa64_sme2_or_sve2p1`) — features DIFERENTES na MESMA família.
        Aarch64Feature required = bfloat16
                ? (subtract ? Aarch64Feature.SVE2_1 : Aarch64Feature.BFLOAT16)
                : Aarch64Feature.SVE2;
        if (!architecture.has(required)) {
            return null;
        }
        boolean top = bit(word, TOP_BIT);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, MUL_ADD_RM_SHIFT, REGISTER_MASK);
        return bfloat16
                ? new SveFpOp64.FpMultiplyAddLongWidenBFloat16(
                        subtract ? SveFpOp64.FpMultiplyAddLongWidenBFloat16.Op.BFMLSL
                                : SveFpOp64.FpMultiplyAddLongWidenBFloat16.Op.BFMLAL,
                        top, rd, rn, rm, false, 0, address)
                : new SveFpOp64.FpMultiplyAddLongWiden(
                        subtract ? SveFpOp64.FpMultiplyAddLongWiden.Op.FMLSL
                                : SveFpOp64.FpMultiplyAddLongWiden.Op.FMLAL,
                        top, rd, rn, rm, false, 0, address);
    }

    private Ir64Op decodeMultiplyAddIndexed(int word, long address) {
        boolean bfloat16;
        if ((word & MUL_ADD_INDEXED_MASK) == MUL_ADD_INDEXED_VALUE_REAL) {
            bfloat16 = false;
        } else if ((word & MUL_ADD_INDEXED_MASK) == MUL_ADD_INDEXED_VALUE_BFLOAT16) {
            bfloat16 = true;
        } else {
            return null;
        }
        boolean subtract = bit(word, SUBTRACT_BIT);
        Aarch64Feature required = bfloat16
                ? (subtract ? Aarch64Feature.SVE2_1 : Aarch64Feature.BFLOAT16)
                : Aarch64Feature.SVE2;
        if (!architecture.has(required)) {
            return null;
        }
        boolean top = bit(word, TOP_BIT);
        int index = index3(word);
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, MUL_ADD_RM_SHIFT, REGISTER_LOW_MASK);
        return bfloat16
                ? new SveFpOp64.FpMultiplyAddLongWidenBFloat16(
                        subtract ? SveFpOp64.FpMultiplyAddLongWidenBFloat16.Op.BFMLSL
                                : SveFpOp64.FpMultiplyAddLongWidenBFloat16.Op.BFMLAL,
                        top, rd, rn, rm, true, index, address)
                : new SveFpOp64.FpMultiplyAddLongWiden(
                        subtract ? SveFpOp64.FpMultiplyAddLongWiden.Op.FMLSL
                                : SveFpOp64.FpMultiplyAddLongWiden.Op.FMLAL,
                        top, rd, rn, rm, true, index, address);
    }

    /// `%index3_19_11`: bits[20:19] (alta, 2 bits) concatenados com bit 11 (baixa, 1 bit).
    private static int index3(int word) {
        int high = field(word, INDEX3_HIGH_SHIFT, INDEX3_HIGH_MASK);
        int low = bit(word, INDEX3_LOW_BIT) ? 1 : 0;
        return (high << 1) | low;
    }

    private Ir64Op decodeDotProduct(int word, long address) {
        Ir64Op indexed = decodeDotIndexed(word, address);
        return indexed != null ? indexed : decodeDotVector(word, address);
    }

    private Ir64Op decodeDotVector(int word, long address) {
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, 16, REGISTER_MASK);
        if ((word & DOT_VECTOR_MASK) == DOT_VECTOR_VALUE_REAL && architecture.has(Aarch64Feature.SVE2_1)) {
            return new SveFpOp64.FpDotProductWiden(rd, rn, rm, false, 0, address);
        }
        if ((word & DOT_VECTOR_MASK) == DOT_VECTOR_VALUE_BFLOAT16 && architecture.has(Aarch64Feature.BFLOAT16)) {
            return new SveFpOp64.FpDotProductWidenBFloat16(rd, rn, rm, false, 0, address);
        }
        return null;
    }

    private Ir64Op decodeDotIndexed(int word, long address) {
        int rd = field(word, 0, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rm = field(word, 16, REGISTER_LOW_MASK);
        int index = field(word, 19, 0b11);
        if ((word & DOT_INDEXED_MASK) == DOT_INDEXED_VALUE_REAL && architecture.has(Aarch64Feature.SVE2_1)) {
            return new SveFpOp64.FpDotProductWiden(rd, rn, rm, true, index, address);
        }
        if ((word & DOT_INDEXED_MASK) == DOT_INDEXED_VALUE_BFLOAT16 && architecture.has(Aarch64Feature.BFLOAT16)) {
            return new SveFpOp64.FpDotProductWidenBFloat16(rd, rn, rm, true, index, address);
        }
        return null;
    }

    private static boolean bit(int word, int shift) {
        return ((word >>> shift) & 1) != 0;
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
