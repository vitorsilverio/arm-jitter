package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15g: o AdvSIMD "vector/scalar × indexed element" (vetorial, prefixo `01111`; escalar, `11111` com `bit30=1`;
/// `bit10=0` nos dois). Antes era `decodeAdvancedSimdIndexedElement` no `Aarch64Decoder` (B8.19, B11.4, B19.5.6,
/// B19.7, B19.11b–d, B19.12, B19.13, B19.20, B19.23).
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) size(23:22) L M Rm(19:16) opcode(15:12) H 0 Rn Rd`.
///
/// O par `size` decide onde estão o registrador e o índice (`a64.decode` do QEMU, `@qrrx_h`/`_s`/`_d`):
///
/// - `H` (`@qrrx_h`): `Rm` de 4 bits (`V0`–`V15`), índice `H:L:M`;
/// - `S` (`@qrrx_s`): `Rm` de 5 bits (`M:Rm`), índice `H:L`;
/// - `D` (`@qrrx_d`): `Rm` de 5 bits, índice `H`, `L=0`, vetorial só `.2d`;
/// - FP8 (`%hlm4`): `Rm` de 3 bits, índice `H:L:M:Rm[3]`.
///
/// Todo `L` livre é escrito em duas linhas (`0`/`1`) e todo `U` livre também: a chave de balde da `advSimdTable` é a
/// interseção das máscaras de todas as linhas (E15.15e), e as outras famílias fixam `bit21` e `bit29`.
///
/// Corrigido na migração (o `objdump` e o `a64.decode` concordam): `BFDOT_vi`, `USDOT_vi` e `SUDOT_vi` são
/// `@qrrx_s` (`Rm` de 5 bits, índice `H:L`) e o `FCMLA_vi` de meia precisão tem `Rm` de 5 bits — a cascata
/// descartava o `M` (e, no `BFDOT_vi`, o `H`).
final class AdvSimdIndexedElementRows {
    private static final int Q_SHIFT = 30;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final int RM_SHIFT = 16;
    /// `Rm` do layout `H` (`bits[19:16]`).
    private static final int RM_H_MASK = 0b1111;
    /// `Rm` do layout FP8 (`bits[18:16]`).
    private static final int RM_FP8_MASK = 0b111;
    private static final int H_SHIFT = 11;
    private static final int L_SHIFT = 21;
    /// `L:M` (`bits[21:20]`).
    private static final int LM_SHIFT = 20;
    private static final int LM_MASK = 0b11;
    /// `L:M:Rm[3]` (`bits[21:19]`), a parte baixa do índice FP8.
    private static final int FP8_INDEX_LOW_SHIFT = 19;
    private static final int FP8_INDEX_LOW_MASK = 0b111;
    private static final int FP8_INDEX_LOW_BITS = 3;
    /// `bit22` — a metade baixa de `idxn` do `FMLALL_sb_vi`.
    private static final int FMLALL_IDXN_LOW_SHIFT = 22;
    /// `rot` (`bits[14:13]`) do `FCMLA_vi`, em unidades de 90°.
    private static final int ROTATION_SHIFT = 13;
    private static final int ROTATION_MASK = 0b11;
    private static final int ROTATION_UNIT_DEGREES = 90;
    private static final String VECTOR_PREFIX = "01111";
    /// Escalar: prefixo `11111` com `bit30=1` (o `Q` das linhas escalares fixa o `1`).
    private static final String SCALAR_PREFIX = "11111";
    private static final String SIZE_H_FP16 = "00";
    private static final String SIZE_H = "01";
    private static final String SIZE_S = "10";
    private static final String SIZE_D = "11";
    private static final int ESZ_HALFWORD = 1;
    private static final int ESZ_WORD = 2;
    private static final int ESZ_DOUBLEWORD = 3;

    static final List<DecodeRow<Ir64Op>> ROWS = rows();

    private AdvSimdIndexedElementRows() {
    }

    private static List<DecodeRow<Ir64Op>> rows() {
        List<DecodeRow<Ir64Op>> rows = new ArrayList<>();
        // ponto flutuante — escalar e vetorial; meia precisão = FEAT_FP16
        fp(rows, "0", "1001", Ir64VectorFpThreeSameOp.MUL);
        fp(rows, "1", "1001", Ir64VectorFpThreeSameOp.MULX);
        fp(rows, "0", "0001", Ir64VectorFpThreeSameOp.MLA);
        fp(rows, "0", "0101", Ir64VectorFpThreeSameOp.MLS);
        // inteiro não alargante
        integer(rows, "0", "1000", Ir64VectorThreeSameOp.MUL, false, null);
        integer(rows, "1", "0000", Ir64VectorThreeSameOp.MLA, false, null);
        integer(rows, "1", "0100", Ir64VectorThreeSameOp.MLS, false, null);
        integer(rows, "0", "1100", Ir64VectorThreeSameOp.SQDMULH, true, null);
        integer(rows, "0", "1101", Ir64VectorThreeSameOp.SQRDMULH, true, null);
        integer(rows, "1", "1101", Ir64VectorThreeSameOp.SQRDMLAH, true, Aarch64Feature.RDM);
        integer(rows, "1", "1111", Ir64VectorThreeSameOp.SQRDMLSH, true, Aarch64Feature.RDM);
        // inteiro alargante — só os saturantes têm escalar
        widening(rows, "0", "1010", Ir64VectorWideningOp.SMULL, false);
        widening(rows, "1", "1010", Ir64VectorWideningOp.UMULL, false);
        widening(rows, "0", "0010", Ir64VectorWideningOp.SMLAL, false);
        widening(rows, "1", "0010", Ir64VectorWideningOp.UMLAL, false);
        widening(rows, "0", "0110", Ir64VectorWideningOp.SMLSL, false);
        widening(rows, "1", "0110", Ir64VectorWideningOp.UMLSL, false);
        widening(rows, "0", "1011", Ir64VectorWideningOp.SQDMULL, true);
        widening(rows, "0", "0011", Ir64VectorWideningOp.SQDMLAL, true);
        widening(rows, "0", "0111", Ir64VectorWideningOp.SQDMLSL, true);
        complexMultiplyAccumulate(rows);
        dotProducts(rows);
        multiplyAddLong(rows);
        fp8(rows);
        return List.copyOf(rows);
    }

    /// Uma linha por valor de `L` (`l = "."` vira `0` e `1`): `0 Q U prefixo size L M Rm opcode H 0 Rn Rd` (`M` sempre livre).
    private static void add(List<DecodeRow<Ir64Op>> rows, String q, String u, String prefix, String size, String l,
            String opcode, String h, Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        for (String lValue : l.equals(".") ? new String[] {"0", "1"} : new String[] {l}) {
            rows.add(DecodeRow.of("0 " + q + " " + u + " " + prefix + " " + size + " " + lValue + " . .... "
                    + opcode + " " + h + " 0 ..... .....", requires, build));
        }
    }

    /// `FMUL`/`FMULX`/`FMLA`/`FMLS`: `H` (meia precisão, `FEAT_FP16`), `S` e `D` (vetorial só `.2d`).
    private static void fp(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorFpThreeSameOp op) {
        add(rows, ".", u, VECTOR_PREFIX, SIZE_H_FP16, ".", opcode, ".", Aarch64Feature.FP16, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, false, q(word), ESZ_HALFWORD, rd(word), rn(word),
                        rmH(word), indexHlm(word)));
        add(rows, "1", u, SCALAR_PREFIX, SIZE_H_FP16, ".", opcode, ".", Aarch64Feature.FP16, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, true, false, ESZ_HALFWORD, rd(word), rn(word),
                        rmH(word), indexHlm(word)));
        add(rows, ".", u, VECTOR_PREFIX, SIZE_S, ".", opcode, ".", null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, false, q(word), ESZ_WORD, rd(word), rn(word),
                        rm(word), indexHl(word)));
        add(rows, "1", u, SCALAR_PREFIX, SIZE_S, ".", opcode, ".", null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, true, false, ESZ_WORD, rd(word), rn(word),
                        rm(word), indexHl(word)));
        add(rows, "1", u, VECTOR_PREFIX, SIZE_D, "0", opcode, ".", null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, false, true, ESZ_DOUBLEWORD, rd(word), rn(word),
                        rm(word), indexH(word)));
        add(rows, "1", u, SCALAR_PREFIX, SIZE_D, "0", opcode, ".", null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSameByElement(op, true, false, ESZ_DOUBLEWORD, rd(word), rn(word),
                        rm(word), indexH(word)));
    }

    /// Inteiro não alargante, `H` e `S`; `scalar` diz se a forma escalar existe.
    private static void integer(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorThreeSameOp op,
            boolean scalar, Aarch64Feature requires) {
        add(rows, ".", u, VECTOR_PREFIX, SIZE_H, ".", opcode, ".", requires, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticThreeSameByElement(op, false, q(word), ESZ_HALFWORD, rd(word),
                        rn(word), rmH(word), indexHlm(word)));
        add(rows, ".", u, VECTOR_PREFIX, SIZE_S, ".", opcode, ".", requires, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticThreeSameByElement(op, false, q(word), ESZ_WORD, rd(word), rn(word),
                        rm(word), indexHl(word)));
        if (scalar) {
            add(rows, "1", u, SCALAR_PREFIX, SIZE_H, ".", opcode, ".", requires, (word, address) ->
                    new AdvSimdIntegerOp64.ArithmeticThreeSameByElement(op, true, false, ESZ_HALFWORD, rd(word),
                            rn(word), rmH(word), indexHlm(word)));
            add(rows, "1", u, SCALAR_PREFIX, SIZE_S, ".", opcode, ".", requires, (word, address) ->
                    new AdvSimdIntegerOp64.ArithmeticThreeSameByElement(op, true, false, ESZ_WORD, rd(word), rn(word),
                            rm(word), indexHl(word)));
        }
    }

    /// Inteiro alargante, `H` e `S` (o tamanho é o do elemento estreito); `scalar` diz se a forma escalar existe.
    private static void widening(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorWideningOp op,
            boolean scalar) {
        add(rows, ".", u, VECTOR_PREFIX, SIZE_H, ".", opcode, ".", null, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticWideningByElement(op, false, q(word), ESZ_HALFWORD, rd(word),
                        rn(word), rmH(word), indexHlm(word)));
        add(rows, ".", u, VECTOR_PREFIX, SIZE_S, ".", opcode, ".", null, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticWideningByElement(op, false, q(word), ESZ_WORD, rd(word), rn(word),
                        rm(word), indexHl(word)));
        if (scalar) {
            add(rows, "1", u, SCALAR_PREFIX, SIZE_H, ".", opcode, ".", null, (word, address) ->
                    new AdvSimdIntegerOp64.ArithmeticWideningByElement(op, true, false, ESZ_HALFWORD, rd(word),
                            rn(word), rmH(word), indexHlm(word)));
            add(rows, "1", u, SCALAR_PREFIX, SIZE_S, ".", opcode, ".", null, (word, address) ->
                    new AdvSimdIntegerOp64.ArithmeticWideningByElement(op, true, false, ESZ_WORD, rd(word), rn(word),
                            rm(word), indexHl(word)));
        }
    }

    /// `FCMLA_vi` (`FEAT_FCMA`, `opcode = 0 rot 1`, `U=1`): `.4h` (índice `L`, `H=0`), `.8h` (índice `H:L`) e
    /// `.4s` (índice `H`, `L=0`) — `Rm` de 5 bits nas três (`a64.decode`, `FCMLA_vi`).
    private static void complexMultiplyAccumulate(List<DecodeRow<Ir64Op>> rows) {
        Aarch64Feature fcma = Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC;
        add(rows, "0", "1", VECTOR_PREFIX, SIZE_H, ".", "0..1", "0", fcma, (word, address) ->
                new AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement(false, ESZ_HALFWORD, rotation(word), rd(word),
                        rn(word), rm(word), bit(word, L_SHIFT)));
        add(rows, "1", "1", VECTOR_PREFIX, SIZE_H, ".", "0..1", ".", fcma, (word, address) ->
                new AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement(true, ESZ_HALFWORD, rotation(word), rd(word),
                        rn(word), rm(word), indexHl(word)));
        add(rows, "1", "1", VECTOR_PREFIX, SIZE_S, "0", "0..1", ".", fcma, (word, address) ->
                new AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement(true, ESZ_WORD, rotation(word), rd(word),
                        rn(word), rm(word), indexH(word)));
    }

    /// `opcode=1111`/`1110`, `@qrrx_s`: `BFDOT_vi` (`FEAT_BF16`), `USDOT_vi`/`SUDOT_vi` (`FEAT_I8MM`, `U=0`, o
    /// `size` separa) e `SDOT_vi`/`UDOT_vi` (`FEAT_DotProd`, o `U` separa).
    private static void dotProducts(List<DecodeRow<Ir64Op>> rows) {
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_H, ".", "1111", ".", Aarch64Feature.BFLOAT16, (word, address) ->
                new AdvSimdFpOp64.FpDotProductBFloat16ByElement(q(word), rd(word), rn(word), rm(word), indexHl(word)));
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_S, ".", "1111", ".", Aarch64Feature.INT8_MATRIX_MULTIPLY,
                (word, address) -> new AdvSimdIntegerOp64.IntegerDotProductByElement(q(word), false, true, rd(word),
                        rn(word), rm(word), indexHl(word)));
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_H_FP16, ".", "1111", ".", Aarch64Feature.INT8_MATRIX_MULTIPLY,
                (word, address) -> new AdvSimdIntegerOp64.IntegerDotProductByElement(q(word), true, false, rd(word),
                        rn(word), rm(word), indexHl(word)));
        for (String u : new String[] {"0", "1"}) {
            boolean signed = u.equals("0");
            add(rows, ".", u, VECTOR_PREFIX, SIZE_S, ".", "1110", ".", Aarch64Feature.DOT_PRODUCT,
                    (word, address) -> new AdvSimdIntegerOp64.IntegerDotProductByElement(q(word), signed, signed,
                            rd(word), rn(word), rm(word), indexHl(word)));
        }
    }

    /// `@qrrx_h`, só vetorial: `FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2` (`FEAT_FHM`, `size=10`, `U` = bit `top` do
    /// `opcode`) e `BFMLALB`/`BFMLALT` (`FEAT_BF16`, `size=11`, `Q` = `top`).
    private static void multiplyAddLong(List<DecodeRow<Ir64Op>> rows) {
        Aarch64Feature fhm = Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG;
        for (String opcode : new String[] {"0000", "0100", "1000", "1100"}) {
            boolean top = opcode.charAt(0) == '1';
            boolean subtract = opcode.charAt(1) == '1';
            add(rows, ".", top ? "1" : "0", VECTOR_PREFIX, SIZE_S, ".", opcode, ".", fhm, (word, address) ->
                    new AdvSimdFpOp64.FpMultiplyAddLongByElement(q(word), top, subtract, rd(word), rn(word), rmH(word),
                            indexHlm(word)));
        }
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_D, ".", "1111", ".", Aarch64Feature.BFLOAT16, (word, address) ->
                new AdvSimdFpOp64.FpMultiplyAddLongBFloat16ByElement(q(word), rd(word), rn(word), rmH(word),
                        indexHlm(word)));
    }

    /// FP8, só vetorial: `FMLALB`/`FMLALT` (`FMLAL_hb_vi`, `size=11`, `U=0`, `idxn = Q`) e `FMLALL*`
    /// (`FMLALL_sb_vi`, `U=1`, `bit23=0`, `idxn = Q:bit22`) com o layout `%hlm4` (`FEAT_FP8FMA`); `FDOT` `.4h`
    /// (`FDOT_hb_vi`, `@qrrx_h`, `FEAT_FP8DOT2`) e `.2s`/`.4s` (`FDOT_sb_vi`, `@qrrx_s`, `FEAT_FP8DOT4`).
    private static void fp8(List<DecodeRow<Ir64Op>> rows) {
        Aarch64Feature fma = Aarch64Feature.FP8_FUSED_MULTIPLY_ADD;
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_D, ".", "0000", ".", fma, (word, address) ->
                new AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement(false, bit(word, Q_SHIFT), rd(word), rn(word),
                        rmFp8(word), indexFp8(word)));
        add(rows, ".", "1", VECTOR_PREFIX, "0.", ".", "1000", ".", fma, (word, address) ->
                new AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement(true,
                        (bit(word, Q_SHIFT) << 1) | bit(word, FMLALL_IDXN_LOW_SHIFT), rd(word), rn(word), rmFp8(word),
                        indexFp8(word)));
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_H, ".", "0000", ".", Aarch64Feature.FP8_DOT_PRODUCT_2WAY,
                (word, address) -> new AdvSimdFpOp64.Fp8DotProductByElement(false, q(word), rd(word), rn(word),
                        rmH(word), indexHlm(word)));
        add(rows, ".", "0", VECTOR_PREFIX, SIZE_H_FP16, ".", "0000", ".", Aarch64Feature.FP8_DOT_PRODUCT_4WAY,
                (word, address) -> new AdvSimdFpOp64.Fp8DotProductByElement(true, q(word), rd(word), rn(word),
                        rm(word), indexHl(word)));
    }

    private static int bit(int word, int shift) {
        return (word >>> shift) & 1;
    }

    private static boolean q(int word) {
        return bit(word, Q_SHIFT) != 0;
    }

    private static int rotation(int word) {
        return ((word >>> ROTATION_SHIFT) & ROTATION_MASK) * ROTATION_UNIT_DEGREES;
    }

    /// Layout `D`: índice `H`.
    private static int indexH(int word) {
        return bit(word, H_SHIFT);
    }

    /// Layout `S`: índice `H:L`.
    private static int indexHl(int word) {
        return (bit(word, H_SHIFT) << 1) | bit(word, L_SHIFT);
    }

    /// Layout `H`: índice `H:L:M`.
    private static int indexHlm(int word) {
        return (bit(word, H_SHIFT) << 2) | ((word >>> LM_SHIFT) & LM_MASK);
    }

    /// Layout FP8 (`%hlm4`): índice `H:L:M:Rm[3]`.
    private static int indexFp8(int word) {
        return (bit(word, H_SHIFT) << FP8_INDEX_LOW_BITS) | ((word >>> FP8_INDEX_LOW_SHIFT) & FP8_INDEX_LOW_MASK);
    }

    /// Layouts `S`/`D`: `Rm` de 5 bits (`M:Rm`).
    private static int rm(int word) {
        return (word >>> RM_SHIFT) & REGISTER_MASK;
    }

    private static int rmH(int word) {
        return (word >>> RM_SHIFT) & RM_H_MASK;
    }

    private static int rmFp8(int word) {
        return (word >>> RM_SHIFT) & RM_FP8_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }
}
