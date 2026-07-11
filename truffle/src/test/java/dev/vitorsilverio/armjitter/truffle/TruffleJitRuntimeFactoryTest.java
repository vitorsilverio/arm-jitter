package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.opt.StandardIrOptimizer;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import org.junit.jupiter.api.Test;

/// A4 — espelha `JitRuntimeJvmFactoryTest` (core), mas para `TruffleJitRuntimeFactory`.
class TruffleJitRuntimeFactoryTest {

    @Test
    void truffleArmThumbReportsTruffleBackend() {
        JitRuntime runtime = TruffleJitRuntimeFactory.truffleArmThumb(16, 1);

        assertEquals(CodegenBackend.TRUFFLE, runtime.codegenBackend());
    }

    @Test
    void truffleArmThumbEmitterIsTruffleCodeEmitter() {
        JitRuntime runtime = TruffleJitRuntimeFactory.truffleArmThumb(16, 1);

        assertTrue(runtime.emitter() instanceof TruffleCodeEmitter);
    }

    @Test
    void truffleArmThumbAppliesGbaOptimizer() {
        // TruffleCodeEmitter não recebe otimizador no construtor (diferente do AsmCodeEmitter):
        // o pipeline "mesmo tier quente com otimizador GBA" da task A4 entra no nível do
        // JitRuntime em vez de dentro do emissor.
        JitRuntime runtime = TruffleJitRuntimeFactory.truffleArmThumb(16, 1);

        assertEquals(StandardIrOptimizer.gba().getClass(), runtime.optimizer().getClass());
    }

    @Test
    void truffleArmThumbAcceptsArchitectureOverload() {
        JitRuntime runtime = TruffleJitRuntimeFactory.truffleArmThumb(16, 1, ArmArchitecture.ARMV4T);

        assertEquals(CodegenBackend.TRUFFLE, runtime.codegenBackend());
    }

    @Test
    void truffleArmThumbTieredRuntimeExecutesAluBlock() {
        // Fluxo tiered de ponta a ponta: primeira visita roda no tier frio (interpretado, sem
        // classloading); visitas seguintes disparam a compilação em background pelo
        // TruffleCodeEmitter, que integra assim que pronta (timing não-determinístico — por isso
        // o teste repete execuções em vez de assumir QUAL tier rodou em cada uma). MOV r0, #5 ;
        // ADD r0, r0, #1: cada execução deve somar exatamente 6, frio ou quente (G1).
        JitRuntime runtime = TruffleJitRuntimeFactory.truffleArmThumb(16, 1);
        ByteArrayAddressSpace memory = new ByteArrayAddressSpace(64);
        memory.write32(0, 0xE3A00005); // MOV r0, #5
        memory.write32(4, 0xE2800001); // ADD r0, r0, #1
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        for (int i = 0; i < 20; i++) {
            core.setRegister(0, 0);
            runtime.execute(0, core);
            assertEquals(6, core.register(0), "iteração " + i);
        }
    }

    @Test
    void interpretedFallbackStillWorksAsColdTier() {
        InterpretedCodeEmitter reference = new InterpretedCodeEmitter(ArmArchitecture.ARMV4T);

        assertEquals(CodegenBackend.INTERPRETED_IR, reference.backend());
    }
}
