package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrBuilder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrBlockLifter;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// Orquestra cache, decodificação, IR, otimização e codegen.
///
/// O nome histórico *JIT* refere-se ao pipeline de blocos cacheados; o backend efetivo
/// depende do {@link dev.vitorsilverio.armjitter.codegen.CodeEmitter} configurado.
/// O factory recomendado ({@link JitRuntimeFactory#armThumb}) usa
/// {@link dev.vitorsilverio.armjitter.codegen.CodegenBackend#JVM_BYTECODE} com
/// o otimizador GBA ativo desde a Fase 8. Os factories {@code interpreted*} mantêm
/// {@link dev.vitorsilverio.armjitter.codegen.CodegenBackend#INTERPRETED_IR} como
/// debug/oráculo.
public final class JitRuntime {
    private final BlockCache blockCache;
    private final InstructionDecoder armDecoder;
    private final InstructionDecoder thumbDecoder;
    private final IrBuilder irBuilder;
    private final IrOptimizer optimizer;
    private final CodeEmitter emitter;
    /// Emissor do tier FRIO: quando presente (≠ null), blocos novos rodam interpretados
    /// (closure cacheada, sem classloading) e só os QUENTES (≥ threshold) compilam via
    /// {@link #emitter}. Quando null, mantém o caminho antigo (single-step frio).
    private final CodeEmitter coldEmitter;
    private final ExecutionThreshold threshold;
    private final int maxBlockInstructions;
    /// Compilação assíncrona (apenas no modo tiered): o `emit` do tier quente (gerar bytecode
    /// + classload, a parte que trava) roda numa thread daemon de background sobre o `IrBlock`
    /// imutável, enquanto a emulação segue interpretando; o resultado é integrado depois.
    private final java.util.concurrent.ExecutorService compileExecutor;
    /// Fila de resultados de compilação prontos (background → emulação).
    private final java.util.Queue<CompileResult> compiled = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /// Closures frias com compilação em voo (evita re-submeter; só a thread de emulação acessa).
    private final java.util.Set<CompiledBlock> compilingColdBlocks =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /// Resultado de uma compilação de background, com a closure fria de origem para checar
    /// staleness (se o bloco foi invalidado/re-liftado por SMC, descarta).
    private record CompileResult(BlockKey key, CompiledBlock coldBlock, CompiledBlock compiledBlock,
                                 int startPc, int endPc) {
    }

    // ── Inline cache de dispatch ────────────────────────────────────────────────
    // Cache front-side direct-mapped sobre o {@link BlockCache}: evita, no caminho
    // quente, a alocação de {@link BlockKey}, o lookup no HashMap e o {@link java.util.Optional}.
    // Cada slot guarda uma tag (pc + conjunto de instruções) e o bloco compilado.
    //
    // Correção (código automodificável): o IC é validado em bloco contra
    // {@link BlockCache#generation()}. Qualquer remoção estrutural no cache principal
    // (SMC, evicção LRU, sobrescrita, clear) incrementa a geração; ao detectar a
    // divergência o IC é esvaziado preguiçosamente, então um bloco obsoleto nunca executa.
    /// Número de entradas do inline cache (potência de 2). 32K slots: com 4K o working set de um
    /// jogo comercial (MKDS) sofria conflitos direct-mapped — o caminho de miss (executeTiered:
    /// lookup no HashMap + alocação de BlockKey) chegou a 22% do tempo de CPU no profile.
    private static final int IC_SIZE = 1 << 15;
    /// Máscara de indexação do inline cache.
    private static final int IC_MASK = IC_SIZE - 1;
    /// Tag sentinela para slot vazio (nenhuma tag válida é negativa).
    private static final long IC_EMPTY = -1L;
    /// Orçamento de ciclos internos do encadeamento de blocos (ver `execute`): a corrente para de
    /// seguir o PC ao atingir isto. 0 = encadeamento desligado (default seguro). Configurável por
    /// runtime ({@link #setChainCycleBudget}): handshakes de boot cross-CPU (NitroSDK IPC-sync,
    /// HLE de boot do host) dependem de o scheduler do emulador rodar ENTRE blocos, então o host
    /// decide onde encadear é seguro (ex.: só na CPU principal, ou com orçamento pequeno).
    private int chainCycleBudget;
    /// Tags `(pc & 0xFFFFFFFF) | (instructionSet.ordinal() << 32)` por slot.
    private final long[] icTags = new long[IC_SIZE];
    /// Blocos compilados por slot, alinhados a {@link #icTags}.
    private final CompiledBlock[] icBlocks = new CompiledBlock[IC_SIZE];
    /// Geração do {@link BlockCache} para a qual o IC é válido. Inicia em -1
    /// (sentinela impossível) para forçar o flush/preenchimento no primeiro `execute`.
    private long icGeneration = -1L;

    /// Diagnóstico (leitura externa; incrementos não-atômicos no caminho quente): acertos e
    /// faltas do inline cache e flushes por mudança de geração — para dimensionar IC/hash e
    /// detectar thrash por invalidação (SMC/DMA).
    public long icHits;
    public long icMisses;
    public long icFlushes;
    /// Blocos executados por encadeamento (sem round-trip pelo scheduler).
    public long chainedBlocks;

    /// Liga os contadores de reaquecimento pós-restore (task C11, fase 1). Custo zero quando
    /// desligado (guard único por chamada) — os contadores somem do caminho quente antes do PR
    /// final da trilha; ver Armadilha do spec.
    private boolean warmupDiagnostics;
    /// Execuções no tier FRIO (interpretado, ou single-step sem tiering) desde o último reset.
    public long coldTierExecutions;
    /// Execuções no tier QUENTE (bloco compilado, via IC hit ou lookup direto).
    public long hotTierExecutions;
    /// Blocos promovidos frio→quente (integrados de `integrateCompiled`).
    public long blocksCompiledTotal;

    /// Liga/desliga os contadores de {@link #coldTierExecutions}/{@link #hotTierExecutions}/
    /// {@link #blocksCompiledTotal} (task C11, fase 1 — medição de reaquecimento pós-restore).
    public void setWarmupDiagnostics(boolean enabled) {
        this.warmupDiagnostics = enabled;
    }

    /// Blocos com compilação em voo agora (submetidos ao pool, ainda não integrados) — proxy
    /// barato da profundidade da fila de compilação (task C11, fase 1).
    public int compilingBlockCount() {
        return compilingColdBlocks.size();
    }

    /// Define o orçamento de ciclos do encadeamento de blocos (0 desliga). Ver o comentário de
    /// {@link #chainCycleBudget} para as restrições de quando encadear é seguro.
    public void setChainCycleBudget(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("chain cycle budget must be >= 0");
        }
        this.chainCycleBudget = cycles;
    }

    /// Orçamento de ciclos atual do encadeamento (0 = desligado) — para hospedeiros que trocam
    /// o budget em fases (ex. boot vs pós-boot) e para testes.
    public int chainCycleBudget() {
        return chainCycleBudget;
    }

    /// Profiler opt-in do encadeamento (medição da task C0 — superblocos). `null` = desligado
    /// (custo: um null-check por salto de corrente). Não thread-safe: um por runtime.
    private ChainProfiler chainProfiler;

    /// Instala (ou remove, com `null`) o [ChainProfiler] deste runtime.
    public void setChainProfiler(ChainProfiler profiler) {
        this.chainProfiler = profiler;
    }

    /// Detector opt-in de loops fechados para o loop-superbloco (tasks C0.2/C0.3). `null` =
    /// desligado (custo: um null-check por run/salto). Com o flag ligado, candidatos
    /// promovidos são CONSTRUÍDOS como loop-superblocos quando o emissor suporta
    /// composição (backend ASM); em emissores sem suporte fica só a detecção.
    private LoopSuperblockDetector superblockDetector;
    /// Espelho de `superblockDetector != null` para o guard barato do chain loop.
    private boolean superblocksEnabled;
    /// Adaptador dos guards por-iteração dos superblocos (budget + generation).
    private dev.vitorsilverio.armjitter.codegen.jvm.SuperblockContext superblockContext;
    /// Heads canônicos já tentados (construídos OU recusados) — uma tentativa por ciclo.
    private final java.util.HashSet<Integer> superblockHeads = new java.util.HashSet<>();
    /// Envoltória máxima de endereços de um superbloco (invalidação por página marca o
    /// intervalo inteiro; os loops medidos têm 8–10 KB).
    private static final int MAX_SUPERBLOCK_SPAN_BYTES = 32 * 1024;

    /// Diagnóstico: superblocos construídos e candidatos recusados (span/emissor/membros frios).
    public long superblocksBuilt;
    public long superblockSkips;

    /// Liga/desliga a detecção+construção de loop-superbloco (default OFF).
    public void setLoopSuperblocks(boolean enabled) {
        this.superblockDetector = enabled ? new LoopSuperblockDetector() : null;
        this.superblocksEnabled = enabled;
        if (enabled && superblockContext == null) {
            superblockContext = new dev.vitorsilverio.armjitter.codegen.jvm.SuperblockContext() {
                @Override public long generation() { return blockCache.generation(); }
                @Override public int chainCycleBudget() { return chainCycleBudget; }
            };
        }
    }

    /// Detector instalado, ou `null` — para relatórios e testes.
    public LoopSuperblockDetector superblockDetector() {
        return superblockDetector;
    }

    /// Constrói e instala o superbloco do ciclo `memberPcs` (rotacionado para o head
    /// canônico = menor PC unsigned). Uma tentativa por ciclo; requer todos os membros
    /// compilados no cache e emissor com suporte a composição. Roda na thread de emulação
    /// (evento raro e classe minúscula — build síncrono deliberado; ver spec C0.3).
    private void buildSuperblock(InstructionSet instructionSet, int[] memberPcs) {
        int rotation = 0;
        for (int i = 1; i < memberPcs.length; i++) {
            if (Integer.compareUnsigned(memberPcs[i], memberPcs[rotation]) < 0) {
                rotation = i;
            }
        }
        int[] canonical = new int[memberPcs.length];
        for (int i = 0; i < memberPcs.length; i++) {
            canonical[i] = memberPcs[(rotation + i) % memberPcs.length];
        }
        if (!superblockHeads.add(canonical[0])) {
            return; // ciclo já tentado (inclui as outras rotações promovidas pelo detector)
        }
        java.util.List<CompiledBlock> members = new java.util.ArrayList<>(canonical.length);
        int coveringStart = canonical[0];
        int coveringEnd = canonical[0];
        for (int memberPc : canonical) {
            BlockCache.CacheEntry entry = blockCache.entry(new BlockKey(memberPc, instructionSet));
            if (entry == null || !entry.compiled()) {
                superblockSkips++;
                return; // membro frio/ausente: o ciclo continua atendido pelo chaining normal
            }
            members.add(entry.block());
            if (Integer.compareUnsigned(entry.startPc(), coveringStart) < 0) {
                coveringStart = entry.startPc();
            }
            if (Integer.compareUnsigned(entry.endPc(), coveringEnd) > 0) {
                coveringEnd = entry.endPc();
            }
        }
        if (Integer.compareUnsigned(coveringEnd - coveringStart, MAX_SUPERBLOCK_SPAN_BYTES) > 0) {
            superblockSkips++;
            return;
        }
        CompiledBlock superblock = emitter.emitLoopSuperblock(members, canonical, superblockContext);
        if (superblock == null) {
            superblockSkips++;
            return; // emissor sem composição (interpretado) ou membro não-ASM
        }
        blockCache.put(new BlockKey(canonical[0], instructionSet), superblock, coveringStart, coveringEnd);
        superblocksBuilt++;
    }

    /// Gancho de teste (C0.3): constrói o superbloco AGORA, sem esperar o detector.
    boolean buildSuperblockNow(InstructionSet instructionSet, int... memberPcs) {
        if (superblockContext == null) {
            setLoopSuperblocks(true);
        }
        long before = superblocksBuilt;
        buildSuperblock(instructionSet, memberPcs);
        return superblocksBuilt > before;
    }

    /// `true` se a entrada de `pc` no cache é um loop-superbloco (asserções de teste).
    boolean superblockInstalled(int pc, InstructionSet instructionSet) {
        BlockCache.CacheEntry entry = blockCache.entry(new BlockKey(pc, instructionSet));
        return entry != null && entry.block() instanceof LoopSuperblock;
    }

    /// Reset completo pós-restore de save state: limpa o {@link BlockCache} (equivalente a
    /// `blockCache().clear()`) E TAMBÉM todo o estado de detecção/construção de
    /// loop-superbloco (task C0.2/C0.3) — {@link #superblockHeads} e o
    /// [LoopSuperblockDetector] instalado, se houver.
    ///
    /// Sem isto (achado da task C11, fase 1): `blockCache().clear()` sozinho deixa o
    /// working set frio, mas um head de loop já PROMOVIDO ou já TENTADO antes do save
    /// nunca é reavaliado depois — `superblockHeads` só recebe `add`, nunca `clear`, e o
    /// detector nunca esquece um candidato já promovido ({@code isPromoted}). O loop de
    /// maior valor de uma cena fica permanentemente preso ao chaining bloco-a-bloco cru
    /// pelo resto da sessão, mesmo com o bloco individual recompilando normalmente — o
    /// gap de desempenho frio→quente nunca fecha.
    ///
    /// Use isto (não `blockCache().clear()` diretamente) em `loadState` dos hospedeiros.
    public void reset() {
        blockCache.clear();
        superblockHeads.clear();
        if (superblocksEnabled) {
            superblockDetector = new LoopSuperblockDetector();
        }
    }

    /// Cria um runtime JIT com seus componentes principais.
    public JitRuntime(
            BlockCache blockCache,
            InstructionDecoder decoder,
            IrBuilder irBuilder,
            IrOptimizer optimizer,
            CodeEmitter emitter,
            ExecutionThreshold threshold) {
        this(blockCache, decoder, irBuilder, optimizer, emitter, threshold, 64);
    }

    /// Cria um runtime JIT com limite customizado de instruções por bloco.
    public JitRuntime(
            BlockCache blockCache,
            InstructionDecoder decoder,
            IrBuilder irBuilder,
            IrOptimizer optimizer,
            CodeEmitter emitter,
            ExecutionThreshold threshold,
            int maxBlockInstructions) {
        this(blockCache, decoder, decoder, irBuilder, optimizer, emitter, null, threshold, maxBlockInstructions);
    }

    /// Cria um runtime JIT capaz de alternar entre decoders ARM e THUMB.
    public JitRuntime(
            BlockCache blockCache,
            InstructionDecoder armDecoder,
            InstructionDecoder thumbDecoder,
            IrBuilder irBuilder,
            IrOptimizer optimizer,
            CodeEmitter emitter,
            ExecutionThreshold threshold,
            int maxBlockInstructions) {
        this(blockCache, armDecoder, thumbDecoder, irBuilder, optimizer, emitter, null, threshold, maxBlockInstructions);
    }

    /// Construtor terminal: aceita um `coldEmitter` opcional que ativa o modo TIERED
    /// (frio interpretado + compilação assíncrona em background). `coldEmitter == null`
    /// mantém o caminho clássico (single-step frio, compilação síncrona).
    public JitRuntime(
            BlockCache blockCache,
            InstructionDecoder armDecoder,
            InstructionDecoder thumbDecoder,
            IrBuilder irBuilder,
            IrOptimizer optimizer,
            CodeEmitter emitter,
            CodeEmitter coldEmitter,
            ExecutionThreshold threshold,
            int maxBlockInstructions) {
        this.blockCache = Objects.requireNonNull(blockCache, "blockCache");
        this.armDecoder = Objects.requireNonNull(armDecoder, "armDecoder");
        this.thumbDecoder = Objects.requireNonNull(thumbDecoder, "thumbDecoder");
        this.irBuilder = Objects.requireNonNull(irBuilder, "irBuilder");
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.coldEmitter = coldEmitter; // null = sem tiering
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (maxBlockInstructions <= 0) {
            throw new IllegalArgumentException("maxBlockInstructions must be positive");
        }
        this.maxBlockInstructions = maxBlockInstructions;
        this.compileExecutor = coldEmitter == null ? null
                : java.util.concurrent.Executors.newFixedThreadPool(compileThreadCount(), new java.util.concurrent.ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger seq =
                            new java.util.concurrent.atomic.AtomicInteger();

                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "arm-jitter-compile-" + seq.getAndIncrement());
                        thread.setDaemon(true);
                        // Prioridade mínima: a compilação é background e não-latência-crítica. Cede
                        // CPU à thread de emulação (frames/input) e à EDT, compilando nas folgas do
                        // frame pacing em vez de disputar — evita atrasar frames/input numa rajada.
                        // Um POOL (não uma thread só) drena o backlog de blocos novos ao entrar numa
                        // cena N× mais rápido — encurta o "warmup" visível (cada bloco novo roda
                        // interpretado até compilar). Cada thread usa seu próprio AsmBlockCompiler
                        // (ThreadLocal no AsmCodeEmitter); loader/blockSequence são thread-safe.
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }
                });
    }

    /// Threads de compilação em background por runtime: poucas, com teto, para que um backlog de
    /// blocos de troca de cena drene em paralelo sem sobrecarregar (há dois runtimes — ARM9/ARM7 —
    /// mais as threads de emu e render; os pools ficam ociosos entre rajadas e rodam em MIN_PRIORITY).
    private static int compileThreadCount() {
        return Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
    }

    /// Cria um runtime JIT ARM/THUMB com componentes padrão, exceto cache e emissor.
    public JitRuntime(BlockCache blockCache, IrBuilder irBuilder, IrOptimizer optimizer, CodeEmitter emitter, ExecutionThreshold threshold) {
        this(blockCache, new ArmDecoder(), new ThumbDecoder(), irBuilder, optimizer, emitter, threshold, 64);
    }

    /// Executa em `pc`, usando interpretação fria até o threshold e cache depois.
    ///
    /// O retorno conta apenas ciclos internos (`IrOp.Cycle`) do bloco ou instrução
    /// executada; fetch e waitstates de memória são somados diretamente em
    /// {@link ArmCore#cycles()}.
    public int execute(int pc, ArmCore core) {
        InstructionSet instructionSet = instructionSet(core);
        // ITSTATE (B2.4, D1): quase sempre 0 (fora de um Thumb-2 IT block) — só custa uma leitura
        // de campo, e entra na chave/tag para que um mesmo `pc` lifted com ITSTATE de entrada
        // diferentes nunca reaproveite um bloco com condições por-op erradas.
        int itState = core.cpsr().itState();

        // ── Inline cache (caminho quente) ───────────────────────────────────────
        // Revalida o IC contra a geração do cache (esvazia se algo foi removido) e
        // tenta o slot direto. Hit ⇒ executa sem tocar BlockKey/HashMap/Optional.
        syncInlineCacheGeneration();
        int slot = icSlot(pc);
        long tag = inlineTag(pc, instructionSet, itState);
        if (icTags[slot] == tag) {
            // Invariante: tag válida ⇒ bloco não-nulo (gravados juntos em inlineRecord).
            icHits++;
            if (warmupDiagnostics) {
                hotTierExecutions++;
            }
            int cycles = icBlocks[slot].execute(core);
            int chainFromPc = pc;
            int chainHops = 0;
            ChainProfiler.BreakReason chainBreak = null;
            boolean detectorSampling = superblockDetector != null
                    && superblockDetector.startRun(pc, instructionSet);
            // ── Encadeamento de blocos (chain fast path) ───────────────────────
            // Loops de spin (poll de IPC/VCOUNT/flags, com ou sem `bl` para um helper) são ciclos
            // de blocos de 1-4 instruções: sem isto, CADA bloco paga o round-trip completo
            // (scheduler + runBlock + IC + chamada megamórfica) — medido ~1,25 ciclos/bloco no
            // title screen de MKDS, com o despacho dominando o tempo de CPU. Enquanto o próximo
            // PC acertar o inline cache, executa o próximo bloco AQUI, até um orçamento de ciclos.
            // Limites de segurança:
            //  - orçamento: latência de interleave/IRQ fica na ordem de um bloco longo normal;
            //  - interruptLine: linha pendente volta ao runBlock, que a serve;
            //  - sleepState: um bloco pode ter dormido a CPU (SWI Halt/WFI);
            //  - generation: escrita automodificável esvazia o IC ⇒ quebra a corrente;
            //  - progresso: um passo sem ciclos internos aborta (nunca gira sem avançar o tempo);
            //  - `core.mode()` por passo re-sincroniza o banco de registradores como o runBlock faz.
            while (cycles < chainCycleBudget
                    && core.sleepState() == CpuSleepState.RUNNING
                    && !core.interruptLine()
                    && blockCache.generation() == icGeneration) {
                core.mode(); // sincroniza modo/banking a partir do CPSR (como no runBlock)
                int nextPc = core.programCounter();
                InstructionSet nextSet = instructionSet(core);
                int nextItState = core.cpsr().itState();
                int nextSlot = icSlot(nextPc);
                if (icTags[nextSlot] != inlineTag(nextPc, nextSet, nextItState)) {
                    if (chainProfiler != null) {
                        chainProfiler.recordIcMiss(chainFromPc, nextPc);
                        chainBreak = ChainProfiler.BreakReason.IC_MISS;
                    }
                    break; // próximo bloco não está no IC: volta ao caminho normal
                }
                CompiledBlock next = icBlocks[nextSlot];
                if (superblocksEnabled && next instanceof LoopSuperblock) {
                    // Superbloco consome o orçamento INTEIRO internamente: encadear para
                    // dentro dele quase dobraria a latência de interleave (S1). Ele só
                    // entra como primeiro bloco de um execute (IC hit no topo).
                    break;
                }
                int step = next.execute(core);
                cycles += step;
                chainedBlocks++;
                if (chainProfiler != null) {
                    chainProfiler.recordHop(chainFromPc, nextPc);
                    chainFromPc = nextPc;
                    chainHops++;
                }
                if (detectorSampling) {
                    detectorSampling = !superblockDetector.observeHop(nextPc, nextSet);
                }
                if (step <= 0) {
                    if (chainProfiler != null) {
                        chainBreak = ChainProfiler.BreakReason.NO_PROGRESS;
                    }
                    break;
                }
            }
            if (chainProfiler != null) {
                chainProfiler.recordRun(chainHops,
                        chainBreak != null ? chainBreak : whileExitReason(core, cycles));
            }
            if (superblockDetector != null) {
                LoopSuperblockDetector.Candidate promotedCandidate = superblockDetector.pollFresh();
                if (promotedCandidate != null) {
                    buildSuperblock(promotedCandidate.instructionSet(), promotedCandidate.memberPcs());
                }
            }
            core.addCycles(cycles);
            return cycles;
        }
        icMisses++;

        BlockKey key = new BlockKey(pc, instructionSet, itState);
        if (coldEmitter != null) {
            return executeTiered(pc, core, instructionSet, itState, key, slot, tag);
        }

        // ── Caminho clássico (sem tiering): lookup, threshold, compilação síncrona ─
        CompiledBlock block = blockCache.getOrNull(key);
        if (block == null) {
            int hits = blockCache.hit(key);
            if (!threshold.isHot(hits)) {
                core.setProgramCounter(pc);
                return core.stepReturningInternalCycles();
            }
            IrBlock irBlock = lift(pc, core.memory(), instructionSet, itState);
            block = emitter.emit(optimizer.optimize(irBlock));
            blockCache.put(key, block, irBlock.startPc(), irBlock.endPc());
        }
        inlineRecord(slot, tag, block);
        return run(block, core);
    }

    /// Classifica por que o `while` do encadeamento terminou (só chamado com profiler ativo,
    /// fora do caminho quente). Reavalia as condições na MESMA ordem do loop.
    private ChainProfiler.BreakReason whileExitReason(ArmCore core, int cycles) {
        if (cycles >= chainCycleBudget) {
            return ChainProfiler.BreakReason.BUDGET;
        }
        if (core.sleepState() != CpuSleepState.RUNNING) {
            return ChainProfiler.BreakReason.SLEEP;
        }
        if (core.interruptLine()) {
            return ChainProfiler.BreakReason.INTERRUPT;
        }
        return ChainProfiler.BreakReason.GENERATION;
    }

    /// Caminho TIERED: integra compilações de background prontas, depois despacha o tier do
    /// bloco (frio interpretado / quente compilado), submetendo a compilação ao esquentar.
    private int executeTiered(int pc, ArmCore core, InstructionSet instructionSet, int itState, BlockKey key, int slot, long tag) {
        integrateCompiled();
        BlockCache.CacheEntry entry = blockCache.entry(key);
        if (entry == null) {
            // Primeira visão: interpreta o bloco inteiro (frio) e cacheia. Sem classloading.
            if (warmupDiagnostics) {
                coldTierExecutions++;
            }
            IrBlock irBlock = lift(pc, core.memory(), instructionSet, itState);
            CompiledBlock cold = coldEmitter.emit(irBlock);
            blockCache.put(key, cold, false, irBlock.startPc(), irBlock.endPc());
            return run(cold, core);
        }
        if (entry.compiled()) {
            if (warmupDiagnostics) {
                hotTierExecutions++;
            }
            inlineRecord(slot, tag, entry.block());
            return run(entry.block(), core);
        }
        // Tier frio: conta execuções; ao esquentar, submete a compilação ao background (uma vez).
        if (warmupDiagnostics) {
            coldTierExecutions++;
        }
        CompiledBlock cold = entry.block();
        int hits = blockCache.hit(key);
        if (threshold.isHot(hits) && compilingColdBlocks.add(cold)) {
            submitCompile(key, cold, lift(pc, core.memory(), instructionSet, itState));
        }
        return run(cold, core);
    }

    private int run(CompiledBlock block, ArmCore core) {
        int cycles = block.execute(core);
        core.addCycles(cycles);
        return cycles;
    }

    /// Submete o `emit` (bytecode + classload — a parte que trava) ao background, sobre o
    /// `IrBlock` imutável (o lift já rodou na thread de emulação, então não toca a memória do
    /// guest concorrentemente).
    private void submitCompile(BlockKey key, CompiledBlock cold, IrBlock irBlock) {
        int startPc = irBlock.startPc();
        int endPc = irBlock.endPc();
        compileExecutor.execute(() -> {
            CompiledBlock hot = emitter.emit(optimizer.optimize(irBlock));
            compiled.add(new CompileResult(key, cold, hot, startPc, endPc));
        });
    }

    /// Integra (na thread de emulação) compilações prontas, promovendo frio→quente. Descarta
    /// resultados obsoletos: se a closure fria de origem não está mais no cache (SMC invalidou/
    /// re-liftou), o bytecode veio de código que mudou.
    private void integrateCompiled() {
        CompileResult result;
        while ((result = compiled.poll()) != null) {
            compilingColdBlocks.remove(result.coldBlock());
            BlockCache.CacheEntry current = blockCache.entry(result.key());
            if (current != null && !current.compiled() && current.block() == result.coldBlock()) {
                blockCache.put(result.key(), result.compiledBlock(), true, result.startPc(), result.endPc());
                if (warmupDiagnostics) {
                    blocksCompiledTotal++;
                }
            }
        }
    }

    /// Índice do inline cache para um PC. Dobra os bits altos no índice: sem isso, blocos a
    /// strides de IC_SIZE*2 bytes (e regiões diferentes: main RAM 0x02xxxxxx vs WRAM 0x03xxxxxx)
    /// aliasam no mesmo slot.
    private static int icSlot(int pc) {
        return ((pc >>> 1) ^ (pc >>> 16)) & IC_MASK;
    }

    /// Tag do inline cache: `pc` (32 bits) + conjunto de instruções no bit 32 + ITSTATE\[7:0\]
    /// (B2.4, D1) nos bits 33-40 — mesma razão de `BlockKey` incluir `itState`: sem isto, o IC
    /// (camada front-side sobre o `BlockCache`) poderia servir um bloco com condições por-op
    /// baked para um ITSTATE de entrada diferente do atual, mesmo com o `BlockCache` já correto.
    private static long inlineTag(int pc, InstructionSet instructionSet, int itState) {
        return (pc & 0xFFFF_FFFFL) | ((long) instructionSet.ordinal() << 32) | ((long) (itState & 0xFF) << 33);
    }

    /// Esvazia o inline cache se o {@link BlockCache} sofreu remoção desde a última
    /// validação, ressincronizando a geração. Chamado no início de `execute` e antes
    /// de gravar uma entrada nova (a compilação pode ter evictado/sobrescrito blocos).
    private void syncInlineCacheGeneration() {
        long gen = blockCache.generation();
        if (gen != icGeneration) {
            Arrays.fill(icTags, IC_EMPTY);
            icGeneration = gen;
            icFlushes++;
        }
    }

    /// Grava o bloco no slot do inline cache, revalidando a geração antes (a `put` da
    /// compilação pode tê-la incrementado) para que a entrada nova não seja descartada.
    private void inlineRecord(int slot, long tag, CompiledBlock block) {
        syncInlineCacheGeneration();
        icTags[slot] = tag;
        icBlocks[slot] = block;
    }

    /// Devolve as até `max` chaves dos blocos COMPILADOS mais executados (task C10 —
    /// warm-start): o hospedeiro persiste essas chaves por ROM e as reagenda via
    /// {@link #precompile(Collection, AddressSpace)} na próxima carga, cortando o tier
    /// frio inicial dos blocos que historicamente mais rodam. Delega a {@link BlockCache#hotKeys}.
    public List<BlockKey> hotBlockKeys(int max) {
        return blockCache.hotKeys(max);
    }

    /// Agenda a compilação adiantada das `keys` informadas (task C10 — warm-start), lendo o
    /// código ATUAL de `memory` e reusando o MESMO caminho/pool de compilação em background do
    /// tier quente (nunca uma thread dedicada nova) — ver {@link #submitCompile}.
    ///
    /// <p>Sem efeito no modo não-tiered ({@code coldEmitter == null}): não há pool de background
    /// para agendar, e o caminho síncrono clássico não se beneficia de pré-aquecimento.</p>
    ///
    /// <p>Chaves cujo `pc` já está no {@link BlockCache} (frio ou quente) são ignoradas — o
    /// caminho normal já as cobre. Chaves cujo conteúdo de memória mudou desde que foram
    /// persistidas (ROM diferente, código automodificado) simplesmente compilam o que está lá
    /// AGORA: a validação natural é o próprio lift, igual à compilação normal — um bloco
    /// pré-compilado "errado" é impossível por construção (no pior caso, um bloco inútil que
    /// nunca chega a ser chamado, removido depois pela invalidação por escrita normal). Falhas de
    /// leitura de memória (endereço fora do espaço mapeado) são ignoradas silenciosamente,
    /// por chave.</p>
    public void precompile(Collection<BlockKey> keys, AddressSpace memory) {
        if (compileExecutor == null) {
            return;
        }
        for (BlockKey key : keys) {
            if (blockCache.entry(key) != null) {
                continue; // já presente (frio ou quente): o caminho normal já cobre
            }
            IrBlock irBlock;
            try {
                irBlock = lift(key.pc(), memory, key.instructionSet(), key.itState());
            } catch (RuntimeException e) {
                continue; // ROM diferente/self-modified/fora do mapa: ignora silenciosamente
            }
            CompiledBlock cold = coldEmitter.emit(irBlock);
            blockCache.put(key, cold, false, irBlock.startPc(), irBlock.endPc());
            if (compilingColdBlocks.add(cold)) {
                submitCompile(key, cold, irBlock);
            }
        }
    }

    /// Compila um bloco iniciando em `pc`.
    public CompiledBlock compile(int pc) {
        throw new UnsupportedOperationException("Use compile(int, AddressSpace) so the runtime can read guest memory");
    }

    /// Compila um bloco iniciando em `pc` lendo instruções da memória informada.
    public CompiledBlock compile(int pc, AddressSpace memory) {
        return compile(pc, memory, InstructionSet.ARM);
    }

    /// Compila um bloco iniciando em `pc` usando o conjunto de instruções informado.
    public CompiledBlock compile(int pc, AddressSpace memory, InstructionSet instructionSet) {
        IrBlock block = lift(pc, memory, instructionSet, 0);
        return emitter.emit(optimizer.optimize(block));
    }

    /// Invalida código compilado afetado por uma escrita de memória de 1 byte.
    public void invalidate(int address) {
        blockCache.invalidate(address);
    }

    /// Invalida código compilado afetado por uma escrita de `sizeBytes` a partir de `address`.
    /// Prefira esta forma nos barramentos: o intervalo inteiro da escrita conta (ver
    /// {@link BlockCache#invalidate(int, int)} — um bloco de uma instrução THUMB no meio de
    /// uma word escapa da invalidação por endereço-base).
    public void invalidate(int address, int sizeBytes) {
        blockCache.invalidate(address, sizeBytes);
    }

    /// Retorna o cache de blocos usado pelo runtime.
    public BlockCache blockCache() {
        return blockCache;
    }

    /// Retorna o decodificador configurado.
    public InstructionDecoder decoder() {
        return armDecoder;
    }

    /// Retorna o decoder ARM configurado.
    public InstructionDecoder armDecoder() {
        return armDecoder;
    }

    /// Retorna o decoder THUMB configurado.
    public InstructionDecoder thumbDecoder() {
        return thumbDecoder;
    }

    /// Retorna o construtor de IR configurado.
    public IrBuilder irBuilder() {
        return irBuilder;
    }

    /// Retorna o otimizador configurado.
    public IrOptimizer optimizer() {
        return optimizer;
    }

    /// Retorna o emissor de código configurado.
    public CodeEmitter emitter() {
        return emitter;
    }

    /// Retorna o backend de codegen do emissor configurado.
    public CodegenBackend codegenBackend() {
        return emitter.backend();
    }

    /// Retorna a política de aquecimento configurada.
    public ExecutionThreshold threshold() {
        return threshold;
    }

    /// Retorna o limite máximo de instruções por bloco.
    public int maxBlockInstructions() {
        return maxBlockInstructions;
    }

    private InstructionSet instructionSet(ArmCore core) {
        return core.cpsr().isThumbMode() ? InstructionSet.THUMB : InstructionSet.ARM;
    }

    private InstructionDecoder decoderFor(InstructionSet instructionSet) {
        return switch (instructionSet) {
            case ARM -> armDecoder;
            case THUMB -> thumbDecoder;
        };
    }

    private IrBlock lift(int pc, AddressSpace memory, InstructionSet instructionSet, int itStateAtEntry) {
        IrBlockLifter lifter = new StandardIrBlockLifter(decoderFor(instructionSet), irBuilder);
        return lifter.lift(memory, pc, maxBlockInstructions, itStateAtEntry);
    }
}
