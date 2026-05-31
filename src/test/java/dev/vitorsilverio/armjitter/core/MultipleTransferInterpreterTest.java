package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultipleTransferInterpreterTest {
    @Test
    void executesArmStmiaAndLdmiaWithWriteback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE8A0_0002);
        memory.put32(4, 0xE8B4_0004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0x11);
        core.setRegister(4, 32);
        core.setRegister(2, 0);
        core.setRegister(3, 0);

        core.step(2);

        assertEquals(0x11, core.register(2));
        assertEquals(36, core.register(0));
        assertEquals(36, core.register(4));
    }

    @Test
    void executesThumbStmiaAndLdmiaWithWriteback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0xC002);
        memory.put16(2, 0xCC04);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 32);
        core.setRegister(1, 0x22);
        core.setRegister(4, 32);

        core.step(2);

        assertEquals(0x22, core.register(2));
        assertEquals(36, core.register(0));
        assertEquals(36, core.register(4));
    }
}
