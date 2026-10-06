package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpAcrossLanes;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64.FpArithmeticPairwise;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.AcrossLanes;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ScalarPairwiseAdd;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpAcrossLanesOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorFpPairwiseOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15d: "across lanes" e "scalar pairwise" como tabela ({@link AdvSimdAcrossLanesRows}). Comportamento idêntico
/// ao da cascata (oráculo `e15.15d-scripts/AdvSimdBit10ZeroOracle.java`, diff vazio). A sobreposição com o resto da
/// `advSimdTable` é testada em {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdAcrossLanesRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 14 inteiras, 8 FP across lanes (4 `_h`), 1 `ADDP`, 10 FP scalar pairwise (5 `_h`).
    private static final int ROW_COUNT = 33;
    /// Sem as 9 formas `_h` (`FEAT_FP16`).
    private static final int BASE_ROW_COUNT = 24;
    /// `FEAT_FP16` sem SME (que embrulharia as ops em `StreamingRestricted`).
    private static final Aarch64Architecture FP16 = Aarch64Architecture.of("e15.15d", Aarch64Feature.FP16);

    private static Ir64Op decodeWord(Aarch64Architecture architecture, int word) {
        return new Aarch64Decoder(architecture).decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdAcrossLanesRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdAcrossLanesRows.ROWS);
    }

    @Test
    void featureRowsAreFilteredByThePreset() {
        assertEquals(BASE_ROW_COUNT,
                DecodeTable.forArchitecture(AdvSimdAcrossLanesRows.ROWS, Aarch64Architecture.ARMV8_0_A).rows().size());
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Ir64Op> table = DecodeTable.forArchitecture(AdvSimdAcrossLanesRows.ROWS, FP16);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515DL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(FP16);
        SplittableRandom random = new SplittableRandom(0xE1515DL);
        for (DecodeRow<Ir64Op> row : AdvSimdAcrossLanesRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decoder.decode(word, ADDRESS), Integer.toHexString(word));
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`); `Rd=0`, `Rn=1`.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e303820 | saddlv h0, v1.8b  | SADDLV | false | 0
            6e703820 | uaddlv s0, v1.8h  | UADDLV | true  | 1
            4e30a820 | smaxv b0, v1.16b  | SMAXV  | true  | 0
            2e70a820 | umaxv h0, v1.4h   | UMAXV  | false | 1
            4eb1a820 | sminv s0, v1.4s   | SMINV  | true  | 2
            2e31a820 | uminv b0, v1.8b   | UMINV  | false | 0
            4e71b820 | addv h0, v1.8h    | ADDV   | true  | 1
            """)
    void integerFieldsFollowTheEncoding(String word, String objdump, Ir64VectorAcrossLanesOp op, boolean q, int esz) {
        assertEquals(new AcrossLanes(op, q, esz, 0, 1),
                decodeWord(Aarch64Architecture.ARMV8_0_A, Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            6e30c820 | fmaxnmv s0, v1.4s | FMAXNMV | true  | 2
            6eb0c820 | fminnmv s0, v1.4s | FMINNMV | true  | 2
            6e30f820 | fmaxv s0, v1.4s   | FMAXV   | true  | 2
            6eb0f820 | fminv s0, v1.4s   | FMINV   | true  | 2
            0e30f820 | fmaxv h0, v1.4h   | FMAXV   | false | 1
            4eb0c820 | fminnmv h0, v1.8h | FMINNMV | true  | 1
            """)
    void fpFieldsFollowTheEncoding(String word, String objdump, Ir64VectorFpAcrossLanesOp op, boolean q, int esz) {
        assertEquals(new FpAcrossLanes(op, q, esz, 0, 1), decodeWord(FP16, Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            7e30d820 | faddp s0, v1.2s   | ADD   | 2
            7e70d820 | faddp d0, v1.2d   | ADD   | 3
            7e30f820 | fmaxp s0, v1.2s   | MAX   | 2
            7ef0f820 | fminp d0, v1.2d   | MIN   | 3
            7e30c820 | fmaxnmp s0, v1.2s | MAXNM | 2
            7ef0c820 | fminnmp d0, v1.2d | MINNM | 3
            5e30d820 | faddp h0, v1.2h   | ADD   | 1
            5eb0c820 | fminnmp h0, v1.2h | MINNM | 1
            """)
    void scalarPairwiseFieldsFollowTheEncoding(String word, String objdump, Ir64VectorFpPairwiseOp op, int esz) {
        assertEquals(new FpArithmeticPairwise(op, true, false, esz, 0, 1, 1),
                decodeWord(FP16, Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @Test
    void scalarAddPairwiseFieldsFollowTheEncoding() {
        // addp d0, v1.2d
        assertEquals(new ScalarPairwiseAdd(0, 1), decodeWord(Aarch64Architecture.ARMV8_0_A, 0x5ef1b820));
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata. Preset com `FEAT_FP16`.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            0eb03820 | 4eb03820 | saddlv d0, v1.4s — reduzir .2s é a forma pareada
            4ef03820 | 4eb03820 | saddlv d0, v1.4s — sem elemento de 64 bits
            0e313820 | 0e303820 | saddlv h0, v1.8b — Rm=10001 é do ADDV
            2e71b820 | 4e71b820 | addv h0, v1.8h — ADDV com U=1
            6e70c820 | 6e30c820 | fmaxnmv s0, v1.4s — sz=1
            2e30c820 | 6e30c820 | fmaxnmv s0, v1.4s — forma _s só com Q=1
            5eb1b820 | 5ef1b820 | addp d0, v1.2d — ADDP só D
            7ef1b820 | 5ef1b820 | addp d0, v1.2d — ADDP com U=1
            7eb0d820 | 7e30d820 | faddp s0, v1.2s — FADDP com a=1
            7e31d820 | 7e30d820 | faddp s0, v1.2s — Rm=10001
            5e70d820 | 5e30d820 | faddp h0, v1.2h — forma _h com sz=1
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(FP16, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(FP16, neighbourWord), objdump);
    }

    /// As formas `_h` só existem com `FEAT_FP16`.
    @Test
    void halfPrecisionFormsNeedFp16() {
        for (int word : new int[] {0x0e30f820, 0x4eb0c820, 0x5e30d820, 0x5eb0c820}) {
            assertThrows(UnsupportedOperationException.class, () -> decodeWord(Aarch64Architecture.ARMV8_0_A, word),
                    Integer.toHexString(word));
            assertDoesNotThrow(() -> decodeWord(FP16, word), Integer.toHexString(word));
        }
    }
}
