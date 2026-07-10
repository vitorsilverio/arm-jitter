package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbAddressingRuntimeTest {
    @Test
    void executesThumbHalfwordAndSpRelativeThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0x9001);
        memory.put16(2, 0x9801);
        memory.put16(4, 0xE000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 0x1234_5678);
        core.setRegister(13, 32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(3, core.runBlocks(runtime, 1));

        assertEquals(0x1234_5678, memory.read32(36));
        assertEquals(0x1234_5678, core.register(0));
    }

    @Test
    void rotatesMisalignedThumbWordLoadThroughRuntime() {
        TestAddressSpace memory = thumbMisalignedWordLoadProgram();
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(3, 32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        core.runBlock(runtime);

        assertEquals(0xFF00_0000, core.register(0));
        assertEquals(0xFF00_0000, core.register(1));
        assertEquals(33, core.register(3));
    }

    @Test
    void rotatesMisalignedThumbHalfwordLoadThroughRuntime() {
        TestAddressSpace memory = thumbMisalignedHalfwordLoadProgram();
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(3, 32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        core.runBlock(runtime);

        assertEquals(0xFF00_0000, core.register(1));
        assertEquals(0xFF00_0000, core.register(2));
        assertEquals(1, core.register(0));
    }

    private static TestAddressSpace thumbMisalignedHalfwordLoadProgram() {
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

    private static TestAddressSpace thumbMisalignedWordLoadProgram() {
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
