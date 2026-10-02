package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;

/// Decoder SVE do multiply por elemento indexado e dos dot-products vetoriais (B17.8) — o recorte `01000100`
/// de `#### SVE Multiply - Indexed` mais `DOT_zzzz`/`CDOT_zzzz` do `sve.decode`, transcrito linha a linha.
///
/// **Cada operação tem o formato de índice/`Zm` do `@rrx*`/`@rrxr*` que a linha nomeia**, e errar qual é silencioso
/// (só `esz`/`index` fora de `0` mostram):
///
/// | formato | tamanho | índice | `Zm` |
/// |---|---|---|---|
/// | `_3` (half) | `esz = 1` | `bit 22 : bits 20:19` (3 bits) | `Z0`-`Z7` |
/// | `_2` (word) | `esz = 2` | `bits 20:19` | `Z0`-`Z7` |
/// | `_1` (double) | `esz = 3` | `bit 20` | `Z0`-`Z15` |
/// | `_3a` (alarg. `.S`) | `esz = 2` | `bits 20:19 : bit 11` | `Z0`-`Z7` |
/// | `_2a` (alarg. `.D`) | `esz = 3` | `bit 20 : bit 11` | `Z0`-`Z15` |
///
/// Nas alargantes, `bit 11` é o bit BAIXO do índice e `bit 10` escolhe `B`/`T` (a linha `0010.0` do decodetree
/// tem um don't-care no meio do opcode — ele NÃO é um segundo opcode). Feature, linha a linha (`TRANS_FEAT` do
/// QEMU): `SDOT`/`UDOT` de 4 vias e `DOT_zzzz` são `FEAT_SVE`; `USDOT`/`SUDOT`, `FEAT_I8MM`; o dot de 2 vias,
/// `FEAT_SVE2p1` (que `SVE2_2` implica); todo o resto, `FEAT_SVE2`. Recusa (`null`) o que sobra (G8).
final class Aarch64SveMultiplyDecoder {
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int ESZ_FIELD_WORD = 0b10;
    private static final int RD_MASK = 0b11111;
    private static final int RN_SHIFT = 5;
    private static final int RN_MASK = 0b11111;
    private static final int RM_SHIFT = 16;
    private static final int RM5_MASK = 0b11111;
    private static final int RM4_MASK = 0b1111;
    private static final int RM3_MASK = 0b111;

    private static final int BIT_INDEXED = 21;
    private static final int BIT_TOP = 10;
    private static final int BIT_INDEX_LOW = 11;
    private static final int BIT_INDEX_HIGH = 20;
    private static final int BIT_INDEX_HALF = 22;
    private static final int BIT_DOT_HIGH_ESZ = 23;
    private static final int BIT_DOT_SZ = 22;
    private static final int BIT_DOT_UNSIGNED = 10;
    private static final int INDEX_PAIR_SHIFT = 19;
    private static final int INDEX_PAIR_MASK = 0b11;
    private static final int FAMILY_SHIFT = 12;
    private static final int FAMILY_MASK = 0b1111;
    private static final int LOW_SHIFT = 10;
    private static final int LOW_MASK = 0b11;
    private static final int DOT_VECTOR_FIXED_SHIFT = 11;
    private static final int DOT_VECTOR_FIXED_MASK = 0b11111;
    private static final int DOT_TWO_WAY_FAMILY = 0b1100;
    private static final int CDOT_VECTOR_FAMILY = 0b0001;
    private static final int DOT_TWO_WAY_SELECT_BIT = 11;
    private static final int ROT_SHIFT = 10;
    private static final int ROT_MASK = 0b11;
    private static final int WAYS_FOUR = 4;
    private static final int WAYS_TWO = 2;

    private static final int FAMILY_DOT_MLA = 0b0000;
    private static final int FAMILY_SQRDMLA_USDOT = 0b0001;
    private static final int FAMILY_SQDMLAL = 0b0010;
    private static final int FAMILY_SQDMLSL = 0b0011;
    private static final int FAMILY_CDOT = 0b0100;
    private static final int FAMILY_CMLA = 0b0110;
    private static final int FAMILY_SQRDCMLAH = 0b0111;
    private static final int FAMILY_SMLAL = 0b1000;
    private static final int FAMILY_UMLAL = 0b1001;
    private static final int FAMILY_SMLSL = 0b1010;
    private static final int FAMILY_UMLSL = 0b1011;
    private static final int FAMILY_SMULL = 0b1100;
    private static final int FAMILY_UMULL = 0b1101;
    private static final int FAMILY_SQDMULL = 0b1110;
    private static final int FAMILY_SQDMULH_MUL = 0b1111;
    private static final int LOW_SDOT_OR_SQRDMLAH_OR_SQDMULH = 0b00;
    private static final int LOW_UDOT_OR_SQRDMLSH_OR_SQRDMULH = 0b01;
    private static final int LOW_MLA_OR_USDOT_OR_MUL = 0b10;
    private static final int LOW_MLS_OR_SUDOT = 0b11;

    private final Aarch64Architecture architecture;

    Aarch64SveMultiplyDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra do prefixo `0x44`; `null` quando não é (ainda) deste grupo ou a feature falta.
    Ir64Op decode(int word, long address) {
        return bit(word, BIT_INDEXED) ? decodeIndexed(word, address) : decodeNonIndexed(word, address);
    }

    // ── bit 21 = 0: dots vetoriais e dot de 2 vias indexado ──────────────────────────────────────

    private Ir64Op decodeNonIndexed(int word, long address) {
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        int rm5 = (word >>> RM_SHIFT) & RM5_MASK;
        if (bit(word, BIT_DOT_HIGH_ESZ) && ((word >>> DOT_VECTOR_FIXED_SHIFT) & DOT_VECTOR_FIXED_MASK) == 0) {
            return new SveIntegerOp64.MultiplyIndexed(
                    bit(word, BIT_DOT_UNSIGNED) ? SveIntegerOp64.MultiplyIndexed.Op.UDOT : SveIntegerOp64.MultiplyIndexed.Op.SDOT,
                    bit(word, BIT_DOT_SZ) ? ESZ_DOUBLEWORD : ESZ_WORD, rd, rn, rm5, false, 0, 0, false, WAYS_FOUR,
                    address);
        }
        int family = (word >>> FAMILY_SHIFT) & FAMILY_MASK;
        if (family == CDOT_VECTOR_FAMILY) {
            return esz >= ESZ_WORD && architecture.has(Aarch64Feature.SVE2)
                    ? new SveIntegerOp64.MultiplyIndexed(SveIntegerOp64.MultiplyIndexed.Op.CDOT, esz, rd, rn, rm5, false, 0,
                            (word >>> ROT_SHIFT) & ROT_MASK, false, 0, address)
                    : null;
        }
        if (esz == ESZ_WORD && family == DOT_TWO_WAY_FAMILY && bit(word, DOT_TWO_WAY_SELECT_BIT)) {
            return architecture.has(Aarch64Feature.SVE2_1) || architecture.has(Aarch64Feature.SVE2_2)
                    ? new SveIntegerOp64.MultiplyIndexed(
                            bit(word, BIT_DOT_UNSIGNED) ? SveIntegerOp64.MultiplyIndexed.Op.UDOT
                                    : SveIntegerOp64.MultiplyIndexed.Op.SDOT,
                            ESZ_WORD, rd, rn, (word >>> RM_SHIFT) & RM3_MASK, true,
                            (word >>> INDEX_PAIR_SHIFT) & INDEX_PAIR_MASK, 0, false, WAYS_TWO, address)
                    : null;
        }
        return decodeMultiplyAddLong(word, address, esz, family, rd, rn, rm5);
    }

    // ── B17.22: as 21 linhas de "SVE Integer Multiply-Add (unpredicated)" NÃO-indexadas ────────────

    private static final int ESZ_BYTE = 0;
    private static final int LOW_SDOT_ZZZZ_2S = 0b10;
    private static final int LOW_UDOT_ZZZZ_2S = 0b11;
    private static final int FAMILY_INTERLEAVED_LONG = 0b0000;
    private static final int LOW_INTERLEAVED_LONG_ADD = 0b10;
    private static final int LOW_INTERLEAVED_LONG_SUB = 0b11;
    private static final int FAMILY_CMLA_VECTOR = 0b0010;
    private static final int FAMILY_SQRDCMLAH_VECTOR = 0b0011;
    private static final int FAMILY_SQDMLAL_STRAIGHT = 0b0110;
    private static final int FAMILY_LONG_SIGNED_UNSIGNED_ADD = 0b0100;
    private static final int FAMILY_LONG_SIGNED_UNSIGNED_SUB = 0b0101;
    private static final int FAMILY_HIGH_MULTIPLY = 0b0111;
    private static final int LOW_SQRDMLAH = 0b00;
    private static final int LOW_SQRDMLSH = 0b01;
    private static final int LOW_USDOT_ZZZZ_4S = 0b10;
    /// `bit 0` de `low` (`bits[11:10]`): `0` = metade BAIXA (`B`) de `Zn`/`Zm`; `1` = ALTA (`T`).
    private static final int LOW_TOP_MASK = 0b01;
    /// `bit 1` de `low`: nas famílias `0100`/`0101` escolhe sinal (`0` = signed, `1` = unsigned); na `0110`
    /// escolhe soma/subtração (`0` = `SQDMLAL`, `1` = `SQDMLSL`).
    private static final int LOW_SECOND_SELECTOR_MASK = 0b10;

    /// As 21 linhas de multiply-add long NÃO-indexado (par sem índice das 71 linhas indexadas da B17.8) — MESMA
    /// operação (`SveIntegerOp64.MultiplyIndexed`, `indexed = false`), reusando a semântica em `SveMultiplyIndexedOps`
    /// (Achado 4 da task: só a leitura de `Zm` muda). `rm` aqui é sempre `Z0`-`Z31` (`@rda_rn_rm`/`@rda_rn_rm_ex`,
    /// sem a restrição de 3/4 bits das formas indexadas). Feature por linha (`TRANS_FEAT` do QEMU): tudo
    /// `FEAT_SVE2`, exceto `USDOT_zzzz_4s` (`FEAT_I8MM`) e `SDOT_zzzz_2s`/`UDOT_zzzz_2s` (`FEAT_SVE2p1`).
    private Ir64Op decodeMultiplyAddLong(int word, long address, int esz, int family, int rd, int rn, int rm) {
        int low = (word >>> LOW_SHIFT) & LOW_MASK;
        boolean top = (low & LOW_TOP_MASK) != 0;
        return switch (family) {
            case FAMILY_INTERLEAVED_LONG -> switch (low) {
                case LOW_INTERLEAVED_LONG_ADD ->
                        wideningVector(esz, SveIntegerOp64.MultiplyIndexed.Op.SQDMLALBT, rd, rn, rm, false, 1, address);
                case LOW_INTERLEAVED_LONG_SUB ->
                        wideningVector(esz, SveIntegerOp64.MultiplyIndexed.Op.SQDMLSLBT, rd, rn, rm, false, 1, address);
                default -> null;
            };
            case FAMILY_CMLA_VECTOR -> // esz 0..3 todos válidos — sem restrição (`cmla_fns` do QEMU tem as 4).
                    architecture.has(Aarch64Feature.SVE2)
                            ? new SveIntegerOp64.MultiplyIndexed(SveIntegerOp64.MultiplyIndexed.Op.CMLA, esz, rd, rn, rm, false,
                                    0, low, false, 0, address)
                            : null;
            case FAMILY_SQRDCMLAH_VECTOR ->
                    architecture.has(Aarch64Feature.SVE2)
                            ? new SveIntegerOp64.MultiplyIndexed(SveIntegerOp64.MultiplyIndexed.Op.SQRDCMLAH, esz, rd, rn, rm,
                                    false, 0, low, false, 0, address)
                            : null;
            case FAMILY_LONG_SIGNED_UNSIGNED_ADD -> wideningVector(esz,
                    (low & LOW_SECOND_SELECTOR_MASK) == 0 ? SveIntegerOp64.MultiplyIndexed.Op.SMLAL
                            : SveIntegerOp64.MultiplyIndexed.Op.UMLAL,
                    rd, rn, rm, top, top ? 1 : 0, address);
            case FAMILY_LONG_SIGNED_UNSIGNED_SUB -> wideningVector(esz,
                    (low & LOW_SECOND_SELECTOR_MASK) == 0 ? SveIntegerOp64.MultiplyIndexed.Op.SMLSL
                            : SveIntegerOp64.MultiplyIndexed.Op.UMLSL,
                    rd, rn, rm, top, top ? 1 : 0, address);
            case FAMILY_SQDMLAL_STRAIGHT -> wideningVector(esz,
                    (low & LOW_SECOND_SELECTOR_MASK) == 0 ? SveIntegerOp64.MultiplyIndexed.Op.SQDMLAL
                            : SveIntegerOp64.MultiplyIndexed.Op.SQDMLSL,
                    rd, rn, rm, top, top ? 1 : 0, address);
            case FAMILY_HIGH_MULTIPLY -> switch (low) {
                case LOW_SQRDMLAH -> sameSizeVector(esz, SveIntegerOp64.MultiplyIndexed.Op.SQRDMLAH, rd, rn, rm, address);
                case LOW_SQRDMLSH -> sameSizeVector(esz, SveIntegerOp64.MultiplyIndexed.Op.SQRDMLSH, rd, rn, rm, address);
                case LOW_USDOT_ZZZZ_4S -> esz == ESZ_WORD && architecture.has(Aarch64Feature.INT8_MATRIX_MULTIPLY)
                        ? new SveIntegerOp64.MultiplyIndexed(SveIntegerOp64.MultiplyIndexed.Op.USDOT, ESZ_WORD, rd, rn, rm,
                                false, 0, 0, false, WAYS_FOUR, address)
                        : null;
                default -> null;
            };
            // `1100`: `low` distingue `SCLAMP`(00)/`UCLAMP`(01) — B17.22, sem relação com multiply — de
            // `SDOT_zzzz_2s`(10)/`UDOT_zzzz_2s`(11), mesmo family bits que o dot de 2 vias indexado.
            case DOT_TWO_WAY_FAMILY -> switch (low) {
                case 0b00 -> clamp(SveIntegerOp64.Clamp.Op.SCLAMP, esz, rd, rn, rm, address);
                case 0b01 -> clamp(SveIntegerOp64.Clamp.Op.UCLAMP, esz, rd, rn, rm, address);
                default -> esz == ESZ_BYTE && architecture.has(Aarch64Feature.SVE2_1)
                        ? new SveIntegerOp64.MultiplyIndexed(
                                low == LOW_UDOT_ZZZZ_2S ? SveIntegerOp64.MultiplyIndexed.Op.UDOT
                                        : SveIntegerOp64.MultiplyIndexed.Op.SDOT,
                                ESZ_WORD, rd, rn, rm, false, 0, 0, false, WAYS_TWO, address)
                        : null;
            };
            default -> null;
        };
    }

    /// `SCLAMP`/`UCLAMP` (B17.22, `FEAT_SME`/`FEAT_SVE2p1`): `Zd = min(max(Zd, Zn), Zm)`. Mora aqui (não num
    /// decoder próprio) porque compartilha o family `1100` do prefixo `0x44`/`bit 21 = 0` com o dot de 2 vias.
    private Ir64Op clamp(SveIntegerOp64.Clamp.Op op, int esz, int rd, int rn, int rm, long address) {
        return architecture.has(Aarch64Feature.SVE2_1) || architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION)
                ? new SveIntegerOp64.Clamp(op, esz, rd, rn, rm, address)
                : null;
    }

    /// `SMLALB/T`/`UMLALB/T`/`SMLSLB/T`/`UMLSLB/T`/`SQDMLALB/T`/`SQDMLSLB/T`/`SQDMLALBT`/`SQDMLSLBT` não-indexados:
    /// `esz = 0` (byte) não é alocado (`fns[0] = NULL` no QEMU — só `H`/`S`/`D`, ao contrário das formas indexadas
    /// da B17.8 que exigem `S`/`D`).
    private Ir64Op wideningVector(int esz, SveIntegerOp64.MultiplyIndexed.Op op, int rd, int rn, int rm, boolean topN,
            int topM, long address) {
        if (esz == ESZ_BYTE || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        return new SveIntegerOp64.MultiplyIndexed(op, esz, rd, rn, rm, false, topM, 0, topN, 0, address);
    }

    /// `SQRDMLAH_zzzz`/`SQRDMLSH_zzzz`: todo `esz` (`B`/`H`/`S`/`D`) é válido — os 4 helpers do QEMU existem.
    private Ir64Op sameSizeVector(int esz, SveIntegerOp64.MultiplyIndexed.Op op, int rd, int rn, int rm, long address) {
        return architecture.has(Aarch64Feature.SVE2)
                ? new SveIntegerOp64.MultiplyIndexed(op, esz, rd, rn, rm, false, 0, 0, false, 0, address)
                : null;
    }

    // ── bit 21 = 1: multiply indexado ────────────────────────────────────────────────────────────

    private Ir64Op decodeIndexed(int word, long address) {
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int family = (word >>> FAMILY_SHIFT) & FAMILY_MASK;
        int low = (word >>> LOW_SHIFT) & LOW_MASK;
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        boolean wordOrDouble = esz >= ESZ_FIELD_WORD;
        return switch (family) {
            case FAMILY_DOT_MLA -> low <= LOW_UDOT_OR_SQRDMLSH_OR_SQRDMULH
                    ? decodeDot(word, address, esz, low == 0 ? SveIntegerOp64.MultiplyIndexed.Op.SDOT
                            : SveIntegerOp64.MultiplyIndexed.Op.UDOT, rd, rn)
                    : sameSize(word, address, esz, low == LOW_MLA_OR_USDOT_OR_MUL ? SveIntegerOp64.MultiplyIndexed.Op.MLA
                            : SveIntegerOp64.MultiplyIndexed.Op.MLS, rd, rn);
            case FAMILY_SQRDMLA_USDOT -> low <= LOW_UDOT_OR_SQRDMLSH_OR_SQRDMULH
                    ? sameSize(word, address, esz, low == 0 ? SveIntegerOp64.MultiplyIndexed.Op.SQRDMLAH
                            : SveIntegerOp64.MultiplyIndexed.Op.SQRDMLSH, rd, rn)
                    : mixedSignDot(word, address, esz, low == LOW_MLA_OR_USDOT_OR_MUL
                            ? SveIntegerOp64.MultiplyIndexed.Op.USDOT : SveIntegerOp64.MultiplyIndexed.Op.SUDOT, rd, rn);
            case FAMILY_SQDMLAL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.SQDMLAL,
                    rd, rn);
            case FAMILY_SQDMLSL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.SQDMLSL,
                    rd, rn);
            case FAMILY_SMLAL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.SMLAL, rd, rn);
            case FAMILY_UMLAL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.UMLAL, rd, rn);
            case FAMILY_SMLSL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.SMLSL, rd, rn);
            case FAMILY_UMLSL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.UMLSL, rd, rn);
            case FAMILY_SMULL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.SMULL, rd, rn);
            case FAMILY_UMULL -> widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.UMULL, rd, rn);
            case FAMILY_SQDMULL ->
                    widening(word, address, wordOrDouble, esz, SveIntegerOp64.MultiplyIndexed.Op.SQDMULL, rd, rn);
            case FAMILY_CDOT -> complex(word, address, esz, SveIntegerOp64.MultiplyIndexed.Op.CDOT, esz, rd, rn);
            case FAMILY_CMLA -> complex(word, address, esz, SveIntegerOp64.MultiplyIndexed.Op.CMLA, esz - 1, rd, rn);
            case FAMILY_SQRDCMLAH ->
                    complex(word, address, esz, SveIntegerOp64.MultiplyIndexed.Op.SQRDCMLAH, esz - 1, rd, rn);
            case FAMILY_SQDMULH_MUL -> low > LOW_MLA_OR_USDOT_OR_MUL
                    ? null
                    : sameSize(word, address, esz, switch (low) {
                        case LOW_SDOT_OR_SQRDMLAH_OR_SQDMULH -> SveIntegerOp64.MultiplyIndexed.Op.SQDMULH;
                        case LOW_UDOT_OR_SQRDMLSH_OR_SQRDMULH -> SveIntegerOp64.MultiplyIndexed.Op.SQRDMULH;
                        default -> SveIntegerOp64.MultiplyIndexed.Op.MUL;
                    }, rd, rn);
            default -> null;
        };
    }

    /// `SDOT`/`UDOT` de 4 vias indexados: só `.S` (`esz = 10`, `_2`) e `.D` (`esz = 11`, `_1`); `FEAT_SVE`.
    private Ir64Op decodeDot(int word, long address, int esz, SveIntegerOp64.MultiplyIndexed.Op op, int rd, int rn) {
        if (esz < ESZ_FIELD_WORD) {
            return null;
        }
        boolean word32 = esz == ESZ_WORD;
        return new SveIntegerOp64.MultiplyIndexed(op, esz, rd, rn, rm(word, word32), true, sameSizeIndex(word, esz), 0,
                false, WAYS_FOUR, address);
    }

    /// `USDOT`/`SUDOT` indexados: só `.S` (`_2`); `FEAT_I8MM`.
    private Ir64Op mixedSignDot(int word, long address, int esz, SveIntegerOp64.MultiplyIndexed.Op op, int rd, int rn) {
        if (esz != ESZ_WORD || !architecture.has(Aarch64Feature.INT8_MATRIX_MULTIPLY)) {
            return null;
        }
        return new SveIntegerOp64.MultiplyIndexed(op, ESZ_WORD, rd, rn, rm(word, true), true, sameSizeIndex(word, esz), 0,
                false, WAYS_FOUR, address);
    }

    /// Operações no tamanho do elemento: `esz = 0x` é half (`_3`, o bit 22 é o bit ALTO do índice), `10` é word
    /// (`_2`) e `11` é double (`_1`). `FEAT_SVE2`.
    private Ir64Op sameSize(int word, long address, int esz, SveIntegerOp64.MultiplyIndexed.Op op, int rd, int rn) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        int size = esz >= ESZ_FIELD_WORD ? esz : ESZ_HALFWORD;
        return new SveIntegerOp64.MultiplyIndexed(op, size, rd, rn, rm(word, size != ESZ_DOUBLEWORD), true,
                sameSizeIndex(word, esz), 0, false, 0, address);
    }

    /// `_3`/`_2`/`_1`: o índice depende do tamanho, e o de half pega o bit 22 como bit mais alto.
    private static int sameSizeIndex(int word, int esz) {
        if (esz < ESZ_FIELD_WORD) {
            return (bit(word, BIT_INDEX_HALF) ? 0b100 : 0) | ((word >>> INDEX_PAIR_SHIFT) & INDEX_PAIR_MASK);
        }
        return esz == ESZ_WORD ? (word >>> INDEX_PAIR_SHIFT) & INDEX_PAIR_MASK : bit(word, BIT_INDEX_HIGH) ? 1 : 0;
    }

    /// `Zm` de 3 bits (`Z0`-`Z7`) ou de 4 bits (`Z0`-`Z15`).
    private static int rm(int word, boolean threeBits) {
        return (word >>> RM_SHIFT) & (threeBits ? RM3_MASK : RM4_MASK);
    }

    /// Alargantes `_3a`/`_2a`: só `.S` e `.D`; `bit 10` é `B`/`T`, `bit 11` é o bit baixo do índice.
    private Ir64Op widening(int word, long address, boolean wordOrDouble, int esz, SveIntegerOp64.MultiplyIndexed.Op op,
            int rd, int rn) {
        if (!wordOrDouble || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        boolean word32 = esz == ESZ_WORD;
        int low = bit(word, BIT_INDEX_LOW) ? 1 : 0;
        int index = word32 ? (((word >>> INDEX_PAIR_SHIFT) & INDEX_PAIR_MASK) << 1) | low
                : ((bit(word, BIT_INDEX_HIGH) ? 1 : 0) << 1) | low;
        return new SveIntegerOp64.MultiplyIndexed(op, esz, rd, rn, rm(word, word32), true, index, 0, bit(word, BIT_TOP),
                0, address);
    }

    /// `CDOT` (destino `.S`/`.D`, `esz` do encoding) e `CMLA`/`SQRDCMLAH` (elemento `.H`/`.S`, `esz - 1`): as duas
    /// usam `index:2 rm:3` para `esz = 10` e `index:1 rm:4` para `esz = 11`; `bits[11:10]` é a rotação.
    private Ir64Op complex(int word, long address, int esz, SveIntegerOp64.MultiplyIndexed.Op op, int elementEsz, int rd,
            int rn) {
        if (esz < ESZ_FIELD_WORD || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        boolean word32 = esz == ESZ_WORD;
        int index = word32 ? (word >>> INDEX_PAIR_SHIFT) & INDEX_PAIR_MASK : bit(word, BIT_INDEX_HIGH) ? 1 : 0;
        return new SveIntegerOp64.MultiplyIndexed(op, elementEsz, rd, rn, rm(word, word32), true, index,
                (word >>> ROT_SHIFT) & ROT_MASK, false, 0, address);
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }
}
