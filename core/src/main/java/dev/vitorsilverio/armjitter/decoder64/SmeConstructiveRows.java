package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeConstructive.Op;

import java.util.List;

/// As 94 linhas de `### SME2 Multi-vector SVE Constructive Unary`/`Binary`/`Select` (B18.12) do `sme.decode`, **geradas
/// por script direto do arquivo** (máscara/valor dos bits fixos; o formato `@zz_*`/`@rshr_*` lido do texto, nunca
/// deduzido pelo nome). As 8 `ADD_aaz`/`SUB_aaz` vivem em {@link SmeArrayVectorRows}.
final class SmeConstructiveRows {
    /// Como `Zd`/`Zn`/`Zm`/shift/`pg` são extraídos da palavra.
    enum Form {
        ZD5_ZN2, ZD5_ZN4, ZD2_ZN5, ZD2_ZN2, ZD4_ZN4, ZD4_ZN2, RSHR_SH, RSHR_SB, RSHR_DH,
        ZD2_ZN5_ZM5, ZD4_ZN5_ZM5, SEL2, SEL4
    }

    /// Feature exigida além de `FEAT_SME`.
    enum Gate { SME2, SME_F16F16, SME2_OR_SVE2P1, SME2_FP8 }

    /// `esz` = `-1` quando vem de `bits[23:22]`.
    record Row(int mask, int value, Op op, int esz, int sources, int destinations, Form form, Gate gate) {
        boolean matches(int word) {
            return (word & mask) == value;
        }
    }

    static final List<Row> ROWS = List.of(
            row(0xFFFFFC20, 0xC160E000, Op.BFCVT, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC20, 0xC160E020, Op.BFCVTN, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC20, 0xC120E000, Op.FCVT_N, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC20, 0xC120E020, Op.FCVTN, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC01, 0xC1A0E000, Op.FCVT_W, 1, 1, 2, Form.ZD2_ZN5, Gate.SME_F16F16),
            row(0xFFFFFC01, 0xC1A0E001, Op.FCVTL, 1, 1, 2, Form.ZD2_ZN5, Gate.SME_F16F16),
            row(0xFFFFFC21, 0xC121E000, Op.FCVTZS, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC131E000, Op.FCVTZS, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC121E020, Op.FCVTZU, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC131E020, Op.FCVTZU, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC122E000, Op.SCVTF, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC132E000, Op.SCVTF, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC122E020, Op.UCVTF, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC132E020, Op.UCVTF, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC1A8E000, Op.FRINTN, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC1B8E000, Op.FRINTN, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC1A9E000, Op.FRINTP, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC1B9E000, Op.FRINTP, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC1AAE000, Op.FRINTM, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC1BAE000, Op.FRINTM, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC21, 0xC1ACE000, Op.FRINTA, 2, 2, 2, Form.ZD2_ZN2, Gate.SME2),
            row(0xFFFFFC63, 0xC1BCE000, Op.FRINTA, 2, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC20, 0xC123E000, Op.SQCVT, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC20, 0xC123E020, Op.UQCVT, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC20, 0xC163E000, Op.SQCVTU, 2, 2, 1, Form.ZD5_ZN2, Gate.SME2),
            row(0xFFFFFC60, 0xC133E000, Op.SQCVT, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC133E020, Op.UQCVT, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC173E000, Op.SQCVTU, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC1B3E000, Op.SQCVT, 3, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC1B3E020, Op.UQCVT, 3, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC1F3E000, Op.SQCVTU, 3, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC133E040, Op.SQCVTN, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC133E060, Op.UQCVTN, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC173E040, Op.SQCVTUN, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC1B3E040, Op.SQCVTN, 3, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC1B3E060, Op.UQCVTN, 3, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC60, 0xC1F3E040, Op.SQCVTUN, 3, 4, 1, Form.ZD5_ZN4, Gate.SME2),
            row(0xFFFFFC01, 0xC165E000, Op.SUNPK, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2),
            row(0xFFFFFC01, 0xC1A5E000, Op.SUNPK, 2, 1, 2, Form.ZD2_ZN5, Gate.SME2),
            row(0xFFFFFC01, 0xC1E5E000, Op.SUNPK, 3, 1, 2, Form.ZD2_ZN5, Gate.SME2),
            row(0xFFFFFC01, 0xC165E001, Op.UUNPK, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2),
            row(0xFFFFFC01, 0xC1A5E001, Op.UUNPK, 2, 1, 2, Form.ZD2_ZN5, Gate.SME2),
            row(0xFFFFFC01, 0xC1E5E001, Op.UUNPK, 3, 1, 2, Form.ZD2_ZN5, Gate.SME2),
            row(0xFFFFFC23, 0xC175E000, Op.SUNPK, 1, 2, 4, Form.ZD4_ZN2, Gate.SME2),
            row(0xFFFFFC23, 0xC1B5E000, Op.SUNPK, 2, 2, 4, Form.ZD4_ZN2, Gate.SME2),
            row(0xFFFFFC23, 0xC1F5E000, Op.SUNPK, 3, 2, 4, Form.ZD4_ZN2, Gate.SME2),
            row(0xFFFFFC23, 0xC175E001, Op.UUNPK, 1, 2, 4, Form.ZD4_ZN2, Gate.SME2),
            row(0xFFFFFC23, 0xC1B5E001, Op.UUNPK, 2, 2, 4, Form.ZD4_ZN2, Gate.SME2),
            row(0xFFFFFC23, 0xC1F5E001, Op.UUNPK, 3, 2, 4, Form.ZD4_ZN2, Gate.SME2),
            row(0xFFFFFC01, 0xC126E000, Op.F1CVT, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC1A6E000, Op.F2CVT, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC126E001, Op.F1CVTL, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC1A6E001, Op.F2CVTL, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC166E000, Op.BF1CVT, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC1E6E000, Op.BF2CVT, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC166E001, Op.BF1CVTL, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC01, 0xC1E6E001, Op.BF2CVTL, 1, 1, 2, Form.ZD2_ZN5, Gate.SME2_FP8),
            row(0xFFFFFC20, 0xC124E000, Op.FCVT_BH, 1, 2, 1, Form.ZD5_ZN2, Gate.SME2_FP8),
            row(0xFFFFFC60, 0xC134E000, Op.FCVT_BS, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2_FP8),
            row(0xFFFFFC60, 0xC134E020, Op.FCVTN_BS, 2, 4, 1, Form.ZD5_ZN4, Gate.SME2_FP8),
            row(0xFF3FFC63, 0xC136E000, Op.ZIP, -1, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC63, 0xC137E000, Op.ZIP, 4, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFF3FFC63, 0xC136E002, Op.UZP, -1, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFFFFC63, 0xC137E002, Op.UZP, 4, 4, 4, Form.ZD4_ZN4, Gate.SME2),
            row(0xFFF0FC20, 0xC1E0D400, Op.SQRSHR, 2, 2, 1, Form.RSHR_SH, Gate.SME2),
            row(0xFFF0FC20, 0xC1E0D420, Op.UQRSHR, 2, 2, 1, Form.RSHR_SH, Gate.SME2),
            row(0xFFF0FC20, 0xC1F0D400, Op.SQRSHRU, 2, 2, 1, Form.RSHR_SH, Gate.SME2),
            row(0xFFE0FC60, 0xC160D800, Op.SQRSHR, 2, 4, 1, Form.RSHR_SB, Gate.SME2),
            row(0xFFA0FC60, 0xC1A0D800, Op.SQRSHR, 3, 4, 1, Form.RSHR_DH, Gate.SME2),
            row(0xFFE0FC60, 0xC160D820, Op.UQRSHR, 2, 4, 1, Form.RSHR_SB, Gate.SME2),
            row(0xFFA0FC60, 0xC1A0D820, Op.UQRSHR, 3, 4, 1, Form.RSHR_DH, Gate.SME2),
            row(0xFFE0FC60, 0xC160D840, Op.SQRSHRU, 2, 4, 1, Form.RSHR_SB, Gate.SME2),
            row(0xFFA0FC60, 0xC1A0D840, Op.SQRSHRU, 3, 4, 1, Form.RSHR_DH, Gate.SME2),
            row(0xFFF0FC20, 0x45B02800, Op.SQRSHRN, 2, 2, 1, Form.RSHR_SH, Gate.SME2_OR_SVE2P1),
            row(0xFFF0FC20, 0x45B03800, Op.UQRSHRN, 2, 2, 1, Form.RSHR_SH, Gate.SME2_OR_SVE2P1),
            row(0xFFF0FC20, 0x45B00800, Op.SQRSHRUN, 2, 2, 1, Form.RSHR_SH, Gate.SME2_OR_SVE2P1),
            row(0xFFE0FC60, 0xC160DC00, Op.SQRSHRN, 2, 4, 1, Form.RSHR_SB, Gate.SME2),
            row(0xFFA0FC60, 0xC1A0DC00, Op.SQRSHRN, 3, 4, 1, Form.RSHR_DH, Gate.SME2),
            row(0xFFE0FC60, 0xC160DC20, Op.UQRSHRN, 2, 4, 1, Form.RSHR_SB, Gate.SME2),
            row(0xFFA0FC60, 0xC1A0DC20, Op.UQRSHRN, 3, 4, 1, Form.RSHR_DH, Gate.SME2),
            row(0xFFE0FC60, 0xC160DC40, Op.SQRSHRUN, 2, 4, 1, Form.RSHR_SB, Gate.SME2),
            row(0xFFA0FC60, 0xC1A0DC40, Op.SQRSHRUN, 3, 4, 1, Form.RSHR_DH, Gate.SME2),
            row(0xFF20FC01, 0xC120D000, Op.ZIP, -1, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFFE0FC01, 0xC120D400, Op.ZIP, 4, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC01, 0xC120D001, Op.UZP, -1, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFFE0FC01, 0xC120D401, Op.UZP, 4, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC01, 0xC120C000, Op.FCLAMP, -1, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC03, 0xC120C800, Op.FCLAMP, -1, 4, 4, Form.ZD4_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC01, 0xC120C400, Op.SCLAMP, -1, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC03, 0xC120CC00, Op.SCLAMP, -1, 4, 4, Form.ZD4_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC01, 0xC120C401, Op.UCLAMP, -1, 2, 2, Form.ZD2_ZN5_ZM5, Gate.SME2),
            row(0xFF20FC03, 0xC120CC01, Op.UCLAMP, -1, 4, 4, Form.ZD4_ZN5_ZM5, Gate.SME2),
            row(0xFF21E021, 0xC1208000, Op.SEL, -1, 2, 2, Form.SEL2, Gate.SME2),
            row(0xFF23E063, 0xC1218000, Op.SEL, -1, 4, 4, Form.SEL4, Gate.SME2)
    );

    private SmeConstructiveRows() {
    }

    private static Row row(int mask, int value, Op op, int esz, int sources, int destinations, Form form, Gate gate) {
        return new Row(mask, value, op, esz, sources, destinations, form, gate);
    }
}
