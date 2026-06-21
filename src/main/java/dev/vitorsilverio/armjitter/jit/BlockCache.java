package dev.vitorsilverio.armjitter.jit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Cache LRU de blocos compilados indexados por PC e conjunto de instruções.
///
/// Para que a invalidação por escrita (código automodificável) não percorra todo
/// o cache a cada escrita da CPU, os blocos são indexados por página de memória:
/// um bitset sem alocação filtra a esmagadora maioria das escritas (regiões de
/// dados que nunca contêm código) em O(1), e um índice página->blocos limita a
/// remoção real aos poucos blocos que tocam aquela página.
public final class BlockCache {
    /// Tamanho da página de indexação (1 KiB). Um bloco compilado (<= 64 instruções
    /// ARM = 256 bytes) cruza no máximo duas páginas.
    private static final int PAGE_BITS = 10;
    /// Número de páginas que cobrem o espaço de 32 bits (2^22).
    private static final int PAGE_COUNT = 1 << (32 - PAGE_BITS);

    private final int maxEntries;
    private final LinkedHashMap<BlockKey, CacheEntry> cache;
    private final Map<BlockKey, Integer> hitCounters = new LinkedHashMap<>();
    /// Contador monotônico incrementado a cada remoção/substituição estrutural de bloco
    /// (invalidação por SMC, evicção LRU, sobrescrita de chave, {@link #clear()}).
    ///
    /// Permite que caches front-side (p.ex. o inline cache do {@link JitRuntime}) detectem
    /// em O(1) que algum {@link CompiledBlock} pode ter sido descartado e revalidem suas
    /// entradas — garantindo que um bloco obsoleto (código automodificável) nunca execute.
    private long generation;
    /// Índice página -> blocos cujo intervalo [startPc, endPc) intersecta a página.
    private final Map<Integer, List<Located>> blocksByPage = new HashMap<>();
    /// Bitset (sem boxing) marcando páginas que já receberam algum bloco. Serve de
    /// porta rápida na invalidação; é definido ao indexar e nunca limpo (uma página
    /// marcada que ficou sem blocos apenas cai no caminho do mapa, que retorna vazio).
    private final long[] pageOccupied = new long[PAGE_COUNT >>> 6];

    /// Cria um cache com limite máximo de entradas.
    public BlockCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BlockKey, CacheEntry> eldest) {
                if (size() > BlockCache.this.maxEntries) {
                    CacheEntry evicted = eldest.getValue();
                    dropFromIndex(eldest.getKey(), evicted.startPc(), evicted.endPc());
                    BlockCache.this.generation++;
                    return true;
                }
                return false;
            }
        };
    }

    /// Busca um bloco ARM compilado por PC.
    public Optional<CompiledBlock> get(int pc) {
        return get(new BlockKey(pc, InstructionSet.ARM));
    }

    /// Busca um bloco compilado por chave.
    public Optional<CompiledBlock> get(BlockKey key) {
        return Optional.ofNullable(getOrNull(key));
    }

    /// Busca um bloco compilado por chave retornando `null` se ausente.
    ///
    /// Variante sem alocação de {@link Optional} para o caminho quente do runtime.
    public CompiledBlock getOrNull(BlockKey key) {
        CacheEntry entry = cache.get(key);
        return entry == null ? null : entry.block();
    }

    /// Retorna a geração atual do cache (ver {@link #generation}).
    public long generation() {
        return generation;
    }

    /// Armazena ou substitui um bloco ARM compilado por PC.
    public void put(int pc, CompiledBlock block) {
        put(new BlockKey(pc, InstructionSet.ARM), block);
    }

    /// Armazena ou substitui um bloco compilado por chave.
    public void put(BlockKey key, CompiledBlock block) {
        put(key, block, key.pc(), key.pc() + instructionWidth(key.instructionSet()));
    }

    /// Armazena ou substitui um bloco compilado com intervalo de endereço.
    public void put(BlockKey key, CompiledBlock block, int startPc, int endPc) {
        if (endPc <= startPc) {
            throw new IllegalArgumentException("endPc must be greater than startPc");
        }
        CacheEntry previous = cache.get(key);
        if (previous != null) {
            dropFromIndex(key, previous.startPc(), previous.endPc());
            generation++;
        }
        cache.put(key, new CacheEntry(block, startPc, endPc));
        indexBlock(key, startPc, endPc);
    }

    /// Incrementa e retorna o contador ARM de execuções de um PC.
    public int hit(int pc) {
        return hit(new BlockKey(pc, InstructionSet.ARM));
    }

    /// Incrementa e retorna o contador de execuções de uma chave.
    public int hit(BlockKey key) {
        int hits = hitCounters.getOrDefault(key, 0) + 1;
        hitCounters.put(key, hits);
        return hits;
    }

    /// Remove blocos compilados afetados por uma escrita no endereço informado.
    ///
    /// Caminho comum (escrita em região sem código): apenas testa um bit -> O(1).
    /// Caminho raro (página com código): percorre somente os blocos daquela página.
    public void invalidate(int address) {
        int page = pageIndex(address);
        if (!isPageOccupied(page)) {
            return;
        }
        List<Located> located = blocksByPage.get(page);
        if (located == null || located.isEmpty()) {
            return;
        }
        List<Located> hits = null;
        for (Located candidate : located) {
            if (candidate.contains(address)) {
                if (hits == null) {
                    hits = new ArrayList<>();
                }
                hits.add(candidate);
            }
        }
        if (hits == null) {
            return;
        }
        for (Located hit : hits) {
            cache.remove(hit.key());
            dropFromIndex(hit.key(), hit.startPc(), hit.endPc());
        }
        generation++;
    }

    /// Remove todos os blocos compilados.
    public void clear() {
        cache.clear();
        blocksByPage.clear();
        generation++;
    }

    /// Retorna a quantidade atual de blocos compilados em cache.
    public int size() {
        return cache.size();
    }

    private void indexBlock(BlockKey key, int startPc, int endPc) {
        int firstPage = pageIndex(startPc);
        int lastPage = pageIndex(endPc - 1);
        for (int page = firstPage; page <= lastPage; page++) {
            markPageOccupied(page);
            blocksByPage.computeIfAbsent(page, p -> new ArrayList<>())
                    .add(new Located(key, startPc, endPc));
        }
    }

    private void dropFromIndex(BlockKey key, int startPc, int endPc) {
        int firstPage = pageIndex(startPc);
        int lastPage = pageIndex(endPc - 1);
        for (int page = firstPage; page <= lastPage; page++) {
            List<Located> located = blocksByPage.get(page);
            if (located != null) {
                located.removeIf(entry -> entry.key().equals(key));
                if (located.isEmpty()) {
                    blocksByPage.remove(page);
                }
            }
        }
    }

    private static int pageIndex(int address) {
        return address >>> PAGE_BITS;
    }

    private void markPageOccupied(int page) {
        pageOccupied[page >>> 6] |= 1L << (page & 63);
    }

    private boolean isPageOccupied(int page) {
        return (pageOccupied[page >>> 6] & (1L << (page & 63))) != 0;
    }

    private int instructionWidth(InstructionSet instructionSet) {
        return switch (instructionSet) {
            case ARM -> 4;
            case THUMB -> 2;
        };
    }

    private record CacheEntry(CompiledBlock block, int startPc, int endPc) {
    }

    private record Located(BlockKey key, int startPc, int endPc) {
        private boolean contains(int address) {
            return address >= startPc && address < endPc;
        }
    }
}
