package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp;
import dev.vitorsilverio.armjitter.ir64.Ir64LogicalShiftType;
import dev.vitorsilverio.armjitter.ir64.Ir64MinMaxOp;
import dev.vitorsilverio.armjitter.ir64.Ir64OneSourceOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64PointerAuthOp;
import dev.vitorsilverio.armjitter.ir64.Ir64ShiftType;

import java.util.List;

/// E15.13 (D5 do épico E15): a classe "Data Processing — Register" inteira (`op0 = x101`, `bit26=0`)
/// do A64 como tabela — antes `Aarch64Decoder#decodeDataProcessingRegister` e 16 sub-decoders com as
/// restrições de campo em `if`/`throw`. Aqui cada restrição é coluna de linha, igual ao `a64.decode`/
/// ARM ARM C4.1.95, e o que não casa nenhuma linha é recusado pelo chamador (G8).
///
/// Layout (bit 31 → 0): `sf op S 1 op1(28) 101 op2(24:21) ...`. Origem das famílias: add/sub
/// deslocado/estendido (B6.3.1), `CSEL` (B6.3.2), `MADD`/`SDIV` (B6.3.3), `CCMP` (B6.8), logical
/// deslocado (B6.9), deslocamento variável (B6.11), 1-source/carry/flags/multiplicação longa (B8.2/B11.7),
/// `PACGA`/`ABS` (B19.6), MTE (B19.14), PAuth de propósito geral (B19.15), `CRC32` (B19.17), CSSC (B19.21).
///
/// **Restrições que viraram linha** — ausentes da versão em cascata, que aceitava 38 787 palavras que o
/// `objdump` dá como não alocadas (medido pela E15.13):
///
/// - 3-source: `op54` (`bits[30:29]`) é sempre `00`; `SMADDL`/`UMADDL`/`SMULH`/`UMULH` exigem `sf=1`;
///   `SMULH`/`UMULH` exigem `o0=0`;
/// - `CCMP`/`CCMN`: `o2` (`bit10`) e `o3` (`bit4`) são `0`;
/// - `SUBP`/`IRG`/`GMI`/`PACGA` exigem `sf=1`; `SUBPS` exige `opcode=000000`.
///
/// `CNT` escalar ganhou a coluna `FEAT_CSSC` (a cascata o aceitava desde ARMv8.0-A, ao contrário de
/// `CTZ`/`ABS`, que são da mesma extensão).
final class DataProcessingRegisterRows {
    private static final int SF_SHIFT = 31;
    private static final int OP_SHIFT = 30;
    private static final int SET_FLAGS_SHIFT = 29;
    private static final int SHIFT_TYPE_SHIFT = 22;
    private static final int TWO_BIT_MASK = 0b11;
    private static final int LOGICAL_OPC_SHIFT = 29;
    private static final int LOGICAL_OPC_ANDS = 0b11;
    private static final int LOGICAL_INVERT_SHIFT = 21;
    private static final int IMM6_SHIFT = 10;
    private static final int SIX_BIT_FIELD_MASK = 0b11_1111;
    private static final int EXTEND_OPTION_SHIFT = 13;
    private static final int EXTEND_IMM3_SHIFT = 10;
    private static final int THREE_BIT_MASK = 0b111;
    private static final int COND_SHIFT = 12;
    private static final int COND_MASK = 0xF;
    private static final int CSEL_ELSE_INC_SHIFT = 10;
    private static final int CCMP_IMM_FORM_SHIFT = 11;
    private static final int NZCV_MASK = 0xF;
    private static final int DIVIDE_SIGNED_SHIFT = 10;
    private static final int TWO_SOURCE_LOW2_SHIFT = 10;
    private static final int CRC32_CASTAGNOLI_SHIFT = 12;
    private static final int CRC32_SIZE_SHIFT = 10;
    private static final int PAUTH_LOW3_MASK = 0b111;
    private static final int PAUTH_LOW3_SHIFT = 10;
    private static final int MULTIPLY_O0_SHIFT = 15;
    private static final int RA_SHIFT = 10;
    private static final int RMIF_IMM6_SHIFT = 15;
    private static final int RMIF_MASK_FIELD_MASK = 0xF;
    /// `SETF8`/`SETF16` avaliam o byte/halfword baixo (ver {@link IntegerOp64.EvaluateIntoFlags#sizeBits}).
    private static final int EVALUATE_FLAGS_SIZE_8 = 8;
    private static final int EVALUATE_FLAGS_SIZE_16 = 16;
    /// `XPACI`/`XPACD` não têm `Rn` (o campo é fixo em `11111`).
    private static final int NO_REGISTER = -1;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;

    private static final Ir64ShiftType[] ADD_SUB_SHIFT_TYPES = {Ir64ShiftType.LSL, Ir64ShiftType.LSR, Ir64ShiftType.ASR};
    private static final Ir64AluOp[] LOGICAL_OPCODES = {Ir64AluOp.AND, Ir64AluOp.ORR, Ir64AluOp.EOR, Ir64AluOp.AND};
    private static final Ir64AluExtendType[] EXTEND_TYPES = {
            Ir64AluExtendType.UXTB, Ir64AluExtendType.UXTH, Ir64AluExtendType.UXTW, Ir64AluExtendType.UXTX,
            Ir64AluExtendType.SXTB, Ir64AluExtendType.SXTH, Ir64AluExtendType.SXTW, Ir64AluExtendType.SXTX};
    /// `else_inv`(bit30):`else_inc`(bit10).
    private static final Ir64ConditionalSelectOp[] CONDITIONAL_SELECT_OPS = {
            Ir64ConditionalSelectOp.CSEL, Ir64ConditionalSelectOp.CSINC,
            Ir64ConditionalSelectOp.CSINV, Ir64ConditionalSelectOp.CSNEG};
    /// `bits[11:10]` de `011000`..`011011`.
    private static final Ir64MinMaxOp[] MIN_MAX_OPS = {Ir64MinMaxOp.SMAX, Ir64MinMaxOp.UMAX, Ir64MinMaxOp.SMIN,
            Ir64MinMaxOp.UMIN};
    /// `bits[11:10]`: `B`/`H`/`W`/`X`.
    private static final int[] CRC32_DATA_WIDTHS = {Byte.SIZE, Short.SIZE, Integer.SIZE, Long.SIZE};
    /// `opcode<2:0>` das 8 formas de propósito geral (`opcode<3>` = `Z`).
    private static final Ir64PointerAuthOp[] POINTER_AUTH_OPS = {
            Ir64PointerAuthOp.PACIA, Ir64PointerAuthOp.PACIB, Ir64PointerAuthOp.PACDA, Ir64PointerAuthOp.PACDB,
            Ir64PointerAuthOp.AUTIA, Ir64PointerAuthOp.AUTIB, Ir64PointerAuthOp.AUTDA, Ir64PointerAuthOp.AUTDB};

    private static final Aarch64Feature CSSC = Aarch64Feature.COMMON_SHORT_SEQUENCE_COMPRESSION;
    private static final Aarch64Feature MTE = Aarch64Feature.MEMORY_TAGGING;
    private static final Aarch64Feature PAUTH = Aarch64Feature.POINTER_AUTHENTICATION;
    private static final Aarch64Feature FLAGM = Aarch64Feature.FLAG_MANIPULATION;

    /// As linhas. Colunas: `sf op S 1 op1 101 op2 ...` (os campos de cada família no comentário).
    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = List.of(
            // logical deslocado — sf opc 01010 shift N Rm imm6 Rn Rd (imm6 < 32 em 32 bits)
            row("1 .. 01010 .. . ..... ...... ..... .....", DataProcessingRegisterRows::logical),
            row("0 .. 01010 .. . ..... 0..... ..... .....", DataProcessingRegisterRows::logical),
            // add/sub deslocado — sf op S 01011 shift 0 Rm imm6 Rn Rd (shift=11 reservado)
            row("1 . . 01011 0. 0 ..... ...... ..... .....", DataProcessingRegisterRows::addSubShifted),
            row("1 . . 01011 10 0 ..... ...... ..... .....", DataProcessingRegisterRows::addSubShifted),
            row("0 . . 01011 0. 0 ..... 0..... ..... .....", DataProcessingRegisterRows::addSubShifted),
            row("0 . . 01011 10 0 ..... 0..... ..... .....", DataProcessingRegisterRows::addSubShifted),
            // add/sub estendido — sf op S 01011 00 1 Rm option imm3 Rn Rd (imm3 ≤ 4)
            row(". . . 01011 00 1 ..... ... 0.. ..... .....", DataProcessingRegisterRows::addSubExtended),
            row(". . . 01011 00 1 ..... ... 100 ..... .....", DataProcessingRegisterRows::addSubExtended),
            // carry — sf op S 11010000 Rm 000000 Rn Rd
            row(". . . 11010000 ..... 000000 ..... .....", DataProcessingRegisterRows::withCarry),
            // RMIF — 1 01 11010000 imm6 00001 Rn 0 mask; SETF8/SETF16 — 0 01 11010000 00000 0x0010 Rn 01101
            row("1 0 1 11010000 ...... 00001 ..... 0 ....", FLAGM, DataProcessingRegisterRows::rotateIntoFlags),
            row("0 0 1 11010000 00000 000010 ..... 01101", FLAGM, evaluateIntoFlags(EVALUATE_FLAGS_SIZE_8)),
            row("0 0 1 11010000 00000 010010 ..... 01101", FLAGM, evaluateIntoFlags(EVALUATE_FLAGS_SIZE_16)),
            // CCMP/CCMN — sf op 1 11010010 Rm|imm5 cond imm 0 Rn 0 nzcv
            row(". . 1 11010010 ..... .... . 0 ..... 0 ....", DataProcessingRegisterRows::conditionalCompare),
            // CSEL/CSINC/CSINV/CSNEG — sf else_inv 0 11010100 Rm cond 0 else_inc Rn Rd
            row(". . 0 11010100 ..... .... 0 . ..... .....", DataProcessingRegisterRows::conditionalSelect),
            // 2-source — sf 0 S 11010110 Rm opcode Rn Rd
            row(". 0 0 11010110 ..... 00001 . ..... .....", DataProcessingRegisterRows::divide),
            row(". 0 0 11010110 ..... 0010 .. ..... .....", DataProcessingRegisterRows::shiftVariable),
            row("0 0 0 11010110 ..... 010 . 0 . ..... .....", Aarch64Feature.CRC32, DataProcessingRegisterRows::crc32),
            row("0 0 0 11010110 ..... 010 . 10 ..... .....", Aarch64Feature.CRC32, DataProcessingRegisterRows::crc32),
            row("1 0 0 11010110 ..... 010 . 11 ..... .....", Aarch64Feature.CRC32, DataProcessingRegisterRows::crc32),
            row("1 0 0 11010110 ..... 000000 ..... .....", MTE, subtractPointer(false)),
            row("1 0 1 11010110 ..... 000000 ..... .....", MTE, subtractPointer(true)),
            row("1 0 0 11010110 ..... 000100 ..... .....", MTE,
                    (word, address) -> new IntegerOp64.InsertRandomTag(rd(word), rn(word), rm(word))),
            row("1 0 0 11010110 ..... 000101 ..... .....", MTE,
                    (word, address) -> new IntegerOp64.TagMaskInsert(rd(word), rn(word), rm(word))),
            row("1 0 0 11010110 ..... 001100 ..... .....", PAUTH,
                    (word, address) -> new IntegerOp64.PointerAuthGeneric(rd(word), rn(word), rm(word))),
            row(". 0 0 11010110 ..... 0110 .. ..... .....", CSSC, DataProcessingRegisterRows::minMax),
            // 1-source — sf 1 0 11010110 opcode2 opcode Rn Rd (REV64 só em 64 bits)
            row(". 1 0 11010110 00000 000000 ..... .....", oneSource(Ir64OneSourceOp.RBIT)),
            row(". 1 0 11010110 00000 000001 ..... .....", oneSource(Ir64OneSourceOp.REV16)),
            row(". 1 0 11010110 00000 000010 ..... .....", oneSource(Ir64OneSourceOp.REV32)),
            row("1 1 0 11010110 00000 000011 ..... .....", oneSource(Ir64OneSourceOp.REV64)),
            row(". 1 0 11010110 00000 000100 ..... .....", oneSource(Ir64OneSourceOp.CLZ)),
            row(". 1 0 11010110 00000 000101 ..... .....", oneSource(Ir64OneSourceOp.CLS)),
            row(". 1 0 11010110 00000 000110 ..... .....", CSSC, oneSource(Ir64OneSourceOp.CTZ)),
            row(". 1 0 11010110 00000 000111 ..... .....", CSSC, oneSource(Ir64OneSourceOp.CNT)),
            row(". 1 0 11010110 00000 001000 ..... .....", CSSC,
                    (word, address) -> new IntegerOp64.AbsGeneral(rd(word), rn(word), wide(word))),
            // PAC*/AUT* — 1 1 0 11010110 00001 00 Z op3 Rn Rd (Z=1 exige Rn=11111); XPACI/XPACD
            row("1 1 0 11010110 00001 00 0 ... ..... .....", PAUTH, DataProcessingRegisterRows::pointerAuth),
            row("1 1 0 11010110 00001 00 1 ... 11111 .....", PAUTH, DataProcessingRegisterRows::pointerAuth),
            row("1 1 0 11010110 00001 010000 11111 .....", PAUTH, stripPointerAuth(Ir64PointerAuthOp.XPACI)),
            row("1 1 0 11010110 00001 010001 11111 .....", PAUTH, stripPointerAuth(Ir64PointerAuthOp.XPACD)),
            // 3-source — sf 00 11011 op31 Rm o0 Ra Rn Rd
            row(". 0 0 11011 000 ..... . ..... ..... .....", DataProcessingRegisterRows::multiplyAccumulate),
            row("1 0 0 11011 001 ..... . ..... ..... .....", multiplyAccumulateLong(true)),
            row("1 0 0 11011 101 ..... . ..... ..... .....", multiplyAccumulateLong(false)),
            row("1 0 0 11011 010 ..... 0 11111 ..... .....", multiplyHigh(true)),
            row("1 0 0 11011 110 ..... 0 11111 ..... .....", multiplyHigh(false))
    );

    private DataProcessingRegisterRows() {
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, Aarch64Feature requires, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, requires, build);
    }

    /// `AND`/`ORR`/`EOR`/`ANDS` (`N=0`) e `BIC`/`ORN`/`EON`/`BICS` (`N=1`); `ROR` é válido aqui (ao
    /// contrário do add/sub). `MOV`/`MVN` registrador são o caminho geral com `Rn=31`.
    private static Ir64Op logical(int word, long address) {
        int opc = (word >>> LOGICAL_OPC_SHIFT) & TWO_BIT_MASK;
        Ir64LogicalShiftType shiftType = Ir64LogicalShiftType.values()[(word >>> SHIFT_TYPE_SHIFT) & TWO_BIT_MASK];
        return new IntegerOp64.LogicalShiftedRegister(LOGICAL_OPCODES[opc], rd(word), rn(word), rm(word), shiftType,
                imm6(word), bit(word, LOGICAL_INVERT_SHIFT) != 0, wide(word), opc == LOGICAL_OPC_ANDS);
    }

    private static Ir64Op addSubShifted(int word, long address) {
        Ir64ShiftType shiftType = ADD_SUB_SHIFT_TYPES[(word >>> SHIFT_TYPE_SHIFT) & TWO_BIT_MASK];
        return new IntegerOp64.AluShiftedRegister(addOrSub(word), rd(word), rn(word), rm(word), shiftType, imm6(word),
                wide(word), setFlags(word));
    }

    /// ARM DDI 0487 C6.2.4/C6.2.339 (extended): `Rn` é sempre `Rn|SP`; `Rd` é `Rd|SP` só sem `S`
    /// (resolvido pelo executor checando o índice, ver {@link IntegerOp64.AluExtendedRegister}).
    private static Ir64Op addSubExtended(int word, long address) {
        Ir64AluExtendType extendType = EXTEND_TYPES[(word >>> EXTEND_OPTION_SHIFT) & THREE_BIT_MASK];
        int shiftAmount = (word >>> EXTEND_IMM3_SHIFT) & THREE_BIT_MASK;
        boolean setFlags = setFlags(word);
        return new IntegerOp64.AluExtendedRegister(addOrSub(word), rd(word), rn(word), rm(word), extendType,
                shiftAmount, wide(word), setFlags, !setFlags);
    }

    private static Ir64Op withCarry(int word, long address) {
        return new IntegerOp64.AluWithCarry(bit(word, OP_SHIFT) != 0, rd(word), rn(word), rm(word), wide(word),
                setFlags(word));
    }

    private static Ir64Op rotateIntoFlags(int word, long address) {
        return new IntegerOp64.RotateIntoFlags(rn(word), (word >>> RMIF_IMM6_SHIFT) & SIX_BIT_FIELD_MASK,
                word & RMIF_MASK_FIELD_MASK);
    }

    private static WordDecoder<Ir64Op> evaluateIntoFlags(int sizeBits) {
        return (word, address) -> new IntegerOp64.EvaluateIntoFlags(rn(word), sizeBits);
    }

    /// Forma registrador (`bit11=0`, `Rm`) e imediato (`bit11=1`, `imm5` no lugar de `Rm`); o campo
    /// ausente vai como `-1`.
    private static Ir64Op conditionalCompare(int word, long address) {
        boolean immediateForm = bit(word, CCMP_IMM_FORM_SHIFT) != 0;
        int field = rm(word);
        return new IntegerOp64.ConditionalCompare(addOrSub(word), rn(word), immediateForm,
                immediateForm ? NO_REGISTER : field, immediateForm ? field : NO_REGISTER, wide(word), condition(word),
                word & NZCV_MASK);
    }

    /// Os aliases (`CSET`/`CINC`/`CNEG`/...) chegam como o opcode real de 4 combinações.
    private static Ir64Op conditionalSelect(int word, long address) {
        int index = bit(word, OP_SHIFT) << 1 | bit(word, CSEL_ELSE_INC_SHIFT);
        return new IntegerOp64.ConditionalSelect(CONDITIONAL_SELECT_OPS[index], rd(word), rn(word), rm(word),
                wide(word), condition(word));
    }

    private static Ir64Op divide(int word, long address) {
        return new IntegerOp64.Divide(bit(word, DIVIDE_SIGNED_SHIFT) != 0, rd(word), rn(word), rm(word), wide(word));
    }

    /// `bits[11:10]` seguem a ordem de {@link Ir64LogicalShiftType} (`LSL`/`LSR`/`ASR`/`ROR`).
    private static Ir64Op shiftVariable(int word, long address) {
        Ir64LogicalShiftType shiftType = Ir64LogicalShiftType.values()[(word >>> TWO_SOURCE_LOW2_SHIFT) & TWO_BIT_MASK];
        return new IntegerOp64.ShiftVariable(rd(word), rn(word), rm(word), shiftType, wide(word));
    }

    /// `C`(bit12) escolhe o polinômio; `size`(`bits[11:10]`) a largura do dado (`X` só com `sf=1`, pela linha).
    private static Ir64Op crc32(int word, long address) {
        int dataWidthBits = CRC32_DATA_WIDTHS[(word >>> CRC32_SIZE_SHIFT) & TWO_BIT_MASK];
        return new IntegerOp64.Crc32(rd(word), rn(word), rm(word), dataWidthBits, bit(word, CRC32_CASTAGNOLI_SHIFT) != 0);
    }

    private static WordDecoder<Ir64Op> subtractPointer(boolean setFlags) {
        return (word, address) -> new IntegerOp64.SubtractPointer(setFlags, rd(word), rn(word), rm(word));
    }

    private static Ir64Op minMax(int word, long address) {
        Ir64MinMaxOp op = MIN_MAX_OPS[(word >>> TWO_SOURCE_LOW2_SHIFT) & TWO_BIT_MASK];
        return new IntegerOp64.MinMaxGeneral(op, rd(word), rn(word), rm(word), wide(word));
    }

    private static WordDecoder<Ir64Op> oneSource(Ir64OneSourceOp op) {
        return (word, address) -> new IntegerOp64.DataProcessing1Source(op, rd(word), rn(word), wide(word));
    }

    /// As formas `Z` (`PACIZA`/…) têm `Rn=31` pela linha e saem com o mesmo `op` da forma com `Rn`.
    private static Ir64Op pointerAuth(int word, long address) {
        Ir64PointerAuthOp op = POINTER_AUTH_OPS[(word >>> PAUTH_LOW3_SHIFT) & PAUTH_LOW3_MASK];
        return new IntegerOp64.PointerAuthInPlace(op, rd(word), rn(word));
    }

    private static WordDecoder<Ir64Op> stripPointerAuth(Ir64PointerAuthOp op) {
        return (word, address) -> new IntegerOp64.PointerAuthInPlace(op, rd(word), NO_REGISTER);
    }

    /// `MUL`/`MNEG` são o caminho geral com `Ra=31`.
    private static Ir64Op multiplyAccumulate(int word, long address) {
        return new IntegerOp64.MultiplyAccumulate(bit(word, MULTIPLY_O0_SHIFT) != 0, rd(word), rn(word), rm(word),
                ra(word), wide(word));
    }

    private static WordDecoder<Ir64Op> multiplyAccumulateLong(boolean signed) {
        return (word, address) -> new IntegerOp64.MultiplyAccumulateLong(bit(word, MULTIPLY_O0_SHIFT) != 0, signed,
                rd(word), rn(word), rm(word), ra(word));
    }

    /// `Ra` é fixo em `11111` pela linha (não é acumulador).
    private static WordDecoder<Ir64Op> multiplyHigh(boolean signed) {
        return (word, address) -> new IntegerOp64.MultiplyHigh(signed, rd(word), rn(word), rm(word));
    }

    private static Ir64AluOp addOrSub(int word) {
        return bit(word, OP_SHIFT) != 0 ? Ir64AluOp.SUB : Ir64AluOp.ADD;
    }

    private static Ir64Condition condition(int word) {
        return Ir64Condition.decode((word >>> COND_SHIFT) & COND_MASK);
    }

    private static boolean wide(int word) {
        return bit(word, SF_SHIFT) != 0;
    }

    private static boolean setFlags(int word) {
        return bit(word, SET_FLAGS_SHIFT) != 0;
    }

    private static int imm6(int word) {
        return (word >>> IMM6_SHIFT) & SIX_BIT_FIELD_MASK;
    }

    private static int ra(int word) {
        return (word >>> RA_SHIFT) & REGISTER_MASK;
    }

    private static int rm(int word) {
        return (word >>> RM_SHIFT) & REGISTER_MASK;
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
