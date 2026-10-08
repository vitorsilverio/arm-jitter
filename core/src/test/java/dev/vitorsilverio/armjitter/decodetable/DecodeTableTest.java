package dev.vitorsilverio.armjitter.decodetable;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// E15.9: infra {@link DecodeTable}/{@link DecodeRow} com linhas sintéticas.
class DecodeTableTest {
    private static final Aarch64Architecture BASE = Aarch64Architecture.ARMV8_0_A;
    private static final Aarch64Architecture WITH_RDM = Aarch64Architecture.of("rdm", Aarch64Feature.RDM);
    private static final long ADDRESS = 0x4000L;

    @Test
    void patternParsesFixedAndFreeBits() {
        DecodeRow<Aarch64Feature, String> row = DecodeRow.of("1... .... .... .... .... .... .... ..01", null, (word, address) -> "x");
        assertEquals(0x8000_0003, row.mask());
        assertEquals(0x8000_0001, row.value());
        assertTrue(row.matches(0xFFFF_FFFD));
        assertFalse(row.matches(0x0000_0001));
    }

    @Test
    void patternWithWrongLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DecodeRow.of("0101", null, (word, address) -> "x"));
    }

    @Test
    void patternWithUnknownSymbolIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DecodeRow.of("x... .... .... .... .... .... .... ....", null, (word, address) -> "x"));
    }

    @Test
    void valueOutsideMaskIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DecodeRow<>(0x1, 0x2, null, (word, address) -> "x"));
    }

    @Test
    void buildIsRequired() {
        assertThrows(NullPointerException.class, () -> new DecodeRow<Aarch64Feature, String>(0x1, 0x1, null, null));
    }

    @Test
    void rowsWithAbsentFeatureAreDroppedAndBaseRowsKept() {
        DecodeRow<Aarch64Feature, String> base = new DecodeRow<>(0xF, 0x1, null, (word, address) -> "base");
        DecodeRow<Aarch64Feature, String> rdm = new DecodeRow<>(0xF, 0x2, Aarch64Feature.RDM, (word, address) -> "rdm");
        List<DecodeRow<Aarch64Feature, String>> rows = List.of(base, rdm);

        DecodeTable<Aarch64Feature, String> baseTable = DecodeTable.forFeatures(rows, BASE::has);
        assertEquals(List.of(base), baseTable.rows());
        assertEquals("base", baseTable.decode(0x1, ADDRESS));
        assertNull(baseTable.decode(0x2, ADDRESS));

        DecodeTable<Aarch64Feature, String> rdmTable = DecodeTable.forFeatures(rows, WITH_RDM::has);
        assertEquals(List.of(base, rdm), rdmTable.rows());
        assertEquals("rdm", rdmTable.decode(0x2, ADDRESS));
        assertNull(rdmTable.decode(0x3, ADDRESS));
    }

    /// E15.12: `alsoRequires` é conjunção — a linha só existe quando o preset declara as duas features.
    @Test
    void rowWithTwoFeaturesNeedsBoth() {
        DecodeRow<Aarch64Feature, String> both = DecodeRow.of("0000 0000 0000 0000 0000 0000 0000 0001", Aarch64Feature.RDM,
                Aarch64Feature.LSE, (word, address) -> "both");
        List<DecodeRow<Aarch64Feature, String>> rows = List.of(both);

        assertEquals(List.of(), DecodeTable.forFeatures(rows, WITH_RDM::has).rows());
        assertEquals(List.of(), DecodeTable.forFeatures(rows, Aarch64Architecture.of("lse", Aarch64Feature.LSE)::has).rows());
        DecodeTable<Aarch64Feature, String> table = DecodeTable.forFeatures(rows,
                Aarch64Architecture.of("rdm+lse", Aarch64Feature.RDM, Aarch64Feature.LSE)::has);
        assertEquals("both", table.decode(0x1, ADDRESS));
    }

    @Test
    void emptyTableDecodesNothing() {
        DecodeTable<Aarch64Feature, String> table = DecodeTable.forFeatures(List.of(), BASE::has);
        assertEquals(0, table.keyMask());
        assertNull(table.decode(0x1234_5678, ADDRESS));
    }

    @Test
    void keyUsesOnlyBitsFixedInEveryRowThatSplitTheRows() {
        // bit0 separa as linhas; bit4 é fixo nas duas mas igual (não separa); bit8 só é fixo numa.
        DecodeRow<Aarch64Feature, String> a = new DecodeRow<>(0x111, 0x010, null, (word, address) -> "a");
        DecodeRow<Aarch64Feature, String> b = new DecodeRow<>(0x011, 0x011, null, (word, address) -> "b");
        DecodeTable<Aarch64Feature, String> table = DecodeTable.forFeatures(List.of(a, b), BASE::has);
        assertEquals(0x1, table.keyMask());
        assertEquals("a", table.decode(0x010, ADDRESS));
        assertEquals("b", table.decode(0x111, ADDRESS));
        assertNull(table.decode(0x110, ADDRESS)); // bucket de `a`, mas bit8 diverge
    }

    @Test
    void keyIsCappedAndPrefersTheMostBalancedBits() {
        // 12 bits candidatos (0..11), todos fixos; bits 0..1 dividem 2048×2048 e os outros menos.
        List<DecodeRow<Aarch64Feature, Integer>> rows = new ArrayList<>();
        int fixed = 0xFFF;
        for (int value = 0; value < 4096; value++) {
            int v = value;
            rows.add(new DecodeRow<>(fixed, v, null, (word, address) -> v));
        }
        DecodeTable<Aarch64Feature, Integer> table = DecodeTable.forFeatures(rows, BASE::has);
        assertEquals(DecodeTable.MAX_KEY_BITS, Integer.bitCount(table.keyMask()));
        // Empate total (todo bit divide 2048×2048): ficam os 10 mais altos.
        assertEquals(0xFFC, table.keyMask());
        DecodeTableInvariants.assertNoOverlap(rows);
        DecodeTableInvariants.assertReachable(table, 0, 1L);
    }

    @Test
    void buildReceivesTheInstructionAddress() {
        DecodeRow<Aarch64Feature, Long> row = DecodeRow.of("1... .... .... .... .... .... .... ....", null, (word, address) -> address);
        DecodeTable<Aarch64Feature, Long> table = DecodeTable.forFeatures(List.of(row), BASE::has);
        assertEquals(ADDRESS, table.decode(0x8000_0000, ADDRESS));
    }

    @Test
    void overlapIsDetected() {
        DecodeRow<Aarch64Feature, String> a = new DecodeRow<>(0x3, 0x1, null, (word, address) -> "a");
        DecodeRow<Aarch64Feature, String> b = new DecodeRow<>(0x1, 0x1, null, (word, address) -> "b");
        assertThrows(AssertionError.class, () -> DecodeTableInvariants.assertNoOverlap(List.of(a, b)));
    }

    /// E15.16a: linha com `whenAbsent` não some sem a feature — continua casando e constrói por ele.
    @Test
    void rowWithWhenAbsentStaysAndBuildsByIt() {
        DecodeRow<Aarch64Feature, String> rdm = new DecodeRow<Aarch64Feature, String>(0xF, 0x2, Aarch64Feature.RDM,
                (word, address) -> "rdm").orWhenAbsent((word, address) -> "sem rdm");
        List<DecodeRow<Aarch64Feature, String>> rows = List.of(rdm);

        DecodeTable<Aarch64Feature, String> baseTable = DecodeTable.forFeatures(rows, BASE::has);
        assertEquals(1, baseTable.rows().size());
        assertEquals("sem rdm", baseTable.decode(0x2, ADDRESS));
        assertEquals(Aarch64Feature.RDM, baseTable.rows().get(0).requires());

        DecodeTable<Aarch64Feature, String> rdmTable = DecodeTable.forFeatures(rows, WITH_RDM::has);
        assertEquals(List.of(rdm), rdmTable.rows());
        assertEquals("rdm", rdmTable.decode(0x2, ADDRESS));
    }

    @Test
    void whenAbsentIsRequiredByOrWhenAbsent() {
        DecodeRow<Aarch64Feature, String> row = new DecodeRow<>(0x1, 0x1, null, (word, address) -> "x");
        assertThrows(NullPointerException.class, () -> row.orWhenAbsent(null));
    }
}
