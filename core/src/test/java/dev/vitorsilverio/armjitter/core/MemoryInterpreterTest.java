package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryInterpreterTest {
    @Test
    void executesArmStoreAndLoadImmediate() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE580_1004);
        memory.put32(4, 0xE590_2004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 32);
        core.setRegister(1, 0x1234_5678);

        core.step(2);

        assertEquals(0x1234_5678, memory.read32(36));
        assertEquals(0x1234_5678, core.register(2));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmStoreAndLoadRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE780_2001);
        memory.put32(4, 0xE790_3001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 12);
        core.setRegister(2, 0x1020_3040);

        core.step(2);

        assertEquals(0x1020_3040, memory.read32(60));
        assertEquals(0x1020_3040, core.register(3));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmSubtractRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE700_2001);
        memory.put32(4, 0xE710_3001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(1, 4);
        core.setRegister(2, 0x0102_0304);

        core.step(2);

        assertEquals(0x0102_0304, memory.read32(60));
        assertEquals(0x0102_0304, core.register(3));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmShiftedRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE780_2101);
        memory.put32(4, 0xE790_3101);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 3);
        core.setRegister(2, 0x1122_3344);

        core.step(2);

        assertEquals(0x1122_3344, memory.read32(60));
        assertEquals(0x1122_3344, core.register(3));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmRrxRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE790_2061);
        memory.write32(72, 0x5566_7788);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(1, 16);
        core.cpsr().setNzcv(false, false, false, false);

        core.step();

        assertEquals(0x5566_7788, core.register(2));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmPreIndexedWriteback() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE5A0_1004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 0x1020_3040);

        core.step();

        assertEquals(0x1020_3040, memory.read32(52));
        assertEquals(52, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmPostIndexedLoadAndStoreImmediate() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE480_1004);
        memory.put32(4, 0xE493_2004);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 48);
        core.setRegister(1, 0x1020_3040);
        core.setRegister(3, 48);

        core.step(2);

        assertEquals(0x1020_3040, memory.read32(48));
        assertEquals(0x1020_3040, core.register(2));
        assertEquals(52, core.register(0));
        assertEquals(52, core.register(3));
        assertEquals(8, core.programCounter());
    }

    @Test
    void executesArmPostIndexedSignedHalfLoad() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE0D0_20F4);
        memory.write16(64, 0xFF80);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);

        core.step();

        assertEquals(-128, core.register(2));
        assertEquals(68, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmLoadIntoPc() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE590_F000);
        memory.write32(64, 0x101);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);

        core.step();

        // ARMv4T (ARM7TDMI): LDR para o PC não faz interworking; o bit 0 é ignorado e
        // a CPU permanece em estado ARM. Loads com interworking só existem a partir do ARMv5T.
        assertFalse(core.cpsr().isThumbMode());
        assertEquals(0x100, core.programCounter());
    }

    @Test
    void executesArmPcRelativeLoadUsingPipelinePc() {
        TestAddressSpace memory = new TestAddressSpace(64);
        memory.put32(0, 0xE59F_1004);
        memory.write32(12, 0xCAFE_BABE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        core.step();

        assertEquals(0xCAFE_BABE, core.register(1));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmUnalignedWordLoadWithRotation() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put32(0, 0xE590_1000);
        memory.write32(64, 0x1122_3344);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 65);

        core.step();

        assertEquals(0x4411_2233, core.register(1));
        assertEquals(4, core.programCounter());
    }

    @Test
    void executesArmSignedLoadsWithRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE190_20D1);
        memory.put32(4, 0xE190_30F1);
        memory.put16(65, 0xFF80); // configuração bruta: byte 0x80 no endereço ímpar 65
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 64);
        core.setRegister(1, 1);

        core.step(2);

        assertEquals(-128, core.register(2));
        assertEquals(-128, core.register(3));
        assertEquals(8, core.programCounter());
    }

    @Test
    void approximatesArm7HalfwordAlignmentRules() {
        TestAddressSpace memory = new TestAddressSpace(96);
        memory.put32(0, 0xE1D0_10B0);
        memory.put32(4, 0xE1D0_20F0);
        memory.put32(8, 0xE1C0_30B0);
        memory.write16(64, 0x1234);
        memory.write8(65, 0x80);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 65);
        core.setRegister(3, 0xABCD);

        core.step(3);

        assertEquals(0x3400_0080, core.register(1));
        assertEquals(-128, core.register(2));
        assertEquals(0xABCD, memory.read16(64));
    }

    @Test
    void executesBxAndSwitchesToThumbMode() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE12F_FF10);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(0, 9);

        core.step();

        assertTrue(core.cpsr().isThumbMode());
        assertEquals(8, core.programCounter());
    }
}
