package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmCoreSleepTest {
    @Test
    void haltPreventsInstructionExecutionUntilWake() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        core.halt();
        core.step();

        assertEquals(CpuSleepState.HALTED, core.sleepState());
        assertEquals(0, core.register(0));
        assertEquals(0, core.programCounter());
        assertEquals(1, core.cycles());

        core.wake();
        core.step();

        assertEquals(CpuSleepState.RUNNING, core.sleepState());
        assertEquals(1, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void assertedInterruptLineWakesHaltedCoreAndServicesIrqWhenEnabled() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setIrqDisabled(false);
        core.setBankedRegister(CpuMode.IRQ, 13, 0x4000);

        core.halt();
        core.setInterruptLine(true);
        core.step();

        assertEquals(CpuSleepState.RUNNING, core.sleepState());
        assertEquals(CpuMode.IRQ, core.mode());
        assertEquals(0x18, core.programCounter());
        assertEquals(0, core.register(0));
    }

    @Test
    void stopPreventsRuntimeBlockExecutionUntilWake() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        core.stop();
        int cycles = core.runBlock(dev.vitorsilverio.armjitter.jit.JitRuntimeFactory.interpretedArmThumb(16, 1));

        assertEquals(1, cycles);
        assertEquals(CpuSleepState.STOPPED, core.sleepState());
        assertEquals(0, core.register(0));
        assertEquals(0, core.programCounter());
    }
}
