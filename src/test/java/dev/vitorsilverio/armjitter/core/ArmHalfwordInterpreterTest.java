package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmHalfwordInterpreterTest {
    @Test
    void executesArmHalfwordStoreAndLoadImmediate() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE1C0_10B4);
        memory.put32(4, 0xE1D0_20B4);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0x1234_ABCD);

        core.step(2);

        assertEquals(0xABCD, memory.read16(36));
        assertEquals(0xABCD, core.register(2));
    }
}
