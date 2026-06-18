package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrOptimizer;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;

/// Fábrica de runtimes comuns para integração com emuladores.
///
/// Os métodos `interpreted*` criam runtimes com
/// {@link dev.vitorsilverio.armjitter.codegen.CodegenBackend#INTERPRETED_IR}, o backend
/// padrão e recomendado até a cobertura ASM descrita em `ROADMAP.md`.
public final class JitRuntimeFactory {
    private JitRuntimeFactory() {
    }

    /// Cria um runtime ARM32 com emissor interpretado de IR (ARMv4T).
    public static JitRuntime interpretedArm(int cacheEntries, int hotThreshold) {
        return interpretedArm(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM/THUMB com emissor interpretado de IR (ARMv4T).
    public static JitRuntime interpretedArmThumb(int cacheEntries, int hotThreshold) {
        return interpretedArmThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime THUMB16 com emissor interpretado de IR (ARMv4T).
    public static JitRuntime interpretedThumb(int cacheEntries, int hotThreshold) {
        return interpretedThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM32 para a arquitetura informada.
    public static JitRuntime interpretedArm(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture);
    }

    /// Cria um runtime ARM/THUMB para a arquitetura informada.
    public static JitRuntime interpretedArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture);
    }

    /// Cria um runtime THUMB16 para a arquitetura informada.
    public static JitRuntime interpretedThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture, new InterpretedCodeEmitter(architecture));
    }

    /// Cria um runtime ARM/THUMB com emissor ASM (ALU simples + fallback interpretado).
    public static JitRuntime jvmArmThumb(int cacheEntries, int hotThreshold) {
        return jvmArmThumb(cacheEntries, hotThreshold, ArmArchitecture.ARMV4T);
    }

    /// Cria um runtime ARM/THUMB com emissor ASM para a arquitetura informada.
    public static JitRuntime jvmArmThumb(int cacheEntries, int hotThreshold, ArmArchitecture architecture) {
        return build(cacheEntries, hotThreshold, architecture, new AsmCodeEmitter(architecture));
    }

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
