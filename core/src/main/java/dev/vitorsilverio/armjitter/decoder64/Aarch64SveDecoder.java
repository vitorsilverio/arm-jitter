package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder das instruções SVE (`FEAT_SVE`, classe `op0 = 0010` do A64), fatiado por grupo do
/// `sve.decode` do QEMU. Hoje cobre os predicados da B17.4: lógica de predicado, "misc"
/// (`PTEST`/`PTRUE`/`FFR`/`PFIRST`/`PNEXT`), partition break, contagem por predicado e contagem de
/// elementos — 38 dos 40 encodings do recorte (as duas exceções estão abaixo).
///
/// **Cada padrão abaixo é o `decodetree` transcrito em `máscara`/`valor`** (bits fixos de cada linha
/// de `sve.decode`, medidos contra `aarch64-none-elf-as`); nada é derivado por analogia. Tudo que não
/// bate exatamente devolve `null` e o chamador recusa a instrução (G8) — em particular:
///
/// - `PTRUE` na forma predicado-como-contador (`PTRUE_cnt`, `PN8`-`PN15`) e `CNTP` na forma
///   predicado-como-contador (`CNTP_c`), ambos SVE2.1: **pendência nomeada**, dependem do estado de
///   predicado-como-contador (B17.25 o cita), que NÃO é uma máscara de bits (Armadilha 4 da B17.4);
/// - `SEL` com `S = 1` (não alocado) e `INCP`/`SQINCP` vetoriais com `esz = 0` (não alocados).
///
/// `FIRSTP`/`LASTP` exigem `FEAT_SVE2p2` ({@link Aarch64Feature#SVE2_2}); o resto exige só
/// {@link Aarch64Feature#SVE}. Presets sem SVE recusam o espaço inteiro.
final class Aarch64SveDecoder {
    // ── Classe de encoding SVE: prefixos de 8 bits em bits[31:24] ────────────────────────────────
    private static final int PREFIX_SHIFT = 24;
    private static final int PREFIX_MASK = 0xFF;
    private static final int PREFIX_PREDICATE = 0x25;
    private static final int PREFIX_ELEMENT_COUNT = 0x04;

    // ── Campos comuns ─────────────────────────────────────────────────────────────────────────────
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int PREDICATE_FIELD_MASK = 0b1111;
    private static final int REGISTER_FIELD_MASK = 0b11111;
    private static final int PD_SHIFT = 0;
    private static final int PN_SHIFT = 5;
    private static final int PG_LOGICAL_SHIFT = 10;
    private static final int PM_SHIFT = 16;
    private static final int BIT_OPERATION_SELECT = 23;
    private static final int BIT_SET_FLAGS = 22;
    private static final int BIT_O2 = 9;
    private static final int BIT_O3 = 4;
    private static final int PATTERN_SHIFT = 5;
    private static final int PATTERN_MASK = 0b11111;
    private static final int PTRUE_PATTERN_SHIFT = 5;
    private static final int PTRUE_SET_FLAGS_BIT = 16;
    private static final int COUNT_PG_SHIFT = 10;
    private static final int INCDECP_PG_SHIFT = 5;
    private static final int INCDECP_D_BIT = 16;
    private static final int SINCDECP_D_BIT = 17;
    private static final int SINCDECP_U_BIT = 16;
    private static final int ELEMENT_COUNT_IMM4_SHIFT = 16;
    private static final int ELEMENT_COUNT_IMM4_MASK = 0b1111;
    private static final int ELEMENT_COUNT_D_BIT_UNSIGNED_FORMS = 11;
    private static final int ELEMENT_COUNT_U_BIT_UNSIGNED_FORMS = 10;
    private static final int ELEMENT_COUNT_D_BIT_PLAIN_FORMS = 10;
    private static final int RESERVED_ESZ_BYTE = 0;

    // ── B17.5: inteiro sem predicado (prefixo 0x04) ──────────────────────────────────────────────
    private static final int OPCODE_SHIFT = 10;
    private static final int OPCODE_FIELD_MASK = 0b111111;
    private static final int OP_ADD = 0b000000;
    private static final int OP_SUB = 0b000001;
    private static final int OP_SQADD = 0b000100;
    private static final int OP_UQADD = 0b000101;
    private static final int OP_SQSUB = 0b000110;
    private static final int OP_UQSUB = 0b000111;
    private static final int OP_LOGICAL = 0b001100;
    private static final int OP_XAR = 0b001101;
    private static final int OP_TERNARY_EOR3_BCAX = 0b001110;
    private static final int OP_TERNARY_BSL = 0b001111;
    private static final int OP_SHIFT_WIDE_ASR = 0b100000;
    private static final int OP_SHIFT_WIDE_LSR = 0b100001;
    private static final int OP_SHIFT_WIDE_LSL = 0b100011;
    private static final int OP_SHIFT_IMM_ASR = 0b100100;
    private static final int OP_SHIFT_IMM_LSR = 0b100101;
    private static final int OP_SHIFT_IMM_LSL = 0b100111;
    private static final int OP_INDEX_II = 0b010000;
    private static final int OP_INDEX_RI = 0b010001;
    private static final int OP_INDEX_IR = 0b010010;
    private static final int OP_INDEX_RR = 0b010011;
    private static final int OP_FTSSEL = 0b101100;
    private static final int FEXPA_MASK = 0xFF3FFC00;
    private static final int FEXPA_VALUE = 0x0420B800;
    private static final int MOVPRFX_MASK = 0xFFFFFC00;
    private static final int MOVPRFX_VALUE = 0x0420BC00;
    private static final int MULTIPLY_ADD_MASK = 0xFF200000;
    private static final int MULTIPLY_ADD_BASE = 0x04000000;
    private static final int MULTIPLY_ADD_OPCODE_SHIFT = 13;
    private static final int MULTIPLY_ADD_OPCODE_MASK = 0b111;
    private static final int MULTIPLY_ADD_MLA = 0b010;
    private static final int MULTIPLY_ADD_MLS = 0b011;
    private static final int MULTIPLY_ADD_MAD = 0b110;
    private static final int MULTIPLY_ADD_MSB = 0b111;
    private static final int PG_MULTIPLY_SHIFT = 10;
    private static final int PG_MULTIPLY_MASK = 0b111;
    private static final int RM_SHIFT = 16;
    private static final int RA_SHIFT = 5;
    private static final int TSZ_HIGH_SHIFT = 22;
    private static final int TSZ_LOW_SHIFT = 16;
    private static final int TSZ_HIGH_MASK = 0b11;
    private static final int TSZ_LOW_MASK = 0b11111;
    private static final int TSZ_HIGH_FIELD_SHIFT = 5;
    private static final int TSZ_ESZ_SHIFT = 3;
    private static final int SIGNED_IMMEDIATE_BITS = 5;
    private static final int INDEX_IMM_HIGH_SHIFT = 16;
    private static final int INDEX_IMM_LOW_SHIFT = 5;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int ESIZE_BITS_BASE = 8;
    private static final int ESIZE_SHR_BASE = 16;

    // ── B17.6: inteiro predicado (prefixo 0x04, bit 21 = 0) ──────────────────────────────────────
    private static final int BIT_PREDICATED_EXCLUDED = 21;
    private static final int PREDICATED_GROUP_SHIFT = 13;
    private static final int PREDICATED_GROUP_MASK = 0b111;
    private static final int PREDICATED_GROUP_BINARY = 0b000;
    private static final int PREDICATED_GROUP_SHIFT_OPS = 0b100;
    private static final int PREDICATED_GROUP_UNARY = 0b101;
    private static final int PREDICATED_GROUP_REDUCTION = 0b001;
    // B17.7: opcode (bits 20:16) do grupo de redução.
    private static final int RED_SADDV = 0x00;
    private static final int RED_UADDV = 0x01;
    private static final int RED_ADDQV = 0x05;
    private static final int RED_SMAXV = 0x08;
    private static final int RED_UMAXV = 0x09;
    private static final int RED_SMINV = 0x0A;
    private static final int RED_UMINV = 0x0B;
    private static final int RED_SMAXQV = 0x0C;
    private static final int RED_UMAXQV = 0x0D;
    private static final int RED_SMINQV = 0x0E;
    private static final int RED_UMINQV = 0x0F;
    private static final int RED_MOVPRFX_Z = 0x10;
    private static final int RED_MOVPRFX_M = 0x11;
    private static final int RED_ORV = 0x18;
    private static final int RED_EORV = 0x19;
    private static final int RED_ANDV = 0x1A;
    private static final int RED_ORQV = 0x1C;
    private static final int RED_EORQV = 0x1D;
    private static final int RED_ANDQV = 0x1E;
    private static final int PREDICATED_OPCODE_SHIFT = 16;
    private static final int PREDICATED_OPCODE_MASK = 0b11111;
    private static final int PG_PREDICATED_SHIFT = 10;
    private static final int PG_PREDICATED_MASK = 0b111;
    private static final int RN_PREDICATED_SHIFT = 5;
    private static final int TSZ_PREDICATED_LOW_SHIFT = 5;
    private static final int UNARY_MERGING_BIT = 4;
    private static final int UNARY_BIT_OPERATIONS = 3;
    private static final int UNARY_SELECTOR_MASK = 0b111;
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;
    // Binário predicado: bits 20:16 (bit 21 = 0 já garantido).
    private static final int PRED_ADD = 0x00;
    private static final int PRED_SUB = 0x01;
    private static final int PRED_SUBR = 0x03;
    private static final int PRED_SMAX = 0x08;
    private static final int PRED_UMAX = 0x09;
    private static final int PRED_SMIN = 0x0A;
    private static final int PRED_UMIN = 0x0B;
    private static final int PRED_SABD = 0x0C;
    private static final int PRED_UABD = 0x0D;
    private static final int PRED_MUL = 0x10;
    private static final int PRED_SMULH = 0x12;
    private static final int PRED_UMULH = 0x13;
    private static final int PRED_SDIV = 0x14;
    private static final int PRED_UDIV = 0x15;
    private static final int PRED_SDIVR = 0x16;
    private static final int PRED_UDIVR = 0x17;
    private static final int PRED_ORR = 0x18;
    private static final int PRED_EOR = 0x19;
    private static final int PRED_AND = 0x1A;
    private static final int PRED_BIC = 0x1B;
    // Shift predicado.
    private static final int PRED_SHIFT_ASR_IMM = 0x00;
    private static final int PRED_SHIFT_LSR_IMM = 0x01;
    private static final int PRED_SHIFT_LSL_IMM = 0x03;
    private static final int PRED_SHIFT_ASRD = 0x04;
    private static final int PRED_SHIFT_SQSHL_IMM = 0x06;
    private static final int PRED_SHIFT_UQSHL_IMM = 0x07;
    private static final int PRED_SHIFT_SRSHR = 0x0C;
    private static final int PRED_SHIFT_URSHR = 0x0D;
    private static final int PRED_SHIFT_SQSHLU = 0x0F;
    private static final int PRED_SHIFT_ASR = 0x10;
    private static final int PRED_SHIFT_LSR = 0x11;
    private static final int PRED_SHIFT_LSL = 0x13;
    private static final int PRED_SHIFT_ASRR = 0x14;
    private static final int PRED_SHIFT_LSRR = 0x15;
    private static final int PRED_SHIFT_LSLR = 0x17;
    private static final int PRED_SHIFT_ASR_WIDE = 0x18;
    private static final int PRED_SHIFT_LSR_WIDE = 0x19;
    private static final int PRED_SHIFT_LSL_WIDE = 0x1B;

    // ── Máscaras/valores (bits fixos de cada linha de sve.decode) ───────────────────────────────
    private static final int LOGICAL_MASK = 0xFF30C000;
    private static final int LOGICAL_VALUE = 0x25004000;
    private static final int BRKP_MASK = 0xFF30C200;
    private static final int BRKP_VALUE = 0x2500C000;
    private static final int PTEST_MASK = 0xFFFFC21F;
    private static final int PTEST_VALUE = 0x2550C000;
    private static final int PTRUE_MASK = 0xFF3EFC10;
    private static final int PTRUE_VALUE = 0x2518E000;
    private static final int SETFFR_WORD = 0x252C9000;
    private static final int PFALSE_MASK = 0xFFFFFFF0;
    private static final int PFALSE_VALUE = 0x2518E400;
    private static final int RDFFR_MASK = 0xFFFFFFF0;
    private static final int RDFFR_VALUE = 0x2519F000;
    private static final int RDFFR_PREDICATED_MASK = 0xFFBFFE10;
    private static final int RDFFR_PREDICATED_VALUE = 0x2518F000;
    private static final int WRFFR_MASK = 0xFFFFFE1F;
    private static final int WRFFR_VALUE = 0x25289000;
    private static final int PFIRST_MASK = 0xFFFFFE10;
    private static final int PFIRST_VALUE = 0x2558C000;
    private static final int PNEXT_MASK = 0xFF3FFE10;
    private static final int PNEXT_VALUE = 0x2519C400;
    private static final int BRKAB_MASK = 0xFF3FC200;
    private static final int BRKAB_VALUE = 0x25104000;
    private static final int BRKN_MASK = 0xFFBFC210;
    private static final int BRKN_VALUE = 0x25184000;
    private static final int CNTP_MASK = 0xFF3FC200;
    private static final int CNTP_VALUE = 0x25208000;
    private static final int FIRSTP_VALUE = 0x25218000;
    private static final int LASTP_VALUE = 0x25228000;
    private static final int INCDECP_SCALAR_MASK = 0xFF3EFE00;
    private static final int INCDECP_SCALAR_VALUE = 0x252C8800;
    private static final int INCDECP_VECTOR_MASK = 0xFF3EFE00;
    private static final int INCDECP_VECTOR_VALUE = 0x252C8000;
    private static final int SINCDECP_SCALAR_32_MASK = 0xFF3CFE00;
    private static final int SINCDECP_SCALAR_32_VALUE = 0x25288800;
    private static final int SINCDECP_SCALAR_64_MASK = 0xFF3CFE00;
    private static final int SINCDECP_SCALAR_64_VALUE = 0x25288C00;
    private static final int SINCDECP_VECTOR_MASK = 0xFF3CFE00;
    private static final int SINCDECP_VECTOR_VALUE = 0x25288000;
    private static final int CNT_R_MASK = 0xFF30FC00;
    private static final int CNT_R_VALUE = 0x0420E000;
    private static final int INCDEC_R_MASK = 0xFF30F800;
    private static final int INCDEC_R_VALUE = 0x0430E000;
    private static final int SINCDEC_R_32_MASK = 0xFF30F000;
    private static final int SINCDEC_R_32_VALUE = 0x0420F000;
    private static final int SINCDEC_R_64_VALUE = 0x0430F000;
    private static final int INCDEC_V_MASK = 0xFF30F800;
    private static final int INCDEC_V_VALUE = 0x0430C000;
    private static final int SINCDEC_V_MASK = 0xFF30F000;
    private static final int SINCDEC_V_VALUE = 0x0420C000;

    private final Aarch64Architecture architecture;

    Aarch64SveDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra da classe SVE. Devolve `null` quando a palavra não é (ainda) uma
    /// instrução SVE reconhecida, ou quando a arquitetura não declara `FEAT_SVE` — o chamador a
    /// recusa como qualquer encoding desconhecido (G8).
    Ir64Op decode(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE)) {
            return null;
        }
        return switch ((word >>> PREFIX_SHIFT) & PREFIX_MASK) {
            case PREFIX_PREDICATE -> decodePredicateGroup(word, address);
            case PREFIX_ELEMENT_COUNT -> {
                Ir64Op integer = decodeIntegerUnpredicated(word, address);
                if (integer == null) {
                    integer = decodeIntegerPredicated(word, address);
                }
                yield integer != null ? integer : decodeElementCount(word, address);
            }
            default -> null;
        };
    }

    private Ir64Op decodePredicateGroup(int word, long address) {
        if ((word & LOGICAL_MASK) == LOGICAL_VALUE) {
            return decodeLogical(word, address);
        }
        if ((word & BRKP_MASK) == BRKP_VALUE) {
            return decodeBrkp(word, address);
        }
        if ((word & PTEST_MASK) == PTEST_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.PTEST, 0, 0, field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK),
                    field(word, PN_SHIFT, PREDICATE_FIELD_MASK), true, 0, address);
        }
        if ((word & PTRUE_MASK) == PTRUE_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.PTRUE, field(word, ESZ_SHIFT, ESZ_MASK),
                    field(word, PD_SHIFT, PREDICATE_FIELD_MASK), 0, 0, bit(word, PTRUE_SET_FLAGS_BIT),
                    field(word, PTRUE_PATTERN_SHIFT, PATTERN_MASK), address);
        }
        if (word == SETFFR_WORD) {
            return misc(Ir64Op.SvePredicateMisc.Op.SETFFR, 0, 0, 0, 0, false, 0, address);
        }
        if ((word & PFALSE_MASK) == PFALSE_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.PFALSE, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK), 0, 0,
                    false, 0, address);
        }
        if ((word & RDFFR_MASK) == RDFFR_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.RDFFR, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK), 0, 0,
                    false, 0, address);
        }
        if ((word & RDFFR_PREDICATED_MASK) == RDFFR_PREDICATED_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.RDFFR_PREDICATED, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                    field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0, bit(word, BIT_SET_FLAGS), 0, address);
        }
        if ((word & WRFFR_MASK) == WRFFR_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.WRFFR, 0, 0, 0, field(word, PN_SHIFT, PREDICATE_FIELD_MASK), false,
                    0, address);
        }
        if ((word & PFIRST_MASK) == PFIRST_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.PFIRST, 0, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                    field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0, true, 0, address);
        }
        if ((word & PNEXT_MASK) == PNEXT_VALUE) {
            return misc(Ir64Op.SvePredicateMisc.Op.PNEXT, field(word, ESZ_SHIFT, ESZ_MASK),
                    field(word, PD_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0, true,
                    0, address);
        }
        if ((word & BRKAB_MASK) == BRKAB_VALUE) {
            return decodeBrkAb(word, address);
        }
        if ((word & BRKN_MASK) == BRKN_VALUE) {
            int pd = field(word, PD_SHIFT, PREDICATE_FIELD_MASK);
            return new Ir64Op.SvePartitionBreak(Ir64Op.SvePartitionBreak.Op.BRKN, pd,
                    field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK),
                    pd, bit(word, BIT_SET_FLAGS), false, address);
        }
        return decodePredicateCount(word, address);
    }

    private Ir64Op decodeLogical(int word, long address) {
        boolean secondGroup = bit(word, BIT_OPERATION_SELECT);
        boolean setFlags = bit(word, BIT_SET_FLAGS);
        int selector = (bit(word, BIT_O2) ? 2 : 0) | (bit(word, BIT_O3) ? 1 : 0);
        Ir64Op.SvePredicateLogical.Op op = switch (selector) {
            case 0 -> secondGroup ? Ir64Op.SvePredicateLogical.Op.ORR : Ir64Op.SvePredicateLogical.Op.AND;
            case 1 -> secondGroup ? Ir64Op.SvePredicateLogical.Op.ORN : Ir64Op.SvePredicateLogical.Op.BIC;
            case 2 -> secondGroup ? Ir64Op.SvePredicateLogical.Op.NOR : Ir64Op.SvePredicateLogical.Op.EOR;
            default -> secondGroup ? Ir64Op.SvePredicateLogical.Op.NAND : Ir64Op.SvePredicateLogical.Op.SEL;
        };
        if (op == Ir64Op.SvePredicateLogical.Op.SEL && setFlags) {
            return null; // SEL não tem forma que seta flags: não alocado
        }
        return new Ir64Op.SvePredicateLogical(op, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PM_SHIFT, PREDICATE_FIELD_MASK), setFlags, address);
    }

    private Ir64Op decodeBrkp(int word, long address) {
        if (bit(word, BIT_OPERATION_SELECT)) {
            return null; // `BRKPA`/`BRKPB` só existem com bit 23 = 0
        }
        Ir64Op.SvePartitionBreak.Op op = bit(word, BIT_O3)
                ? Ir64Op.SvePartitionBreak.Op.BRKPB
                : Ir64Op.SvePartitionBreak.Op.BRKPA;
        return new Ir64Op.SvePartitionBreak(op, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PM_SHIFT, PREDICATE_FIELD_MASK), bit(word, BIT_SET_FLAGS), false, address);
    }

    private Ir64Op decodeBrkAb(int word, long address) {
        boolean merging = bit(word, BIT_O3);
        boolean setFlags = bit(word, BIT_SET_FLAGS);
        if (merging && setFlags) {
            return null; // as formas /M não têm sufixo S (`@pd_pg_pn_s0`: s = 0 forçado)
        }
        Ir64Op.SvePartitionBreak.Op op = bit(word, BIT_OPERATION_SELECT)
                ? Ir64Op.SvePartitionBreak.Op.BRKB
                : Ir64Op.SvePartitionBreak.Op.BRKA;
        return new Ir64Op.SvePartitionBreak(op, field(word, PD_SHIFT, PREDICATE_FIELD_MASK),
                field(word, PG_LOGICAL_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK), 0,
                setFlags, merging, address);
    }

    private Ir64Op decodePredicateCount(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if ((word & CNTP_MASK) == CNTP_VALUE) {
            return count(Ir64Op.SvePredicateCount.Op.CNTP, esz, word, address);
        }
        if ((word & CNTP_MASK) == FIRSTP_VALUE) {
            return architecture.has(Aarch64Feature.SVE2_2)
                    ? count(Ir64Op.SvePredicateCount.Op.FIRSTP, esz, word, address)
                    : null;
        }
        if ((word & CNTP_MASK) == LASTP_VALUE) {
            return architecture.has(Aarch64Feature.SVE2_2)
                    ? count(Ir64Op.SvePredicateCount.Op.LASTP, esz, word, address)
                    : null;
        }
        int pg = field(word, INCDECP_PG_SHIFT, PREDICATE_FIELD_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        if ((word & INCDECP_SCALAR_MASK) == INCDECP_SCALAR_VALUE) {
            return incdecp(Ir64Op.SvePredicateCount.Op.INCDECP_SCALAR, esz, rd, pg, bit(word, INCDECP_D_BIT), false,
                    address);
        }
        if ((word & INCDECP_VECTOR_MASK) == INCDECP_VECTOR_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : incdecp(Ir64Op.SvePredicateCount.Op.INCDECP_VECTOR, esz, rd, pg, bit(word, INCDECP_D_BIT), false,
                            address);
        }
        boolean decrement = bit(word, SINCDECP_D_BIT);
        boolean unsigned = bit(word, SINCDECP_U_BIT);
        if ((word & SINCDECP_SCALAR_32_MASK) == SINCDECP_SCALAR_32_VALUE) {
            return incdecp(Ir64Op.SvePredicateCount.Op.SINCDECP_SCALAR_32, esz, rd, pg, decrement, unsigned, address);
        }
        if ((word & SINCDECP_SCALAR_64_MASK) == SINCDECP_SCALAR_64_VALUE) {
            return incdecp(Ir64Op.SvePredicateCount.Op.SINCDECP_SCALAR_64, esz, rd, pg, decrement, unsigned, address);
        }
        if ((word & SINCDECP_VECTOR_MASK) == SINCDECP_VECTOR_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : incdecp(Ir64Op.SvePredicateCount.Op.SINCDECP_VECTOR, esz, rd, pg, decrement, unsigned, address);
        }
        return null;
    }

    private Ir64Op decodeElementCount(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        int pattern = field(word, PATTERN_SHIFT, PATTERN_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int multiplier = field(word, ELEMENT_COUNT_IMM4_SHIFT, ELEMENT_COUNT_IMM4_MASK) + 1;
        if ((word & CNT_R_MASK) == CNT_R_VALUE) {
            return elementCount(Ir64Op.SveElementCount.Op.CNT, esz, rd, pattern, multiplier, false, true, address);
        }
        if ((word & INCDEC_R_MASK) == INCDEC_R_VALUE) {
            return elementCount(Ir64Op.SveElementCount.Op.INCDEC_SCALAR, esz, rd, pattern, multiplier,
                    bit(word, ELEMENT_COUNT_D_BIT_PLAIN_FORMS), true, address);
        }
        boolean decrement = bit(word, ELEMENT_COUNT_D_BIT_UNSIGNED_FORMS);
        boolean unsigned = bit(word, ELEMENT_COUNT_U_BIT_UNSIGNED_FORMS);
        if ((word & SINCDEC_R_32_MASK) == SINCDEC_R_32_VALUE) {
            return elementCount(Ir64Op.SveElementCount.Op.SINCDEC_SCALAR_32, esz, rd, pattern, multiplier, decrement,
                    unsigned, address);
        }
        if ((word & SINCDEC_R_32_MASK) == SINCDEC_R_64_VALUE) {
            return elementCount(Ir64Op.SveElementCount.Op.SINCDEC_SCALAR_64, esz, rd, pattern, multiplier, decrement,
                    unsigned, address);
        }
        if ((word & INCDEC_V_MASK) == INCDEC_V_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : elementCount(Ir64Op.SveElementCount.Op.INCDEC_VECTOR, esz, rd, pattern, multiplier,
                            bit(word, ELEMENT_COUNT_D_BIT_PLAIN_FORMS), true, address);
        }
        if ((word & SINCDEC_V_MASK) == SINCDEC_V_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : elementCount(Ir64Op.SveElementCount.Op.SINCDEC_VECTOR, esz, rd, pattern, multiplier, decrement,
                            unsigned, address);
        }
        return null;
    }

    /// Grupo inteiro sem predicado da B17.5 (34 encodings). Devolve `null` para o que não reconhece
    /// (o chamador então tenta a contagem de elementos e, por fim, recusa — G8).
    private Ir64Op decodeIntegerUnpredicated(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int rn = field(word, PN_SHIFT, REGISTER_FIELD_MASK);
        int rm = field(word, RM_SHIFT, REGISTER_FIELD_MASK);
        if ((word & MULTIPLY_ADD_MASK) == MULTIPLY_ADD_BASE) {
            return decodeMultiplyAdd(word, address, esz, rd, rn, rm);
        }
        if ((word & MOVPRFX_MASK) == MOVPRFX_VALUE) {
            return integer(Ir64Op.SveIntegerUnpredicated.Op.MOVPRFX, 0, rd, rn, 0, 0, 0, 0, 0, address);
        }
        if ((word & FEXPA_MASK) == FEXPA_VALUE) {
            return esz == RESERVED_ESZ_BYTE
                    ? null
                    : integer(Ir64Op.SveIntegerUnpredicated.Op.FEXPA, esz, rd, rn, 0, 0, 0, 0, 0, address);
        }
        // Daqui em diante bit 21 = 1: com bit 21 = 0 a palavra já foi consumida (ou recusada) pelo teste do MLA acima.
        int opcode = field(word, OPCODE_SHIFT, OPCODE_FIELD_MASK);
        Ir64Op.SveIntegerUnpredicated.Op arithmetic = switch (opcode) {
            case OP_ADD -> Ir64Op.SveIntegerUnpredicated.Op.ADD;
            case OP_SUB -> Ir64Op.SveIntegerUnpredicated.Op.SUB;
            case OP_SQADD -> Ir64Op.SveIntegerUnpredicated.Op.SQADD;
            case OP_UQADD -> Ir64Op.SveIntegerUnpredicated.Op.UQADD;
            case OP_SQSUB -> Ir64Op.SveIntegerUnpredicated.Op.SQSUB;
            case OP_UQSUB -> Ir64Op.SveIntegerUnpredicated.Op.UQSUB;
            default -> null;
        };
        if (arithmetic != null) {
            return integer(arithmetic, esz, rd, rn, rm, 0, 0, 0, 0, address);
        }
        return switch (opcode) {
            case OP_LOGICAL -> integer(switch (esz) {
                case 0 -> Ir64Op.SveIntegerUnpredicated.Op.AND;
                case 1 -> Ir64Op.SveIntegerUnpredicated.Op.ORR;
                case 2 -> Ir64Op.SveIntegerUnpredicated.Op.EOR;
                default -> Ir64Op.SveIntegerUnpredicated.Op.BIC;
            }, 0, rd, rn, rm, 0, 0, 0, 0, address);
            case OP_XAR -> decodeXar(word, address, rd);
            case OP_TERNARY_EOR3_BCAX -> decodeTernary(esz, rd, rm, word, address, false);
            case OP_TERNARY_BSL -> decodeTernary(esz, rd, rm, word, address, true);
            case OP_SHIFT_IMM_ASR ->
                    decodeShiftImmediate(Ir64Op.SveIntegerUnpredicated.Op.ASR_IMM, word, address, false);
            case OP_SHIFT_IMM_LSR ->
                    decodeShiftImmediate(Ir64Op.SveIntegerUnpredicated.Op.LSR_IMM, word, address, false);
            case OP_SHIFT_IMM_LSL ->
                    decodeShiftImmediate(Ir64Op.SveIntegerUnpredicated.Op.LSL_IMM, word, address, true);
            case OP_SHIFT_WIDE_ASR -> wideShift(Ir64Op.SveIntegerUnpredicated.Op.ASR_WIDE, esz, rd, rn, rm, address);
            case OP_SHIFT_WIDE_LSR -> wideShift(Ir64Op.SveIntegerUnpredicated.Op.LSR_WIDE, esz, rd, rn, rm, address);
            case OP_SHIFT_WIDE_LSL -> wideShift(Ir64Op.SveIntegerUnpredicated.Op.LSL_WIDE, esz, rd, rn, rm, address);
            case OP_INDEX_II -> integer(Ir64Op.SveIntegerUnpredicated.Op.INDEX_II, esz, rd, 0, 0, 0, 0,
                    signedImmediate(word, INDEX_IMM_LOW_SHIFT), signedImmediate(word, INDEX_IMM_HIGH_SHIFT), address);
            case OP_INDEX_IR -> integer(Ir64Op.SveIntegerUnpredicated.Op.INDEX_IR, esz, rd, 0, rm, 0, 0,
                    signedImmediate(word, INDEX_IMM_LOW_SHIFT), 0, address);
            case OP_INDEX_RI -> integer(Ir64Op.SveIntegerUnpredicated.Op.INDEX_RI, esz, rd, rn, 0, 0, 0,
                    signedImmediate(word, INDEX_IMM_HIGH_SHIFT), 0, address);
            case OP_INDEX_RR ->
                    integer(Ir64Op.SveIntegerUnpredicated.Op.INDEX_RR, esz, rd, rn, rm, 0, 0, 0, 0, address);
            case OP_FTSSEL -> esz == RESERVED_ESZ_BYTE
                    ? null
                    : integer(Ir64Op.SveIntegerUnpredicated.Op.FTSSEL, esz, rd, rn, rm, 0, 0, 0, 0, address);
            default -> null;
        };
    }

    /// Grupo inteiro predicado da B17.6 (68 encodings): aritmética binária (`bits[15:13] = 000`), shifts
    /// (`100`) e unárias (`101`) e, desde a B17.7, as reduções (`001`), todos com `bit 21 = 0`. Devolve `null` para o que não reconhece — e para
    /// os buracos que o `decodetree` deixa passar mas o tradutor do QEMU recusa (G8).
    private Ir64Op decodeIntegerPredicated(int word, long address) {
        if (bit(word, BIT_PREDICATED_EXCLUDED)) {
            return null;
        }
        int opcode = field(word, PREDICATED_OPCODE_SHIFT, PREDICATED_OPCODE_MASK);
        return switch (field(word, PREDICATED_GROUP_SHIFT, PREDICATED_GROUP_MASK)) {
            case PREDICATED_GROUP_BINARY -> decodePredicatedBinary(word, address, opcode);
            case PREDICATED_GROUP_SHIFT_OPS -> decodePredicatedShift(word, address, opcode);
            case PREDICATED_GROUP_REDUCTION -> decodeReduction(word, address, opcode);
            // `bits[15:13]` = `010`/`011`/`110`/`111` (MLA/MLS/MAD/MSB) já foram consumidos por
            // `decodeIntegerUnpredicated` (as 4 estão alocadas com `bit 21 = 0`), então só `101` chega aqui.
            default -> decodePredicatedUnary(word, address, opcode);
        };
    }

    /// Grupo de redução (`bits[15:13] = 001`, 19 encodings): 9 reduções escalares, 8 por segmento (`FEAT_SVE2p1`) e
    /// os 2 `MOVPRFX` predicados — que escrevem `Zd`, não `Vd`, e por isso viram {@link Ir64Op.SveIntegerPredicated}.
    private Ir64Op decodeReduction(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int rn = field(word, RN_PREDICATED_SHIFT, REGISTER_FIELD_MASK);
        int pg = field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK);
        if (opcode == RED_MOVPRFX_Z || opcode == RED_MOVPRFX_M) {
            return new Ir64Op.SveIntegerPredicated(Ir64Op.SveIntegerPredicated.Op.MOVPRFX, esz, rd, rn, 0, pg, 0,
                    opcode == RED_MOVPRFX_Z, address);
        }
        Ir64Op.SveIntegerReduction.Op op = switch (opcode) {
            case RED_ORV -> Ir64Op.SveIntegerReduction.Op.ORV;
            case RED_EORV -> Ir64Op.SveIntegerReduction.Op.EORV;
            case RED_ANDV -> Ir64Op.SveIntegerReduction.Op.ANDV;
            case RED_SADDV -> Ir64Op.SveIntegerReduction.Op.SADDV;
            case RED_UADDV -> Ir64Op.SveIntegerReduction.Op.UADDV;
            case RED_SMAXV -> Ir64Op.SveIntegerReduction.Op.SMAXV;
            case RED_UMAXV -> Ir64Op.SveIntegerReduction.Op.UMAXV;
            case RED_SMINV -> Ir64Op.SveIntegerReduction.Op.SMINV;
            case RED_UMINV -> Ir64Op.SveIntegerReduction.Op.UMINV;
            case RED_ORQV -> Ir64Op.SveIntegerReduction.Op.ORQV;
            case RED_EORQV -> Ir64Op.SveIntegerReduction.Op.EORQV;
            case RED_ANDQV -> Ir64Op.SveIntegerReduction.Op.ANDQV;
            case RED_ADDQV -> Ir64Op.SveIntegerReduction.Op.ADDQV;
            case RED_SMAXQV -> Ir64Op.SveIntegerReduction.Op.SMAXQV;
            case RED_UMAXQV -> Ir64Op.SveIntegerReduction.Op.UMAXQV;
            case RED_SMINQV -> Ir64Op.SveIntegerReduction.Op.SMINQV;
            case RED_UMINQV -> Ir64Op.SveIntegerReduction.Op.UMINQV;
            default -> null;
        };
        if (op == null || op == Ir64Op.SveIntegerReduction.Op.SADDV && esz == ESZ_DOUBLEWORD) {
            return null; // SADDV exige esz != 3 (não há como alargar a soma com sinal além de 64 bits)
        }
        if (isSegmentReduction(op) && !architecture.has(Aarch64Feature.SVE2_1)
                && !architecture.has(Aarch64Feature.SVE2_2)) {
            return null; // `aa64_sme2p1_or_sve2p1`; SVE2p2 implica SVE2p1
        }
        return new Ir64Op.SveIntegerReduction(op, esz, rd, rn, pg, address);
    }

    private static boolean isSegmentReduction(Ir64Op.SveIntegerReduction.Op op) {
        return switch (op) {
            case ORQV, EORQV, ANDQV, ADDQV, SMAXQV, UMAXQV, SMINQV, UMINQV -> true;
            default -> false;
        };
    }

    /// Binárias `_zpzz`. As formas reversas (`SUBR`/`SDIVR`/`UDIVR`) usam o mesmo `Op` com `rn`/`rm`
    /// trocados (`@rdm_pg_rn`): o campo 9:5 é o PRIMEIRO operando e `Zdn` o segundo.
    private Ir64Op decodePredicatedBinary(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        boolean reverse = opcode == PRED_SUBR || opcode == PRED_SDIVR || opcode == PRED_UDIVR;
        Ir64Op.SveIntegerPredicated.Op op = switch (opcode) {
            case PRED_ORR -> Ir64Op.SveIntegerPredicated.Op.ORR;
            case PRED_EOR -> Ir64Op.SveIntegerPredicated.Op.EOR;
            case PRED_AND -> Ir64Op.SveIntegerPredicated.Op.AND;
            case PRED_BIC -> Ir64Op.SveIntegerPredicated.Op.BIC;
            case PRED_ADD -> Ir64Op.SveIntegerPredicated.Op.ADD;
            case PRED_SUB, PRED_SUBR -> Ir64Op.SveIntegerPredicated.Op.SUB;
            case PRED_SMAX -> Ir64Op.SveIntegerPredicated.Op.SMAX;
            case PRED_UMAX -> Ir64Op.SveIntegerPredicated.Op.UMAX;
            case PRED_SMIN -> Ir64Op.SveIntegerPredicated.Op.SMIN;
            case PRED_UMIN -> Ir64Op.SveIntegerPredicated.Op.UMIN;
            case PRED_SABD -> Ir64Op.SveIntegerPredicated.Op.SABD;
            case PRED_UABD -> Ir64Op.SveIntegerPredicated.Op.UABD;
            case PRED_MUL -> Ir64Op.SveIntegerPredicated.Op.MUL;
            case PRED_SMULH -> Ir64Op.SveIntegerPredicated.Op.SMULH;
            case PRED_UMULH -> Ir64Op.SveIntegerPredicated.Op.UMULH;
            case PRED_SDIV, PRED_SDIVR -> Ir64Op.SveIntegerPredicated.Op.SDIV;
            case PRED_UDIV, PRED_UDIVR -> Ir64Op.SveIntegerPredicated.Op.UDIV;
            default -> null;
        };
        boolean divide = op == Ir64Op.SveIntegerPredicated.Op.SDIV || op == Ir64Op.SveIntegerPredicated.Op.UDIV;
        if (op == null || (divide && esz < ESZ_WORD)) {
            return null; // divisão exige esz >= 2; abaixo disso é não alocado
        }
        return predicated(op, esz, word, address, reverse, 0, false);
    }

    private Ir64Op decodePredicatedShift(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        Ir64Op.SveIntegerPredicated.Op op = switch (opcode) {
            case PRED_SHIFT_ASR_IMM -> Ir64Op.SveIntegerPredicated.Op.ASR_IMM;
            case PRED_SHIFT_LSR_IMM -> Ir64Op.SveIntegerPredicated.Op.LSR_IMM;
            case PRED_SHIFT_LSL_IMM -> Ir64Op.SveIntegerPredicated.Op.LSL_IMM;
            case PRED_SHIFT_ASRD -> Ir64Op.SveIntegerPredicated.Op.ASRD;
            case PRED_SHIFT_SQSHL_IMM -> Ir64Op.SveIntegerPredicated.Op.SQSHL_IMM;
            case PRED_SHIFT_UQSHL_IMM -> Ir64Op.SveIntegerPredicated.Op.UQSHL_IMM;
            case PRED_SHIFT_SRSHR -> Ir64Op.SveIntegerPredicated.Op.SRSHR;
            case PRED_SHIFT_URSHR -> Ir64Op.SveIntegerPredicated.Op.URSHR;
            case PRED_SHIFT_SQSHLU -> Ir64Op.SveIntegerPredicated.Op.SQSHLU;
            case PRED_SHIFT_ASR, PRED_SHIFT_ASRR -> Ir64Op.SveIntegerPredicated.Op.ASR;
            case PRED_SHIFT_LSR, PRED_SHIFT_LSRR -> Ir64Op.SveIntegerPredicated.Op.LSR;
            case PRED_SHIFT_LSL, PRED_SHIFT_LSLR -> Ir64Op.SveIntegerPredicated.Op.LSL;
            case PRED_SHIFT_ASR_WIDE -> Ir64Op.SveIntegerPredicated.Op.ASR_WIDE;
            case PRED_SHIFT_LSR_WIDE -> Ir64Op.SveIntegerPredicated.Op.LSR_WIDE;
            case PRED_SHIFT_LSL_WIDE -> Ir64Op.SveIntegerPredicated.Op.LSL_WIDE;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        switch (op) {
            case ASR_IMM, LSR_IMM, LSL_IMM, ASRD, SRSHR, URSHR, SQSHL_IMM, UQSHL_IMM, SQSHLU -> {
                boolean sve2Only = op == Ir64Op.SveIntegerPredicated.Op.SQSHL_IMM
                        || op == Ir64Op.SveIntegerPredicated.Op.UQSHL_IMM
                        || op == Ir64Op.SveIntegerPredicated.Op.SRSHR
                        || op == Ir64Op.SveIntegerPredicated.Op.URSHR
                        || op == Ir64Op.SveIntegerPredicated.Op.SQSHLU;
                if (sve2Only && !architecture.has(Aarch64Feature.SVE2)) {
                    return null;
                }
                int tszimm = tszimm(word, TSZ_PREDICATED_LOW_SHIFT);
                int immEsz = tszimmEsz(tszimm);
                if (immEsz < 0) {
                    return null; // tsz = 0: não alocado
                }
                boolean left = op == Ir64Op.SveIntegerPredicated.Op.LSL_IMM
                        || op == Ir64Op.SveIntegerPredicated.Op.SQSHL_IMM
                        || op == Ir64Op.SveIntegerPredicated.Op.UQSHL_IMM
                        || op == Ir64Op.SveIntegerPredicated.Op.SQSHLU;
                long amount = left ? tszimm - (ESIZE_BITS_BASE << immEsz) : (ESIZE_SHR_BASE << immEsz) - tszimm;
                return predicatedImmediate(op, immEsz, word, address, amount);
            }
            case ASR_WIDE, LSR_WIDE, LSL_WIDE -> {
                return esz == ESZ_DOUBLEWORD ? null : predicated(op, esz, word, address, false, 0, false);
            }
            default -> {
                boolean reverse = opcode == PRED_SHIFT_ASRR || opcode == PRED_SHIFT_LSRR || opcode == PRED_SHIFT_LSLR;
                return predicated(op, esz, word, address, reverse, 0, false);
            }
        }
    }

    /// Unárias predicadas: `bit 20` = 1 é a forma merging (`_m`), 0 a forma zeroing (`_z`, `FEAT_SVE2p2`);
    /// `bit 19` escolhe entre as bit-ops (`CLS`…`NOT`) e as inteiras (`SXTB`…`NEG`).
    private Ir64Op decodePredicatedUnary(int word, long address, int opcode) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        boolean zeroing = ((opcode >>> UNARY_MERGING_BIT) & 1) == 0;
        if (zeroing && !architecture.has(Aarch64Feature.SVE2_2)) {
            return null;
        }
        int selector = opcode & UNARY_SELECTOR_MASK;
        boolean bitOperations = ((opcode >>> UNARY_BIT_OPERATIONS) & 1) != 0;
        Ir64Op.SveIntegerPredicated.Op op = bitOperations ? switch (selector) {
            case 0 -> Ir64Op.SveIntegerPredicated.Op.CLS;
            case 1 -> Ir64Op.SveIntegerPredicated.Op.CLZ;
            case 2 -> Ir64Op.SveIntegerPredicated.Op.CNT;
            case 3 -> Ir64Op.SveIntegerPredicated.Op.CNOT;
            case 4 -> Ir64Op.SveIntegerPredicated.Op.FABS;
            case 5 -> Ir64Op.SveIntegerPredicated.Op.FNEG;
            case 6 -> Ir64Op.SveIntegerPredicated.Op.NOT;
            default -> null;
        } : switch (selector) {
            case 0 -> Ir64Op.SveIntegerPredicated.Op.SXTB;
            case 1 -> Ir64Op.SveIntegerPredicated.Op.UXTB;
            case 2 -> Ir64Op.SveIntegerPredicated.Op.SXTH;
            case 3 -> Ir64Op.SveIntegerPredicated.Op.UXTH;
            case 4 -> Ir64Op.SveIntegerPredicated.Op.SXTW;
            case 5 -> Ir64Op.SveIntegerPredicated.Op.UXTW;
            case 6 -> Ir64Op.SveIntegerPredicated.Op.ABS;
            default -> Ir64Op.SveIntegerPredicated.Op.NEG;
        };
        if (op == null || esz < minimumUnaryEsz(op) || (op == Ir64Op.SveIntegerPredicated.Op.SXTW
                || op == Ir64Op.SveIntegerPredicated.Op.UXTW) && esz != ESZ_DOUBLEWORD) {
            return null;
        }
        return new Ir64Op.SveIntegerPredicated(op, esz, field(word, PD_SHIFT, REGISTER_FIELD_MASK),
                field(word, RN_PREDICATED_SHIFT, REGISTER_FIELD_MASK), 0,
                field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK), 0, zeroing, address);
    }

    /// Menor `esz` permitido de cada unária: as extensões precisam de um elemento maior que a origem
    /// (`SXTB`/`UXTB` ≥ half, `SXTH`/`UXTH` ≥ word) e `FABS`/`FNEG` não existem em bytes.
    private static int minimumUnaryEsz(Ir64Op.SveIntegerPredicated.Op op) {
        return switch (op) {
            case SXTB, UXTB, FABS, FNEG -> ESZ_HALFWORD;
            case SXTH, UXTH -> ESZ_WORD;
            default -> 0;
        };
    }

    private static Ir64Op predicated(Ir64Op.SveIntegerPredicated.Op op, int esz, int word, long address,
            boolean reverse, long imm, boolean zeroing) {
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        int other = field(word, RN_PREDICATED_SHIFT, REGISTER_FIELD_MASK);
        return new Ir64Op.SveIntegerPredicated(op, esz, rd, reverse ? other : rd, reverse ? rd : other,
                field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK), imm, zeroing, address);
    }

    private static Ir64Op predicatedImmediate(Ir64Op.SveIntegerPredicated.Op op, int esz, int word, long address,
            long amount) {
        int rd = field(word, PD_SHIFT, REGISTER_FIELD_MASK);
        return new Ir64Op.SveIntegerPredicated(op, esz, rd, rd, 0, field(word, PG_PREDICATED_SHIFT, PG_PREDICATED_MASK),
                amount, false, address);
    }

    /// `MLA`/`MLS` (escrevem o acumulador `Zda`) e `MAD`/`MSB` (escrevem o multiplicando `Zdn`), sempre
    /// com predicado: o grupo se chama "não predicado" pela partição do épico, mas estas 4 linhas têm `pg`.
    private Ir64Op decodeMultiplyAdd(int word, long address, int esz, int rd, int rn, int rm) {
        int pg = field(word, PG_MULTIPLY_SHIFT, PG_MULTIPLY_MASK);
        return switch (field(word, MULTIPLY_ADD_OPCODE_SHIFT, MULTIPLY_ADD_OPCODE_MASK)) {
            case MULTIPLY_ADD_MLA ->
                    integer(Ir64Op.SveIntegerUnpredicated.Op.MLA, esz, rd, rn, rm, 0, pg, 0, 0, address);
            case MULTIPLY_ADD_MLS ->
                    integer(Ir64Op.SveIntegerUnpredicated.Op.MLS, esz, rd, rn, rm, 0, pg, 0, 0, address);
            case MULTIPLY_ADD_MAD ->
                    integer(Ir64Op.SveIntegerUnpredicated.Op.MAD, esz, rd, rd, rm, rn, pg, 0, 0, address);
            case MULTIPLY_ADD_MSB ->
                    integer(Ir64Op.SveIntegerUnpredicated.Op.MSB, esz, rd, rd, rm, rn, pg, 0, 0, address);
            default -> null;
        };
    }

    /// Lógica ternária SVE2 (`EOR3`/`BCAX`/`BSL`/`BSL1N`/`BSL2N`/`NBSL`): bits 23:22 escolhem a
    /// operação (não são `esz`), `rn = rd` (destrutiva), `Zk` em 9:5. Exige `FEAT_SVE2`.
    private Ir64Op decodeTernary(int selector, int rd, int rm, int word, long address, boolean bitSelect) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        Ir64Op.SveIntegerUnpredicated.Op op;
        if (bitSelect) {
            op = switch (selector) {
                case 0 -> Ir64Op.SveIntegerUnpredicated.Op.BSL;
                case 1 -> Ir64Op.SveIntegerUnpredicated.Op.BSL1N;
                case 2 -> Ir64Op.SveIntegerUnpredicated.Op.BSL2N;
                default -> Ir64Op.SveIntegerUnpredicated.Op.NBSL;
            };
        } else {
            op = switch (selector) {
                case 0 -> Ir64Op.SveIntegerUnpredicated.Op.EOR3;
                case 1 -> Ir64Op.SveIntegerUnpredicated.Op.BCAX;
                default -> null;
            };
        }
        return op == null
                ? null
                : integer(op, 0, rd, rd, rm, field(word, RA_SHIFT, REGISTER_FIELD_MASK), 0, 0, 0, address);
    }

    /// `XAR` (SVE2): `Zm` em 9:5, `Zdn` destrutivo, `esz` e a rotação vêm do `tszimm` (bits 23:22 e 20:16).
    private Ir64Op decodeXar(int word, long address, int rd) {
        int tszimm = tszimm(word, TSZ_LOW_SHIFT);
        int esz = tszimmEsz(tszimm);
        if (esz < 0 || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        return integer(Ir64Op.SveIntegerUnpredicated.Op.XAR, esz, rd, rd, field(word, RA_SHIFT, REGISTER_FIELD_MASK), 0,
                0, (ESIZE_SHR_BASE << esz) - tszimm, 0, address);
    }

    /// `ASR`/`LSR`/`LSL` por imediato: `esz` e a contagem derivam do MESMO campo `tszimm` (não há `size`).
    private static Ir64Op decodeShiftImmediate(Ir64Op.SveIntegerUnpredicated.Op op, int word, long address,
            boolean left) {
        int tszimm = tszimm(word, TSZ_LOW_SHIFT);
        int esz = tszimmEsz(tszimm);
        if (esz < 0) {
            return null;
        }
        long amount = left ? tszimm - (ESIZE_BITS_BASE << esz) : (ESIZE_SHR_BASE << esz) - tszimm;
        return integer(op, esz, field(word, PD_SHIFT, REGISTER_FIELD_MASK), field(word, PN_SHIFT, REGISTER_FIELD_MASK),
                0, 0, 0, amount, 0, address);
    }

    /// Shift por elemento largo (`_zzw`): `esz = 3` não existe (o `Zm` já é de doublewords).
    private static Ir64Op wideShift(Ir64Op.SveIntegerUnpredicated.Op op, int esz, int rd, int rn, int rm,
            long address) {
        return esz == ESZ_DOUBLEWORD ? null : integer(op, esz, rd, rn, rm, 0, 0, 0, 0, address);
    }

    /// `tszh:tszl:imm3` de 7 bits: bits 23:22 e o campo baixo de 5 bits em `lowShift` (`%tszimm16_*`: 20:16;
    /// `%tszimm_*` dos shifts predicados: 9:5).
    private static int tszimm(int word, int lowShift) {
        return (field(word, TSZ_HIGH_SHIFT, TSZ_HIGH_MASK) << TSZ_HIGH_FIELD_SHIFT)
                | field(word, lowShift, TSZ_LOW_MASK);
    }

    /// `tszimm_esz` do QEMU: posição do bit mais alto de `tsz` (`-1` quando `tsz = 0`, não alocado).
    private static int tszimmEsz(int tszimm) {
        int tsz = tszimm >>> TSZ_ESZ_SHIFT;
        return tsz == 0 ? -1 : Integer.SIZE - 1 - Integer.numberOfLeadingZeros(tsz);
    }

    private static long signedImmediate(int word, int shift) {
        int raw = field(word, shift, REGISTER_FIELD_MASK);
        return (raw << (Integer.SIZE - SIGNED_IMMEDIATE_BITS)) >> (Integer.SIZE - SIGNED_IMMEDIATE_BITS);
    }

    private static Ir64Op integer(Ir64Op.SveIntegerUnpredicated.Op op, int esz, int rd, int rn, int rm, int ra, int pg,
            long imm, long imm2, long address) {
        return new Ir64Op.SveIntegerUnpredicated(op, esz, rd, rn, rm, ra, pg, imm, imm2, address);
    }

    private static Ir64Op misc(Ir64Op.SvePredicateMisc.Op op, int esz, int pd, int pg, int pn, boolean setFlags,
            int pattern, long address) {
        // `PTEST`/`PFIRST`/`PNEXT` usam o argumento `pn` como o predicado lido e `pg` como o
        // governante; os chamadores acima já passaram cada campo na posição certa.
        return new Ir64Op.SvePredicateMisc(op, esz, pd, pg, pn, setFlags, pattern, address);
    }

    private static Ir64Op count(Ir64Op.SvePredicateCount.Op op, int esz, int word, long address) {
        return new Ir64Op.SvePredicateCount(op, esz, field(word, PD_SHIFT, REGISTER_FIELD_MASK),
                field(word, COUNT_PG_SHIFT, PREDICATE_FIELD_MASK), field(word, PN_SHIFT, PREDICATE_FIELD_MASK), false,
                false, address);
    }

    private static Ir64Op incdecp(Ir64Op.SvePredicateCount.Op op, int esz, int rd, int pg, boolean decrement,
            boolean unsigned, long address) {
        return new Ir64Op.SvePredicateCount(op, esz, rd, pg, pg, decrement, unsigned, address);
    }

    private static Ir64Op elementCount(Ir64Op.SveElementCount.Op op, int esz, int rd, int pattern, int multiplier,
            boolean decrement, boolean unsigned, long address) {
        return new Ir64Op.SveElementCount(op, esz, rd, pattern, multiplier, decrement, unsigned, address);
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }
}
