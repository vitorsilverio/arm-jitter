package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.List;

/// E15.10 (D5 do épico E15): a classe "Data Processing — Immediate" (`bits[28:26]=100`) do A64 como
/// tabela — antes `Aarch64Decoder#decodeDataProcessingImmediate` e 6 sub-decoders com as restrições
/// de campo em `if`/`throw`. Aqui cada restrição é coluna de linha, igual ao `a64.decode`/ARM ARM
/// C4.1.86, e o que não casa nenhuma linha é recusado pelo chamador (G8).
///
/// Layout (bit 31 → 0): `sf op S 100 op0(25:23) ...`. Origem das famílias: `ADR`/`ADRP`, add/sub e
/// logical imediato (B6.3.1), move wide e bitfield (B6.3.2), `EXTR` (B8.2).
///
/// **Restrições de 32 bits que viraram linha** — as três estavam ausentes da versão em cascata e
/// aceitavam 30 720 palavras que o `objdump` dá como `undefined` (medido pela E15.10):
///
/// - move wide: `sf=0` só tem `hw` `00`/`01` (`hw<1>` desloca para fora do registrador W);
/// - bitfield: `sf=0` exige `N=0`, `immr<5>=0` e `imms<5>=0`;
/// - `EXTR`: `op21` (`bits[30:29]`) é sempre `00`, além de `o0=0`, `N=sf` e `imms<5>=0` em 32 bits.
///
/// **A única restrição que não é linha:** a bitmask reservada do logical imediato (corrida de uns
/// ocupando o elemento inteiro — 8 combinações de `N:imms` espalhadas, ver
/// {@link Aarch64LogicalImmediate#decodeBitMasks}), que lança `UnsupportedOperationException` no
/// construtor da op.
///
/// Add/sub com `bit23=1` (`ADDG`/`SUBG` de `FEAT_MTE`, min/max imediato de `FEAT_CSSC`) não tem linha
/// ainda: é a B19.30, que acrescenta as linhas com a feature na coluna `requires`.
final class DataProcessingImmediateRows {
    private static final int SF_SHIFT = 31;
    private static final int PC_REL_PAGE_SHIFT = 31;
    private static final int PC_REL_IMMLO_SHIFT = 29;
    private static final int PC_REL_IMMLO_BITS = 2;
    private static final int PC_REL_IMMLO_MASK = (1 << PC_REL_IMMLO_BITS) - 1;
    private static final int PC_REL_IMMHI_SHIFT = 5;
    private static final int PC_REL_IMMHI_BITS = 19;
    private static final int PC_REL_IMMHI_MASK = (1 << PC_REL_IMMHI_BITS) - 1;
    /// `immhi:immlo` tem 21 bits; deslocar até o bit 31 e voltar com `>>` estende o sinal.
    private static final int PC_REL_SIGN_EXTEND_SHIFT = Integer.SIZE - (PC_REL_IMMHI_BITS + PC_REL_IMMLO_BITS);
    private static final int ADRP_PAGE_SHIFT = 12;
    private static final int ADD_SUB_SHIFT_BIT = 22;
    private static final int ADD_SUB_LSL_12 = 12;
    private static final int IMM12_SHIFT = 10;
    private static final int IMM12_MASK = 0xFFF;
    private static final int N_SHIFT = 22;
    private static final int IMMR_SHIFT = 16;
    private static final int IMMS_SHIFT = 10;
    private static final int SIX_BIT_FIELD_MASK = 0b11_1111;
    private static final int MOVE_WIDE_HW_SHIFT = 21;
    private static final int MOVE_WIDE_HW_MASK = 0b11;
    private static final int MOVE_WIDE_HW_UNIT_BITS = 16;
    private static final int IMM16_SHIFT = 5;
    private static final int IMM16_MASK = 0xFFFF;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;

    /// As 28 linhas. Colunas: `sf op S 100 op0 ...` (os campos de cada família no comentário).
    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = List.of(
            // PC-relative — op immlo 10000 immhi Rd
            row("0 .. 10000 ................... .....", DataProcessingImmediateRows::pcRelative),
            row("1 .. 10000 ................... .....", DataProcessingImmediateRows::pcRelative),
            // add/sub imediato — sf op S 100010 sh imm12 Rn Rd (Rn sempre Rn|SP; Rd|SP só sem S)
            row(". 0 0 100010 . ............ ..... .....", addSub(Ir64AluOp.ADD, false)),
            row(". 0 1 100010 . ............ ..... .....", addSub(Ir64AluOp.ADD, true)),
            row(". 1 0 100010 . ............ ..... .....", addSub(Ir64AluOp.SUB, false)),
            row(". 1 1 100010 . ............ ..... .....", addSub(Ir64AluOp.SUB, true)),
            // logical imediato — sf opc 100100 N immr imms Rn Rd (N=1 só em 64 bits)
            row("1 00 100100 . ...... ...... ..... .....", logical(Ir64AluOp.AND, false)),
            row("0 00 100100 0 ...... ...... ..... .....", logical(Ir64AluOp.AND, false)),
            row("1 01 100100 . ...... ...... ..... .....", logical(Ir64AluOp.ORR, false)),
            row("0 01 100100 0 ...... ...... ..... .....", logical(Ir64AluOp.ORR, false)),
            row("1 10 100100 . ...... ...... ..... .....", logical(Ir64AluOp.EOR, false)),
            row("0 10 100100 0 ...... ...... ..... .....", logical(Ir64AluOp.EOR, false)),
            row("1 11 100100 . ...... ...... ..... .....", logical(Ir64AluOp.AND, true)),
            row("0 11 100100 0 ...... ...... ..... .....", logical(Ir64AluOp.AND, true)),
            // move wide — sf opc 100101 hw imm16 Rd (opc=01 reservado; hw<1>=0 em 32 bits)
            row("1 00 100101 .. ................ .....", moveWide(Ir64MoveWideOp.MOVN)),
            row("0 00 100101 0. ................ .....", moveWide(Ir64MoveWideOp.MOVN)),
            row("1 10 100101 .. ................ .....", moveWide(Ir64MoveWideOp.MOVZ)),
            row("0 10 100101 0. ................ .....", moveWide(Ir64MoveWideOp.MOVZ)),
            row("1 11 100101 .. ................ .....", moveWide(Ir64MoveWideOp.MOVK)),
            row("0 11 100101 0. ................ .....", moveWide(Ir64MoveWideOp.MOVK)),
            // bitfield — sf opc 100110 N immr imms Rn Rd (N=sf; immr/imms < 32 em 32 bits)
            row("1 00 100110 1 ...... ...... ..... .....", bitfield(Ir64BitfieldOp.SBFM)),
            row("0 00 100110 0 0..... 0..... ..... .....", bitfield(Ir64BitfieldOp.SBFM)),
            row("1 01 100110 1 ...... ...... ..... .....", bitfield(Ir64BitfieldOp.BFM)),
            row("0 01 100110 0 0..... 0..... ..... .....", bitfield(Ir64BitfieldOp.BFM)),
            row("1 10 100110 1 ...... ...... ..... .....", bitfield(Ir64BitfieldOp.UBFM)),
            row("0 10 100110 0 0..... 0..... ..... .....", bitfield(Ir64BitfieldOp.UBFM)),
            // EXTR — sf 00 100111 N o0 Rm imms Rn Rd (N=sf, o0=0; imms < 32 em 32 bits)
            row("1 00 100111 1 0 ..... ...... ..... .....", DataProcessingImmediateRows::extract),
            row("0 00 100111 0 0 ..... 0..... ..... .....", DataProcessingImmediateRows::extract)
    );

    private DataProcessingImmediateRows() {
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static Ir64Op pcRelative(int word, long address) {
        boolean page = bit(word, PC_REL_PAGE_SHIFT) != 0;
        int immlo = (word >>> PC_REL_IMMLO_SHIFT) & PC_REL_IMMLO_MASK;
        int immhi = (word >>> PC_REL_IMMHI_SHIFT) & PC_REL_IMMHI_MASK;
        long imm = ((immhi << PC_REL_IMMLO_BITS | immlo) << PC_REL_SIGN_EXTEND_SHIFT) >> PC_REL_SIGN_EXTEND_SHIFT;
        return new IntegerOp64.PcRelative(rd(word), address, page ? imm << ADRP_PAGE_SHIFT : imm, page);
    }

    /// ARM DDI 0487 C6.2.4/C6.2.339: sem `S`, `Rd|SP`; com `S`, `Rd` é registrador normal (`ZR` quando
    /// 31). `Rn` é sempre `Rn|SP`.
    private static WordDecoder<Ir64Op> addSub(Ir64AluOp opcode, boolean setFlags) {
        return (word, address) -> {
            long imm12 = (word >>> IMM12_SHIFT) & IMM12_MASK;
            long immediate = bit(word, ADD_SUB_SHIFT_BIT) != 0 ? imm12 << ADD_SUB_LSL_12 : imm12;
            return new IntegerOp64.Alu64(opcode, rd(word), rn(word), immediate, wide(word), setFlags,
                    !setFlags, true);
        };
    }

    /// `AND`/`ORR`/`EOR`/`ANDS` imediato nunca têm forma SP em `Rd`/`Rn` (D2 da B6.3.1).
    private static WordDecoder<Ir64Op> logical(Ir64AluOp opcode, boolean setFlags) {
        return (word, address) -> {
            long immediate = Aarch64LogicalImmediate.decodeBitMasks(bit(word, N_SHIFT), imms(word), immr(word));
            return new IntegerOp64.Alu64(opcode, rd(word), rn(word), immediate, wide(word), setFlags, false, false);
        };
    }

    private static WordDecoder<Ir64Op> moveWide(Ir64MoveWideOp opcode) {
        return (word, address) -> {
            int shift = ((word >>> MOVE_WIDE_HW_SHIFT) & MOVE_WIDE_HW_MASK) * MOVE_WIDE_HW_UNIT_BITS;
            return new IntegerOp64.MoveWide(opcode, rd(word), (word >>> IMM16_SHIFT) & IMM16_MASK, shift, wide(word));
        };
    }

    /// `SBFM`/`BFM`/`UBFM` a partir dos campos crus — os aliases (`UBFX`/`LSL`/`SXTW`/...) são só
    /// valores de `immr`/`imms` (D2 da B6.3.2).
    private static WordDecoder<Ir64Op> bitfield(Ir64BitfieldOp opcode) {
        return (word, address) -> new IntegerOp64.Bitfield(opcode, rd(word), rn(word), immr(word), imms(word), wide(word));
    }

    /// `imms` é o `lsb`; em 32 bits o `imms<5>` é `0` pela linha.
    private static Ir64Op extract(int word, long address) {
        int rm = (word >>> RM_SHIFT) & REGISTER_MASK;
        return new IntegerOp64.Extract(rd(word), rn(word), rm, imms(word), wide(word));
    }

    private static boolean wide(int word) {
        return bit(word, SF_SHIFT) != 0;
    }

    private static int immr(int word) {
        return (word >>> IMMR_SHIFT) & SIX_BIT_FIELD_MASK;
    }

    private static int imms(int word) {
        return (word >>> IMMS_SHIFT) & SIX_BIT_FIELD_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }

    private static int bit(int word, int shift) {
        return (word >>> shift) & 1;
    }
}
