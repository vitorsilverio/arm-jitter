package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// Reproduces rockwrestler's `init_stackpointers`: switch to IRQ mode via `msr cpsr_c`, set the
/// (banked) SP there, switch back, set the System SP. The two stack pointers must end up in their
/// own banks. A bug here parks SP_irq in the wrong place, which corrupts BIOS IRQ handling.
class MsrModeBankingTest {

    @Test
    void msrControlFieldBanksTheStackPointer() {
        TestAddressSpace memory = new TestAddressSpace(0x20);
        memory.put32(0x00, 0xE121F000); // msr cpsr_c, r0   (r0 = IRQ mode)
        memory.put32(0x04, 0xE1A0D001); // mov r13, r1       (IRQ SP)
        memory.put32(0x08, 0xE121F002); // msr cpsr_c, r2   (r2 = System mode)
        memory.put32(0x0C, 0xE1A0D003); // mov r13, r3       (System SP)

        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, true, true);
        core.setRegister(0, 0x000000D2); // IRQ mode (+ I/F)
        core.setRegister(1, 0x22220000); // intended SP_irq
        core.setRegister(2, 0x000000DF); // System mode (+ I/F)
        core.setRegister(3, 0x11110000); // intended SP_sys

        core.step();
        core.step();
        core.step();
        core.step();

        assertEquals(0x11110000, core.register(13), "System SP set after returning to System mode");
        assertEquals(0x22220000, core.bankedRegister(CpuMode.IRQ, 13), "IRQ SP banked while in IRQ mode");
    }

    @Test
    void setCpsrBanksTheStackPointerDirectly() {
        ArmCore core = new ArmCore(new TestAddressSpace(8), SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, true, true);
        core.setRegister(13, 0x11110000);
        core.setCpsr(0x000000D2); // -> IRQ
        core.setRegister(13, 0x22220000);
        core.setCpsr(0x000000DF); // -> System
        assertEquals(0x11110000, core.register(13));
        assertEquals(0x22220000, core.bankedRegister(CpuMode.IRQ, 13));
    }
}
