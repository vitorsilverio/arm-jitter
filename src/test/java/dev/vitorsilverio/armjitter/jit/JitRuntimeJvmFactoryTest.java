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
    void armThumbEmitterHasWholeBlockPolicy() {
        JitRuntime runtime = JitRuntimeFactory.armThumb(16, 1);

        AsmCodeEmitter emitter = (AsmCodeEmitter) runtime.emitter();
        assertEquals(AsmFallbackPolicy.WHOLE_BLOCK, emitter.policy());
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
