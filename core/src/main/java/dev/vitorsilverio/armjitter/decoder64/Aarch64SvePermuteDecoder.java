package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE da permutação não predicada (B17.10): os grupos `### SVE Permute - Extract`, `- Unpredicated` e
/// `- Interleaving` do `sve.decode` do QEMU — 42 encodings.
///
/// **Cada padrão é o `decodetree` transcrito em `máscara`/`valor`**, medido contra `aarch64-none-elf-as`. As três
/// granularidades de intercalação convivem e o gate de feature é POR LINHA:
///
/// - `ZIP1`/`UZP1`/`TRN1`… (`_z`): vetor INTEIRO, `FEAT_SVE`;
/// - `ZIP1_q`…`TRN2_q`: o elemento é um segmento de 128 bits, `FEAT_F64MM` (o rascunho da spec dizia SVE2 — o
///   `sve.decode` e o `translate-sve.c` do QEMU dizem `aa64_sve_f64mm`);
/// - `ZIPQ1`/`UZPQ1`/`TBLQ`/… : permutação DENTRO de cada segmento de 128 bits, `FEAT_SVE2p1`, e vivem no prefixo
///   `0x44` (as demais, no `0x05`).
///
/// `EXT_sve2`/`TBL_sve2`/`TBX` exigem `FEAT_SVE2`; `DUPQ`/`EXTQ`/`PMOV`/`TBXQ`, `FEAT_SVE2p1` (que `SVE2_2` implica).
/// Tudo o que não bate exatamente devolve `null` e o chamador recusa a instrução (G8).
final class Aarch64SvePermuteDecoder {
    private static final int RD_MASK = 0b11111;
    private static final int RN_SHIFT = 5;
    private static final int RN_MASK = 0b11111;
    private static final int RM_SHIFT = 16;
    private static final int RM_MASK = 0b11111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int OPCODE_SHIFT = 10;
    private static final int OPCODE_MASK = 0b111;

    // ── EXT / EXTQ ────────────────────────────────────────────────────────────────────────────────
    private static final int EXT_MASK = 0xFFE0E000;
    private static final int EXT_VALUE = 0x05200000;
    private static final int EXT_SVE2_VALUE = 0x05600000;
    private static final int EXT_IMM_HIGH_MASK = 0b11111;
    private static final int EXT_IMM_LOW_BITS = 3;
    private static final int EXTQ_MASK = 0xFFF0FC00;
    private static final int EXTQ_VALUE = 0x05602400;
    private static final int EXTQ_IMM_MASK = 0b1111;

    // ── DUP / DUPQ ────────────────────────────────────────────────────────────────────────────────
    private static final int DUP_S_VALUE = 0x05203800;
    private static final int DUP_X_MASK = 0xFF20FC00;
    private static final int DUP_X_VALUE = 0x05202000;
    private static final int DUPQ_MASK = 0xFFE0FC00;
    private static final int DUPQ_VALUE = 0x05202400;
    private static final int TSZ_MASK = 0b11111;
    private static final int TSZ_HIGH_SHIFT = 5;
    private static final int DUPQ_TSZ_ELEMENT_MASK = 0b1111;
    private static final int ESZ_QUADWORD = 4;

    // ── INSR / REV / TBL / UNPK ────────────────────────────────────────────────────────────────────
    private static final int SCALAR_MASK = 0xFF3FFC00;
    private static final int INSR_R_VALUE = 0x05243800;
    private static final int INSR_F_VALUE = 0x05343800;
    private static final int REV_VALUE = 0x05383800;
    private static final int TABLE_MASK = 0xFF20FC00;
    private static final int TBL_VALUE = 0x05203000;
    private static final int TBL_SVE2_VALUE = 0x05202800;
    private static final int TBX_VALUE = 0x05202C00;
    private static final int TBXQ_VALUE = 0x05203400;
    private static final int UNPK_MASK = 0xFF3CFC00;
    private static final int UNPK_VALUE = 0x05303800;
    private static final int UNPK_HIGH_BIT = 16;
    private static final int UNPK_UNSIGNED_BIT = 17;

    // ── PMOV ──────────────────────────────────────────────────────────────────────────────────────
    private static final int PMOV_PV_MASK = 0xFF39FC10;
    private static final int PMOV_PV_VALUE = 0x05283800;
    private static final int PMOV_VP_MASK = 0xFF39FE00;
    private static final int PMOV_VP_VALUE = 0x05293800;
    private static final int PMOV_PREDICATE_MASK = 0b1111;
    private static final int PMOV_LOW_SHIFT = 17;
    private static final int PMOV_LOW_MASK = 0b11;
    private static final int PMOV_HALF_BIT = 18;
    private static final int PMOV_HALF_INDEX_BIT = 17;
    private static final int PMOV_WORD_INDEX_BITS = 2;
    private static final int PMOV_BYTE_LOW_FIELD = 0b01;

    // ── Intercalação ──────────────────────────────────────────────────────────────────────────────
    private static final int INTERLEAVE_MASK = 0xFF20E000;
    private static final int INTERLEAVE_VALUE = 0x05206000;
    private static final int SEGMENT_MASK = 0xFFE0E000;
    private static final int SEGMENT_VALUE = 0x05A00000;
    private static final int PER_SEGMENT_MASK = 0xFF20E000;
    private static final int PER_SEGMENT_VALUE = 0x4400E000;
    private static final int ORDER_ZIP1 = 0b000;
    private static final int ORDER_ZIP2 = 0b001;
    private static final int ORDER_UZP1 = 0b010;
    private static final int ORDER_UZP2 = 0b011;
    private static final int ORDER_TRN1 = 0b100;
    private static final int ORDER_TRN2 = 0b101;
    private static final int SEGMENT_ORDER_TRN1 = 0b110;
    private static final int SEGMENT_ORDER_TRN2 = 0b111;
    private static final int PER_SEGMENT_TBLQ = 0b110;

    private final Aarch64Architecture architecture;

    Aarch64SvePermuteDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Prefixo `0x05`. Só é chamado DEPOIS de o decoder de imediato recusar a palavra.
    Ir64Op decodePrefix05(int word, long address) {
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        int rm = (word >>> RM_SHIFT) & RM_MASK;
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if ((word & EXT_MASK) == EXT_VALUE) {
            return permute(Ir64Op.SvePermute.Op.EXT, 0, rd, 0, rn, extImmediate(word), address);
        }
        if ((word & EXT_MASK) == EXT_SVE2_VALUE) {
            return architecture.has(Aarch64Feature.SVE2)
                    ? permute(Ir64Op.SvePermute.Op.EXT_SVE2, 0, rd, rn, 0, extImmediate(word), address)
                    : null;
        }
        if ((word & EXTQ_MASK) == EXTQ_VALUE) {
            return sve2p1() ? permute(Ir64Op.SvePermute.Op.EXTQ, 0, rd, 0, rn, (word >>> RM_SHIFT) & EXTQ_IMM_MASK,
                    address) : null;
        }
        if ((word & SCALAR_MASK) == DUP_S_VALUE) {
            return permute(Ir64Op.SvePermute.Op.DUP_S, esz, rd, rn, 0, 0, address);
        }
        if ((word & DUP_X_MASK) == DUP_X_VALUE) {
            return decodeDupIndexed(word, rd, rn, address);
        }
        if ((word & DUPQ_MASK) == DUPQ_VALUE) {
            return sve2p1() ? decodeDupq(word, rd, rn, address) : null;
        }
        if ((word & SCALAR_MASK) == INSR_R_VALUE) {
            return permute(Ir64Op.SvePermute.Op.INSR_R, esz, rd, 0, rn, 0, address);
        }
        if ((word & SCALAR_MASK) == INSR_F_VALUE) {
            return permute(Ir64Op.SvePermute.Op.INSR_F, esz, rd, 0, rn, 0, address);
        }
        if ((word & SCALAR_MASK) == REV_VALUE) {
            return permute(Ir64Op.SvePermute.Op.REV, esz, rd, rn, 0, 0, address);
        }
        if ((word & UNPK_MASK) == UNPK_VALUE) {
            return decodeUnpack(word, esz, rd, rn, address);
        }
        if ((word & PMOV_PV_MASK) == PMOV_PV_VALUE || (word & PMOV_VP_MASK) == PMOV_VP_VALUE) {
            return sve2p1() ? decodePmov(word, address) : null;
        }
        Ir64Op table = decodeTable(word, esz, rd, rn, rm, address);
        if (table != null) {
            return table;
        }
        if ((word & INTERLEAVE_MASK) == INTERLEAVE_VALUE) {
            return decodeInterleave(word, esz, rd, rn, rm, address);
        }
        if ((word & SEGMENT_MASK) == SEGMENT_VALUE) {
            return architecture.has(Aarch64Feature.F64MM) ? decodeSegmentInterleave(word, rd, rn, rm, address) : null;
        }
        return null;
    }

    /// Prefixo `0x44`: `ZIPQ*`/`UZPQ*`/`TBLQ` (`FEAT_SVE2p1`). Só é chamado quando o decoder de multiplicação
    /// recusou a palavra.
    Ir64Op decodePrefix44(int word, long address) {
        if ((word & PER_SEGMENT_MASK) != PER_SEGMENT_VALUE || !sve2p1()) {
            return null;
        }
        Ir64Op.SvePermute.Op op = switch ((word >>> OPCODE_SHIFT) & OPCODE_MASK) {
            case ORDER_ZIP1 -> Ir64Op.SvePermute.Op.ZIPQ1;
            case ORDER_ZIP2 -> Ir64Op.SvePermute.Op.ZIPQ2;
            case ORDER_UZP1 -> Ir64Op.SvePermute.Op.UZPQ1;
            case ORDER_UZP2 -> Ir64Op.SvePermute.Op.UZPQ2;
            case PER_SEGMENT_TBLQ -> Ir64Op.SvePermute.Op.TBLQ;
            default -> null;
        };
        return op == null ? null : permute(op, (word >>> ESZ_SHIFT) & ESZ_MASK, word & RD_MASK,
                (word >>> RN_SHIFT) & RN_MASK, (word >>> RM_SHIFT) & RM_MASK, 0, address);
    }

    private boolean sve2p1() {
        return architecture.has(Aarch64Feature.SVE2_1) || architecture.has(Aarch64Feature.SVE2_2);
    }

    /// `%imm8_16_10`: os 5 bits altos em `[20:16]` e os 3 baixos em `[12:10]`.
    private static int extImmediate(int word) {
        return (((word >>> RM_SHIFT) & EXT_IMM_HIGH_MASK) << EXT_IMM_LOW_BITS) | ((word >>> OPCODE_SHIFT) & OPCODE_MASK);
    }

    /// `DUP Zd.T, Zn.T[index]`: `imm7 = imm2:tsz`; `esz` é a posição do bit 1 mais baixo (`tsz = 0` é reservado) e
    /// `4` é o quadword.
    private Ir64Op decodeDupIndexed(int word, int rd, int rn, long address) {
        int tsz = (word >>> RM_SHIFT) & TSZ_MASK;
        if (tsz == 0) {
            return null;
        }
        int imm7 = (((word >>> ESZ_SHIFT) & ESZ_MASK) << TSZ_HIGH_SHIFT) | tsz;
        int esz = Integer.numberOfTrailingZeros(imm7);
        return permute(Ir64Op.SvePermute.Op.DUP_X, esz, rd, rn, 0, imm7 >>> (esz + 1), address);
    }

    /// `DUPQ`: mesma codificação `tsz`, mas o quadword (`tsz = 10000`) não existe.
    private Ir64Op decodeDupq(int word, int rd, int rn, long address) {
        int tsz = (word >>> RM_SHIFT) & TSZ_MASK;
        if ((tsz & DUPQ_TSZ_ELEMENT_MASK) == 0) {
            return null;
        }
        int esz = Integer.numberOfTrailingZeros(tsz);
        return permute(Ir64Op.SvePermute.Op.DUPQ, esz, rd, rn, 0, tsz >>> (esz + 1), address);
    }

    private Ir64Op decodeUnpack(int word, int esz, int rd, int rn, long address) {
        if (esz == 0) {
            return null;
        }
        boolean high = ((word >>> UNPK_HIGH_BIT) & 1) != 0;
        boolean unsigned = ((word >>> UNPK_UNSIGNED_BIT) & 1) != 0;
        Ir64Op.SvePermute.Op op = unsigned
                ? (high ? Ir64Op.SvePermute.Op.UUNPKHI : Ir64Op.SvePermute.Op.UUNPKLO)
                : (high ? Ir64Op.SvePermute.Op.SUNPKHI : Ir64Op.SvePermute.Op.SUNPKLO);
        return permute(op, esz, rd, rn, 0, 0, address);
    }

    /// `PMOV` move entre predicado e vetor. O `esz` e o índice saem de uma decodificação POSICIONAL (não há campo
    /// `esz`): `bits[23:22]` e `[18:17]` são lidos juntos, nesta ordem de precedência.
    private Ir64Op decodePmov(int word, long address) {
        boolean toPredicate = (word & PMOV_PV_MASK) == PMOV_PV_VALUE;
        int low = (word >>> PMOV_LOW_SHIFT) & PMOV_LOW_MASK;
        int highField = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int esz;
        int index;
        if ((highField & 0b10) != 0) {
            esz = 3;
            index = ((highField & 1) << PMOV_WORD_INDEX_BITS) | low;
        } else if (highField == 0b01) {
            esz = 2;
            index = low;
        } else if (((word >>> PMOV_HALF_BIT) & 1) != 0) {
            esz = 1;
            index = (word >>> PMOV_HALF_INDEX_BIT) & 1;
        } else if (low == PMOV_BYTE_LOW_FIELD) {
            esz = 0;
            index = 0;
        } else {
            return null;
        }
        return toPredicate
                ? permute(Ir64Op.SvePermute.Op.PMOV_PV, esz, word & PMOV_PREDICATE_MASK,
                        (word >>> RN_SHIFT) & RN_MASK, 0, index, address)
                : permute(Ir64Op.SvePermute.Op.PMOV_VP, esz, word & RD_MASK,
                        (word >>> RN_SHIFT) & PMOV_PREDICATE_MASK, 0, index, address);
    }

    /// `TBL`/`TBL_sve2`/`TBX`/`TBXQ`: mesmo formato `@rd_rn_rm`, só os bits `[15:10]` mudam.
    private Ir64Op decodeTable(int word, int esz, int rd, int rn, int rm, long address) {
        Ir64Op.SvePermute.Op op = switch (word & TABLE_MASK) {
            case TBL_VALUE -> Ir64Op.SvePermute.Op.TBL;
            case TBL_SVE2_VALUE -> architecture.has(Aarch64Feature.SVE2) ? Ir64Op.SvePermute.Op.TBL_SVE2 : null;
            case TBX_VALUE -> architecture.has(Aarch64Feature.SVE2) ? Ir64Op.SvePermute.Op.TBX : null;
            case TBXQ_VALUE -> sve2p1() ? Ir64Op.SvePermute.Op.TBXQ : null;
            default -> null;
        };
        return op == null ? null : permute(op, esz, rd, rn, rm, 0, address);
    }

    private Ir64Op decodeInterleave(int word, int esz, int rd, int rn, int rm, long address) {
        Ir64Op.SvePermute.Op op = switch ((word >>> OPCODE_SHIFT) & OPCODE_MASK) {
            case ORDER_ZIP1 -> Ir64Op.SvePermute.Op.ZIP1;
            case ORDER_ZIP2 -> Ir64Op.SvePermute.Op.ZIP2;
            case ORDER_UZP1 -> Ir64Op.SvePermute.Op.UZP1;
            case ORDER_UZP2 -> Ir64Op.SvePermute.Op.UZP2;
            case ORDER_TRN1 -> Ir64Op.SvePermute.Op.TRN1;
            case ORDER_TRN2 -> Ir64Op.SvePermute.Op.TRN2;
            default -> null;
        };
        return op == null ? null : permute(op, esz, rd, rn, rm, 0, address);
    }

    private Ir64Op decodeSegmentInterleave(int word, int rd, int rn, int rm, long address) {
        Ir64Op.SvePermute.Op op = switch ((word >>> OPCODE_SHIFT) & OPCODE_MASK) {
            case ORDER_ZIP1 -> Ir64Op.SvePermute.Op.ZIP1_Q;
            case ORDER_ZIP2 -> Ir64Op.SvePermute.Op.ZIP2_Q;
            case ORDER_UZP1 -> Ir64Op.SvePermute.Op.UZP1_Q;
            case ORDER_UZP2 -> Ir64Op.SvePermute.Op.UZP2_Q;
            case SEGMENT_ORDER_TRN1 -> Ir64Op.SvePermute.Op.TRN1_Q;
            case SEGMENT_ORDER_TRN2 -> Ir64Op.SvePermute.Op.TRN2_Q;
            default -> null;
        };
        return op == null ? null : permute(op, ESZ_QUADWORD, rd, rn, rm, 0, address);
    }

    private static Ir64Op permute(Ir64Op.SvePermute.Op op, int esz, int rd, int rn, int rm, int imm, long address) {
        return new Ir64Op.SvePermute(op, esz, rd, rn, rm, imm, address);
    }
}
