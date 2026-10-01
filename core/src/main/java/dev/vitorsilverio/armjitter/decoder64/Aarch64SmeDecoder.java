package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// `ZERO`/`ZERO_zt0`/`MOVA`/`MOVAZ` (B18.3) — as 47 linhas de `### SME Misc` + `### SME Move
/// into/from Array` + `### SME Move and Zero` de `target/isa-decode/sme.decode` (linhas 22-138).
/// Vivem no prefixo `0xC0` (`bits[31:24] = 11000000`), dentro do mesmo espaço `bits[28:26] = 000`
/// que {@link Aarch64SveCounterDecoder#decodeMultiVector} (os `LD1`/`ST1` multi-vetor SVE2.1,
/// prefixos `0xA0`/`0xA1`) — discriminados por máscara/valor de 32 bits, nunca pelos 3 bits do
/// `topLevelClass` sozinhos.
///
/// **As 45 linhas de `MOVA`/`MOVAZ` viram uma linha da tabela {@link #MOVA_ROWS} cada, TRANSCRITA
/// mecanicamente** por um script à parte (nunca à mão — Armadilha 3 da task: as larguras de
/// `za`/`off` por `esz` e por `count` não seguem uma fórmula uniforme, e a posição do grupo
/// `za`/`off` troca de lado — baixo em `MOVA_*z*`/`MOVA_a*`, alto em `MOVA_z*`/`MOVA_*a*` — conforme
/// `zr` ocupa o outro lado). `rs`/`rv` (bits `14:13`, o `..` do `.decode`) não têm nome ali; são
/// extraídos à parte e somados a `+12`/`+8` (`%mova_rs`/`%mova_rv`), conferido contra
/// `translate-sme.c` (`get_tile_rowcol`/`get_zarray`).
final class Aarch64SmeDecoder {
    // ── `%mova_rs`/`%mova_rv` — bits[14:13], bases DIFERENTES (Armadilha 2 da task) ──────────────
    private static final int RS_RV_FIELD_SHIFT = 13;
    private static final int RS_RV_FIELD_MASK = 0b11;
    /// `%mova_rs = bits[14:13] + 12` (`W12`-`W15`): formas predicada e multi-vetor de tile.
    private static final int MOVA_RS_BASE = 12;
    /// `%mova_rv = bits[14:13] + 8` (`W8`-`W11`): formas array-vetor.
    private static final int MOVA_RV_BASE = 8;

    // ── `### SME Misc` ───────────────────────────────────────────────────────────────────────────
    private static final int ZERO_MASK = 0xFFFFFF00;
    private static final int ZERO_VALUE = 0xC0080000;
    private static final int ZERO_IMM_MASK = 0xFF;
    private static final int ZERO_ZT0_MASK = 0xFFFFFFFF;
    private static final int ZERO_ZT0_VALUE = 0xC0480001;

    /// Uma linha de `MOVA`/`MOVAZ`: máscara/valor de 32 bits + onde (`shift`/largura) cada campo
    /// mora NESTA linha. `-1` em `pgShift` = sem predicado; `-1` em `zaShift` = `za = 0` fixo (só 1
    /// tile neste `esz`); `-1` em `offShift` = `off = 0` fixo (só 1 slice possível, `esz = Q`).
    private record Row(int mask, int value, boolean toVector, boolean zero, boolean array, int count, int esz,
                       int pgShift, int zrShift, int zrWidth, int zaShift, int zaWidth, int offShift, int offWidth) {
        boolean matches(int word) {
            return (word & mask) == value;
        }

        int zr(int word) {
            return (word >>> zrShift) & ((1 << zrWidth) - 1);
        }

        int tile(int word) {
            return zaShift < 0 ? 0 : (word >>> zaShift) & ((1 << zaWidth) - 1);
        }

        int off(int word) {
            return offShift < 0 ? 0 : (word >>> offShift) & ((1 << offWidth) - 1);
        }

        int pg(int word) {
            return pgShift < 0 ? -1 : (word >>> pgShift) & 0b111;
        }

        boolean requiresSme2p1() {
            return zero;
        }

        /// SME2 puro quando não é a forma predicada de 1 vetor (`FEAT_SME`) nem já exige SME2.1.
        boolean requiresSme2() {
            return count > 1; // só consultado quando !zero (MOVAZ já exige SME2.1 antes)
        }
    }

    private static final int V_BIT_SHIFT = 15;

    // ── `### SME Memory` (B18.4) — prefixo `0xE0`/`0xE1` ───────────────────────────────────────────
    /// `LDST1` com `esz = 0`..`3`: `bits[31:24] = 11100000`, `bit 4 = 0`; `bits[23:22] = esz`.
    private static final int LDST1_MASK = 0xFF000010;
    private static final int LDST1_VALUE = 0xE0000000;
    /// `LDST1` com `esz = 4` (`LD1Q`/`ST1Q`): `bits[31:22] = 1110000111`, `bit 4 = 0` — `bit 24 = 1`, não `0`.
    private static final int LDST1_Q_MASK = 0xFFC00010;
    private static final int LDST1_Q_VALUE = 0xE1C00000;
    private static final int LDST1_ESZ_SHIFT = 22;
    private static final int LDST1_ESZ_MASK = 0b11;
    private static final int ESZ_QUAD = 4;
    /// `za:esz` + `off:(4 - esz)` ocupam sempre os 4 bits baixos.
    private static final int LDST1_SPAN_BITS = 4;
    private static final int STORE_BIT_SHIFT = 21;
    private static final int RM_SHIFT = 16;
    private static final int REGISTER_MASK = 0b11111;
    private static final int PG_SHIFT = 10;
    private static final int PG_MASK = 0b111;
    private static final int RN_SHIFT = 5;
    /// `LDR`/`STR` de vetor de `ZA`: `bits[31:22] = 1110000100`, `bits[20:15] = 0`, `bits[12:10] = 0`,
    /// `bit 4 = 0`; `bit 21` = `st`.
    private static final int LDR_ZA_MASK = 0xFFDF9C10;
    private static final int LDR_ZA_VALUE = 0xE1000000;
    private static final int LDR_ZA_IMM_MASK = 0b1111;
    /// `LDR`/`STR` de `ZT0`: `bits[20:15] = 111111`, `bits[14:10] = 0`, `bits[4:0] = 0`.
    private static final int LDR_ZT0_MASK = 0xFFDFFC1F;
    private static final int LDR_ZT0_VALUE = 0xE11F8000;

    // ── `### SME Add Vector to Array` + `### SME Outer Product` (B18.5) ──────────────────────────────
    private static final int PM_SHIFT = 13;
    private static final int SUB_BIT_SHIFT = 4;

    /// Uma linha de `ADDHA`/`ADDVA`/produto externo: máscara/valor de 32 bits (gerados mecanicamente dos padrões do
    /// `.decode`, nunca à mão — Armadilha 3: `SMOPA`/`SUMOPA`/`USMOPA`/`UMOPA` diferem em dois bits espalhados) +
    /// a feature EXTRA além de `FEAT_SME` (`null` = só `FEAT_SME`). `hasSubtract` é falso nas linhas sem o bit `sub`
    /// (`ADDHA`/`ADDVA` e as duas `fp8`, que o `.decode` fixa em `sub = 0`).
    private record OuterProductRow(int mask, int value, Ir64Op.SmeOuterProduct.Op op, Aarch64Feature extra,
                                   boolean hasSubtract, boolean hasSecondVector) {
        boolean matches(int word) {
            return (word & mask) == value;
        }
    }

    // Gerada por script a partir de `target/isa-decode/sme.decode` linhas 174-217 — NÃO editar à mão.
    private static final OuterProductRow[] OUTER_PRODUCT_ROWS = {
            outer(0xFFFF001C, 0xC0900000, Ir64Op.SmeOuterProduct.Op.ADDHA_S, null, false, false),
            outer(0xFFFF001C, 0xC0910000, Ir64Op.SmeOuterProduct.Op.ADDVA_S, null, false, false),
            outer(0xFFFF0018, 0xC0D00000, Ir64Op.SmeOuterProduct.Op.ADDHA_D, Aarch64Feature.SME_I16I64, false, false),
            outer(0xFFFF0018, 0xC0D10000, Ir64Op.SmeOuterProduct.Op.ADDVA_D, Aarch64Feature.SME_I16I64, false, false),
            outer(0xFFE0000E, 0x81800008, Ir64Op.SmeOuterProduct.Op.FMOPA_H, Aarch64Feature.SME_F16F16, true, true),
            outer(0xFFE0000C, 0x80800000, Ir64Op.SmeOuterProduct.Op.FMOPA_S, null, true, true),
            outer(0xFFE00008, 0x80C00000, Ir64Op.SmeOuterProduct.Op.FMOPA_D, Aarch64Feature.SME_F64F64, true, true),
            outer(0xFFE0000E, 0x81A00008, Ir64Op.SmeOuterProduct.Op.BFMOPA, Aarch64Feature.SME_B16B16, true, true),
            outer(0xFFE0000C, 0x81800000, Ir64Op.SmeOuterProduct.Op.BFMOPA_W, null, true, true),
            outer(0xFFE0000C, 0x81A00000, Ir64Op.SmeOuterProduct.Op.FMOPA_W_H, null, true, true),
            outer(0xFFE0001C, 0x80A00000, Ir64Op.SmeOuterProduct.Op.FMOPA_SB, Aarch64Feature.SME_F8F32, false, true),
            outer(0xFFE0001E, 0x80A00008, Ir64Op.SmeOuterProduct.Op.FMOPA_HB, Aarch64Feature.SME_F8F16, false, true),
            outer(0xFFE0000C, 0xA0800000, Ir64Op.SmeOuterProduct.Op.SMOPA_S, null, true, true),
            outer(0xFFE0000C, 0xA0A00000, Ir64Op.SmeOuterProduct.Op.SUMOPA_S, null, true, true),
            outer(0xFFE0000C, 0xA1800000, Ir64Op.SmeOuterProduct.Op.USMOPA_S, null, true, true),
            outer(0xFFE0000C, 0xA1A00000, Ir64Op.SmeOuterProduct.Op.UMOPA_S, null, true, true),
            outer(0xFFE00008, 0xA0C00000, Ir64Op.SmeOuterProduct.Op.SMOPA_D, Aarch64Feature.SME_I16I64, true, true),
            outer(0xFFE00008, 0xA0E00000, Ir64Op.SmeOuterProduct.Op.SUMOPA_D, Aarch64Feature.SME_I16I64, true, true),
            outer(0xFFE00008, 0xA1C00000, Ir64Op.SmeOuterProduct.Op.USMOPA_D, Aarch64Feature.SME_I16I64, true, true),
            outer(0xFFE00008, 0xA1E00000, Ir64Op.SmeOuterProduct.Op.UMOPA_D, Aarch64Feature.SME_I16I64, true, true),
            outer(0xFFE0000C, 0x80800008, Ir64Op.SmeOuterProduct.Op.BMOPA, Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2,
                    true, true),
            outer(0xFFE0000C, 0xA0800008, Ir64Op.SmeOuterProduct.Op.SMOPA2_S,
                    Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, true, true),
            outer(0xFFE0000C, 0xA1800008, Ir64Op.SmeOuterProduct.Op.UMOPA2_S,
                    Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, true, true),
    };

    private static OuterProductRow outer(int mask, int value, Ir64Op.SmeOuterProduct.Op op, Aarch64Feature extra,
            boolean hasSubtract, boolean hasSecondVector) {
        return new OuterProductRow(mask, value, op, extra, hasSubtract, hasSecondVector);
    }

    // ── `# SME MOP4 Quarter-tile outer products` + `# SME TMOP Sparse outer products` (B18.5b) ───────
    private static final int MOP4_N_SHIFT = 9;
    private static final int MOP4_M_SHIFT = 20;
    private static final int MOP4_ZN_SHIFT = 6;
    private static final int MOP4_ZN_MASK = 0b111;
    private static final int MOP4_ZM_SHIFT = 17;
    private static final int MOP4_ZM_MASK = 0b111;
    /// `%mop4_zm = times_2_plus_16` (`Z16`-`Z30`).
    private static final int MOP4_ZM_BASE = 16;
    private static final int TMOP_ZN_SHIFT = 6;
    private static final int TMOP_ZN_MASK = 0b1111;
    private static final int TMOP_ZK_SHIFT = 10;
    private static final int TMOP_ZK_MASK = 0b111;
    /// `expand_tmop_zk`: o pseudocódigo `1:K:1:zk` — `0b10100 | ((x & 4) << 1) | (x & 3)` (`Z20`-`Z23`/`Z28`-`Z31`).
    private static final int TMOP_ZK_BASE = 0b10100;
    private static final int TMOP_ZK_HIGH_BIT = 0b100;
    private static final int TMOP_ZK_LOW_MASK = 0b11;
    private static final int TMOP_IDX_SHIFT = 4;
    private static final int TMOP_IDX_MASK = 0b11;
    private static final int PAIR_FACTOR = 2;

    /// Linha de `MOP4`/`TMOP`: máscara/valor de 32 bits + feature de formato EXTRA (a feature `MOP4`/`TMOP` em si é
    /// sempre exigida — `aa64_sme_mop4_*`/`aa64_sme_tmop_*` do QEMU são a conjunção das duas).
    private record Mop4Row(int mask, int value, Ir64Op.SmeMop4.Op op, Aarch64Feature extra) {
    }

    private record TmopRow(int mask, int value, Ir64Op.SmeTmop.Op op, Aarch64Feature extra) {
    }

    // Gerada por script a partir de `target/isa-decode/sme.decode` linhas 1090-1134 — NÃO editar à mão.
    private static final Mop4Row[] MOP4_ROWS = {
            mop4Row(0xFFE1FC2E, 0x81200008, Ir64Op.SmeMop4.Op.BFMOP4_HH, Aarch64Feature.SME_B16B16),
            mop4Row(0xFFE1FC2E, 0x81000008, Ir64Op.SmeMop4.Op.FMOP4_HH, Aarch64Feature.SME_F16F16),
            mop4Row(0xFFE1FC2C, 0x80000000, Ir64Op.SmeMop4.Op.FMOP4_SS, null),
            mop4Row(0xFFE1FC28, 0x80C00008, Ir64Op.SmeMop4.Op.FMOP4_DD, Aarch64Feature.SME_F64F64),
            mop4Row(0xFFE1FC2C, 0x81000000, Ir64Op.SmeMop4.Op.BFMOP4_SH, null),
            mop4Row(0xFFE1FC2C, 0x81200000, Ir64Op.SmeMop4.Op.FMOP4_SH, null),
            mop4Row(0xFFE1FC3C, 0x80200000, Ir64Op.SmeMop4.Op.FMOP4A_SB, Aarch64Feature.SME_F8F32),
            mop4Row(0xFFE1FC3E, 0x80200008, Ir64Op.SmeMop4.Op.FMOP4A_HB, Aarch64Feature.SME_F8F16),
            mop4Row(0xFFE1FC2C, 0x80008008, Ir64Op.SmeMop4.Op.SMOP4_SH, null),
            mop4Row(0xFFE1FC2C, 0x81008008, Ir64Op.SmeMop4.Op.UMOP4_SH, null),
            mop4Row(0xFFE1FC2C, 0x80008000, Ir64Op.SmeMop4.Op.SMOP4_SB, null),
            mop4Row(0xFFE1FC28, 0xA0C00008, Ir64Op.SmeMop4.Op.SMOP4_DH, Aarch64Feature.SME_I16I64),
            mop4Row(0xFFE1FC2C, 0x80208000, Ir64Op.SmeMop4.Op.SUMOP4_SB, null),
            mop4Row(0xFFE1FC28, 0xA0E00008, Ir64Op.SmeMop4.Op.SUMOP4_DH, Aarch64Feature.SME_I16I64),
            mop4Row(0xFFE1FC2C, 0x81208000, Ir64Op.SmeMop4.Op.UMOP4_SB, null),
            mop4Row(0xFFE1FC28, 0xA1E00008, Ir64Op.SmeMop4.Op.UMOP4_DH, Aarch64Feature.SME_I16I64),
            mop4Row(0xFFE1FC2C, 0x81008000, Ir64Op.SmeMop4.Op.USMOP4_SB, null),
            mop4Row(0xFFE1FC28, 0xA1C00008, Ir64Op.SmeMop4.Op.USMOP4_DH, Aarch64Feature.SME_I16I64),
    };

    // Gerada por script a partir de `target/isa-decode/sme.decode` linhas 1135-1161 — NÃO editar à mão.
    private static final TmopRow[] TMOP_ROWS = {
            tmopRow(0xFFE0E00E, 0x81600008, Ir64Op.SmeTmop.Op.BFTMOPA_HH, Aarch64Feature.SME_B16B16),
            tmopRow(0xFFE0E00E, 0x81400008, Ir64Op.SmeTmop.Op.FTMOPA_HH, Aarch64Feature.SME_F16F16),
            tmopRow(0xFFE0E00C, 0x80400000, Ir64Op.SmeTmop.Op.FTMOPA_SS, null),
            tmopRow(0xFFE0E00C, 0x81400000, Ir64Op.SmeTmop.Op.BFTMOPA_SH, null),
            tmopRow(0xFFE0E00C, 0x81600000, Ir64Op.SmeTmop.Op.FTMOPA_SH, null),
            tmopRow(0xFFE0E00E, 0x80600008, Ir64Op.SmeTmop.Op.FTMOPA_HB, Aarch64Feature.SME_F8F16),
            tmopRow(0xFFE0E00C, 0x80600000, Ir64Op.SmeTmop.Op.FTMOPA_SB, Aarch64Feature.SME_F8F32),
            tmopRow(0xFFE0E00C, 0x80408008, Ir64Op.SmeTmop.Op.STMOPA_SH, null),
            tmopRow(0xFFE0E00C, 0x81408008, Ir64Op.SmeTmop.Op.UTMOPA_SH, null),
            tmopRow(0xFFE0E00C, 0x80408000, Ir64Op.SmeTmop.Op.STMOPA_SB, null),
            tmopRow(0xFFE0E00C, 0x80608000, Ir64Op.SmeTmop.Op.SUTMOPA_SB, null),
            tmopRow(0xFFE0E00C, 0x81408000, Ir64Op.SmeTmop.Op.USTMOPA_SB, null),
            tmopRow(0xFFE0E00C, 0x81608000, Ir64Op.SmeTmop.Op.UTMOPA_SB, null),
    };

    private static Mop4Row mop4Row(int mask, int value, Ir64Op.SmeMop4.Op op, Aarch64Feature extra) {
        return new Mop4Row(mask, value, op, extra);
    }

    private static TmopRow tmopRow(int mask, int value, Ir64Op.SmeTmop.Op op, Aarch64Feature extra) {
        return new TmopRow(mask, value, op, extra);
    }

    // ── `### SME Multiple Zero` + `### SME Lookup Table Read` + `### SME Move into/from ZT0` (B18.6) ─────────
    private static final int MOVT_OFF_SHIFT = 12;
    private static final int MOVT_OFF_MASK = 0b111;
    private static final int MOVT_VECTOR_OFF_MASK = 0b11;
    private static final int MOVT_RT_MASK = 0b11111;
    private static final int LUT_ESZ_SHIFT = 12;
    private static final int LUT_ESZ_MASK = 0b11;
    /// `idx` termina sempre no bit 14 e perde um bit por duplicação de `count`: `LUTI2` tem `4 - log2(count)` bits,
    /// `LUTI4` tem `3 - log2(count)`, a partir do bit `14 + log2(count)`.
    private static final int LUT_IDX_BASE_SHIFT = 14;
    private static final int LUTI2_IDX_BITS = 4;
    private static final int LUTI4_IDX_BITS = 3;
    private static final int LUT_ZN_SHIFT = 5;
    private static final int LUT_ZN_PAIR_SHIFT = 6;
    private static final int LUT_ZN_PAIR_MASK = 0b1111;
    private static final int LUT_ZD_X2_SHIFT = 1;
    private static final int LUT_ZD_X2_MASK = 0b1111;
    private static final int LUT_ZD_X4_SHIFT = 2;
    private static final int LUT_ZD_X4_MASK = 0b111;
    /// `do_lut_s8`/`do_lut_s4` do QEMU: nas formas strided `zd` precisa estar alinhado ao espaçamento.
    private static final int LUT_STRIDED_X2_ZD_ALIGN_MASK = 0b01000;
    private static final int LUT_STRIDED_X4_ZD_ALIGN_MASK = 0b01100;

    // ── `### SME2 Multi-vector Multiple and Single SVE Destructive` (B18.7) ──────────────────────────────────
    /// `bits[31:20] = 1100_0001_0010` (`esz` em `[23:22]` livre) e `bits[15:12] = 1010`; `bit 11` escolhe `x2`/`x4`
    /// (`@z2z_2x1` x `@z2z_4x1`) e os bits `[10:5]`/`[0]` escolhem a operação.
    private static final int MV_SINGLE_MASK = 0xFF30F000;
    private static final int MV_SINGLE_VALUE = 0xC120A000;
    private static final int MV_COUNT_BIT_SHIFT = 11;
    private static final int MV_ESZ_SHIFT = 22;
    private static final int MV_ESZ_MASK = 0b11;
    private static final int MV_ZM_SHIFT = 16;
    /// `zm:4` — o operando avulso só alcança `Z0`-`Z15` (Armadilha 2 da task).
    private static final int MV_ZM_MASK = 0b1111;
    private static final int MV_ZDN_X2_SHIFT = 1;
    private static final int MV_ZDN_X4_SHIFT = 2;
    /// No `x4` o `bit 1` é fixo em `0` (`@z2z_4x1`: `...0 .`); no `x2` ele faz parte de `zdn`.
    private static final int MV_X4_ZERO_BIT = 0b10;
    private static final int MV_OPERATION_SHIFT = 5;
    private static final int MV_OPERATION_MASK = 0b111111;
    private static final int MV_UNSIGNED_BIT = 1;
    /// Chave `bits[10:5]`: `bit 10` separa `SQDMULH` do resto; `[9:5]` é o `op5` do `.decode`.
    private static final int MV_KEY_SMAX = 0b000000;
    private static final int MV_KEY_SMIN = 0b000001;
    private static final int MV_KEY_FMAX = 0b001000;
    private static final int MV_KEY_FMAXNM = 0b001001;
    private static final int MV_KEY_FSCALE = 0b001100;
    private static final int MV_KEY_SRSHL = 0b010001;
    private static final int MV_KEY_ADD = 0b011000;
    private static final int MV_KEY_SQDMULH = 0b100000;
    private static final int MV_ESZ_BYTE = 0;

    // ── `### SME2 Multi-vector Multiple Vectors SVE Destructive` (B18.8) ─────────────────────────────────────
    /// `bits[31:24] = 1100_0001`, `bit 21 = 1` (`bit 20` e acima fazem parte de `zm`) e `bits[15:12] = 1011` — um bit
    /// de diferença do prefixo `1010` da forma `_n1` (Armadilha 3 da B18.8). `bit 11` escolhe `x2`/`x4`.
    private static final int MV_MULTIPLE_MASK = 0xFF20F000;
    private static final int MV_MULTIPLE_VALUE = 0xC120B000;
    /// `%zm_ax2 = bits[20:17] × 2` (`bit 16` fixo em `0`); `%zm_ax4 = bits[20:18] × 4` (`bits[17:16]` fixos em `0`).
    private static final int MV_ZM_GROUP_X2_SHIFT = 17;
    private static final int MV_ZM_GROUP_X4_SHIFT = 18;
    private static final int MV_ZM_GROUP_X2_ZERO_MASK = 0b01 << MV_ZM_SHIFT;
    private static final int MV_ZM_GROUP_X4_ZERO_MASK = 0b11 << MV_ZM_SHIFT;
    /// `FAMAX_nn`/`FAMIN_nn` (`bits[10:5] = 001010`) — só existem na forma `_nn`.
    private static final int MV_KEY_FAMAX = 0b001010;

    // ── `### SME2 Multi-vector Multiple and Single Array Vectors` (B18.9) ─────────────────────────────────────
    /// `bits[31:24] = 1100_0001` e `bit 15 = 0` (a forma de B18.7/B18.8 tem `bit 15 = 1`): prefixo comum às 107 linhas,
    /// só para não percorrer a tabela inteira em palavras de outras famílias.
    private static final int ARRAY_VECTOR_PREFIX_MASK = 0xFF008000;
    private static final int ARRAY_VECTOR_PREFIX_VALUE = 0xC1000000;
    /// `zn:5` em `bits[9:5]` (SEM alinhamento, ao contrário de `%zn_ax2`) e `zm:4` em `bits[19:16]` (`Z0`-`Z15`).
    private static final int ARRAY_VECTOR_ZN_SHIFT = 5;
    private static final int ARRAY_VECTOR_ZM_SHIFT = 16;
    private static final int ARRAY_VECTOR_ZM_MASK = 0b1111;
    /// Grupos alinhados da forma `_nn` (B18.10): `%zn_ax2` = `bits[9:6]×2`, `%zn_ax4` = `bits[9:7]×4`, `%zm_ax2` =
    /// `bits[20:17]×2`, `%zm_ax4` = `bits[20:18]×4` — quatro bases de bits distintas.
    private static final int GROUP_PAIR = 2;
    private static final int GROUP_ZN_SHIFT_X2 = 6;
    private static final int GROUP_ZN_SHIFT_X4 = 7;
    private static final int GROUP_ZM_SHIFT_X2 = 17;
    private static final int GROUP_ZM_SHIFT_X4 = 18;
    private static final int GROUP_FIELD_MASK_X2 = 0b1111;
    private static final int GROUP_FIELD_MASK_X4 = 0b111;

    /// Linha de `ZERO_za`: máscara/valor de 32 bits + `ngrp`/`nvec` + a largura e a escala do `off` DESTA linha.
    private record ZeroArrayRow(int mask, int value, int ngrp, int nvec, int offMask, int offScale) {
    }

    // Geradas por script a partir de `target/isa-decode/sme.decode` linhas 1019-1041 — NÃO editar à mão.
    private static final ZeroArrayRow[] ZERO_ARRAY_ROWS = {
            new ZeroArrayRow(0xFFFF9FF8, 0xC00C0000, 2, 1, 0b111, 1),
            new ZeroArrayRow(0xFFFF9FF8, 0xC00E0000, 4, 1, 0b111, 1),
            new ZeroArrayRow(0xFFFF9FF8, 0xC00C8000, 1, 2, 0b111, 2),
            new ZeroArrayRow(0xFFFF9FFC, 0xC00D0000, 2, 2, 0b11, 2),
            new ZeroArrayRow(0xFFFF9FFC, 0xC00D8000, 4, 2, 0b11, 2),
            new ZeroArrayRow(0xFFFF9FFC, 0xC00E8000, 1, 4, 0b11, 4),
            new ZeroArrayRow(0xFFFF9FFE, 0xC00F0000, 2, 4, 0b1, 4),
            new ZeroArrayRow(0xFFFF9FFE, 0xC00F8000, 4, 4, 0b1, 4),
    };

    private static final int MOVT_RZT_MASK = 0xFFFF8FE0;
    private static final int MOVT_RZT_VALUE = 0xC04C03E0;
    private static final int MOVT_ZTR_VALUE = 0xC04E03E0;
    private static final int MOVT_ZTZ_MASK = 0xFFFFCFE0;
    private static final int MOVT_ZTZ_VALUE = 0xC04F03E0;

    /// Linha de `LUTI2`/`LUTI4`: máscara/valor + variante. Gates: consecutivas = `FEAT_SME2`; strided =
    /// `FEAT_SME2p1`; `LUTI4_*_4b` ainda exige `FEAT_SME_LUTv2` (`aa64_sme_lutv2`/`aa64_sme2p1_lutv2` do QEMU).
    private record LutRow(int mask, int value, boolean fourBit, int esz, int count, boolean strided,
                          boolean needsLutv2) {
    }

    // Geradas por script a partir de `target/isa-decode/sme.decode` linhas 1042-1089 — NÃO editar à mão. NÃO é
    // produto cartesiano (Armadilha 3): não existe `LUTI2_s_4s` e `LUTI4_*_4b` tem `idx = 0` fixo.
    private static final LutRow[] LUT_ROWS = {
            lut(0xFFFC3C00, 0xC0CC0000, false, 0, 1, false, false), // LUTI2_c_1b
            lut(0xFFFC3C00, 0xC0CC1000, false, 1, 1, false, false), // LUTI2_c_1h
            lut(0xFFFC3C00, 0xC0CC2000, false, 2, 1, false, false), // LUTI2_c_1s
            lut(0xFFFC7C01, 0xC08C4000, false, 0, 2, false, false), // LUTI2_c_2b
            lut(0xFFFC7C01, 0xC08C5000, false, 1, 2, false, false), // LUTI2_c_2h
            lut(0xFFFC7C01, 0xC08C6000, false, 2, 2, false, false), // LUTI2_c_2s
            lut(0xFFFCFC03, 0xC08C8000, false, 0, 4, false, false), // LUTI2_c_4b
            lut(0xFFFCFC03, 0xC08C9000, false, 1, 4, false, false), // LUTI2_c_4h
            lut(0xFFFCFC03, 0xC08CA000, false, 2, 4, false, false), // LUTI2_c_4s
            lut(0xFFFC7C00, 0xC09C4000, false, 0, 2, true, false), // LUTI2_s_2b
            lut(0xFFFC7C00, 0xC09C5000, false, 1, 2, true, false), // LUTI2_s_2h
            lut(0xFFFCFC00, 0xC09C8000, false, 0, 4, true, false), // LUTI2_s_4b
            lut(0xFFFCFC00, 0xC09C9000, false, 1, 4, true, false), // LUTI2_s_4h
            lut(0xFFFE3C00, 0xC0CA0000, true, 0, 1, false, false), // LUTI4_c_1b
            lut(0xFFFE3C00, 0xC0CA1000, true, 1, 1, false, false), // LUTI4_c_1h
            lut(0xFFFE3C00, 0xC0CA2000, true, 2, 1, false, false), // LUTI4_c_1s
            lut(0xFFFE7C01, 0xC08A4000, true, 0, 2, false, false), // LUTI4_c_2b
            lut(0xFFFE7C01, 0xC08A5000, true, 1, 2, false, false), // LUTI4_c_2h
            lut(0xFFFE7C01, 0xC08A6000, true, 2, 2, false, false), // LUTI4_c_2s
            lut(0xFFFEFC03, 0xC08A9000, true, 1, 4, false, false), // LUTI4_c_4h
            lut(0xFFFEFC03, 0xC08AA000, true, 2, 4, false, false), // LUTI4_c_4s
            lut(0xFFFFFC23, 0xC08B0000, true, 0, 4, false, true), // LUTI4_c_4b (zn par, idx = 0)
            lut(0xFFFE7C00, 0xC09A4000, true, 0, 2, true, false), // LUTI4_s_2b
            lut(0xFFFE7C00, 0xC09A5000, true, 1, 2, true, false), // LUTI4_s_2h
            lut(0xFFFEFC00, 0xC09A9000, true, 1, 4, true, false), // LUTI4_s_4h
            lut(0xFFFFFC20, 0xC09B0000, true, 0, 4, true, true), // LUTI4_s_4b (zn par, idx = 0)
    };

    private static LutRow lut(int mask, int value, boolean fourBit, int esz, int count, boolean strided,
            boolean needsLutv2) {
        return new LutRow(mask, value, fourBit, esz, count, strided, needsLutv2);
    }

    // Gerada por script a partir de `target/isa-decode/sme.decode` linhas 27-138 (ver javadoc da
    // classe) — NÃO editar à mão sem regerar e reconferir.
    private static final Row[] MOVA_ROWS = {
            new Row(0xFFFF0010, 0xC0000000, false, false, false, 1, 0, 10, 5, 5, -1, 0, 0, 4), // MOVA_tz esz=0
            new Row(0xFFFF0010, 0xC0400000, false, false, false, 1, 1, 10, 5, 5, 3, 1, 0, 3), // MOVA_tz esz=1
            new Row(0xFFFF0010, 0xC0800000, false, false, false, 1, 2, 10, 5, 5, 2, 2, 0, 2), // MOVA_tz esz=2
            new Row(0xFFFF0010, 0xC0C00000, false, false, false, 1, 3, 10, 5, 5, 1, 3, 0, 1), // MOVA_tz esz=3
            new Row(0xFFFF0010, 0xC0C10000, false, false, false, 1, 4, 10, 5, 5, 0, 4, -1, 0), // MOVA_tz esz=4
            new Row(0xFFFF0200, 0xC0020000, true, false, false, 1, 0, 10, 0, 5, -1, 0, 5, 4), // MOVA_zt esz=0
            new Row(0xFFFF0200, 0xC0420000, true, false, false, 1, 1, 10, 0, 5, 8, 1, 5, 3), // MOVA_zt esz=1
            new Row(0xFFFF0200, 0xC0820000, true, false, false, 1, 2, 10, 0, 5, 7, 2, 5, 2), // MOVA_zt esz=2
            new Row(0xFFFF0200, 0xC0C20000, true, false, false, 1, 3, 10, 0, 5, 6, 3, 5, 1), // MOVA_zt esz=3
            new Row(0xFFFF0200, 0xC0C30000, true, false, false, 1, 4, 10, 0, 5, 5, 4, -1, 0), // MOVA_zt esz=4
            new Row(0xFFFF1C38, 0xC0040000, false, false, false, 2, 0, -1, 6, 4, -1, 0, 0, 3), // MOVA_tz2 esz=0
            new Row(0xFFFF1C38, 0xC0440000, false, false, false, 2, 1, -1, 6, 4, 2, 1, 0, 2), // MOVA_tz2 esz=1
            new Row(0xFFFF1C38, 0xC0840000, false, false, false, 2, 2, -1, 6, 4, 1, 2, 0, 1), // MOVA_tz2 esz=2
            new Row(0xFFFF1C38, 0xC0C40000, false, false, false, 2, 3, -1, 6, 4, 0, 3, -1, 0), // MOVA_tz2 esz=3
            new Row(0xFFFF1F01, 0xC0060000, true, false, false, 2, 0, -1, 1, 4, -1, 0, 5, 3), // MOVA_zt2 esz=0
            new Row(0xFFFF1F01, 0xC0460000, true, false, false, 2, 1, -1, 1, 4, 7, 1, 5, 2), // MOVA_zt2 esz=1
            new Row(0xFFFF1F01, 0xC0860000, true, false, false, 2, 2, -1, 1, 4, 6, 2, 5, 1), // MOVA_zt2 esz=2
            new Row(0xFFFF1F01, 0xC0C60000, true, false, false, 2, 3, -1, 1, 4, 5, 3, -1, 0), // MOVA_zt2 esz=3
            new Row(0xFFFF1C7C, 0xC0040400, false, false, false, 4, 0, -1, 7, 3, -1, 0, 0, 2), // MOVA_tz4 esz=0
            new Row(0xFFFF1C7C, 0xC0440400, false, false, false, 4, 1, -1, 7, 3, 1, 1, 0, 1), // MOVA_tz4 esz=1
            new Row(0xFFFF1C7C, 0xC0840400, false, false, false, 4, 2, -1, 7, 3, 0, 2, -1, 0), // MOVA_tz4 esz=2
            new Row(0xFFFF1C78, 0xC0C40400, false, false, false, 4, 3, -1, 7, 3, 0, 3, -1, 0), // MOVA_tz4 esz=3
            new Row(0xFFFF1F83, 0xC0060400, true, false, false, 4, 0, -1, 2, 3, -1, 0, 5, 2), // MOVA_zt4 esz=0
            new Row(0xFFFF1F83, 0xC0460400, true, false, false, 4, 1, -1, 2, 3, 6, 1, 5, 1), // MOVA_zt4 esz=1
            new Row(0xFFFF1F83, 0xC0860400, true, false, false, 4, 2, -1, 2, 3, 5, 2, -1, 0), // MOVA_zt4 esz=2
            new Row(0xFFFF1F03, 0xC0C60400, true, false, false, 4, 3, -1, 2, 3, 5, 3, -1, 0), // MOVA_zt4 esz=3
            new Row(0xFFFF9C38, 0xC0040800, false, false, true, 2, -1, -1, 6, 4, -1, 0, 0, 3), // MOVA_az2
            new Row(0xFFFF9C78, 0xC0040C00, false, false, true, 4, -1, -1, 7, 3, -1, 0, 0, 3), // MOVA_az4
            new Row(0xFFFF9F01, 0xC0060800, true, false, true, 2, -1, -1, 1, 4, -1, 0, 5, 3), // MOVA_za2
            new Row(0xFFFF9F03, 0xC0060C00, true, false, true, 4, -1, -1, 2, 3, -1, 0, 5, 3), // MOVA_za4
            new Row(0xFFFF9F01, 0xC0060A00, true, true, true, 2, -1, -1, 1, 4, -1, 0, 5, 3), // MOVAZ_za2
            new Row(0xFFFF9F03, 0xC0060E00, true, true, true, 4, -1, -1, 2, 3, -1, 0, 5, 3), // MOVAZ_za4
            new Row(0xFFFF1E00, 0xC0020200, true, true, false, 1, 0, -1, 0, 5, -1, 0, 5, 4), // MOVAZ_zt esz=0
            new Row(0xFFFF1E00, 0xC0420200, true, true, false, 1, 1, -1, 0, 5, 8, 1, 5, 3), // MOVAZ_zt esz=1
            new Row(0xFFFF1E00, 0xC0820200, true, true, false, 1, 2, -1, 0, 5, 7, 2, 5, 2), // MOVAZ_zt esz=2
            new Row(0xFFFF1E00, 0xC0C20200, true, true, false, 1, 3, -1, 0, 5, 6, 3, 5, 1), // MOVAZ_zt esz=3
            new Row(0xFFFF1E00, 0xC0C30200, true, true, false, 1, 4, -1, 0, 5, 5, 4, -1, 0), // MOVAZ_zt esz=4
            new Row(0xFFFF1F01, 0xC0060200, true, true, false, 2, 0, -1, 1, 4, -1, 0, 5, 3), // MOVAZ_zt2 esz=0
            new Row(0xFFFF1F01, 0xC0460200, true, true, false, 2, 1, -1, 1, 4, 7, 1, 5, 2), // MOVAZ_zt2 esz=1
            new Row(0xFFFF1F01, 0xC0860200, true, true, false, 2, 2, -1, 1, 4, 6, 2, 5, 1), // MOVAZ_zt2 esz=2
            new Row(0xFFFF1F01, 0xC0C60200, true, true, false, 2, 3, -1, 1, 4, 5, 3, -1, 0), // MOVAZ_zt2 esz=3
            new Row(0xFFFF1F83, 0xC0060600, true, true, false, 4, 0, -1, 2, 3, -1, 0, 5, 2), // MOVAZ_zt4 esz=0
            new Row(0xFFFF1F83, 0xC0460600, true, true, false, 4, 1, -1, 2, 3, 6, 1, 5, 1), // MOVAZ_zt4 esz=1
            new Row(0xFFFF1F83, 0xC0860600, true, true, false, 4, 2, -1, 2, 3, 5, 2, -1, 0), // MOVAZ_zt4 esz=2
            new Row(0xFFFF1F03, 0xC0C60600, true, true, false, 4, 3, -1, 2, 3, 5, 3, -1, 0), // MOVAZ_zt4 esz=3
    };

    private final Aarch64Architecture architecture;

    Aarch64SmeDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    private boolean hasSme() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION);
    }

    private boolean hasSme2() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    }

    private boolean hasSme2p1() {
        return architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2_1);
    }

    /// `null` = não é deste grupo, ou é uma forma cuja feature está ausente (G8 trata como recusa).
    Ir64Op decode(int word, long address) {
        if (!hasSme()) {
            return null;
        }
        if ((word & ZERO_MASK) == ZERO_VALUE) {
            return new Ir64Op.SmeZero(word & ZERO_IMM_MASK, address);
        }
        if ((word & ZERO_ZT0_MASK) == ZERO_ZT0_VALUE) {
            return hasSme2() ? new Ir64Op.SmeZeroZt0(address) : null;
        }
        Ir64Op memory = decodeMemory(word, address);
        if (memory != null) {
            return memory;
        }
        for (OuterProductRow row : OUTER_PRODUCT_ROWS) {
            if (row.matches(word)) {
                return decodeOuterProduct(row, word, address);
            }
        }
        for (Mop4Row row : MOP4_ROWS) {
            if ((word & row.mask()) == row.value()) {
                return decodeMop4(row, word, address);
            }
        }
        for (TmopRow row : TMOP_ROWS) {
            if ((word & row.mask()) == row.value()) {
                return decodeTmop(row, word, address);
            }
        }
        Ir64Op zt0Family = decodeZt0Family(word, address);
        if (zt0Family != null) {
            return zt0Family;
        }
        if ((word & MV_SINGLE_MASK) == MV_SINGLE_VALUE) {
            return decodeMultiVector(word, address, false);
        }
        if ((word & MV_MULTIPLE_MASK) == MV_MULTIPLE_VALUE) {
            return decodeMultiVector(word, address, true);
        }
        if ((word & ARRAY_VECTOR_PREFIX_MASK) == ARRAY_VECTOR_PREFIX_VALUE) {
            for (SmeArrayVectorRows.Row row : SmeArrayVectorRows.ROWS) {
                if (row.matches(word)) {
                    return decodeArrayVector(row, word, address);
                }
            }
        }
        if ((word & SmeArrayIndexedRows.PREFIX_MASK) == SmeArrayIndexedRows.PREFIX_VALUE) {
            for (SmeArrayIndexedRows.Row row : SmeArrayIndexedRows.ROWS) {
                if (row.matches(word)) {
                    return decodeArrayVectorIndexed(row, word, address);
                }
            }
        }
        for (Row row : MOVA_ROWS) {
            if (!row.matches(word)) {
                continue;
            }
            if (row.requiresSme2p1() ? !hasSme2p1() : row.requiresSme2() && !hasSme2()) {
                return null;
            }
            int registerBase = row.array() ? MOVA_RV_BASE : MOVA_RS_BASE;
            int registerIndex = registerBase + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK);
            boolean vertical = !row.array() && ((word >>> V_BIT_SHIFT) & 1) != 0;
            boolean predicated = row.pgShift() >= 0;
            return new Ir64Op.SmeMova(row.toVector(), row.zero(), predicated, row.pg(word), row.count(), row.esz(),
                    row.array() ? -1 : row.tile(word), vertical, row.zr(word), registerIndex, row.off(word), address);
        }
        return null;
    }

    /// `ZERO` multi-vetor, `MOVT` e `LUTI2`/`LUTI4` (B18.6). `null` = não é desta família OU a feature/alinhamento
    /// da linha falha (G8: cai em `UNIMPLEMENTED`, nunca é confundida com outra instrução).
    private Ir64Op decodeZt0Family(int word, long address) {
        for (ZeroArrayRow row : ZERO_ARRAY_ROWS) {
            if ((word & row.mask()) == row.value()) {
                if (!hasSme2p1()) {
                    return null;
                }
                int rv = MOVA_RV_BASE + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK);
                return new Ir64Op.SmeZeroArray(row.ngrp(), row.nvec(), rv, (word & row.offMask()) * row.offScale(),
                        address);
            }
        }
        if ((word & MOVT_RZT_MASK) == MOVT_RZT_VALUE || (word & MOVT_RZT_MASK) == MOVT_ZTR_VALUE) {
            if (!hasSme2()) {
                return null;
            }
            Ir64Op.SmeMovt.Form form = (word & MOVT_RZT_MASK) == MOVT_RZT_VALUE ? Ir64Op.SmeMovt.Form.ZT_TO_X
                    : Ir64Op.SmeMovt.Form.X_TO_ZT;
            return new Ir64Op.SmeMovt(form, word & MOVT_RT_MASK, (word >>> MOVT_OFF_SHIFT) & MOVT_OFF_MASK, address);
        }
        if ((word & MOVT_ZTZ_MASK) == MOVT_ZTZ_VALUE) {
            return architecture.has(Aarch64Feature.SME_LUTV2)
                    ? new Ir64Op.SmeMovt(Ir64Op.SmeMovt.Form.VECTOR_TO_ZT, word & MOVT_RT_MASK,
                            (word >>> MOVT_OFF_SHIFT) & MOVT_VECTOR_OFF_MASK, address)
                    : null;
        }
        for (LutRow row : LUT_ROWS) {
            if ((word & row.mask()) == row.value()) {
                return decodeLut(row, word, address);
            }
        }
        return null;
    }

    private Ir64Op decodeLut(LutRow row, int word, long address) {
        boolean supported = row.strided() ? hasSme2p1() : hasSme2();
        if (!supported || row.needsLutv2() && !architecture.has(Aarch64Feature.SME_LUTV2)) {
            return null;
        }
        int countLog2 = Integer.numberOfTrailingZeros(row.count());
        int zd = word & REGISTER_MASK;
        if (row.strided()) {
            int alignMask = row.count() == 2 ? LUT_STRIDED_X2_ZD_ALIGN_MASK : LUT_STRIDED_X4_ZD_ALIGN_MASK;
            if ((zd & alignMask) != 0) {
                return null;
            }
        } else if (row.count() == 2) {
            zd = ((word >>> LUT_ZD_X2_SHIFT) & LUT_ZD_X2_MASK) * 2;
        } else if (row.count() == 4) {
            zd = ((word >>> LUT_ZD_X4_SHIFT) & LUT_ZD_X4_MASK) * 4;
        }
        boolean pairSource = row.fourBit() && row.esz() == 0 && row.count() == 4;
        int zn = pairSource ? ((word >>> LUT_ZN_PAIR_SHIFT) & LUT_ZN_PAIR_MASK) * 2
                : (word >>> LUT_ZN_SHIFT) & REGISTER_MASK;
        int index = 0;
        if (!pairSource) {
            int idxBits = (row.fourBit() ? LUTI4_IDX_BITS : LUTI2_IDX_BITS) - countLog2;
            index = (word >>> (LUT_IDX_BASE_SHIFT + countLog2)) & ((1 << idxBits) - 1);
        }
        return new Ir64Op.SmeLut(row.fourBit(), (word >>> LUT_ESZ_SHIFT) & LUT_ESZ_MASK, row.count(),
                row.strided(), zd, zn, index, address);
    }

    /// Base de um grupo de `count` registradores consecutivos (`%zd_ax2`/`%zd_ax4`, `%zm_ax2`/`%zm_ax4`, … — as
    /// funções `times_2`/`times_4` do `.decode`): o campo NÃO tem os `log2(count)` bits baixos (é assim que o
    /// alinhamento do grupo é garantido por construção), então o registrador-base é `campo × count`, com o campo de
    /// `5 - log2(count)` bits a partir de `shift`. Ler o campo como um registrador cru endereça o `Z` errado em
    /// TODAS as instruções da família (Armadilha 1 da B18.7). Compartilhado por B18.7-B18.12.
    ///
    /// @param word  a palavra de instrução
    /// @param shift bit baixo do campo (`1`/`2` para `zdn`, `17`/`18` para `zm`, `6`/`7` para `zn`)
    /// @param count `2` ou `4`
    static int groupBase(int word, int shift, int count) {
        int fieldBits = Integer.SIZE - Integer.numberOfLeadingZeros(REGISTER_MASK) - Integer.numberOfTrailingZeros(count);
        return ((word >>> shift) & ((1 << fieldBits) - 1)) * count;
    }

    /// `SMAX`/`UMAX`/`SMIN`/`UMIN`/`FMAX`/`FMIN`/`FMAXNM`/`FMINNM`/`SRSHL`/`URSHL`/`ADD`/`SQDMULH`/`FSCALE` na forma
    /// `_n1` — `Zm` avulso (B18.7) — e, com {@code multipleVectors}, na forma `_nn` — `Zm` é outro grupo — mais
    /// `FAMAX`/`FAMIN` (B18.8, só `_nn`; `ADD` só existe em `_n1`). `null` = fora da família: `bit 1` do `x4` ligado,
    /// `zm` do grupo desalinhado, combinação de opção/`U` que o `.decode` não define, ponto flutuante com `esz = 0`
    /// (`BFMAX_*` e afins — outro gate) ou feature ausente (G8 cai em `UNIMPLEMENTED`). Gates (`translate-sme.c`):
    /// `FEAT_SME2`; `FSCALE` exige também `FEAT_FP8` (`aa64_sme2_f8cvt`) e `FAMAX`/`FAMIN` `FEAT_FAMINMAX`
    /// (`aa64_sme2_faminmax`).
    private Ir64Op decodeMultiVector(int word, long address, boolean multipleVectors) {
        if (!hasSme2()) {
            return null;
        }
        int count = ((word >>> MV_COUNT_BIT_SHIFT) & 1) == 0 ? 2 : 4;
        if (count == 4 && (word & MV_X4_ZERO_BIT) != 0) {
            return null;
        }
        if (multipleVectors && (word & (count == 2 ? MV_ZM_GROUP_X2_ZERO_MASK : MV_ZM_GROUP_X4_ZERO_MASK)) != 0) {
            return null;
        }
        boolean unsigned = (word & MV_UNSIGNED_BIT) != 0;
        Ir64Op.SmeMultiVectorSingle.Op op = switch ((word >>> MV_OPERATION_SHIFT) & MV_OPERATION_MASK) {
            case MV_KEY_SMAX -> unsigned ? Ir64Op.SmeMultiVectorSingle.Op.UMAX : Ir64Op.SmeMultiVectorSingle.Op.SMAX;
            case MV_KEY_SMIN -> unsigned ? Ir64Op.SmeMultiVectorSingle.Op.UMIN : Ir64Op.SmeMultiVectorSingle.Op.SMIN;
            case MV_KEY_FMAX -> unsigned ? Ir64Op.SmeMultiVectorSingle.Op.FMIN : Ir64Op.SmeMultiVectorSingle.Op.FMAX;
            case MV_KEY_FMAXNM ->
                    unsigned ? Ir64Op.SmeMultiVectorSingle.Op.FMINNM : Ir64Op.SmeMultiVectorSingle.Op.FMAXNM;
            case MV_KEY_SRSHL ->
                    unsigned ? Ir64Op.SmeMultiVectorSingle.Op.URSHL : Ir64Op.SmeMultiVectorSingle.Op.SRSHL;
            case MV_KEY_ADD -> unsigned || multipleVectors ? null : Ir64Op.SmeMultiVectorSingle.Op.ADD;
            case MV_KEY_SQDMULH -> unsigned ? null : Ir64Op.SmeMultiVectorSingle.Op.SQDMULH;
            case MV_KEY_FSCALE -> unsigned || !architecture.has(Aarch64Feature.FP8) ? null
                    : Ir64Op.SmeMultiVectorSingle.Op.FSCALE;
            case MV_KEY_FAMAX -> !multipleVectors || !architecture.has(Aarch64Feature.FP_ABSOLUTE_MAX_MIN) ? null
                    : unsigned ? Ir64Op.SmeMultiVectorSingle.Op.FAMIN : Ir64Op.SmeMultiVectorSingle.Op.FAMAX;
            default -> null;
        };
        int esz = (word >>> MV_ESZ_SHIFT) & MV_ESZ_MASK;
        if (op == null || op.isFloatingPoint() && esz == MV_ESZ_BYTE) {
            return null;
        }
        int zdn = groupBase(word, count == 2 ? MV_ZDN_X2_SHIFT : MV_ZDN_X4_SHIFT, count);
        int zm = multipleVectors
                ? groupBase(word, count == 2 ? MV_ZM_GROUP_X2_SHIFT : MV_ZM_GROUP_X4_SHIFT, count)
                : (word >>> MV_ZM_SHIFT) & MV_ZM_MASK;
        return new Ir64Op.SmeMultiVectorSingle(op, esz, count, zdn, zm, multipleVectors, address);
    }

    /// As 107 linhas de "multiple and single, array vectors" (B18.9). `null` = a feature da linha está ausente
    /// (G8 cai em `UNIMPLEMENTED`). Gates medidos no `translate-sme.c`, instrução a instrução (não por família):
    /// tudo `FEAT_SME2`; as formas `_d`/`4h` (acumulam em 64 bits) exigem `FEAT_SME_I16I64`, `FMLA_d`/`FMLS_d`
    /// `FEAT_SME_F64F64`, `FMLA_h`/`FMLS_h` `FEAT_SME_F16F16`, `BFMLA`/`BFMLS` `FEAT_SME_B16B16`, `FMLALL_b`/`FDOT_sb`
    /// `FEAT_SME_F8F32` e `FMLAL_hb`/`FDOT_hb` `FEAT_SME_F8F16`.
    private Ir64Op decodeArrayVector(SmeArrayVectorRows.Row row, int word, long address) {
        if (!arrayVectorFeaturesPresent(row.op())) {
            return null;
        }
        int registerIndex = MOVA_RV_BASE + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK);
        int off = row.offset(word);
        return switch (row.form()) {
            case SINGLE -> new Ir64Op.SmeArrayMultiVector(row.op(), row.count(), registerIndex, off,
                    (word >>> ARRAY_VECTOR_ZN_SHIFT) & REGISTER_MASK,
                    (word >>> ARRAY_VECTOR_ZM_SHIFT) & ARRAY_VECTOR_ZM_MASK, address);
            case MULTIPLE -> new Ir64Op.SmeArrayMultiVector(row.op(), row.count(), registerIndex, off,
                    alignedGroup(word, row.count(), GROUP_ZN_SHIFT_X2, GROUP_ZN_SHIFT_X4),
                    alignedGroup(word, row.count(), GROUP_ZM_SHIFT_X2, GROUP_ZM_SHIFT_X4), address, true);
            case ACCUMULATE -> new Ir64Op.SmeArrayMultiVector(row.op(), row.count(), registerIndex, off, 0,
                    alignedGroup(word, row.count(), GROUP_ZN_SHIFT_X2, GROUP_ZN_SHIFT_X4), address, true);
        };
    }

    /// `FEAT_SME2` mais a capacidade EXTRA de cada operação (B18.9/B18.10/B18.11). `SVDOT_4h`/`UVDOT_4h` exigem
    /// `FEAT_SME_I16I64` e `FVDOT_sh`/`BFVDOT` exigem `FEAT_SME2` pelo manual (`IsFeatureImplemented` do decode de cada
    /// uma) — o `translate-sme.c` do QEMU as gateia só em `aa64_sme2`/`aa64_sme`, frouxo demais.
    private boolean arrayVectorFeaturesPresent(Ir64Op.SmeArrayMultiVector.Op op) {
        if (!hasSme2()) {
            return false;
        }
        Aarch64Feature extra = switch (op) {
            case ADD_D, SUB_D, SDOT_4H, UDOT_4H, SVDOT_4H, UVDOT_4H, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D ->
                    Aarch64Feature.SME_I16I64;
            case FMLA_D, FMLS_D -> Aarch64Feature.SME_F64F64;
            case FMLA_H, FMLS_H -> Aarch64Feature.SME_F16F16;
            case BFMLA, BFMLS -> Aarch64Feature.SME_B16B16;
            case FMLALL_B, FDOT_SB, FVDOTB, FVDOTT -> Aarch64Feature.SME_F8F32;
            case FMLAL_HB, FDOT_HB, FVDOT_HB -> Aarch64Feature.SME_F8F16;
            case FADD_D, FSUB_D -> Aarch64Feature.SME_F64F64;
            case BFADD, BFSUB -> Aarch64Feature.SME_B16B16;
            default -> null;
        };
        if (extra != null && !architecture.has(extra)) {
            return false;
        }
        // `FADD_h`/`FSUB_h`: `aa64_sme_f16f16_or_f8f16` — QUALQUER uma das duas features basta.
        return (op != Ir64Op.SmeArrayMultiVector.Op.FADD_H && op != Ir64Op.SmeArrayMultiVector.Op.FSUB_H)
                || architecture.has(Aarch64Feature.SME_F16F16) || architecture.has(Aarch64Feature.SME_F8F16);
    }

    /// `_nx` (B18.11): `null` = não é desta família OU a feature da linha falha (G8).
    private Ir64Op decodeArrayVectorIndexed(SmeArrayIndexedRows.Row row, int word, long address) {
        if (!arrayVectorFeaturesPresent(row.op())) {
            return null;
        }
        return new Ir64Op.SmeArrayMultiVector(row.op(), row.count(), MOVA_RV_BASE
                + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK), row.offset(word), row.znBase(word),
                (word >>> ARRAY_VECTOR_ZM_SHIFT) & ARRAY_VECTOR_ZM_MASK, address, false, row.index(word));
    }

    /// Base do grupo alinhado de `count` registradores (`times_2`/`times_4` do `.decode`).
    private static int alignedGroup(int word, int count, int shiftX2, int shiftX4) {
        return count == GROUP_PAIR ? ((word >>> shiftX2) & GROUP_FIELD_MASK_X2) * count
                : ((word >>> shiftX4) & GROUP_FIELD_MASK_X4) * count;
    }

    /// `null` = a feature EXTRA da linha está ausente (G8 trata como recusa). O índice do tile tem `esz` bits
    /// (`zad:1`/`zad:2`/`zad:3` ⇒ 2/4/8 tiles) — o tamanho do elemento do ACUMULADOR, não o da origem
    /// (Armadilha 4: `@op_16`/`@op_32`/`@op_64` diferem só na largura de `zad`).
    private Ir64Op decodeOuterProduct(OuterProductRow row, int word, long address) {
        if (row.extra() != null && !architecture.has(row.extra())) {
            return null;
        }
        int esz = row.op().accumulatorEsz();
        int tile = word & ((1 << esz) - 1);
        int zm = row.hasSecondVector() ? (word >>> RM_SHIFT) & REGISTER_MASK : 0;
        boolean subtract = row.hasSubtract() && ((word >>> SUB_BIT_SHIFT) & 1) != 0;
        return new Ir64Op.SmeOuterProduct(row.op(), tile, (word >>> RN_SHIFT) & REGISTER_MASK, zm,
                (word >>> PG_SHIFT) & PG_MASK, (word >>> PM_SHIFT) & PG_MASK, subtract, address);
    }

    /// `null` = `FEAT_SME_MOP4` ou a feature de formato ausente (G8 trata como recusa). `n`/`m` escolhem a metade
    /// do par; `s` (bit 4) é subtrair — nas linhas `FMOP4A_*` o `.decode` fixa esse bit em `0`.
    private Ir64Op decodeMop4(Mop4Row row, int word, long address) {
        if (!architecture.has(Aarch64Feature.SME_MOP4) || row.extra() != null && !architecture.has(row.extra())) {
            return null;
        }
        int esz = row.op().accumulatorEsz();
        return new Ir64Op.SmeMop4(row.op(), word & ((1 << esz) - 1),
                ((word >>> MOP4_ZN_SHIFT) & MOP4_ZN_MASK) * PAIR_FACTOR,
                ((word >>> MOP4_ZM_SHIFT) & MOP4_ZM_MASK) * PAIR_FACTOR + MOP4_ZM_BASE,
                ((word >>> SUB_BIT_SHIFT) & 1) != 0, ((word >>> MOP4_N_SHIFT) & 1) != 0,
                ((word >>> MOP4_M_SHIFT) & 1) != 0, address);
    }

    /// `null` = `FEAT_SME_TMOP` ou a feature de formato ausente. `zk` NUNCA é o índice cru (`expand_tmop_zk`).
    private Ir64Op decodeTmop(TmopRow row, int word, long address) {
        if (!architecture.has(Aarch64Feature.SME_TMOP) || row.extra() != null && !architecture.has(row.extra())) {
            return null;
        }
        int esz = row.op().accumulatorEsz();
        int rawZk = (word >>> TMOP_ZK_SHIFT) & TMOP_ZK_MASK;
        int zk = TMOP_ZK_BASE | ((rawZk & TMOP_ZK_HIGH_BIT) << 1) | (rawZk & TMOP_ZK_LOW_MASK);
        return new Ir64Op.SmeTmop(row.op(), word & ((1 << esz) - 1),
                ((word >>> TMOP_ZN_SHIFT) & TMOP_ZN_MASK) * PAIR_FACTOR, (word >>> RM_SHIFT) & REGISTER_MASK, zk,
                (word >>> TMOP_IDX_SHIFT) & TMOP_IDX_MASK, address);
    }

    /// `LD1`/`ST1` de tile, `LDR`/`STR` de `ZA` e de `ZT0` (B18.4). `null` = não é desta família (ou
    /// `LDR`/`STR ZT0` sem `FEAT_SME2`).
    private Ir64Op decodeMemory(int word, long address) {
        boolean store = ((word >>> STORE_BIT_SHIFT) & 1) != 0;
        int rn = (word >>> RN_SHIFT) & REGISTER_MASK;
        int registerIndex = MOVA_RS_BASE + ((word >>> RS_RV_FIELD_SHIFT) & RS_RV_FIELD_MASK);
        if ((word & LDR_ZT0_MASK) == LDR_ZT0_VALUE) {
            return hasSme2() ? new Ir64Op.SmeZt0LoadStore(store, rn, address) : null;
        }
        if ((word & LDR_ZA_MASK) == LDR_ZA_VALUE) {
            return new Ir64Op.SmeArrayLoadStore(store, rn, registerIndex, word & LDR_ZA_IMM_MASK, address);
        }
        int esz;
        if ((word & LDST1_Q_MASK) == LDST1_Q_VALUE) {
            esz = ESZ_QUAD;
        } else if ((word & LDST1_MASK) == LDST1_VALUE) {
            esz = (word >>> LDST1_ESZ_SHIFT) & LDST1_ESZ_MASK;
        } else {
            return null;
        }
        int offsetWidth = LDST1_SPAN_BITS - esz;
        int tile = (word >>> offsetWidth) & ((1 << esz) - 1);
        int offset = word & ((1 << offsetWidth) - 1);
        return new Ir64Op.SmeTileLoadStore(store, esz, tile, ((word >>> V_BIT_SHIFT) & 1) != 0,
                (word >>> PG_SHIFT) & PG_MASK, rn, (word >>> RM_SHIFT) & REGISTER_MASK, registerIndex, offset,
                address);
    }
}
