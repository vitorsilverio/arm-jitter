package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultipleTransferInterpreterTest {
    @Test
    void executesArmStmiaAndLdmiaWithWriteback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE8A0_0002);
        memory.put32(4, 0xE8B4_0004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0x11);
        core.setRegister(4, 32);
        core.setRegister(2, 0);
        core.setRegister(3, 0);

        core.step(2);

        assertEquals(0x11, core.register(2));
        assertEquals(36, core.register(0));
        assertEquals(36, core.register(4));
    }

    @Test
    void executesArmStmdbAndLdmiaAsStackRoundTrip() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE92D_0003);
        memory.put32(4, 0xE8BD_000C);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(13, 96);
        core.setRegister(0, 0x11);
        core.setRegister(1, 0x22);

        core.step(2);

        assertEquals(0x11, core.register(2));
        assertEquals(0x22, core.register(3));
        assertEquals(96, core.register(13));
    }

    @Test
    void executesArmStmibAndLdmdaWithWriteback() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE9A5_0003);
        memory.put32(4, 0xE835_000C);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(5, 48);
        core.setRegister(0, 0x33);
        core.setRegister(1, 0x44);

        core.step();

        assertEquals(56, core.register(5));
        core.step();

        assertEquals(0x33, core.register(2));
        assertEquals(0x44, core.register(3));
        assertEquals(48, core.register(5));
    }

    @Test
    void executesArmStmCaretUsingUserBank() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8C0_6000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(0, 64);
        core.setBankedRegister(CpuMode.USER, 13, 0xCAFE);
        core.setBankedRegister(CpuMode.USER, 14, 0xBABE);
        core.setRegister(13, 0x1111);
        core.setRegister(14, 0x2222);

        core.step();

        assertEquals(0xCAFE, memory.read32(64));
        assertEquals(0xBABE, memory.read32(68));
        assertEquals(0x1111, core.register(13));
        assertEquals(0x2222, core.register(14));
    }

    @Test
    void executesArmStmStoresPipelinePcPlusTwelve() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE880_8000);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);

        core.step();

        assertEquals(12, memory.read32(64));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmLdmCaretWithoutPcIntoUserBank() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8D0_6000);
        memory.write32(64, 0x1234);
        memory.write32(68, 0x5678);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setRegister(0, 64);

        core.step();

        assertEquals(0x1234, core.bankedRegister(CpuMode.USER, 13));
        assertEquals(0x5678, core.bankedRegister(CpuMode.USER, 14));
        assertNotEquals(0x1234, core.register(13));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmLdmCaretWithPcRestoresCpsrFromSpsr() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8D0_8002);
        memory.write32(64, 0x7777);
        memory.write32(68, 0x101);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setSpsr(CpuMode.SUPERVISOR, 0x30);
        core.setRegister(0, 64);

        core.step();

        assertEquals(0x7777, core.register(1));
        assertEquals(CpuMode.USER, core.mode());
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesArmLdmCaretWithPcAlignsAfterRestoringArmCpsr() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE8D0_8002);
        memory.write32(64, 0x7777);
        memory.write32(68, 0x103);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.switchMode(CpuMode.SUPERVISOR);
        core.setSpsr(CpuMode.SUPERVISOR, CpuMode.SYSTEM.bits());
        core.setRegister(0, 64);

        core.step();

        assertEquals(0x7777, core.register(1));
        assertEquals(CpuMode.SYSTEM, core.mode());
        assertFalse(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesThumbStmiaAndLdmiaWithWriteback() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put16(0, 0xC002);
        memory.put16(2, 0xCC04);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 32);
        core.setRegister(1, 0x22);
        core.setRegister(4, 32);

        core.step(2);

        assertEquals(0x22, core.register(2));
        assertEquals(36, core.register(0));
        assertEquals(36, core.register(4));
    }

    @Test
    void executesArmEmptyRegisterListQuirk() {
        TestAddressSpace memory = new TestAddressSpace(160);
        memory.put32(0, 0xE8A0_0000);
        memory.put32(4, 0xE8B1_0000);
        memory.write32(96, 0x105);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(1, 96);

        core.step();

        assertEquals(12, memory.read32(64));
        assertEquals(128, core.register(0));

        core.step();

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x104, core.programCounter());
        assertEquals(160, core.register(1));
    }

    @Test
    void executesThumbEmptyRegisterListQuirk() {
        TestAddressSpace memory = new TestAddressSpace(160);
        memory.put16(0, 0xC000);
        memory.put16(2, 0xC900);
        memory.write32(96, 0x45);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        core.setRegister(0, 64);
        core.setRegister(1, 96);

        core.step();

        assertEquals(4, memory.read32(64));
        assertEquals(128, core.register(0));

        core.step();

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x44, core.programCounter());
        assertEquals(160, core.register(1));
    }
}
