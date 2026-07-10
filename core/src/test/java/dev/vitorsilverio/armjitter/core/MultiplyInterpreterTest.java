package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultiplyInterpreterTest {
    @Test
    void executesArmAndThumbMul() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE001_0290);
        memory.put16(8, 0x4348);
        ArmCore arm = new ArmCore(memory, SwiDispatcher.empty());
        arm.setRegister(0, 6);
        arm.setRegister(2, 7);

        arm.step();

        assertEquals(42, arm.register(1));

        ArmCore thumb = new ArmCore(memory, SwiDispatcher.empty());
        thumb.cpsr().setThumbMode(true);
        thumb.setProgramCounter(8);
        thumb.setRegister(0, 6);
        thumb.setRegister(1, 7);

        thumb.step();

        assertEquals(42, thumb.register(0));
    }

    @Test
    void executesArmMla() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE021_3290);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 6);
        core.setRegister(2, 7);
        core.setRegister(3, 5);

        core.step();

        assertEquals(47, core.register(1));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmUnsignedLongMultiply() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE083_2190);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 0xFFFF_FFFF);
        core.setRegister(1, 2);

        core.step();

        assertEquals(0xFFFF_FFFE, core.register(2));
        assertEquals(1, core.register(3));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmSignedLongMultiply() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE0C3_2190);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, -2);
        core.setRegister(1, 3);

        core.step();

        assertEquals(-6, core.register(2));
        assertEquals(-1, core.register(3));
    }

    @Test
    void executesArmLongMultiplyAccumulate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE0A3_2190);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 2);
        core.setRegister(1, 3);
        core.setRegister(2, 4);
        core.setRegister(3, 0);

        core.step();

        assertEquals(10, core.register(2));
        assertEquals(0, core.register(3));
    }
}
