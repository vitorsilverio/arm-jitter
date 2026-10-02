package dev.vitorsilverio.armjitter.jit64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/// Índice de invalidação por página física de {@link BlockCache64} (F11, código auto-modificável).
class BlockCache64InvalidationTest {
    private static final CompiledBlock64 BLOCK = core -> 1;
    private static final BlockKey64 A = new BlockKey64(0x1000);
    private static final BlockKey64 B = new BlockKey64(0x2000);
    private static final BlockKey64 SPANNING = new BlockKey64(0x1FF0);

    @Test
    void invalidatingAPageDropsOnlyTheBlocksOnThatPage() {
        BlockCache64 cache = new BlockCache64();
        cache.put(A, BLOCK, new long[] {1});
        cache.put(B, BLOCK, new long[] {2});

        assertEquals(1, cache.invalidatePhysicalPage(1));

        assertNull(cache.getOrNull(A));
        assertNotNull(cache.getOrNull(B));
        assertEquals(1, cache.size());
    }

    @Test
    void aBlockSpanningTwoPagesIsDroppedByEitherAndForgottenByBoth() {
        BlockCache64 cache = new BlockCache64();
        cache.put(SPANNING, BLOCK, new long[] {1, 2});

        assertEquals(1, cache.invalidatePhysicalPage(2));
        assertNull(cache.getOrNull(SPANNING));
        assertEquals(0, cache.invalidatePhysicalPage(1), "a outra página não guarda mais o bloco");
    }

    @Test
    void invalidatingAPageWithNoBlocksIsANoOp() {
        BlockCache64 cache = new BlockCache64();
        cache.put(A, BLOCK, new long[] {1});

        assertEquals(0, cache.invalidatePhysicalPage(99));
        assertNotNull(cache.getOrNull(A));
    }

    @Test
    void replacingAKeyMovesItToTheNewPages() {
        BlockCache64 cache = new BlockCache64();
        cache.put(A, BLOCK, new long[] {1});
        cache.put(A, BLOCK, new long[] {5});

        assertEquals(0, cache.invalidatePhysicalPage(1), "o registro antigo foi desfeito");
        assertNotNull(cache.getOrNull(A));
        assertEquals(1, cache.invalidatePhysicalPage(5));
    }

    @Test
    void twoBlocksOnTheSamePageAreBothDropped() {
        BlockCache64 cache = new BlockCache64();
        cache.put(A, BLOCK, new long[] {7});
        cache.put(B, BLOCK, new long[] {7});

        assertEquals(2, cache.invalidatePhysicalPage(7));
        assertEquals(0, cache.size());
    }

    @Test
    void clearForgetsThePageIndexToo() {
        BlockCache64 cache = new BlockCache64();
        cache.put(A, BLOCK, new long[] {1});

        cache.clear();

        assertEquals(0, cache.invalidatePhysicalPage(1));
        assertEquals(0, cache.size());
    }

    @Test
    void blocksInsertedWithoutPagesAreNeverInvalidatedByPage() {
        BlockCache64 cache = new BlockCache64();
        cache.put(A, BLOCK);

        assertEquals(0, cache.invalidatePhysicalPage(1));
        assertNotNull(cache.getOrNull(A));
    }

    @Test
    void invalidatedBlockKeepsItsHitCounterSoItRecompilesOnTheNextRun() {
        BlockCache64 cache = new BlockCache64();
        cache.hit(A);
        cache.hit(A);
        cache.put(A, BLOCK, new long[] {1});

        cache.invalidatePhysicalPage(1);

        assertEquals(3, cache.hit(A));
    }

    @Test
    void aBlockWhoseTwoVirtualPagesAliasTheSamePhysicalPageIsDroppedOnce() {
        BlockCache64 cache = new BlockCache64();
        cache.put(SPANNING, BLOCK, new long[] {3, 3});

        assertEquals(1, cache.invalidatePhysicalPage(3));
        assertEquals(0, cache.size());
        assertEquals(0, cache.invalidatePhysicalPage(3));
    }
}
