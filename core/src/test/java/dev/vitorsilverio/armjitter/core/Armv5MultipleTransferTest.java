package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// LDM com writeback e o registrador base dentro da lista: o ARMv5 ainda faz writeback a menos que
/// a base seja o registrador mais alto de uma transferência multi-registrador (aí o valor carregado
/// prevalece). O ARMv4 sempre mantém o valor carregado.
class Armv5MultipleTransferTest {
    private static final int W0 = 0xC0FFEEAB;
    private static final int W1 = 0xDEADBEEF;
    private static final int W2 = 0xD00DFEED;

    private static ArmCore boot(ArmArchitecture arch, int instruction) {
        TestAddressSpace memory = new TestAddressSpace(0x200);
        memory.put32(0, instruction);
        memory.put32(0x100, W0);
        memory.put32(0x104, W1);
        memory.put32(0x108, W2);
        return new ArmCore(memory, SwiDispatcher.empty(), arch);
    }

    @Test
    void baseAloneWritesBack() {
        ArmCore core = boot(ArmArchitecture.ARMV5TE, 0xE8B1_0002); // ldmia r1!, {r1}
        core.setRegister(1, 0x100);
        core.step();
        assertEquals(0x104, core.register(1), "single-register list -> writeback wins");
    }

    @Test
    void baseHighestOfManyKeepsLoadedValue() {
        ArmCore core = boot(ArmArchitecture.ARMV5TE, 0xE8B3_000E); // ldmia r3!, {r1-r3}
        core.setRegister(3, 0x100);
        core.step();
        assertEquals(W0, core.register(1));
        assertEquals(W1, core.register(2));
        assertEquals(W2, core.register(3), "base is highest of the list -> loaded value wins");
    }

    @Test
    void baseInTheMiddleWritesBack() {
        ArmCore core = boot(ArmArchitecture.ARMV5TE, 0xE8B2_000E); // ldmia r2!, {r1-r3}
        core.setRegister(2, 0x100);
        core.step();
        assertEquals(W0, core.register(1));
        assertEquals(W2, core.register(3));
        assertEquals(0x10C, core.register(2), "base not the highest -> writeback wins");
    }

    @Test
    void emptyListTransfersNothingButAdvancesBaseByThe16WordCount() {
        ArmCore core = boot(ArmArchitecture.ARMV5TE, 0xE8B2_0000); // ldmia r2!, {} (empty list)
        core.setRegister(2, 0x100);
        core.step();
        assertEquals(0x140, core.register(2), "empty list still bumps the base by 0x40");
        assertEquals(4, core.programCounter(), "ARMv5 transfers no register (PC was not loaded)");
    }

    @Test
    void armv4AlwaysKeepsLoadedValue() {
        ArmCore core = boot(ArmArchitecture.ARMV4T, 0xE8B1_0002); // ldmia r1!, {r1}
        core.setRegister(1, 0x100);
        core.step();
        assertEquals(W0, core.register(1), "ARMv4 suppresses writeback when the base is in the list");
    }

    @Test
    void stmBaseInListStoresOriginalOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(0x200);
        memory.put32(0, 0xE8A3_000E); // stmia r3!, {r1-r3}
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.setRegister(1, 0xAAAA);
        core.setRegister(2, 0xBBBB);
        core.setRegister(3, 0x100);
        core.step();
        assertEquals(0x100, memory.read32(0x108), "the base stores its original value on ARMv5");
        assertEquals(0x10C, core.register(3), "base written back by three words");
    }

    @Test
    void stmBaseInListStoresWrittenBackValueOnArmv4() {
        TestAddressSpace memory = new TestAddressSpace(0x200);
        memory.put32(0, 0xE8A3_000E); // stmia r3!, {r1-r3}
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV4T);
        core.setRegister(1, 0xAAAA);
        core.setRegister(2, 0xBBBB);
        core.setRegister(3, 0x100);
        core.step();
        assertEquals(0x10C, memory.read32(0x108), "ARMv4 stores the incremented base when not first");
    }
}
