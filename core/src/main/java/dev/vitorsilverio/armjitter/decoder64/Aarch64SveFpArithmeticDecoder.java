package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE da aritmética de ponto flutuante da B17.13 (prefixo `0x65`): as 32 linhas de `### SVE Floating Point
/// Arithmetic - Unpredicated Group`, `### SVE FP Arithmetic Predicated Group` (com as 8 de imediato e `FTMAD`) e
/// `FRECPE`/`FRSQRTE` do `## SVE Floating Point Unary Operations - Unpredicated Group` do `sve.decode` do QEMU.
///
/// **Cada padrão é o `decodetree` transcrito em `máscara`/`valor`**, conferido contra `aarch64-none-elf-as`. Em FP o
/// campo `esz` é `1` = meia, `2` = simples, `3` = dupla: **`esz = 0` não é alocado em nenhuma das 32** (não é "meia
/// precisão por analogia com o inteiro") e devolve `null` (G8). `FAMAX`/`FAMIN` exigem `FEAT_FAMINMAX`
/// ({@link Aarch64Feature#FP_ABSOLUTE_MAX_MIN}); o resto vale sob `FEAT_SVE` (o gate de SVE é do chamador).
///
/// As formas reversas (`FSUBR`/`FDIVR`) chegam como `SUB`/`DIV` com `reversed = true` — o `rn` é sempre o registrador
/// destrutivo (`Zdn`) e o resultado é `op(Zm, Zdn)`. Tudo o que não bate exatamente devolve `null` e é recusado.
final class Aarch64SveFpArithmeticDecoder {
    private static final int PREDICATE_MASK = 0b111;
    private static final int REGISTER_MASK = 0b11111;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int RM_LOW_SHIFT = 5;
    private static final int OPCODE_SHIFT = 16;
    private static final int OPCODE_MASK = 0b1111;
    private static final int IMM3_MASK = 0b111;
    private static final int IMM_BIT = 5;
    private static final int UNPREDICATED_OPCODE_SHIFT = 10;
    private static final int UNPREDICATED_OPCODE_MASK = 0b111;
    private static final int IMM_OPCODE_MASK = 0b111;
    private static final int ESZ_RESERVED = 0;

    // Máscara/valor de cada linha (bits fixos do decodetree).
    private static final int UNPREDICATED_MASK = 0xFF20E000;
    private static final int UNPREDICATED_VALUE = 0x65000000;
    private static final int UNARY_MASK = 0xFF3EFC00;
    private static final int UNARY_VALUE = 0x650E3000;
    private static final int UNARY_RSQRTE_BIT = 16;
    private static final int TMAD_MASK = 0xFF38FC00;
    private static final int TMAD_VALUE = 0x65108000;
    private static final int IMMEDIATE_MASK = 0xFF38E3C0;
    private static final int IMMEDIATE_VALUE = 0x65188000;
    private static final int PREDICATED_MASK = 0xFF30E000;
    private static final int PREDICATED_VALUE = 0x65008000;

    // Opcodes das não predicadas (bits 12:10).
    private static final int UNPRED_FADD = 0b000;
    private static final int UNPRED_FSUB = 0b001;
    private static final int UNPRED_FMUL = 0b010;
    private static final int UNPRED_FTSMUL = 0b011;
    private static final int UNPRED_FRECPS = 0b110;
    private static final int UNPRED_FRSQRTS = 0b111;

    // Opcodes das predicadas por vetor (bits 19:16).
    private static final int PRED_FADD = 0b0000;
    private static final int PRED_FSUB = 0b0001;
    private static final int PRED_FMUL = 0b0010;
    private static final int PRED_FSUBR = 0b0011;
    private static final int PRED_FMAXNM = 0b0100;
    private static final int PRED_FMINNM = 0b0101;
    private static final int PRED_FMAX = 0b0110;
    private static final int PRED_FMIN = 0b0111;
    private static final int PRED_FABD = 0b1000;
    private static final int PRED_FSCALE = 0b1001;
    private static final int PRED_FMULX = 0b1010;
    private static final int PRED_FDIVR = 0b1100;
    private static final int PRED_FDIV = 0b1101;
    private static final int PRED_FAMAX = 0b1110;
    private static final int PRED_FAMIN = 0b1111;

    // Opcodes das com imediato de 1 bit (bits 18:16).
    private static final int IMM_FADD = 0b000;
    private static final int IMM_FSUB = 0b001;
    private static final int IMM_FMUL = 0b010;
    private static final int IMM_FSUBR = 0b011;
    private static final int IMM_FMAXNM = 0b100;
    private static final int IMM_FMINNM = 0b101;
    private static final int IMM_FMAX = 0b110;
    private static final int IMM_FMIN = 0b111;

    private final Aarch64Architecture architecture;

    Aarch64SveFpArithmeticDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Prefixo `0x65`: as cinco famílias da B17.13. `null` = não é (ainda) instrução deste grupo.
    Ir64Op decodePrefix65(int word, long address) {
        int esz = field(word, ESZ_SHIFT, ESZ_MASK);
        if (esz == ESZ_RESERVED) {
            return null;
        }
        if ((word & UNPREDICATED_MASK) == UNPREDICATED_VALUE) {
            return decodeUnpredicated(word, esz, address);
        }
        if ((word & UNARY_MASK) == UNARY_VALUE) {
            Ir64Op.SveFpArithmetic.Op op = bit(word, UNARY_RSQRTE_BIT)
                    ? Ir64Op.SveFpArithmetic.Op.RSQRTE : Ir64Op.SveFpArithmetic.Op.RECPE;
            return new Ir64Op.SveFpArithmetic(op, esz, field(word, 0, REGISTER_MASK), field(word, RN_SHIFT, REGISTER_MASK),
                    0, 0, false, false, false, 0, address);
        }
        if ((word & TMAD_MASK) == TMAD_VALUE) {
            int rd = field(word, 0, REGISTER_MASK);
            return new Ir64Op.SveFpArithmetic(Ir64Op.SveFpArithmetic.Op.TMAD, esz, rd, rd,
                    field(word, RM_LOW_SHIFT, REGISTER_MASK), 0, false, false, false,
                    field(word, RM_SHIFT, IMM3_MASK), address);
        }
        if ((word & IMMEDIATE_MASK) == IMMEDIATE_VALUE) {
            return decodeImmediate(word, esz, address);
        }
        if ((word & PREDICATED_MASK) == PREDICATED_VALUE) {
            return decodePredicated(word, esz, address);
        }
        return null;
    }

    private Ir64Op decodeUnpredicated(int word, int esz, long address) {
        Ir64Op.SveFpArithmetic.Op op = switch (field(word, UNPREDICATED_OPCODE_SHIFT, UNPREDICATED_OPCODE_MASK)) {
            case UNPRED_FADD -> Ir64Op.SveFpArithmetic.Op.ADD;
            case UNPRED_FSUB -> Ir64Op.SveFpArithmetic.Op.SUB;
            case UNPRED_FMUL -> Ir64Op.SveFpArithmetic.Op.MUL;
            case UNPRED_FTSMUL -> Ir64Op.SveFpArithmetic.Op.TSMUL;
            case UNPRED_FRECPS -> Ir64Op.SveFpArithmetic.Op.RECPS;
            case UNPRED_FRSQRTS -> Ir64Op.SveFpArithmetic.Op.RSQRTS;
            default -> null; // 100/101: não alocados neste grupo
        };
        if (op == null) {
            return null;
        }
        return new Ir64Op.SveFpArithmetic(op, esz, field(word, 0, REGISTER_MASK), field(word, RN_SHIFT, REGISTER_MASK),
                field(word, RM_SHIFT, REGISTER_MASK), 0, false, false, false, 0, address);
    }

    private Ir64Op decodeImmediate(int word, int esz, long address) {
        Ir64Op.SveFpArithmetic.Op op;
        boolean reversed = false;
        switch (field(word, OPCODE_SHIFT, IMM_OPCODE_MASK)) {
            case IMM_FADD -> op = Ir64Op.SveFpArithmetic.Op.ADD;
            case IMM_FSUB -> op = Ir64Op.SveFpArithmetic.Op.SUB;
            case IMM_FMUL -> op = Ir64Op.SveFpArithmetic.Op.MUL;
            case IMM_FSUBR -> {
                op = Ir64Op.SveFpArithmetic.Op.SUB;
                reversed = true;
            }
            case IMM_FMAXNM -> op = Ir64Op.SveFpArithmetic.Op.MAXNM;
            case IMM_FMINNM -> op = Ir64Op.SveFpArithmetic.Op.MINNM;
            case IMM_FMAX -> op = Ir64Op.SveFpArithmetic.Op.MAX;
            default -> op = Ir64Op.SveFpArithmetic.Op.MIN; // IMM_FMIN: os 8 opcodes existem
        }
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpArithmetic(op, esz, rd, rd, 0, field(word, PG_SHIFT, PREDICATE_MASK), true, reversed,
                true, bit(word, IMM_BIT) ? 1 : 0, address);
    }

    private Ir64Op decodePredicated(int word, int esz, long address) {
        Ir64Op.SveFpArithmetic.Op op;
        boolean reversed = false;
        switch (field(word, OPCODE_SHIFT, OPCODE_MASK)) {
            case PRED_FADD -> op = Ir64Op.SveFpArithmetic.Op.ADD;
            case PRED_FSUB -> op = Ir64Op.SveFpArithmetic.Op.SUB;
            case PRED_FMUL -> op = Ir64Op.SveFpArithmetic.Op.MUL;
            case PRED_FSUBR -> {
                op = Ir64Op.SveFpArithmetic.Op.SUB;
                reversed = true;
            }
            case PRED_FMAXNM -> op = Ir64Op.SveFpArithmetic.Op.MAXNM;
            case PRED_FMINNM -> op = Ir64Op.SveFpArithmetic.Op.MINNM;
            case PRED_FMAX -> op = Ir64Op.SveFpArithmetic.Op.MAX;
            case PRED_FMIN -> op = Ir64Op.SveFpArithmetic.Op.MIN;
            case PRED_FABD -> op = Ir64Op.SveFpArithmetic.Op.ABD;
            case PRED_FSCALE -> op = Ir64Op.SveFpArithmetic.Op.SCALE;
            case PRED_FMULX -> op = Ir64Op.SveFpArithmetic.Op.MULX;
            case PRED_FDIVR -> {
                op = Ir64Op.SveFpArithmetic.Op.DIV;
                reversed = true;
            }
            case PRED_FDIV -> op = Ir64Op.SveFpArithmetic.Op.DIV;
            case PRED_FAMAX, PRED_FAMIN -> {
                if (!architecture.has(Aarch64Feature.FP_ABSOLUTE_MAX_MIN)) {
                    return null;
                }
                op = field(word, OPCODE_SHIFT, OPCODE_MASK) == PRED_FAMAX
                        ? Ir64Op.SveFpArithmetic.Op.AMAX : Ir64Op.SveFpArithmetic.Op.AMIN;
            }
            default -> {
                return null; // 1011: não alocado
            }
        }
        int rd = field(word, 0, REGISTER_MASK);
        return new Ir64Op.SveFpArithmetic(op, esz, rd, rd, field(word, RN_SHIFT, REGISTER_MASK),
                field(word, PG_SHIFT, PREDICATE_MASK), true, reversed, false, 0, address);
    }

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }

    private static int field(int word, int shift, int mask) {
        return (word >>> shift) & mask;
    }
}
