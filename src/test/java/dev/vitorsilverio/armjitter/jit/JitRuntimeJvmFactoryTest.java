package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JitRuntimeJvmFactoryTest {
    @Test
    void jvmFactoryReportsJvmBytecodeBackend() {
        JitRuntime runtime = JitRuntimeFactory.jvmArmThumb(16, 1);

        assertEquals(CodegenBackend.JVM_BYTECODE, runtime.codegenBackend());
    }
}
