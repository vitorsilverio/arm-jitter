package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrOptimizer;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;

/// Fabrica de runtimes comuns para integracao com emuladores.
public final class JitRuntimeFactory {
    private JitRuntimeFactory() {
    }

    /// Cria um runtime ARM32 com emissor interpretado de IR.
    public static JitRuntime interpretedArm(int cacheEntries, int hotThreshold) {
        return new JitRuntime(
                new BlockCache(cacheEntries),
                new ArmDecoder(),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                new InterpretedCodeEmitter(),
                new ExecutionThreshold(hotThreshold));
    }

    /// Cria um runtime ARM/THUMB com emissor interpretado de IR.
    public static JitRuntime interpretedArmThumb(int cacheEntries, int hotThreshold) {
        return new JitRuntime(
                new BlockCache(cacheEntries),
                new ArmDecoder(),
                new ThumbDecoder(),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                new InterpretedCodeEmitter(),
                new ExecutionThreshold(hotThreshold),
                64);
    }

    /// Cria um runtime THUMB16 com emissor interpretado de IR.
    public static JitRuntime interpretedThumb(int cacheEntries, int hotThreshold) {
        return new JitRuntime(
                new BlockCache(cacheEntries),
                new ThumbDecoder(),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                new InterpretedCodeEmitter(),
                new ExecutionThreshold(hotThreshold));
    }
}
