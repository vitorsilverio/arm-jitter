package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbBootstrapRuntimeTest {
    @Test
    void executesThumbBootstrapOpsThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0x0081);
        memory.put16(2, 0x4041);
        memory.put16(4, 0x4800);
        memory.put32(8, 0xCAFE_BABE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 3);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(4, core.runBlocks(runtime, 1));

        assertEquals(15, core.register(1));
        assertEquals(0xCAFE_BABE, core.register(0));
        assertEquals(8, core.programCounter());
    }
}
