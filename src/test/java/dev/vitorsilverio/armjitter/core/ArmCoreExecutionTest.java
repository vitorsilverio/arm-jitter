package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmCoreExecutionTest {
    @Test
    void stepsMultipleInstructionsWithInterpreter() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        assertEquals(2, core.step(2));

        assertEquals(3, core.register(0));
        assertEquals(8, core.programCounter());
    }

    @Test
    void runsSingleBlockWithRuntime() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(3, core.register(0));
        assertEquals(8, core.programCounter());
    }
}
