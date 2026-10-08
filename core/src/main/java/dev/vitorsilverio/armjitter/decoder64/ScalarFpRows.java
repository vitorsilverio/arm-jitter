package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64Conversion;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64HalfPrecisionConversion;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64Operation;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64RoundingDirection;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;

import java.util.List;

/// E15.14 (D5 do épico E15): o FP escalar do A64 como tabela — `bits[30:24] = 0011110` (`bit31` = `M` ou `sf`)
/// e o 3-source (`bits[31:24] = 00011111`). Antes era a cascata de prefixos de
/// `Aarch64Decoder#decodeDataProcessingScalarFpSimd` e 10 sub-decoders, com `type` lido por um método que
/// recusava `10`/`11` e 6 `architecture.has(...)`. Aqui `type` e feature são colunas da linha (ARM ARM C4.1.96 /
/// `a64.decode`), e o que não casa nenhuma linha é recusado pelo chamador (G8).
///
/// Origem das famílias: aritmética, imediato e `FCMP` (B6.5.2/B6.5.3), 2/3-source e `FSQRT` (B8.4),
/// `FCSEL`/`FCCMP`/`FRINT*`/conversões/`FMOV` geral (B8.5), `FMOV` `D[1]` (B19.6), `BFCVT` (B19.7), `FRINT32/64`
/// (B19.18), `FCVT`/`FMOV` de meia precisão (B19.26), `FJCVTZS` (B19.29), `M`/`sf` reservados (E15.9c).
///
/// As formas de meia precisão da aritmética (`type=11` fora de `FCVT`/`FMOV`) ainda não existem e ficam fora da
/// tabela (achado da E15.14).
final class ScalarFpRows {
    private static final int SF_SHIFT = 31;
    private static final int TYPE_SHIFT = 22;
    private static final int THREE_SOURCE_O1_SHIFT = 21;
    private static final int THREE_SOURCE_O0_SHIFT = 15;
    private static final int RA_SHIFT = 10;
    private static final int TWO_SOURCE_OPCODE_SHIFT = 12;
    private static final int TWO_SOURCE_OPCODE_MASK = 0b111;
    private static final int IMM8_SHIFT = 13;
    private static final int IMM8_MASK = 0xFF;
    private static final int COMPARE_E_SHIFT = 4;
    private static final int COMPARE_ZERO_SHIFT = 3;
    private static final int CCMP_E_SHIFT = 4;
    private static final int NZCV_MASK = 0xF;
    private static final int COND_SHIFT = 12;
    private static final int COND_MASK = 0xF;
    private static final int SCALE_SHIFT = 10;
    private static final int SCALE_MASK = 0b11_1111;
    /// A escala de 32 bits usa só `bits[14:10]` (`bit15` é fixo em `1` pela linha).
    private static final int SCALE_NARROW_MASK = 0b1_1111;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `FCMP #0.0` não lê `Rm`.
    private static final int NO_COMPARE_OPERAND = 0;
    /// `FMOV`/`FABS`/`FNEG`/`FSQRT` são `FpOp64.Alu` com um operando só, em `vm`.
    private static final int UNUSED_OPERAND = 0;

    /// `opcode` (`bits[14:12]`) do 2-source com `bit15=0`; `bit15=1` só tem `FNMUL` (`1000`).
    private static final Fp64Operation[] TWO_SOURCE_OPS = {
            Fp64Operation.MUL, Fp64Operation.DIV, Fp64Operation.ADD, Fp64Operation.SUB,
            Fp64Operation.MAX, Fp64Operation.MIN, Fp64Operation.MAXNM, Fp64Operation.MINNM};

    private static final Aarch64Feature FP16 = Aarch64Feature.FP16;
    private static final Aarch64Feature FRINTTS = Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL;

    /// As linhas. Colunas: `M|sf 0 S 11110 type 1 ...` (os campos de cada família no comentário); `type` `0.` é
    /// simples/dupla (`bit22` = dupla).
    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = List.of(
            // 3-source — 0 0 0 11111 type o1 Rm o0 Ra Rn Rd
            row("0 0 0 11111 0. . ..... . ..... ..... .....", ScalarFpRows::multiplyAdd),
            // ponto fixo — sf 0 0 11110 type 0 rmode:opcode scale Rn Rd (32 bits: scale ≥ 32, bit15=1)
            fixedPointWide("00010", true, true),
            fixedPointNarrow("00010", true, true),
            fixedPointWide("00011", true, false),
            fixedPointNarrow("00011", true, false),
            fixedPointWide("11000", false, true),
            fixedPointNarrow("11000", false, true),
            fixedPointWide("11001", false, false),
            fixedPointNarrow("11001", false, false),
            // inteiro — sf 0 0 11110 type 1 rmode:opcode 000000 Rn Rd
            row(". 0 0 11110 0. 1 00010 000000 ..... .....", integerConvert(true, true, Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            row(". 0 0 11110 0. 1 00011 000000 ..... .....", integerConvert(true, false, Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            row(". 0 0 11110 0. 1 00000 000000 ..... .....", integerConvert(false, true, Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            row(". 0 0 11110 0. 1 00001 000000 ..... .....", integerConvert(false, false, Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            row(". 0 0 11110 0. 1 01000 000000 ..... .....", integerConvert(false, true, Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY)),
            row(". 0 0 11110 0. 1 01001 000000 ..... .....", integerConvert(false, false, Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY)),
            row(". 0 0 11110 0. 1 10000 000000 ..... .....", integerConvert(false, true, Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY)),
            row(". 0 0 11110 0. 1 10001 000000 ..... .....", integerConvert(false, false, Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY)),
            row(". 0 0 11110 0. 1 11000 000000 ..... .....", integerConvert(false, true, Fp64RoundingDirection.TOWARD_ZERO)),
            row(". 0 0 11110 0. 1 11001 000000 ..... .....", integerConvert(false, false, Fp64RoundingDirection.TOWARD_ZERO)),
            row(". 0 0 11110 0. 1 00100 000000 ..... .....", integerConvert(false, true, Fp64RoundingDirection.NEAREST_TIES_AWAY)),
            row(". 0 0 11110 0. 1 00101 000000 ..... .....", integerConvert(false, false, Fp64RoundingDirection.NEAREST_TIES_AWAY)),
            row("0 0 0 11110 01 1 11110 000000 ..... .....", Aarch64Feature.JAVASCRIPT_CONVERT,
                    (word, address) -> new FpOp64.JavascriptConvert(rd(word), rn(word))),
            // FMOV geral — W↔S, X↔D, X↔D[1] e H↔W/X (sf livre: resultado idêntico)
            row("0 0 0 11110 00 1 00110 000000 ..... .....", generalRegisterMove(false)),
            row("0 0 0 11110 00 1 00111 000000 ..... .....", generalRegisterMove(true)),
            row("1 0 0 11110 01 1 00110 000000 ..... .....", generalRegisterMove(false)),
            row("1 0 0 11110 01 1 00111 000000 ..... .....", generalRegisterMove(true)),
            row("1 0 0 11110 10 1 01110 000000 ..... .....", highHalfMove(false)),
            row("1 0 0 11110 10 1 01111 000000 ..... .....", highHalfMove(true)),
            row(". 0 0 11110 11 1 00110 000000 ..... .....", FP16, halfPrecisionGeneralRegisterMove(false)),
            row(". 0 0 11110 11 1 00111 000000 ..... .....", FP16, halfPrecisionGeneralRegisterMove(true)),
            // imediato — 0 0 0 11110 type 1 imm8 100 00000 Rd
            row("0 0 0 11110 0. 1 ........ 100 00000 .....", ScalarFpRows::moveImmediate),
            // FCMP/FCMPE — 0 0 0 11110 type 1 Rm 001000 Rn e z 000
            row("0 0 0 11110 0. 1 ..... 001000 ..... ..000", ScalarFpRows::compare),
            // 1-source — 0 0 0 11110 type 1 opcode 10000 Rn Rd
            row("0 0 0 11110 0. 1 000000 10000 ..... .....", unary(Fp64Operation.MOV)),
            row("0 0 0 11110 0. 1 000001 10000 ..... .....", unary(Fp64Operation.ABS)),
            row("0 0 0 11110 0. 1 000010 10000 ..... .....", unary(Fp64Operation.NEG)),
            row("0 0 0 11110 0. 1 000011 10000 ..... .....", unary(Fp64Operation.SQRT)),
            // FCVT — type é a fonte, opcode 0001xx o destino (00 S, 01 D, 11 H)
            row("0 0 0 11110 01 1 000100 10000 ..... .....", convert(Fp64Conversion.F64_TO_F32)),
            row("0 0 0 11110 00 1 000101 10000 ..... .....", convert(Fp64Conversion.F32_TO_F64)),
            row("0 0 0 11110 00 1 000111 10000 ..... .....", FP16, convertHalf(Fp64HalfPrecisionConversion.SINGLE_TO_HALF)),
            row("0 0 0 11110 01 1 000111 10000 ..... .....", FP16, convertHalf(Fp64HalfPrecisionConversion.DOUBLE_TO_HALF)),
            row("0 0 0 11110 11 1 000100 10000 ..... .....", FP16, convertHalf(Fp64HalfPrecisionConversion.HALF_TO_SINGLE)),
            row("0 0 0 11110 11 1 000101 10000 ..... .....", FP16, convertHalf(Fp64HalfPrecisionConversion.HALF_TO_DOUBLE)),
            row("0 0 0 11110 01 1 000110 10000 ..... .....", Aarch64Feature.BFLOAT16,
                    (word, address) -> new FpOp64.ConvertToBf16(rd(word), rn(word))),
            // FRINTX/FRINTI: mesma direção de FRINTN (FPCR.RMode não modelado em A64, ver FpOp64.Round)
            row("0 0 0 11110 0. 1 001000 10000 ..... .....", round(Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            row("0 0 0 11110 0. 1 001001 10000 ..... .....", round(Fp64RoundingDirection.TOWARD_POSITIVE_INFINITY)),
            row("0 0 0 11110 0. 1 001010 10000 ..... .....", round(Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY)),
            row("0 0 0 11110 0. 1 001011 10000 ..... .....", round(Fp64RoundingDirection.TOWARD_ZERO)),
            row("0 0 0 11110 0. 1 001100 10000 ..... .....", round(Fp64RoundingDirection.NEAREST_TIES_AWAY)),
            row("0 0 0 11110 0. 1 001110 10000 ..... .....", round(Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            row("0 0 0 11110 0. 1 001111 10000 ..... .....", round(Fp64RoundingDirection.NEAREST_TIES_EVEN)),
            // FRINT32/64: Z sempre trunca; X degenera para NEAREST_TIES_EVEN (mesma decisão de FRINTX)
            row("0 0 0 11110 0. 1 010000 10000 ..... .....", FRINTTS, roundRangeLimited(Fp64RoundingDirection.TOWARD_ZERO, false)),
            row("0 0 0 11110 0. 1 010001 10000 ..... .....", FRINTTS, roundRangeLimited(Fp64RoundingDirection.NEAREST_TIES_EVEN, false)),
            row("0 0 0 11110 0. 1 010010 10000 ..... .....", FRINTTS, roundRangeLimited(Fp64RoundingDirection.TOWARD_ZERO, true)),
            row("0 0 0 11110 0. 1 010011 10000 ..... .....", FRINTTS, roundRangeLimited(Fp64RoundingDirection.NEAREST_TIES_EVEN, true)),
            // 2-source — 0 0 0 11110 type 1 Rm opcode 10 Rn Rd (opcode 1001-1111 reservado)
            row("0 0 0 11110 0. 1 ..... 0 ... 10 ..... .....", ScalarFpRows::twoSource),
            row("0 0 0 11110 0. 1 ..... 1000 10 ..... .....", (word, address) -> alu(Fp64Operation.NMUL, word)),
            // FCSEL — 0 0 0 11110 type 1 Rm cond 11 Rn Rd; FCCMP/FCCMPE — ... cond 01 Rn e nzcv
            row("0 0 0 11110 0. 1 ..... .... 11 ..... .....", ScalarFpRows::conditionalSelect),
            row("0 0 0 11110 0. 1 ..... .... 01 ..... .....", ScalarFpRows::conditionalCompare)
    );

    private ScalarFpRows() {
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, null, build);
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String pattern, Aarch64Feature requires, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, requires, build);
    }

    /// `neg_a = o1` e `neg_n = o1 XOR o0` (conferido contra `TRANS(FNMADD, do_fmadd, a, true, true)` do QEMU:
    /// `FMADD` 0,0 · `FMSUB` 0,1 → `neg_n` · `FNMADD` 1,0 → os dois · `FNMSUB` 1,1 → só `neg_a`).
    private static Ir64Op multiplyAdd(int word, long address) {
        boolean negateAddend = bit(word, THREE_SOURCE_O1_SHIFT) != 0;
        boolean negateProduct = negateAddend ^ (bit(word, THREE_SOURCE_O0_SHIFT) != 0);
        return new FpOp64.MultiplyAdd(doublePrecision(word), negateAddend, negateProduct, rd(word), rn(word),
                rm(word), (word >>> RA_SHIFT) & REGISTER_MASK);
    }

    /// `sf=1`: escala de 6 bits, `fbits = 64 - scale`. `Z` já é o nome: sempre trunca para zero.
    private static DecodeRow<Aarch64Feature, Ir64Op> fixedPointWide(String opcode, boolean toFloat, boolean signed) {
        return row("1 0 0 11110 0. 0 " + opcode + " ...... ..... .....", (word, address) ->
                fixedPoint(word, toFloat, signed, Long.SIZE - ((word >>> SCALE_SHIFT) & SCALE_MASK)));
    }

    /// `sf=0`: `bit15=1` (escala ≥ 32) e `fbits = 32 - scale<4:0>` (`%fcvt_shift32`).
    private static DecodeRow<Aarch64Feature, Ir64Op> fixedPointNarrow(String opcode, boolean toFloat, boolean signed) {
        return row("0 0 0 11110 0. 0 " + opcode + " 1..... ..... .....", (word, address) ->
                fixedPoint(word, toFloat, signed, Integer.SIZE - ((word >>> SCALE_SHIFT) & SCALE_NARROW_MASK)));
    }

    private static Ir64Op fixedPoint(int word, boolean toFloat, boolean signed, int fractionBits) {
        return integerConvertOp(word, toFloat, signed, Fp64RoundingDirection.TOWARD_ZERO, fractionBits);
    }

    private static WordDecoder<Ir64Op> integerConvert(boolean toFloat, boolean signed, Fp64RoundingDirection rounding) {
        return (word, address) -> integerConvertOp(word, toFloat, signed, rounding, 0);
    }

    /// `Rn`/`Rd` trocam de banco conforme a direção: o registrador FP é o destino de `SCVTF`/`UCVTF`.
    private static Ir64Op integerConvertOp(int word, boolean toFloat, boolean signed, Fp64RoundingDirection rounding,
            int fractionBits) {
        int fpReg = toFloat ? rd(word) : rn(word);
        int gpReg = toFloat ? rn(word) : rd(word);
        return new FpOp64.IntegerConvert(toFloat, signed, rounding, doublePrecision(word), wide(word), fractionBits,
                fpReg, gpReg);
    }

    private static WordDecoder<Ir64Op> generalRegisterMove(boolean toFloat) {
        return (word, address) -> new FpOp64.GeneralRegisterMove(toFloat, wide(word),
                toFloat ? rd(word) : rn(word), toFloat ? rn(word) : rd(word));
    }

    private static WordDecoder<Ir64Op> highHalfMove(boolean toFloat) {
        return (word, address) -> new FpOp64.HighHalfMove(toFloat,
                toFloat ? rd(word) : rn(word), toFloat ? rn(word) : rd(word));
    }

    private static WordDecoder<Ir64Op> halfPrecisionGeneralRegisterMove(boolean toFloat) {
        return (word, address) -> new FpOp64.HalfPrecisionGeneralRegisterMove(toFloat,
                toFloat ? rd(word) : rn(word), toFloat ? rn(word) : rd(word));
    }

    /// O imediato de 8 bits é expandido aqui (`VFPExpandImm`), nunca no executor.
    private static Ir64Op moveImmediate(int word, long address) {
        boolean doublePrecision = doublePrecision(word);
        int imm8 = (word >>> IMM8_SHIFT) & IMM8_MASK;
        return new FpOp64.MoveImmediate(doublePrecision, rd(word),
                Aarch64Decoder.expandFpImmediate(imm8, doublePrecision));
    }

    private static Ir64Op compare(int word, long address) {
        boolean compareWithZero = bit(word, COMPARE_ZERO_SHIFT) != 0;
        return new FpOp64.Compare(doublePrecision(word), compareWithZero, bit(word, COMPARE_E_SHIFT) != 0, rn(word),
                compareWithZero ? NO_COMPARE_OPERAND : rm(word));
    }

    private static WordDecoder<Ir64Op> unary(Fp64Operation op) {
        return (word, address) -> new FpOp64.Alu(op, doublePrecision(word), rd(word), UNUSED_OPERAND, rn(word));
    }

    private static WordDecoder<Ir64Op> convert(Fp64Conversion conversion) {
        return (word, address) -> new FpOp64.Convert(conversion, rd(word), rn(word));
    }

    private static WordDecoder<Ir64Op> convertHalf(Fp64HalfPrecisionConversion conversion) {
        return (word, address) -> new FpOp64.ConvertHalfPrecision(conversion, rd(word), rn(word));
    }

    private static WordDecoder<Ir64Op> round(Fp64RoundingDirection direction) {
        return (word, address) -> new FpOp64.Round(direction, doublePrecision(word), rd(word), rn(word));
    }

    private static WordDecoder<Ir64Op> roundRangeLimited(Fp64RoundingDirection direction, boolean sixtyFourBit) {
        return (word, address) -> new FpOp64.RoundRangeLimited(direction, sixtyFourBit, doublePrecision(word),
                rd(word), rn(word));
    }

    private static Ir64Op twoSource(int word, long address) {
        return alu(TWO_SOURCE_OPS[(word >>> TWO_SOURCE_OPCODE_SHIFT) & TWO_SOURCE_OPCODE_MASK], word);
    }

    private static Ir64Op alu(Fp64Operation op, int word) {
        return new FpOp64.Alu(op, doublePrecision(word), rd(word), rn(word), rm(word));
    }

    private static Ir64Op conditionalSelect(int word, long address) {
        return new FpOp64.ConditionalSelect(doublePrecision(word), rd(word), rn(word), rm(word), condition(word));
    }

    private static Ir64Op conditionalCompare(int word, long address) {
        return new FpOp64.ConditionalCompare(doublePrecision(word), bit(word, CCMP_E_SHIFT) != 0, rn(word), rm(word),
                condition(word), word & NZCV_MASK);
    }

    private static Ir64Condition condition(int word) {
        return Ir64Condition.decode((word >>> COND_SHIFT) & COND_MASK);
    }

    /// `type` `00` simples, `01` dupla — as linhas que chamam isto fixam `bit23=0`.
    private static boolean doublePrecision(int word) {
        return bit(word, TYPE_SHIFT) != 0;
    }

    private static boolean wide(int word) {
        return bit(word, SF_SHIFT) != 0;
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
