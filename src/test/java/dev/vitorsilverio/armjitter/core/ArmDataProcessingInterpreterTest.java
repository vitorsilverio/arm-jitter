package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmDataProcessingInterpreterTest {
    @Test
    void executesArmLogicalAndTestOperations() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE200_100F);
        memory.put32(4, 0xE3C0_200F);
        memory.put32(8, 0xE310_000F);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 0xF3);

        core.step(3);

        assertEquals(0x03, core.register(1));
        assertEquals(0xF0, core.register(2));
        assertFalse(core.cpsr().zero());
    }
}
