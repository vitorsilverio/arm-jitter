package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.core.ArmCore;
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
    private final ExecutionThreshold threshold;
    private final int maxBlockInstructions;

    // ── Inline cache de dispatch ────────────────────────────────────────────────
    // Cache front-side direct-mapped sobre o {@link BlockCache}: evita, no caminho
    // quente, a alocação de {@link BlockKey}, o lookup no HashMap e o {@link java.util.Optional}.
    // Cada slot guarda uma tag (pc + conjunto de instruções) e o bloco compilado.
    //
    // Correção (código automodificável): o IC é validado em bloco contra
    // {@link BlockCache#generation()}. Qualquer remoção estrutural no cache principal
    // (SMC, evicção LRU, sobrescrita, clear) incrementa a geração; ao detectar a
    // divergência o IC é esvaziado preguiçosamente, então um bloco obsoleto nunca executa.
    /// Número de entradas do inline cache (potência de 2).
    private static final int IC_SIZE = 1 << 12;
    /// Máscara de indexação do inline cache.
    private static final int IC_MASK = IC_SIZE - 1;
    /// Tag sentinela para slot vazio (nenhuma tag válida é negativa).
    private static final long IC_EMPTY = -1L;
    /// Tags `(pc & 0xFFFFFFFF) | (instructionSet.ordinal() << 32)` por slot.
    private final long[] icTags = new long[IC_SIZE];
    /// Blocos compilados por slot, alinhados a {@link #icTags}.
    private final CompiledBlock[] icBlocks = new CompiledBlock[IC_SIZE];
    /// Geração do {@link BlockCache} para a qual o IC é válido. Inicia em -1
    /// (sentinela impossível) para forçar o flush/preenchimento no primeiro `execute`.
    private long icGeneration = -1L;

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
        this.blockCache = Objects.requireNonNull(blockCache, "blockCache");
        this.armDecoder = Objects.requireNonNull(decoder, "decoder");
        this.thumbDecoder = Objects.requireNonNull(decoder, "decoder");
        this.irBuilder = Objects.requireNonNull(irBuilder, "irBuilder");
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (maxBlockInstructions <= 0) {
            throw new IllegalArgumentException("maxBlockInstructions must be positive");
        }
        this.maxBlockInstructions = maxBlockInstructions;
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
        this.blockCache = Objects.requireNonNull(blockCache, "blockCache");
        this.armDecoder = Objects.requireNonNull(armDecoder, "armDecoder");
        this.thumbDecoder = Objects.requireNonNull(thumbDecoder, "thumbDecoder");
        this.irBuilder = Objects.requireNonNull(irBuilder, "irBuilder");
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (maxBlockInstructions <= 0) {
            throw new IllegalArgumentException("maxBlockInstructions must be positive");
        }
        this.maxBlockInstructions = maxBlockInstructions;
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
        int slot = (pc >>> 1) & IC_MASK;
        long tag = inlineTag(pc, instructionSet);
        if (icTags[slot] == tag) {
            // Invariante: tag válida ⇒ bloco não-nulo (gravados juntos em inlineRecord).
            int cycles = icBlocks[slot].execute(core);
            core.addCycles(cycles);
            return cycles;
        }

        // ── Caminho frio: lookup no HashMap, threshold de aquecimento, compilação ─
        BlockKey key = new BlockKey(pc, instructionSet);
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
        int cycles = block.execute(core);
        core.addCycles(cycles);
        return cycles;
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
