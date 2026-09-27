package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder SVE2 inteiro I (B17.20) — as 21 linhas de `#### SVE2 Support` de `sve.decode` que cobrem o multiply
/// não-predicado, `SADALP`/`UADALP`, as unárias predicadas (`URECPE`/`URSQRTE`/`SQABS`/`SQNEG`) e o pairwise predicado.
///
/// Dois prefixos, transcritos linha a linha (e conferidos contra o assembler, ver os testes):
///
/// - `0x04` com `bit 21 = 1`, `bits[15:10] = 0110xx`/`0111xx`: os 6 multiplies não-predicados (mesmo prefixo das
///   operações da B17.5, separados só pelo opcode — por isso este decoder roda DEPOIS do da B17.5, que devolve `null`
///   para esses opcodes). `PMUL` só existe com `esz = 0`;
/// - `0x44` com `bits[15:13] = 101`: `bits[21:16]` escolhem a operação. As 8 unárias `_m`/`_z`, `SADALP`/`UADALP`
///   (`@rdm_pg_rn`: o destino é também o acumulador) e as 5 pairwise (`@rdn_pg_rm`).
///
/// Feature (`TRANS_FEAT` do QEMU): tudo é `FEAT_SVE2`, exceto as unárias `_z`, que são `FEAT_SVE2p2`. Restrições de
/// `esz`: `SADALP`/`UADALP` recusam `esz = 0`; `URECPE`/`URSQRTE` só existem com `esz = 2`. Recusa (`null`) o que sobra
/// (G8).
final class Aarch64Sve2IntegerDecoder {
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_WORD = 2;
    private static final int RD_MASK = 0b11111;
    private static final int RN_SHIFT = 5;
    private static final int RN_MASK = 0b11111;
    private static final int RM_SHIFT = 16;
    private static final int RM_MASK = 0b11111;
    private static final int PG_SHIFT = 10;
    private static final int PG_MASK = 0b111;

    /// `00000100 .. 1 ..... 011x xx ...`: prefixo `0x04`, `bit 21 = 1`, `bits[15:12] = 011x` (`bit 12` é livre).
    private static final int UNPREDICATED_FIXED_MASK = 0xFF20_E000;
    private static final int UNPREDICATED_FIXED_VALUE = 0x0420_6000;
    private static final int UNPREDICATED_OPCODE_SHIFT = 10;
    private static final int UNPREDICATED_OPCODE_MASK = 0b111111;
    private static final int UNPREDICATED_MUL = 0b011000;
    private static final int UNPREDICATED_PMUL = 0b011001;
    private static final int UNPREDICATED_SMULH = 0b011010;
    private static final int UNPREDICATED_UMULH = 0b011011;
    private static final int UNPREDICATED_SQDMULH = 0b011100;
    private static final int UNPREDICATED_SQRDMULH = 0b011101;

    /// `01000100 .. ...... 101 ...`: prefixo `0x44` e `bits[15:13] = 101`.
    private static final int PREDICATED_FIXED_MASK = 0xFF00_E000;
    private static final int PREDICATED_FIXED_VALUE = 0x4400_A000;
    private static final int PREDICATED_OPCODE_SHIFT = 16;
    private static final int PREDICATED_OPCODE_MASK = 0b111111;
    private static final int PRED_URECPE_M = 0b000000;
    private static final int PRED_URSQRTE_M = 0b000001;
    private static final int PRED_URECPE_Z = 0b000010;
    private static final int PRED_URSQRTE_Z = 0b000011;
    private static final int PRED_SADALP = 0b000100;
    private static final int PRED_UADALP = 0b000101;
    private static final int PRED_SQABS_M = 0b001000;
    private static final int PRED_SQNEG_M = 0b001001;
    private static final int PRED_SQABS_Z = 0b001010;
    private static final int PRED_SQNEG_Z = 0b001011;
    private static final int PRED_ADDP = 0b010001;
    private static final int PRED_SMAXP = 0b010100;
    private static final int PRED_UMAXP = 0b010101;
    private static final int PRED_SMINP = 0b010110;
    private static final int PRED_UMINP = 0b010111;

    /// `01000100 .. ...... 100 ...`: prefixo `0x44` e `bits[15:13] = 100` (B17.21a) — shift por vetor saturante/arredondado,
    /// halving e saturating add/sub predicados.
    private static final int VECTOR_FIXED_VALUE = 0x4400_8000;
    private static final int TSZ_HIGH_SHIFT = 22;
    private static final int TSZ_LOW_SHIFT = 19;
    private static final int TSZ_FIELD_MASK = 0b11;
    private static final int TSZ_LOW_FIELD_BITS = 2;
    private static final int TSZIMM_HIGH_SHIFT_BITS = 5;
    private static final int TSZIMM_LOW_MASK = 0b11111;
    private static final int ESZ_BYTE_BITS = 8;
    private static final int ESZ_BYTE_BITS_DOUBLE = 16;
    private static final int SHIFT_FLAGS_SHIFT = 10;
    private static final int SHIFT_FLAGS_MASK = 0b11;

    /// Espaço `0x45` (B17.21a, `#### SVE2 Accumulate`): `bit 21 = 0`, `bits[15:10]` escolhem a família.
    private static final int ACCUMULATE_FIXED_MASK = 0xFF20_0000;
    private static final int ACCUMULATE_FIXED_VALUE = 0x4500_0000;
    private static final int ACCUMULATE_FAMILY_MASK = 0xF000;
    private static final int FAMILY_ABS_DIFF_LONG = 0xC000;
    private static final int FAMILY_SHIFT_ACCUMULATE = 0xE000;
    private static final int COMPLEX_ADD_MASK = 0xFF3E_F800;
    private static final int COMPLEX_ADD_VALUE = 0x4500_D800;
    private static final int COMPLEX_SATURATE_BIT = 1 << 16;
    private static final int TOP_BIT = 1 << 10;
    private static final int UNSIGNED_BIT = 1 << 11;
    private static final int FIVE_BIT_FAMILY_MASK = 0xFF20_F800;
    private static final int CARRY_VALUE = 0x4500_D000;
    private static final int INSERT_VALUE = 0x4500_F000;
    private static final int ABS_DIFF_VALUE = 0x4500_F800;
    private static final int SUBTRACT_BIT = 1 << 23;
    private static final int CARRY_SIZE_BIT = 1 << 22;
    private static final int ESZ_ADCL_WORD = 2;
    private static final int ESZ_ADCL_DOUBLEWORD = 3;

    /// `bit 21` do prefixo `0x45`: `1` = `#### SVE2 Narrowing` (B17.21b), `0` = Widening/Accumulate.
    private static final int NARROWING_SPACE_BIT = 1 << 21;

    private final Aarch64Architecture architecture;
    private final Aarch64Sve2WideningDecoder widening;

    Aarch64Sve2IntegerDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
        this.widening = new Aarch64Sve2WideningDecoder(architecture);
    }

    /// Multiply não-predicado (prefixo `0x04`). `null` quando a palavra não é uma destas 6 linhas.
    Ir64Op decodePrefix04(int word, long address) {
        if ((word & UNPREDICATED_FIXED_MASK) != UNPREDICATED_FIXED_VALUE || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        Ir64Op.SveIntegerUnpredicated.Op op = switch ((word >>> UNPREDICATED_OPCODE_SHIFT) & UNPREDICATED_OPCODE_MASK) {
            case UNPREDICATED_MUL -> Ir64Op.SveIntegerUnpredicated.Op.MUL;
            case UNPREDICATED_SMULH -> Ir64Op.SveIntegerUnpredicated.Op.SMULH;
            case UNPREDICATED_UMULH -> Ir64Op.SveIntegerUnpredicated.Op.UMULH;
            case UNPREDICATED_PMUL -> esz == ESZ_BYTE ? Ir64Op.SveIntegerUnpredicated.Op.PMUL : null;
            case UNPREDICATED_SQDMULH -> Ir64Op.SveIntegerUnpredicated.Op.SQDMULH;
            case UNPREDICATED_SQRDMULH -> Ir64Op.SveIntegerUnpredicated.Op.SQRDMULH;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        return new Ir64Op.SveIntegerUnpredicated(op, esz, word & RD_MASK, (word >>> RN_SHIFT) & RN_MASK,
                (word >>> RM_SHIFT) & RM_MASK, 0, 0, 0, 0, address);
    }

    /// `SADALP`/`UADALP`, unárias e pairwise predicados (prefixo `0x44`). `null` quando não é uma destas 15 linhas
    /// (a palavra segue para os outros decoders do prefixo, ou é recusada).
    Ir64Op decodePrefix44(int word, long address) {
        if ((word & PREDICATED_FIXED_MASK) == VECTOR_FIXED_VALUE) {
            return decodeVectorPredicated(word, address);
        }
        if ((word & PREDICATED_FIXED_MASK) != PREDICATED_FIXED_VALUE || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int rd = word & RD_MASK;
        int field = (word >>> RN_SHIFT) & RN_MASK;
        int pg = (word >>> PG_SHIFT) & PG_MASK;
        return switch ((word >>> PREDICATED_OPCODE_SHIFT) & PREDICATED_OPCODE_MASK) {
            case PRED_SADALP -> accumulate(Ir64Op.SveIntegerPredicated.Op.SADALP, esz, rd, field, pg, address);
            case PRED_UADALP -> accumulate(Ir64Op.SveIntegerPredicated.Op.UADALP, esz, rd, field, pg, address);
            case PRED_URECPE_M -> unary(Ir64Op.SveIntegerPredicated.Op.URECPE, esz, rd, field, pg, false, address);
            case PRED_URSQRTE_M -> unary(Ir64Op.SveIntegerPredicated.Op.URSQRTE, esz, rd, field, pg, false, address);
            case PRED_URECPE_Z -> unary(Ir64Op.SveIntegerPredicated.Op.URECPE, esz, rd, field, pg, true, address);
            case PRED_URSQRTE_Z -> unary(Ir64Op.SveIntegerPredicated.Op.URSQRTE, esz, rd, field, pg, true, address);
            case PRED_SQABS_M -> unary(Ir64Op.SveIntegerPredicated.Op.SQABS, esz, rd, field, pg, false, address);
            case PRED_SQNEG_M -> unary(Ir64Op.SveIntegerPredicated.Op.SQNEG, esz, rd, field, pg, false, address);
            case PRED_SQABS_Z -> unary(Ir64Op.SveIntegerPredicated.Op.SQABS, esz, rd, field, pg, true, address);
            case PRED_SQNEG_Z -> unary(Ir64Op.SveIntegerPredicated.Op.SQNEG, esz, rd, field, pg, true, address);
            case PRED_ADDP -> pairwise(Ir64Op.SveIntegerPredicated.Op.ADDP, esz, rd, field, pg, address);
            case PRED_SMAXP -> pairwise(Ir64Op.SveIntegerPredicated.Op.SMAXP, esz, rd, field, pg, address);
            case PRED_UMAXP -> pairwise(Ir64Op.SveIntegerPredicated.Op.UMAXP, esz, rd, field, pg, address);
            case PRED_SMINP -> pairwise(Ir64Op.SveIntegerPredicated.Op.SMINP, esz, rd, field, pg, address);
            case PRED_UMINP -> pairwise(Ir64Op.SveIntegerPredicated.Op.UMINP, esz, rd, field, pg, address);
            default -> null;
        };
    }

    /// `@rdm_pg_rn`: `Zn` em `bits[9:5]`; o destino `Zda` é também o acumulador (`rm = rd`). `esz = 0` não existe.
    private static Ir64Op accumulate(Ir64Op.SveIntegerPredicated.Op op, int esz, int rd, int rn, int pg,
            long address) {
        if (esz == ESZ_BYTE) {
            return null;
        }
        return new Ir64Op.SveIntegerPredicated(op, esz, rd, rn, rd, pg, 0, false, address);
    }

    /// `@rd_pg_rn`. As estimativas só existem com `esz = 2`; `_z` (`FEAT_SVE2p2`) zera o elemento inativo.
    private Ir64Op unary(Ir64Op.SveIntegerPredicated.Op op, int esz, int rd, int rn, int pg, boolean zeroing,
            long address) {
        boolean estimate = op == Ir64Op.SveIntegerPredicated.Op.URECPE || op == Ir64Op.SveIntegerPredicated.Op.URSQRTE;
        if (estimate && esz != ESZ_WORD || zeroing && !architecture.has(Aarch64Feature.SVE2_2)) {
            return null;
        }
        return new Ir64Op.SveIntegerPredicated(op, esz, rd, rn, 0, pg, 0, zeroing, address);
    }

    /// `@rdn_pg_rm`: `Zm` em `bits[9:5]`, destrutiva em `Zdn` (`rn = rd`).
    private static Ir64Op pairwise(Ir64Op.SveIntegerPredicated.Op op, int esz, int rd, int rm, int pg,
            long address) {
        return new Ir64Op.SveIntegerPredicated(op, esz, rd, rd, rm, pg, 0, false, address);
    }

    /// B17.21a: as 28 linhas predicadas de `bits[15:13] = 100` (`@rdn_pg_rm`, `@rdm_pg_rn` nas reversas — o decoder entrega
    /// `rn`/`rm` já trocados, então o resultado é sempre `op(Zn, Zm)`). Todas `FEAT_SVE2`, todos os tamanhos.
    private Ir64Op decodeVectorPredicated(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        int opcode = (word >>> PREDICATED_OPCODE_SHIFT) & PREDICATED_OPCODE_MASK;
        Ir64Op.SveIntegerPredicated.Op op = vectorOperation(opcode);
        if (op == null) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int rd = word & RD_MASK;
        int field = (word >>> RN_SHIFT) & RN_MASK;
        int pg = (word >>> PG_SHIFT) & PG_MASK;
        return isReversed(opcode)
                ? new Ir64Op.SveIntegerPredicated(op, esz, rd, field, rd, pg, 0, false, address)
                : new Ir64Op.SveIntegerPredicated(op, esz, rd, rd, field, pg, 0, false, address);
    }

    private static Ir64Op.SveIntegerPredicated.Op vectorOperation(int opcode) {
        return switch (opcode) {
            case 0b000010, 0b000110 -> Ir64Op.SveIntegerPredicated.Op.SRSHL;
            case 0b000011, 0b000111 -> Ir64Op.SveIntegerPredicated.Op.URSHL;
            case 0b001000, 0b001100 -> Ir64Op.SveIntegerPredicated.Op.SQSHL_VECTOR;
            case 0b001001, 0b001101 -> Ir64Op.SveIntegerPredicated.Op.UQSHL_VECTOR;
            case 0b001010, 0b001110 -> Ir64Op.SveIntegerPredicated.Op.SQRSHL;
            case 0b001011, 0b001111 -> Ir64Op.SveIntegerPredicated.Op.UQRSHL;
            case 0b010000 -> Ir64Op.SveIntegerPredicated.Op.SHADD;
            case 0b010001 -> Ir64Op.SveIntegerPredicated.Op.UHADD;
            case 0b010010, 0b010110 -> Ir64Op.SveIntegerPredicated.Op.SHSUB;
            case 0b010011, 0b010111 -> Ir64Op.SveIntegerPredicated.Op.UHSUB;
            case 0b010100 -> Ir64Op.SveIntegerPredicated.Op.SRHADD;
            case 0b010101 -> Ir64Op.SveIntegerPredicated.Op.URHADD;
            case 0b011000 -> Ir64Op.SveIntegerPredicated.Op.SQADD;
            case 0b011001 -> Ir64Op.SveIntegerPredicated.Op.UQADD;
            case 0b011010, 0b011110 -> Ir64Op.SveIntegerPredicated.Op.SQSUB;
            case 0b011011, 0b011111 -> Ir64Op.SveIntegerPredicated.Op.UQSUB;
            case 0b011100 -> Ir64Op.SveIntegerPredicated.Op.SUQADD;
            case 0b011101 -> Ir64Op.SveIntegerPredicated.Op.USQADD;
            default -> null;
        };
    }

    /// As 10 formas reversas (`SRSHLR`, `URSHLR`, `SQSHLR`, `UQSHLR`, `SQRSHLR`, `UQRSHLR`, `SHSUBR`, `UHSUBR`,
    /// `SQSUBR`, `UQSUBR`): `Zdn` é o SEGUNDO operando.
    private static boolean isReversed(int opcode) {
        return switch (opcode) {
            case 0b000110, 0b000111, 0b001100, 0b001101, 0b001110, 0b001111, 0b010110, 0b010111, 0b011110,
                    0b011111 -> true;
            default -> false;
        };
    }

    /// B17.21a: as 18 linhas de `#### SVE2 Accumulate` (prefixo `0x45`, `bit 21 = 0`). `null` para o resto do espaço (G8).
    Ir64Op decodePrefix45(int word, long address) {
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int rd = word & RD_MASK;
        int rn = (word >>> RN_SHIFT) & RN_MASK;
        int rm = (word >>> RM_SHIFT) & RM_MASK;
        long top = (word & TOP_BIT) != 0 ? 1 : 0;
        if ((word & COMPLEX_ADD_MASK) == COMPLEX_ADD_VALUE) {
            Ir64Op.SveIntegerUnpredicated.Op op = (word & COMPLEX_SATURATE_BIT) != 0
                    ? Ir64Op.SveIntegerUnpredicated.Op.SQCADD : Ir64Op.SveIntegerUnpredicated.Op.CADD;
            // `@rdn_rm`: `Zm` em `bits[9:5]`, destrutiva em `Zdn`. `bit 10` = rotação 270.
            return unpredicated(op, esz, rd, rd, rn, top, address);
        }
        if ((word & NARROWING_SPACE_BIT) != 0) {
            return widening.decode(word, address); // B17.21b: `#### SVE2 Narrowing` (e o espaço MATCH, recusado)
        }
        if ((word & ACCUMULATE_FIXED_MASK) != ACCUMULATE_FIXED_VALUE) {
            return null;
        }
        int fiveBitFamily = word & FIVE_BIT_FAMILY_MASK;
        if (fiveBitFamily == CARRY_VALUE) {
            // `bit 23` escolhe ADC/SBC e `bit 22` o tamanho (`.S`/`.D`) — o oposto do que a spec da task dizia.
            Ir64Op.SveIntegerUnpredicated.Op op = (word & SUBTRACT_BIT) != 0
                    ? Ir64Op.SveIntegerUnpredicated.Op.SBCL : Ir64Op.SveIntegerUnpredicated.Op.ADCL;
            int size = (word & CARRY_SIZE_BIT) != 0 ? ESZ_ADCL_DOUBLEWORD : ESZ_ADCL_WORD;
            return unpredicated(op, size, rd, rn, rm, top, address);
        }
        if (fiveBitFamily == ABS_DIFF_VALUE) {
            return unpredicated(top != 0 ? Ir64Op.SveIntegerUnpredicated.Op.UABA
                    : Ir64Op.SveIntegerUnpredicated.Op.SABA, esz, rd, rn, rm, 0, address);
        }
        if (fiveBitFamily == INSERT_VALUE) {
            return shiftInsert(word, rd, rn, top != 0, address);
        }
        return switch (word & ACCUMULATE_FAMILY_MASK) {
            case FAMILY_ABS_DIFF_LONG -> esz == 0 ? null : unpredicated(
                    (word & UNSIGNED_BIT) != 0 ? Ir64Op.SveIntegerUnpredicated.Op.UABAL
                            : Ir64Op.SveIntegerUnpredicated.Op.SABAL,
                    esz, rd, rn, rm, top, address);
            case FAMILY_SHIFT_ACCUMULATE -> shiftAccumulate(word, rd, rn, address);
            default -> widening.decode(word, address); // B17.21b: `#### SVE2 Widening Integer Arithmetic`
        };
    }

    private static Ir64Op unpredicated(Ir64Op.SveIntegerUnpredicated.Op op, int esz, int rd, int rn, int rm, long imm,
            long address) {
        return new Ir64Op.SveIntegerUnpredicated(op, esz, rd, rn, rm, 0, 0, imm, 0, address);
    }

    /// `SSRA`/`USRA`/`SRSRA`/`URSRA` (`bits[11:10]` = `U`,`R`): acumulam o shift à direita de `Zn` em `Zda`.
    private static Ir64Op shiftAccumulate(int word, int rd, int rn, long address) {
        int tsz = tsz(word);
        if (tsz == 0) {
            return null;
        }
        Ir64Op.SveIntegerUnpredicated.Op op = switch ((word >>> SHIFT_FLAGS_SHIFT) & SHIFT_FLAGS_MASK) {
            case 0b00 -> Ir64Op.SveIntegerUnpredicated.Op.SSRA;
            case 0b01 -> Ir64Op.SveIntegerUnpredicated.Op.USRA;
            case 0b10 -> Ir64Op.SveIntegerUnpredicated.Op.SRSRA;
            default -> Ir64Op.SveIntegerUnpredicated.Op.URSRA;
        };
        int esz = tszEsz(tsz);
        return unpredicated(op, esz, rd, rn, 0, rightShift(word, esz), address);
    }

    /// `SRI` (`bit 10 = 0`) e `SLI` (`bit 10 = 1`). `tsz = 0` é não alocado.
    private static Ir64Op shiftInsert(int word, int rd, int rn, boolean left, long address) {
        int tsz = tsz(word);
        if (tsz == 0) {
            return null;
        }
        int esz = tszEsz(tsz);
        return left
                ? unpredicated(Ir64Op.SveIntegerUnpredicated.Op.SLI, esz, rd, rn, 0, leftShift(word, esz), address)
                : unpredicated(Ir64Op.SveIntegerUnpredicated.Op.SRI, esz, rd, rn, 0, rightShift(word, esz), address);
    }

    /// `tsz` = `bits[23:22]`:`bits[20:19]` (o `imm3` são `bits[18:16]`).
    static int tsz(int word) {
        return ((word >>> TSZ_HIGH_SHIFT) & TSZ_FIELD_MASK) << TSZ_LOW_FIELD_BITS
                | (word >>> TSZ_LOW_SHIFT) & TSZ_FIELD_MASK;
    }

    /// `esz` = posição do bit mais alto de `tsz` (`%tszimm_esz`).
    static int tszEsz(int tsz) {
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(tsz);
    }

    private static int tszimm(int word) {
        return ((word >>> TSZ_HIGH_SHIFT) & TSZ_FIELD_MASK) << TSZIMM_HIGH_SHIFT_BITS
                | (word >>> RM_SHIFT) & TSZIMM_LOW_MASK;
    }

    /// `%tszimm_shr`: `(16 << esz) - tszimm` (1 a `esize`).
    static int rightShift(int word, int esz) {
        return (ESZ_BYTE_BITS_DOUBLE << esz) - tszimm(word);
    }

    /// `%tszimm_shl`: `tszimm - (8 << esz)` (0 a `esize - 1`).
    static int leftShift(int word, int esz) {
        return tszimm(word) - (ESZ_BYTE_BITS << esz);
    }
}
