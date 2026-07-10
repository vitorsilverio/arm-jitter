package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwapInterpreterTest {
    @Test
    void executesArmSwp() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE100_1092);
        memory.write32(64, 0x1122_3344);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(2, 0xCAFE_BABE);

        core.step();

        assertEquals(0x1122_3344, core.register(1));
        assertEquals(0xCAFE_BABE, memory.read32(64));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmSwpb() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE140_1092);
        memory.write8(64, 0x7F);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(2, 0x1234_56A5);

        core.step();

        assertEquals(0x7F, core.register(1));
        assertEquals(0xA5, memory.read8(64));
        assertEquals(4, core.programCounter());
    }
}
