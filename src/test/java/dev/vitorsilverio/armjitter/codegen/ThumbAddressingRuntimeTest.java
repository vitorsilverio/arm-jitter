package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbAddressingRuntimeTest {
    @Test
    void executesThumbHalfwordAndSpRelativeThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0x9001);
        memory.put16(2, 0x9801);
        memory.put16(4, 0xE000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 0x1234_5678);
        core.setRegister(13, 32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(3, core.runBlocks(runtime, 1));

        assertEquals(0x1234_5678, memory.read32(36));
        assertEquals(0x1234_5678, core.register(0));
    }
}
