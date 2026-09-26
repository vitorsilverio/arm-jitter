package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE da permutação de predicado, da permutação predicada e do `SEL` (B17.11): os grupos
/// `### SVE Permute - Predicates`, `- Predicated` e `### SVE Select Vectors` do `sve.decode` do QEMU — 36 encodings,
/// todos no prefixo `0x05`.
///
/// **Cada padrão é o `decodetree` transcrito em `máscara`/`valor`**, medido contra `aarch64-none-elf-as`. O gate de
/// feature é POR LINHA e segue o `translate-sve.c` do QEMU (não o rascunho da spec):
///
/// - SVE base: permutação de predicado, `CLAST*`/`LAST*`, `CPY`, `REVB/H/W_m`, `RBIT_m`, `SPLICE`, `SEL`, e `COMPACT`
///   com `esz >= 2`;
/// - SVE2: `SPLICE` construtivo; SVE2.1: `REVD_m`;
/// - **SVE2.2**: as cinco formas `_z` (zeroing), `EXPAND` e `COMPACT` com `esz < 2` (a spec dizia SVE2.1 para
///   `EXPAND` e "recusar" `COMPACT` de byte/halfword — ambos existem, só que a partir de SVE2.2).
///
/// O que não bate exatamente — inclusive `REVB` com `esz = 0`, `REVH` com `esz < 2` e `REVW` com `esz < 3` (a
/// operação não cabe no elemento) — devolve `null` e o chamador recusa a instrução (G8).
final class Aarch64SvePredicatedPermuteDecoder {
    private static final int RD_MASK = 0b11111;
    private static final int RN_SHIFT = 5;
    private static final int RN_MASK = 0b11111;
    private static final int RM_SHIFT = 16;
    private static final int RM_MASK = 0b11111;
    private static final int PG_SHIFT = 10;
    private static final int PG_MASK = 0b111;
    private static final int PG_WIDE_MASK = 0b1111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int PREDICATE_MASK = 0b1111;
    private static final int PREDICATE_RM_SHIFT = 16;
    private static final int PREDICATE_OPCODE_SHIFT = 10;
    private static final int PREDICATE_OPCODE_MASK = 0b111;

    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;

    // ── Permutação de predicado ───────────────────────────────────────────────────────────────────
    private static final int PREDICATE_PERMUTE_MASK = 0xFF30E210;
    private static final int PREDICATE_PERMUTE_VALUE = 0x05204000;
    private static final int REV_P_MASK = 0xFF3FFE10;
    private static final int REV_P_VALUE = 0x05344000;
    private static final int PUNPK_MASK = 0xFFFEFE10;
    private static final int PUNPK_VALUE = 0x05304000;
    private static final int PUNPK_HIGH_BIT = 16;
    private static final int PREDICATE_ORDER_ZIP1 = 0b000;
    private static final int PREDICATE_ORDER_ZIP2 = 0b001;
    private static final int PREDICATE_ORDER_UZP1 = 0b010;
    private static final int PREDICATE_ORDER_UZP2 = 0b011;
    private static final int PREDICATE_ORDER_TRN1 = 0b100;
    private static final int PREDICATE_ORDER_TRN2 = 0b101;

    // ── Predicada: máscara `esz:2 | bits 21:13` inteira, exceto `esz` ─────────────────────────────
    private static final int FULL_MASK = 0xFF3FE000;
    private static final int PAIR_MASK = 0xFF3EE000;
    private static final int SEL_MASK = 0xFF20C000;
    private static final int SEL_VALUE = 0x0520C000;
    private static final int COMPACT_VALUE = 0x05218000;
    private static final int EXPAND_VALUE = 0x05318000;
    private static final int SPLICE_VALUE = 0x052C8000;
    private static final int SPLICE_SVE2_VALUE = 0x052D8000;
    private static final int CPY_M_V_VALUE = 0x05208000;
    private static final int CPY_M_R_VALUE = 0x0528A000;
    private static final int CLAST_Z_VALUE = 0x05288000;
    private static final int CLAST_V_VALUE = 0x052A8000;
    private static final int CLAST_R_VALUE = 0x0530A000;
    private static final int LAST_V_VALUE = 0x05228000;
    private static final int LAST_R_VALUE = 0x0520A000;
    private static final int AFTER_BEFORE_BIT = 16;
    private static final int REVB_VALUE = 0x05248000;
    private static final int REVH_VALUE = 0x05258000;
    private static final int REVW_VALUE = 0x05268000;
    private static final int RBIT_VALUE = 0x05278000;
    private static final int ZEROING_BIT = 13;
    private static final int REVD_MASK = 0xFFFFE000;
    private static final int REVD_VALUE = 0x052E8000;
    private static final int REVERSE_KIND_MASK = 0xFF3FC000;

    private final Aarch64Architecture architecture;

    Aarch64SvePredicatedPermuteDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Prefixo `0x05`. Só é chamado DEPOIS de os decoders de imediato e de permutação recusarem a palavra.
    Ir64Op decodePrefix05(int word, long address) {
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        int pg = (word >>> PG_SHIFT) & PG_MASK;
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if ((word & PREDICATE_PERMUTE_MASK) == PREDICATE_PERMUTE_VALUE) {
            return decodePredicatePermute(word, esz, address);
        }
        if ((word & REV_P_MASK) == REV_P_VALUE) {
            return predicate(Ir64Op.SvePermutePredicated.Op.REV_P, esz, word & PREDICATE_MASK,
                    (word >>> RN_SHIFT) & PREDICATE_MASK, 0, address);
        }
        if ((word & PUNPK_MASK) == PUNPK_VALUE) {
            boolean high = ((word >>> PUNPK_HIGH_BIT) & 1) != 0;
            return predicate(high ? Ir64Op.SvePermutePredicated.Op.PUNPKHI : Ir64Op.SvePermutePredicated.Op.PUNPKLO,
                    0, word & PREDICATE_MASK, (word >>> RN_SHIFT) & PREDICATE_MASK, 0, address);
        }
        if ((word & SEL_MASK) == SEL_VALUE) {
            return op(Ir64Op.SvePermutePredicated.Op.SEL, esz, rd, rn, (word >>> RM_SHIFT) & RM_MASK,
                    (word >>> PG_SHIFT) & PG_WIDE_MASK, address);
        }
        Ir64Op.SvePermutePredicated.Op kind = decodePredicatedKind(word, esz);
        if (kind == null) {
            return null;
        }
        // `@rdn_pg_rm` (CLAST_z, SPLICE): o vetor Zm está em [9:5] e Zdn (=rd) é também o primeiro operando.
        return isDestructive(kind) ? op(kind, esz, rd, 0, rn, pg, address) : op(kind, esz, rd, rn, 0, pg, address);
    }

    private static boolean isDestructive(Ir64Op.SvePermutePredicated.Op kind) {
        return kind == Ir64Op.SvePermutePredicated.Op.CLASTA_Z || kind == Ir64Op.SvePermutePredicated.Op.CLASTB_Z
                || kind == Ir64Op.SvePermutePredicated.Op.SPLICE;
    }

    private Ir64Op decodePredicatePermute(int word, int esz, long address) {
        Ir64Op.SvePermutePredicated.Op kind = switch ((word >>> PREDICATE_OPCODE_SHIFT) & PREDICATE_OPCODE_MASK) {
            case PREDICATE_ORDER_ZIP1 -> Ir64Op.SvePermutePredicated.Op.ZIP1_P;
            case PREDICATE_ORDER_ZIP2 -> Ir64Op.SvePermutePredicated.Op.ZIP2_P;
            case PREDICATE_ORDER_UZP1 -> Ir64Op.SvePermutePredicated.Op.UZP1_P;
            case PREDICATE_ORDER_UZP2 -> Ir64Op.SvePermutePredicated.Op.UZP2_P;
            case PREDICATE_ORDER_TRN1 -> Ir64Op.SvePermutePredicated.Op.TRN1_P;
            case PREDICATE_ORDER_TRN2 -> Ir64Op.SvePermutePredicated.Op.TRN2_P;
            default -> null;
        };
        return kind == null ? null : predicate(kind, esz, word & PREDICATE_MASK,
                (word >>> RN_SHIFT) & PREDICATE_MASK, (word >>> PREDICATE_RM_SHIFT) & PREDICATE_MASK, address);
    }

    /// As 26 predicadas. Devolve `null` quando a palavra não é uma delas, quando falta a feature da linha ou quando o
    /// `esz` não comporta a operação.
    private Ir64Op.SvePermutePredicated.Op decodePredicatedKind(int word, int esz) {
        boolean before = ((word >>> AFTER_BEFORE_BIT) & 1) != 0;
        int fullPattern = word & FULL_MASK;
        int pairPattern = word & PAIR_MASK;
        if (fullPattern == COMPACT_VALUE) {
            return esz >= ESZ_WORD || sve22() ? Ir64Op.SvePermutePredicated.Op.COMPACT : null;
        }
        if (fullPattern == EXPAND_VALUE) {
            return sve22() ? Ir64Op.SvePermutePredicated.Op.EXPAND : null;
        }
        if (fullPattern == SPLICE_VALUE) {
            return Ir64Op.SvePermutePredicated.Op.SPLICE;
        }
        if (fullPattern == SPLICE_SVE2_VALUE) {
            return architecture.has(Aarch64Feature.SVE2) ? Ir64Op.SvePermutePredicated.Op.SPLICE_SVE2 : null;
        }
        if (fullPattern == CPY_M_V_VALUE) {
            return Ir64Op.SvePermutePredicated.Op.CPY_M_V;
        }
        if (fullPattern == CPY_M_R_VALUE) {
            return Ir64Op.SvePermutePredicated.Op.CPY_M_R;
        }
        if (pairPattern == CLAST_Z_VALUE) {
            return before ? Ir64Op.SvePermutePredicated.Op.CLASTB_Z : Ir64Op.SvePermutePredicated.Op.CLASTA_Z;
        }
        if (pairPattern == CLAST_V_VALUE) {
            return before ? Ir64Op.SvePermutePredicated.Op.CLASTB_V : Ir64Op.SvePermutePredicated.Op.CLASTA_V;
        }
        if (pairPattern == CLAST_R_VALUE) {
            return before ? Ir64Op.SvePermutePredicated.Op.CLASTB_R : Ir64Op.SvePermutePredicated.Op.CLASTA_R;
        }
        if (pairPattern == LAST_V_VALUE) {
            return before ? Ir64Op.SvePermutePredicated.Op.LASTB_V : Ir64Op.SvePermutePredicated.Op.LASTA_V;
        }
        if (pairPattern == LAST_R_VALUE) {
            return before ? Ir64Op.SvePermutePredicated.Op.LASTB_R : Ir64Op.SvePermutePredicated.Op.LASTA_R;
        }
        return decodeReverse(word, esz);
    }

    /// `REVB`/`REVH`/`REVW`/`RBIT`/`REVD`, formas `_m` (bit 13 = 0) e `_z` (bit 13 = 1, SVE2.2).
    private Ir64Op.SvePermutePredicated.Op decodeReverse(int word, int esz) {
        boolean zeroing = ((word >>> ZEROING_BIT) & 1) != 0;
        if (zeroing && !sve22()) {
            return null;
        }
        if ((word & REVD_MASK & ~(1 << ZEROING_BIT)) == REVD_VALUE) {
            if (!zeroing && !sve21()) {
                return null;
            }
            return zeroing ? Ir64Op.SvePermutePredicated.Op.REVD_Z : Ir64Op.SvePermutePredicated.Op.REVD_M;
        }
        int pattern = word & REVERSE_KIND_MASK & ~(1 << ZEROING_BIT);
        if (pattern == REVB_VALUE) {
            return esz < 1 ? null : zeroing ? Ir64Op.SvePermutePredicated.Op.REVB_Z
                    : Ir64Op.SvePermutePredicated.Op.REVB_M;
        }
        if (pattern == REVH_VALUE) {
            return esz < ESZ_WORD ? null : zeroing ? Ir64Op.SvePermutePredicated.Op.REVH_Z
                    : Ir64Op.SvePermutePredicated.Op.REVH_M;
        }
        if (pattern == REVW_VALUE) {
            return esz != ESZ_DOUBLEWORD ? null : zeroing ? Ir64Op.SvePermutePredicated.Op.REVW_Z
                    : Ir64Op.SvePermutePredicated.Op.REVW_M;
        }
        if (pattern == RBIT_VALUE) {
            return zeroing ? Ir64Op.SvePermutePredicated.Op.RBIT_Z : Ir64Op.SvePermutePredicated.Op.RBIT_M;
        }
        return null;
    }

    private boolean sve21() {
        return architecture.has(Aarch64Feature.SVE2_1) || architecture.has(Aarch64Feature.SVE2_2);
    }

    private boolean sve22() {
        return architecture.has(Aarch64Feature.SVE2_2);
    }

    private static Ir64Op predicate(Ir64Op.SvePermutePredicated.Op op, int esz, int rd, int rn, int rm, long address) {
        return op(op, esz, rd, rn, rm, 0, address);
    }

    private static Ir64Op op(Ir64Op.SvePermutePredicated.Op op, int esz, int rd, int rn, int rm, int pg,
            long address) {
        return new Ir64Op.SvePermutePredicated(op, esz, rd, rn, rm, pg, address);
    }
}
