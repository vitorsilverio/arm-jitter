package dev.vitorsilverio.armjitter.codegen.jvm;

/// Os emissores de família de um {@link AsmBlockCompiler}, todos sobre o mesmo {@link AsmEmitState}.
record AsmEmitters(
        AsmEmitterBase base,
        AsmAluEmitter alu,
        AsmIntegerEmitter integer,
        AsmMemoryEmitter memory,
        AsmControlEmitter control,
        AsmVfpEmitter vfp) {

    static AsmEmitters of(AsmEmitState state) {
        return new AsmEmitters(new AsmEmitterBase(state), new AsmAluEmitter(state), new AsmIntegerEmitter(state),
                new AsmMemoryEmitter(state), new AsmControlEmitter(state), new AsmVfpEmitter(state));
    }
}
