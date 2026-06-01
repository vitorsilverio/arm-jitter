package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PsrInterpreterTest {
    @Test
    void executesMrsFromCpsrAndSpsr() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE10F_1000);
        memory.put32(4, 0xE14F_2000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.cpsr().setNzcv(true, false, true, false);
        core.setSpsr(CpuMode.SUPERVISOR, 0x6000_001F);

        core.step(2);

        assertEquals(core.cpsr().get(), core.register(1));
        assertEquals(0x6000_001F, core.register(2));
    }

    @Test
    void executesMsrToCpsrFlagsAndControl() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE129_F003);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(3, 0xA000_0013);

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode());
        assertTrue(core.cpsr().negative());
        assertFalse(core.cpsr().zero());
        assertTrue(core.cpsr().carry());
    }

    @Test
    void executesMsrImmediateToCpsrControl() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE321_F013);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        core.step();

        assertEquals(CpuMode.SUPERVISOR, core.mode());
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesMsrToSpsrFlagsAndControl() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE169_F003);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setSpsr(CpuMode.SUPERVISOR, 0);
        core.setRegister(3, 0xF000_001F);

        core.step();

        assertEquals(0xF000_001F, core.spsr(CpuMode.SUPERVISOR));
    }
}
