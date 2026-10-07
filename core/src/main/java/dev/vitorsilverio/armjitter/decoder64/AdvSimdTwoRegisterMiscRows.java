package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;

import java.util.ArrayList;
import java.util.List;

/// E15.15e: o AdvSIMD "two-register misc" — inteiro, FP `_sd`, FP16, narrow e conversões de precisão (BF16/FP8
/// inclusive), vetorial (prefixo `01110`) e escalar (`11110` com `bit30=1`). Antes era o ramo `Rm` `0000x`/`1100x`
/// de `decodeAdvancedSimdInteger` no `Aarch64Decoder`, com `decodeVectorUnaryOpcode`,
/// `decodeVectorUnaryByteOnlyOpcode`, `decodeVectorNarrowUnaryOpcode`, `decodeVectorFpUnaryRmZeroOpcode`,
/// `decodeVectorFpUnaryRmOneOpcode` e as validações de `esz` (B8.7, B8.8, B8.9, B8.18, B8.20, B19.3, B19.4,
/// B19.5.4, B19.7, B19.11, B19.18, E15.9c); aqui cada `size` aceito é uma linha.
///
/// Layout (bit 31 → 0): `0 Q U prefixo(28:24) size(23:22) 10000 opcode(16:12) 10 Rn Rd`; o FP16 é
/// `0 Q U prefixo(28:24) a 1 11100 opcode(16:12) 10 Rn Rd`. Nas formas FP, `bit23` (`a`) é mais um bit de opcode e só
/// `bit22` (`sz`) é tamanho.
///
/// Tamanhos (ARM DDI 0487, conferidos no `a64.decode` do QEMU e no `objdump`):
///
/// - inteiro vetorial: sem `.1d`; `SADDLP`/`SADALP`/`CLS`/`CLZ` sem elemento de 64 bits; `REV64` até `S`, `REV32`
///   até `H`, `REV16` só `B`; `CNT`/`NOT`/`RBIT` só `.8b`/`.16b` (`size` separa as três; o record leva `esz=0`);
/// - inteiro escalar: `ABS`/`NEG`/comparações com zero só `D`; `SUQADD`/`USQADD`/`SQABS`/`SQNEG` qualquer tamanho;
/// - narrow (`XTN`/`SQXTN`/`UQXTN`/`SQXTUN`) e `SHLL`: lado estreito `B`/`H`/`S`; `XTN` e `SHLL` sem forma escalar;
/// - FP `_sd` vetorial sem `.1d`; escalar só comparações com zero, `FRECPE`/`FRSQRTE` e as conversões int↔FP;
/// - FP16 (`FEAT_FP16`): as mesmas operações `_h`, menos `FRINT32*`/`FRINT64*`.
final class AdvSimdTwoRegisterMiscRows {
    private static final int Q_SHIFT = 30;
    private static final int SIZE_SHIFT = 22;
    private static final int SIZE_MASK = 0b11;
    private static final int A_SHIFT = 23;
    private static final int RN_SHIFT = 5;
    private static final int REGISTER_MASK = 0b1_1111;
    /// `esz` de `S` (`log2` dos bytes do elemento): o `esz` das formas FP `_sd` é `SINGLE_ESZ + sz`.
    private static final int SINGLE_ESZ = 2;
    /// `esz` das formas FP16 (e do lado estreito de `BFCVTN`).
    private static final int HALF_ESZ = 1;
    /// `esz` de `D`: `FCVTXN` escalar lê um `f64`.
    private static final int DOUBLE_ESZ = 3;
    /// `CNT`/`NOT`/`RBIT` operam byte a byte.
    private static final int BYTE_ESZ = 0;
    private static final String VECTOR_PREFIX = "01110";
    /// Escalar: prefixo `11110` com `bit30=1` (o `Q` das linhas escalares fixa o `1`).
    private static final String SCALAR_PREFIX = "11110";
    /// `bits[21:17]` do inteiro e do FP `_sd`.
    private static final String SLOT = "10000";
    /// `bits[21:17]` do FP16.
    private static final String FP16_SLOT = "11100";
    /// `opcode` (`bits[16:12]`) de `CNT`/`NOT`/`RBIT`.
    private static final String BYTE_ONLY_OPCODE = "00101";
    /// `opcode` de `FCVTN`/`FCVTXN`/`BFCVTN` (e `FCVTXN` escalar).
    private static final String FCVTN_OPCODE = "10110";
    /// `opcode` de `FCVTL` e dos `*CVTL` de FP8.
    private static final String FCVTL_OPCODE = "10111";
    /// `opcode` de `FRECPX` escalar.
    private static final String FRECPX_OPCODE = "11111";

    /// Tamanhos aceitos: os pares `Q size` (`bit30`, `bits[23:22]`) de cada linha.
    private enum Sizes {
        /// Vetorial `B`/`H`/`S`/`D`, com `D` só em `Q=1`.
        ALL_BUT_1D(". 0.", ". 10", "1 11"),
        /// Vetorial `B`/`H`/`S`.
        BHS(". 0.", ". 10"),
        /// Vetorial `B`/`H`.
        BH(". 0."),
        /// Vetorial, só `B`.
        B(". 00"),
        /// Vetorial, só `S`.
        S(". 10"),
        /// Escalar, só `D`.
        SCALAR_D("1 11"),
        /// Escalar, qualquer tamanho.
        SCALAR_ANY("1 .."),
        /// Escalar `B`/`H`/`S`.
        SCALAR_BHS("1 0.", "1 10");

        private final List<String> qSize;

        Sizes(String... qSize) {
            this.qSize = List.of(qSize);
        }
    }

    static final List<DecodeRow<Ir64Op>> ROWS = rows();

    private AdvSimdTwoRegisterMiscRows() {
    }

    private static List<DecodeRow<Ir64Op>> rows() {
        List<DecodeRow<Ir64Op>> rows = new ArrayList<>();
        // inteiro vetorial — 0 Q U 01110 size 10000 opcode 10 Rn Rd
        integer(rows, "0", "00000", Ir64VectorUnaryOp.REV64, Sizes.BHS);
        integer(rows, "1", "00000", Ir64VectorUnaryOp.REV32, Sizes.BH);
        integer(rows, "0", "00001", Ir64VectorUnaryOp.REV16, Sizes.B);
        integer(rows, "0", "00010", Ir64VectorUnaryOp.SADDLP, Sizes.BHS);
        integer(rows, "1", "00010", Ir64VectorUnaryOp.UADDLP, Sizes.BHS);
        integer(rows, "0", "00011", Ir64VectorUnaryOp.SUQADD, Sizes.ALL_BUT_1D);
        integer(rows, "1", "00011", Ir64VectorUnaryOp.USQADD, Sizes.ALL_BUT_1D);
        integer(rows, "0", "00100", Ir64VectorUnaryOp.CLS, Sizes.BHS);
        integer(rows, "1", "00100", Ir64VectorUnaryOp.CLZ, Sizes.BHS);
        integer(rows, "0", "00110", Ir64VectorUnaryOp.SADALP, Sizes.BHS);
        integer(rows, "1", "00110", Ir64VectorUnaryOp.UADALP, Sizes.BHS);
        integer(rows, "0", "00111", Ir64VectorUnaryOp.SQABS, Sizes.ALL_BUT_1D);
        integer(rows, "1", "00111", Ir64VectorUnaryOp.SQNEG, Sizes.ALL_BUT_1D);
        integer(rows, "0", "01000", Ir64VectorUnaryOp.CMGT0, Sizes.ALL_BUT_1D);
        integer(rows, "1", "01000", Ir64VectorUnaryOp.CMGE0, Sizes.ALL_BUT_1D);
        integer(rows, "0", "01001", Ir64VectorUnaryOp.CMEQ0, Sizes.ALL_BUT_1D);
        integer(rows, "1", "01001", Ir64VectorUnaryOp.CMLE0, Sizes.ALL_BUT_1D);
        integer(rows, "0", "01010", Ir64VectorUnaryOp.CMLT0, Sizes.ALL_BUT_1D);
        integer(rows, "0", "01011", Ir64VectorUnaryOp.ABS, Sizes.ALL_BUT_1D);
        integer(rows, "1", "01011", Ir64VectorUnaryOp.NEG, Sizes.ALL_BUT_1D);
        integer(rows, "0", "11100", Ir64VectorUnaryOp.URECPE, Sizes.S);
        integer(rows, "1", "11100", Ir64VectorUnaryOp.URSQRTE, Sizes.S);
        byteOnly(rows, "0", "00", Ir64VectorUnaryOp.CNT);
        byteOnly(rows, "1", "00", Ir64VectorUnaryOp.NOT);
        byteOnly(rows, "1", "01", Ir64VectorUnaryOp.RBIT);
        // inteiro escalar — 0 1 U 11110 size 10000 opcode 10 Rn Rd
        integerScalar(rows, "0", "00011", Ir64VectorUnaryOp.SUQADD, Sizes.SCALAR_ANY);
        integerScalar(rows, "1", "00011", Ir64VectorUnaryOp.USQADD, Sizes.SCALAR_ANY);
        integerScalar(rows, "0", "00111", Ir64VectorUnaryOp.SQABS, Sizes.SCALAR_ANY);
        integerScalar(rows, "1", "00111", Ir64VectorUnaryOp.SQNEG, Sizes.SCALAR_ANY);
        integerScalar(rows, "0", "01000", Ir64VectorUnaryOp.CMGT0, Sizes.SCALAR_D);
        integerScalar(rows, "1", "01000", Ir64VectorUnaryOp.CMGE0, Sizes.SCALAR_D);
        integerScalar(rows, "0", "01001", Ir64VectorUnaryOp.CMEQ0, Sizes.SCALAR_D);
        integerScalar(rows, "1", "01001", Ir64VectorUnaryOp.CMLE0, Sizes.SCALAR_D);
        integerScalar(rows, "0", "01010", Ir64VectorUnaryOp.CMLT0, Sizes.SCALAR_D);
        integerScalar(rows, "0", "01011", Ir64VectorUnaryOp.ABS, Sizes.SCALAR_D);
        integerScalar(rows, "1", "01011", Ir64VectorUnaryOp.NEG, Sizes.SCALAR_D);
        // narrow e alargamento — 0 Q U 01110 size 10000 opcode 10 Rn Rd (size = lado estreito)
        narrow(rows, VECTOR_PREFIX, "0", "10010", Ir64VectorNarrowUnaryOp.XTN, Sizes.BHS);
        narrow(rows, VECTOR_PREFIX, "1", "10010", Ir64VectorNarrowUnaryOp.SQXTUN, Sizes.BHS);
        narrow(rows, VECTOR_PREFIX, "0", "10100", Ir64VectorNarrowUnaryOp.SQXTN, Sizes.BHS);
        narrow(rows, VECTOR_PREFIX, "1", "10100", Ir64VectorNarrowUnaryOp.UQXTN, Sizes.BHS);
        narrow(rows, SCALAR_PREFIX, "1", "10010", Ir64VectorNarrowUnaryOp.SQXTUN, Sizes.SCALAR_BHS);
        narrow(rows, SCALAR_PREFIX, "0", "10100", Ir64VectorNarrowUnaryOp.SQXTN, Sizes.SCALAR_BHS);
        narrow(rows, SCALAR_PREFIX, "1", "10100", Ir64VectorNarrowUnaryOp.UQXTN, Sizes.SCALAR_BHS);
        add(rows, VECTOR_PREFIX, "1", "10011", Sizes.BHS, null, (word, address) ->
                new AdvSimdIntegerOp64.ShiftWidenImmediate(Ir64VectorShiftWidenOp.USHLL, q(word), size(word),
                        Byte.SIZE << size(word), rd(word), rn(word)));
        // FP `_sd` — 0 Q U 01110 a sz 10000 opcode 10 Rn Rd
        fp(rows, "1", "0", "01111", Ir64VectorFpUnaryOp.ABS, false);
        fp(rows, "1", "1", "01111", Ir64VectorFpUnaryOp.NEG, false);
        fp(rows, "1", "0", "01100", Ir64VectorFpUnaryOp.CMGT0, true);
        fp(rows, "1", "1", "01100", Ir64VectorFpUnaryOp.CMGE0, true);
        fp(rows, "1", "0", "01101", Ir64VectorFpUnaryOp.CMEQ0, true);
        fp(rows, "1", "1", "01101", Ir64VectorFpUnaryOp.CMLE0, true);
        fp(rows, "1", "0", "01110", Ir64VectorFpUnaryOp.CMLT0, true);
        fp(rows, "0", "0", "11000", Ir64VectorFpUnaryOp.RINTN, false);
        fp(rows, "1", "0", "11000", Ir64VectorFpUnaryOp.RINTP, false);
        fp(rows, "0", "1", "11000", Ir64VectorFpUnaryOp.RINTA, false);
        fp(rows, "0", "0", "11001", Ir64VectorFpUnaryOp.RINTM, false);
        fp(rows, "1", "0", "11001", Ir64VectorFpUnaryOp.RINTZ, false);
        fp(rows, "0", "1", "11001", Ir64VectorFpUnaryOp.RINTX, false);
        fp(rows, "1", "1", "11001", Ir64VectorFpUnaryOp.RINTI, false);
        fp(rows, "0", "0", "11010", Ir64VectorFpUnaryOp.FCVTNS, true);
        fp(rows, "0", "1", "11010", Ir64VectorFpUnaryOp.FCVTNU, true);
        fp(rows, "1", "0", "11010", Ir64VectorFpUnaryOp.FCVTPS, true);
        fp(rows, "1", "1", "11010", Ir64VectorFpUnaryOp.FCVTPU, true);
        fp(rows, "0", "0", "11011", Ir64VectorFpUnaryOp.FCVTMS, true);
        fp(rows, "0", "1", "11011", Ir64VectorFpUnaryOp.FCVTMU, true);
        fp(rows, "1", "0", "11011", Ir64VectorFpUnaryOp.FCVTZS, true);
        fp(rows, "1", "1", "11011", Ir64VectorFpUnaryOp.FCVTZU, true);
        fp(rows, "0", "0", "11100", Ir64VectorFpUnaryOp.FCVTAS, true);
        fp(rows, "0", "1", "11100", Ir64VectorFpUnaryOp.FCVTAU, true);
        fp(rows, "0", "0", "11101", Ir64VectorFpUnaryOp.SCVTF, true);
        fp(rows, "0", "1", "11101", Ir64VectorFpUnaryOp.UCVTF, true);
        fp(rows, "1", "0", "11101", Ir64VectorFpUnaryOp.RECPE, true);
        fp(rows, "1", "1", "11101", Ir64VectorFpUnaryOp.RSQRTE, true);
        fp(rows, "1", "1", "11111", Ir64VectorFpUnaryOp.SQRT, false);
        // `FEAT_FRINTTS`: sem forma `_h`
        fpVector(rows, "0", "0", "11110", Ir64VectorFpUnaryOp.RINT32Z, Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL);
        fpVector(rows, "0", "1", "11110", Ir64VectorFpUnaryOp.RINT32X, Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL);
        fpVector(rows, "0", "0", "11111", Ir64VectorFpUnaryOp.RINT64Z, Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL);
        fpVector(rows, "0", "1", "11111", Ir64VectorFpUnaryOp.RINT64X, Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL);
        // `FRECPX` escalar — 0 1 0 11110 1 sz 10000 11111 10 Rn Rd (e a forma `_h`)
        rows.add(row("1", "0", SCALAR_PREFIX, "1.", SLOT, FRECPX_OPCODE, null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticUnary(Ir64VectorFpUnaryOp.FRECPX, true, false, SINGLE_ESZ + sz(word),
                        rd(word), rn(word))));
        rows.add(row("1", "0", SCALAR_PREFIX, "11", FP16_SLOT, FRECPX_OPCODE, Aarch64Feature.FP16, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticUnary(Ir64VectorFpUnaryOp.FRECPX, true, false, HALF_ESZ, rd(word),
                        rn(word))));
        // conversões de precisão — 0 Q U 01110 a sz 10000 opcode 10 Rn Rd (`esz` = lado estreito)
        rows.add(row("1", "1", SCALAR_PREFIX, "01", SLOT, FCVTN_OPCODE, null, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticUnary(Ir64VectorFpUnaryOp.FCVTXN, true, false, DOUBLE_ESZ, rd(word),
                        rn(word))));
        precision(rows, "0", "0.", FCVTN_OPCODE, null, Ir64VectorFpConvertPrecisionOp.FCVTN);
        precision(rows, "1", "01", FCVTN_OPCODE, null, Ir64VectorFpConvertPrecisionOp.FCVTXN);
        precision(rows, "0", "0.", FCVTL_OPCODE, null, Ir64VectorFpConvertPrecisionOp.FCVTL);
        precision(rows, "0", "10", FCVTN_OPCODE, Aarch64Feature.BFLOAT16, Ir64VectorFpConvertPrecisionOp.BFCVTN);
        // `FEAT_FP8`: `F1CVTL`/`F2CVTL` (`a=0`) e `BF1CVTL`/`BF2CVTL` (`a=1`); `sz` escolhe o fluxo
        rows.add(row(".", "1", VECTOR_PREFIX, "..", SLOT, FCVTL_OPCODE, Aarch64Feature.FP8, (word, address) ->
                new AdvSimdFpOp64.FpConvertFromFp8(sz(word) != 0, a(word), q(word), rd(word), rn(word))));
        return List.copyOf(rows);
    }

    private static DecodeRow<Ir64Op> row(String q, String u, String prefix, String size, String slot, String opcode,
            Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        return DecodeRow.of("0 " + q + " " + u + " " + prefix + " " + size + " " + slot + " " + opcode
                + " 10 ..... .....", requires, build);
    }

    /// Uma linha por par `Q size` de `sizes`, no slot do inteiro.
    private static void add(List<DecodeRow<Ir64Op>> rows, String prefix, String u, String opcode, Sizes sizes,
            Aarch64Feature requires, DecodeRow.WordDecoder<Ir64Op> build) {
        for (String qSize : sizes.qSize) {
            rows.add(row(qSize.substring(0, 1), u, prefix, qSize.substring(2), SLOT, opcode, requires, build));
        }
    }

    private static void integer(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorUnaryOp op,
            Sizes sizes) {
        add(rows, VECTOR_PREFIX, u, opcode, sizes, null, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticUnary(op, false, q(word), size(word), rd(word), rn(word)));
    }

    private static void integerScalar(List<DecodeRow<Ir64Op>> rows, String u, String opcode, Ir64VectorUnaryOp op,
            Sizes sizes) {
        add(rows, SCALAR_PREFIX, u, opcode, sizes, null, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticUnary(op, true, false, size(word), rd(word), rn(word)));
    }

    /// `CNT`/`NOT`/`RBIT`: `size` é o seletor da operação (uma linha, `Q` livre).
    private static void byteOnly(List<DecodeRow<Ir64Op>> rows, String u, String size, Ir64VectorUnaryOp op) {
        rows.add(row(".", u, VECTOR_PREFIX, size, SLOT, BYTE_ONLY_OPCODE, null, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticUnary(op, false, q(word), BYTE_ESZ, rd(word), rn(word))));
    }

    private static void narrow(List<DecodeRow<Ir64Op>> rows, String prefix, String u, String opcode,
            Ir64VectorNarrowUnaryOp op, Sizes sizes) {
        boolean scalar = SCALAR_PREFIX.equals(prefix);
        add(rows, prefix, u, opcode, sizes, null, (word, address) ->
                new AdvSimdIntegerOp64.ArithmeticNarrowUnary(op, scalar, !scalar && q(word), size(word), rd(word),
                        rn(word)));
    }

    /// FP `_sd` vetorial (`.2s`/`.4s`/`.2d`), a forma escalar `s`/`d` se `hasScalar`, e as mesmas em FP16.
    private static void fp(List<DecodeRow<Ir64Op>> rows, String a, String u, String opcode, Ir64VectorFpUnaryOp op,
            boolean hasScalar) {
        fpVector(rows, a, u, opcode, op, null);
        rows.add(row(".", u, VECTOR_PREFIX, a + "1", FP16_SLOT, opcode, Aarch64Feature.FP16, (word, address) ->
                new AdvSimdFpOp64.FpArithmeticUnary(op, false, q(word), HALF_ESZ, rd(word), rn(word))));
        if (hasScalar) {
            rows.add(row("1", u, SCALAR_PREFIX, a + ".", SLOT, opcode, null, (word, address) ->
                    new AdvSimdFpOp64.FpArithmeticUnary(op, true, false, SINGLE_ESZ + sz(word), rd(word), rn(word))));
            rows.add(row("1", u, SCALAR_PREFIX, a + "1", FP16_SLOT, opcode, Aarch64Feature.FP16, (word, address) ->
                    new AdvSimdFpOp64.FpArithmeticUnary(op, true, false, HALF_ESZ, rd(word), rn(word))));
        }
    }

    /// FP `_sd` vetorial: `sz=0` em qualquer `Q`, `sz=1` só com `Q=1` (sem `.1d`).
    private static void fpVector(List<DecodeRow<Ir64Op>> rows, String a, String u, String opcode,
            Ir64VectorFpUnaryOp op, Aarch64Feature requires) {
        DecodeRow.WordDecoder<Ir64Op> build = (word, address) ->
                new AdvSimdFpOp64.FpArithmeticUnary(op, false, q(word), SINGLE_ESZ + sz(word), rd(word), rn(word));
        rows.add(row(".", u, VECTOR_PREFIX, a + "0", SLOT, opcode, requires, build));
        rows.add(row("1", u, VECTOR_PREFIX, a + "1", SLOT, opcode, requires, build));
    }

    /// Conversão de precisão vetorial: o record leva o lado estreito, `HALF_ESZ + sz` (`BFCVTN` tem `sz=0`).
    private static void precision(List<DecodeRow<Ir64Op>> rows, String u, String size, String opcode,
            Aarch64Feature requires, Ir64VectorFpConvertPrecisionOp op) {
        rows.add(row(".", u, VECTOR_PREFIX, size, SLOT, opcode, requires, (word, address) ->
                new AdvSimdFpOp64.FpConvertPrecision(op, q(word), HALF_ESZ + sz(word), rd(word), rn(word))));
    }

    private static boolean q(int word) {
        return ((word >>> Q_SHIFT) & 1) != 0;
    }

    private static boolean a(int word) {
        return ((word >>> A_SHIFT) & 1) != 0;
    }

    private static int size(int word) {
        return (word >>> SIZE_SHIFT) & SIZE_MASK;
    }

    private static int sz(int word) {
        return (word >>> SIZE_SHIFT) & 1;
    }

    private static int rn(int word) {
        return (word >>> RN_SHIFT) & REGISTER_MASK;
    }

    private static int rd(int word) {
        return word & REGISTER_MASK;
    }
}
