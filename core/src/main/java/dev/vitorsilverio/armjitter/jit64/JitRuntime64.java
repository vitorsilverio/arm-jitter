package dev.vitorsilverio.armjitter.jit64;

import dev.vitorsilverio.armjitter.codegen64.Ir64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64BlockLifter;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;

import java.util.Objects;

/// Orquestra cache, lifting e codegen A64 — espelho estrutural (caminho "clássico" pré-tiering)
/// de {@link dev.vitorsilverio.armjitter.jit.JitRuntime} (32 bits), introduzido na task B6.4
/// (PR1). Deliberadamente sem inline cache, sem encadeamento de blocos e sem compilação em
/// background (decisão D0/D5 da spec `b6.4-aarch64-asm-backend.md`) — nenhum consumidor A64 hoje
/// tem um hot loop medido que justifique replicar essa engenharia às cegas.
///
/// {@link ExecutionThreshold} é reaproveitado DIRETAMENTE do pacote `jit` (32 bits) — é genérico
/// o bastante (só `int hotCount`, nenhuma referência a `ArmCore`/`int pc`), decisão D0 da spec.
public final class JitRuntime64 {
    private final BlockCache64 blockCache;
    private final Ir64BlockLifter lifter;
    private final Ir64BlockExecutor coldExecutor;
    private final Ir64CodeEmitter emitter;
    private final ExecutionThreshold threshold;
    private final int maxBlockInstructions;

    /// Cria um runtime com componentes padrão (lifter e executor interpretado do B6.1).
    public JitRuntime64(BlockCache64 blockCache, Ir64CodeEmitter emitter, ExecutionThreshold threshold) {
        this(blockCache, new StandardIr64BlockLifter(), new Ir64BlockExecutor(), emitter, threshold, 64);
    }

    /// Cria um runtime com todos os componentes explícitos (útil em testes).
    public JitRuntime64(
            BlockCache64 blockCache,
            Ir64BlockLifter lifter,
            Ir64BlockExecutor coldExecutor,
            Ir64CodeEmitter emitter,
            ExecutionThreshold threshold,
            int maxBlockInstructions) {
        this.blockCache = Objects.requireNonNull(blockCache, "blockCache");
        this.lifter = Objects.requireNonNull(lifter, "lifter");
        this.coldExecutor = Objects.requireNonNull(coldExecutor, "coldExecutor");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (maxBlockInstructions <= 0) {
            throw new IllegalArgumentException("maxBlockInstructions must be positive");
        }
        this.maxBlockInstructions = maxBlockInstructions;
    }

    /// Executa em `pc`: interpreta uma instrução por vez (frio) até o threshold, então faz lift
    /// de um {@link Ir64Block} e compila via {@link Ir64CodeEmitter} (quente, cacheado por
    /// {@link BlockKey64}). Sem inline cache/encadeamento (ver Javadoc da classe) — cada chamada
    /// faz no máximo um lookup de `HashMap`.
    ///
    /// @param pc endereço a executar
    /// @param core core a executar
    /// @return ciclos internos consumidos (mesma convenção do mundo 32-bit: só `Ir64Op.Cycle`;
    ///         fetch/waitstates vão direto para {@link Aarch64Core#cycles()})
    public int execute(long pc, Aarch64Core core) {
        BlockKey64 key = new BlockKey64(pc);
        CompiledBlock64 block = blockCache.getOrNull(key);
        if (block == null) {
            int hits = blockCache.hit(key);
            if (!threshold.isHot(hits)) {
                core.setProgramCounter(pc);
                return coldExecutor.step(core);
            }
            Ir64Block irBlock = lifter.lift(core.memory(), pc, maxBlockInstructions);
            block = emitter.emit(irBlock);
            blockCache.put(key, block);
        }
        int cycles = block.execute(core);
        core.addCycles(cycles);
        return cycles;
    }

    /// Retorna o cache de blocos usado pelo runtime.
    public BlockCache64 blockCache() {
        return blockCache;
    }

    /// Retorna o emissor de código configurado.
    public Ir64CodeEmitter emitter() {
        return emitter;
    }

    /// Retorna a política de aquecimento configurada.
    public ExecutionThreshold threshold() {
        return threshold;
    }
}
