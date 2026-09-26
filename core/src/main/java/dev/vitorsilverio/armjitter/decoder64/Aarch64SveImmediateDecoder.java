package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE das operações com imediato (B17.8): bitmask (`ORR`/`EOR`/`AND`/`DUPM`), cópia predicada
/// (`CPY`/`FCPY`), broadcast (`DUP`/`FDUP`), aritmética com imediato de 8 bits e `SMAX`/`UMAX`/`SMIN`/`UMIN`/`MUL`
/// — os grupos `### SVE Bitwise Immediate`, `### SVE Integer Wide Immediate - Predicated` e `- Unpredicated` do
/// `sve.decode`. Todos são `FEAT_SVE` (`aa64_sme_or_sve` no QEMU), então o gate fica no chamador.
///
/// O imediato sai **expandido** no `Ir64Op.SveImmediate` (bitmask de 64 bits via {@link Aarch64LogicalImmediate},
/// `sh8` aplicado, `VFPExpandImm` feito). Os **10 padrões `INVALID`** do inventário (byte com `sh = 1`: 2 nas formas
/// de cópia, 1 no `DUP` e 7 na aritmética — a spec da task contava 8) são recusados aqui (G8): o `INVALID` do
/// `decodetree` existe justamente para o padrão não cair na forma geral. `FCPY`/`FDUP` em byte e o bitmask
/// reservado também são recusados.
final class Aarch64SveImmediateDecoder {
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int RD_MASK = 0b11111;

    // ── Prefixo 0x05: bitmask e cópia predicada ─────────────────────────────────────────────────
    private static final int BITMASK_GROUP_SHIFT = 18;
    private static final int BITMASK_GROUP_MASK = 0b1111;
    private static final int DBM_SHIFT = 5;
    private static final int DBM_MASK = 0x1FFF;
    private static final int DBM_N_SHIFT = 12;
    private static final int DBM_IMMR_SHIFT = 6;
    private static final int DBM_FIELD_MASK = 0b111111;
    private static final int COPY_GROUP_SHIFT = 20;
    private static final int COPY_GROUP_MASK = 0b11;
    private static final int COPY_GROUP = 0b01;
    private static final int COPY_PG_SHIFT = 16;
    private static final int COPY_PG_MASK = 0b1111;
    private static final int COPY_KIND_SHIFT = 14;
    private static final int COPY_KIND_MASK = 0b11;
    private static final int COPY_ZEROING = 0b00;
    private static final int COPY_MERGING = 0b01;
    private static final int COPY_FLOATING = 0b11;

    // ── Prefixo 0x25: imediato largo sem predicado ──────────────────────────────────────────────
    private static final int WIDE_GROUP_SHIFT = 19;
    private static final int WIDE_GROUP_MASK = 0b111;
    private static final int WIDE_GROUP_ADD_SUB = 0b100;
    private static final int WIDE_GROUP_MIN_MAX = 0b101;
    private static final int WIDE_GROUP_MUL = 0b110;
    private static final int WIDE_GROUP_BROADCAST = 0b111;
    private static final int WIDE_OPCODE_SHIFT = 16;
    private static final int WIDE_OPCODE_MASK = 0b111;
    private static final int WIDE_KIND_SHIFT = 14;
    private static final int WIDE_KIND_MASK = 0b11;
    private static final int WIDE_KIND_ADD_SUB = 0b11;
    private static final int WIDE_FIXED_SHIFT = 13;
    private static final int WIDE_FIXED_MASK = 0b111;
    private static final int WIDE_FIXED_MIN_MAX_MUL = 0b110;
    private static final int BROADCAST_ZERO_SHIFT = 17;
    private static final int BROADCAST_ZERO_MASK = 0b11;
    private static final int BROADCAST_FLOAT_SHIFT = 13;
    private static final int BROADCAST_FLOAT_MASK = 0b1111;
    private static final int BROADCAST_FLOAT = 0b1110;
    private static final int BROADCAST_INTEGER_SHIFT = 14;
    private static final int BROADCAST_INTEGER_MASK = 0b111;
    private static final int BROADCAST_INTEGER = 0b011;

    // ── Campos de imediato ──────────────────────────────────────────────────────────────────────
    private static final int SHIFT_BIT = 13;
    private static final int IMM8_SHIFT = 5;
    private static final int IMM8_MASK = 0xFF;
    private static final int IMM8_SIGN_SHIFT = Integer.SIZE - Byte.SIZE;
    private static final int WIDE_SHIFT_AMOUNT = Byte.SIZE;
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;

    private Aarch64SveImmediateDecoder() {
    }

    /// Prefixo `0x05`: bitmask (`bits[21:18] = 0000`) e cópia predicada (`bits[21:20] = 01`). O resto do prefixo
    /// pertence a outros grupos (B17.10/B17.11) e devolve `null`.
    static Ir64Op decodePrefix05(int word, long address) {
        int rd = word & RD_MASK;
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if (((word >>> BITMASK_GROUP_SHIFT) & BITMASK_GROUP_MASK) == 0) {
            return decodeBitmask(word, address, rd, esz);
        }
        if (((word >>> COPY_GROUP_SHIFT) & COPY_GROUP_MASK) != COPY_GROUP) {
            return null;
        }
        int pg = (word >>> COPY_PG_SHIFT) & COPY_PG_MASK;
        int kind = (word >>> COPY_KIND_SHIFT) & COPY_KIND_MASK;
        if (kind == COPY_FLOATING) {
            // `FCPY` = `110`: bit 13 é parte do opcode; e byte não existe em ponto flutuante.
            return bit(word, SHIFT_BIT) || esz == ESZ_BYTE
                    ? null
                    : new Ir64Op.SveImmediate(Ir64Op.SveImmediate.Op.FCPY, esz, rd, pg,
                            floatImmediate((word >>> IMM8_SHIFT) & IMM8_MASK, esz), address);
        }
        if ((kind != COPY_ZEROING && kind != COPY_MERGING) || invalidShift(word, esz)) {
            return null;
        }
        return new Ir64Op.SveImmediate(kind == COPY_MERGING ? Ir64Op.SveImmediate.Op.CPY_MERGING
                : Ir64Op.SveImmediate.Op.CPY_ZEROING, esz, rd, pg, signedShiftedImmediate(word), address);
    }

    /// Prefixo `0x25` com `bit 21 = 1`: só é chamado DEPOIS de o decoder de predicados recusar a palavra.
    static Ir64Op decodePrefix25(int word, long address) {
        int rd = word & RD_MASK;
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int opcode = (word >>> WIDE_OPCODE_SHIFT) & WIDE_OPCODE_MASK;
        return switch ((word >>> WIDE_GROUP_SHIFT) & WIDE_GROUP_MASK) {
            case WIDE_GROUP_BROADCAST -> decodeBroadcast(word, address, rd, esz);
            case WIDE_GROUP_ADD_SUB -> decodeAddSub(word, address, rd, esz, opcode);
            case WIDE_GROUP_MIN_MAX -> decodeMinMax(word, address, rd, esz, opcode);
            case WIDE_GROUP_MUL -> opcode == 0 && fixedTail(word)
                    ? new Ir64Op.SveImmediate(Ir64Op.SveImmediate.Op.MUL, esz, rd, 0, signed8(word), address)
                    : null;
            default -> null;
        };
    }

    private static Ir64Op decodeBitmask(int word, long address, int rd, int esz) {
        int dbm = (word >>> DBM_SHIFT) & DBM_MASK;
        long mask;
        try {
            mask = Aarch64LogicalImmediate.decodeBitMasks((dbm >>> DBM_N_SHIFT) & 1, dbm & DBM_FIELD_MASK,
                    (dbm >>> DBM_IMMR_SHIFT) & DBM_FIELD_MASK);
        } catch (UnsupportedOperationException reserved) {
            return null; // combinação reservada do bitmask: UNDEFINED
        }
        Ir64Op.SveImmediate.Op op = switch (esz) {
            case 0 -> Ir64Op.SveImmediate.Op.ORR;
            case 1 -> Ir64Op.SveImmediate.Op.EOR;
            case 2 -> Ir64Op.SveImmediate.Op.AND;
            default -> Ir64Op.SveImmediate.Op.DUPM;
        };
        return new Ir64Op.SveImmediate(op, ESZ_DOUBLEWORD, rd, 0, mask, address);
    }

    private static Ir64Op decodeBroadcast(int word, long address, int rd, int esz) {
        if (((word >>> BROADCAST_ZERO_SHIFT) & BROADCAST_ZERO_MASK) != 0) {
            return null;
        }
        if (((word >>> BROADCAST_FLOAT_SHIFT) & BROADCAST_FLOAT_MASK) == BROADCAST_FLOAT) {
            return esz == ESZ_BYTE
                    ? null
                    : new Ir64Op.SveImmediate(Ir64Op.SveImmediate.Op.FDUP, esz, rd, 0,
                            floatImmediate((word >>> IMM8_SHIFT) & IMM8_MASK, esz), address);
        }
        if (((word >>> BROADCAST_INTEGER_SHIFT) & BROADCAST_INTEGER_MASK) == BROADCAST_INTEGER
                && !invalidShift(word, esz)) {
            return new Ir64Op.SveImmediate(Ir64Op.SveImmediate.Op.DUP, esz, rd, 0, signedShiftedImmediate(word),
                    address);
        }
        return null;
    }

    private static Ir64Op decodeAddSub(int word, long address, int rd, int esz, int opcode) {
        Ir64Op.SveImmediate.Op op = switch (opcode) {
            case 0b000 -> Ir64Op.SveImmediate.Op.ADD;
            case 0b001 -> Ir64Op.SveImmediate.Op.SUB;
            case 0b011 -> Ir64Op.SveImmediate.Op.SUBR;
            case 0b100 -> Ir64Op.SveImmediate.Op.SQADD;
            case 0b101 -> Ir64Op.SveImmediate.Op.UQADD;
            case 0b110 -> Ir64Op.SveImmediate.Op.SQSUB;
            case 0b111 -> Ir64Op.SveImmediate.Op.UQSUB;
            default -> null;
        };
        if (op == null || ((word >>> WIDE_KIND_SHIFT) & WIDE_KIND_MASK) != WIDE_KIND_ADD_SUB
                || invalidShift(word, esz)) {
            return null;
        }
        return new Ir64Op.SveImmediate(op, esz, rd, 0, unsignedShiftedImmediate(word), address);
    }

    private static Ir64Op decodeMinMax(int word, long address, int rd, int esz, int opcode) {
        Ir64Op.SveImmediate.Op op = switch (opcode) {
            case 0b000 -> Ir64Op.SveImmediate.Op.SMAX;
            case 0b001 -> Ir64Op.SveImmediate.Op.UMAX;
            case 0b010 -> Ir64Op.SveImmediate.Op.SMIN;
            case 0b011 -> Ir64Op.SveImmediate.Op.UMIN;
            default -> null;
        };
        if (op == null || !fixedTail(word)) {
            return null;
        }
        boolean unsigned = op == Ir64Op.SveImmediate.Op.UMAX || op == Ir64Op.SveImmediate.Op.UMIN;
        return new Ir64Op.SveImmediate(op, esz, rd, 0, unsigned ? (word >>> IMM8_SHIFT) & IMM8_MASK : signed8(word),
                address);
    }

    /// `bits[15:13] = 110`, o opcode fixo de `SMAX`/`UMAX`/`SMIN`/`UMIN`/`MUL`.
    private static boolean fixedTail(int word) {
        return ((word >>> WIDE_FIXED_SHIFT) & WIDE_FIXED_MASK) == WIDE_FIXED_MIN_MAX_MUL;
    }

    /// `INVALID` do inventário: `esz = 00` (byte) com `sh = 1` não tem como deslocar 8 bits.
    private static boolean invalidShift(int word, int esz) {
        return esz == ESZ_BYTE && bit(word, SHIFT_BIT);
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }

    private static long signed8(int word) {
        return ((word >>> IMM8_SHIFT) << IMM8_SIGN_SHIFT) >> IMM8_SIGN_SHIFT;
    }

    /// `%sh8_i8s`: `imm8` COM sinal, deslocado 8 bits à esquerda quando `sh` (bit 13) está ligado.
    private static long signedShiftedImmediate(int word) {
        long value = signed8(word);
        return bit(word, SHIFT_BIT) ? value << WIDE_SHIFT_AMOUNT : value;
    }

    /// `%sh8_i8u`: `imm8` SEM sinal, deslocado 8 bits à esquerda quando `sh` está ligado.
    private static long unsignedShiftedImmediate(int word) {
        long value = (word >>> IMM8_SHIFT) & IMM8_MASK;
        return bit(word, SHIFT_BIT) ? value << WIDE_SHIFT_AMOUNT : value;
    }

    /// `VFPExpandImm` no tamanho do elemento (`1` = half, `2` = single, `3` = double), que o A64 já implementa.
    private static long floatImmediate(int imm8, int esz) {
        return switch (esz) {
            case ESZ_HALFWORD -> Aarch64Decoder.expandFpImmediateHalf(imm8);
            case ESZ_WORD -> Aarch64Decoder.expandFpImmediate(imm8, false);
            default -> Aarch64Decoder.expandFpImmediate(imm8, true);
        };
    }
}
