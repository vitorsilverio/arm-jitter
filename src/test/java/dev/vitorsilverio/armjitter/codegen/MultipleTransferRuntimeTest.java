package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultipleTransferRuntimeTest {
    @Test
    void executesThumbMultipleTransferThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0xC002);
        memory.put16(2, 0xCC04);
        memory.put16(4, 0xE000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 32);
        core.setRegister(1, 0x33);
        core.setRegister(4, 32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(3, core.runBlocks(runtime, 1));

        assertEquals(0x33, core.register(2));
        assertEquals(36, core.register(0));
        assertEquals(36, core.register(4));
    }
}
