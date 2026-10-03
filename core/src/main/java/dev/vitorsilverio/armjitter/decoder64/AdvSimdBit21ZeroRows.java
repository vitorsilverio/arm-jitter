package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decoder64.DecodeRow.WordDecoder;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;

import java.util.List;

/// E15.9 (piloto de {@link DecodeTable}): as formas com feature do espaço AdvSIMD `bit21=0` de
/// `Aarch64Decoder#decodeAdvancedSimdInteger` — antes uma cascata de 8 `if (has(...))` e 10
/// sub-decoders que devolviam `null`, com a exclusão mútua só afirmada em comentário. Aqui cada
/// encoding é uma linha e a exclusão mútua é verificada por máquina (`AdvSimdBit21ZeroRowsTest`).
///
/// Layout das linhas (bit 31 → 0): `b31 Q U prefixo(28:24) a(23) b22 b21 Rm opcode(15:11) b10 Rn Rd`.
/// Prefixo `01110` = vetorial; `11110` com `bit30=1` = escalar (D-only, sem `Q`). O que não cai
/// em nenhuma linha segue para EXT/permute/TBL/copy/SHA no chamador.
///
/// `bit31` é `0` em toda linha: AdvSIMD nunca tem `bit31=1` (E15.9b; o chamador também recusa antes
/// de chegar aqui, a coluna fixa só deixa o padrão igual ao do ARM ARM).
///
/// Origem de cada família (os achados de encoding estão no `## Resultado` de cada task):
///
/// - **B11.4 `FEAT_RDM`** — `SQRDMLAH`/`SQRDMLSH`: mesmo opcode de `ADD_v`/`SUB_v`, separados por
///   `bit21=0`; só `esz` `H`/`S` (`B`/`D` reservados, G8).
/// - **B19.5.5 `FEAT_FP16`** — "three same (FP)" de meia precisão: a tabela `(U, a, opcode)` da
///   forma `_sd` com `opcode_h = 00ooo` (`opcode_sd = 11ooo`). `bits[15:14]=00` fixos: a versão em
///   cascata ignorava o `bit14` e aceitava `01ooo` (bug G8 corrigido na E15.9, `0x4e405400` é
///   `undefined` no `objdump`). Escalar só para as 9 operações com forma escalar real.
///   **E15.9b**: as pareadas `FADDP`/`FMAXP`/`FMINP`/`FMAXNMP`/`FMINNMP` `_h` (`U=1`, `a` separa
///   MAX de MIN), que antes caíam no copy e saíam como `INS`.
/// - **B19.11 `FEAT_FP8`** — `FCVTN_bh`/`FCVTN_bs` (o `bit22` é a largura de ORIGEM, não um
///   `sz`); **B19.11e** `FSCALE_h`.
/// - **B19.24 `FEAT_FAMINMAX`** — `FAMAX_h` (`U=0`)/`FAMIN_h` (`U=1`).
/// - **B19.11b `FEAT_FP8FMA`** — `FMLAL_hb_v`/`FMLALL_sb_v`: o `bit30` é `idxn`, não `Q` (o QEMU
///   fixa `oprsz=16`); em `FMLALL` o `idxn` tem 2 bits (`bit30:bit22`).
/// - **B19.11c/d `FEAT_FP8DOT2`/`FEAT_FP8DOT4`** — `FDOT_hb_v` (`b22=1`)/`FDOT_sb_v` (`b22=0`), com
///   `Q` normal; mesmo opcode de `FMLAL_hb_v`, que se separa por `a`.
/// - **B19.20 `FEAT_FCMA`** — `FCMLA` (opcode `110rr`, rotação `rr × 90°`)/`FCADD` 90°/270°;
///   `esz=0` reservado e `esz=3` só com `Q=1` (par complexo de dupla não cabe em 64 bits).
final class AdvSimdBit21ZeroRows {
    private static final int SCALAR_BIT_SHIFT = 28;
    private static final int Q_SHIFT = 30;
    private static final int SIZE_SHIFT = 22;
    private static final int SIZE_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final int OPCODE_SHIFT = 11;
    private static final int FCMA_ROTATION_MASK = 0b11;
    private static final int ROTATION_UNIT_DEGREES = 90;
    private static final int FCADD_270_DEGREES = 3 * ROTATION_UNIT_DEGREES;
    private static final int ESZ_HALFWORD = 1;

    private static final Aarch64Feature RDM = Aarch64Feature.RDM;
    private static final Aarch64Feature FP16 = Aarch64Feature.FP16;
    private static final Aarch64Feature FP8 = Aarch64Feature.FP8;
    private static final Aarch64Feature FAMINMAX = Aarch64Feature.FP_ABSOLUTE_MAX_MIN;
    private static final Aarch64Feature FP8FMA = Aarch64Feature.FP8_FUSED_MULTIPLY_ADD;
    private static final Aarch64Feature FP8DOT2 = Aarch64Feature.FP8_DOT_PRODUCT_2WAY;
    private static final Aarch64Feature FP8DOT4 = Aarch64Feature.FP8_DOT_PRODUCT_4WAY;
    private static final Aarch64Feature FCMA = Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC;

    /// As 53 linhas do piloto. Colunas do padrão: `b31 Q U prefixo a b22 b21 Rm opcode b10 Rn Rd`.
    static final List<DecodeRow<Ir64Op>> ROWS = List.of(
            // RDM — vetorial e escalar, esz H/S
            row("0 . 1 01110 01 0 ..... 10000 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLAH)),
            row("0 . 1 01110 10 0 ..... 10000 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLAH)),
            row("0 . 1 01110 01 0 ..... 10001 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLSH)),
            row("0 . 1 01110 10 0 ..... 10001 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLSH)),
            row("0 1 1 11110 01 0 ..... 10000 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLAH)),
            row("0 1 1 11110 10 0 ..... 10000 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLAH)),
            row("0 1 1 11110 01 0 ..... 10001 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLSH)),
            row("0 1 1 11110 10 0 ..... 10001 1 ..... .....", RDM, rdm(Ir64VectorThreeSameOp.SQRDMLSH)),
            // FP16 three same — vetorial (U, a, opcode 00ooo)
            row("0 . 0 01110 0 1 0 ..... 00010 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.ADD)),
            row("0 . 0 01110 1 1 0 ..... 00010 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.SUB)),
            row("0 . 1 01110 1 1 0 ..... 00010 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.ABD)),
            row("0 . 1 01110 0 1 0 ..... 00111 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.DIV)),
            row("0 . 0 01110 0 1 0 ..... 00111 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.RECPS)),
            row("0 . 0 01110 1 1 0 ..... 00111 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.RSQRTS)),
            row("0 . 1 01110 0 1 0 ..... 00011 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MUL)),
            row("0 . 0 01110 0 1 0 ..... 00011 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MULX)),
            row("0 . 0 01110 0 1 0 ..... 00110 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MAX)),
            row("0 . 0 01110 1 1 0 ..... 00110 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MIN)),
            row("0 . 0 01110 0 1 0 ..... 00000 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MAXNM)),
            row("0 . 0 01110 1 1 0 ..... 00000 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MINNM)),
            row("0 . 0 01110 0 1 0 ..... 00001 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MLA)),
            row("0 . 0 01110 1 1 0 ..... 00001 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MLS)),
            row("0 . 0 01110 0 1 0 ..... 00100 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.CMEQ)),
            row("0 . 1 01110 0 1 0 ..... 00100 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.CMGE)),
            row("0 . 1 01110 1 1 0 ..... 00100 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.CMGT)),
            row("0 . 1 01110 0 1 0 ..... 00101 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.FACGE)),
            row("0 . 1 01110 1 1 0 ..... 00101 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.FACGT)),
            // FP16 three same pairwise — só vetorial (a escalar mora em "scalar pairwise", bit10=0)
            row("0 . 1 01110 0 1 0 ..... 00010 1 ..... .....", FP16, fp16Pairwise(Ir64VectorFpPairwiseOp.ADD)),
            row("0 . 1 01110 0 1 0 ..... 00110 1 ..... .....", FP16, fp16Pairwise(Ir64VectorFpPairwiseOp.MAX)),
            row("0 . 1 01110 1 1 0 ..... 00110 1 ..... .....", FP16, fp16Pairwise(Ir64VectorFpPairwiseOp.MIN)),
            row("0 . 1 01110 0 1 0 ..... 00000 1 ..... .....", FP16, fp16Pairwise(Ir64VectorFpPairwiseOp.MAXNM)),
            row("0 . 1 01110 1 1 0 ..... 00000 1 ..... .....", FP16, fp16Pairwise(Ir64VectorFpPairwiseOp.MINNM)),
            // FP16 three same — escalar (só as operações com forma escalar real)
            row("0 1 0 11110 0 1 0 ..... 00011 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.MULX)),
            row("0 1 1 11110 1 1 0 ..... 00010 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.ABD)),
            row("0 1 0 11110 0 1 0 ..... 00111 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.RECPS)),
            row("0 1 0 11110 1 1 0 ..... 00111 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.RSQRTS)),
            row("0 1 0 11110 0 1 0 ..... 00100 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.CMEQ)),
            row("0 1 1 11110 0 1 0 ..... 00100 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.CMGE)),
            row("0 1 1 11110 1 1 0 ..... 00100 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.CMGT)),
            row("0 1 1 11110 0 1 0 ..... 00101 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.FACGE)),
            row("0 1 1 11110 1 1 0 ..... 00101 1 ..... .....", FP16, fp16(Ir64VectorFpThreeSameOp.FACGT)),
            // FP8: FCVTN_bh/_bs (b22 = largura de origem) e FSCALE_h
            row("0 . 0 01110 0 . 0 ..... 11110 1 ..... .....", FP8, AdvSimdBit21ZeroRows::convertToFp8),
            row("0 . 1 01110 1 1 0 ..... 00111 1 ..... .....", FP8, AdvSimdBit21ZeroRows::scaleHalf),
            // FAMINMAX: FAMAX_h (U=0) / FAMIN_h (U=1)
            row("0 . 0 01110 1 1 0 ..... 00011 1 ..... .....", FAMINMAX, absoluteMaxMin(true)),
            row("0 . 1 01110 1 1 0 ..... 00011 1 ..... .....", FAMINMAX, absoluteMaxMin(false)),
            // FP8FMA: FMLAL_hb_v (idxn = bit30) / FMLALL_sb_v (idxn = bit30:bit22)
            row("0 . 0 01110 1 1 0 ..... 11111 1 ..... .....", FP8FMA, AdvSimdBit21ZeroRows::multiplyAddHalf),
            row("0 . 0 01110 0 . 0 ..... 11000 1 ..... .....", FP8FMA, AdvSimdBit21ZeroRows::multiplyAddSingle),
            // FP8DOT2 / FP8DOT4: FDOT_hb_v (b22=1) / FDOT_sb_v (b22=0)
            row("0 . 0 01110 0 1 0 ..... 11111 1 ..... .....", FP8DOT2, dot(false)),
            row("0 . 0 01110 0 0 0 ..... 11111 1 ..... .....", FP8DOT4, dot(true)),
            // FCMA: esz 01 / 10 / 11 (este só com Q=1)
            row("0 . 1 01110 01 0 ..... 110.. 1 ..... .....", FCMA, AdvSimdBit21ZeroRows::complexMultiplyAccumulate),
            row("0 . 1 01110 10 0 ..... 110.. 1 ..... .....", FCMA, AdvSimdBit21ZeroRows::complexMultiplyAccumulate),
            row("0 1 1 01110 11 0 ..... 110.. 1 ..... .....", FCMA, AdvSimdBit21ZeroRows::complexMultiplyAccumulate),
            row("0 . 1 01110 01 0 ..... 11100 1 ..... .....", FCMA, complexAdd(ROTATION_UNIT_DEGREES)),
            row("0 . 1 01110 10 0 ..... 11100 1 ..... .....", FCMA, complexAdd(ROTATION_UNIT_DEGREES)),
            row("0 1 1 01110 11 0 ..... 11100 1 ..... .....", FCMA, complexAdd(ROTATION_UNIT_DEGREES)),
            row("0 . 1 01110 01 0 ..... 11110 1 ..... .....", FCMA, complexAdd(FCADD_270_DEGREES)),
            row("0 . 1 01110 10 0 ..... 11110 1 ..... .....", FCMA, complexAdd(FCADD_270_DEGREES)),
            row("0 1 1 01110 11 0 ..... 11110 1 ..... .....", FCMA, complexAdd(FCADD_270_DEGREES))
    );

    private AdvSimdBit21ZeroRows() {
    }

    private static DecodeRow<Ir64Op> row(String pattern, Aarch64Feature requires, WordDecoder<Ir64Op> build) {
        return DecodeRow.of(pattern, requires, build);
    }

    private static WordDecoder<Ir64Op> rdm(Ir64VectorThreeSameOp op) {
        return word -> new AdvSimdIntegerOp64.ArithmeticThreeSame(op, scalar(word), q(word), esz(word),
                rd(word), rn(word), rm(word));
    }

    private static WordDecoder<Ir64Op> fp16(Ir64VectorFpThreeSameOp op) {
        return word -> new AdvSimdFpOp64.FpArithmeticThreeSame(op, scalar(word), q(word), ESZ_HALFWORD,
                rd(word), rn(word), rm(word));
    }

    private static WordDecoder<Ir64Op> fp16Pairwise(Ir64VectorFpPairwiseOp op) {
        return word -> new AdvSimdFpOp64.FpArithmeticPairwise(op, false, q(word), ESZ_HALFWORD,
                rd(word), rn(word), rm(word));
    }

    private static Ir64Op convertToFp8(int word) {
        return new AdvSimdFpOp64.FpConvertToFp8(bit(word, SIZE_SHIFT) != 0, q(word), rd(word), rn(word), rm(word));
    }

    private static Ir64Op scaleHalf(int word) {
        return new AdvSimdFpOp64.FpScaleByInt(q(word), ESZ_HALFWORD, rd(word), rn(word), rm(word));
    }

    private static WordDecoder<Ir64Op> absoluteMaxMin(boolean max) {
        return word -> new AdvSimdFpOp64.FpAbsoluteMaxMin(max, q(word), ESZ_HALFWORD, rd(word), rn(word), rm(word));
    }

    private static Ir64Op multiplyAddHalf(int word) {
        return new AdvSimdFpOp64.Fp8FusedMultiplyAddLong(false, bit(word, Q_SHIFT), rd(word), rn(word), rm(word));
    }

    private static Ir64Op multiplyAddSingle(int word) {
        int idxn = (bit(word, Q_SHIFT) << 1) | bit(word, SIZE_SHIFT);
        return new AdvSimdFpOp64.Fp8FusedMultiplyAddLong(true, idxn, rd(word), rn(word), rm(word));
    }

    private static WordDecoder<Ir64Op> dot(boolean single) {
        return word -> new AdvSimdFpOp64.Fp8DotProduct(single, q(word), rd(word), rn(word), rm(word));
    }

    private static Ir64Op complexMultiplyAccumulate(int word) {
        int rotation = ((word >>> OPCODE_SHIFT) & FCMA_ROTATION_MASK) * ROTATION_UNIT_DEGREES;
        return new AdvSimdFpOp64.FpComplexMultiplyAccumulate(q(word), esz(word), rotation, rd(word), rn(word), rm(word));
    }

    private static WordDecoder<Ir64Op> complexAdd(int rotation) {
        return word -> new AdvSimdFpOp64.FpComplexAdd(q(word), esz(word), rotation, rd(word), rn(word), rm(word));
    }

    private static boolean scalar(int word) {
        return bit(word, SCALAR_BIT_SHIFT) != 0;
    }

    /// `Q` só existe na forma vetorial; na escalar o `bit30` é parte do prefixo.
    private static boolean q(int word) {
        return !scalar(word) && bit(word, Q_SHIFT) != 0;
    }

    private static int esz(int word) {
        return (word >>> SIZE_SHIFT) & SIZE_MASK;
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
