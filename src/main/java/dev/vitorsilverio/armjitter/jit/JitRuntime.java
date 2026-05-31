package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrBuilder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrBlockLifter;
import dev.vitorsilverio.armjitter.ir.IrOptimizer;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Objects;

/// Orquestra cache, decodificacao, IR, otimizacao e codegen.
public final class JitRuntime {
    private final BlockCache blockCache;
    private final InstructionDecoder armDecoder;
    private final InstructionDecoder thumbDecoder;
    private final IrBuilder irBuilder;
    private final IrOptimizer optimizer;
    private final CodeEmitter emitter;
    private final ExecutionThreshold threshold;
    private final int maxBlockInstructions;

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

    /// Cria um runtime JIT com limite customizado de instrucoes por bloco.
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

    /// Cria um runtime JIT ARM/THUMB com componentes padrao, exceto cache e emissor.
    public JitRuntime(BlockCache blockCache, IrBuilder irBuilder, IrOptimizer optimizer, CodeEmitter emitter, ExecutionThreshold threshold) {
        this(blockCache, new ArmDecoder(), new ThumbDecoder(), irBuilder, optimizer, emitter, threshold, 64);
    }

    /// Executa em `pc`, usando interpretacao fria ate o threshold e cache depois.
    public int execute(int pc, ArmCore core) {
        InstructionSet instructionSet = instructionSet(core);
        BlockKey key = new BlockKey(pc, instructionSet);
        CompiledBlock block = blockCache.get(key).orElse(null);
        if (block == null) {
            int hits = blockCache.hit(key);
            if (!threshold.isHot(hits)) {
                core.setProgramCounter(pc);
                core.step();
                return 1;
            }
            IrBlock irBlock = lift(pc, core.memory(), instructionSet);
            block = emitter.emit(optimizer.optimize(irBlock));
            blockCache.put(key, block, irBlock.startPc(), irBlock.endPc());
        }
        int cycles = block.execute(core);
        core.addCycles(cycles);
        return cycles;
    }

    /// Compila um bloco iniciando em `pc`.
    public CompiledBlock compile(int pc) {
        throw new UnsupportedOperationException("Use compile(int, AddressSpace) so the runtime can read guest memory");
    }

    /// Compila um bloco iniciando em `pc` lendo instrucoes da memoria informada.
    public CompiledBlock compile(int pc, AddressSpace memory) {
        return compile(pc, memory, InstructionSet.ARM);
    }

    /// Compila um bloco iniciando em `pc` usando o conjunto de instrucoes informado.
    public CompiledBlock compile(int pc, AddressSpace memory, InstructionSet instructionSet) {
        IrBlock block = lift(pc, memory, instructionSet);
        return emitter.emit(optimizer.optimize(block));
    }

    /// Invalida codigo compilado afetado por uma escrita de memoria.
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

    /// Retorna o emissor de codigo configurado.
    public CodeEmitter emitter() {
        return emitter;
    }

    /// Retorna a politica de aquecimento configurada.
    public ExecutionThreshold threshold() {
        return threshold;
    }

    /// Retorna o limite maximo de instrucoes por bloco.
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
