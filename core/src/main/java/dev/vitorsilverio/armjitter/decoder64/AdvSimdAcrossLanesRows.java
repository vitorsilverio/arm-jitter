package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15d: as reduções do AdvSIMD — "across lanes" vetorial (inteiro e FP, prefixo `01110`) e "scalar pairwise"
/// (`ADDP` e os pareados FP, prefixo `11110` com `bit30=1`). As duas famílias dividem o slot `bits[21:17]=11000`.
/// Antes era o ramo `Rm=1000x` de `decodeAdvancedSimdInteger` no `Aarch64Decoder`, com
/// `decodeVectorAcrossLanesOpcode`, `decodeVectorFpAcrossLanesOpcode` e `decodeVectorFpScalarPairwiseOpcode` (B8.7,
/// B8.10, B19.2, B19.5.3, E15.9c).
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) size(23:22) 11000 opcode(16:12) 10 Rn Rd`. Nas formas FP, `bit23`
/// (`a`) é mais um bit de opcode e só `bit22` (`sz`) é tamanho.
///
/// Tamanhos (ARM DDI 0487, conferidos no `a64.decode` do QEMU e no `objdump`):
///
/// - inteiro: `.8b`/`.16b`/`.4h`/`.8h`/`.4s` — reduzir 2 elementos (`.2s`) é a forma pareada e não existe
///   elemento de 64 bits;
/// - FP: `_s` só `.4s` (`U=1`, `Q=1`, `sz=0`); `_h` (`FEAT_FP16`, `U=0`, `sz=0`) `.4h`/`.8h`;
/// - scalar pairwise: `ADDP` só `D`; FP `_sd` (`U=1`) `s`/`d`; FP `_h` (`FEAT_FP16`, `U=0`, `sz=0`).
final class AdvSimdAcrossLanesRows {
    private static final int Q_SHIFT = 30;
    private static final int SIZE_SHIFT = 22;
    private static final int SIZE_MASK = 0b11;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `esz` (`log2` dos bytes do elemento) de `sz=0` nas formas FP `_sd`: precisão simples; `sz=1` soma um.
    private static final int SINGLE_ESZ = 2;
    /// `esz` das formas FP `_h`.
    private static final int HALF_ESZ = 1;
    private static final String VECTOR_PREFIX = "01110";
    /// Escalar: prefixo `11110` com `bit30=1`.
    private static final String SCALAR_PREFIX = "11110";
    /// `opcode` (`bits[16:12]`) de `FMAXNMV`/`FMINNMV` e `FMAXNMP`/`FMINNMP`.
    private static final String FP_MAX_MIN_NUMBER_OPCODE = "01100";
    /// `opcode` de `FMAXV`/`FMINV` e `FMAXP`/`FMINP`.
    private static final String FP_MAX_MIN_OPCODE = "01111";
    /// `opcode` de `FADDP` escalar.
    private static final String FP_ADD_OPCODE = "01101";

    /// Pares `Q size` (`bit30`, `bits[23:22]`) do "across lanes" inteiro: `B`/`H` em qualquer `Q`, `S` só com `Q=1`.
    private static final List<String> INTEGER_SIZES = List.of(". 0.", "1 10");

    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = rows();

    private AdvSimdAcrossLanesRows() {
    }

    private static List<DecodeRow<Aarch64Feature, Ir64Op>> rows() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = new ArrayList<>();
        // across lanes inteiro — 0 Q U 01110 size 11000 opcode 10 Rn Rd
        integer(rows, "0", "00011", Ir64VectorAcrossLanesOp.SADDLV);
        integer(rows, "1", "00011", Ir64VectorAcrossLanesOp.UADDLV);
        integer(rows, "0", "01010", Ir64VectorAcrossLanesOp.SMAXV);
        integer(rows, "1", "01010", Ir64VectorAcrossLanesOp.UMAXV);
        integer(rows, "0", "11010", Ir64VectorAcrossLanesOp.SMINV);
        integer(rows, "1", "11010", Ir64VectorAcrossLanesOp.UMINV);
        integer(rows, "0", "11011", Ir64VectorAcrossLanesOp.ADDV);
        // across lanes FP — 0 Q U 01110 a 0 11000 opcode 10 Rn Rd
        fpAcrossLanes(rows, "0", FP_MAX_MIN_NUMBER_OPCODE, Ir64VectorFpAcrossLanesOp.FMAXNMV);
        fpAcrossLanes(rows, "1", FP_MAX_MIN_NUMBER_OPCODE, Ir64VectorFpAcrossLanesOp.FMINNMV);
        fpAcrossLanes(rows, "0", FP_MAX_MIN_OPCODE, Ir64VectorFpAcrossLanesOp.FMAXV);
        fpAcrossLanes(rows, "1", FP_MAX_MIN_OPCODE, Ir64VectorFpAcrossLanesOp.FMINV);
        // scalar pairwise — 0 1 U 11110 size 11000 opcode 10 Rn Rd
        rows.add(DecodeRow.of("0 1 0 " + SCALAR_PREFIX + " 11 11000 11011 10 ..... .....", null,
                (word, address) -> new AdvSimdIntegerOp64.ScalarPairwiseAdd(rd(word), rn(word))));
        fpScalarPairwise(rows, "0", FP_ADD_OPCODE, Ir64VectorFpPairwiseOp.ADD);
        fpScalarPairwise(rows, "0", FP_MAX_MIN_OPCODE, Ir64VectorFpPairwiseOp.MAX);
        fpScalarPairwise(rows, "1", FP_MAX_MIN_OPCODE, Ir64VectorFpPairwiseOp.MIN);
        fpScalarPairwise(rows, "0", FP_MAX_MIN_NUMBER_OPCODE, Ir64VectorFpPairwiseOp.MAXNM);
        fpScalarPairwise(rows, "1", FP_MAX_MIN_NUMBER_OPCODE, Ir64VectorFpPairwiseOp.MINNM);
        return List.copyOf(rows);
    }

    private static DecodeRow<Aarch64Feature, Ir64Op> row(String q, String u, String prefix, String size, String opcode,
            Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        return DecodeRow.of("0 " + q + " " + u + " " + prefix + " " + size + " 11000 " + opcode + " 10 ..... .....",
                requires, build);
    }

    private static void integer(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, String opcode, Ir64VectorAcrossLanesOp op) {
        for (String qSize : INTEGER_SIZES) {
            rows.add(row(qSize.substring(0, 1), u, VECTOR_PREFIX, qSize.substring(2), opcode, null, (word, address) ->
                    new AdvSimdIntegerOp64.AcrossLanes(op, q(word), esz(word), rd(word), rn(word))));
        }
    }

    /// `_s` (`U=1`, só `.4s`) e `_h` (`U=0`, `FEAT_FP16`); `a` é o bit23.
    private static void fpAcrossLanes(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String a, String opcode,
            Ir64VectorFpAcrossLanesOp op) {
        rows.add(row("1", "1", VECTOR_PREFIX, a + "0", opcode, null, (word, address) ->
                new AdvSimdFpOp64.FpAcrossLanes(op, true, SINGLE_ESZ, rd(word), rn(word))));
        rows.add(row(".", "0", VECTOR_PREFIX, a + "0", opcode, Aarch64Feature.FP16, (word, address) ->
                new AdvSimdFpOp64.FpAcrossLanes(op, q(word), HALF_ESZ, rd(word), rn(word))));
    }

    /// `_sd` (`U=1`, `sz` livre) e `_h` (`U=0`, `sz=0`, `FEAT_FP16`); `a` é o bit23.
    private static void fpScalarPairwise(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String a, String opcode,
            Ir64VectorFpPairwiseOp op) {
        rows.add(row("1", "1", SCALAR_PREFIX, a + ".", opcode, null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticPairwise(op, true, false, SINGLE_ESZ + (esz(word) & 1), rd(word),
                        rn(word), rn(word))));
        rows.add(row("1", "0", SCALAR_PREFIX, a + "0", opcode, Aarch64Feature.FP16, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticPairwise(op, true, false, HALF_ESZ, rd(word), rn(word), rn(word))));
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
    }

    private static int esz(int word) {
        return (word >>> SIZE_SHIFT) & SIZE_MASK;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }
}
