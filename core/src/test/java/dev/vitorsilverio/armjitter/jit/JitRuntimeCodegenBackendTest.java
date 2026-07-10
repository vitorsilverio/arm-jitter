package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JitRuntimeCodegenBackendTest {
    @Test
    void interpretedFactoryReportsInterpretedIrBackend() {
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(CodegenBackend.INTERPRETED_IR, runtime.codegenBackend());
        assertEquals(CodegenBackend.INTERPRETED_IR, runtime.emitter().backend());
    }
}
