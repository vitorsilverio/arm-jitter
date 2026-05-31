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
}
