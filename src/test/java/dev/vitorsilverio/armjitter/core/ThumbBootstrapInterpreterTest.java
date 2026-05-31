package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbBootstrapInterpreterTest {
    @Test
    void executesThumbShiftLogicAndLiteralLoad() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0x0081);
        memory.put16(2, 0x4041);
        memory.put16(4, 0x4800);
        memory.put32(8, 0xDEAD_BEEF);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 3);

        core.step(3);

        assertEquals(15, core.register(1));
        assertEquals(0xDEAD_BEEF, core.register(0));
    }

    @Test
    void executesThumbPushAndPopWithPc() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0xB503);
        memory.put16(2, 0xBD03);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 0x11);
        core.setRegister(1, 0x22);
        core.setRegister(13, 48);
        core.setRegister(14, 9);

        core.step();
        core.setRegister(0, 0);
        core.setRegister(1, 0);
        core.step();

        assertEquals(0x11, core.register(0));
        assertEquals(0x22, core.register(1));
        assertEquals(48, core.register(13));
        assertEquals(8, core.programCounter());
        assertTrue(core.cpsr().isThumbMode());
    }
}
