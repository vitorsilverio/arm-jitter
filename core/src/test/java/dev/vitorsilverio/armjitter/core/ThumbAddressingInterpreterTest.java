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
        assertEquals(0x0200_0000, core.register(2));
        assertEquals(24, core.register(0));
    }

    @Test
    void rotatesMisalignedThumbWordLoadLikeArm7tdmi() {
        TestAddressSpace memory = thumbMisalignedWordLoadProgram();
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(3, 32);

        core.step(6);

        assertEquals(0xFF00_0000, core.register(0));
        assertEquals(0xFF00_0000, core.register(1));
        assertEquals(33, core.register(3));
    }

    @Test
    void rotatesMisalignedThumbHalfwordLoadLikeArm7tdmi() {
        TestAddressSpace memory = thumbMisalignedHalfwordLoadProgram();
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(3, 32);

        core.step(7);

        assertEquals(0xFF00_0000, core.register(1));
        assertEquals(0xFF00_0000, core.register(2));
        assertEquals(1, core.register(0));
    }

    static TestAddressSpace thumbMisalignedHalfwordLoadProgram() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0x2000);
        memory.put16(2, 0x21FF);
        memory.put16(4, 0x8019);
        memory.put16(6, 0x3001);
        memory.put16(8, 0x2208);
        memory.put16(10, 0x41D1);
        memory.put16(12, 0x5A1A);
        memory.put16(14, 0xE000);
        return memory;
    }

    static TestAddressSpace thumbMisalignedWordLoadProgram() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0x20FF);
        memory.put16(2, 0x6018);
        memory.put16(4, 0x2108);
        memory.put16(6, 0x41C8);
        memory.put16(8, 0x3301);
        memory.put16(10, 0x6819);
        memory.put16(12, 0xE000);
        return memory;
    }
}
