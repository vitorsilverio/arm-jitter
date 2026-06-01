package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwapRuntimeTest {
    @Test
    void executesArmSwpThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE100_1092);
        memory.put32(4, 0xE7F0_00F0);
        memory.write32(64, 0x1122_3344);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(2, 0xCAFE_BABE);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x1122_3344, core.register(1));
        assertEquals(0xCAFE_BABE, memory.read32(64));
    }
}
