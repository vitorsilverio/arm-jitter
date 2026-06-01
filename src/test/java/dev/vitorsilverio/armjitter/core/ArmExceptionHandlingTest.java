package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmExceptionHandlingTest {
    private static final int RESET_CPSR = CpuMode.SUPERVISOR.bits()
            | CpsrRegister.IRQ_DISABLE_FLAG
            | CpsrRegister.FIQ_DISABLE_FLAG;

    @Test
    void startsInArmResetState() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());

        assertEquals(CpuMode.SUPERVISOR, core.mode());
        assertEquals(0, core.programCounter());
        assertTrue(core.cpsr().irqDisabled());
        assertTrue(core.cpsr().fiqDisabled());
        assertFalse(core.cpsr().isThumbMode());
    }

    @Test
    void configuresExecutionStateForBootHandoff() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.setRegister(13, 0x0300_7FE0);
        core.setBankedRegister(CpuMode.SYSTEM, 13, 0x0300_7F00);

        core.configureExecutionState(0x0800_0000, CpuMode.SYSTEM, InstructionSet.ARM, false, true);

        assertEquals(0x0800_0000, core.programCounter());
        assertEquals(CpuMode.SYSTEM, core.mode());
        assertEquals(0x0300_7F00, core.register(13));
        assertFalse(core.cpsr().isThumbMode());
        assertFalse(core.cpsr().irqDisabled());
        assertTrue(core.cpsr().fiqDisabled());
        assertEquals(0x0300_7FE0, core.bankedRegister(CpuMode.SUPERVISOR, 13));
    }

    @Test
    void setCpsrSwitchesRegisterBanks() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.setRegister(13, 0x1111);
        core.setBankedRegister(CpuMode.IRQ, 13, 0x2222);

        core.setCpsr(CpuMode.IRQ.bits() | CpsrRegister.IRQ_DISABLE_FLAG);

        assertEquals(CpuMode.IRQ, core.mode());
        assertEquals(0x2222, core.register(13));
        assertEquals(0x1111, core.bankedRegister(CpuMode.SUPERVISOR, 13));
        assertTrue(core.cpsr().irqDisabled());
    }

    @Test
    void switchesSpLrBanksWhenModeChanges() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.switchMode(CpuMode.SYSTEM);
        core.setRegister(8, 0x88);
        core.setRegister(13, 0x1000);
        core.setRegister(14, 0x2000);

        core.setBankedRegister(CpuMode.IRQ, 13, 0x3000);
        core.setBankedRegister(CpuMode.IRQ, 14, 0x4000);
        core.switchMode(CpuMode.IRQ);

        assertEquals(CpuMode.IRQ, core.mode());
        assertEquals(0x3000, core.register(13));
        assertEquals(0x4000, core.register(14));
        assertEquals(0x88, core.register(8));

        core.setRegister(13, 0x3333);
        core.switchMode(CpuMode.SYSTEM);

        assertEquals(0x1000, core.register(13));
        assertEquals(0x2000, core.register(14));
        assertEquals(0x3333, core.bankedRegister(CpuMode.IRQ, 13));
    }

    @Test
    void switchesFiqR8ToR14Bank() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.switchMode(CpuMode.SYSTEM);
        core.setRegister(8, 0x100);
        core.setRegister(13, 0x200);
        core.setBankedRegister(CpuMode.FIQ, 8, 0xF00);
        core.setBankedRegister(CpuMode.FIQ, 13, 0xF13);

        core.switchMode(CpuMode.FIQ);

        assertEquals(0xF00, core.register(8));
        assertEquals(0xF13, core.register(13));

        core.switchMode(CpuMode.SYSTEM);

        assertEquals(0x100, core.register(8));
        assertEquals(0x200, core.register(13));
    }

    @Test
    void swiWithoutHostHandlerEntersSupervisorVector() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xEF00_0008);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(13, 0x1000);
        core.setBankedRegister(CpuMode.SUPERVISOR, 13, 0x3000);

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode());
        assertEquals(0x08, core.programCounter());
        assertEquals(4, core.register(14));
        assertEquals(0x3000, core.register(13));
        assertEquals(RESET_CPSR, core.spsr(CpuMode.SUPERVISOR));
        assertTrue(core.cpsr().irqDisabled());
        assertFalse(core.cpsr().isThumbMode());
    }

    @Test
    void unimplementedArmInstructionEntersUndefinedVector() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xEE00_0000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setBankedRegister(CpuMode.UNDEFINED, 13, 0x5000);

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
        assertEquals(4, core.register(14));
        assertEquals(0x5000, core.register(13));
        assertEquals(RESET_CPSR, core.spsr(CpuMode.UNDEFINED));
        assertTrue(core.cpsr().irqDisabled());
        assertFalse(core.cpsr().isThumbMode());
    }

    @Test
    void unimplementedThumbInstructionEntersUndefinedVector() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0xB600);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
        assertEquals(2, core.register(14));
        assertTrue(core.cpsr().irqDisabled());
        assertFalse(core.cpsr().isThumbMode());
    }

    @Test
    void reservedThumbConditionalBranchEntersUndefinedVector() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0xDE00);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode());
        assertEquals(0x04, core.programCounter());
        assertEquals(2, core.register(14));
        assertFalse(core.cpsr().isThumbMode());
    }

    @Test
    void pendingIrqEntersIrqVectorBeforeNextInstruction() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setBankedRegister(CpuMode.IRQ, 13, 0x4000);
        core.cpsr().setIrqDisabled(false);
        core.setInterruptLine(true);

        core.step();

        assertEquals(CpuMode.IRQ, core.mode());
        assertEquals(0x18, core.programCounter());
        assertEquals(4, core.register(14));
        assertEquals(0x4000, core.register(13));
        assertEquals(CpuMode.SUPERVISOR.bits() | CpsrRegister.FIQ_DISABLE_FLAG, core.spsr(CpuMode.IRQ));
        assertEquals(0, core.register(0));
    }
}
