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
                    if (evicted.compiled()) {
                        BlockCache.this.generation++;
                    }
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

    /// Retorna a entrada (bloco + flag compiled + intervalo) ou `null`. Uso interno do runtime
    /// para distinguir o tier frio/quente numa única busca.
    CacheEntry entry(BlockKey key) {
        return cache.get(key);
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

    /// Armazena ou substitui um bloco COMPILADO com intervalo de endereço.
    public void put(BlockKey key, CompiledBlock block, int startPc, int endPc) {
        put(key, block, true, startPc, endPc);
    }

    /// Armazena ou substitui um bloco (compilado ou interpretado/frio) com intervalo de endereço.
    ///
    /// A geração só é incrementada quando um bloco COMPILADO anterior é descartado: apenas
    /// blocos compilados podem estar no inline cache do runtime, então promover um bloco frio
    /// (previous frio) para compilado NÃO precisa invalidar o IC — evitando thrash a cada
    /// promoção (crítico quando o threshold é 1, como no ndsemu).
    public void put(BlockKey key, CompiledBlock block, boolean compiled, int startPc, int endPc) {
        if (endPc <= startPc) {
            throw new IllegalArgumentException("endPc must be greater than startPc");
        }
        CacheEntry previous = cache.get(key);
        if (previous != null) {
            dropFromIndex(key, previous.startPc(), previous.endPc());
            if (previous.compiled()) {
                generation++;
            }
        }
        cache.put(key, new CacheEntry(block, compiled, startPc, endPc));
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
        boolean removedCompiled = false;
        for (Located hit : hits) {
            CacheEntry removed = cache.remove(hit.key());
            if (removed != null && removed.compiled()) {
                removedCompiled = true;
            }
            dropFromIndex(hit.key(), hit.startPc(), hit.endPc());
        }
        if (removedCompiled) {
            generation++;
        }
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

    /// Retorna as até `max` chaves de blocos COMPILADOS (tier quente) mais executados, em ordem
    /// decrescente de contagem de acertos ({@link #hit}) — task C10 (warm-start): o hospedeiro
    /// persiste essas chaves por ROM e as reagenda na próxima carga via {@link JitRuntime#precompile}.
    ///
    /// <p>Blocos ainda no tier frio (interpretado, nunca promovido) são excluídos: não há
    /// bytecode compilado para reaproveitar neles. Quando só existe "já passou do threshold"
    /// (contadores empatados, ex. {@code hotThreshold=1} do ndsemu), o desempate preserva a
    /// ordem de iteração do cache interno — suficiente por especificação.</p>
    public List<BlockKey> hotKeys(int max) {
        if (max <= 0) {
            return List.of();
        }
        List<Map.Entry<BlockKey, CacheEntry>> compiledEntries = new ArrayList<>();
        for (Map.Entry<BlockKey, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().compiled()) {
                compiledEntries.add(entry);
            }
        }
        compiledEntries.sort((a, b) -> Integer.compare(
                hitCounters.getOrDefault(b.getKey(), 0),
                hitCounters.getOrDefault(a.getKey(), 0)));
        int resultSize = Math.min(max, compiledEntries.size());
        List<BlockKey> result = new ArrayList<>(resultSize);
        for (int i = 0; i < resultSize; i++) {
            result.add(compiledEntries.get(i).getKey());
        }
        return result;
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

    /// Entrada do cache: o bloco, se já foi COMPILADO para bytecode (vs. interpretado/frio),
    /// e o intervalo de PC coberto. O flag `compiled` distingue os tiers e governa o bump de
    /// geração — só blocos compilados podem estar no inline cache do {@link JitRuntime}.
    record CacheEntry(CompiledBlock block, boolean compiled, int startPc, int endPc) {
    }

    private record Located(BlockKey key, int startPc, int endPc) {
        private boolean contains(int address) {
            return address >= startPc && address < endPc;
        }
    }
}
