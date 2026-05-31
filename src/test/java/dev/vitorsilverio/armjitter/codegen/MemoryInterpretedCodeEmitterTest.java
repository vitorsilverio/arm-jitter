package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryInterpretedCodeEmitterTest {
    @Test
    void executesArmMemoryOpsThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE580_1004);
        memory.put32(4, 0xE590_2004);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0xCAFE_BABE);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0xCAFE_BABE, memory.read32(36));
        assertEquals(0xCAFE_BABE, core.register(2));
    }

    @Test
    void executesBxThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12F_FF10);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 9);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(8, core.programCounter());
    }
}
