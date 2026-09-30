package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE2 da B17.23: pairwise FP (`FADDP`/`FMAXNMP`/`FMINNMP`/`FMAXP`/`FMINP`) e matmul FP
/// (`BFMMLA`/`FMMLA_s`/`FMMLA_d`/`FMMLA_sb`/`FMMLA_hb`), ambos no prefixo `0x64`. Padrões medidos contra
/// `DO_ZPZZ_PAIR_FP` (pairwise) e os `TRANS_FEAT`/`&rda_rn_rm_ex` (matmul) do `sve.decode`/`translate-sve.c`
/// reais do QEMU.
final class Aarch64SveFpMatrixDecoder {
    private static final int REGISTER_MASK = 0b11111;

    // Pairwise (`@rdn_pg_rm`): bits[21:19] fixos em `010`, bits[15:13] fixos em `100`. `esz` = bits[23:22],
    // `xy` = bits[18:17] (kind), `z` = bit 16, `pg` = bits[12:10].
    private static final long PAIRWISE_MASK = 0xFF38E000L;
    private static final long PAIRWISE_VALUE = 0x64108000L;
    private static final int PAIRWISE_ESZ_SHIFT = 22;
    private static final int PAIRWISE_XY_SHIFT = 17;
    private static final int PAIRWISE_Z_BIT = 16;
    private static final int PAIRWISE_PG_SHIFT = 10;
    private static final int PAIRWISE_XY_ADD = 0b00;
    private static final int PAIRWISE_XY_NM = 0b10;
    private static final int PAIRWISE_XY_PLAIN = 0b11;
    /// `esz = 0` é BFloat16 (`FEAT_SVE_B16B16`) — pendência nomeada, nunca chega aqui.
    private static final int ESZ_BFLOAT16 = 0;

    // Matmul (`@rda_rn_rm_ex`): bit 21 fixo em `1`, bits[15:13] fixos em `111`. `kind2` = bits[23:22],
    // `rm` = bits[20:16], `kind1` = bits[12:10].
    private static final long MATMUL_MASK = 0xFF20E000L;
    private static final long MATMUL_VALUE = 0x6420E000L;
    private static final int MATMUL_KIND2_SHIFT = 22;
    private static final int MATMUL_RM_SHIFT = 16;
    private static final int MATMUL_KIND1_SHIFT = 10;
    private static final int MATMUL_KIND1_MASK = 0b111;
    private static final int MATMUL_KIND1_BF_S_D = 0b001;
    private static final int MATMUL_KIND1_FP8 = 0b000;

    private final Aarch64Architecture architecture;

    Aarch64SveFpMatrixDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra do prefixo `0x64`. `null` = não é deste grupo.
    Ir64Op decode(int word, long address) {
        Ir64Op pairwise = decodePairwise(word, address);
        return pairwise != null ? pairwise : decodeMatrixMultiply(word, address);
    }

    private Ir64Op decodePairwise(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        if ((word & PAIRWISE_MASK) != PAIRWISE_VALUE) {
            return null;
        }
        int esz = field(word, PAIRWISE_ESZ_SHIFT, 0b11);
        if (esz == ESZ_BFLOAT16) {
            return null;
        }
        int xy = field(word, PAIRWISE_XY_SHIFT, 0b11);
        boolean z = bit(word, PAIRWISE_Z_BIT);
        Ir64Op.SveFpPairwise.Op op = switch (xy) {
            case PAIRWISE_XY_ADD -> Ir64Op.SveFpPairwise.Op.FADDP;
            case PAIRWISE_XY_NM -> z ? Ir64Op.SveFpPairwise.Op.FMINNMP : Ir64Op.SveFpPairwise.Op.FMAXNMP;
            case PAIRWISE_XY_PLAIN -> z ? Ir64Op.SveFpPairwise.Op.FMINP : Ir64Op.SveFpPairwise.Op.FMAXP;
            default -> null; // `01`: não alocado
        };
        if (op == null) {
            return null;
        }
        int pg = field(word, PAIRWISE_PG_SHIFT, 0b111);
        int rm = field(word, 5, REGISTER_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpPairwise(op, esz, rd, rm, pg, address);
    }

    private Ir64Op decodeMatrixMultiply(int word, long address) {
        if ((word & MATMUL_MASK) != MATMUL_VALUE) {
            return null;
        }
        int kind2 = field(word, MATMUL_KIND2_SHIFT, 0b11);
        int kind1 = field(word, MATMUL_KIND1_SHIFT, MATMUL_KIND1_MASK);
        Ir64Op.SveFpMatrixMultiply.Op op;
        int esz;
        Aarch64Feature feature;
        if (kind1 == MATMUL_KIND1_BF_S_D) {
            op = switch (kind2) {
                case 0b01 -> Ir64Op.SveFpMatrixMultiply.Op.BFMMLA;
                case 0b10 -> Ir64Op.SveFpMatrixMultiply.Op.FMMLA_S;
                case 0b11 -> Ir64Op.SveFpMatrixMultiply.Op.FMMLA_D;
                default -> null; // `00`: não alocado
            };
            esz = switch (kind2) {
                case 0b01 -> 1; // BFMMLA
                case 0b11 -> 3; // FMMLA_d
                default -> 2; // FMMLA_s
            };
            feature = switch (kind2) {
                case 0b01 -> Aarch64Feature.BFLOAT16;
                case 0b11 -> Aarch64Feature.F64MM;
                default -> Aarch64Feature.F32MM;
            };
        } else if (kind1 == MATMUL_KIND1_FP8) {
            op = switch (kind2) {
                case 0b00 -> Ir64Op.SveFpMatrixMultiply.Op.FMMLA_SB;
                case 0b01 -> Ir64Op.SveFpMatrixMultiply.Op.FMMLA_HB;
                default -> null; // `10`/`11`: não alocado
            };
            esz = kind2 == 0b00 ? 2 : 1;
            feature = kind2 == 0b00 ? Aarch64Feature.FP8_MATRIX_MULTIPLY_FP32 : Aarch64Feature.FP8_MATRIX_MULTIPLY_FP16;
        } else {
            return null;
        }
        if (op == null || !architecture.has(feature)) {
            return null;
        }
        int rm = field(word, MATMUL_RM_SHIFT, REGISTER_MASK);
        int rn = field(word, 5, REGISTER_MASK);
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpMatrixMultiply(op, esz, rd, rn, rm, address);
    }

    private static boolean bit(int word, int shift) {
        return ((word >>> shift) & 1) != 0;
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
