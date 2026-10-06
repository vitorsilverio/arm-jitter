package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15b: o AdvSIMD "three same" INTEIRO (`bit21=1`, `bit10=1`) — aritmética, lógico e pareado, vetorial
/// (prefixo `01110`) e escalar (`11110` com `bit30=1`). Antes era `decodeAdvancedSimdThreeSameShape` do
/// `Aarch64Decoder` (B8.7, B8.8, B8.18, E15.9c) com as restrições de `size` em
/// `validateScalarThreeSameEsz`/`validateVectorThreeSameEsz`; aqui cada `size` aceito é uma linha. O "three
/// same (FP)" (`opcode` `11xxx`) mora no mesmo espaço e ainda não é tabela (E15.15c).
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) size(23:22) 1 Rm(20:16) opcode(15:11) 1 Rn Rd`.
///
/// Restrições de `size` por operação (ARM DDI 0487, conferidas no `a64.decode` do QEMU e no `objdump`):
///
/// - vetorial: elemento de 64 bits só com `Q=1` (não existe `.1d`) nas que o têm; `SHADD`…`UABA`, `MUL`/`MLA`/
///   `MLS` e os pareados `SMAXP`…`UMINP` não têm elemento de 64 bits; `SQDMULH`/`SQRDMULH` só `H`/`S`;
///   `PMUL` só byte;
/// - escalar: `ADD`/`SUB`/comparações/`SSHL`/`USHL`/`SRSHL`/`URSHL` só `D`; os saturantes de soma e
///   deslocamento aceitam qualquer tamanho; `SQDMULH`/`SQRDMULH` só `H`/`S`; o resto não tem forma escalar;
/// - lógico (`opcode=00011`, só vetorial): `size` não é tamanho, é o que separa as 4 operações de cada `U`
///   (o record leva `esz=0`).
final class AdvSimdThreeSameRows {
    private static final int Q_SHIFT = 30;
    private static final int SIZE_SHIFT = 22;
    private static final int SIZE_MASK = 0b11;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// O lógico é bit a bit: o record não distingue tamanho de elemento.
    private static final int BITWISE_ESZ = 0;
    private static final String VECTOR_PREFIX = "01110";
    /// Escalar: prefixo `11110` com `bit30=1` (o par `Q size` das linhas escalares fixa o `1`).
    private static final String SCALAR_PREFIX = "11110";
    /// `opcode` (`bits[15:11]`) de toda a família lógica.
    private static final String LOGICAL_OPCODE = "00011";

    /// Tamanhos aceitos por uma operação: os pares `Q size` (`bit30`, `bits[23:22]`) de cada linha.
    private enum Sizes {
        /// Vetorial `B`/`H`/`S`/`D`, com `D` só em `Q=1`.
        ALL_BUT_1D(". 0.", ". 10", "1 11"),
        /// Vetorial `B`/`H`/`S`.
        BHS(". 0.", ". 10"),
        /// Vetorial `H`/`S`.
        HS(". 01", ". 10"),
        /// Vetorial, só `B`.
        B(". 00"),
        /// Escalar, só `D`.
        SCALAR_D("1 11"),
        /// Escalar, qualquer tamanho.
        SCALAR_ANY("1 .."),
        /// Escalar `H`/`S`.
        SCALAR_HS("1 01", "1 10");

        private final List<String> qSize;

        Sizes(String... qSize) {
            this.qSize = List.of(qSize);
        }
    }

    static final List<DecodeRow<Ir64Op>> ROWS = rows();

    private AdvSimdThreeSameRows() {
    }

    private static List<DecodeRow<Ir64Op>> rows() {
        List<DecodeRow<Ir64Op>> rows = new ArrayList<>();
        // vetorial — 0 Q U 01110 size 1 Rm opcode 1 Rn Rd
        vector(rows, "0", "00000", Ir64VectorThreeSameOp.SHADD, Sizes.BHS);
        vector(rows, "1", "00000", Ir64VectorThreeSameOp.UHADD, Sizes.BHS);
        vector(rows, "0", "00001", Ir64VectorThreeSameOp.SQADD, Sizes.ALL_BUT_1D);
        vector(rows, "1", "00001", Ir64VectorThreeSameOp.UQADD, Sizes.ALL_BUT_1D);
        vector(rows, "0", "00010", Ir64VectorThreeSameOp.SRHADD, Sizes.BHS);
        vector(rows, "1", "00010", Ir64VectorThreeSameOp.URHADD, Sizes.BHS);
        vector(rows, "0", "00100", Ir64VectorThreeSameOp.SHSUB, Sizes.BHS);
        vector(rows, "1", "00100", Ir64VectorThreeSameOp.UHSUB, Sizes.BHS);
        vector(rows, "0", "00101", Ir64VectorThreeSameOp.SQSUB, Sizes.ALL_BUT_1D);
        vector(rows, "1", "00101", Ir64VectorThreeSameOp.UQSUB, Sizes.ALL_BUT_1D);
        vector(rows, "0", "00110", Ir64VectorThreeSameOp.CMGT, Sizes.ALL_BUT_1D);
        vector(rows, "1", "00110", Ir64VectorThreeSameOp.CMHI, Sizes.ALL_BUT_1D);
        vector(rows, "0", "00111", Ir64VectorThreeSameOp.CMGE, Sizes.ALL_BUT_1D);
        vector(rows, "1", "00111", Ir64VectorThreeSameOp.CMHS, Sizes.ALL_BUT_1D);
        vector(rows, "0", "01000", Ir64VectorThreeSameOp.SSHL, Sizes.ALL_BUT_1D);
        vector(rows, "1", "01000", Ir64VectorThreeSameOp.USHL, Sizes.ALL_BUT_1D);
        vector(rows, "0", "01001", Ir64VectorThreeSameOp.SQSHL, Sizes.ALL_BUT_1D);
        vector(rows, "1", "01001", Ir64VectorThreeSameOp.UQSHL, Sizes.ALL_BUT_1D);
        vector(rows, "0", "01010", Ir64VectorThreeSameOp.SRSHL, Sizes.ALL_BUT_1D);
        vector(rows, "1", "01010", Ir64VectorThreeSameOp.URSHL, Sizes.ALL_BUT_1D);
        vector(rows, "0", "01011", Ir64VectorThreeSameOp.SQRSHL, Sizes.ALL_BUT_1D);
        vector(rows, "1", "01011", Ir64VectorThreeSameOp.UQRSHL, Sizes.ALL_BUT_1D);
        vector(rows, "0", "01100", Ir64VectorThreeSameOp.SMAX, Sizes.BHS);
        vector(rows, "1", "01100", Ir64VectorThreeSameOp.UMAX, Sizes.BHS);
        vector(rows, "0", "01101", Ir64VectorThreeSameOp.SMIN, Sizes.BHS);
        vector(rows, "1", "01101", Ir64VectorThreeSameOp.UMIN, Sizes.BHS);
        vector(rows, "0", "01110", Ir64VectorThreeSameOp.SABD, Sizes.BHS);
        vector(rows, "1", "01110", Ir64VectorThreeSameOp.UABD, Sizes.BHS);
        vector(rows, "0", "01111", Ir64VectorThreeSameOp.SABA, Sizes.BHS);
        vector(rows, "1", "01111", Ir64VectorThreeSameOp.UABA, Sizes.BHS);
        vector(rows, "0", "10000", Ir64VectorThreeSameOp.ADD, Sizes.ALL_BUT_1D);
        vector(rows, "1", "10000", Ir64VectorThreeSameOp.SUB, Sizes.ALL_BUT_1D);
        vector(rows, "0", "10001", Ir64VectorThreeSameOp.CMTST, Sizes.ALL_BUT_1D);
        vector(rows, "1", "10001", Ir64VectorThreeSameOp.CMEQ, Sizes.ALL_BUT_1D);
        vector(rows, "0", "10010", Ir64VectorThreeSameOp.MLA, Sizes.BHS);
        vector(rows, "1", "10010", Ir64VectorThreeSameOp.MLS, Sizes.BHS);
        vector(rows, "0", "10011", Ir64VectorThreeSameOp.MUL, Sizes.BHS);
        vector(rows, "1", "10011", Ir64VectorThreeSameOp.PMUL, Sizes.B);
        vector(rows, "0", "10110", Ir64VectorThreeSameOp.SQDMULH, Sizes.HS);
        vector(rows, "1", "10110", Ir64VectorThreeSameOp.SQRDMULH, Sizes.HS);
        // lógico — 0 Q U 01110 opc 1 Rm 00011 1 Rn Rd
        logical(rows, "0", "00", Ir64VectorThreeSameOp.AND);
        logical(rows, "0", "01", Ir64VectorThreeSameOp.BIC);
        logical(rows, "0", "10", Ir64VectorThreeSameOp.ORR);
        logical(rows, "0", "11", Ir64VectorThreeSameOp.ORN);
        logical(rows, "1", "00", Ir64VectorThreeSameOp.EOR);
        logical(rows, "1", "01", Ir64VectorThreeSameOp.BSL);
        logical(rows, "1", "10", Ir64VectorThreeSameOp.BIT);
        logical(rows, "1", "11", Ir64VectorThreeSameOp.BIF);
        // pareado — 0 Q U 01110 size 1 Rm opcode 1 Rn Rd
        pairwise(rows, "0", "10100", Ir64VectorPairwiseOp.SMAX, Sizes.BHS);
        pairwise(rows, "1", "10100", Ir64VectorPairwiseOp.UMAX, Sizes.BHS);
        pairwise(rows, "0", "10101", Ir64VectorPairwiseOp.SMIN, Sizes.BHS);
        pairwise(rows, "1", "10101", Ir64VectorPairwiseOp.UMIN, Sizes.BHS);
        pairwise(rows, "0", "10111", Ir64VectorPairwiseOp.ADD, Sizes.ALL_BUT_1D);
        // escalar — 0 1 U 11110 size 1 Rm opcode 1 Rn Rd
        scalar(rows, "0", "00001", Ir64VectorThreeSameOp.SQADD, Sizes.SCALAR_ANY);
        scalar(rows, "1", "00001", Ir64VectorThreeSameOp.UQADD, Sizes.SCALAR_ANY);
        scalar(rows, "0", "00101", Ir64VectorThreeSameOp.SQSUB, Sizes.SCALAR_ANY);
        scalar(rows, "1", "00101", Ir64VectorThreeSameOp.UQSUB, Sizes.SCALAR_ANY);
        scalar(rows, "0", "00110", Ir64VectorThreeSameOp.CMGT, Sizes.SCALAR_D);
        scalar(rows, "1", "00110", Ir64VectorThreeSameOp.CMHI, Sizes.SCALAR_D);
        scalar(rows, "0", "00111", Ir64VectorThreeSameOp.CMGE, Sizes.SCALAR_D);
        scalar(rows, "1", "00111", Ir64VectorThreeSameOp.CMHS, Sizes.SCALAR_D);
        scalar(rows, "0", "01000", Ir64VectorThreeSameOp.SSHL, Sizes.SCALAR_D);
        scalar(rows, "1", "01000", Ir64VectorThreeSameOp.USHL, Sizes.SCALAR_D);
        scalar(rows, "0", "01001", Ir64VectorThreeSameOp.SQSHL, Sizes.SCALAR_ANY);
        scalar(rows, "1", "01001", Ir64VectorThreeSameOp.UQSHL, Sizes.SCALAR_ANY);
        scalar(rows, "0", "01010", Ir64VectorThreeSameOp.SRSHL, Sizes.SCALAR_D);
        scalar(rows, "1", "01010", Ir64VectorThreeSameOp.URSHL, Sizes.SCALAR_D);
        scalar(rows, "0", "01011", Ir64VectorThreeSameOp.SQRSHL, Sizes.SCALAR_ANY);
        scalar(rows, "1", "01011", Ir64VectorThreeSameOp.UQRSHL, Sizes.SCALAR_ANY);
        scalar(rows, "0", "10000", Ir64VectorThreeSameOp.ADD, Sizes.SCALAR_D);
        scalar(rows, "1", "10000", Ir64VectorThreeSameOp.SUB, Sizes.SCALAR_D);
        scalar(rows, "0", "10001", Ir64VectorThreeSameOp.CMTST, Sizes.SCALAR_D);
        scalar(rows, "1", "10001", Ir64VectorThreeSameOp.CMEQ, Sizes.SCALAR_D);
        scalar(rows, "0", "10110", Ir64VectorThreeSameOp.SQDMULH, Sizes.SCALAR_HS);
        scalar(rows, "1", "10110", Ir64VectorThreeSameOp.SQRDMULH, Sizes.SCALAR_HS);
        return List.copyOf(rows);
    }

    /// Uma linha por par `Q size` de `sizes`: `0 Q U prefixo size 1 Rm opcode 1 Rn Rd`.
    private static void add(List<DecodeRow<Ir64Op>> rows, String prefix, String u, String opcode, Sizes sizes,
            DecodeRow.WordDecoder<Ir64Op> build) {
        for (String qSize : sizes.qSize) {
            String q = qSize.substring(0, 1);
            String size = qSize.substring(2);
            rows.add(DecodeRow.of("0 " + q + " " + u + " " + prefix + " " + size + " 1 ..... " + opcode
                    + " 1 ..... .....", null, build));
        }
    }

    private static void vector(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorThreeSameOp op,
            Sizes sizes) {
        add(rows, VECTOR_PREFIX, u, opcode, sizes, (word, address) -> new AdvSimdIntegerOp64.ArithmeticThreeSame(
                op, false, q(word), size(word), rd(word), rn(word), rm(word)));
    }

    private static void pairwise(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorPairwiseOp op,
            Sizes sizes) {
        add(rows, VECTOR_PREFIX, u, opcode, sizes, (word, address) -> new AdvSimdIntegerOp64.ArithmeticPairwise(
                op, q(word), size(word), rd(word), rn(word), rm(word)));
    }

    /// Lógico: `size` é o seletor `opc` da operação (uma linha, `Q` livre).
    private static void logical(List<DecodeRow<Ir64Op>> rows, String u, String opc, Ir64VectorThreeSameOp op) {
        rows.add(DecodeRow.of("0 . " + u + " " + VECTOR_PREFIX + " " + opc + " 1 ..... " + LOGICAL_OPCODE
                + " 1 ..... .....", null, (word, address) -> new AdvSimdIntegerOp64.ArithmeticThreeSame(op, false,
                q(word), BITWISE_ESZ, rd(word), rn(word), rm(word))));
    }

    private static void scalar(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorThreeSameOp op,
            Sizes sizes) {
        add(rows, SCALAR_PREFIX, u, opcode, sizes, (word, address) -> new AdvSimdIntegerOp64.ArithmeticThreeSame(
                op, true, false, size(word), rd(word), rn(word), rm(word)));
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
    }

    private static int size(int word) {
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
