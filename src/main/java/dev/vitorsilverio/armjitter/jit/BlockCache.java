package dev.vitorsilverio.armjitter.jit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Cache LRU de blocos compilados indexados por PC e conjunto de instrucoes.
public final class BlockCache {
    private final int maxEntries;
    private final LinkedHashMap<BlockKey, CacheEntry> cache;
    private final Map<BlockKey, Integer> hitCounters = new LinkedHashMap<>();

    /// Cria um cache com limite maximo de entradas.
    public BlockCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BlockKey, CacheEntry> eldest) {
                return size() > BlockCache.this.maxEntries;
            }
        };
    }

    /// Busca um bloco ARM compilado por PC.
    public Optional<CompiledBlock> get(int pc) {
        return get(new BlockKey(pc, InstructionSet.ARM));
    }

    /// Busca um bloco compilado por chave.
    public Optional<CompiledBlock> get(BlockKey key) {
        CacheEntry entry = cache.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.block());
    }

    /// Armazena ou substitui um bloco ARM compilado por PC.
    public void put(int pc, CompiledBlock block) {
        put(new BlockKey(pc, InstructionSet.ARM), block);
    }

    /// Armazena ou substitui um bloco compilado por chave.
    public void put(BlockKey key, CompiledBlock block) {
        put(key, block, key.pc(), key.pc() + instructionWidth(key.instructionSet()));
    }

    /// Armazena ou substitui um bloco compilado com intervalo de endereco.
    public void put(BlockKey key, CompiledBlock block, int startPc, int endPc) {
        if (endPc <= startPc) {
            throw new IllegalArgumentException("endPc must be greater than startPc");
        }
        cache.put(key, new CacheEntry(block, startPc, endPc));
    }

    /// Incrementa e retorna o contador ARM de execucoes de um PC.
    public int hit(int pc) {
        return hit(new BlockKey(pc, InstructionSet.ARM));
    }

    /// Incrementa e retorna o contador de execucoes de uma chave.
    public int hit(BlockKey key) {
        int hits = hitCounters.getOrDefault(key, 0) + 1;
        hitCounters.put(key, hits);
        return hits;
    }

    /// Remove blocos compilados associados ao PC informado em qualquer modo.
    public void invalidate(int address) {
        cache.entrySet().removeIf(entry -> entry.getValue().contains(address));
    }

    /// Remove todos os blocos compilados.
    public void clear() {
        cache.clear();
    }

    /// Retorna a quantidade atual de blocos compilados em cache.
    public int size() {
        return cache.size();
    }

    private int instructionWidth(InstructionSet instructionSet) {
        return switch (instructionSet) {
            case ARM -> 4;
            case THUMB -> 2;
        };
    }

    private record CacheEntry(CompiledBlock block, int startPc, int endPc) {
        private boolean contains(int address) {
            return address >= startPc && address < endPc;
        }
    }
}
