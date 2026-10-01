package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeArrayMultiVector.Op;

import java.util.List;

/// As 113 linhas de `### SME2 Multi-vector Indexed` (B18.11) do `sme.decode`, **geradas por script direto do arquivo
/// (`@azx_*` + os `%idx*` lidos do próprio texto, nunca deduzidos pelo nome)**: uma por encoding, com `mask`/`value`
/// dos bits fixos, o número `n` de vetores de destino em `ZA`, como `Zn` é codificado ([Zn]), o campo `off` (largura e
/// escala) e o ÍNDICE do elemento de `Zm`, montado de bits NÃO contíguos — cada `IDX_*` é uma lista de pares
/// `(bit baixo, largura)`, do pedaço mais significativo para o menos (`%idx3_15_10` = `15:1 10:2`).
final class SmeArrayIndexedRows {
    /// Como `Zn` é codificado.
    enum Zn {
        /// `zn:5` (bits 9:5), registrador cru (`@azx_1x1_*`: `n = 1`).
        S5,
        /// `%zn_ax2` (bits 9:6) × 2.
        A2,
        /// `%zn_ax4` (bits 9:7) × 4.
        A4
    }

    /// Uma linha do `.decode`.
    record Row(int mask, int value, Op op, int count, Zn zn, int offsetBits, int offsetScale, int[] indexChunks) {
        boolean matches(int word) {
            return (word & mask) == value;
        }

        /// `off` já escalado (`%off*_x*`: os bits baixos da palavra).
        int offset(int word) {
            return (word & ((1 << offsetBits) - 1)) * offsetScale;
        }

        /// O índice do elemento de `Zm`, concatenando os pedaços na ordem do `.decode`.
        int index(int word) {
            int index = 0;
            for (int i = 0; i < indexChunks.length; i += 2) {
                index = (index << indexChunks[i + 1]) | ((word >>> indexChunks[i]) & ((1 << indexChunks[i + 1]) - 1));
            }
            return index;
        }

        /// Primeiro registrador de `Zn` (já multiplicado nos formatos alinhados).
        int znBase(int word) {
            return switch (zn) {
                case S5 -> (word >>> ZN_SHIFT) & 0b11111;
                case A2 -> ((word >>> ZN_X2_SHIFT) & 0b1111) * 2;
                case A4 -> ((word >>> ZN_X4_SHIFT) & 0b111) * 4;
            };
        }
    }

    private static final int ZN_SHIFT = 5;
    private static final int ZN_X2_SHIFT = 6;
    private static final int ZN_X4_SHIFT = 7;
    static final int PREFIX_MASK = 0xFF000000;
    static final int PREFIX_VALUE = 0xC1000000;

    private static final int[] IDX_15_1_10_2 = {15, 1, 10, 2};
    private static final int[] IDX_10_2_2_1 = {10, 2, 2, 1};
    private static final int[] IDX_10_2 = {10, 2};
    private static final int[] IDX_10_1 = {10, 1};
    private static final int[] IDX_15_1_10_3 = {15, 1, 10, 3};
    private static final int[] IDX_10_2_1_2 = {10, 2, 1, 2};
    private static final int[] IDX_10_1_1_2 = {10, 1, 1, 2};
    private static final int[] IDX_10_2_3_1 = {10, 2, 3, 1};
    private static final int[] IDX_15_1_10_2_3_1 = {15, 1, 10, 2, 3, 1};
    private static final int[] IDX_10_2_2_2 = {10, 2, 2, 2};
    private static final int[] IDX_10_1_3_1 = {10, 1, 3, 1};

    static final List<Row> ROWS = List.of(
            row(0xFFF01018, 0xC1801000, Op.FMLAL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1901000, Op.FMLAL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1909000, Op.FMLAL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF01018, 0xC1801008, Op.FMLSL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1901008, Op.FMLSL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1909008, Op.FMLSL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF01018, 0xC1801010, Op.BFMLAL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1901010, Op.BFMLAL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1909010, Op.BFMLAL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF01018, 0xC1801018, Op.BFMLSL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1901018, Op.BFMLSL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1909018, Op.BFMLSL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF09038, 0xC1501008, Op.FDOT, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509008, Op.FDOT, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1501018, Op.BFDOT, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509018, Op.BFDOT, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1500008, Op.FVDOT_SH, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1500018, Op.BFVDOT, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1501000, Op.SDOT_2H, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509000, Op.SDOT_2H, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1501020, Op.SDOT_4B, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509020, Op.SDOT_4B, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09838, 0xC1D00008, Op.SDOT_4H, 2, Zn.A2, 3, 1, IDX_10_1),
            row(0xFFF09878, 0xC1D08008, Op.SDOT_4H, 4, Zn.A4, 3, 1, IDX_10_1),
            row(0xFFF09038, 0xC1501010, Op.UDOT_2H, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509010, Op.UDOT_2H, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1501030, Op.UDOT_4B, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509030, Op.UDOT_4B, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09838, 0xC1D00018, Op.UDOT_4H, 2, Zn.A2, 3, 1, IDX_10_1),
            row(0xFFF09878, 0xC1D08018, Op.UDOT_4H, 4, Zn.A4, 3, 1, IDX_10_1),
            row(0xFFF09038, 0xC1501028, Op.USDOT, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509028, Op.USDOT, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1501038, Op.SUDOT, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1509038, Op.SUDOT, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09038, 0xC1500020, Op.SVDOT_2H, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1508020, Op.SVDOT_4B, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09878, 0xC1D08808, Op.SVDOT_4H, 4, Zn.A4, 3, 1, IDX_10_1),
            row(0xFFF09038, 0xC1500030, Op.UVDOT_2H, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1508030, Op.UVDOT_4B, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09878, 0xC1D08818, Op.UVDOT_4H, 4, Zn.A4, 3, 1, IDX_10_1),
            row(0xFFF09078, 0xC1508038, Op.SUVDOT, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1508028, Op.USVDOT, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF01018, 0xC1C01000, Op.SMLAL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1D01000, Op.SMLAL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1D09000, Op.SMLAL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF01018, 0xC1C01008, Op.SMLSL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1D01008, Op.SMLSL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1D09008, Op.SMLSL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF01018, 0xC1C01010, Op.UMLAL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1D01010, Op.UMLAL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1D09010, Op.UMLAL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF01018, 0xC1C01018, Op.UMLSL, 1, Zn.S5, 3, 2, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1D01018, Op.UMLSL, 2, Zn.A2, 2, 2, IDX_10_2_2_1),
            row(0xFFF09078, 0xC1D09018, Op.UMLSL, 4, Zn.A4, 2, 2, IDX_10_2_2_1),
            row(0xFFF0001C, 0xC1000000, Op.SMLALL_S, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF0101C, 0xC1800000, Op.SMLALL_D, 1, Zn.S5, 2, 4, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1100000, Op.SMLALL_S, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09838, 0xC1900000, Op.SMLALL_D, 2, Zn.A2, 1, 4, IDX_10_1_1_2),
            row(0xFFF09078, 0xC1108000, Op.SMLALL_S, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF09878, 0xC1908000, Op.SMLALL_D, 4, Zn.A4, 1, 4, IDX_10_1_1_2),
            row(0xFFF0001C, 0xC1000008, Op.SMLSLL_S, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF0101C, 0xC1800008, Op.SMLSLL_D, 1, Zn.S5, 2, 4, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1100008, Op.SMLSLL_S, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09838, 0xC1900008, Op.SMLSLL_D, 2, Zn.A2, 1, 4, IDX_10_1_1_2),
            row(0xFFF09078, 0xC1108008, Op.SMLSLL_S, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF09878, 0xC1908008, Op.SMLSLL_D, 4, Zn.A4, 1, 4, IDX_10_1_1_2),
            row(0xFFF0001C, 0xC1000010, Op.UMLALL_S, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF0101C, 0xC1800010, Op.UMLALL_D, 1, Zn.S5, 2, 4, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1100010, Op.UMLALL_S, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09838, 0xC1900010, Op.UMLALL_D, 2, Zn.A2, 1, 4, IDX_10_1_1_2),
            row(0xFFF09078, 0xC1108010, Op.UMLALL_S, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF09878, 0xC1908010, Op.UMLALL_D, 4, Zn.A4, 1, 4, IDX_10_1_1_2),
            row(0xFFF0001C, 0xC1000018, Op.UMLSLL_S, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF0101C, 0xC1800018, Op.UMLSLL_D, 1, Zn.S5, 2, 4, IDX_15_1_10_2),
            row(0xFFF09038, 0xC1100018, Op.UMLSLL_S, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09838, 0xC1900018, Op.UMLSLL_D, 2, Zn.A2, 1, 4, IDX_10_1_1_2),
            row(0xFFF09078, 0xC1108018, Op.UMLSLL_S, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF09878, 0xC1908018, Op.UMLSLL_D, 4, Zn.A4, 1, 4, IDX_10_1_1_2),
            row(0xFFF0001C, 0xC1000004, Op.USMLALL, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF09038, 0xC1100020, Op.USMLALL, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09078, 0xC1108020, Op.USMLALL, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF0001C, 0xC1000014, Op.SUMLALL, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF09038, 0xC1100030, Op.SUMLALL, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09078, 0xC1108030, Op.SUMLALL, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF09030, 0xC1101020, Op.BFMLA, 2, Zn.A2, 3, 1, IDX_10_2_3_1),
            row(0xFFF09030, 0xC1101000, Op.FMLA_H, 2, Zn.A2, 3, 1, IDX_10_2_3_1),
            row(0xFFF09038, 0xC1500000, Op.FMLA_S, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09838, 0xC1D00000, Op.FMLA_D, 2, Zn.A2, 3, 1, IDX_10_1),
            row(0xFFF09070, 0xC1109020, Op.BFMLA, 4, Zn.A4, 3, 1, IDX_10_2_3_1),
            row(0xFFF09070, 0xC1109000, Op.FMLA_H, 4, Zn.A4, 3, 1, IDX_10_2_3_1),
            row(0xFFF09078, 0xC1508000, Op.FMLA_S, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09878, 0xC1D08000, Op.FMLA_D, 4, Zn.A4, 3, 1, IDX_10_1),
            row(0xFFF09030, 0xC1101030, Op.BFMLS, 2, Zn.A2, 3, 1, IDX_10_2_3_1),
            row(0xFFF09030, 0xC1101010, Op.FMLS_H, 2, Zn.A2, 3, 1, IDX_10_2_3_1),
            row(0xFFF09038, 0xC1500010, Op.FMLS_S, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09838, 0xC1D00010, Op.FMLS_D, 2, Zn.A2, 3, 1, IDX_10_1),
            row(0xFFF09070, 0xC1109030, Op.BFMLS, 4, Zn.A4, 3, 1, IDX_10_2_3_1),
            row(0xFFF09070, 0xC1109010, Op.FMLS_H, 4, Zn.A4, 3, 1, IDX_10_2_3_1),
            row(0xFFF09078, 0xC1508010, Op.FMLS_S, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF09878, 0xC1D08010, Op.FMLS_D, 4, Zn.A4, 3, 1, IDX_10_1),
            row(0xFFF0001C, 0xC1400000, Op.FMLALL_B, 1, Zn.S5, 2, 4, IDX_15_1_10_3),
            row(0xFFF09038, 0xC1900020, Op.FMLALL_B, 2, Zn.A2, 1, 4, IDX_10_2_1_2),
            row(0xFFF09078, 0xC1108040, Op.FMLALL_B, 4, Zn.A4, 1, 4, IDX_10_2_1_2),
            row(0xFFF09038, 0xC1500038, Op.FDOT_SB, 2, Zn.A2, 3, 1, IDX_10_2),
            row(0xFFF09078, 0xC1508008, Op.FDOT_SB, 4, Zn.A4, 3, 1, IDX_10_2),
            row(0xFFF01010, 0xC1C00000, Op.FMLAL_HB, 1, Zn.S5, 3, 2, IDX_15_1_10_2_3_1),
            row(0xFFF09030, 0xC1901030, Op.FMLAL_HB, 2, Zn.A2, 2, 2, IDX_10_2_2_2),
            row(0xFFF09070, 0xC1909020, Op.FMLAL_HB, 4, Zn.A4, 2, 2, IDX_10_2_2_2),
            row(0xFFF09030, 0xC1D00020, Op.FDOT_HB, 2, Zn.A2, 3, 1, IDX_10_2_3_1),
            row(0xFFF09070, 0xC1109040, Op.FDOT_HB, 4, Zn.A4, 3, 1, IDX_10_2_3_1),
            row(0xFFF09830, 0xC1D00800, Op.FVDOTB, 4, Zn.A2, 3, 1, IDX_10_1_3_1),
            row(0xFFF09830, 0xC1D00810, Op.FVDOTT, 4, Zn.A2, 3, 1, IDX_10_1_3_1),
            row(0xFFF09030, 0xC1D01020, Op.FVDOT_HB, 2, Zn.A2, 3, 1, IDX_10_2_3_1)
    );

    private SmeArrayIndexedRows() {
    }

    private static Row row(int mask, int value, Op op, int count, Zn zn, int offsetBits, int offsetScale,
            int[] indexChunks) {
        return new Row(mask, value, op, count, zn, offsetBits, offsetScale, indexChunks);
    }
}
