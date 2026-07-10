package dev.vitorsilverio.armjitter.jit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// Profiler OPT-IN do encadeamento de blocos — a fase de MEDIÇÃO da task C0
/// (superblocos/trace-JIT): coleta os pares bloco→bloco percorridos pelo chain
/// fast path do [JitRuntime] para responder, com dados, se vale fundir sequências
/// encadeadas em um único método compilado (quais traces dominam, tamanho típico,
/// estabilidade de sucessor, motivo das quebras de corrente).
///
/// Instale com [JitRuntime#setChainProfiler]. Custo quando não instalado: um
/// null-check por salto. NÃO é thread-safe: um profiler por runtime, usado apenas
/// pela thread de emulação.
public final class ChainProfiler {
    /// Motivo pelo qual uma corrente de blocos parou.
    public enum BreakReason {
        /// Orçamento de ciclos do runtime esgotado (fim normal).
        BUDGET,
        /// Próximo PC não estava no inline cache (oportunidade perdida — ver `lostPairs`).
        IC_MISS,
        /// CPU dormiu (SWI Halt/WFI) dentro da corrente.
        SLEEP,
        /// Linha de interrupção pendente devolveu o controle ao runtime.
        INTERRUPT,
        /// Escrita automodificável esvaziou o inline cache.
        GENERATION,
        /// Bloco executou sem produzir ciclos internos (guarda de progresso).
        NO_PROGRESS
    }

    private static final int HISTOGRAM_MAX_HOPS = 64;
    /// Sucessor é "estável" (trace-compilável) quando domina esta fração das saídas do bloco.
    private static final double STABLE_SUCCESSOR_THRESHOLD = 0.80;
    private static final int GREEDY_TRACE_LIMIT = 32;

    private final Map<Long, long[]> pairCounts = new HashMap<>();
    private final Map<Long, long[]> lostPairs = new HashMap<>();
    private final long[] hopsHistogram = new long[HISTOGRAM_MAX_HOPS + 1];
    private final long[] breakCounts = new long[BreakReason.values().length];
    private long runs;
    private long hops;

    private static long pairKey(int fromPc, int toPc) {
        return ((long) fromPc << Integer.SIZE) | Integer.toUnsignedLong(toPc);
    }

    /// Um salto de corrente bem-sucedido do bloco em `fromPc` para o bloco em `toPc`.
    public void recordHop(int fromPc, int toPc) {
        pairCounts.computeIfAbsent(pairKey(fromPc, toPc), key -> new long[1])[0]++;
        hops++;
    }

    /// Corrente quebrada porque `toPc` não estava no inline cache (par perdido).
    public void recordIcMiss(int fromPc, int toPc) {
        lostPairs.computeIfAbsent(pairKey(fromPc, toPc), key -> new long[1])[0]++;
    }

    /// Fim de uma chamada de `execute`: `hopCount` saltos após o bloco inicial.
    public void recordRun(int hopCount, BreakReason reason) {
        runs++;
        hopsHistogram[Math.min(hopCount, HISTOGRAM_MAX_HOPS)]++;
        breakCounts[reason.ordinal()]++;
    }

    /// Total de saltos registrados (para asserções e sanidade).
    public long totalHops() {
        return hops;
    }

    /// Relatório em texto: totais, quebras, histograma, top pares (com estabilidade do
    /// sucessor), traces gulosos a partir dos blocos mais quentes e pares perdidos.
    public String report(int topPairs) {
        StringBuilder out = new StringBuilder();
        out.append("== ChainProfiler ==\n");
        out.append("runs=%d hops=%d media=%.2f hops/run%n"
                .formatted(runs, hops, runs == 0 ? 0.0 : (double) hops / runs));

        out.append("-- quebras de corrente --\n");
        for (BreakReason reason : BreakReason.values()) {
            long count = breakCounts[reason.ordinal()];
            if (count > 0) {
                out.append("%-12s %,d (%.1f%%)%n"
                        .formatted(reason, count, 100.0 * count / Math.max(1, runs)));
            }
        }

        out.append("-- histograma de hops/run (0..%d+) --%n".formatted(HISTOGRAM_MAX_HOPS));
        for (int i = 0; i <= HISTOGRAM_MAX_HOPS; i++) {
            if (hopsHistogram[i] > 0) {
                out.append("%3d%s: %,d%n".formatted(i, i == HISTOGRAM_MAX_HOPS ? "+" : " ", hopsHistogram[i]));
            }
        }

        // Total de saídas por bloco de origem, para calcular estabilidade do sucessor.
        Map<Integer, Long> outgoingTotals = new HashMap<>();
        pairCounts.forEach((key, count) ->
                outgoingTotals.merge((int) (key >> Integer.SIZE), count[0], Long::sum));

        out.append("-- top %d pares (from -> to  count  estabilidade) --%n".formatted(topPairs));
        List<Map.Entry<Long, long[]>> sorted = new ArrayList<>(pairCounts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (int i = 0; i < Math.min(topPairs, sorted.size()); i++) {
            long key = sorted.get(i).getKey();
            int from = (int) (key >> Integer.SIZE);
            int to = (int) key;
            long count = sorted.get(i).getValue()[0];
            double stability = (double) count / outgoingTotals.get(from);
            out.append("%08X -> %08X  %,12d  %5.1f%%%n".formatted(from, to, count, 100.0 * stability));
        }

        out.append("-- traces gulosos (a partir dos 10 blocos mais quentes; sucessor >= %.0f%%) --%n"
                .formatted(100.0 * STABLE_SUCCESSOR_THRESHOLD));
        List<Map.Entry<Integer, Long>> heads = new ArrayList<>(outgoingTotals.entrySet());
        heads.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(10, heads.size()); i++) {
            appendGreedyTrace(out, heads.get(i).getKey(), outgoingTotals);
        }

        if (!lostPairs.isEmpty()) {
            out.append("-- top pares perdidos por IC miss --\n");
            List<Map.Entry<Long, long[]>> lost = new ArrayList<>(lostPairs.entrySet());
            lost.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (int i = 0; i < Math.min(10, lost.size()); i++) {
                long key = lost.get(i).getKey();
                out.append("%08X -> %08X  %,d%n"
                        .formatted((int) (key >> Integer.SIZE), (int) key, lost.get(i).getValue()[0]));
            }
        }
        return out.toString();
    }

    /// Segue o sucessor mais frequente enquanto for estável; um trace que volta ao
    /// início é um LOOP (o caso ideal de superbloco).
    private void appendGreedyTrace(StringBuilder out, int head, Map<Integer, Long> outgoingTotals) {
        LinkedHashSet<Integer> trace = new LinkedHashSet<>();
        trace.add(head);
        int current = head;
        String terminator = "fim (sucessor instável)";
        while (trace.size() < GREEDY_TRACE_LIMIT) {
            int best = 0;
            long bestCount = 0;
            for (Map.Entry<Long, long[]> entry : pairCounts.entrySet()) {
                if ((int) (entry.getKey() >> Integer.SIZE) == current && entry.getValue()[0] > bestCount) {
                    bestCount = entry.getValue()[0];
                    best = (int) (long) entry.getKey();
                }
            }
            Long total = outgoingTotals.get(current);
            if (total == null || bestCount < total * STABLE_SUCCESSOR_THRESHOLD) {
                break;
            }
            if (!trace.add(best)) {
                terminator = best == head ? "LOOP fechado" : "loop interno em %08X".formatted(best);
                break;
            }
            current = best;
        }
        out.append("trace[%d blocos, %s]: ".formatted(trace.size(), terminator));
        out.append(String.join(" -> ", trace.stream().map("%08X"::formatted).toList()));
        out.append('\n');
    }
}
