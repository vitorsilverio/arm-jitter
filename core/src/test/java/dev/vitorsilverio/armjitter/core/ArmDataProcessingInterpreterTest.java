package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmDataProcessingInterpreterTest {
    @Test
    void executesArmLogicalAndTestOperations() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE200_100F);
        memory.put32(4, 0xE3C0_200F);
        memory.put32(8, 0xE310_000F);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 0xF3);

        core.step(3);

        assertEquals(0x03, core.register(1));
        assertEquals(0xF0, core.register(2));
        assertFalse(core.cpsr().zero());
    }

    @Test
    void executesArmReverseSubtract() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE260_100A);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 3);

        core.step();

        assertEquals(7, core.register(1));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmReverseSubtractWithCarry() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE2E0_100A);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 3);
        core.cpsr().setNzcv(false, false, false, false);

        core.step();

        assertEquals(6, core.register(1));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmRegisterOperandWithImmediateShift() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE081_0102);
        memory.put32(4, 0xE1A0_30A2);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 3);
        core.setRegister(2, 8);

        core.step(2);

        assertEquals(35, core.register(0));
        assertEquals(4, core.register(3));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmRegisterOperandWithRegisterShift() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE081_0312);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 5);
        core.setRegister(2, 3);
        core.setRegister(3, 2);

        core.step();

        assertEquals(17, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmRrxOperand() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE1A0_0061);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 2);
        core.cpsr().setNzcv(false, false, true, false);

        core.step();

        assertEquals(0x8000_0001, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void updatesCarryFromArmLogicalShifterOperand() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE1B0_00A1);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(1, 3);
        core.cpsr().setNzcv(false, false, false, false);

        core.step();

        assertEquals(1, core.register(0));
        assertTrue(core.cpsr().carry());
    }

    @Test
    void executesArmMovIntoPcAsBranch() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE1A0_F00E);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(14, 0x103);

        core.step();

        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesArmSubsIntoPcAsExceptionReturn() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE25E_F004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setSpsr(CpuMode.SUPERVISOR, 0x30);
        core.setRegister(14, 0x105);

        core.step();

        assertEquals(CpuMode.USER, core.mode());
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesArmClz() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE16F_1F10);
        // CLZ é uma instrução ARMv5, então o core precisa usar uma arquitetura ARMv5.
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.setRegister(0, 0x0000_8000);

        core.step();

        assertEquals(16, core.register(1));
        assertEquals(4, core.programCounter());
    }
}
