package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbAddressingInterpreterTest {
    @Test
    void executesThumbAddressingBatch() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0x1888);
        memory.put16(2, 0x1E49);
        memory.put16(4, 0x8041);
        memory.put16(6, 0x8842);
        memory.put16(8, 0xA003);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 10);
        core.setRegister(1, 3);
        core.setRegister(2, 30);

        core.step(5);

        assertEquals(2, core.register(1));
        assertEquals(2, core.register(2));
        assertEquals(24, core.register(0));
    }
}
