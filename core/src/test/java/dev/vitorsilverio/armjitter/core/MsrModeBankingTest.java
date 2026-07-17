package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// Reproduz o `init_stackpointers` do rockwrestler: troca para o modo IRQ via `msr cpsr_c`, ajusta
/// o SP (banked) lá, volta, ajusta o SP do System. Os dois stack pointers precisam terminar em seus
/// próprios bancos. Um bug aqui estaciona o SP_irq no lugar errado, o que corrompe o tratamento de IRQ da BIOS.
class MsrModeBankingTest {

    @Test
    void msrControlFieldBanksTheStackPointer() {
        TestAddressSpace memory = new TestAddressSpace(0x20);
        memory.put32(0x00, 0xE121F000); // msr cpsr_c, r0   (r0 = modo IRQ)
        memory.put32(0x04, 0xE1A0D001); // mov r13, r1       (SP do IRQ)
        memory.put32(0x08, 0xE121F002); // msr cpsr_c, r2   (r2 = modo System)
        memory.put32(0x0C, 0xE1A0D003); // mov r13, r3       (SP do System)

        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, true, true);
        core.setRegister(0, 0x000000D2); // modo IRQ (+ I/F)
        core.setRegister(1, 0x22220000); // SP_irq pretendido
        core.setRegister(2, 0x000000DF); // modo System (+ I/F)
        core.setRegister(3, 0x11110000); // SP_sys pretendido

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
