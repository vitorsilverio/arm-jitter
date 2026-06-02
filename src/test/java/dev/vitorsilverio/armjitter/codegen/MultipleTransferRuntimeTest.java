package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.IrOptimizer;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.jit.BlockCache;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultipleTransferRuntimeTest {
    @Test
    void executesArmDecrementBeforeStackTransferThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE92D_0003);
        memory.put32(4, 0xE8BD_000C);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(13, 96);
        core.setRegister(0, 0x55);
        core.setRegister(1, 0x66);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(0x55, core.register(2));
        assertEquals(0x66, core.register(3));
        assertEquals(96, core.register(13));
    }

    @Test
    void executesArmLdmCaretWithPcThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8D0_8002);
        memory.write32(64, 0x7777);
        memory.write32(68, 0x101);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setSpsr(CpuMode.SUPERVISOR, 0x30);
        core.setRegister(0, 64);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x7777, core.register(1));
        assertEquals(CpuMode.USER, core.mode());
        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesArmLdmCaretWithArmReturnThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8D0_8002);
        memory.write32(64, 0x7777);
        memory.write32(68, 0x103);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setSpsr(CpuMode.SUPERVISOR, CpuMode.SYSTEM.bits());
        core.setRegister(0, 64);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x7777, core.register(1));
        assertEquals(CpuMode.SYSTEM, core.mode());
        assertFalse(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesArmStmStoresPipelinePcThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE3A0_0040);
        memory.put32(4, 0xE880_8000);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(16, memory.read32(64));
        assertEquals(8, core.programCounter());
    }

    @Test
    void handlesArmLdmBaseInListThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8B0_0003);
        memory.write32(64, 0x1111);
        memory.write32(68, 0x2222);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        JitRuntime runtime = singleInstructionRuntime();

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0x1111, core.register(0));
        assertEquals(0x2222, core.register(1));
    }

    @Test
    void handlesArmStmBaseInListThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8A1_0003);
        memory.put32(4, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 0xAAAA);
        core.setRegister(1, 64);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(0xAAAA, memory.read32(64));
        assertEquals(72, memory.read32(68));
        assertEquals(72, core.register(1));
    }

    @Test
    void executesThumbMultipleTransferThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0xC002);
        memory.put16(2, 0xCC04);
        memory.put16(4, 0xE000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 32);
        core.setRegister(1, 0x33);
        core.setRegister(4, 32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(3, core.runBlocks(runtime, 1));

        assertEquals(0x33, core.register(2));
        assertEquals(36, core.register(0));
        assertEquals(36, core.register(4));
    }

    @Test
    void executesArmEmptyRegisterListThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(160);
        memory.put32(0, 0xE8A0_0000);
        memory.put32(4, 0xE8B1_0000);
        memory.write32(96, 0x105);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(1, 96);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(12, memory.read32(64));
        assertEquals(128, core.register(0));
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x104, core.programCounter());
        assertEquals(160, core.register(1));
    }

    @Test
    void executesThumbEmptyRegisterListThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(160);
        memory.put16(0, 0xC000);
        memory.put16(2, 0xC900);
        memory.write32(96, 0x45);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 64);
        core.setRegister(1, 96);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(4, memory.read32(64));
        assertEquals(128, core.register(0));
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x44, core.programCounter());
        assertEquals(160, core.register(1));
    }

    private JitRuntime singleInstructionRuntime() {
        return new JitRuntime(
                new BlockCache(16),
                new ArmDecoder(),
                new ThumbDecoder(),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                new InterpretedCodeEmitter(),
                new ExecutionThreshold(1),
                1);
    }
}
