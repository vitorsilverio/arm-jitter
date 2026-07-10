package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbLongBranchRuntimeTest {
    @Test
    void executesThumbBlPairThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0xF801);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(6, core.programCounter());
        assertEquals(5, core.register(14));
    }
}
