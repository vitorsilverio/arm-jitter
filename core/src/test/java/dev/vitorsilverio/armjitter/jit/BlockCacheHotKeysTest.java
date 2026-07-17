package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes de {@link BlockCache#hotKeys(int)} (task C10 — warm-start): a base para
/// {@link JitRuntime#hotBlockKeys(int)}. Testado no nível do {@link BlockCache} para controlar
/// exatamente os contadores de acerto sem depender de temporização de compilação em background.
class BlockCacheHotKeysTest {
    private static CompiledBlock stubBlock() {
        return core -> 1;
    }

    @Test
    void ordersByHitCountDescendingAndExcludesColdEntries() {
        BlockCache cache = new BlockCache(16);
        BlockKey cold = new BlockKey(0, InstructionSet.ARM);
        BlockKey warm = new BlockKey(4, InstructionSet.ARM);
        BlockKey hot = new BlockKey(8, InstructionSet.ARM);

        // `cold` nunca é promovido (compiled=false): não deve aparecer em hotKeys.
        cache.put(cold, stubBlock(), false, 0, 4);
        cache.hit(cold);
        cache.hit(cold);
        cache.hit(cold);
        cache.hit(cold);

        cache.put(warm, stubBlock(), true, 4, 8);
        cache.hit(warm);
        cache.hit(warm);

        cache.put(hot, stubBlock(), true, 8, 12);
        cache.hit(hot);
        cache.hit(hot);
        cache.hit(hot);
        cache.hit(hot);
        cache.hit(hot);

        List<BlockKey> hotKeys = cache.hotKeys(10);

        assertEquals(List.of(hot, warm), hotKeys);
    }

    @Test
    void truncatesToMax() {
        BlockCache cache = new BlockCache(16);
        BlockKey a = new BlockKey(0, InstructionSet.ARM);
        BlockKey b = new BlockKey(4, InstructionSet.ARM);
        cache.put(a, stubBlock(), true, 0, 4);
        cache.put(b, stubBlock(), true, 4, 8);
        cache.hit(a);
        cache.hit(b);

        assertEquals(1, cache.hotKeys(1).size());
        assertEquals(0, cache.hotKeys(0).size());
    }

    @Test
    void emptyCacheYieldsEmptyList() {
        BlockCache cache = new BlockCache(16);
        assertTrue(cache.hotKeys(10).isEmpty());
    }
}
