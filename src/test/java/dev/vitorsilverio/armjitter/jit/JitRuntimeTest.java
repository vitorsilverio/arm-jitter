package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitRuntimeTest {
    @Test
    void interpretsColdThenCachesHotBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 2);

        assertEquals(1, runtime.execute(0, core));
        assertEquals(10, core.register(0));
        assertEquals(0, runtime.blockCache().size());

        core.setRegister(0, 0);
        assertEquals(2, runtime.execute(0, core));
        assertEquals(15, core.register(0));
        assertEquals(1, runtime.blockCache().size());

        core.setRegister(0, 0);
        assertEquals(2, runtime.execute(0, core));
        assertEquals(15, core.register(0));
    }

    @Test
    void compilesBlockExplicitly() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_002A);
        memory.put32(4, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 1);

        CompiledBlock block = runtime.compile(0, memory);
        int cycles = block.execute(core);

        assertEquals(42, core.register(0));
        assertEquals(4, core.programCounter());
        assertEquals(1, cycles);
    }
}
