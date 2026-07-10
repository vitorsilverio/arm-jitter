package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmThumbJitRuntimeTest {
    @Test
    void cachesArmAndThumbBlocksSeparatelyAtSamePc() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_202A);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        ArmCore armCore = new ArmCore(memory, SwiDispatcher.empty());
        runtime.execute(0, armCore);

        ArmCore thumbCore = new ArmCore(memory, SwiDispatcher.empty());
        thumbCore.cpsr().setThumbMode(true);
        runtime.execute(0, thumbCore);

        assertEquals(42, armCore.register(2));
        assertEquals(42, thumbCore.register(0));
        assertEquals(2, runtime.blockCache().size());
    }

    @Test
    void interpretedArmFactoryStillCompilesHotThumbBlocksAfterInterworking() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x0000);
        memory.put16(2, 0xE000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 0x1234);
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0x1234, core.register(0));
        assertEquals(6, core.programCounter());
        assertTrue(core.cpsr().isThumbMode());
    }
}
