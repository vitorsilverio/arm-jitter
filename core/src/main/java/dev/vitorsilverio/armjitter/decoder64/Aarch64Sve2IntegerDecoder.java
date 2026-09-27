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

    private final Aarch64Architecture architecture;

    Aarch64Sve2IntegerDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
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
}
