package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticUnary;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpConvertFromFp8;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpConvertPrecision;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticNarrowUnary;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticUnary;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ShiftWidenImmediate;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpConvertPrecisionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowUnaryOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorShiftWidenOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorUnaryOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15e: o "two-register misc" como tabela ({@link AdvSimdTwoRegisterMiscRows}). Comportamento idêntico ao da
/// cascata (oráculo `e15.15d-scripts/AdvSimdBit10ZeroOracle.java`, diff vazio). A sobreposição com o resto da
/// `advSimdTable` é testada em {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdTwoRegisterMiscRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 54 inteiras vetoriais, 11 escalares, 14 narrow, 2 `SHLL`, 125 FP (`_sd` e `_h`), 8 `FRINT32*`/`FRINT64*`, 2
    /// `FRECPX`, 6 conversões de precisão.
    private static final int ROW_COUNT = 222;
    /// Sem as 49 formas FP16, as 8 de `FEAT_FRINTTS`, `BFCVTN` e os `*CVTL` de FP8.
    private static final int BASE_ROW_COUNT = 163;
    /// Todas as features do espaço, sem SME (que embrulharia as ops em `StreamingRestricted`).
    private static final Aarch64Architecture FEATURES = Aarch64Architecture.of("e15.15e", Aarch64Feature.FP16,
            Aarch64Feature.BFLOAT16, Aarch64Feature.FP8, Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL);

    private static Ir64Op decodeWord(Aarch64Architecture architecture, String word) {
        return new Aarch64Decoder(architecture).decode(Integer.parseUnsignedInt(word, 16), ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdTwoRegisterMiscRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdTwoRegisterMiscRows.ROWS);
    }

    @Test
    void featureRowsAreFilteredByThePreset() {
        assertEquals(BASE_ROW_COUNT, DecodeTable.forFeatures(AdvSimdTwoRegisterMiscRows.ROWS,
                Aarch64Architecture.ARMV8_0_A::has).rows().size());
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(AdvSimdTwoRegisterMiscRows.ROWS, FEATURES::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515EL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(FEATURES);
        SplittableRandom random = new SplittableRandom(0xE1515EL);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdTwoRegisterMiscRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decoder.decode(word, ADDRESS), Integer.toHexString(word));
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`); `Rd=0`, `Rn=1`.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            4e200820 | rev64 v0.16b, v1.16b       | REV64   | false | true  | 0
            2e600820 | rev32 v0.4h, v1.4h         | REV32   | false | false | 1
            0e201820 | rev16 v0.8b, v1.8b         | REV16   | false | false | 0
            4e602820 | saddlp v0.4s, v1.8h        | SADDLP  | false | true  | 1
            2e202820 | uaddlp v0.4h, v1.8b        | UADDLP  | false | false | 0
            2ea06820 | uadalp v0.1d, v1.2s        | UADALP  | false | false | 2
            4e206820 | sadalp v0.8h, v1.16b       | SADALP  | false | true  | 0
            4ee03820 | suqadd v0.2d, v1.2d        | SUQADD  | false | true  | 3
            2e203820 | usqadd v0.8b, v1.8b        | USQADD  | false | false | 0
            4ea04820 | cls v0.4s, v1.4s           | CLS     | false | true  | 2
            6e204820 | clz v0.16b, v1.16b         | CLZ     | false | true  | 0
            4e607820 | sqabs v0.8h, v1.8h         | SQABS   | false | true  | 1
            2ea07820 | sqneg v0.2s, v1.2s         | SQNEG   | false | false | 2
            4ee08820 | cmgt v0.2d, v1.2d, #0      | CMGT0   | false | true  | 3
            2e608820 | cmge v0.4h, v1.4h, #0      | CMGE0   | false | false | 1
            4e209820 | cmeq v0.16b, v1.16b, #0    | CMEQ0   | false | true  | 0
            6ea09820 | cmle v0.4s, v1.4s, #0      | CMLE0   | false | true  | 2
            0e20a820 | cmlt v0.8b, v1.8b, #0      | CMLT0   | false | false | 0
            4ee0b820 | abs v0.2d, v1.2d           | ABS     | false | true  | 3
            2e60b820 | neg v0.4h, v1.4h           | NEG     | false | false | 1
            0ea1c820 | urecpe v0.2s, v1.2s        | URECPE  | false | false | 2
            6ea1c820 | ursqrte v0.4s, v1.4s       | URSQRTE | false | true  | 2
            4e205820 | cnt v0.16b, v1.16b         | CNT     | false | true  | 0
            2e205820 | not v0.8b, v1.8b           | NOT     | false | false | 0
            6e605820 | rbit v0.16b, v1.16b        | RBIT    | false | true  | 0
            5e203820 | suqadd b0, b1              | SUQADD  | true  | false | 0
            7e603820 | usqadd h0, h1              | USQADD  | true  | false | 1
            5ea07820 | sqabs s0, s1               | SQABS   | true  | false | 2
            7ee07820 | sqneg d0, d1               | SQNEG   | true  | false | 3
            5ee08820 | cmgt d0, d1, #0            | CMGT0   | true  | false | 3
            7ee08820 | cmge d0, d1, #0            | CMGE0   | true  | false | 3
            5ee09820 | cmeq d0, d1, #0            | CMEQ0   | true  | false | 3
            7ee09820 | cmle d0, d1, #0            | CMLE0   | true  | false | 3
            5ee0a820 | cmlt d0, d1, #0            | CMLT0   | true  | false | 3
            5ee0b820 | abs d0, d1                 | ABS     | true  | false | 3
            7ee0b820 | neg d0, d1                 | NEG     | true  | false | 3
            """)
    void integerFieldsFollowTheEncoding(String word, String objdump, Ir64VectorUnaryOp op, boolean scalar, boolean q,
            int esz) {
        assertEquals(new ArithmeticUnary(op, scalar, q, esz, 0, 1), decodeWord(Aarch64Architecture.ARMV8_0_A, word),
                objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e212820 | xtn v0.8b, v1.8h           | XTN    | false | false | 0
            4e612820 | xtn2 v0.8h, v1.4s          | XTN    | false | true  | 1
            2ea12820 | sqxtun v0.2s, v1.2d        | SQXTUN | false | false | 2
            4e214820 | sqxtn2 v0.16b, v1.8h       | SQXTN  | false | true  | 0
            2e614820 | uqxtn v0.4h, v1.4s         | UQXTN  | false | false | 1
            7e212820 | sqxtun b0, h1              | SQXTUN | true  | false | 0
            5e614820 | sqxtn h0, s1               | SQXTN  | true  | false | 1
            7ea14820 | uqxtn s0, d1               | UQXTN  | true  | false | 2
            """)
    void narrowFieldsFollowTheEncoding(String word, String objdump, Ir64VectorNarrowUnaryOp op, boolean scalar,
            boolean q, int esz) {
        assertEquals(new ArithmeticNarrowUnary(op, scalar, q, esz, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, word), objdump);
    }

    @Test
    void shiftLeftLongShiftsByTheElementWidth() {
        // shll v0.8h, v1.8b, #8 · shll2 v0.2d, v1.4s, #32
        assertEquals(new ShiftWidenImmediate(Ir64VectorShiftWidenOp.USHLL, false, 0, 8, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, "2e213820"));
        assertEquals(new ShiftWidenImmediate(Ir64VectorShiftWidenOp.USHLL, true, 2, 32, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, "6ea13820"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            4ee0f820 | fabs v0.2d, v1.2d          | ABS     | false | true  | 3
            6ea0f820 | fneg v0.4s, v1.4s          | NEG     | false | true  | 2
            0ea0c820 | fcmgt v0.2s, v1.2s, #0.0   | CMGT0   | false | false | 2
            7ea0c820 | fcmge s0, s1, #0.0         | CMGE0   | true  | false | 2
            5ee0d820 | fcmeq d0, d1, #0.0         | CMEQ0   | true  | false | 3
            6ea0d820 | fcmle v0.4s, v1.4s, #0.0   | CMLE0   | false | true  | 2
            5ee0e820 | fcmlt d0, d1, #0.0         | CMLT0   | true  | false | 3
            4e618820 | frintn v0.2d, v1.2d        | RINTN   | false | true  | 3
            4ea18820 | frintp v0.4s, v1.4s        | RINTP   | false | true  | 2
            2e218820 | frinta v0.2s, v1.2s        | RINTA   | false | false | 2
            4e619820 | frintm v0.2d, v1.2d        | RINTM   | false | true  | 3
            4ea19820 | frintz v0.4s, v1.4s        | RINTZ   | false | true  | 2
            2e219820 | frintx v0.2s, v1.2s        | RINTX   | false | false | 2
            6ee19820 | frinti v0.2d, v1.2d        | RINTI   | false | true  | 3
            5e21a820 | fcvtns s0, s1              | FCVTNS  | true  | false | 2
            6e61a820 | fcvtnu v0.2d, v1.2d        | FCVTNU  | false | true  | 3
            0ea1a820 | fcvtps v0.2s, v1.2s        | FCVTPS  | false | false | 2
            7ee1a820 | fcvtpu d0, d1              | FCVTPU  | true  | false | 3
            4e21b820 | fcvtms v0.4s, v1.4s        | FCVTMS  | false | true  | 2
            7e21b820 | fcvtmu s0, s1              | FCVTMU  | true  | false | 2
            4ea1b820 | fcvtzs v0.4s, v1.4s        | FCVTZS  | false | true  | 2
            7ee1b820 | fcvtzu d0, d1              | FCVTZU  | true  | false | 3
            4e61c820 | fcvtas v0.2d, v1.2d        | FCVTAS  | false | true  | 3
            7e21c820 | fcvtau s0, s1              | FCVTAU  | true  | false | 2
            4e61d820 | scvtf v0.2d, v1.2d         | SCVTF   | false | true  | 3
            7e21d820 | ucvtf s0, s1               | UCVTF   | true  | false | 2
            5ee1d820 | frecpe d0, d1              | RECPE   | true  | false | 3
            6ea1d820 | frsqrte v0.4s, v1.4s       | RSQRTE  | false | true  | 2
            6ee1f820 | fsqrt v0.2d, v1.2d         | SQRT    | false | true  | 3
            4e21e820 | frint32z v0.4s, v1.4s      | RINT32Z | false | true  | 2
            6e61e820 | frint32x v0.2d, v1.2d      | RINT32X | false | true  | 3
            0e21f820 | frint64z v0.2s, v1.2s      | RINT64Z | false | false | 2
            6e21f820 | frint64x v0.4s, v1.4s      | RINT64X | false | true  | 2
            5ea1f820 | frecpx s0, s1              | FRECPX  | true  | false | 2
            5ee1f820 | frecpx d0, d1              | FRECPX  | true  | false | 3
            5ef9f820 | frecpx h0, h1              | FRECPX  | true  | false | 1
            4ef8f820 | fabs v0.8h, v1.8h          | ABS     | false | true  | 1
            5ef8c820 | fcmgt h0, h1, #0.0         | CMGT0   | true  | false | 1
            0e798820 | frintn v0.4h, v1.4h        | RINTN   | false | false | 1
            5ef9b820 | fcvtzs h0, h1              | FCVTZS  | true  | false | 1
            4e79d820 | scvtf v0.8h, v1.8h         | SCVTF   | false | true  | 1
            2ef9f820 | fsqrt v0.4h, v1.4h         | SQRT    | false | false | 1
            7ef9d820 | frsqrte h0, h1             | RSQRTE  | true  | false | 1
            7e616820 | fcvtxn s0, d1              | FCVTXN  | true  | false | 3
            """)
    void fpFieldsFollowTheEncoding(String word, String objdump, Ir64VectorFpUnaryOp op, boolean scalar, boolean q,
            int esz) {
        assertEquals(new FpArithmeticUnary(op, scalar, q, esz, 0, 1), decodeWord(FEATURES, word), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e216820 | fcvtn v0.4h, v1.4s         | FCVTN  | false | 1
            4e616820 | fcvtn2 v0.4s, v1.2d        | FCVTN  | true  | 2
            2e616820 | fcvtxn v0.2s, v1.2d        | FCVTXN | false | 2
            0e217820 | fcvtl v0.4s, v1.4h         | FCVTL  | false | 1
            4e617820 | fcvtl2 v0.2d, v1.4s        | FCVTL  | true  | 2
            0ea16820 | bfcvtn v0.4h, v1.4s        | BFCVTN | false | 1
            4ea16820 | bfcvtn2 v0.8h, v1.4s       | BFCVTN | true  | 1
            """)
    void precisionFieldsFollowTheEncoding(String word, String objdump, Ir64VectorFpConvertPrecisionOp op, boolean q,
            int esz) {
        assertEquals(new FpConvertPrecision(op, q, esz, 0, 1), decodeWord(FEATURES, word), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            2e217820 | f1cvtl v0.8h, v1.8b        | false | false | false
            6e617820 | f2cvtl2 v0.8h, v1.16b      | true  | false | true
            6ea17820 | bf1cvtl2 v0.8h, v1.16b     | false | true  | true
            2ee17820 | bf2cvtl v0.8h, v1.8b       | true  | true  | false
            """)
    void fp8FieldsFollowTheEncoding(String word, String objdump, boolean secondStream, boolean bfloat16Destination,
            boolean q) {
        assertEquals(new FpConvertFromFp8(secondStream, bfloat16Destination, q, 0, 1), decodeWord(FEATURES, word),
                objdump);
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata. Preset com todas as features do espaço.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            4ee00820 | 4ea00820 | rev64 v0.4s, v1.4s — REV64 sem elemento de 64 bits
            2ea00820 | 2e600820 | rev32 v0.4h, v1.4h — REV32 até H
            0e601820 | 0e201820 | rev16 v0.8b, v1.8b — REV16 só B
            4ee02820 | 4ea02820 | saddlp v0.2d, v1.4s — sem elemento de 64 bits
            0ee0b820 | 4ee0b820 | abs v0.2d, v1.2d — sem .1d
            0e605820 | 0e205820 | cnt v0.8b, v1.8b — CNT com size=01
            5ea04820 | 5ea07820 | sqabs s0, s1 — CLS sem forma escalar
            5ea0b820 | 5ee0b820 | abs d0, d1 — ABS escalar só D
            5e212820 | 7e212820 | sqxtun b0, h1 — XTN sem forma escalar
            4ee14820 | 4ea14820 | sqxtn2 v0.4s, v1.2d — narrow sem lado estreito D
            0e213820 | 2e213820 | shll v0.8h, v1.8b, #8 — SHLL com U=0
            0ee1c820 | 0ea1c820 | urecpe v0.2s, v1.2s — URECPE só S
            0ee0f820 | 4ee0f820 | fabs v0.2d, v1.2d — sem .1d
            5ee0f820 | 5ee0e820 | fcmlt d0, d1, #0.0 — FABS sem forma escalar
            7ee1f820 | 6ee1f820 | fsqrt v0.2d, v1.2d — FSQRT sem forma escalar
            2e216820 | 2e616820 | fcvtxn v0.2s, v1.2d — FCVTXN só de D
            7e216820 | 7e616820 | fcvtxn s0, d1 — FCVTXN escalar com size=01
            4e79e820 | 4e21e820 | frint32z v0.4s, v1.4s — FRINT32Z sem forma _h
            4eb8f820 | 4ef8f820 | fabs v0.8h, v1.8h — FP16 com bit22=0
            4ea1f820 | 5ea1f820 | frecpx s0, s1 — FRECPX só escalar
            0ee16820 | 0ea16820 | bfcvtn v0.4h, v1.4s — BFCVTN com sz=1
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FEATURES, rejected), objdump);
        assertDoesNotThrow(() -> decodeWord(FEATURES, neighbour), objdump);
    }

    /// FP16, `FEAT_FRINTTS`, `FEAT_BF16` e `FEAT_FP8`: recusadas sem a feature.
    @ParameterizedTest(name = "{0} precisa de feature")
    @CsvSource(delimiter = '|', textBlock = """
            4ef8f820 | fabs v0.8h, v1.8h
            5ef9f820 | frecpx h0, h1
            4e21e820 | frint32z v0.4s, v1.4s
            0ea16820 | bfcvtn v0.4h, v1.4s
            2e217820 | f1cvtl v0.8h, v1.8b
            """)
    void featureFormsNeedTheirFeature(String word, String objdump) {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(Aarch64Architecture.ARMV8_0_A, word),
                objdump);
        assertDoesNotThrow(() -> decodeWord(FEATURES, word), objdump);
    }
}
