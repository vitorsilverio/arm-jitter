package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B4.1.2: liga `MCR`/`MRC` ao `TranslatingAddressSpace` (B4.1.1). Duas camadas:
/// - Unidade direta (`write`/`read` chamados sem `ArmCore`) para os registradores de
///   armazenamento/encaminhamento simples.
/// - Integração via `ArmCore.step()` executando a sequência real de habilitação de MMU
///   (`TTBR0` -> `DACR` -> `SCTLR.M=1`) — o aceite explícito da task: código ARM rodando no
///   interpretador muda a tradução.
class Cp15VmsaCoprocessorTest {

    @Test
    void ttbr0DacrForwardedToMmuAndReadBack() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        cp15.write(15, 0, 2, 0, 0, 0x4000_0000); // TTBR0
        cp15.write(15, 0, 3, 0, 0, 0x0000_0001); // DACR: domínio 0 = CLIENT

        assertEquals(0x4000_0000, cp15.read(15, 0, 2, 0, 0));
        assertEquals(0x0000_0001, cp15.read(15, 0, 3, 0, 0));
    }

    @Test
    void sctlrMBitTogglesMmuEnabled() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        assertFalse(mmu.mmuEnabled(), "reset real de hardware: MMU começa desligada");

        cp15.write(15, 0, 1, 0, 0, 1); // SCTLR.M=1
        assertTrue(mmu.mmuEnabled());
        assertEquals(1, cp15.read(15, 0, 1, 0, 0));

        cp15.write(15, 0, 1, 0, 0, 0); // SCTLR.M=0
        assertFalse(mmu.mmuEnabled());
    }

    @Test
    void sctlrVBitTogglesHighVectorsOnCore() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        ArmCore core = coreWithoutCode();
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);

        cp15.write(15, 0, 1, 0, 0, 1 << 13); // SCTLR.V=1
        assertTrue(core.highVectors());
        assertEquals(1 << 13, cp15.read(15, 0, 1, 0, 0));
    }

    @Test
    void tlbOpsForwardedToMmu() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        physical.put32(0, sectionDescriptor(0, 0)); // L1[0]: identidade em PA 0, domínio 0
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);
        mmu.setDacr(1); // domínio 0 = CLIENT
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());
        mmu.setMmuEnabled(true); // construtor simula reset (M=0); liga para testar a TLB isolada
        cp15.write(15, 0, 2, 0, 0, 0); // TTBR0=0

        mmu.read32(0);
        long afterFirstWalk = mmu.pageWalkCount();
        mmu.read32(0);
        assertEquals(afterFirstWalk, mmu.pageWalkCount(), "HIT antes de invalidar");

        cp15.write(15, 0, 8, 7, 1, 0); // TLBIMVA(0)
        mmu.read32(0);
        assertEquals(afterFirstWalk + 1, mmu.pageWalkCount(), "TLBIMVA deve forçar novo walk");

        long afterMva = mmu.pageWalkCount();
        mmu.read32(0);
        assertEquals(afterMva, mmu.pageWalkCount());

        cp15.write(15, 0, 8, 7, 0, 0); // TLBIALL
        mmu.read32(0);
        assertEquals(afterMva + 1, mmu.pageWalkCount(), "TLBIALL deve forçar novo walk");
    }

    @Test
    void contextIdrAsidLowByteForwardedAndReadBackWhole() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        cp15.write(15, 0, 13, 0, 1, 0x0000_1234);

        assertEquals(0x0000_1234, cp15.read(15, 0, 13, 0, 1));
    }

    @Test
    void cacheMaintenanceIsNoopAndReadsAsZero() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        cp15.write(15, 0, 7, 5, 4, 0); // ISB
        cp15.write(15, 0, 7, 10, 4, 0); // DSB
        cp15.write(15, 0, 7, 10, 5, 0); // DMB

        assertEquals(0, cp15.read(15, 0, 7, 5, 4));
    }

    @Test
    void unclaimedRegisterIsNotHandledByFinePredicate() {
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(
                new TranslatingAddressSpace(new TestAddressSpace(0x100)), coreWithoutCode());

        // c9 (cache lockdown) fora do escopo VMSA da B4.1.2.
        assertFalse(cp15.handles(15, 0, 9, 1, 0));
        assertTrue(cp15.handles(15)); // grosso continua true (padrão do CoprocessorBus)
    }

    @Test
    void sequenciaRealDeHabilitacaoDeMmuExecutadaPeloInterpretadorMudaATraducao() {
        // Memória de código independente da memória traduzida sob teste (mesmo padrão do
        // CoprocessorTest.java: o ArmCore roda a partir de uma memória plana própria).
        TestAddressSpace codeMemory = new TestAddressSpace(0x1000);
        int r0 = 0;
        int r1 = 1;
        codeMemory.put32(0x00, movImmediate(r0, 0)); // r0 = 0 (TTBR0 base)
        codeMemory.put32(0x04, mcr(r0, 2, 0, 0)); // MCR p15,0,r0,c2,c0,0 -> TTBR0
        codeMemory.put32(0x08, movImmediate(r1, 1)); // r1 = 1 (domínio 0 = CLIENT)
        codeMemory.put32(0x0C, mcr(r1, 3, 0, 0)); // MCR p15,0,r1,c3,c0,0 -> DACR
        codeMemory.put32(0x10, movImmediate(r1, 1)); // r1 = 1 (SCTLR.M)
        codeMemory.put32(0x14, mcr(r1, 1, 0, 0)); // MCR p15,0,r1,c1,c0,0 -> SCTLR.M=1

        ArmCore core = new ArmCore(codeMemory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, false, false);

        TestAddressSpace mmuPhysical = new TestAddressSpace(0x0100_0000);
        mmuPhysical.put32(0, sectionDescriptor(0, 0)); // L1[0] -> seção identidade em PA 0, domínio 0
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(mmuPhysical);
        mmu.setMmuEnabled(false); // simula reset real: nada traduzido antes de SCTLR.M=1
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);
        core.setCoprocessorBus(cp15);

        assertFalse(mmu.mmuEnabled());

        core.step(); // MOV r0, #0
        core.step(); // MCR TTBR0
        core.step(); // MOV r1, #1
        core.step(); // MCR DACR
        core.step(); // MOV r1, #1
        core.step(); // MCR SCTLR.M=1

        assertTrue(mmu.mmuEnabled(), "SCTLR.M=1 executado pelo interpretador deve ligar a MMU");
        mmu.write32(0, 0x1234_5678);
        assertEquals(0x1234_5678, mmu.read32(0));
        assertEquals(0x1234_5678, mmuPhysical.read32(0), "seção identidade: PA == VA");
    }

    private static int sectionDescriptor(int physicalBase, int domain) {
        int typeSection = 0b10;
        int apFullAccess = 0b11;
        return (physicalBase & 0xFFF0_0000) | (apFullAccess << 10) | (domain << 5) | typeSection;
    }

    private static ArmCore coreWithoutCode() {
        ArmCore core = new ArmCore(new TestAddressSpace(0x100), SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.configureExecutionState(0, CpuMode.SYSTEM, InstructionSet.ARM, false, false);
        return core;
    }

    /// `MCR p15,0,Rd,c<crn>,c<crm>,<opcode2>` (`opcode1=0`, único válido para os registradores
    /// VMSA desta task). Fórmula verificada contra `CoprocessorTest.MCR_P15_R1_C9_C1_0`.
    private static int mcr(int rd, int crn, int crm, int opcode2) {
        int mcrBase = 0xEE00_0010; // cond=AL, fixo 1110, opcode1=0, L=0(MCR), bit4=1
        int cp15Field = 0xF << 8;
        return mcrBase | (crn << 16) | (rd << 12) | cp15Field | (opcode2 << 5) | crm;
    }

    /// `MOV Rd, #imm8` sem rotação (cond `AL`).
    private static int movImmediate(int rd, int imm8) {
        int movBase = 0xE3A0_0000;
        return movBase | (rd << 12) | (imm8 & 0xFF);
    }
}
