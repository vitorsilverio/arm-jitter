package dev.vitorsilverio.armjitter.jit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockCacheTest {
    @Test
    void evictsLeastRecentlyUsedBlock() {
        BlockCache cache = new BlockCache(2);
        CompiledBlock first = core -> 1;
        CompiledBlock second = core -> 2;
        CompiledBlock third = core -> 3;

        cache.put(0x100, first);
        cache.put(0x200, second);
        assertTrue(cache.get(0x100).isPresent());
        cache.put(0x300, third);

        assertTrue(cache.get(0x100).isPresent());
        assertTrue(cache.get(0x300).isPresent());
        assertTrue(cache.get(0x200).isEmpty());
    }

    @Test
    void tracksHitsPerProgramCounter() {
        BlockCache cache = new BlockCache(2);

        assertEquals(1, cache.hit(0x08000000));
        assertEquals(2, cache.hit(0x08000000));
        assertEquals(1, cache.hit(0x08000004));
    }
}
