package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryInterpreterTest {
    @Test
    void executesArmStoreAndLoadImmediate() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE580_1004);
        memory.put32(4, 0xE590_2004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0x1234_5678);

        core.step(2);

        assertEquals(0x1234_5678, memory.read32(36));
        assertEquals(0x1234_5678, core.register(2));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesBxAndSwitchesToThumbMode() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12F_FF10);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 9);

        core.step();

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(8, core.programCounter());
    }
}
