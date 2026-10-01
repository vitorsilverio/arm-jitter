package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeArrayMultiVector.Op;

import java.util.List;

/// As 107 linhas de `### SME2 Multi-vector Multiple and Single Array Vectors` (B18.9) mais as 100 de `### SME2
/// Multi-vector Multiple Array Vectors` (B18.10) do `sme.decode`, uma por
/// encoding — **transcritas linha a linha, nunca geradas por laço**: a tabela NÃO é um produto cartesiano
/// (`SUMLALL` não tem `n = 1`, `SDOT` só tem `n = 2`/`4`, `FMLAL_n1_hb` usa o prefixo `001 1` onde `FMLAL_n1_sh`
/// usa `001 0`, …). Cada linha guarda `mask`/`value` (os bits fixos do `.decode`), o número de vetores `n` do grupo
/// e o formato do campo `off` (`@azz_nx1_o3`/`o3x2`/`o2x2`/`o2x4`/`o1x4`): `offsetBits` bits baixos, multiplicados
/// por `offsetScale` (`times_2`/`times_4`, o número de vetores de `ZA` que a instrução escreve por membro).
final class SmeArrayVectorRows {
    /// Como os operandos `Zn`/`Zm` do grupo são codificados.
    enum Form {
        /// `_n1` (B18.9): `zn:5` cru (dá a volta em `Z31`) e UM `zm:4` avulso (`Z0`-`Z15`).
        SINGLE,
        /// `_nn` (B18.10): `Zn` e `Zm` são grupos alinhados (`%zn_ax*`/`%zm_ax*`).
        MULTIPLE,
        /// `FADD`/`FSUB`/`BFADD`/`BFSUB` `_nn` (`&az_n`): SEM `Zn` — o único grupo de `Z` é `Zm`, extraído pela base de
        /// bits de `zn` (`zm=%zn_ax2`/`%zn_ax4`).
        ACCUMULATE
    }

    /// Uma linha do `.decode`.
    record Row(int mask, int value, Op op, int count, int offsetBits, int offsetScale, Form form) {
        boolean matches(int word) {
            return (word & mask) == value;
        }

        /// `off` já escalado.
        int offset(int word) {
            return (word & ((1 << offsetBits) - 1)) * offsetScale;
        }
    }

    static final List<Row> ROWS = List.of(
            row(0xFFF09C18, 0xC1201810, Op.ADD_S, 2, 3, 1),
            row(0xFFF09C18, 0xC1301810, Op.ADD_S, 4, 3, 1),
            row(0xFFF09C18, 0xC1601810, Op.ADD_D, 2, 3, 1),
            row(0xFFF09C18, 0xC1701810, Op.ADD_D, 4, 3, 1),
            row(0xFFF09C18, 0xC1201818, Op.SUB_S, 2, 3, 1),
            row(0xFFF09C18, 0xC1301818, Op.SUB_S, 4, 3, 1),
            row(0xFFF09C18, 0xC1601818, Op.SUB_D, 2, 3, 1),
            row(0xFFF09C18, 0xC1701818, Op.SUB_D, 4, 3, 1),
            row(0xFFF09C18, 0xC1200C00, Op.FMLAL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1200800, Op.FMLAL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1300800, Op.FMLAL, 4, 2, 2),
            row(0xFFF09C18, 0xC1200C08, Op.FMLSL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1200808, Op.FMLSL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1300808, Op.FMLSL, 4, 2, 2),
            row(0xFFF09C18, 0xC1200C10, Op.BFMLAL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1200810, Op.BFMLAL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1300810, Op.BFMLAL, 4, 2, 2),
            row(0xFFF09C18, 0xC1200C18, Op.BFMLSL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1200818, Op.BFMLSL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1300818, Op.BFMLSL, 4, 2, 2),
            row(0xFFF09C18, 0xC1201000, Op.FDOT, 2, 3, 1),
            row(0xFFF09C18, 0xC1301000, Op.FDOT, 4, 3, 1),
            row(0xFFF09C18, 0xC1201010, Op.BFDOT, 2, 3, 1),
            row(0xFFF09C18, 0xC1301010, Op.BFDOT, 4, 3, 1),
            row(0xFFF09C18, 0xC1201408, Op.USDOT, 2, 3, 1),
            row(0xFFF09C18, 0xC1301408, Op.USDOT, 4, 3, 1),
            row(0xFFF09C18, 0xC1201418, Op.SUDOT, 2, 3, 1),
            row(0xFFF09C18, 0xC1301418, Op.SUDOT, 4, 3, 1),
            row(0xFFF09C18, 0xC1201400, Op.SDOT_4B, 2, 3, 1),
            row(0xFFF09C18, 0xC1301400, Op.SDOT_4B, 4, 3, 1),
            row(0xFFF09C18, 0xC1601400, Op.SDOT_4H, 2, 3, 1),
            row(0xFFF09C18, 0xC1701400, Op.SDOT_4H, 4, 3, 1),
            row(0xFFF09C18, 0xC1601408, Op.SDOT_2H, 2, 3, 1),
            row(0xFFF09C18, 0xC1701408, Op.SDOT_2H, 4, 3, 1),
            row(0xFFF09C18, 0xC1201410, Op.UDOT_4B, 2, 3, 1),
            row(0xFFF09C18, 0xC1301410, Op.UDOT_4B, 4, 3, 1),
            row(0xFFF09C18, 0xC1601410, Op.UDOT_4H, 2, 3, 1),
            row(0xFFF09C18, 0xC1701410, Op.UDOT_4H, 4, 3, 1),
            row(0xFFF09C18, 0xC1601418, Op.UDOT_2H, 2, 3, 1),
            row(0xFFF09C18, 0xC1701418, Op.UDOT_2H, 4, 3, 1),
            row(0xFFF09C18, 0xC1600C00, Op.SMLAL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1600800, Op.SMLAL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1700800, Op.SMLAL, 4, 2, 2),
            row(0xFFF09C18, 0xC1600C08, Op.SMLSL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1600808, Op.SMLSL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1700808, Op.SMLSL, 4, 2, 2),
            row(0xFFF09C18, 0xC1600C10, Op.UMLAL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1600810, Op.UMLAL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1700810, Op.UMLAL, 4, 2, 2),
            row(0xFFF09C18, 0xC1600C18, Op.UMLSL, 1, 3, 2),
            row(0xFFF09C1C, 0xC1600818, Op.UMLSL, 2, 2, 2),
            row(0xFFF09C1C, 0xC1700818, Op.UMLSL, 4, 2, 2),
            row(0xFFF09C1C, 0xC1200400, Op.SMLALL_S, 1, 2, 4),
            row(0xFFF09C1C, 0xC1600400, Op.SMLALL_D, 1, 2, 4),
            row(0xFFF09C1E, 0xC1200000, Op.SMLALL_S, 2, 1, 4),
            row(0xFFF09C1E, 0xC1600000, Op.SMLALL_D, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300000, Op.SMLALL_S, 4, 1, 4),
            row(0xFFF09C1E, 0xC1700000, Op.SMLALL_D, 4, 1, 4),
            row(0xFFF09C1C, 0xC1200408, Op.SMLSLL_S, 1, 2, 4),
            row(0xFFF09C1C, 0xC1600408, Op.SMLSLL_D, 1, 2, 4),
            row(0xFFF09C1E, 0xC1200008, Op.SMLSLL_S, 2, 1, 4),
            row(0xFFF09C1E, 0xC1600008, Op.SMLSLL_D, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300008, Op.SMLSLL_S, 4, 1, 4),
            row(0xFFF09C1E, 0xC1700008, Op.SMLSLL_D, 4, 1, 4),
            row(0xFFF09C1C, 0xC1200410, Op.UMLALL_S, 1, 2, 4),
            row(0xFFF09C1C, 0xC1600410, Op.UMLALL_D, 1, 2, 4),
            row(0xFFF09C1E, 0xC1200010, Op.UMLALL_S, 2, 1, 4),
            row(0xFFF09C1E, 0xC1600010, Op.UMLALL_D, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300010, Op.UMLALL_S, 4, 1, 4),
            row(0xFFF09C1E, 0xC1700010, Op.UMLALL_D, 4, 1, 4),
            row(0xFFF09C1C, 0xC1200418, Op.UMLSLL_S, 1, 2, 4),
            row(0xFFF09C1C, 0xC1600418, Op.UMLSLL_D, 1, 2, 4),
            row(0xFFF09C1E, 0xC1200018, Op.UMLSLL_S, 2, 1, 4),
            row(0xFFF09C1E, 0xC1600018, Op.UMLSLL_D, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300018, Op.UMLSLL_S, 4, 1, 4),
            row(0xFFF09C1E, 0xC1700018, Op.UMLSLL_D, 4, 1, 4),
            row(0xFFF09C1C, 0xC1200404, Op.USMLALL, 1, 2, 4),
            row(0xFFF09C1E, 0xC1200004, Op.USMLALL, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300004, Op.USMLALL, 4, 1, 4),
            row(0xFFF09C1E, 0xC1200014, Op.SUMLALL, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300014, Op.SUMLALL, 4, 1, 4),
            row(0xFFF09C18, 0xC1601C00, Op.BFMLA, 2, 3, 1),
            row(0xFFF09C18, 0xC1201C00, Op.FMLA_H, 2, 3, 1),
            row(0xFFF09C18, 0xC1201800, Op.FMLA_S, 2, 3, 1),
            row(0xFFF09C18, 0xC1601800, Op.FMLA_D, 2, 3, 1),
            row(0xFFF09C18, 0xC1701C00, Op.BFMLA, 4, 3, 1),
            row(0xFFF09C18, 0xC1301C00, Op.FMLA_H, 4, 3, 1),
            row(0xFFF09C18, 0xC1301800, Op.FMLA_S, 4, 3, 1),
            row(0xFFF09C18, 0xC1701800, Op.FMLA_D, 4, 3, 1),
            row(0xFFF09C18, 0xC1601C08, Op.BFMLS, 2, 3, 1),
            row(0xFFF09C18, 0xC1201C08, Op.FMLS_H, 2, 3, 1),
            row(0xFFF09C18, 0xC1201808, Op.FMLS_S, 2, 3, 1),
            row(0xFFF09C18, 0xC1601808, Op.FMLS_D, 2, 3, 1),
            row(0xFFF09C18, 0xC1701C08, Op.BFMLS, 4, 3, 1),
            row(0xFFF09C18, 0xC1301C08, Op.FMLS_H, 4, 3, 1),
            row(0xFFF09C18, 0xC1301808, Op.FMLS_S, 4, 3, 1),
            row(0xFFF09C18, 0xC1701808, Op.FMLS_D, 4, 3, 1),
            row(0xFFF09C1C, 0xC1300400, Op.FMLALL_B, 1, 2, 4),
            row(0xFFF09C1E, 0xC1200002, Op.FMLALL_B, 2, 1, 4),
            row(0xFFF09C1E, 0xC1300002, Op.FMLALL_B, 4, 1, 4),
            row(0xFFF09C18, 0xC1201018, Op.FDOT_SB, 2, 3, 1),
            row(0xFFF09C18, 0xC1301018, Op.FDOT_SB, 4, 3, 1),
            row(0xFFF09C18, 0xC1300C00, Op.FMLAL_HB, 1, 3, 2),
            row(0xFFF09C1C, 0xC1200804, Op.FMLAL_HB, 2, 2, 2),
            row(0xFFF09C1C, 0xC1300804, Op.FMLAL_HB, 4, 2, 2),
            row(0xFFF09C18, 0xC1201008, Op.FDOT_HB, 2, 3, 1),
            row(0xFFF09C18, 0xC1301008, Op.FDOT_HB, 4, 3, 1),
            // ── `### SME2 Multi-vector Multiple Array Vectors` (B18.10, `_nn`), linhas 463-619 do `sme.decode` ──
            row(0xFFE19C38, 0xC1A01810, Op.ADD_S, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11810, Op.ADD_S, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01810, Op.ADD_D, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11810, Op.ADD_D, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01818, Op.SUB_S, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11818, Op.SUB_S, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01818, Op.SUB_D, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11818, Op.SUB_D, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1A00800, Op.FMLAL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1A10800, Op.FMLAL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1A00808, Op.FMLSL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1A10808, Op.FMLSL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1A00810, Op.BFMLAL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1A10810, Op.BFMLAL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1A00818, Op.BFMLSL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1A10818, Op.BFMLSL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01000, Op.FDOT, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11000, Op.FDOT, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01010, Op.BFDOT, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11010, Op.BFDOT, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01408, Op.USDOT, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11408, Op.USDOT, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01400, Op.SDOT_4B, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11400, Op.SDOT_4B, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01400, Op.SDOT_4H, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11400, Op.SDOT_4H, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01408, Op.SDOT_2H, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11408, Op.SDOT_2H, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01410, Op.UDOT_4B, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11410, Op.UDOT_4B, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01410, Op.UDOT_4H, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11410, Op.UDOT_4H, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01418, Op.UDOT_2H, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11418, Op.UDOT_2H, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1E00800, Op.SMLAL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1E10800, Op.SMLAL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1E00808, Op.SMLSL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1E10808, Op.SMLSL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1E00810, Op.UMLAL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1E10810, Op.UMLAL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1E00818, Op.UMLSL, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1E10818, Op.UMLSL, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1A00000, Op.SMLALL_S, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1E00000, Op.SMLALL_D, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1A10000, Op.SMLALL_S, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1E10000, Op.SMLALL_D, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1A00008, Op.SMLSLL_S, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1E00008, Op.SMLSLL_D, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1A10008, Op.SMLSLL_S, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1E10008, Op.SMLSLL_D, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1A00010, Op.UMLALL_S, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1E00010, Op.UMLALL_D, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1A10010, Op.UMLALL_S, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1E10010, Op.UMLALL_D, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1A00018, Op.UMLSLL_S, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1E00018, Op.UMLSLL_D, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1A10018, Op.UMLSLL_S, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1E10018, Op.UMLSLL_D, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1A00004, Op.USMLALL, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1A10004, Op.USMLALL, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01008, Op.BFMLA, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01008, Op.FMLA_H, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01800, Op.FMLA_S, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01800, Op.FMLA_D, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11008, Op.BFMLA, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11008, Op.FMLA_H, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11800, Op.FMLA_S, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11800, Op.FMLA_D, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01018, Op.BFMLS, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01018, Op.FMLS_H, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01808, Op.FMLS_S, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1E01808, Op.FMLS_D, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11018, Op.BFMLS, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11018, Op.FMLS_H, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11808, Op.FMLS_S, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1E11808, Op.FMLS_D, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C3E, 0xC1A00020, Op.FMLALL_B, 2, 1, 4, Form.MULTIPLE),
            row(0xFFE39C7E, 0xC1A10020, Op.FMLALL_B, 4, 1, 4, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01030, Op.FDOT_SB, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11030, Op.FDOT_SB, 4, 3, 1, Form.MULTIPLE),
            row(0xFFE19C3C, 0xC1A00820, Op.FMLAL_HB, 2, 2, 2, Form.MULTIPLE),
            row(0xFFE39C7C, 0xC1A10820, Op.FMLAL_HB, 4, 2, 2, Form.MULTIPLE),
            row(0xFFE19C38, 0xC1A01020, Op.FDOT_HB, 2, 3, 1, Form.MULTIPLE),
            row(0xFFE39C78, 0xC1A11020, Op.FDOT_HB, 4, 3, 1, Form.MULTIPLE),
            row(0xFFFF9C38, 0xC1A41C00, Op.FADD_H, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1A01C00, Op.FADD_S, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1E01C00, Op.FADD_D, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1A51C00, Op.FADD_H, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1A11C00, Op.FADD_S, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1E11C00, Op.FADD_D, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1A41C08, Op.FSUB_H, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1A01C08, Op.FSUB_S, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1E01C08, Op.FSUB_D, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1A51C08, Op.FSUB_H, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1A11C08, Op.FSUB_S, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1E11C08, Op.FSUB_D, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1E41C00, Op.BFADD, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1E51C00, Op.BFADD, 4, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C38, 0xC1E41C08, Op.BFSUB, 2, 3, 1, Form.ACCUMULATE),
            row(0xFFFF9C78, 0xC1E51C08, Op.BFSUB, 4, 3, 1, Form.ACCUMULATE)
    );

    private SmeArrayVectorRows() {
    }

    private static Row row(int mask, int value, Op op, int count, int offsetBits, int offsetScale) {
        return new Row(mask, value, op, count, offsetBits, offsetScale, Form.SINGLE);
    }

    private static Row row(int mask, int value, Op op, int count, int offsetBits, int offsetScale, Form form) {
        return new Row(mask, value, op, count, offsetBits, offsetScale, form);
    }
}
