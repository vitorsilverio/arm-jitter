package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockCacheRangeTest {
    @Test
    void invalidatesBlockWhenWriteFallsInsideCompiledRange() {
        BlockCache cache = new BlockCache(4);
        BlockKey key = new BlockKey(0, InstructionSet.ARM);
        cache.put(key, core -> 1, 0, 8);

        cache.invalidate(4);

        assertTrue(cache.get(key).isEmpty());
    }

    @Test
    void keepsBlockWhenWriteFallsOutsideCompiledRange() {
        BlockCache cache = new BlockCache(4);
        BlockKey key = new BlockKey(0, InstructionSet.ARM);
        cache.put(key, core -> 1, 0, 8);

        cache.invalidate(8);

        assertTrue(cache.get(key).isPresent());
    }

    @Test
    void keepsBlockWhenWriteHitsAFarUnrelatedPage() {
        BlockCache cache = new BlockCache(4);
        BlockKey key = new BlockKey(0x03000000, InstructionSet.ARM);
        cache.put(key, core -> 1, 0x03000000, 0x03000010);

        cache.invalidate(0x06000000); // escrita na VRAM: página diferente, sem código lá

        assertTrue(cache.get(key).isPresent());
    }

    @Test
    void invalidatesBlockThatSpansTwoPagesFromTheSecondPage() {
        BlockCache cache = new BlockCache(4);
        BlockKey key = new BlockKey(0x000003F0, InstructionSet.ARM);
        // [0x3F0, 0x410) atravessa a fronteira de página de 1 KiB em 0x400.
        cache.put(key, core -> 1, 0x000003F0, 0x00000410);

        cache.invalidate(0x00000404); // a escrita cai na segunda página

        assertTrue(cache.get(key).isEmpty());
    }

    @Test
    void reindexesWhenSameKeyIsRecompiledWithANewRange() {
        BlockCache cache = new BlockCache(4);
        BlockKey key = new BlockKey(0x1000, InstructionSet.ARM);
        cache.put(key, core -> 1, 0x1000, 0x1008);
        cache.put(key, core -> 2, 0x2000, 0x2008); // recompilado em outro lugar

        cache.invalidate(0x1004); // o intervalo antigo não deve mais bater
        assertTrue(cache.get(key).isPresent());

        cache.invalidate(0x2004); // o intervalo novo deve bater
        assertTrue(cache.get(key).isEmpty());
    }

    @Test
    void doesNotResurrectEvictedBlockOnLaterWriteToItsOldPage() {
        BlockCache cache = new BlockCache(1);
        BlockKey first = new BlockKey(0x1000, InstructionSet.ARM);
        BlockKey second = new BlockKey(0x5000, InstructionSet.ARM);
        cache.put(first, core -> 1, 0x1000, 0x1008);
        cache.put(second, core -> 2, 0x5000, 0x5008); // remove `first` do cache

        assertTrue(cache.get(first).isEmpty());
        cache.invalidate(0x1004); // precisa ser um no-op inofensivo, sem tocar `second`

        assertTrue(cache.get(second).isPresent());
        assertEquals(1, cache.size());
    }
}
