package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.StandardIrOptimizer;

/// Fábrica de runtimes comuns para integração com emuladores.
///
/// <h2>Uso recomendado (Fase 8+)</h2>
///
/// <pre>{@code
/// // Recomendado: bytecode JVM + otimizador GBA
/// JitRuntime runtime = JitRuntimeFactory.armThumb(1024, 3);
///
/// // Debug / oráculo: interpretador IR puro
/// JitRuntime oracle = JitRuntimeFactory.interpretedArmThumb(1024, 3);
/// }</pre>
///
/// <h2>Migração de {@code jvmArmThumb}</h2>
///
/// Substituir {@code JitRuntimeFactory.jvmArmThumb(...)} por
/// {@code JitRuntimeFactory.armThumb(...)}: a nova factory inclui o
/// {@link StandardIrOptimizer#gba() otimizador GBA} (constant fold + DCE + flag merge)
/// que a versão anterior não tinha.
public final class JitRuntimeFactory {
    private JitRuntimeFactory() {
    }

    // ── Factories padrão (JVM bytecode + otimizador, Fase 8) ─────────────────

    /// Cria um runtime ARM/THUMB com bytecode JVM e otimizador GBA (ARMv4T).
    ///
    /// <p>Este é o factory <b>recomendado</b> para produção: emite bytecode JVM com
    /// fallback interpretado para ops não suportadas e aplica o pipeline
    /// {@link StandardIrOptimizer#gba()} (constant fold → DCE → flag merge).</p>
    public static JitRuntime armThumb(int cacheEntries, int hotThreshold) {
        return armThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM/THUMB com bytecode JVM e otimizador GBA para a arquitetura informada.
    public static JitRuntime armThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture,
                new AsmCodeEmitter(architecture, AsmFallbackPolicy.WHOLE_BLOCK, StandardIrOptimizer.gba()));
    }

    /// Cria um runtime de DIAGNÓSTICO que executa cada bloco pelo interpretador (oráculo) e
    /// pelo backend ASM de produção, comparando o estado da CPU e lançando ao primeiro divergir.
    ///
    /// <p>Lento (dupla execução + save/restore por bloco) e destinado a localizar bugs de
    /// emissão ASM (ver {@link dev.vitorsilverio.armjitter.codegen.equivalence.DivergenceCheckingCodeEmitter}).</p>
    public static JitRuntime divergenceCheckingArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        dev.vitorsilverio.armjitter.codegen.CodeEmitter reference = new InterpretedCodeEmitter(architecture);
        dev.vitorsilverio.armjitter.codegen.CodeEmitter candidate =
                new AsmCodeEmitter(architecture, AsmFallbackPolicy.WHOLE_BLOCK, StandardIrOptimizer.gba());
        return build(cacheEntries, hotThreshold, architecture,
                new dev.vitorsilverio.armjitter.codegen.equivalence.DivergenceCheckingCodeEmitter(reference, candidate, architecture));
    }

    // ── Factories interpretadas (debug / oráculo) ─────────────────────────────

    /// Cria um runtime ARM32 com emissor interpretado de IR (ARMv4T).
    ///
    /// <p>Indicado para debug, step-by-step e como oráculo em harnesses de equivalência.</p>
    public static JitRuntime interpretedArm(int cacheEntries, int hotThreshold) {
        return interpretedArm(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM/THUMB com emissor interpretado de IR (ARMv4T).
    ///
    /// <p>Indicado para debug, step-by-step e como oráculo em harnesses de equivalência.
    /// Para produção prefira {@link #armThumb(int, int)}.</p>
    public static JitRuntime interpretedArmThumb(int cacheEntries, int hotThreshold) {
        return interpretedArmThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime THUMB16 com emissor interpretado de IR (ARMv4T).
    ///
    /// <p>Indicado para debug, step-by-step e como oráculo em harnesses de equivalência.</p>
    public static JitRuntime interpretedThumb(int cacheEntries, int hotThreshold) {
        return interpretedThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM32 para a arquitetura informada (emissor interpretado).
    public static JitRuntime interpretedArm(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture);
    }

    /// Cria um runtime ARM/THUMB para a arquitetura informada (emissor interpretado).
    public static JitRuntime interpretedArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture);
    }

    /// Cria um runtime THUMB16 para a arquitetura informada (emissor interpretado).
    public static JitRuntime interpretedThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture, new InterpretedCodeEmitter(architecture));
    }

    // ── Deprecated ────────────────────────────────────────────────────────────

    /// Cria um runtime ARM/THUMB com bytecode JVM sem otimizador (ARMv4T).
    ///
    /// @deprecated Use {@link #armThumb(int, int)} que inclui o otimizador GBA e é o factory
    ///             recomendado a partir da Fase 8. Esta versão não aplica constant fold, DCE
    ///             nem flag merge.
    @Deprecated(since = "1.0", forRemoval = false)
    public static JitRuntime jvmArmThumb(int cacheEntries, int hotThreshold) {
        return jvmArmThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM/THUMB com bytecode JVM para a arquitetura informada (sem otimizador).
    ///
    /// @deprecated Use {@link #armThumb(int, int, ArmArchitecture)}.
    @Deprecated(since = "1.0", forRemoval = false)
    public static JitRuntime jvmArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture, new AsmCodeEmitter(architecture));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JitRuntime build(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture, new InterpretedCodeEmitter(architecture));
    }

    private static JitRuntime build(
            int cacheEntries,
            int hotThreshold,
            ArmArchitecture architecture,
            dev.vitorsilverio.armjitter.codegen.CodeEmitter emitter) {
        return new JitRuntime(
                new BlockCache(cacheEntries),
                new ArmDecoder(architecture),
                new ThumbDecoder(architecture),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                emitter,
                new ExecutionThreshold(hotThreshold),
                64);
    }
}
