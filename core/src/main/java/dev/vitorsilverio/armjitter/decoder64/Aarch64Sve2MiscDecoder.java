package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

/// Decoder do "resto" do SVE2 (B17.22): `MATCH`/`NMATCH`, `HISTCNT`/`HISTSEG`, `LUTI2`/`LUTI4` (prefixo `0x45`,
/// `bit 21 = 1` — depois do {@link Aarch64Sve2IntegerDecoder}, que já reivindica esse mesmo espaço para o
/// narrowing da B17.21b), `PSEL` (prefixo `0x25`) e `FCLAMP` (prefixo `0x64`; `SCLAMP`/`UCLAMP` moram no
/// {@link Aarch64SveMultiplyDecoder}, que já decodifica o resto do prefixo `0x44`/`bit 21 = 0`).
///
/// Cada padrão é o `decodetree` transcrito em extração de campo (conferido contra `aarch64-none-elf-as
/// -march=armv9.5-a+sve2+lut`); o que não bate devolve `null` (G8).
final class Aarch64Sve2MiscDecoder {
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int REGISTER5_MASK = 0b11111;
    private static final int REGISTER4_MASK = 0b1111;
    private static final int RN_SHIFT = 5;
    private static final int OPCODE_SHIFT = 10;
    private static final int OPCODE6_MASK = 0b11_1111;
    private static final int OPCODE3_MASK = 0b111;
    private static final int BIT21 = 1 << 21;
    private static final int BIT_MATCH_INVERT = 1 << 4;
    private static final int OPCODE3_MATCH = 0b100;
    private static final int OPCODE3_HISTCNT = 0b110;
    private static final int OPCODE6_HISTSEG = 0b101_000;
    private static final int OPCODE6_LUTI2_1B = 0b101_100;
    private static final int OPCODE6_LUTI4_1B = 0b101_001;
    private static final int OPCODE6_LUTI4_1H = 0b101_111;
    private static final int OPCODE6_LUTI4_2H = 0b101_101;
    /// `101.10`: `bits[15:13] = 101`, `bit 12` LIVRE (parte do índice de 3 bits do `LUTI2_1h`), `bits[11:10] = 10`.
    private static final int LUTI2_1H_FIXED_MASK = 0b111_0_11;
    private static final int LUTI2_1H_FIXED_VALUE = 0b101_0_10;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;
    private static final int MATCH_ESZ_LIMIT = 1;
    private static final int LUTI4_1B_INDEX_BIT = 23;
    private static final int LUTI4_1B_FIXED_MASK = 0b011 << 21;
    private static final int LUTI4_1B_FIXED_VALUE = 0b011 << 21;
    private static final int INDEX3_LOW_BIT = 12;
    private static final int TABLE_REGISTERS_ONE = 1;
    private static final int TABLE_REGISTERS_TWO = 2;

    /// Bits comuns às 4 formas de `PSEL` (`@psel`, fora de `esz`/imediato/`pn`/`pm`/`pd`/`rv`): `bit 21 = 1`,
    /// `bits[15:14] = 01`, `bit 9 = 0`, `bit 4 = 0`.
    private static final int PSEL_COMMON_MASK = BIT21 | 0b11 << 14 | 1 << 9 | 1 << 4;
    private static final int PSEL_COMMON_VALUE = BIT21 | 0b01 << 14;
    /// `bit 18`: `1` só na forma `.B` (`bits[20:19]` são livres — parte do imediato de 4 bits).
    private static final int PSEL_BIT_B_FORM = 1 << 18;
    /// `bit 19`: `1` só na forma `.H` (com `bit 18 = 0`; `bit 20` livre — parte do imediato de 3 bits).
    private static final int PSEL_BIT_H_FORM = 1 << 19;
    /// `bits[20:18]`: `100` = `.S` (imediato inteiro em `bits[23:22]`), `000` = `.D` (exige também `bit 22 = 1`;
    /// o imediato de 1 bit é `bit 23`).
    private static final int PSEL_SIZE_FIELD_MASK = 0b111 << 18;
    private static final int PSEL_SIZE_FIELD_S = 0b100 << 18;
    private static final int PSEL_BIT_D_FORM = 1 << 22;
    private static final int PSEL_IMM_HIGH_SHIFT = 22;
    private static final int PSEL_IMM_HIGH2_MASK = 0b11;
    private static final int PSEL_IMM_LOW2_SHIFT = 19;
    private static final int PSEL_IMM_LOW2_MASK = 0b11;
    private static final int PSEL_IMM_LOW1_BIT = 20;
    private static final int PSEL_IMM_D_BIT = 23;
    private static final int PSEL_RV_SHIFT = 16;
    private static final int PSEL_RV_MASK = 0b11;
    private static final int PSEL_RV_BASE = 12;
    private static final int PSEL_PN_SHIFT = 10;
    private static final int PSEL_PM_SHIFT = 5;
    private static final int PSEL_PD_SHIFT = 0;
    private static final int PSEL_PREDICATE_MASK = 0b1111;

    private static final int FCLAMP_OPCODE6 = 0b001_001;
    private static final int FCLAMP_ESZ_BFLOAT16 = 0;

    private final Aarch64Architecture architecture;

    Aarch64Sve2MiscDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// `MATCH`/`NMATCH`/`HISTCNT`/`HISTSEG`/`LUTI2`/`LUTI4` — prefixo `0x45`, `bit 21 = 1` (o mesmo espaço da
    /// Narrowing/B17.21b: chamar DEPOIS de {@link Aarch64Sve2IntegerDecoder#decodePrefix45}, que já recusa o que
    /// não é dela).
    Ir64Op decodePrefix45(int word, long address) {
        if ((word & BIT21) == 0) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        int rm = (word >>> RM_SHIFT) & REGISTER5_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER5_MASK;
        int opcode6 = (word >>> OPCODE_SHIFT) & OPCODE6_MASK;
        int opcode3 = opcode6 >>> 3;
        if (opcode3 == OPCODE3_MATCH) {
            return decodeMatch(word, address, esz, rm, rn, opcode6 & OPCODE3_MASK);
        }
        if (opcode3 == OPCODE3_HISTCNT) {
            return decodeHistogram(true, address, esz, rm, rn, opcode6 & OPCODE3_MASK, word & REGISTER5_MASK);
        }
        if (opcode6 == OPCODE6_HISTSEG) {
            return decodeHistogram(false, address, esz, rm, rn, 0, word & REGISTER5_MASK);
        }
        if (opcode6 == OPCODE6_LUTI2_1B) {
            return lookupTable(false, ESZ_BYTE, word & REGISTER5_MASK, rn, rm, esz, TABLE_REGISTERS_ONE, address);
        }
        if ((opcode6 & LUTI2_1H_FIXED_MASK) == LUTI2_1H_FIXED_VALUE) {
            int index = (esz << 1) | ((word >>> INDEX3_LOW_BIT) & 1);
            return lookupTable(false, ESZ_HALFWORD, word & REGISTER5_MASK, rn, rm, index, TABLE_REGISTERS_ONE, address);
        }
        if ((word & LUTI4_1B_FIXED_MASK) == LUTI4_1B_FIXED_VALUE && opcode6 == OPCODE6_LUTI4_1B) {
            int index = (word >>> LUTI4_1B_INDEX_BIT) & 1;
            return lookupTable(true, ESZ_BYTE, word & REGISTER5_MASK, rn, rm, index, TABLE_REGISTERS_ONE, address);
        }
        if (opcode6 == OPCODE6_LUTI4_1H) {
            return lookupTable(true, ESZ_HALFWORD, word & REGISTER5_MASK, rn, rm, esz, TABLE_REGISTERS_ONE, address);
        }
        if (opcode6 == OPCODE6_LUTI4_2H) {
            return lookupTable(true, ESZ_HALFWORD, word & REGISTER5_MASK, rn, rm, esz, TABLE_REGISTERS_TWO, address);
        }
        return null;
    }

    /// `MATCH`/`NMATCH`: `esz ∈ {0, 1}` só (`helper_sve2_{,n}match_ppzz_{b,h}` — não existem formas `.S`/`.D`).
    private Ir64Op decodeMatch(int word, long address, int esz, int rm, int rn, int pg) {
        if (esz > MATCH_ESZ_LIMIT || !architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        boolean invert = (word & BIT_MATCH_INVERT) != 0;
        int pd = word & REGISTER4_MASK;
        return new Ir64Op.SveMatch(invert, esz, pd, pg, rn, rm, address);
    }

    /// `HISTCNT` (`esz ∈ {2, 3}`, vetor inteiro) / `HISTSEG` (byte, por segmento, sem predicado — `esz` é
    /// ignorado pelo `trans_HISTSEG` do QEMU, transcrito como aceito para os 4 valores).
    private Ir64Op decodeHistogram(boolean counting, long address, int esz, int rm, int rn, int pg, int rd) {
        if (counting && (esz < ESZ_WORD)) {
            return null;
        }
        if (!architecture.has(Aarch64Feature.SVE2)) {
            return null;
        }
        return new Ir64Op.SveHistogram(counting, esz, rd, pg, rn, rm, address);
    }

    /// `LUTI2`/`LUTI4` (`FEAT_LUT`, {@link Aarch64Feature#LOOKUP_TABLE}).
    private Ir64Op lookupTable(boolean four, int esz, int rd, int rn, int rm, int index, int tableRegisters,
            long address) {
        return architecture.has(Aarch64Feature.LOOKUP_TABLE)
                ? new Ir64Op.SveLookupTable(four, esz, rd, rn, rm, index, tableRegisters, address)
                : null;
    }

    /// `PSEL` (prefixo `0x25`, `FEAT_SME`/`FEAT_SVE2p1`): decodificação posicional pura, 4 formas por tamanho —
    /// cada uma tem um subconjunto diferente de bits fixos escolhendo `esz` e a largura do imediato
    /// (`%psel_imm_b|h|s|d`). `%psel_rv` restringe o GPR a `W12`-`W15` (Armadilha 5 da task).
    Ir64Op decodePrefix25(int word, long address) {
        if ((word & PSEL_COMMON_MASK) != PSEL_COMMON_VALUE) {
            return null;
        }
        if (!architecture.has(Aarch64Feature.SVE2_1) && !architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION)) {
            return null;
        }
        int high2 = (word >>> PSEL_IMM_HIGH_SHIFT) & PSEL_IMM_HIGH2_MASK;
        int esz;
        int imm;
        if ((word & PSEL_BIT_B_FORM) != 0) { // `.B`: `bits[20:19]` livres (imediato baixo).
            esz = ESZ_BYTE;
            imm = high2 << 2 | ((word >>> PSEL_IMM_LOW2_SHIFT) & PSEL_IMM_LOW2_MASK);
        } else if ((word & PSEL_BIT_H_FORM) != 0) { // `.H`: `bit 20` livre (imediato baixo).
            esz = ESZ_HALFWORD;
            imm = high2 << 1 | ((word >>> PSEL_IMM_LOW1_BIT) & 1);
        } else if ((word & PSEL_SIZE_FIELD_MASK) == PSEL_SIZE_FIELD_S) { // `.S`: imediato = `bits[23:22]` inteiro.
            esz = ESZ_WORD;
            imm = high2;
        } else if ((word & PSEL_BIT_D_FORM) != 0) {
            // `bits[20:18]` só tem 8 valores; `B`/`H`/`S` já consumiram 001/011/101/111, 010/110 e 100 — o que
            // sobra ao chegar aqui É `000` (`.D`) por exaustão, então só falta checar o `bit 22`.
            esz = ESZ_DOUBLEWORD;
            imm = (word >>> PSEL_IMM_D_BIT) & 1;
        } else {
            return null;
        }
        int rv = PSEL_RV_BASE + ((word >>> PSEL_RV_SHIFT) & PSEL_RV_MASK);
        int pn = (word >>> PSEL_PN_SHIFT) & PSEL_PREDICATE_MASK;
        int pm = (word >>> PSEL_PM_SHIFT) & PSEL_PREDICATE_MASK;
        int pd = (word >>> PSEL_PD_SHIFT) & PSEL_PREDICATE_MASK;
        return new Ir64Op.SvePredicateSelect(esz, pd, pn, pm, rv, imm, address);
    }

    /// `FCLAMP` (prefixo `0x64`): `esz ∈ {1, 2, 3}` (`H`/`S`/`D`, `FEAT_SME2`/`FEAT_SVE2p1`); `esz = 0` codifica
    /// `BFloat16` (`FEAT_SVE_B16B16`) — **pendência nomeada** (nenhuma arquitetura declara essa feature ainda).
    Ir64Op decodePrefix64(int word, long address) {
        if ((word & BIT21) == 0) {
            return null;
        }
        int opcode6 = (word >>> OPCODE_SHIFT) & OPCODE6_MASK;
        if (opcode6 != FCLAMP_OPCODE6) {
            return null;
        }
        int esz = (word >>> ESZ_SHIFT) & ESZ_MASK;
        if (esz == FCLAMP_ESZ_BFLOAT16
                || (!architecture.has(Aarch64Feature.SVE2_1)
                        && !architecture.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2))) {
            return null;
        }
        int rm = (word >>> RM_SHIFT) & REGISTER5_MASK;
        int rn = (word >>> RN_SHIFT) & REGISTER5_MASK;
        int rd = word & REGISTER5_MASK;
        return new Ir64Op.SveClamp(Ir64Op.SveClamp.Op.FCLAMP, esz, rd, rn, rm, address);
    }
}
