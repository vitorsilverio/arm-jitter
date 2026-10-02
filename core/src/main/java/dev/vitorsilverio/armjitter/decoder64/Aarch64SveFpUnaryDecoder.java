package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64.FpUnary.Op;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;

/// Decoder SVE das operações unárias de ponto flutuante predicadas da B17.16: as 105 linhas de `### SVE FP Unary
/// Operations Predicated Group` do `sve.decode` do QEMU (conversões de precisão, FP↔inteiro, `FRINT*`, `FRINT32/64`,
/// `FRECPX`, `FSQRT`; formas merging `_m` no prefixo `0x65` e zeroing `_z` no `0x64`).
///
/// **A tabela abaixo foi transcrita do `.decode` linha a linha** (uma entrada por linha, na ordem do arquivo, com o
/// nome original no comentário) — as listas de pares de `FCVTZS`, `SCVTF` `_m` e `SCVTF` `_z` não são as mesmas nem
/// estão na mesma ordem, então nenhum laço sobre tamanhos as reproduz. Cada entrada traz `máscara`/`valor` dos bits
/// fixos, a operação, os formatos de origem e destino (`1` = meia/16 bits, `2` = simples/32, `3` = dupla/64; `0` no
/// destino de `BFCVT` = BFloat16) e a feature. **`-1` = o tamanho vem do campo `esz` (bits 23:22)**: as `FRINT*`
/// genéricas, `FRECPX` e `FSQRT`, onde `esz = 0` não é alocado (G8). Nas demais linhas os bits 23:22 fazem parte do
/// opcode e nunca são lidos como `esz`.
///
/// Gates (o QEMU é a referência): as formas `_z` são `FEAT_SVE2p2`; as 16 `FRINT32/64{X,Z}` também são `FEAT_SVE2p2`
/// (a spec da task as atribuía a `FEAT_FRINTTS`, mas o `.decode`/`translate-sve.c` só as liga a SVE2p2); `BFCVT_m` é
/// `FEAT_BF16`; as demais valem sob `FEAT_SVE` (o gate de SVE é do chamador). `FCVTX_ds_m` NÃO está aqui — é SVE2 e
/// mora na B17.23.
final class Aarch64SveFpUnaryDecoder {
    private static final int REGISTER_MASK = 0b11111;
    private static final int PREDICATE_MASK = 0b111;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int ESZ_SHIFT = 22;
    private static final int ESZ_MASK = 0b11;
    private static final int ESZ_FROM_FIELD = -1;

    private record Entry(int mask, int value, Op op, int source, int destination, boolean zeroing,
            Aarch64Feature feature) {
    }

    private static Entry entry(long mask, long value, Op op, int source, int destination, boolean zeroing,
            Aarch64Feature feature) {
        return new Entry((int) mask, (int) value, op, source, destination, zeroing, feature);
    }

    private static final Entry[] TABLE = {
        entry(0xFFFFE000L, 0x6588A000L, Op.FCVT, 2, 1, false, Aarch64Feature.SVE), // FCVT_sh_m
        entry(0xFFFFE000L, 0x6589A000L, Op.FCVT, 1, 2, false, Aarch64Feature.SVE), // FCVT_hs_m
        entry(0xFFFFE000L, 0x658AA000L, Op.BFCVT, 2, 0, false, Aarch64Feature.BFLOAT16), // BFCVT_m
        entry(0xFFFFE000L, 0x65C8A000L, Op.FCVT, 3, 1, false, Aarch64Feature.SVE), // FCVT_dh_m
        entry(0xFFFFE000L, 0x65C9A000L, Op.FCVT, 1, 3, false, Aarch64Feature.SVE), // FCVT_hd_m
        entry(0xFFFFE000L, 0x65CAA000L, Op.FCVT, 3, 2, false, Aarch64Feature.SVE), // FCVT_ds_m
        entry(0xFFFFE000L, 0x65CBA000L, Op.FCVT, 2, 3, false, Aarch64Feature.SVE), // FCVT_sd_m
        entry(0xFFFFE000L, 0x641AC000L, Op.FCVTX, 3, 2, true, Aarch64Feature.SVE2_2), // FCVTX_ds_z
        entry(0xFFFFE000L, 0x649A8000L, Op.FCVT, 2, 1, true, Aarch64Feature.SVE2_2), // FCVT_sh_z
        entry(0xFFFFE000L, 0x649AA000L, Op.FCVT, 1, 2, true, Aarch64Feature.SVE2_2), // FCVT_hs_z
        entry(0xFFFFE000L, 0x649AC000L, Op.BFCVT, 2, 0, true, Aarch64Feature.SVE2_2), // BFCVT_z
        entry(0xFFFFE000L, 0x64DA8000L, Op.FCVT, 3, 1, true, Aarch64Feature.SVE2_2), // FCVT_dh_z
        entry(0xFFFFE000L, 0x64DAA000L, Op.FCVT, 1, 3, true, Aarch64Feature.SVE2_2), // FCVT_hd_z
        entry(0xFFFFE000L, 0x64DAC000L, Op.FCVT, 3, 2, true, Aarch64Feature.SVE2_2), // FCVT_ds_z
        entry(0xFFFFE000L, 0x64DAE000L, Op.FCVT, 2, 3, true, Aarch64Feature.SVE2_2), // FCVT_sd_z
        entry(0xFFFFE000L, 0x655AA000L, Op.FCVTZS, 1, 1, false, Aarch64Feature.SVE), // FCVTZS_hh_m
        entry(0xFFFFE000L, 0x655BA000L, Op.FCVTZU, 1, 1, false, Aarch64Feature.SVE), // FCVTZU_hh_m
        entry(0xFFFFE000L, 0x655CA000L, Op.FCVTZS, 1, 2, false, Aarch64Feature.SVE), // FCVTZS_hs_m
        entry(0xFFFFE000L, 0x655DA000L, Op.FCVTZU, 1, 2, false, Aarch64Feature.SVE), // FCVTZU_hs_m
        entry(0xFFFFE000L, 0x655EA000L, Op.FCVTZS, 1, 3, false, Aarch64Feature.SVE), // FCVTZS_hd_m
        entry(0xFFFFE000L, 0x655FA000L, Op.FCVTZU, 1, 3, false, Aarch64Feature.SVE), // FCVTZU_hd_m
        entry(0xFFFFE000L, 0x659CA000L, Op.FCVTZS, 2, 2, false, Aarch64Feature.SVE), // FCVTZS_ss_m
        entry(0xFFFFE000L, 0x659DA000L, Op.FCVTZU, 2, 2, false, Aarch64Feature.SVE), // FCVTZU_ss_m
        entry(0xFFFFE000L, 0x65D8A000L, Op.FCVTZS, 3, 2, false, Aarch64Feature.SVE), // FCVTZS_ds_m
        entry(0xFFFFE000L, 0x65D9A000L, Op.FCVTZU, 3, 2, false, Aarch64Feature.SVE), // FCVTZU_ds_m
        entry(0xFFFFE000L, 0x65DCA000L, Op.FCVTZS, 2, 3, false, Aarch64Feature.SVE), // FCVTZS_sd_m
        entry(0xFFFFE000L, 0x65DDA000L, Op.FCVTZU, 2, 3, false, Aarch64Feature.SVE), // FCVTZU_sd_m
        entry(0xFFFFE000L, 0x65DEA000L, Op.FCVTZS, 3, 3, false, Aarch64Feature.SVE), // FCVTZS_dd_m
        entry(0xFFFFE000L, 0x65DFA000L, Op.FCVTZU, 3, 3, false, Aarch64Feature.SVE), // FCVTZU_dd_m
        entry(0xFFFFE000L, 0x645EC000L, Op.FCVTZS, 1, 1, true, Aarch64Feature.SVE2_2), // FCVTZS_hh_z
        entry(0xFFFFE000L, 0x645EE000L, Op.FCVTZU, 1, 1, true, Aarch64Feature.SVE2_2), // FCVTZU_hh_z
        entry(0xFFFFE000L, 0x645F8000L, Op.FCVTZS, 1, 2, true, Aarch64Feature.SVE2_2), // FCVTZS_hs_z
        entry(0xFFFFE000L, 0x645FA000L, Op.FCVTZU, 1, 2, true, Aarch64Feature.SVE2_2), // FCVTZU_hs_z
        entry(0xFFFFE000L, 0x645FC000L, Op.FCVTZS, 1, 3, true, Aarch64Feature.SVE2_2), // FCVTZS_hd_z
        entry(0xFFFFE000L, 0x645FE000L, Op.FCVTZU, 1, 3, true, Aarch64Feature.SVE2_2), // FCVTZU_hd_z
        entry(0xFFFFE000L, 0x649F8000L, Op.FCVTZS, 2, 2, true, Aarch64Feature.SVE2_2), // FCVTZS_ss_z
        entry(0xFFFFE000L, 0x649FA000L, Op.FCVTZU, 2, 2, true, Aarch64Feature.SVE2_2), // FCVTZU_ss_z
        entry(0xFFFFE000L, 0x64DF8000L, Op.FCVTZS, 2, 3, true, Aarch64Feature.SVE2_2), // FCVTZS_sd_z
        entry(0xFFFFE000L, 0x64DFA000L, Op.FCVTZU, 2, 3, true, Aarch64Feature.SVE2_2), // FCVTZU_sd_z
        entry(0xFFFFE000L, 0x64DE8000L, Op.FCVTZS, 3, 2, true, Aarch64Feature.SVE2_2), // FCVTZS_ds_z
        entry(0xFFFFE000L, 0x64DEA000L, Op.FCVTZU, 3, 2, true, Aarch64Feature.SVE2_2), // FCVTZU_ds_z
        entry(0xFFFFE000L, 0x64DFC000L, Op.FCVTZS, 3, 3, true, Aarch64Feature.SVE2_2), // FCVTZS_dd_z
        entry(0xFFFFE000L, 0x64DFE000L, Op.FCVTZU, 3, 3, true, Aarch64Feature.SVE2_2), // FCVTZU_dd_z
        entry(0xFF3FE000L, 0x6500A000L, Op.FRINTN, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTN_m
        entry(0xFF3FE000L, 0x6501A000L, Op.FRINTP, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTP_m
        entry(0xFF3FE000L, 0x6502A000L, Op.FRINTM, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTM_m
        entry(0xFF3FE000L, 0x6503A000L, Op.FRINTZ, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTZ_m
        entry(0xFF3FE000L, 0x6504A000L, Op.FRINTA, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTA_m
        entry(0xFF3FE000L, 0x6506A000L, Op.FRINTX, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTX_m
        entry(0xFF3FE000L, 0x6507A000L, Op.FRINTI, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRINTI_m
        entry(0xFF3FE000L, 0x64188000L, Op.FRINTN, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTN_z
        entry(0xFF3FE000L, 0x6418A000L, Op.FRINTP, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTP_z
        entry(0xFF3FE000L, 0x6418C000L, Op.FRINTM, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTM_z
        entry(0xFF3FE000L, 0x6418E000L, Op.FRINTZ, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTZ_z
        entry(0xFF3FE000L, 0x64198000L, Op.FRINTA, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTA_z
        entry(0xFF3FE000L, 0x6419C000L, Op.FRINTX, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTX_z
        entry(0xFF3FE000L, 0x6419E000L, Op.FRINTI, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRINTI_z
        entry(0xFFFFE000L, 0x6511A000L, Op.FRINT32X, 2, 2, false, Aarch64Feature.SVE2_2), // FRINT32X_s_m
        entry(0xFFFFE000L, 0x6513A000L, Op.FRINT32X, 3, 3, false, Aarch64Feature.SVE2_2), // FRINT32X_d_m
        entry(0xFFFFE000L, 0x6515A000L, Op.FRINT64X, 2, 2, false, Aarch64Feature.SVE2_2), // FRINT64X_s_m
        entry(0xFFFFE000L, 0x6517A000L, Op.FRINT64X, 3, 3, false, Aarch64Feature.SVE2_2), // FRINT64X_d_m
        entry(0xFFFFE000L, 0x641CA000L, Op.FRINT32X, 2, 2, true, Aarch64Feature.SVE2_2), // FRINT32X_s_z
        entry(0xFFFFE000L, 0x641CE000L, Op.FRINT32X, 3, 3, true, Aarch64Feature.SVE2_2), // FRINT32X_d_z
        entry(0xFFFFE000L, 0x641DA000L, Op.FRINT64X, 2, 2, true, Aarch64Feature.SVE2_2), // FRINT64X_s_z
        entry(0xFFFFE000L, 0x641DE000L, Op.FRINT64X, 3, 3, true, Aarch64Feature.SVE2_2), // FRINT64X_d_z
        entry(0xFFFFE000L, 0x6510A000L, Op.FRINT32Z, 2, 2, false, Aarch64Feature.SVE2_2), // FRINT32Z_s_m
        entry(0xFFFFE000L, 0x6512A000L, Op.FRINT32Z, 3, 3, false, Aarch64Feature.SVE2_2), // FRINT32Z_d_m
        entry(0xFFFFE000L, 0x6514A000L, Op.FRINT64Z, 2, 2, false, Aarch64Feature.SVE2_2), // FRINT64Z_s_m
        entry(0xFFFFE000L, 0x6516A000L, Op.FRINT64Z, 3, 3, false, Aarch64Feature.SVE2_2), // FRINT64Z_d_m
        entry(0xFFFFE000L, 0x641C8000L, Op.FRINT32Z, 2, 2, true, Aarch64Feature.SVE2_2), // FRINT32Z_s_z
        entry(0xFFFFE000L, 0x641CC000L, Op.FRINT32Z, 3, 3, true, Aarch64Feature.SVE2_2), // FRINT32Z_d_z
        entry(0xFFFFE000L, 0x641D8000L, Op.FRINT64Z, 2, 2, true, Aarch64Feature.SVE2_2), // FRINT64Z_s_z
        entry(0xFFFFE000L, 0x641DC000L, Op.FRINT64Z, 3, 3, true, Aarch64Feature.SVE2_2), // FRINT64Z_d_z
        entry(0xFF3FE000L, 0x650CA000L, Op.FRECPX, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FRECPX_m
        entry(0xFF3FE000L, 0x650DA000L, Op.FSQRT, ESZ_FROM_FIELD, ESZ_FROM_FIELD, false, Aarch64Feature.SVE), // FSQRT_m
        entry(0xFF3FE000L, 0x641B8000L, Op.FRECPX, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FRECPX_z
        entry(0xFF3FE000L, 0x641BA000L, Op.FSQRT, ESZ_FROM_FIELD, ESZ_FROM_FIELD, true, Aarch64Feature.SVE2_2), // FSQRT_z
        entry(0xFFFFE000L, 0x6552A000L, Op.SCVTF, 1, 1, false, Aarch64Feature.SVE), // SCVTF_hh_m
        entry(0xFFFFE000L, 0x6554A000L, Op.SCVTF, 2, 1, false, Aarch64Feature.SVE), // SCVTF_sh_m
        entry(0xFFFFE000L, 0x6556A000L, Op.SCVTF, 3, 1, false, Aarch64Feature.SVE), // SCVTF_dh_m
        entry(0xFFFFE000L, 0x6594A000L, Op.SCVTF, 2, 2, false, Aarch64Feature.SVE), // SCVTF_ss_m
        entry(0xFFFFE000L, 0x65D0A000L, Op.SCVTF, 2, 3, false, Aarch64Feature.SVE), // SCVTF_sd_m
        entry(0xFFFFE000L, 0x65D4A000L, Op.SCVTF, 3, 2, false, Aarch64Feature.SVE), // SCVTF_ds_m
        entry(0xFFFFE000L, 0x65D6A000L, Op.SCVTF, 3, 3, false, Aarch64Feature.SVE), // SCVTF_dd_m
        entry(0xFFFFE000L, 0x6553A000L, Op.UCVTF, 1, 1, false, Aarch64Feature.SVE), // UCVTF_hh_m
        entry(0xFFFFE000L, 0x6555A000L, Op.UCVTF, 2, 1, false, Aarch64Feature.SVE), // UCVTF_sh_m
        entry(0xFFFFE000L, 0x6557A000L, Op.UCVTF, 3, 1, false, Aarch64Feature.SVE), // UCVTF_dh_m
        entry(0xFFFFE000L, 0x6595A000L, Op.UCVTF, 2, 2, false, Aarch64Feature.SVE), // UCVTF_ss_m
        entry(0xFFFFE000L, 0x65D1A000L, Op.UCVTF, 2, 3, false, Aarch64Feature.SVE), // UCVTF_sd_m
        entry(0xFFFFE000L, 0x65D5A000L, Op.UCVTF, 3, 2, false, Aarch64Feature.SVE), // UCVTF_ds_m
        entry(0xFFFFE000L, 0x65D7A000L, Op.UCVTF, 3, 3, false, Aarch64Feature.SVE), // UCVTF_dd_m
        entry(0xFFFFE000L, 0x645CC000L, Op.SCVTF, 1, 1, true, Aarch64Feature.SVE2_2), // SCVTF_hh_z
        entry(0xFFFFE000L, 0x645D8000L, Op.SCVTF, 2, 1, true, Aarch64Feature.SVE2_2), // SCVTF_sh_z
        entry(0xFFFFE000L, 0x649D8000L, Op.SCVTF, 2, 2, true, Aarch64Feature.SVE2_2), // SCVTF_ss_z
        entry(0xFFFFE000L, 0x64DC8000L, Op.SCVTF, 2, 3, true, Aarch64Feature.SVE2_2), // SCVTF_sd_z
        entry(0xFFFFE000L, 0x645DC000L, Op.SCVTF, 3, 1, true, Aarch64Feature.SVE2_2), // SCVTF_dh_z
        entry(0xFFFFE000L, 0x64DD8000L, Op.SCVTF, 3, 2, true, Aarch64Feature.SVE2_2), // SCVTF_ds_z
        entry(0xFFFFE000L, 0x64DDC000L, Op.SCVTF, 3, 3, true, Aarch64Feature.SVE2_2), // SCVTF_dd_z
        entry(0xFFFFE000L, 0x645CE000L, Op.UCVTF, 1, 1, true, Aarch64Feature.SVE2_2), // UCVTF_hh_z
        entry(0xFFFFE000L, 0x645DA000L, Op.UCVTF, 2, 1, true, Aarch64Feature.SVE2_2), // UCVTF_sh_z
        entry(0xFFFFE000L, 0x649DA000L, Op.UCVTF, 2, 2, true, Aarch64Feature.SVE2_2), // UCVTF_ss_z
        entry(0xFFFFE000L, 0x64DCA000L, Op.UCVTF, 2, 3, true, Aarch64Feature.SVE2_2), // UCVTF_sd_z
        entry(0xFFFFE000L, 0x645DE000L, Op.UCVTF, 3, 1, true, Aarch64Feature.SVE2_2), // UCVTF_dh_z
        entry(0xFFFFE000L, 0x64DDA000L, Op.UCVTF, 3, 2, true, Aarch64Feature.SVE2_2), // UCVTF_ds_z
        entry(0xFFFFE000L, 0x64DDE000L, Op.UCVTF, 3, 3, true, Aarch64Feature.SVE2_2), // UCVTF_dd_z
    };

    private final Aarch64Architecture architecture;

    Aarch64SveFpUnaryDecoder(Aarch64Architecture architecture) {
        this.architecture = architecture;
    }

    /// Decodifica uma palavra dos prefixos `0x64`/`0x65`. `null` = não é deste grupo, ou a arquitetura não declara a
    /// feature da linha, ou é `esz = 0` numa linha de tamanho variável (recusado, G8).
    Ir64Op decode(int word, long address) {
        for (Entry e : TABLE) {
            if ((word & e.mask) != e.value) {
                continue;
            }
            if (!architecture.has(e.feature)) {
                return null;
            }
            int source = e.source;
            int destination = e.destination;
            if (source == ESZ_FROM_FIELD) {
                source = (word >>> ESZ_SHIFT) & ESZ_MASK;
                if (source == 0) {
                    return null;
                }
                destination = source;
            }
            return new SveFpOp64.FpUnary(e.op, source, destination, e.zeroing, word & REGISTER_MASK,
                    (word >>> RN_SHIFT) & REGISTER_MASK, (word >>> PG_SHIFT) & PREDICATE_MASK, address);
        }
        return null;
    }
}
