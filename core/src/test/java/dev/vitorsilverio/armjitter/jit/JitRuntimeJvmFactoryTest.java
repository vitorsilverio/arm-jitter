package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JitRuntimeJvmFactoryTest {

    @Test
    void armThumbReportsJvmBytecodeBackend() {
        JitRuntime runtime = JitRuntimeFactory.armThumb(16, 1);

        assertEquals(CodegenBackend.JVM_BYTECODE, runtime.codegenBackend());
    }

    @Test
    void armThumbEmitterHasPerOpPolicy() {
        // PER_OP (Fase: execução condicional + compilação parcial): um bloco com uma op exótica
        // (DSP/Saturating/BLX/ShiftedRegister) compila o resto nativo em vez de cair inteiro.
        JitRuntime runtime = JitRuntimeFactory.armThumb(16, 1);

        AsmCodeEmitter emitter = (AsmCodeEmitter) runtime.emitter();
        assertEquals(AsmFallbackPolicy.PER_OP, emitter.policy());
    }

    @Test
    void jvmArmThumbStillWorksAndReportsJvmBackend() {
        @SuppressWarnings("deprecation")
        JitRuntime runtime = JitRuntimeFactory.jvmArmThumb(16, 1);

        assertEquals(CodegenBackend.JVM_BYTECODE, runtime.codegenBackend());
    }

    @Test
    void interpretedArmThumbReportsInterpretedBackend() {
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(CodegenBackend.INTERPRETED_IR, runtime.codegenBackend());
    }
}
