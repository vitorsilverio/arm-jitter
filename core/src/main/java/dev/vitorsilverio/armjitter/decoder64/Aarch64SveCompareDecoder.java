package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SvePredicateOp64;

/// Decoder SVE das comparações da B17.9: `### SVE Integer Compare - Vectors|Unsigned Immediate|Signed Immediate|Scalars
/// Group` do `sve.decode` do QEMU — 26 encodings que produzem predicado (16 vetor/largo + 10 imediato) e, dos 12
/// escalares, 6 decodificados aqui (`CTERM`, `WHILE_lt`, `WHILE_gt`, `WHILE_ptr`, `WHILE_lt_pair`, `WHILE_gt_pair`).
///
/// **Cada padrão é o `decodetree` transcrito em `máscara`/`valor`**, conferido contra `aarch64-none-elf-as`. O gate de
/// feature é POR LINHA (não pelo grupo): `CTERM`/`WHILE_lt`/comparações, `FEAT_SVE`; `WHILE_gt` e `WHILE_ptr`,
/// `FEAT_SVE2` (`aa64_sme_or_sve2`); as `_pair`, `FEAT_SVE2p1`.
///
/// `WHILE_lt|gt_cnt2|cnt4` (B17.28) escrevem um predicado-COMO-CONTADOR (`PN8`-`PN15`), estado que NÃO é máscara de bits
/// (Armadilha 4 da B17.4): `FEAT_SVE2p1` ou `FEAT_SME2`. `PEXT` vive em {@link Aarch64SveCounterDecoder}. Tudo o que não
/// bate exatamente devolve `null` (G8).|gt_cnt2|cnt4` e `PEXT_1|2` escrevem/leem predicado-COMO-CONTADOR (`PN8`-`PN15`),
/// estado que NÃO é máscara de bits (Armadilha 4 da B17.4). Devolvem `null` e são recusadas — nunca decodificadas como
/// `WHILE` comum escrevendo `P<n>`. Tudo o que não bate exatamente devolve `null` (G8).
final class Aarch64SveCompareDecoder {

    private static final int PREDICATE_MASK = 0b1111;
    private static final int REGISTER_MASK = 0b11111;
    private static final int PG_MASK = 0b111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int OPCODE_SHIFT = 13;
    private static final int OPCODE_MASK = 0b111;
    private static final int BIT_SECOND = 4;
    private static final int ESZ_DOUBLEWORD = 3;

    private static final int BIT_IMMEDIATE_SELECT = 21;
    private static final int UNSIGNED_IMM_SHIFT = 14;
    private static final int UNSIGNED_IMM_MASK = 0b1111111;
    private static final int UNSIGNED_IMM_LOW_BIT = 13;
    private static final int SIGNED_IMM_BITS = 5;

    // Opcodes (bits 15:13) do grupo vetorial/largo (prefixo 0x24, bit 21 = 0).
    private static final int OP_VECTOR_HS_HI = 0b000;
    private static final int OP_WIDE_EQ_NE = 0b001;
    private static final int OP_WIDE_GE_GT = 0b010;
    private static final int OP_WIDE_LT_LE = 0b011;
    private static final int OP_VECTOR_GE_GT = 0b100;
    private static final int OP_VECTOR_EQ_NE = 0b101;
    private static final int OP_WIDE_HS_HI = 0b110;
    // Opcodes do grupo com imediato COM sinal (prefixo 0x25, bit 21 = 0).
    private static final int OP_SIGNED_IMM_GE_GT = 0b000;
    private static final int OP_SIGNED_IMM_LT_LE = 0b001;
    private static final int OP_SIGNED_IMM_EQ_NE = 0b100;

    // Grupo escalar (prefixo 0x25, bit 21 = 1): máscara/valor de cada linha.
    private static final int CTERM_MASK = 0xFFA0FC0F;
    private static final int CTERM_VALUE = 0x25A02000;
    private static final int CTERM_SF_BIT = 22;
    private static final int WHILE_MASK = 0xFF20E000;
    private static final int WHILE_VALUE = 0x25200000;
    private static final int WHILE_SF_BIT = 12;
    private static final int WHILE_UNSIGNED_BIT = 11;
    private static final int WHILE_LESS_BIT = 10;
    private static final int WHILE_PTR_MASK = 0xFF20FC00;
    private static final int WHILE_PTR_VALUE = 0x25203000;
    private static final int WHILE_PAIR_MASK = 0xFF20F010;
    private static final int WHILE_PAIR_VALUE = 0x25205010;
    private static final int PAIR_INDEX_SHIFT = 1;
    private static final int PAIR_INDEX_MASK = 0b111;
    private static final int PAIR_STRIDE = 2;
    private static final int WHILE_COUNTER_MASK = 0xFF20F410;
    private static final int WHILE_COUNTER_LT_CNT2 = 0x25204410;
    private static final int WHILE_COUNTER_LT_CNT4 = 0x25206410;
    private static final int WHILE_COUNTER_GT_CNT2 = 0x25204010;
    private static final int WHILE_COUNTER_GT_CNT4 = 0x25206010;
    private static final int WHILE_COUNTER_EQ_BIT = 3;
    private static final int COUNTER_BASE = 8;
    private static final int COUNTER_FIELD_MASK = 0b111;

    private final Aarch64Architecture architecture;

    Aarch64SveCompareDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Prefixo `0x24`: comparação vetorial/larga (bit 21 = 0) ou com imediato SEM sinal (bit 21 = 1).
    Ir64Op decodePrefix24(int word, long address) {
        if (bit(word, BIT_IMMEDIATE_SELECT)) {
            return decodeUnsignedImmediate(word, address);
        }
        int opcode = field(word, OPCODE_SHIFT, OPCODE_MASK);
        boolean second = bit(word, BIT_SECOND);
        boolean wide = opcode != OP_VECTOR_HS_HI && opcode != OP_VECTOR_GE_GT && opcode != OP_VECTOR_EQ_NE;
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if (wide && esz == ESZ_DOUBLEWORD) {
            return null; // o operando largo já tem 64 bits: `esz = 3` não é alocado
        }
        SvePredicateOp64.Compare.Cond cond = switch (opcode) {
            case OP_VECTOR_HS_HI, OP_WIDE_HS_HI -> second ? SvePredicateOp64.Compare.Cond.HI : SvePredicateOp64.Compare.Cond.HS;
            case OP_VECTOR_GE_GT, OP_WIDE_GE_GT -> second ? SvePredicateOp64.Compare.Cond.GT : SvePredicateOp64.Compare.Cond.GE;
            case OP_VECTOR_EQ_NE, OP_WIDE_EQ_NE -> second ? SvePredicateOp64.Compare.Cond.NE : SvePredicateOp64.Compare.Cond.EQ;
            case OP_WIDE_LT_LE -> second ? SvePredicateOp64.Compare.Cond.LE : SvePredicateOp64.Compare.Cond.LT;
            default -> second ? SvePredicateOp64.Compare.Cond.LS : SvePredicateOp64.Compare.Cond.LO; // OP_WIDE_LO_LS: os 8 opcodes existem
        };
        return new SvePredicateOp64.Compare(cond, wide ? SvePredicateOp64.Compare.Form.WIDE : SvePredicateOp64.Compare.Form.VECTOR, esz,
                field(word, 0, PREDICATE_MASK), field(word, PG_SHIFT, PG_MASK), field(word, RN_SHIFT, REGISTER_MASK),
                field(word, RM_SHIFT, REGISTER_MASK), 0, address);
    }

    private Ir64Op decodeUnsignedImmediate(int word, long address) {
        boolean second = bit(word, BIT_SECOND);
        SvePredicateOp64.Compare.Cond cond = bit(word, UNSIGNED_IMM_LOW_BIT)
                ? (second ? SvePredicateOp64.Compare.Cond.LS : SvePredicateOp64.Compare.Cond.LO)
                : (second ? SvePredicateOp64.Compare.Cond.HI : SvePredicateOp64.Compare.Cond.HS);
        return new SvePredicateOp64.Compare(cond, SvePredicateOp64.Compare.Form.IMMEDIATE, field(word, ESZ_SHIFT, ESZ_MASK),
                field(word, 0, PREDICATE_MASK), field(word, PG_SHIFT, PG_MASK), field(word, RN_SHIFT, REGISTER_MASK),
                0, field(word, UNSIGNED_IMM_SHIFT, UNSIGNED_IMM_MASK), address);
    }

    /// Prefixo `0x25`: imediato COM sinal (bit 21 = 0) e o grupo escalar (bit 21 = 1). O que não bate devolve
    /// `null` para o chamador tentar os demais grupos do prefixo.
    Ir64Op decodePrefix25(int word, long address) {
        return bit(word, BIT_IMMEDIATE_SELECT) ? decodeScalar(word, address) : decodeSignedImmediate(word, address);
    }

    private Ir64Op decodeSignedImmediate(int word, long address) {
        boolean second = bit(word, BIT_SECOND);
        SvePredicateOp64.Compare.Cond cond = switch (field(word, OPCODE_SHIFT, OPCODE_MASK)) {
            case OP_SIGNED_IMM_GE_GT -> second ? SvePredicateOp64.Compare.Cond.GT : SvePredicateOp64.Compare.Cond.GE;
            case OP_SIGNED_IMM_LT_LE -> second ? SvePredicateOp64.Compare.Cond.LE : SvePredicateOp64.Compare.Cond.LT;
            case OP_SIGNED_IMM_EQ_NE -> second ? SvePredicateOp64.Compare.Cond.NE : SvePredicateOp64.Compare.Cond.EQ;
            default -> null;
        };
        if (cond == null) {
            return null;
        }
        int imm = (field(word, RM_SHIFT, REGISTER_MASK) << (Integer.SIZE - SIGNED_IMM_BITS))
                >> (Integer.SIZE - SIGNED_IMM_BITS);
        return new SvePredicateOp64.Compare(cond, SvePredicateOp64.Compare.Form.IMMEDIATE, field(word, ESZ_SHIFT, ESZ_MASK),
                field(word, 0, PREDICATE_MASK), field(word, PG_SHIFT, PG_MASK), field(word, RN_SHIFT, REGISTER_MASK),
                0, imm, address);
    }

    private Ir64Op decodeScalar(int word, long address) {
        int rn = field(word, RN_SHIFT, REGISTER_MASK);
        int rm = field(word, RM_SHIFT, REGISTER_MASK);
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        boolean secondBit = bit(word, BIT_SECOND);
        if ((word & CTERM_MASK) == CTERM_VALUE) {
            return new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.CTERM, 0, 0, rn, rm, bit(word, CTERM_SF_BIT),
                    false, secondBit, address);
        }
        if ((word & WHILE_MASK) == WHILE_VALUE) {
            SvePredicateOp64.ScalarCompare.Op op = bit(word, WHILE_LESS_BIT)
                    ? SvePredicateOp64.ScalarCompare.Op.WHILE_LT
                    : SvePredicateOp64.ScalarCompare.Op.WHILE_GT;
            if (op == SvePredicateOp64.ScalarCompare.Op.WHILE_GT && !architecture.has(Aarch64Feature.SVE2)) {
                return null;
            }
            return new SvePredicateOp64.ScalarCompare(op, esz, field(word, 0, PREDICATE_MASK), rn, rm,
                    bit(word, WHILE_SF_BIT), bit(word, WHILE_UNSIGNED_BIT), secondBit, address);
        }
        if ((word & WHILE_PTR_MASK) == WHILE_PTR_VALUE) {
            return architecture.has(Aarch64Feature.SVE2)
                    ? new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_PTR, esz,
                            field(word, 0, PREDICATE_MASK), rn, rm, true, false, secondBit, address)
                    : null;
        }
        if ((word & WHILE_PAIR_MASK) == WHILE_PAIR_VALUE) {
            if (!architecture.has(Aarch64Feature.SVE2_1)) {
                return null;
            }
            SvePredicateOp64.ScalarCompare.Op op = bit(word, WHILE_LESS_BIT)
                    ? SvePredicateOp64.ScalarCompare.Op.WHILE_LT_PAIR
                    : SvePredicateOp64.ScalarCompare.Op.WHILE_GT_PAIR;
            // O destino é um PAR: os bits 3:1 vezes 2 (o bit 0 é o `eq`, não faz parte do registrador).
            return new SvePredicateOp64.ScalarCompare(op, esz,
                    field(word, PAIR_INDEX_SHIFT, PAIR_INDEX_MASK) * PAIR_STRIDE, rn, rm, true,
                    bit(word, WHILE_UNSIGNED_BIT), bit(word, 0), address);
        }
        SvePredicateOp64.ScalarCompare.Op counterOp = switch (word & WHILE_COUNTER_MASK) {
            case WHILE_COUNTER_LT_CNT2 -> SvePredicateOp64.ScalarCompare.Op.WHILE_LT_CNT2;
            case WHILE_COUNTER_LT_CNT4 -> SvePredicateOp64.ScalarCompare.Op.WHILE_LT_CNT4;
            case WHILE_COUNTER_GT_CNT2 -> SvePredicateOp64.ScalarCompare.Op.WHILE_GT_CNT2;
            case WHILE_COUNTER_GT_CNT4 -> SvePredicateOp64.ScalarCompare.Op.WHILE_GT_CNT4;
            default -> null;
        };
        if (counterOp != null) {
            // B17.28: destino é um predicado-COMO-CONTADOR (`PN8`-`PN15`), sempre com operandos de 64 bits.
            return architecture.has(Aarch64Feature.SVE2_1) || architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2)
                    ? new SvePredicateOp64.ScalarCompare(counterOp, esz, COUNTER_BASE + (word & COUNTER_FIELD_MASK), rn, rm, true,
                            bit(word, WHILE_UNSIGNED_BIT), bit(word, WHILE_COUNTER_EQ_BIT), address)
                    : null;
        }
        return null;
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
