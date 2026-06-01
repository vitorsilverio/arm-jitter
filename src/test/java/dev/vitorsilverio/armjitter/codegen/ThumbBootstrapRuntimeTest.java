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

    @Test
    void executesThumbVisiblePcMovAndBxThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0x467B);
        memory.put16(2, 0x4718);
        memory.put32(4, 0xE3A0_0007);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));
        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(4, core.register(3));
        assertFalse(core.cpsr().isThumbMode());
        assertEquals(8, core.programCounter());
        assertEquals(7, core.register(0));
    }
}
