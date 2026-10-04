package dev.vitorsilverio.armjitter.decoder64;

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
        DecodeRow<String> row = DecodeRow.of("1... .... .... .... .... .... .... ..01", null, (word, address) -> "x");
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
        assertThrows(NullPointerException.class, () -> new DecodeRow<String>(0x1, 0x1, null, null));
    }

    @Test
    void rowsWithAbsentFeatureAreDroppedAndBaseRowsKept() {
        DecodeRow<String> base = new DecodeRow<>(0xF, 0x1, null, (word, address) -> "base");
        DecodeRow<String> rdm = new DecodeRow<>(0xF, 0x2, Aarch64Feature.RDM, (word, address) -> "rdm");
        List<DecodeRow<String>> rows = List.of(base, rdm);

        DecodeTable<String> baseTable = DecodeTable.forArchitecture(rows, BASE);
        assertEquals(List.of(base), baseTable.rows());
        assertEquals("base", baseTable.decode(0x1, ADDRESS));
        assertNull(baseTable.decode(0x2, ADDRESS));

        DecodeTable<String> rdmTable = DecodeTable.forArchitecture(rows, WITH_RDM);
        assertEquals(List.of(base, rdm), rdmTable.rows());
        assertEquals("rdm", rdmTable.decode(0x2, ADDRESS));
        assertNull(rdmTable.decode(0x3, ADDRESS));
    }

    @Test
    void emptyTableDecodesNothing() {
        DecodeTable<String> table = DecodeTable.forArchitecture(List.of(), BASE);
        assertEquals(0, table.keyMask());
        assertNull(table.decode(0x1234_5678, ADDRESS));
    }

    @Test
    void keyUsesOnlyBitsFixedInEveryRowThatSplitTheRows() {
        // bit0 separa as linhas; bit4 é fixo nas duas mas igual (não separa); bit8 só é fixo numa.
        DecodeRow<String> a = new DecodeRow<>(0x111, 0x010, null, (word, address) -> "a");
        DecodeRow<String> b = new DecodeRow<>(0x011, 0x011, null, (word, address) -> "b");
        DecodeTable<String> table = DecodeTable.forArchitecture(List.of(a, b), BASE);
        assertEquals(0x1, table.keyMask());
        assertEquals("a", table.decode(0x010, ADDRESS));
        assertEquals("b", table.decode(0x111, ADDRESS));
        assertNull(table.decode(0x110, ADDRESS)); // bucket de `a`, mas bit8 diverge
    }

    @Test
    void keyIsCappedAndPrefersTheMostBalancedBits() {
        // 12 bits candidatos (0..11), todos fixos; bits 0..1 dividem 2048×2048 e os outros menos.
        List<DecodeRow<Integer>> rows = new ArrayList<>();
        int fixed = 0xFFF;
        for (int value = 0; value < 4096; value++) {
            int v = value;
            rows.add(new DecodeRow<>(fixed, v, null, (word, address) -> v));
        }
        DecodeTable<Integer> table = DecodeTable.forArchitecture(rows, BASE);
        assertEquals(DecodeTable.MAX_KEY_BITS, Integer.bitCount(table.keyMask()));
        // Empate total (todo bit divide 2048×2048): ficam os 10 mais altos.
        assertEquals(0xFFC, table.keyMask());
        DecodeTableInvariants.assertNoOverlap(rows);
        DecodeTableInvariants.assertReachable(table, 0, 1L);
    }

    @Test
    void buildReceivesTheInstructionAddress() {
        DecodeRow<Long> row = DecodeRow.of("1... .... .... .... .... .... .... ....", null, (word, address) -> address);
        DecodeTable<Long> table = DecodeTable.forArchitecture(List.of(row), BASE);
        assertEquals(ADDRESS, table.decode(0x8000_0000, ADDRESS));
    }

    @Test
    void overlapIsDetected() {
        DecodeRow<String> a = new DecodeRow<>(0x3, 0x1, null, (word, address) -> "a");
        DecodeRow<String> b = new DecodeRow<>(0x1, 0x1, null, (word, address) -> "b");
        assertThrows(AssertionError.class, () -> DecodeTableInvariants.assertNoOverlap(List.of(a, b)));
    }
}
