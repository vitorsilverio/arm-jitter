package dev.vitorsilverio.armjitter.jit64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Cache de blocos A64 compilados, indexados por {@link BlockKey64} — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.jit.BlockCache} (32 bits), introduzido na task B6.4, mas
/// deliberadamente MAIS SIMPLES (ver `b6.4-aarch64-asm-backend.md`, decisão D5): **sem
/// invalidação por escrita/página** (nenhum host A64 atual sofre código automodificável — armbox
/// roda ELF64 estáticos síncronos) e **sem geração** (não há inline cache do lado do
/// {@code JitRuntime64} para revalidar contra, ver D0). Pendência explícita para quando um host
/// A64 real precisar de invalidação — não replicar essa engenharia às cegas sem um consumidor e
/// um teste de regressão reais para validá-la.
public final class BlockCache64 {
    private final Map<BlockKey64, CompiledBlock64> blocks = new HashMap<>();
    private final Map<BlockKey64, Integer> hitCounters = new HashMap<>();
    /// Blocos compilados por página FÍSICA que o código deles cobre — o índice da invalidação do
    /// cache de instruções (`IC IVAU`). Só blocos inseridos com páginas informadas entram aqui.
    private final Map<Long, Set<BlockKey64>> blocksByPhysicalPage = new HashMap<>();
    private final Map<BlockKey64, long[]> physicalPagesByBlock = new HashMap<>();

    /// Busca um bloco compilado por chave, ou `null` se ausente.
    public CompiledBlock64 getOrNull(BlockKey64 key) {
        return blocks.get(key);
    }

    /// Armazena ou substitui um bloco compilado por chave.
    public void put(BlockKey64 key, CompiledBlock64 block) {
        blocks.put(key, block);
    }

    /// Incrementa e retorna o contador de execuções de uma chave (usado por
    /// {@link dev.vitorsilverio.armjitter.jit.ExecutionThreshold} para decidir quando compilar).
    /// Insere o bloco e o registra nas páginas físicas {@code physicalPages} que o código dele
    /// cobre, para que {@link #invalidatePhysicalPage} o encontre. Substituir uma chave que já
    /// existia desfaz o registro antigo.
    public void put(BlockKey64 key, CompiledBlock64 block, long[] physicalPages) {
        forget(key);
        blocks.put(key, block);
        physicalPagesByBlock.put(key, physicalPages);
        for (long page : physicalPages) {
            blocksByPhysicalPage.computeIfAbsent(page, ignored -> new HashSet<>()).add(key);
        }
    }

    /// Descarta todo bloco cujo código toca a página física {@code physicalPage}
    /// (`endereço físico >>> 12`). O contador de execuções fica: o bloco recompila assim que for
    /// executado de novo, já com o código novo.
    ///
    /// @return quantos blocos foram descartados
    public int invalidatePhysicalPage(long physicalPage) {
        Set<BlockKey64> keys = blocksByPhysicalPage.get(physicalPage);
        if (keys == null) {
            return 0;
        }
        List<BlockKey64> doomed = new ArrayList<>(keys);
        for (BlockKey64 key : doomed) {
            forget(key);
            blocks.remove(key);
        }
        return doomed.size();
    }

    private void forget(BlockKey64 key) {
        long[] pages = physicalPagesByBlock.remove(key);
        if (pages == null) {
            return;
        }
        for (long page : pages) {
            Set<BlockKey64> keys = blocksByPhysicalPage.get(page);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) {
                    blocksByPhysicalPage.remove(page);
                }
            }
        }
    }

    public int hit(BlockKey64 key) {
        int hits = hitCounters.getOrDefault(key, 0) + 1;
        hitCounters.put(key, hits);
        return hits;
    }

    /// Quantidade atual de blocos compilados em cache.
    public int size() {
        return blocks.size();
    }

    /// Remove todos os blocos compilados e zera os contadores de execução.
    public void clear() {
        blocks.clear();
        hitCounters.clear();
        blocksByPhysicalPage.clear();
        physicalPagesByBlock.clear();
    }
}
