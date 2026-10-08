package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.Fp8DotProductByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.Fp8FusedMultiplyAddLongByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticThreeSameByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpDotProductBFloat16ByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpMultiplyAddLongBFloat16ByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpMultiplyAddLongByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticThreeSameByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticWideningByElement;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.IntegerDotProductByElement;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// E15.15g: o "vector/scalar × indexed element" como tabela ({@link AdvSimdIndexedElementRows}). Comportamento
/// idêntico ao da cascata (oráculo `e15.15g-scripts/AdvSimdIndexedElementOracle.java`), menos os campos de
/// `BFDOT_vi`, `USDOT_vi`/`SUDOT_vi` e `FCMLA_vi` `.h`, corrigidos (o `M` é o bit alto de `Rm`). A sobreposição com o
/// resto da `advSimdTable` é testada em {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdIndexedElementRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 40 FP, 44 inteiras não alargantes, 48 alargantes, 5 `FCMLA`, 10 produtos escalares, 10 `FMLAL`/`BFMLAL`, 8
    /// FP8 — cada `L` livre em duas linhas.
    private static final int ROW_COUNT = 165;
    /// Sem features: saem 16 linhas FP16, 16 RDM e as 45 das extensões.
    private static final int BASE_ROW_COUNT = 100;
    /// Todas as features do espaço, sem SME (que embrulharia as ops em `StreamingRestricted`).
    private static final Aarch64Architecture FEATURES = Aarch64Architecture.of("e15.15g", Aarch64Feature.FP16,
            Aarch64Feature.RDM, Aarch64Feature.COMPLEX_NUMBER_ARITHMETIC, Aarch64Feature.BFLOAT16,
            Aarch64Feature.INT8_MATRIX_MULTIPLY, Aarch64Feature.DOT_PRODUCT, Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG,
            Aarch64Feature.FP8_FUSED_MULTIPLY_ADD, Aarch64Feature.FP8_DOT_PRODUCT_2WAY,
            Aarch64Feature.FP8_DOT_PRODUCT_4WAY);
    /// `bit31` e o prefixo `bits[28:24]` de toda classe "Data Processing — Scalar FP and Advanced SIMD".
    private static final int CLASS_PREFIX_MASK = 0x9F00_0000;
    private static final int BIT31 = 1 << 31;
    private static final int BIT30 = 1 << 30;
    /// `bit28`: prefixo escalar (`1111x`).
    private static final int SCALAR_PREFIX_BIT = 1 << 28;
    private static final int CRYPTO_PREFIX_MASK = 0xFF00_0000;
    private static final int CRYPTO_PREFIX = 0xCE00_0000;

    private static Ir64Op decodeWord(Aarch64Architecture architecture, String word) {
        return new Aarch64Decoder(architecture).decode(Integer.parseUnsignedInt(word, 16), ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdIndexedElementRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdIndexedElementRows.ROWS);
    }

    @Test
    void featureRowsAreFilteredByThePreset() {
        assertEquals(BASE_ROW_COUNT, DecodeTable.forFeatures(AdvSimdIndexedElementRows.ROWS,
                Aarch64Architecture.ARMV8_0_A::has).rows().size());
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(AdvSimdIndexedElementRows.ROWS, FEATURES::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515AL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(FEATURES);
        SplittableRandom random = new SplittableRandom(0xE1515AL);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdIndexedElementRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decoder.decode(word, ADDRESS), Integer.toHexString(word));
        }
    }

    /// Sem cascata na frente, a `advSimdTable` é a única guarda da classe: toda linha fixa `bit31` e o prefixo, e as
    /// escalares (`1111x`) fixam `bit30=1` — o `bit30=0` do prefixo escalar é FP escalar ou não alocado. `bit31=1`
    /// só na criptografia `11001110`.
    @Test
    void advSimdTableRowsFixTheClassPrefix() {
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdPermuteCopyRowsTest.advSimdTableRows()) {
            String where = Integer.toHexString(row.mask()) + "/" + Integer.toHexString(row.value());
            assertEquals(CLASS_PREFIX_MASK, row.mask() & CLASS_PREFIX_MASK, where);
            if ((row.value() & BIT31) != 0) {
                assertEquals(CRYPTO_PREFIX_MASK, row.mask() & CRYPTO_PREFIX_MASK, where);
                assertEquals(CRYPTO_PREFIX, row.value() & CRYPTO_PREFIX_MASK, where);
            } else if ((row.value() & SCALAR_PREFIX_BIT) != 0) {
                assertTrue((row.mask() & row.value() & BIT30) != 0, where);
            }
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`); `Rd=0`, `Rn=1`.
    static Stream<Arguments> decodedForms() {
        return Stream.of(
                Arguments.of("4f3f9820", "fmul v0.8h, v1.8h, v15.h[7]", new FpArithmeticThreeSameByElement(
                        Ir64VectorFpThreeSameOp.MUL, false, true, 1, 0, 1, 15, 7)),
                Arguments.of("7f329020", "fmulx h0, h1, v2.h[3]", new FpArithmeticThreeSameByElement(
                        Ir64VectorFpThreeSameOp.MULX, true, false, 1, 0, 1, 2, 3)),
                Arguments.of("4fbf1820", "fmla v0.4s, v1.4s, v31.s[3]", new FpArithmeticThreeSameByElement(
                        Ir64VectorFpThreeSameOp.MLA, false, true, 2, 0, 1, 31, 3)),
                Arguments.of("5fd15820", "fmls d0, d1, v17.d[1]", new FpArithmeticThreeSameByElement(
                        Ir64VectorFpThreeSameOp.MLS, true, false, 3, 0, 1, 17, 1)),
                Arguments.of("0f5f8820", "mul v0.4h, v1.4h, v15.h[5]", new ArithmeticThreeSameByElement(
                        Ir64VectorThreeSameOp.MUL, false, false, 1, 0, 1, 15, 5)),
                Arguments.of("7f94d820", "sqrdmlah s0, s1, v20.s[2]", new ArithmeticThreeSameByElement(
                        Ir64VectorThreeSameOp.SQRDMLAH, true, false, 2, 0, 1, 20, 2)),
                Arguments.of("6f636820", "umlsl2 v0.4s, v1.8h, v3.h[6]", new ArithmeticWideningByElement(
                        Ir64VectorWideningOp.UMLSL, false, true, 1, 0, 1, 3, 6)),
                Arguments.of("5fbfb020", "sqdmull d0, s1, v31.s[1]", new ArithmeticWideningByElement(
                        Ir64VectorWideningOp.SQDMULL, true, false, 2, 0, 1, 31, 1)),
                Arguments.of("5f7f3820", "sqdmlal s0, h1, v15.h[7]", new ArithmeticWideningByElement(
                        Ir64VectorWideningOp.SQDMLAL, true, false, 1, 0, 1, 15, 7)),
                // corrigidos na E15.15g: `Rm` de 5 bits (e, no BFDOT, índice H:L)
                Arguments.of("2f703020", "fcmla v0.4h, v1.4h, v16.h[1], #90",
                        new FpComplexMultiplyAccumulateByElement(false, 1, 90, 0, 1, 16, 1)),
                Arguments.of("6f7f7820", "fcmla v0.8h, v1.8h, v31.h[3], #270",
                        new FpComplexMultiplyAccumulateByElement(true, 1, 270, 0, 1, 31, 3)),
                Arguments.of("6f925820", "fcmla v0.4s, v1.4s, v18.s[1], #180",
                        new FpComplexMultiplyAccumulateByElement(true, 2, 180, 0, 1, 18, 1)),
                Arguments.of("4f71f820", "bfdot v0.4s, v1.8h, v17.2h[3]",
                        new FpDotProductBFloat16ByElement(true, 0, 1, 17, 3)),
                Arguments.of("0f9ef820", "usdot v0.2s, v1.8b, v30.4b[2]",
                        new IntegerDotProductByElement(false, false, true, 0, 1, 30, 2)),
                Arguments.of("4f30f020", "sudot v0.4s, v1.16b, v16.4b[1]",
                        new IntegerDotProductByElement(true, true, false, 0, 1, 16, 1)),
                Arguments.of("6fb9e820", "udot v0.4s, v1.16b, v25.4b[3]",
                        new IntegerDotProductByElement(true, false, false, 0, 1, 25, 3)),
                Arguments.of("6fbfc820", "fmlsl2 v0.4s, v1.4h, v15.h[7]",
                        new FpMultiplyAddLongByElement(true, true, true, 0, 1, 15, 7)),
                Arguments.of("4fc7f820", "bfmlalt v0.4s, v1.8h, v7.h[4]",
                        new FpMultiplyAddLongBFloat16ByElement(true, 0, 1, 7, 4)),
                Arguments.of("4fff0820", "fmlalt v0.8h, v1.16b, v7.b[15]",
                        new Fp8FusedMultiplyAddLongByElement(false, 1, 0, 1, 7, 15)),
                Arguments.of("2f4d8820", "fmlallbt v0.4s, v1.16b, v5.b[9]",
                        new Fp8FusedMultiplyAddLongByElement(true, 1, 0, 1, 5, 9)),
                Arguments.of("0f7f0820", "fdot v0.4h, v1.8b, v15.2b[7]",
                        new Fp8DotProductByElement(false, false, 0, 1, 15, 7)),
                Arguments.of("4f3f0820", "fdot v0.4s, v1.16b, v31.4b[3]",
                        new Fp8DotProductByElement(true, true, 0, 1, 31, 3)));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("decodedForms")
    void fieldsFollowTheEncoding(String word, String objdump, Ir64Op expected) {
        assertEquals(expected, decodeWord(FEATURES, word), objdump);
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            4fe19020 | 4fc19020 | fmul v0.2d, v1.2d, v1.d[0] — L=1 na forma D
            0fc19020 | 4fc19020 | fmul v0.2d, v1.2d, v1.d[0] — não existe .1d
            5f418020 | 0f418020 | mul v0.4h, v1.4h, v1.h[0] — MUL não tem forma escalar
            5f41a020 | 0f41a020 | smull v0.4s, v1.4h, v1.h[0] — SMULL não tem forma escalar
            0f018020 | 0f418020 | mul v0.4h, v1.4h, v1.h[0] — inteiro com size=00
            0fc18020 | 0f418020 | mul v0.4h, v1.4h, v1.h[0] — inteiro com size=11
            2f411820 | 2f411020 | fcmla v0.4h, v1.4h, v1.h[0], #0 — H=1 em .4h
            6fa11020 | 6f811020 | fcmla v0.4s, v1.4s, v1.s[0], #0 — L=1 em .4s
            2f811020 | 6f811020 | fcmla v0.4s, v1.4s, v1.s[0], #0 — não existe .2s
            5f810020 | 0f810020 | fmlal v0.2s, v1.2h, v1.h[0] — FMLAL não tem forma escalar
            5f41f020 | 0f41f020 | bfdot v0.2s, v1.4h, v1.2h[0] — BFDOT não tem forma escalar
            2fc18020 | 2f018020 | fmlallbb v0.4s, v1.16b, v1.b[0] — FMLALL com bit23=1
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FEATURES, rejected), objdump);
        assertDoesNotThrow(() -> decodeWord(FEATURES, neighbour), objdump);
    }

    /// Recusada sem a feature, aceita com ela (sozinha).
    @ParameterizedTest(name = "{0} precisa de {2}")
    @CsvSource(delimiter = '|', textBlock = """
            4f3f9820 | fmul v0.8h, v1.8h, v15.h[7]          | FP16
            7f94d820 | sqrdmlah s0, s1, v20.s[2]            | RDM
            6f925820 | fcmla v0.4s, v1.4s, v18.s[1], #180   | COMPLEX_NUMBER_ARITHMETIC
            4f71f820 | bfdot v0.4s, v1.8h, v17.2h[3]        | BFLOAT16
            4fc7f820 | bfmlalt v0.4s, v1.8h, v7.h[4]        | BFLOAT16
            0f9ef820 | usdot v0.2s, v1.8b, v30.4b[2]        | INT8_MATRIX_MULTIPLY
            4f30f020 | sudot v0.4s, v1.16b, v16.4b[1]       | INT8_MATRIX_MULTIPLY
            6fb9e820 | udot v0.4s, v1.16b, v25.4b[3]        | DOT_PRODUCT
            6fbfc820 | fmlsl2 v0.4s, v1.4h, v15.h[7]        | FP16_FUSED_MULTIPLY_ADD_LONG
            4fff0820 | fmlalt v0.8h, v1.16b, v7.b[15]       | FP8_FUSED_MULTIPLY_ADD
            2f4d8820 | fmlallbt v0.4s, v1.16b, v5.b[9]      | FP8_FUSED_MULTIPLY_ADD
            0f7f0820 | fdot v0.4h, v1.8b, v15.2b[7]         | FP8_DOT_PRODUCT_2WAY
            4f3f0820 | fdot v0.4s, v1.16b, v31.4b[3]        | FP8_DOT_PRODUCT_4WAY
            """)
    void featureFormsNeedTheirFeature(String word, String objdump, Aarch64Feature feature) {
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(Aarch64Architecture.ARMV8_0_A, word),
                objdump);
        assertDoesNotThrow(() -> decodeWord(Aarch64Architecture.of("e15.15g-" + feature, feature), word), objdump);
    }
}
