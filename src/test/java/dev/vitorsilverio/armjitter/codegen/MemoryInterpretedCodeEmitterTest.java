package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryInterpretedCodeEmitterTest {
    @Test
    void executesArmMemoryOpsThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE580_1004);
        memory.put32(4, 0xE590_2004);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0xCAFE_BABE);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0xCAFE_BABE, memory.read32(36));
        assertEquals(0xCAFE_BABE, core.register(2));
    }

    @Test
    void executesArmRegisterOffsetMemoryOpsThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE780_2001);
        memory.put32(4, 0xE790_3001);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 12);
        core.setRegister(2, 0x1020_3040);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0x1020_3040, memory.read32(60));
        assertEquals(0x1020_3040, core.register(3));
    }

    @Test
    void executesArmShiftedRegisterOffsetThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE780_2101);
        memory.put32(4, 0xE790_3101);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 3);
        core.setRegister(2, 0x1122_3344);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0x1122_3344, memory.read32(60));
        assertEquals(0x1122_3344, core.register(3));
    }

    @Test
    void executesArmRrxRegisterOffsetThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE790_2061);
        memory.put32(4, 0xE7F0_00F0);
        memory.write32(72, 0x5566_7788);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(1, 16);
        core.cpsr().setNzcv(false, false, false, false);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x5566_7788, core.register(2));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmPreIndexedWritebackThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE5A0_1004);
        memory.put32(4, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 0x1020_3040);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x1020_3040, memory.read32(52));
        assertEquals(52, core.register(0));
    }

    @Test
    void executesArmPostIndexedMemoryOpsThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE480_1004);
        memory.put32(4, 0xE493_2004);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 0x1020_3040);
        core.setRegister(3, 48);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0x1020_3040, memory.read32(48));
        assertEquals(0x1020_3040, core.register(2));
        assertEquals(52, core.register(0));
        assertEquals(52, core.register(3));
    }

    @Test
    void executesArmUnalignedLoadThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE590_1000);
        memory.put32(4, 0xE7F0_00F0);
        memory.write32(64, 0x1122_3344);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 65);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x4411_2233, core.register(1));
    }

    @Test
    void keepsLoadedPcAsRuntimeBlockExit() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE590_F000);
        memory.put32(4, 0xE3A0_0001);
        memory.write32(64, 0x101);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
        assertEquals(0, core.register(1));
    }

    @Test
    void keepsLiteralLoadedPcAsRuntimeBlockExit() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE59F_F000);
        memory.put32(4, 0xE3A0_0001);
        memory.write32(8, 0x81);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x80, core.programCounter());
        assertEquals(0, core.register(1));
    }

    @Test
    void executesArmPcRelativeLoadThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE59F_1000);
        memory.put32(8, 0xE7F0_00F0);
        memory.write32(12, 0xCAFE_BABE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(1, core.register(0));
        assertEquals(0xCAFE_BABE, core.register(1));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmStorePcThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE3A0_0010);
        memory.put32(4, 0xE580_F000);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(12, memory.read32(16));
        assertEquals(8, core.programCounter());
    }

    @Test
    void approximatesArm7HalfwordAlignmentThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE1D0_10B0);
        memory.put32(4, 0xE1D0_20F0);
        memory.put32(8, 0xE1C0_30B0);
        memory.put32(12, 0xE7F0_00F0);
        memory.write16(64, 0x1234);
        memory.write8(65, 0x80);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 65);
        core.setRegister(3, 0xABCD);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(3, core.runBlocks(runtime, 1));

        assertEquals(0x8034, core.register(1));
        assertEquals(-128, core.register(2));
        assertEquals(0xABCD, memory.read16(64));
    }

    @Test
    void executesBxThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12F_FF10);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 9);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(8, core.programCounter());
    }
}
