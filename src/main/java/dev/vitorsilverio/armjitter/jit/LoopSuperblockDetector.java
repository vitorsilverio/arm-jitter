package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.decoder.InstructionSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Detector de LOOPS FECHADOS de blocos no chain fast path (task C0.2 — fase de
/// detecção do loop-superbloco; a spec é `tasks/trilha-c-perf/c0-impl-loop-superbloco.md`).
///
/// Amostra 1 a cada [#SAMPLE_MASK]+1 runs de corrente e observa os primeiros
/// [#MAX_CYCLE_BLOCKS] saltos: se o PC voltar ao head, o ciclo `[head, hop1..hopK-1]`
/// é um candidato. Um head vira candidato PROMOVIDO após [#CONFIRMATIONS_REQUIRED]
/// confirmações com o MESMO ciclo (uma amostra com ciclo DIFERENTE zera a contagem;
/// amostras sem ciclo apenas não contam). Restrição desta fase: todos os membros no
/// MESMO [InstructionSet] (os loops medidos são ARM; simplifica o dispatch do C0.3).
///
/// NÃO é thread-safe: um por runtime, usado só pela thread de emulação. Custo fora
/// da amostra: um incremento + teste de máscara por run.
public final class LoopSuperblockDetector {
    /// Amostra 1 run a cada 64 (o run 0 é amostrado — determinístico para testes).
    private static final int SAMPLE_MASK = 63;
    /// Tamanho máximo do ciclo, em blocos (dado da medição: os dominantes têm 2–4).
    private static final int MAX_CYCLE_BLOCKS = 4;
    /// Confirmações consecutivas com o mesmo ciclo para promover o candidato.
    private static final int CONFIRMATIONS_REQUIRED = 8;
    /// Limite de candidatos promovidos retidos (bound de memória).
    private static final int MAX_PROMOTED = 64;

    /// Candidato promovido: head + PCs dos membros na ordem do ciclo.
    public record Candidate(int headPc, InstructionSet instructionSet, int[] memberPcs) {
        public Candidate {
            memberPcs = Arrays.copyOf(memberPcs, memberPcs.length);
        }
    }

    private final Map<Integer, PendingCycle> pending = new HashMap<>();
    private final List<Candidate> promoted = new ArrayList<>();

    private long runCounter;
    private long sampledRuns;

    // Estado da amostra em curso (zerado a cada startRun amostrado).
    private boolean sampling;
    private int headPc;
    private InstructionSet headSet;
    private final int[] hops = new int[MAX_CYCLE_BLOCKS];
    private int hopCount;

    private static final class PendingCycle {
        int[] members;
        int confirmations;
    }

    /// Início de um run de corrente (IC hit). Retorna `true` se este run será amostrado.
    public boolean startRun(int pc, InstructionSet instructionSet) {
        if ((runCounter++ & SAMPLE_MASK) != 0) {
            sampling = false;
            return false;
        }
        sampledRuns++;
        sampling = true;
        headPc = pc;
        headSet = instructionSet;
        hopCount = 0;
        return true;
    }

    /// Um salto encadeado do run amostrado. Retorna `true` quando a amostra terminou
    /// (ciclo achado ou descartada) — o chamador para de observar.
    public boolean observeHop(int nextPc, InstructionSet nextSet) {
        if (!sampling) {
            return true;
        }
        if (nextSet != headSet) {
            sampling = false; // ciclo misto ARM/THUMB: fora do escopo desta fase
            return true;
        }
        if (nextPc == headPc) {
            confirm(Arrays.copyOf(hops, hopCount));
            sampling = false;
            return true;
        }
        if (hopCount == MAX_CYCLE_BLOCKS - 1) {
            sampling = false; // ciclo maior que o limite (ou não é ciclo)
            return true;
        }
        hops[hopCount++] = nextPc;
        return false;
    }

    private void confirm(int[] tail) {
        if (promoted.size() >= MAX_PROMOTED || isPromoted(headPc)) {
            return;
        }
        int[] members = new int[tail.length + 1];
        members[0] = headPc;
        System.arraycopy(tail, 0, members, 1, tail.length);
        PendingCycle cycle = pending.computeIfAbsent(headPc, key -> new PendingCycle());
        if (!Arrays.equals(cycle.members, members)) {
            cycle.members = members;
            cycle.confirmations = 1;
            return;
        }
        if (++cycle.confirmations >= CONFIRMATIONS_REQUIRED) {
            promoted.add(new Candidate(headPc, headSet, members));
            pending.remove(headPc);
        }
    }

    private boolean isPromoted(int pc) {
        for (Candidate candidate : promoted) {
            if (candidate.headPc() == pc) {
                return true;
            }
        }
        return false;
    }

    /// Candidatos promovidos até agora (cópia defensiva barata — lista pequena).
    public List<Candidate> candidates() {
        return List.copyOf(promoted);
    }

    /// Resumo em texto para diagnóstico (ndsemu `superblocks`).
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("== LoopSuperblockDetector ==\n");
        out.append("runs=%d amostrados=%d candidatos=%d%n"
                .formatted(runCounter, sampledRuns, promoted.size()));
        for (Candidate candidate : promoted) {
            out.append("loop %s [%d blocos]: %s%n".formatted(
                    candidate.instructionSet(),
                    candidate.memberPcs().length,
                    String.join(" -> ", Arrays.stream(candidate.memberPcs())
                            .mapToObj("%08X"::formatted).toList())));
        }
        return out.toString();
    }
}
