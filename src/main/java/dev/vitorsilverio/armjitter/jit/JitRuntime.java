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

    /// Define o orçamento de ciclos do encadeamento de blocos (0 desliga). Ver o comentário de
    /// {@link #chainCycleBudget} para as restrições de quando encadear é seguro.
    public void setChainCycleBudget(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("chain cycle budget must be >= 0");
        }
        this.chainCycleBudget = cycles;
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
                : java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "arm-jitter-compile");
                    thread.setDaemon(true);
                    // Prioridade mínima: a compilação é background e não-latência-crítica. Cede
                    // CPU à thread de emulação (frames/input) e à EDT, compilando nas folgas do
                    // frame pacing em vez de disputar — evita atrasar frames/input numa rajada.
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
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

        // ── Inline cache (caminho quente) ───────────────────────────────────────
        // Revalida o IC contra a geração do cache (esvazia se algo foi removido) e
        // tenta o slot direto. Hit ⇒ executa sem tocar BlockKey/HashMap/Optional.
        syncInlineCacheGeneration();
        int slot = icSlot(pc);
        long tag = inlineTag(pc, instructionSet);
        if (icTags[slot] == tag) {
            // Invariante: tag válida ⇒ bloco não-nulo (gravados juntos em inlineRecord).
            icHits++;
            int cycles = icBlocks[slot].execute(core);
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
                int nextSlot = icSlot(nextPc);
                if (icTags[nextSlot] != inlineTag(nextPc, nextSet)) {
                    break; // próximo bloco não está no IC: volta ao caminho normal
                }
                int step = icBlocks[nextSlot].execute(core);
                cycles += step;
                chainedBlocks++;
                if (step <= 0) {
                    break;
                }
            }
            core.addCycles(cycles);
            return cycles;
        }
        icMisses++;

        BlockKey key = new BlockKey(pc, instructionSet);
        if (coldEmitter != null) {
            return executeTiered(pc, core, instructionSet, key, slot, tag);
        }

        // ── Caminho clássico (sem tiering): lookup, threshold, compilação síncrona ─
        CompiledBlock block = blockCache.getOrNull(key);
        if (block == null) {
            int hits = blockCache.hit(key);
            if (!threshold.isHot(hits)) {
                core.setProgramCounter(pc);
                return core.stepReturningInternalCycles();
            }
            IrBlock irBlock = lift(pc, core.memory(), instructionSet);
            block = emitter.emit(optimizer.optimize(irBlock));
            blockCache.put(key, block, irBlock.startPc(), irBlock.endPc());
        }
        inlineRecord(slot, tag, block);
        return run(block, core);
    }

    /// Caminho TIERED: integra compilações de background prontas, depois despacha o tier do
    /// bloco (frio interpretado / quente compilado), submetendo a compilação ao esquentar.
    private int executeTiered(int pc, ArmCore core, InstructionSet instructionSet, BlockKey key, int slot, long tag) {
        integrateCompiled();
        BlockCache.CacheEntry entry = blockCache.entry(key);
        if (entry == null) {
            // Primeira visão: interpreta o bloco inteiro (frio) e cacheia. Sem classloading.
            IrBlock irBlock = lift(pc, core.memory(), instructionSet);
            CompiledBlock cold = coldEmitter.emit(irBlock);
            blockCache.put(key, cold, false, irBlock.startPc(), irBlock.endPc());
            return run(cold, core);
        }
        if (entry.compiled()) {
            inlineRecord(slot, tag, entry.block());
            return run(entry.block(), core);
        }
        // Tier frio: conta execuções; ao esquentar, submete a compilação ao background (uma vez).
        CompiledBlock cold = entry.block();
        int hits = blockCache.hit(key);
        if (threshold.isHot(hits) && compilingColdBlocks.add(cold)) {
            submitCompile(key, cold, lift(pc, core.memory(), instructionSet));
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
            }
        }
    }

    /// Índice do inline cache para um PC. Dobra os bits altos no índice: sem isso, blocos a
    /// strides de IC_SIZE*2 bytes (e regiões diferentes: main RAM 0x02xxxxxx vs WRAM 0x03xxxxxx)
    /// aliasam no mesmo slot.
    private static int icSlot(int pc) {
        return ((pc >>> 1) ^ (pc >>> 16)) & IC_MASK;
    }

    /// Tag do inline cache: `pc` (32 bits) + conjunto de instruções no bit 32.
    private static long inlineTag(int pc, InstructionSet instructionSet) {
        return (pc & 0xFFFF_FFFFL) | ((long) instructionSet.ordinal() << 32);
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
        IrBlock block = lift(pc, memory, instructionSet);
        return emitter.emit(optimizer.optimize(block));
    }

    /// Invalida código compilado afetado por uma escrita de memória.
    public void invalidate(int address) {
        blockCache.invalidate(address);
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

    private IrBlock lift(int pc, AddressSpace memory, InstructionSet instructionSet) {
        IrBlockLifter lifter = new StandardIrBlockLifter(decoderFor(instructionSet), irBuilder);
        return lifter.lift(memory, pc, maxBlockInstructions);
    }
}
