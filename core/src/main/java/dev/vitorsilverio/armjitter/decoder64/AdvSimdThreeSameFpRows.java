package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15c: o AdvSIMD "three same (FP)" de precisão simples e dupla (`_sd`, `bit21=1`, `bit10=1`, `opcode`
/// `11xxx`) — aritmética vetorial e escalar, pareado vetorial, e as formas com feature que moram no mesmo espaço
/// (`FMLAL`/`FMLSL`(`2`) de `FEAT_FHM`, `FSCALE` de `FEAT_FP8`, `FAMAX`/`FAMIN` de `FEAT_FAMINMAX`). Antes era
/// `decodeAdvancedSimdThreeSameFp` do `Aarch64Decoder` (B8.9, B19.2, B19.11e, B19.13, B19.24, E15.9c). As formas
/// `_h` (`FEAT_FP16`) são do espaço `bit21=0` ({@link AdvSimdBit21ZeroRows}).
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) a sz 1 Rm(20:16) opcode(15:11) 1 Rn Rd`. `a` (`bit23`) é mais um bit
/// de opcode, nunca tamanho; `sz` (`bit22`) escolhe simples (`0`) ou dupla (`1`).
///
/// Tamanhos (ARM DDI 0487, conferidos no `a64.decode` do QEMU e no `objdump`):
///
/// - vetorial: `.2s`/`.4s`/`.2d` — `sz=1` sem `Q` seria `.1d`, reservado;
/// - escalar (prefixo `11110`, `bit30=1`): `s`/`d`; só `FMULX`/`FABD`/`FRECPS`/`FRSQRTS`/`FCMEQ`/`FCMGE`/`FCMGT`/
///   `FACGE`/`FACGT` têm forma escalar;
/// - `FMLAL`/`FMLSL`(`2`): `sz=0` fixo (o `objdump` aceita `sz=1`, o `a64.decode` não), `Q` livre, sem escalar.
final class AdvSimdThreeSameFpRows {
    private static final int Q_SHIFT = 30;
    private static final int SZ_SHIFT = 22;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `esz` (`log2` dos bytes do elemento) de `sz=0`: precisão simples; `sz=1` soma um (dupla).
    private static final int SINGLE_ESZ = 2;
    private static final String VECTOR_PREFIX = "01110";
    /// Escalar: prefixo `11110` com `bit30=1` (o par `Q sz` das linhas escalares fixa o `1`).
    private static final String SCALAR_PREFIX = "11110";
    /// `opcode` de `FMLAL`/`FMLSL` (`U=0`) e de `FMLAL2`/`FMLSL2` (`U=1`).
    private static final String FHM_OPCODE_LOW = "11101";
    private static final String FHM_OPCODE_HIGH = "11001";
    /// `opcode` de `FSCALE` (o de `FDIV`/`FRECPS`/`FRSQRTS`, com `U=1 a=1`).
    private static final String SCALE_OPCODE = "11111";
    /// `opcode` de `FAMAX`/`FAMIN` (o de `FMUL`/`FMULX`, com `a=1`).
    private static final String ABSOLUTE_MAX_MIN_OPCODE = "11011";

    /// Tamanhos aceitos: os pares `Q sz` (`bit30`, `bit22`) de cada linha.
    private enum Sizes {
        /// Vetorial `.2s`/`.4s`/`.2d`.
        VECTOR(". 0", "1 1"),
        /// Escalar `s`/`d`.
        SCALAR("1 ."),
        /// Vetorial com `sz=0` fixo (`FMLAL`/`FMLSL`).
        VECTOR_SZ0(". 0");

        private final List<String> qSz;

        Sizes(String... qSz) {
            this.qSz = List.of(qSz);
        }
    }

    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = rows();

    private AdvSimdThreeSameFpRows() {
    }

    private static List<DecodeRow<Aarch64Feature, Ir64Op>> rows() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = new ArrayList<>();
        // vetorial — 0 Q U 01110 a sz 1 Rm opcode 1 Rn Rd
        vector(rows, "0", "0", "11000", Ir64VectorFpThreeSameOp.MAXNM);
        vector(rows, "0", "1", "11000", Ir64VectorFpThreeSameOp.MINNM);
        vector(rows, "0", "0", "11001", Ir64VectorFpThreeSameOp.MLA);
        vector(rows, "0", "1", "11001", Ir64VectorFpThreeSameOp.MLS);
        vector(rows, "0", "0", "11010", Ir64VectorFpThreeSameOp.ADD);
        vector(rows, "0", "1", "11010", Ir64VectorFpThreeSameOp.SUB);
        vector(rows, "1", "1", "11010", Ir64VectorFpThreeSameOp.ABD);
        vector(rows, "0", "0", "11011", Ir64VectorFpThreeSameOp.MULX);
        vector(rows, "1", "0", "11011", Ir64VectorFpThreeSameOp.MUL);
        vector(rows, "0", "0", "11100", Ir64VectorFpThreeSameOp.CMEQ);
        vector(rows, "1", "0", "11100", Ir64VectorFpThreeSameOp.CMGE);
        vector(rows, "1", "1", "11100", Ir64VectorFpThreeSameOp.CMGT);
        vector(rows, "1", "0", "11101", Ir64VectorFpThreeSameOp.FACGE);
        vector(rows, "1", "1", "11101", Ir64VectorFpThreeSameOp.FACGT);
        vector(rows, "0", "0", "11110", Ir64VectorFpThreeSameOp.MAX);
        vector(rows, "0", "1", "11110", Ir64VectorFpThreeSameOp.MIN);
        vector(rows, "0", "0", "11111", Ir64VectorFpThreeSameOp.RECPS);
        vector(rows, "0", "1", "11111", Ir64VectorFpThreeSameOp.RSQRTS);
        vector(rows, "1", "0", "11111", Ir64VectorFpThreeSameOp.DIV);
        // escalar — 0 1 U 11110 a sz 1 Rm opcode 1 Rn Rd
        scalar(rows, "1", "1", "11010", Ir64VectorFpThreeSameOp.ABD);
        scalar(rows, "0", "0", "11011", Ir64VectorFpThreeSameOp.MULX);
        scalar(rows, "0", "0", "11100", Ir64VectorFpThreeSameOp.CMEQ);
        scalar(rows, "1", "0", "11100", Ir64VectorFpThreeSameOp.CMGE);
        scalar(rows, "1", "1", "11100", Ir64VectorFpThreeSameOp.CMGT);
        scalar(rows, "1", "0", "11101", Ir64VectorFpThreeSameOp.FACGE);
        scalar(rows, "1", "1", "11101", Ir64VectorFpThreeSameOp.FACGT);
        scalar(rows, "0", "0", "11111", Ir64VectorFpThreeSameOp.RECPS);
        scalar(rows, "0", "1", "11111", Ir64VectorFpThreeSameOp.RSQRTS);
        // pareado — 0 Q 1 01110 a sz 1 Rm opcode 1 Rn Rd (a forma escalar é "scalar pairwise", `bit10=0`)
        pairwise(rows, "0", "11000", Ir64VectorFpPairwiseOp.MAXNM);
        pairwise(rows, "1", "11000", Ir64VectorFpPairwiseOp.MINNM);
        pairwise(rows, "0", "11010", Ir64VectorFpPairwiseOp.ADD);
        pairwise(rows, "0", "11110", Ir64VectorFpPairwiseOp.MAX);
        pairwise(rows, "1", "11110", Ir64VectorFpPairwiseOp.MIN);
        // FEAT_FHM — 0 Q U 01110 a 0 1 Rm opcode 1 Rn Rd (`a` = subtração)
        multiplyAddLong(rows, "0", FHM_OPCODE_LOW, false);
        multiplyAddLong(rows, "1", FHM_OPCODE_HIGH, true);
        // FEAT_FP8 — FSCALE: 0 Q 1 01110 1 sz 1 Rm 11111 1 Rn Rd
        add(rows, VECTOR_PREFIX, "1", "1", SCALE_OPCODE, Sizes.VECTOR, Aarch64Feature.FP8,
                (word, address) -> new AdvSimdFpOp64.FpScaleByInt(q(word), esz(word), rd(word), rn(word), rm(word)));
        // FEAT_FAMINMAX — FAMAX (U=0) / FAMIN (U=1): 0 Q U 01110 1 sz 1 Rm 11011 1 Rn Rd
        absoluteMaxMin(rows, "0", true);
        absoluteMaxMin(rows, "1", false);
        return List.copyOf(rows);
    }

    /// Uma linha por par `Q sz` de `sizes`: `0 Q U prefixo a sz 1 Rm opcode 1 Rn Rd`.
    private static void add(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String prefix, String u, String a, String opcode,
            Sizes sizes, Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        for (String qSz : sizes.qSz) {
            String q = qSz.substring(0, 1);
            String sz = qSz.substring(2);
            rows.add(DecodeRow.of("0 " + q + " " + u + " " + prefix + " " + a + sz + " 1 ..... " + opcode
                    + " 1 ..... .....", requires, build));
        }
    }

    private static void vector(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, String a, String opcode,
            Ir64VectorFpThreeSameOp op) {
        add(rows, VECTOR_PREFIX, u, a, opcode, Sizes.VECTOR, null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSame(op, false, q(word), esz(word), rd(word), rn(word), rm(word)));
    }

    private static void scalar(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, String a, String opcode,
            Ir64VectorFpThreeSameOp op) {
        add(rows, SCALAR_PREFIX, u, a, opcode, Sizes.SCALAR, null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticThreeSame(op, true, false, esz(word), rd(word), rn(word), rm(word)));
    }

    private static void pairwise(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String a, String opcode, Ir64VectorFpPairwiseOp op) {
        add(rows, VECTOR_PREFIX, "1", a, opcode, Sizes.VECTOR, null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticPairwise(op, false, q(word), esz(word), rd(word), rn(word), rm(word)));
    }

    /// `FMLAL`/`FMLSL` (`top=false`) ou `FMLAL2`/`FMLSL2` (`top=true`): uma linha por `a` (soma/subtração).
    private static void multiplyAddLong(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, String opcode, boolean top) {
        for (boolean subtract : new boolean[] {false, true}) {
            add(rows, VECTOR_PREFIX, u, subtract ? "1" : "0", opcode, Sizes.VECTOR_SZ0,
                    Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG, (word, address) ->
                            new AdvSimdFpOp64.FpMultiplyAddLong(q(word), top, subtract, rd(word), rn(word), rm(word)));
        }
    }

    private static void absoluteMaxMin(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, boolean max) {
        add(rows, VECTOR_PREFIX, u, "1", ABSOLUTE_MAX_MIN_OPCODE, Sizes.VECTOR, Aarch64Feature.FP_ABSOLUTE_MAX_MIN,
                (word, address) -> new AdvSimdFpOp64.FpAbsoluteMaxMin(max, q(word), esz(word), rd(word), rn(word),
                        rm(word)));
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
    }

    private static int esz(int word) {
        return SINGLE_ESZ + ((word >>> SZ_SHIFT) & 1);
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
}
