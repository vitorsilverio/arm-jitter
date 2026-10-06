package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpAbsoluteMaxMin;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticPairwise;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticThreeSame;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpMultiplyAddLong;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpScaleByInt;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpThreeSameOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15c: o "three same (FP)" `_sd` como tabela ({@link AdvSimdThreeSameFpRows}). Comportamento idêntico ao da
/// cascata (oráculo `e15.15b-scripts/AdvSimdThreeSameOracle.java`, diff vazio). A sobreposição com o resto da
/// `advSimdTable` é testada em {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdThreeSameFpRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 38 aritméticas vetoriais, 9 escalares, 10 pareadas, 4 `FMLAL`/`FMLSL`, 2 `FSCALE`, 4 `FAMAX`/`FAMIN`.
    private static final int ROW_COUNT = 67;
    /// Linhas da ISA base (sem coluna de feature).
    private static final int BASE_ROW_COUNT = 57;
    /// As três features do espaço, sem SME (que embrulharia as ops em `StreamingRestricted`).
    private static final Aarch64Architecture FEATURES = Aarch64Architecture.of("e15.15c", Aarch64Feature.FP16,
            Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG, Aarch64Feature.FP8, Aarch64Feature.FP_ABSOLUTE_MAX_MIN);

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        return decoder.decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdThreeSameFpRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdThreeSameFpRows.ROWS);
    }

    @Test
    void featureRowsAreFilteredByThePreset() {
        assertEquals(BASE_ROW_COUNT,
                DecodeTable.forArchitecture(AdvSimdThreeSameFpRows.ROWS, Aarch64Architecture.ARMV8_0_A).rows().size());
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(AdvSimdThreeSameFpRows.ROWS, FEATURES);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515CL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(FEATURES);
        SplittableRandom random = new SplittableRandom(0xE1515CL);
        for (DecodeRow<Ir64Op> row : AdvSimdThreeSameFpRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`); `Rd=0`, `Rn=1`, `Rm=2`.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            4e22d420 | fadd v0.4s, v1.4s, v2.4s    | ADD    | false | true  | 2
            4ee2d420 | fsub v0.2d, v1.2d, v2.2d    | SUB    | false | true  | 3
            2ea2d420 | fabd v0.2s, v1.2s, v2.2s    | ABD    | false | false | 2
            6e62dc20 | fmul v0.2d, v1.2d, v2.2d    | MUL    | false | true  | 3
            4e22dc20 | fmulx v0.4s, v1.4s, v2.4s   | MULX   | false | true  | 2
            2e22fc20 | fdiv v0.2s, v1.2s, v2.2s    | DIV    | false | false | 2
            4e22fc20 | frecps v0.4s, v1.4s, v2.4s  | RECPS  | false | true  | 2
            4ee2fc20 | frsqrts v0.2d, v1.2d, v2.2d | RSQRTS | false | true  | 3
            0e22e420 | fcmeq v0.2s, v1.2s, v2.2s   | CMEQ   | false | false | 2
            6e22e420 | fcmge v0.4s, v1.4s, v2.4s   | CMGE   | false | true  | 2
            6ee2e420 | fcmgt v0.2d, v1.2d, v2.2d   | CMGT   | false | true  | 3
            6e22ec20 | facge v0.4s, v1.4s, v2.4s   | FACGE  | false | true  | 2
            2ea2ec20 | facgt v0.2s, v1.2s, v2.2s   | FACGT  | false | false | 2
            4e22c420 | fmaxnm v0.4s, v1.4s, v2.4s  | MAXNM  | false | true  | 2
            4ee2c420 | fminnm v0.2d, v1.2d, v2.2d  | MINNM  | false | true  | 3
            0e22cc20 | fmla v0.2s, v1.2s, v2.2s    | MLA    | false | false | 2
            4ea2cc20 | fmls v0.4s, v1.4s, v2.4s    | MLS    | false | true  | 2
            4e62f420 | fmax v0.2d, v1.2d, v2.2d    | MAX    | false | true  | 3
            4ea2f420 | fmin v0.4s, v1.4s, v2.4s    | MIN    | false | true  | 2
            5e22dc20 | fmulx s0, s1, s2            | MULX   | true  | false | 2
            5e62dc20 | fmulx d0, d1, d2            | MULX   | true  | false | 3
            7ee2d420 | fabd d0, d1, d2             | ABD    | true  | false | 3
            5e22fc20 | frecps s0, s1, s2           | RECPS  | true  | false | 2
            5ee2fc20 | frsqrts d0, d1, d2          | RSQRTS | true  | false | 3
            5e62e420 | fcmeq d0, d1, d2            | CMEQ   | true  | false | 3
            7e22e420 | fcmge s0, s1, s2            | CMGE   | true  | false | 2
            7ee2e420 | fcmgt d0, d1, d2            | CMGT   | true  | false | 3
            7e22ec20 | facge s0, s1, s2            | FACGE  | true  | false | 2
            7ee2ec20 | facgt d0, d1, d2            | FACGT  | true  | false | 3
            """)
    void threeSameFieldsFollowTheEncoding(String word, String objdump, Ir64VectorFpThreeSameOp op, boolean scalar,
            boolean q, int esz) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        assertEquals(new FpArithmeticThreeSame(op, scalar, q, esz, 0, 1, 2),
                decodeWord(decoder, Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            6e22d420 | faddp v0.4s, v1.4s, v2.4s   | ADD   | true  | 2
            6e62f420 | fmaxp v0.2d, v1.2d, v2.2d   | MAX   | true  | 3
            2ea2f420 | fminp v0.2s, v1.2s, v2.2s   | MIN   | false | 2
            6e22c420 | fmaxnmp v0.4s, v1.4s, v2.4s | MAXNM | true  | 2
            6ee2c420 | fminnmp v0.2d, v1.2d, v2.2d | MINNM | true  | 3
            """)
    void pairwiseFieldsFollowTheEncoding(String word, String objdump, Ir64VectorFpPairwiseOp op, boolean q, int esz) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        assertEquals(new FpArithmeticPairwise(op, false, q, esz, 0, 1, 2),
                decodeWord(decoder, Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e22ec20 | fmlal v0.2s, v1.2h, v2.2h  | false | false | false
            4ea2ec20 | fmlsl v0.4s, v1.4h, v2.4h  | true  | false | true
            2e22cc20 | fmlal2 v0.2s, v1.2h, v2.2h | false | true  | false
            6ea2cc20 | fmlsl2 v0.4s, v1.4h, v2.4h | true  | true  | true
            """)
    void multiplyAddLongFieldsFollowTheEncoding(String word, String objdump, boolean q, boolean top,
            boolean subtract) {
        assertEquals(new FpMultiplyAddLong(q, top, subtract, 0, 1, 2),
                decodeWord(new Aarch64Decoder(FEATURES), Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @Test
    void scaleAndAbsoluteMaxMinFieldsFollowTheEncoding() {
        Aarch64Decoder decoder = new Aarch64Decoder(FEATURES);
        // fscale v0.4s, v1.4s, v2.4s · fscale v0.2d, v1.2d, v2.2d
        assertEquals(new FpScaleByInt(true, 2, 0, 1, 2), decodeWord(decoder, 0x6ea2fc20));
        assertEquals(new FpScaleByInt(true, 3, 0, 1, 2), decodeWord(decoder, 0x6ee2fc20));
        // famax v0.4s, v1.4s, v2.4s · famin v0.2d, v1.2d, v2.2d
        assertEquals(new FpAbsoluteMaxMin(true, true, 2, 0, 1, 2), decodeWord(decoder, 0x4ea2dc20));
        assertEquals(new FpAbsoluteMaxMin(false, true, 3, 0, 1, 2), decodeWord(decoder, 0x6ee2dc20));
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata. Preset com as três features do espaço.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            0e62d420 | 4e62d420 | fadd v0.2d — FADD .1d
            0ee2fc20 | 4ee2fc20 | frsqrts v0.2d — FRSQRTS .1d
            0ee2dc20 | 4ee2dc20 | famax v0.2d — FAMAX .1d
            0e62c420 | 4e62c420 | fmaxnm v0.2d — FMAXNM .1d
            5e22d420 | 5e22dc20 | fmulx s0 — FADD não tem forma escalar
            5e62f420 | 5e62dc20 | fmulx d0 — FMAX não tem forma escalar
            7e22d420 | 7ea2d420 | fabd s0 — FADDP não tem forma escalar com bit10=1
            7e22f420 | 7ea2d420 | fabd s0 — FMAXP não tem forma escalar com bit10=1
            4ea2e420 | 6ea2e420 | fcmgt v0.4s — U=0 a=1 opcode=11100 não está alocado
            2ea2d020 | 2ea2d420 | fabd v0.2s — bit10=0 com o mesmo opcode é outro espaço
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(FEATURES);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }

    /// `objdump` desmonta `FMLAL`/`FMLAL2` com `sz=1` (`0e62ec20`, `2e62cc20`), mas o `a64.decode` fixa `bit22=0`
    /// (`FMLAL_v 0.00 1110 001 …`): a recusa é a leitura certa, não lacuna. Sem `FEAT_FHM`, nem a forma `sz=0`
    /// existe; com ela, a forma `sz=0` decodifica.
    @Test
    void multiplyAddLongNeedsSzZeroAndTheFeature() {
        Aarch64Decoder withFhm = new Aarch64Decoder(FEATURES);
        Aarch64Decoder base = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(withFhm, 0x0e62ec20));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(withFhm, 0x2e62cc20));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(base, 0x0e22ec20));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(base, 0x6ea2fc20));
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(base, 0x4ea2dc20));
        assertDoesNotThrow(() -> decodeWord(withFhm, 0x0e22ec20));
    }
}
