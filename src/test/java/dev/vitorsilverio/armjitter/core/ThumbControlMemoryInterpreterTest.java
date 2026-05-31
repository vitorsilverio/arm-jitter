package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbControlMemoryInterpreterTest {
    @Test
    void executesThumbConditionalBranchWhenConditionPasses() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xD001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.cpsr().setNzcv(false, true, false, false);

        core.step();

        assertEquals(6, core.programCounter());
    }

    @Test
    void executesThumbByteStoreAndLoad() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0x7041);
        memory.put16(2, 0x7842);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 16);
        core.setRegister(1, 0xABCD);

        core.step(2);

        assertEquals(0xCD, memory.read8(17));
        assertEquals(0xCD, core.register(2));
    }
}
