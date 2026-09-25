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
            case PREFIX_ELEMENT_COUNT -> decodeElementCount(word, address);
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
