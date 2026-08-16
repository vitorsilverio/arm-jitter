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

    /// Regressão do achado real da task F3 (`virtual-arm-box`, sessão de fechamento do M2): antes,
    /// `read(SCTLR)` reconstruía o valor só a partir de `M`/`V` (RAZ para todo o resto), então o
    /// kernel Linux ARMv6 real (que relê o próprio `SCTLR` em `build_mem_type_table()` para testar
    /// `CR_XP`, bit 23) via `CR_XP=0` mesmo depois de escrevê-lo como `1` no boot — causa raiz
    /// confirmada bit a bit contra o QEMU 8.0.0 como oráculo (mesmo kernel+DTB): a seção do FDT
    /// virava `AP=00`/`APX=0` (sem acesso algum) em vez de `AP=01`/`APX=1` (só leitura privilegiada,
    /// o que o QEMU produz), e a leitura seguinte de `fdt_next_tag()` sofria `DATA_ABORT`. Este
    /// teste prova que bits sem efeito colateral modelado (aqui, `CR_XP`) agora sobrevivem ao
    /// round-trip escrita→leitura, sem alterar o comportamento de `M`/`V`.
    @Test
    void sctlrUnmodeledBitsRoundTripOnRead() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        int crXp = 1 << 23; // SCTLR.XP (ARMv6): bit sem efeito colateral modelado nesta fase.
        int mBit = 1;
        cp15.write(15, 0, 1, 0, 0, crXp | mBit); // SCTLR.M=1, SCTLR.XP=1

        assertEquals(crXp | mBit, cp15.read(15, 0, 1, 0, 0),
                "bits não modelados (ex. CR_XP) devem sobreviver ao round-trip, não RAZ");
        assertTrue(mmu.mmuEnabled(), "M continua com efeito colateral real");

        cp15.write(15, 0, 1, 0, 0, crXp); // SCTLR.M=0, mantém XP=1
        assertEquals(crXp, cp15.read(15, 0, 1, 0, 0));
        assertFalse(mmu.mmuEnabled());
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

    /// B4.1.5 (achado real do boot do Linux no `linuxbox`): um core com TLBs de instrução e dados
    /// SEPARADAS (ARM926EJ-S/ARMv5, o `v4wbi_*` do Linux) nunca emite a forma unificada `c8,c7,*` —
    /// só `c8,c5,*` (instrução) e `c8,c6,*` (dados). Sem elas todo `flush_tlb_*` do kernel virava
    /// UNDEFINED.
    @Test
    void separateInstructionAndDataTlbOpsAreHandledAndForwarded() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        physical.put32(0, sectionDescriptor(0, 0));
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);
        mmu.setDacr(1);
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());
        mmu.setMmuEnabled(true);

        assertTrue(cp15.handles(15, 0, 8, 5, 0), "ITLBIALL (c8,c5,0)");
        assertTrue(cp15.handles(15, 0, 8, 5, 1), "ITLBIMVA (c8,c5,1)");
        assertTrue(cp15.handles(15, 0, 8, 6, 0), "DTLBIALL (c8,c6,0)");
        assertTrue(cp15.handles(15, 0, 8, 6, 1), "DTLBIMVA (c8,c6,1)");
        assertTrue(cp15.handles(15, 0, 8, 7, 2), "TLBIASID (c8,c7,2)");

        mmu.read32(0);
        long afterFirstWalk = mmu.pageWalkCount();
        mmu.read32(0);
        assertEquals(afterFirstWalk, mmu.pageWalkCount(), "HIT de dados antes de invalidar");

        cp15.write(15, 0, 8, 5, 0, 0); // ITLBIALL: não pode mexer na TLB de DADOS
        mmu.read32(0);
        assertEquals(afterFirstWalk, mmu.pageWalkCount(), "ITLBIALL não invalida a TLB de dados");

        cp15.write(15, 0, 8, 6, 0, 0); // DTLBIALL
        mmu.read32(0);
        assertEquals(afterFirstWalk + 1, mmu.pageWalkCount(), "DTLBIALL deve forçar novo walk de dados");

        long afterAll = mmu.pageWalkCount();
        mmu.read32(0);
        assertEquals(afterAll, mmu.pageWalkCount());
        cp15.write(15, 0, 8, 6, 1, 0); // DTLBIMVA(0)
        mmu.read32(0);
        assertEquals(afterAll + 1, mmu.pageWalkCount(), "DTLBIMVA deve forçar novo walk de dados");
    }

    /// B4.1.5 (achado real): sem sincronizar o modo do core com {@link
    /// TranslatingAddressSpace#setPrivileged}, TODO acesso é privilegiado — uma escrita de USUÁRIO
    /// numa página `AP=01` (privilegiado-apenas) passa em vez de faltar, e o *copy-on-write* do
    /// `fork()` do Linux nunca dispara.
    @Test
    void modeChangeListenerSyncsPrivilegedIntoMmu() {
        TestAddressSpace physical = new TestAddressSpace(0x0100_0000);
        int apPrivilegedOnly = 0b01;
        physical.put32(0, (apPrivilegedOnly << 10) | (0 << 5) | 0b10); // seção identidade, priv-only
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(physical);
        mmu.setDacr(1); // domínio 0 = CLIENT (AP é consultado)
        mmu.setMmuEnabled(true);
        ArmCore core = coreWithoutCode();
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);
        mmu.setMmuEnabled(true); // construtor simula reset (M=0)
        core.setModeChangeListener(cp15);

        core.switchMode(CpuMode.SUPERVISOR);
        mmu.write32(0, 0x1234_5678); // privilegiado: permitido
        assertEquals(0x1234_5678, physical.read32(0));

        core.switchMode(CpuMode.USER);
        MemoryTranslationException fault = org.junit.jupiter.api.Assertions.assertThrows(
                MemoryTranslationException.class, () -> mmu.write32(0, 0xDEAD_BEEF),
                "escrita de usuário em página privilegiada deve faltar");
        assertEquals(FaultStatus.SECTION_PERMISSION, fault.faultStatus());
        assertEquals(0x1234_5678, physical.read32(0), "a escrita não pode ter acontecido");

        core.switchMode(CpuMode.SUPERVISOR);
        mmu.write32(0, 0x0BADF00D);
        assertEquals(0x0BADF00D, physical.read32(0), "volta a privilegiado: permitido de novo");
    }

    @Test
    void onDataAbortFillsDfarDfsr() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        cp15.onDataAbort(0x1234_5678, FaultStatus.SECTION_PERMISSION.code());

        assertEquals(0x1234_5678, cp15.read(15, 0, 6, 0, 0), "DFAR");
        assertEquals(FaultStatus.SECTION_PERMISSION.code(), cp15.read(15, 0, 5, 0, 0), "DFSR");
    }

    @Test
    void onPrefetchAbortFillsIfarIfsrWithoutTouchingDataFaultRegisters() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());
        cp15.onDataAbort(0x1111_1111, FaultStatus.SECTION_DOMAIN.code());

        cp15.onPrefetchAbort(0x2000_0000, FaultStatus.PAGE_TRANSLATION.code());

        assertEquals(0x2000_0000, cp15.read(15, 0, 6, 0, 2), "IFAR");
        assertEquals(FaultStatus.PAGE_TRANSLATION.code(), cp15.read(15, 0, 5, 0, 1), "IFSR");
        assertEquals(0x1111_1111, cp15.read(15, 0, 6, 0, 0), "DFAR não deveria mudar");
        assertEquals(FaultStatus.SECTION_DOMAIN.code(), cp15.read(15, 0, 5, 0, 0), "DFSR não deveria mudar");
    }

    @Test
    void contextIdrAsidLowByteForwardedAndReadBackWhole() {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        cp15.write(15, 0, 13, 0, 1, 0x0000_1234);

        assertEquals(0x0000_1234, cp15.read(15, 0, 13, 0, 1));
    }

    @Test
    void threadIdRegistersAreStoredAndReadBackIndependently() {
        // F3/sessão 2 (raspi1/ARMv6K): `MCR p15,0,Rt,c13,c0,3` (TPIDRURO) é uma das primeiras
        // instruções que o kernel Linux ARMv6K real executa, bem antes de `setup_arch()` — sem
        // este suporte ela é UNDEFINED, e como os vetores de exceção ainda não foram copiados
        // pelo `early_trap_init()` nesse ponto do boot, a exceção cascateia num laço infinito de
        // PREFETCH_ABORT (achado real, ver Javadoc da classe). Regressão: os 4 registradores
        // `c13,c0,{0,2,3,4}` são aceitos e cada um guarda seu próprio valor, sem se confundir com
        // `CONTEXTIDR` (`c13,c0,1`, já coberto pelo teste acima).
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(0x1000));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, coreWithoutCode());

        cp15.write(15, 0, 13, 0, 0, 0x1111_0000); // FCSEIDR
        cp15.write(15, 0, 13, 0, 2, 0x2222_0000); // TPIDRURW
        cp15.write(15, 0, 13, 0, 3, 0x3333_0000); // TPIDRURO
        cp15.write(15, 0, 13, 0, 4, 0x4444_0000); // TPIDRPRW

        assertTrue(cp15.handles(15, 0, 13, 0, 0));
        assertTrue(cp15.handles(15, 0, 13, 0, 2));
        assertTrue(cp15.handles(15, 0, 13, 0, 3));
        assertTrue(cp15.handles(15, 0, 13, 0, 4));
        assertEquals(0x1111_0000, cp15.read(15, 0, 13, 0, 0));
        assertEquals(0x2222_0000, cp15.read(15, 0, 13, 0, 2));
        assertEquals(0x3333_0000, cp15.read(15, 0, 13, 0, 3));
        assertEquals(0x4444_0000, cp15.read(15, 0, 13, 0, 4));
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
