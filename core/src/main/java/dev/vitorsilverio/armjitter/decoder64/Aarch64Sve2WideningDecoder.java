package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;

/// Decoder SVE2 inteiro II, metade 21b (B17.21b) — as 43 linhas de `#### SVE2 Widening Integer Arithmetic` e as 33 de
/// `#### SVE2 Narrowing` de `sve.decode`, ambas no prefixo `0x45`.
///
/// - **Widening** (`bit 21 = 0`, `bits[15:10]` escolhem a linha): add/sub/abs-diff long, add/sub wide, multiply long,
///   shift left long, `EORBT`/`EORTB`, `SMMLA`/`USMMLA`/`UMMLA` e `BEXT`/`BDEP`/`BGRP`. `bits[23:22]` é o tamanho do
///   elemento de DESTINO (as fontes têm metade dele); `0` só existe em `PMULL` (elemento de 128 bits);
/// - **Narrowing** (`bit 21 = 1`): extract narrow, shift right narrow e add/sub narrow high part. Nos dois primeiros o
///   tamanho vem de `tsz` e é o do elemento ESTREITO (destino); nas de add/sub vem de `bits[23:22]` e é o do LARGO. O
///   {@code esz} do IR é sempre o do elemento LARGO — o executor não precisa saber de que campo veio.
///
/// Features (`TRANS_FEAT` do QEMU): tudo é `FEAT_SVE2`, exceto `BEXT`/`BDEP`/`BGRP` (`FEAT_SVE_BitPerm`), `PMULL` de
/// 128 bits (`FEAT_SVE_PMULL128`), as matrizes de 8 bits (`FEAT_I8MM`) e os três `*CVTN` de par de registradores
/// (`FEAT_SVE2p1`/`FEAT_SME2`). Recusa (`null`) o que sobra (G8) — inclusive o espaço `MATCH`/`NMATCH` (B17.22).
final class Aarch64Sve2WideningDecoder {
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int RD_MASK = 0b11111;
    private static final int RN_SHIFT = 5;
    private static final int RN_MASK = 0b11111;
    private static final int RM_SHIFT = 16;
    private static final int RM_MASK = 0b11111;
    private static final int OPCODE_SHIFT = 10;
    private static final int OPCODE_MASK = 0b111111;

    private static final int NARROWING_BIT = 1 << 21;
    /// Bit 0 do opcode das linhas `*B`/`*T`: `1` = top (elemento ímpar).
    private static final int OPCODE_TOP_BIT = 0b1;
    private static final int OPCODE_UNSIGNED_BIT = 0b10;
    private static final int OPCODE_SUBTRACT_BIT = 0b100;

    /// `imm` de {@link SveIntegerOp64.IntegerUnpredicated}: qual elemento do par cada fonte lê.
    private static final long SELECT_NONE = 0L;
    private static final long SELECT_N_TOP = 1L;
    private static final long SELECT_M_TOP = 2L;
    private static final long SELECT_BOTH_TOP = 3L;

    private static final int LONG_ADD_SUB_LIMIT = 0b001000;
    private static final int LONG_ABS_DIFF_FIRST = 0b001100;
    private static final int LONG_ABS_DIFF_LAST = 0b001111;
    private static final int WIDE_FIRST = 0b010000;
    private static final int WIDE_LAST = 0b010111;
    private static final int MULTIPLY_LONG_FIRST = 0b011000;
    private static final int MULTIPLY_LONG_LAST = 0b011111;
    private static final int INTERLEAVED_ADD_BT = 0b100000;
    private static final int INTERLEAVED_SUB_BT = 0b100010;
    private static final int INTERLEAVED_SUB_TB = 0b100011;
    private static final int EOR_BT = 0b100100;
    private static final int EOR_TB = 0b100101;
    private static final int MATRIX_MULTIPLY = 0b100110;
    private static final int SHIFT_LEFT_LONG_FIRST = 0b101000;
    private static final int SHIFT_LEFT_LONG_LAST = 0b101011;
    private static final int BIT_PERMUTE_FIRST = 0b101100;
    private static final int BIT_PERMUTE_LAST = 0b101110;

    /// Multiply long: `bits[12:10]` = `SQDMULL`, `PMULL`, `SMULL`, `UMULL` (cada um `B`,`T`).
    private static final int MULTIPLY_KIND_SHIFT = 1;
    private static final int MULTIPLY_KIND_MASK = 0b11;
    private static final int MULTIPLY_SQDMULL = 0;
    private static final int MULTIPLY_PMULL = 1;
    private static final int MULTIPLY_SMULL = 2;

    /// `bits[23:22]` de `SMMLA`/`USMMLA`/`UMMLA`.
    private static final int MATRIX_SIGNED_SIGNED = 0b00;
    private static final int MATRIX_UNSIGNED_SIGNED = 0b10;
    private static final int MATRIX_UNSIGNED_UNSIGNED = 0b11;

    private static final int NARROW_EXTRACT_FIRST = 0b010000;
    private static final int NARROW_EXTRACT_LAST = 0b010101;
    private static final int NARROW_ADD_SUB_FIRST = 0b011000;
    private static final int NARROW_ADD_SUB_LAST = 0b011111;
    private static final int NARROW_SHIFT_LIMIT = 0b010000;
    /// `SQCVTN`/`UQCVTN`/`SQCVTUN` (`_sh`): `bits[23:16] = 00110001` (`esz = 1` fixo, `bit 21 = 1`, `bits[20:16] = 10001`).
    private static final int PAIR_CONVERT_MASK = 0xFFFF_0000;
    private static final int PAIR_CONVERT_VALUE = 0x4531_0000;
    private static final int PAIR_CONVERT_RN_SHIFT = 6;
    private static final int PAIR_CONVERT_RN_MASK = 0b1111;
    private static final int PAIR_CONVERT_ZERO_BIT = 1 << 5;
    private static final int PAIR_CONVERT_REGISTER_STEP = 2;
    private static final int OPCODE_SQCVTN = 0b010000;
    private static final int OPCODE_UQCVTN = 0b010010;
    private static final int OPCODE_SQCVTUN = 0b010100;

    private final Aarch64Architecture architecture;

    Aarch64Sve2WideningDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Uma palavra do prefixo `0x45` com `bit 21 = 1` (narrowing) ou uma das linhas de widening de `bit 21 = 0` que o
    /// decoder de Accumulate não reivindicou. `null` para o resto (G8).
    Ir64Op decode(int word, long address) {
        return (word & NARROWING_BIT) != 0 ? decodeNarrowing(word, address) : decodeWidening(word, address);
    }

    private Ir64Op decodeWidening(int word, long address) {
        int opcode = (word >>> OPCODE_SHIFT) & OPCODE_MASK;
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        int rm = (word >>> RM_SHIFT) & RM_MASK;
        if (opcode == MATRIX_MULTIPLY) {
            // B17.29: `SMMLA`/`USMMLA`/`UMMLA` exigem `FEAT_SVE`+`FEAT_I8MM` (`matrixMultiply` já checa), NÃO
            // `FEAT_SVE2` — por isso decide ANTES do gate de `SVE2` abaixo, que vale para o resto deste decoder
            // (o chamador, `Aarch64Sve2IntegerDecoder#decodePrefix45`, não filtra mais por `SVE2` nesse caminho).
            return matrixMultiply(esz, rd, rn, rm, address);
        }
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        boolean top = (opcode & OPCODE_TOP_BIT) != 0;
        boolean unsigned = (opcode & OPCODE_UNSIGNED_BIT) != 0;
        if (opcode < LONG_ADD_SUB_LIMIT) {
            return esz == 0 ? null : op(unsigned
                    ? ((opcode & OPCODE_SUBTRACT_BIT) != 0 ? SveIntegerOp64.IntegerUnpredicated.Op.USUBL
                            : SveIntegerOp64.IntegerUnpredicated.Op.UADDL)
                    : ((opcode & OPCODE_SUBTRACT_BIT) != 0 ? SveIntegerOp64.IntegerUnpredicated.Op.SSUBL
                            : SveIntegerOp64.IntegerUnpredicated.Op.SADDL),
                    esz, rd, rn, rm, top ? SELECT_BOTH_TOP : SELECT_NONE, 0L, address);
        }
        if (opcode >= LONG_ABS_DIFF_FIRST && opcode <= LONG_ABS_DIFF_LAST) {
            return esz == 0 ? null : op(unsigned ? SveIntegerOp64.IntegerUnpredicated.Op.UABDL
                    : SveIntegerOp64.IntegerUnpredicated.Op.SABDL, esz, rd, rn, rm, top ? SELECT_BOTH_TOP : SELECT_NONE, 0L,
                    address);
        }
        if (opcode >= WIDE_FIRST && opcode <= WIDE_LAST) {
            return esz == 0 ? null : op(wideOperation(opcode), esz, rd, rn, rm, top ? SELECT_M_TOP : SELECT_NONE, 0L,
                    address);
        }
        if (opcode >= MULTIPLY_LONG_FIRST && opcode <= MULTIPLY_LONG_LAST) {
            return multiplyLong(opcode, esz, rd, rn, rm, top, address);
        }
        if (opcode >= SHIFT_LEFT_LONG_FIRST && opcode <= SHIFT_LEFT_LONG_LAST) {
            return shiftLeftLong(word, opcode, rd, rn, address);
        }
        if (opcode >= BIT_PERMUTE_FIRST && opcode <= BIT_PERMUTE_LAST) {
            return architecture.has(Aarch64Feature.SVE_BITPERM) ? op(bitPermutation(opcode), esz, rd, rn, rm, 0L, 0L,
                    address) : null;
        }
        return switch (opcode) {
            case INTERLEAVED_ADD_BT -> esz == 0 ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.SADDL, esz, rd, rn, rm,
                    SELECT_M_TOP, 0L, address);
            case INTERLEAVED_SUB_BT -> esz == 0 ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.SSUBL, esz, rd, rn, rm,
                    SELECT_M_TOP, 0L, address);
            case INTERLEAVED_SUB_TB -> esz == 0 ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.SSUBL, esz, rd, rn, rm,
                    SELECT_N_TOP, 0L, address);
            // `EORBT`: escreve o elemento PAR (`Zn` par ^ `Zm` ímpar); `EORTB`: o ímpar (`Zn` ímpar ^ `Zm` par).
            case EOR_BT -> op(SveIntegerOp64.IntegerUnpredicated.Op.EORBT, esz, rd, rn, rm, SELECT_M_TOP, 0L, address);
            case EOR_TB -> op(SveIntegerOp64.IntegerUnpredicated.Op.EORTB, esz, rd, rn, rm, SELECT_N_TOP, 0L, address);
            // `MATRIX_MULTIPLY` já foi tratado no topo de `decodeWidening` (não exige `SVE2`) — nunca chega aqui.
            default -> null;
        };
    }

    private static SveIntegerOp64.IntegerUnpredicated.Op wideOperation(int opcode) {
        boolean subtract = (opcode & OPCODE_SUBTRACT_BIT) != 0;
        if ((opcode & OPCODE_UNSIGNED_BIT) != 0) {
            return subtract ? SveIntegerOp64.IntegerUnpredicated.Op.USUBW : SveIntegerOp64.IntegerUnpredicated.Op.UADDW;
        }
        return subtract ? SveIntegerOp64.IntegerUnpredicated.Op.SSUBW : SveIntegerOp64.IntegerUnpredicated.Op.SADDW;
    }

    /// `SQDMULL`/`PMULL`/`SMULL`/`UMULL` (`B`/`T`). `PMULL`: `.H` de `.B` (`esz = 1`), `.D` de `.S` (`esz = 3`) e — só com
    /// `FEAT_SVE_PMULL128` — `.Q` de `.D` (`esz = 0`); `esz = 2` não existe.
    private Ir64Op multiplyLong(int opcode, int esz, int rd, int rn, int rm, boolean top, long address) {
        long select = top ? SELECT_BOTH_TOP : SELECT_NONE;
        return switch ((opcode >>> MULTIPLY_KIND_SHIFT) & MULTIPLY_KIND_MASK) {
            case MULTIPLY_SQDMULL -> esz == 0 ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.SQDMULL, esz, rd, rn, rm,
                    select, 0L, address);
            case MULTIPLY_PMULL -> esz == ESZ_WORD || esz == 0 && !architecture.has(Aarch64Feature.SVE_PMULL128)
                    ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.PMULL, esz, rd, rn, rm, select, 0L, address);
            case MULTIPLY_SMULL -> esz == 0 ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.SMULL, esz, rd, rn, rm, select,
                    0L, address);
            default -> esz == 0 ? null : op(SveIntegerOp64.IntegerUnpredicated.Op.UMULL, esz, rd, rn, rm, select, 0L,
                    address);
        };
    }

    /// `SSHLL`/`USHLL` (`B`/`T`): `tsz` dá o tamanho da FONTE (`esz <= 2`); o destino é o dobro. O deslocamento vai em
    /// `imm` e o `T` em `imm2` (o `imm` de outras linhas é seletor, aqui não há dois operandos).
    private Ir64Op shiftLeftLong(int word, int opcode, int rd, int rn, long address) {
        int tsz = Aarch64Sve2IntegerDecoder.tsz(word);
        if (tsz == 0) {
            return null;
        }
        int sourceEsz = Aarch64Sve2IntegerDecoder.tszEsz(tsz);
        if (sourceEsz >= ESZ_DOUBLEWORD) {
            return null;
        }
        SveIntegerOp64.IntegerUnpredicated.Op operation = (opcode & OPCODE_UNSIGNED_BIT) != 0
                ? SveIntegerOp64.IntegerUnpredicated.Op.USHLL : SveIntegerOp64.IntegerUnpredicated.Op.SSHLL;
        return op(operation, sourceEsz + 1, rd, rn, 0, Aarch64Sve2IntegerDecoder.leftShift(word, sourceEsz),
                (opcode & OPCODE_TOP_BIT) != 0 ? 1L : 0L, address);
    }

    private static SveIntegerOp64.IntegerUnpredicated.Op bitPermutation(int opcode) {
        return switch (opcode & 0b11) {
            case 0b00 -> SveIntegerOp64.IntegerUnpredicated.Op.BEXT;
            case 0b01 -> SveIntegerOp64.IntegerUnpredicated.Op.BDEP;
            default -> SveIntegerOp64.IntegerUnpredicated.Op.BGRP;
        };
    }

    /// `SMMLA`/`USMMLA`/`UMMLA` (`@rda_rn_rm_ex esz=2`): `bits[23:22]` escolhem os sinais; `01` não existe. Sem checagem
    /// de `FEAT_SVE` aqui — `Aarch64SveDecoder.decode` já recusa o espaço inteiro sem ela antes de chegar neste decoder
    /// (checar de novo seria código morto, nunca `false` neste ponto — achado do JaCoCo desta task).
    private Ir64Op matrixMultiply(int esz, int rd, int rn, int rm, long address) {
        if (!architecture.has(Aarch64Feature.INT8_MATRIX_MULTIPLY)) {
            return null;
        }
        SveIntegerOp64.IntegerUnpredicated.Op operation = switch (esz) {
            case MATRIX_SIGNED_SIGNED -> SveIntegerOp64.IntegerUnpredicated.Op.SMMLA;
            case MATRIX_UNSIGNED_SIGNED -> SveIntegerOp64.IntegerUnpredicated.Op.USMMLA;
            case MATRIX_UNSIGNED_UNSIGNED -> SveIntegerOp64.IntegerUnpredicated.Op.UMMLA;
            default -> null;
        };
        return operation == null ? null : op(operation, ESZ_WORD, rd, rn, rm, 0L, 0L, address);
    }

    private Ir64Op decodeNarrowing(int word, long address) {
        int opcode = (word >>> OPCODE_SHIFT) & OPCODE_MASK;
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        boolean top = (opcode & OPCODE_TOP_BIT) != 0;
        if (opcode >= NARROW_ADD_SUB_FIRST && opcode <= NARROW_ADD_SUB_LAST) {
            int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
            return esz == 0 ? null : op(addSubNarrow(opcode), esz, rd, rn, (word >>> RM_SHIFT) & RM_MASK,
                    top ? 1L : 0L, 0L, address);
        }
        if (opcode >= NARROW_EXTRACT_FIRST && opcode <= NARROW_EXTRACT_LAST) {
            Ir64Op pair = (word & PAIR_CONVERT_MASK) == PAIR_CONVERT_VALUE ? pairConvert(word, opcode, rd, address) : null;
            return pair != null ? pair : extractNarrow(word, opcode, rd, rn, address);
        }
        if (opcode < NARROW_SHIFT_LIMIT) {
            return shiftRightNarrow(word, opcode, rd, rn, address);
        }
        return null;
    }

    private static SveIntegerOp64.IntegerUnpredicated.Op addSubNarrow(int opcode) {
        boolean subtract = (opcode & OPCODE_SUBTRACT_BIT) != 0;
        boolean rounding = (opcode & OPCODE_UNSIGNED_BIT) != 0; // `bit 1` = arredondamento nesta família
        if (subtract) {
            return rounding ? SveIntegerOp64.IntegerUnpredicated.Op.RSUBHN : SveIntegerOp64.IntegerUnpredicated.Op.SUBHN;
        }
        return rounding ? SveIntegerOp64.IntegerUnpredicated.Op.RADDHN : SveIntegerOp64.IntegerUnpredicated.Op.ADDHN;
    }

    /// `SQCVTN`/`UQCVTN`/`SQCVTUN` (`FEAT_SVE2p1`/`FEAT_SME2`): lê o PAR de registradores `Zn`,`Zn+1` (`bits[9:6] * 2`;
    /// `bit 5` tem que ser zero) e devolve as 16 bits saturadas intercaladas. Se `bit 5 = 1`, cai em `SQXTN*`, que recusa.
    private Ir64Op pairConvert(int word, int opcode, int rd, long address) {
        SveIntegerOp64.IntegerUnpredicated.Op operation = switch (opcode) {
            case OPCODE_SQCVTN -> SveIntegerOp64.IntegerUnpredicated.Op.SQCVTN;
            case OPCODE_UQCVTN -> SveIntegerOp64.IntegerUnpredicated.Op.UQCVTN;
            case OPCODE_SQCVTUN -> SveIntegerOp64.IntegerUnpredicated.Op.SQCVTUN;
            default -> null;
        };
        if (operation == null || (word & PAIR_CONVERT_ZERO_BIT) != 0
                || !architecture.has(Aarch64Feature.SVE2_1) && !architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2)) {
            return null;
        }
        int first = ((word >>> PAIR_CONVERT_RN_SHIFT) & PAIR_CONVERT_RN_MASK) * PAIR_CONVERT_REGISTER_STEP;
        return op(operation, ESZ_WORD, rd, first, 0, 0L, 0L, address);
    }

    /// `SQXTN*`/`UQXTN*`/`SQXTUN*` (`B`/`T`): `tsz` = tamanho ESTREITO (`esz <= 2`) e o deslocamento (`imm3`) tem que ser 0.
    private Ir64Op extractNarrow(int word, int opcode, int rd, int rn, long address) {
        int narrowEsz = narrowEsz(word);
        if (narrowEsz < 0 || Aarch64Sve2IntegerDecoder.leftShift(word, narrowEsz) != 0) {
            return null;
        }
        SveIntegerOp64.IntegerUnpredicated.Op operation = switch (opcode >>> 1) {
            case 0b01000 -> SveIntegerOp64.IntegerUnpredicated.Op.SQXTN;
            case 0b01001 -> SveIntegerOp64.IntegerUnpredicated.Op.UQXTN;
            default -> SveIntegerOp64.IntegerUnpredicated.Op.SQXTUN;
        };
        return op(operation, narrowEsz + 1, rd, rn, 0, (opcode & OPCODE_TOP_BIT) != 0 ? 1L : 0L, 0L, address);
    }

    /// Tamanho estreito de `tsz`, ou `-1` quando `tsz = 0` ou o tamanho passaria de word (`esz > 2`).
    private static int narrowEsz(int word) {
        int tsz = Aarch64Sve2IntegerDecoder.tsz(word);
        if (tsz == 0) {
            return -1;
        }
        int esz = Aarch64Sve2IntegerDecoder.tszEsz(tsz);
        return esz > ESZ_WORD ? -1 : esz;
    }

    /// As 16 linhas de shift right narrow: `bits[13:10]` = `SQSHRUN`, `SQRSHRUN`, `SHRN`, `RSHRN`, `SQSHRN`, `SQRSHRN`,
    /// `UQSHRN`, `UQRSHRN` (cada um `B`,`T`); o deslocamento vai em `imm` e o `T` em `imm2`.
    private Ir64Op shiftRightNarrow(int word, int opcode, int rd, int rn, long address) {
        int narrowEsz = narrowEsz(word);
        if (narrowEsz < 0) {
            return null;
        }
        SveIntegerOp64.IntegerUnpredicated.Op operation = switch (opcode >>> 1) {
            case 0b0000 -> SveIntegerOp64.IntegerUnpredicated.Op.SQSHRUN;
            case 0b0001 -> SveIntegerOp64.IntegerUnpredicated.Op.SQRSHRUN;
            case 0b0010 -> SveIntegerOp64.IntegerUnpredicated.Op.SHRN;
            case 0b0011 -> SveIntegerOp64.IntegerUnpredicated.Op.RSHRN;
            case 0b0100 -> SveIntegerOp64.IntegerUnpredicated.Op.SQSHRN;
            case 0b0101 -> SveIntegerOp64.IntegerUnpredicated.Op.SQRSHRN;
            case 0b0110 -> SveIntegerOp64.IntegerUnpredicated.Op.UQSHRN;
            default -> SveIntegerOp64.IntegerUnpredicated.Op.UQRSHRN;
        };
        return op(operation, narrowEsz + 1, rd, rn, 0, Aarch64Sve2IntegerDecoder.rightShift(word, narrowEsz),
                (opcode & OPCODE_TOP_BIT) != 0 ? 1L : 0L, address);
    }

    private static Ir64Op op(SveIntegerOp64.IntegerUnpredicated.Op operation, int esz, int rd, int rn, int rm, long imm,
            long imm2, long address) {
        return new SveIntegerOp64.IntegerUnpredicated(operation, esz, rd, rn, rm, 0, 0, imm, imm2, address);
    }
}
