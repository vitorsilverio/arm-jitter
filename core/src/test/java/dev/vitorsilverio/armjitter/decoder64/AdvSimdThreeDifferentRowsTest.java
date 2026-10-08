package dev.vitorsilverio.armjitter.decoder64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.decodetable.DecodeTableInvariants;
import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticNarrow;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticWide;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.ArithmeticWidening;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64.PolynomialMultiplyLong;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorNarrowOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorWideningOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// E15.15d: o "three different" como tabela ({@link AdvSimdThreeDifferentRows}). Comportamento idêntico ao da
/// cascata (oráculo `e15.15d-scripts/AdvSimdBit10ZeroOracle.java`, diff vazio). A sobreposição com o resto da
/// `advSimdTable` é testada em {@link AdvSimdPermuteCopyRowsTest#advSimdTableRowsNeverOverlap}.
class AdvSimdThreeDifferentRowsTest {
    private static final long ADDRESS = 0x1000L;
    /// 28 alargando, 12 saturantes dobrados (6 vetoriais + 6 escalares), 8 largo+estreito, 8 estreitando, 2 `PMULL`.
    private static final int ROW_COUNT = 58;
    private static final Aarch64Architecture BASE = Aarch64Architecture.ARMV8_0_A;

    private static Ir64Op decodeWord(int word) {
        return new Aarch64Decoder(BASE).decode(word, ADDRESS);
    }

    @Test
    void rowsNeverOverlap() {
        assertEquals(ROW_COUNT, AdvSimdThreeDifferentRows.ROWS.size());
        DecodeTableInvariants.assertNoOverlap(AdvSimdThreeDifferentRows.ROWS);
    }

    @Test
    void everyRowIsReachable() {
        DecodeTable<Aarch64Feature, Ir64Op> table = DecodeTable.forFeatures(AdvSimdThreeDifferentRows.ROWS, BASE::has);
        assertEquals(ROW_COUNT, table.rows().size());
        DecodeTableInvariants.assertReachable(table, 0, 0xE1515DL);
    }

    @Test
    void everyRowIsReachableThroughTheDecoder() {
        Aarch64Decoder decoder = new Aarch64Decoder(BASE);
        SplittableRandom random = new SplittableRandom(0xE1515DL);
        for (DecodeRow<Aarch64Feature, Ir64Op> row : AdvSimdThreeDifferentRows.ROWS) {
            int word = row.value() | (random.nextInt() & ~row.mask());
            assertEquals(row.build().decode(word, ADDRESS), decoder.decode(word, ADDRESS), Integer.toHexString(word));
        }
    }

    /// Campos conferidos contra o `objdump` 2.46 do devkitA64 (`-M no-aliases`); `Rd=0`, `Rn=1`, `Rm=2`.
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e220020 | saddl v0.8h, v1.8b, v2.8b      | SADDL   | false | false | 0
            6e620020 | uaddl2 v0.4s, v1.8h, v2.8h     | UADDL   | false | true  | 1
            0ea22020 | ssubl v0.2d, v1.2s, v2.2s      | SSUBL   | false | false | 2
            6e222020 | usubl2 v0.8h, v1.16b, v2.16b   | USUBL   | false | true  | 0
            0e625020 | sabal v0.4s, v1.4h, v2.4h      | SABAL   | false | false | 1
            6ea25020 | uabal2 v0.2d, v1.4s, v2.4s     | UABAL   | false | true  | 2
            0e227020 | sabdl v0.8h, v1.8b, v2.8b      | SABDL   | false | false | 0
            2e627020 | uabdl v0.4s, v1.4h, v2.4h      | UABDL   | false | false | 1
            0ea28020 | smlal v0.2d, v1.2s, v2.2s      | SMLAL   | false | false | 2
            6e228020 | umlal2 v0.8h, v1.16b, v2.16b   | UMLAL   | false | true  | 0
            0e62a020 | smlsl v0.4s, v1.4h, v2.4h      | SMLSL   | false | false | 1
            6ea2a020 | umlsl2 v0.2d, v1.4s, v2.4s     | UMLSL   | false | true  | 2
            0e22c020 | smull v0.8h, v1.8b, v2.8b      | SMULL   | false | false | 0
            6e62c020 | umull2 v0.4s, v1.8h, v2.8h     | UMULL   | false | true  | 1
            0e629020 | sqdmlal v0.4s, v1.4h, v2.4h    | SQDMLAL | false | false | 1
            4ea2b020 | sqdmlsl2 v0.2d, v1.4s, v2.4s   | SQDMLSL | false | true  | 2
            0e62d020 | sqdmull v0.4s, v1.4h, v2.4h    | SQDMULL | false | false | 1
            5e62d020 | sqdmull s0, h1, h2             | SQDMULL | true  | false | 1
            5ea29020 | sqdmlal d0, s1, s2             | SQDMLAL | true  | false | 2
            5e62b020 | sqdmlsl s0, h1, h2             | SQDMLSL | true  | false | 1
            """)
    void wideningFieldsFollowTheEncoding(String word, String objdump, Ir64VectorWideningOp op, boolean scalar,
            boolean q, int esz) {
        assertEquals(new ArithmeticWidening(op, scalar, q, esz, 0, 1, 2), decodeWord(Integer.parseUnsignedInt(word, 16)),
                objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e221020 | saddw v0.8h, v1.8h, v2.8b    | SADDW | false | 0
            6e621020 | uaddw2 v0.4s, v1.4s, v2.8h   | UADDW | true  | 1
            0ea23020 | ssubw v0.2d, v1.2d, v2.2s    | SSUBW | false | 2
            6e223020 | usubw2 v0.8h, v1.8h, v2.16b  | USUBW | true  | 0
            """)
    void wideFieldsFollowTheEncoding(String word, String objdump, Ir64VectorWideOp op, boolean q, int esz) {
        assertEquals(new ArithmeticWide(op, q, esz, 0, 1, 2), decodeWord(Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0e224020 | addhn v0.8b, v1.8h, v2.8h     | ADDHN  | false | 0
            6e624020 | raddhn2 v0.8h, v1.4s, v2.4s   | RADDHN | true  | 1
            0ea26020 | subhn v0.2s, v1.2d, v2.2d     | SUBHN  | false | 2
            6e226020 | rsubhn2 v0.16b, v1.8h, v2.8h  | RSUBHN | true  | 0
            """)
    void narrowFieldsFollowTheEncoding(String word, String objdump, Ir64VectorNarrowOp op, boolean q, int esz) {
        assertEquals(new ArithmeticNarrow(op, q, esz, 0, 1, 2), decodeWord(Integer.parseUnsignedInt(word, 16)), objdump);
    }

    @Test
    void polynomialMultiplyFieldsFollowTheEncoding() {
        // pmull v0.8h, v1.8b, v2.8b · pmull2 v0.1q, v1.2d, v2.2d
        assertEquals(new PolynomialMultiplyLong(false, false, 0, 1, 2), decodeWord(0x0e22e020));
        assertEquals(new PolynomialMultiplyLong(true, true, 0, 1, 2), decodeWord(0x4ee2e020));
    }

    /// Coluna 1 = `.inst ... ; undefined` no `objdump` 2.46 do devkitA64; coluna 2 = a vizinha legítima, como o
    /// `objdump` a desmonta. Todas já eram recusadas pela cascata.
    @ParameterizedTest(name = "{0} recusada, {1} ({2}) aceita")
    @CsvSource(delimiter = '|', textBlock = """
            0ee20020 | 0ea20020 | saddl v0.2d — elemento estreito de 64 bits
            0e22d020 | 0e62d020 | sqdmull v0.4s — SQDMULL de byte
            2e62d020 | 0e62d020 | sqdmull v0.4s — não existe UQDMULL
            0e62e020 | 0e22e020 | pmull v0.8h — PMULL só p8 e p64
            2e22e020 | 0e22e020 | pmull v0.8h — PMULL com U=1
            0e22f020 | 0e22e020 | pmull v0.8h — opcode 1111 não está alocado
            5e22d020 | 5e62d020 | sqdmull s0 — escalar de byte
            5ee2d020 | 5ea2d020 | sqdmull d0 — escalar de 64 bits
            7e62d020 | 5e62d020 | sqdmull s0 — escalar com U=1
            5e22c020 | 0e22c020 | smull v0.8h — SMULL não tem forma escalar
            """)
    void reservedFieldIsRejectedAndNeighbourStillDecodes(String rejected, String neighbour, String objdump) {
        int rejectedWord = Integer.parseUnsignedInt(rejected, 16);
        int neighbourWord = Integer.parseUnsignedInt(neighbour, 16);
        assertThrows(UnsupportedOperationException.class, () -> decodeWord(rejectedWord), objdump);
        assertDoesNotThrow(() -> decodeWord(neighbourWord), objdump);
    }
}
