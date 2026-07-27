package dev.vitorsilverio.armjitter.jit64;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitRuntime64Test {
    private static final int MOVZ_X0_1 = 0xd2800020; // movz x0, #1
    /// Blocos de UMA instrução (`maxBlockInstructions=1`) — determinístico para os asserts de PC
    /// abaixo, já que a memória de teste está toda preenchida com `movz x0,#1` (nunca terminal).
    private static final int MAX_BLOCK_INSTRUCTIONS = 1;

    private static Aarch64Core newCore() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x100)));
        for (long addr = 0; addr < 0x100; addr += 4) {
            core.memory().write32(addr, MOVZ_X0_1);
        }
        return core;
    }

    private static JitRuntime64 newRuntime(ExecutionThreshold threshold) {
        return new JitRuntime64(
                new BlockCache64(),
                new StandardIr64BlockLifter(),
                new Ir64BlockExecutor(),
                new Asm64CodeEmitter(),
                threshold,
                MAX_BLOCK_INSTRUCTIONS);
    }

    @Test
    void staysColdUntilThreshold() {
        Aarch64Core core = newCore();
        JitRuntime64 runtime = newRuntime(new ExecutionThreshold(3));

        runtime.execute(0, core);
        runtime.execute(0, core);
        assertEquals(0, runtime.blockCache().size(), "não deveria ter compilado antes do threshold");

        runtime.execute(0, core);
        assertEquals(1, runtime.blockCache().size(), "deveria compilar na 3ª execução (threshold=3)");
    }

    @Test
    void thresholdOneCompilesOnFirstExecution() {
        Aarch64Core core = newCore();
        JitRuntime64 runtime = newRuntime(new ExecutionThreshold(1));

        runtime.execute(0, core);

        assertEquals(1, runtime.blockCache().size());
        assertEquals(1L, core.x(0));
        assertEquals(4L, core.pc());
    }

    @Test
    void cachedBlockIsReusedOnSubsequentExecutions() {
        Aarch64Core core = newCore();
        JitRuntime64 runtime = newRuntime(new ExecutionThreshold(1));

        runtime.execute(0, core);
        CompiledBlock64 first = runtime.blockCache().getOrNull(new BlockKey64(0));
        runtime.execute(0, core);
        CompiledBlock64 second = runtime.blockCache().getOrNull(new BlockKey64(0));

        assertSame(first, second);
    }
}
