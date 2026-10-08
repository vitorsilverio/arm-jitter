package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15d: o AdvSIMD "three different" (`bit21=1`, `bits[11:10]=00`, `Rm` registrador livre) — alargando,
/// largo+estreito, estreitando e `PMULL`, vetorial (prefixo `01110`), e as três únicas formas escalares
/// (`SQDMULL`/`SQDMLAL`/`SQDMLSL`, prefixo `11110` com `bit30=1`). Antes era `decodeAdvancedSimdThreeDifferent` e o
/// ramo `bit11=0` de `decodeAdvancedSimdInteger` no `Aarch64Decoder` (B8.7, B8.8, B8.11, B8.20, E8).
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) size(23:22) 1 Rm(20:16) opcode(15:12) 00 Rn Rd`.
///
/// Tamanhos (ARM DDI 0487, conferidos no `a64.decode` do QEMU e no `objdump`):
///
/// - vetorial: `size` `00`/`01`/`10` (o elemento estreito é `B`/`H`/`S`), `Q` escolhe a metade (`…2`);
/// - `SQDMULL`/`SQDMLAL`/`SQDMLSL`: só `H`/`S`, só `U=0`;
/// - `PMULL`: só `U=0`, `size=00` (`p8`) ou `11` (`p64`) — a única forma com `size=11`.
final class AdvSimdThreeDifferentRows {
    private static final int Q_SHIFT = 30;
    private static final int SIZE_SHIFT = 22;
    private static final int SIZE_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    private static final String VECTOR_PREFIX = "01110";
    /// Escalar: prefixo `11110` com `bit30=1` (o par `Q size` das linhas escalares fixa o `1`).
    private static final String SCALAR_PREFIX = "11110";
    /// `opcode` (`bits[15:12]`) de `PMULL`/`PMULL2`.
    private static final String POLYNOMIAL_MULTIPLY_OPCODE = "1110";

    /// Tamanhos aceitos por uma operação: os pares `Q size` (`bit30`, `bits[23:22]`) de cada linha.
    private enum Sizes {
        /// Vetorial, elemento estreito `B`/`H`/`S`.
        BHS(". 0.", ". 10"),
        /// Vetorial, elemento estreito `H`/`S`.
        HS(". 01", ". 10"),
        /// Escalar `H`/`S`.
        SCALAR_HS("1 01", "1 10");

        private final List<String> qSize;

        Sizes(String... qSize) {
            this.qSize = List.of(qSize);
        }
    }

    static final List<DecodeRow<Aarch64Feature, Ir64Op>> ROWS = rows();

    private AdvSimdThreeDifferentRows() {
    }

    private static List<DecodeRow<Aarch64Feature, Ir64Op>> rows() {
        List<DecodeRow<Aarch64Feature, Ir64Op>> rows = new ArrayList<>();
        // alargando — 0 Q U 01110 size 1 Rm opcode 00 Rn Rd
        widening(rows, "0000", Ir64VectorWideningOp.SADDL, Ir64VectorWideningOp.UADDL);
        widening(rows, "0010", Ir64VectorWideningOp.SSUBL, Ir64VectorWideningOp.USUBL);
        widening(rows, "0101", Ir64VectorWideningOp.SABAL, Ir64VectorWideningOp.UABAL);
        widening(rows, "0111", Ir64VectorWideningOp.SABDL, Ir64VectorWideningOp.UABDL);
        widening(rows, "1000", Ir64VectorWideningOp.SMLAL, Ir64VectorWideningOp.UMLAL);
        widening(rows, "1010", Ir64VectorWideningOp.SMLSL, Ir64VectorWideningOp.UMLSL);
        widening(rows, "1100", Ir64VectorWideningOp.SMULL, Ir64VectorWideningOp.UMULL);
        // saturante dobrado: só U=0, só H/S
        saturatingDoubling(rows, "1001", Ir64VectorWideningOp.SQDMLAL);
        saturatingDoubling(rows, "1011", Ir64VectorWideningOp.SQDMLSL);
        saturatingDoubling(rows, "1101", Ir64VectorWideningOp.SQDMULL);
        // largo+estreito
        wide(rows, "0001", Ir64VectorWideOp.SADDW, Ir64VectorWideOp.UADDW);
        wide(rows, "0011", Ir64VectorWideOp.SSUBW, Ir64VectorWideOp.USUBW);
        // estreitando
        narrow(rows, "0100", Ir64VectorNarrowOp.ADDHN, Ir64VectorNarrowOp.RADDHN);
        narrow(rows, "0110", Ir64VectorNarrowOp.SUBHN, Ir64VectorNarrowOp.RSUBHN);
        // PMULL: 0 Q 0 01110 size 1 Rm 1110 00 Rn Rd, size 00 (p8) ou 11 (p64)
        polynomialMultiplyLong(rows, "00", false);
        polynomialMultiplyLong(rows, "11", true);
        return List.copyOf(rows);
    }

    /// Uma linha por par `Q size` de `sizes`: `0 Q U prefixo size 1 Rm opcode 00 Rn Rd`.
    private static void add(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String prefix, String u, String opcode, Sizes sizes,
            DecodeRow.WordDecoder<Ir64Op> build) {
        for (String qSize : sizes.qSize) {
            add(rows, prefix, u, qSize.substring(0, 1), qSize.substring(2), opcode, build);
        }
    }

    private static void add(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String prefix, String u, String q, String size,
            String opcode, DecodeRow.WordDecoder<Ir64Op> build) {
        rows.add(DecodeRow.of("0 " + q + " " + u + " " + prefix + " " + size + " 1 ..... " + opcode
                + " 00 ..... .....", null, build));
    }

    private static void widening(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, Ir64VectorWideningOp signed,
            Ir64VectorWideningOp unsigned) {
        vectorWidening(rows, "0", opcode, signed, Sizes.BHS);
        vectorWidening(rows, "1", opcode, unsigned, Sizes.BHS);
    }

    private static void saturatingDoubling(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, Ir64VectorWideningOp op) {
        vectorWidening(rows, "0", opcode, op, Sizes.HS);
        add(rows, SCALAR_PREFIX, "0", opcode, Sizes.SCALAR_HS, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticWidening(op, true, false, esz(word), rd(word), rn(word), rm(word)));
    }

    private static void vectorWidening(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String u, String opcode,
            Ir64VectorWideningOp op, Sizes sizes) {
        add(rows, VECTOR_PREFIX, u, opcode, sizes, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticWidening(op, false, q(word), esz(word), rd(word), rn(word), rm(word)));
    }

    private static void wide(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, Ir64VectorWideOp signed,
            Ir64VectorWideOp unsigned) {
        for (Ir64VectorWideOp op : new Ir64VectorWideOp[] {signed, unsigned}) {
            add(rows, VECTOR_PREFIX, op == signed ? "0" : "1", opcode, Sizes.BHS, (word, address) ->
                    new AdvSimdIntegerOp64.ArithmeticWide(op, q(word), esz(word), rd(word), rn(word), rm(word)));
        }
    }

    /// `rounding` é a forma `U=1` (`RADDHN`/`RSUBHN`).
    private static void narrow(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String opcode, Ir64VectorNarrowOp truncating,
            Ir64VectorNarrowOp rounding) {
        for (Ir64VectorNarrowOp op : new Ir64VectorNarrowOp[] {truncating, rounding}) {
            add(rows, VECTOR_PREFIX, op == truncating ? "0" : "1", opcode, Sizes.BHS, (word, address) ->
                    new AdvSimdIntegerOp64.ArithmeticNarrow(op, q(word), esz(word), rd(word), rn(word), rm(word)));
        }
    }

    private static void polynomialMultiplyLong(List<DecodeRow<Aarch64Feature, Ir64Op>> rows, String size, boolean doubleword) {
        add(rows, VECTOR_PREFIX, "0", ".", size, POLYNOMIAL_MULTIPLY_OPCODE, (word, address) ->
                new AdvSimdIntegerOp64.PolynomialMultiplyLong(doubleword, q(word), rd(word), rn(word), rm(word)));
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
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
}
