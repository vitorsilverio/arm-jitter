package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticPairwise;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticThreeSame;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorPairwiseOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15b: o "three same" inteiro como tabela ({@link AdvSimdThreeSameRows}). Comportamento idêntico ao da
/// cascata (oráculo `e15.15b-scripts/AdvSimdThreeSameOracle.java`, diff vazio). A sobreposição com o resto da
/// `advSimdTable` é testada em {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdThreeSameRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 99 aritméticas vetoriais, 8 lógicas, 11 pareadas e 24 escalares.
    private static final int ROW_COUNT = 142;

    private static Ir64Op decodeWord(Aarch64Decoder decoder, int word) {
        return decoder.decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdThreeSameRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdThreeSameRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(AdvSimdThreeSameRows.ROWS, Aarch64Architecture.ARMV8_0_A::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515BL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        SplittableRandom random = new SplittableRandom(0xE1515BL);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdThreeSameRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decodeWord(decoder, word), Integer.toHexString(word));
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`).
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            4ee28420 | add v0.2d, v1.2d, v2.2d       | ADD      | false | true  | 3 | 0  | 1  | 2
            2e258483 | sub v3.8b, v4.8b, v5.8b       | SUB      | false | false | 0 | 3  | 4  | 5
            6ebe3620 | cmhi v0.4s, v17.4s, v30.4s    | CMHI     | false | true  | 2 | 0  | 17 | 30
            4e620420 | shadd v0.8h, v1.8h, v2.8h     | SHADD    | false | true  | 1 | 0  | 1  | 2
            2ea97d07 | uaba v7.2s, v8.2s, v9.2s      | UABA     | false | false | 2 | 7  | 8  | 9
            6e229c20 | pmul v0.16b, v1.16b, v2.16b   | PMUL     | false | true  | 0 | 0  | 1  | 2
            2e629420 | mls v0.4h, v1.4h, v2.4h       | MLS      | false | false | 1 | 0  | 1  | 2
            6e62b420 | sqrdmulh v0.8h, v1.8h, v2.8h  | SQRDMULH | false | true  | 1 | 0  | 1  | 2
            6ee25c20 | uqrshl v0.2d, v1.2d, v2.2d    | UQRSHL   | false | true  | 3 | 0  | 1  | 2
            4e221c20 | and v0.16b, v1.16b, v2.16b    | AND      | false | true  | 0 | 0  | 1  | 2
            0ee21c20 | orn v0.8b, v1.8b, v2.8b       | ORN      | false | false | 0 | 0  | 1  | 2
            6ef61eb4 | bif v20.16b, v21.16b, v22.16b | BIF      | false | true  | 0 | 20 | 21 | 22
            2e621c20 | bsl v0.8b, v1.8b, v2.8b       | BSL      | false | false | 0 | 0  | 1  | 2
            5ee28420 | add d0, d1, d2                | ADD      | true  | false | 3 | 0  | 1  | 2
            5ee58c83 | cmtst d3, d4, d5              | CMTST    | true  | false | 3 | 3  | 4  | 5
            5e220c20 | sqadd b0, b1, b2              | SQADD    | true  | false | 0 | 0  | 1  | 2
            7e624c20 | uqshl h0, h1, h2              | UQSHL    | true  | false | 1 | 0  | 1  | 2
            7ea2b420 | sqrdmulh s0, s1, s2           | SQRDMULH | true  | false | 2 | 0  | 1  | 2
            7efd57df | urshl d31, d30, d29           | URSHL    | true  | false | 3 | 31 | 30 | 29
            """)
    void threeSameFieldsFollowTheEncoding(String word, String objdump, Ir64VectorThreeSameOp op, boolean scalar,
            boolean q, int esz, int rd, int rn, int rm) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        assertEquals(new ArithmeticThreeSame(op, scalar, q, esz, rd, rn, rm),
                decodeWord(decoder, Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            4ee2bc20 | addp v0.2d, v1.2d, v2.2d  | ADD  | true  | 3
            6ea2ac20 | uminp v0.4s, v1.4s, v2.4s | UMIN | true  | 2
            0e22a420 | smaxp v0.8b, v1.8b, v2.8b | SMAX | false | 0
            """)
    void pairwiseFieldsFollowTheEncoding(String word, String objdump, Ir64VectorPairwiseOp op, boolean q, int esz) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        assertEquals(new ArithmeticPairwise(op, q, esz, 0, 1, 2), decodeWord(decoder, Integer.parseUnsignedInt(word, 16)),
                objdump);
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            0ee28420 | 4ee28420 | add v0.2d — ADD .1d
            4ee20420 | 4e620420 | shadd v0.8h — SHADD com elemento de 64 bits
            6e629c20 | 6e229c20 | pmul v0.16b — PMUL com size=01
            6e22b420 | 6e62b420 | sqrdmulh v0.8h — SQRDMULH com size=00
            6ee2b420 | 6e62b420 | sqrdmulh v0.8h — SQRDMULH com size=11
            4ee2a420 | 4ea2a420 | smaxp v0.4s — SMAXP com elemento de 64 bits
            0ee2bc20 | 4ee2bc20 | addp v0.2d — ADDP .1d
            6ea2bc20 | 4ee2bc20 | addp v0.2d — ADDP com U=1
            5ea28420 | 5ee28420 | add d0 — ADD escalar com size=10
            7e22b420 | 7ea2b420 | sqrdmulh s0 — SQRDMULH escalar com size=00
            5e620420 | 5e620c20 | sqadd h0 — SHADD não tem forma escalar
            5e629c20 | 5e620c20 | sqadd h0 — MUL não tem forma escalar
            5e22a420 | 5e620c20 | sqadd h0 — SMAXP não tem forma escalar
            5ea2bc20 | 5e620c20 | sqadd h0 — ADDP (vetorial) não tem forma escalar com bit10=1
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        Aarch64Decoder decoder = new Aarch64Decoder(Aarch64Architecture.ARMV8_0_A);
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(decoder, rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(decoder, neighbourWord), objdump);
    }
}
