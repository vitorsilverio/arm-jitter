package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64Conversion;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64HalfPrecisionConversion;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64Operation;
import dev.vitorsilverio.armjitter.ir64.FpOp64.Fp64RoundingDirection;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static dev.vitorsilverio.armjitter.decoder64.LoadStoreRowsTest.NO_SME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.14: o FP escalar como tabela ({@link ScalarFpRows}) — invariantes das linhas, roteamento pelo
/// {@link Aarch64Decoder}, campos que a cascata calculava e features como coluna. Comportamento idêntico ao da
/// cascata (oráculo `e15.14-scripts/FpScalarOracle.java` sem diff).
class ScalarFpRowsTest {
    private static final long ADDRESS = 0x1000L;
    private static final int ROW_COUNT = 58;
    private static final int BASE_ROW_COUNT = 46;
    private static final long MINUS_TWO_DOUBLE_BITS = Double.doubleToRawLongBits(-2.0);
    private static final long HALF_FLOAT_BITS = Float.floatToRawIntBits(0.5f);

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        return decoder.decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, ScalarFpRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(ScalarFpRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(ScalarFpRows.ROWS, NO_SME::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1514L);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        SplittableRandom random = new SplittableRandom(0xE1514L);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : ScalarFpRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    @Test
    void baseArchitectureKeepsOnlyTheRowsWithoutFeature() {
        assertEquals(BASE_ROW_COUNT, DecodeTable.forFeatures(ScalarFpRows.ROWS,
                Aarch64Architecture.ARMV8_0_A::has).rows().size());
    }

    /// Os campos que a cascata calculava com `switch`/`if`, conferidos contra o `objdump` 2.46 do devkitA64.
    @Test
    void fieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        // fnmadd d0, d1, d2, d3 · fnmsub s0, s1, s2, s3 · fmsub d0, d1, d2, d3 — neg_n = o1 XOR o0
        assertEquals(new FpOp64.MultiplyAdd(true, true, true, 0, 1, 2, 3), decodeWord(decoder, 0x1f620c20));
        assertEquals(new FpOp64.MultiplyAdd(false, true, false, 0, 1, 2, 3), decodeWord(decoder, 0x1f228c20));
        assertEquals(new FpOp64.MultiplyAdd(true, false, true, 0, 1, 2, 3), decodeWord(decoder, 0x1f428c20));
        // scvtf d0, w1, #3 · fcvtzu x0, s1, #60
        assertEquals(new FpOp64.IntegerConvert(true, true, Fp64RoundingDirection.TOWARD_ZERO, true, false, 3, 0, 1),
                decodeWord(decoder, 0x1e42f420));
        assertEquals(new FpOp64.IntegerConvert(false, false, Fp64RoundingDirection.TOWARD_ZERO, false, true, 60, 1, 0),
                decodeWord(decoder, 0x9e191020));
        // ucvtf s0, x1 · fcvtms w0, d1 · fcvtau x0, s1
        assertEquals(new FpOp64.IntegerConvert(true, false, Fp64RoundingDirection.NEAREST_TIES_EVEN, false, true, 0, 0,
                1), decodeWord(decoder, 0x9e230020));
        assertEquals(new FpOp64.IntegerConvert(false, true, Fp64RoundingDirection.TOWARD_NEGATIVE_INFINITY, true, false,
                0, 1, 0), decodeWord(decoder, 0x1e700020));
        assertEquals(new FpOp64.IntegerConvert(false, false, Fp64RoundingDirection.NEAREST_TIES_AWAY, false, true, 0, 1,
                0), decodeWord(decoder, 0x9e250020));
        // fmov x0, v1.d[1] · fmov v0.d[1], x1 · fmov h0, x1 · fmov w0, h1 · fmov s0, w1 · fmov x0, d1
        assertEquals(new FpOp64.HighHalfMove(false, 1, 0), decodeWord(decoder, 0x9eae0020));
        assertEquals(new FpOp64.HighHalfMove(true, 0, 1), decodeWord(decoder, 0x9eaf0020));
        assertEquals(new FpOp64.HalfPrecisionGeneralRegisterMove(true, 0, 1), decodeWord(decoder, 0x9ee70020));
        assertEquals(new FpOp64.HalfPrecisionGeneralRegisterMove(false, 1, 0), decodeWord(decoder, 0x1ee60020));
        assertEquals(new FpOp64.GeneralRegisterMove(true, false, 0, 1), decodeWord(decoder, 0x1e270020));
        assertEquals(new FpOp64.GeneralRegisterMove(false, true, 1, 0), decodeWord(decoder, 0x9e660020));
        // fmov d0, #-2.0 · fmov s0, #0.5
        assertEquals(new FpOp64.MoveImmediate(true, 0, MINUS_TWO_DOUBLE_BITS), decodeWord(decoder, 0x1e701000));
        assertEquals(new FpOp64.MoveImmediate(false, 0, HALF_FLOAT_BITS), decodeWord(decoder, 0x1e2c1000));
        // fcmpe s1, #0.0 · fcmp d1, d2
        assertEquals(new FpOp64.Compare(false, true, true, 1, 0), decodeWord(decoder, 0x1e202038));
        assertEquals(new FpOp64.Compare(true, false, false, 1, 2), decodeWord(decoder, 0x1e622020));
        // fcvt h0, d1 · fcvt s0, d1 · fcvt d0, h1 · fcvt d0, s1
        assertEquals(new FpOp64.ConvertHalfPrecision(Fp64HalfPrecisionConversion.DOUBLE_TO_HALF, 0, 1),
                decodeWord(decoder, 0x1e63c020));
        assertEquals(new FpOp64.Convert(Fp64Conversion.F64_TO_F32, 0, 1), decodeWord(decoder, 0x1e624020));
        assertEquals(new FpOp64.ConvertHalfPrecision(Fp64HalfPrecisionConversion.HALF_TO_DOUBLE, 0, 1),
                decodeWord(decoder, 0x1ee2c020));
        assertEquals(new FpOp64.Convert(Fp64Conversion.F32_TO_F64, 0, 1), decodeWord(decoder, 0x1e22c020));
        // frint64x d0, d1 · frint32z s0, s1 · frintx s0, s1 · frinta d0, d1
        assertEquals(new FpOp64.RoundRangeLimited(Fp64RoundingDirection.NEAREST_TIES_EVEN, true, true, 0, 1),
                decodeWord(decoder, 0x1e69c020));
        assertEquals(new FpOp64.RoundRangeLimited(Fp64RoundingDirection.TOWARD_ZERO, false, false, 0, 1),
                decodeWord(decoder, 0x1e284020));
        assertEquals(new FpOp64.Round(Fp64RoundingDirection.NEAREST_TIES_EVEN, false, 0, 1),
                decodeWord(decoder, 0x1e274020));
        assertEquals(new FpOp64.Round(Fp64RoundingDirection.NEAREST_TIES_AWAY, true, 0, 1),
                decodeWord(decoder, 0x1e664020));
        // fnmul s0, s1, s2 · fminnm d0, d1, d2 · fsqrt d0, d1 · fneg s0, s1
        assertEquals(new FpOp64.Alu(Fp64Operation.NMUL, false, 0, 1, 2), decodeWord(decoder, 0x1e228820));
        assertEquals(new FpOp64.Alu(Fp64Operation.MINNM, true, 0, 1, 2), decodeWord(decoder, 0x1e627820));
        assertEquals(new FpOp64.Alu(Fp64Operation.SQRT, true, 0, 0, 1), decodeWord(decoder, 0x1e61c020));
        assertEquals(new FpOp64.Alu(Fp64Operation.NEG, false, 0, 0, 1), decodeWord(decoder, 0x1e214020));
        // fcsel d0, d1, d2, gt · fccmpe s1, s2, #5, ne
        assertEquals(new FpOp64.ConditionalSelect(true, 0, 1, 2, Ir64Condition.GT), decodeWord(decoder, 0x1e62cc20));
        assertEquals(new FpOp64.ConditionalCompare(false, true, 1, 2, Ir64Condition.NE, 5),
                decodeWord(decoder, 0x1e221435));
    }

    @ParameterizedTest(name = "{1} só com a feature")
    @CsvSource(delimiter = '|', textBlock = """
            1e7e0020 | fjcvtzs w0, d1
            1e634020 | bfcvt h0, s1
            1e23c020 | fcvt h0, s1
            1e63c020 | fcvt h0, d1
            1ee24020 | fcvt s0, h1
            1ee2c020 | fcvt d0, h1
            1e284020 | frint32z s0, s1
            1e28c020 | frint32x s0, s1
            1e294020 | frint64z s0, s1
            1e69c020 | frint64x d0, d1
            9ee60020 | fmov x0, h1
            1ee70020 | fmov h0, w1
            """)
    void featureRowsAreAbsentFromTheBaseArchitecture(String word, String objdump) {
        int value = Integer.parseUnsignedInt(word, 16);
        assertThrows(UnsupportedOperationException.class,
                () -> decodeWord(new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A), value), objdump);
        assertDoesNotThrow(() -> decodeWord(new Aarch64Decoder(NO_SME), value), objdump);
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata (o oráculo da E15.14 não tem diff).
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            1ea22820 | 1e222820 | fadd s0, s1, s2 — type=10
            9e222820 | 1e222820 | fadd s0, s1, s2 — M=1
            9f420c20 | 1f420c20 | fmadd d0, d1, d2, d3 — M=1
            9e260020 | 9e660020 | fmov x0, d1 — X↔S
            1eae0020 | 9eae0020 | fmov x0, v1.d[1] — sf=0
            9e7e0020 | 1e7e0020 | fjcvtzs w0, d1 — sf=1
            1e224020 | 1e624020 | fcvt s0, d1 — fonte = destino
            1e62c020 | 1e22c020 | fcvt d0, s1 — fonte = destino
            1e234020 | 1e634020 | bfcvt h0, s1 — type=00
            1e427420 | 1e42f420 | scvtf d0, w1, #3 — 32 bits com bit15=0
            1e229820 | 1e228820 | fnmul s0, s1, s2 — opcode 1001
            1e622021 | 1e622020 | fcmp d1, d2 — bits[2:0]≠000
            1e701020 | 1e701000 | fmov d0, #-2.0 — bits[9:5]≠00000
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(NO_SME);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }
}
