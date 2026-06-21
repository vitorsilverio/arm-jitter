package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Trava o contrato de {@link BlockCache#generation()} do qual o inline cache de dispatch
/// do {@link JitRuntime} depende: toda remoção/substituição estrutural incrementa a geração,
/// e operações que não descartam nenhum bloco a deixam intacta (mantendo o IC quente).
class BlockCacheGenerationTest {
    @Test
    void freshPutOfNewKeyDoesNotBumpGeneration() {
        BlockCache cache = new BlockCache(4);
        long before = cache.generation();
        cache.put(new BlockKey(0, InstructionSet.ARM), core -> 1, 0, 4);
        assertEquals(before, cache.generation());
    }

    @Test
    void replacingExistingKeyBumpsGeneration() {
        BlockCache cache = new BlockCache(4);
        BlockKey key = new BlockKey(0, InstructionSet.ARM);
        cache.put(key, core -> 1, 0, 4);
        long before = cache.generation();
        cache.put(key, core -> 2, 0, 4); // mesma chave recompilada
        assertTrue(cache.generation() > before);
    }

    @Test
    void invalidatingACachedBlockBumpsGeneration() {
        BlockCache cache = new BlockCache(4);
        cache.put(new BlockKey(0, InstructionSet.ARM), core -> 1, 0, 8);
        long before = cache.generation();
        cache.invalidate(4); // escrita dentro do range compilado
        assertTrue(cache.generation() > before);
    }

    @Test
    void noOpInvalidateLeavesGenerationUnchanged() {
        BlockCache cache = new BlockCache(4);
        cache.put(new BlockKey(0, InstructionSet.ARM), core -> 1, 0, 8);
        long before = cache.generation();
        cache.invalidate(0x06000000); // página distante, sem código
        assertEquals(before, cache.generation());
        cache.invalidate(8); // logo após o range do bloco
        assertEquals(before, cache.generation());
    }

    @Test
    void evictionBumpsGeneration() {
        BlockCache cache = new BlockCache(1);
        cache.put(new BlockKey(0x1000, InstructionSet.ARM), core -> 1, 0x1000, 0x1008);
        long before = cache.generation();
        cache.put(new BlockKey(0x5000, InstructionSet.ARM), core -> 2, 0x5000, 0x5008); // evicta o 1º
        assertTrue(cache.generation() > before);
    }

    @Test
    void clearBumpsGeneration() {
        BlockCache cache = new BlockCache(4);
        cache.put(new BlockKey(0, InstructionSet.ARM), core -> 1, 0, 4);
        long before = cache.generation();
        cache.clear();
        assertTrue(cache.generation() > before);
    }
}
