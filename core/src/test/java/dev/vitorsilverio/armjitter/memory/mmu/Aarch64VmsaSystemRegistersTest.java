package dev.vitorsilverio.armjitter.memory.mmu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64AddressTranslateForm;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B6.6.3: liga `MRS`/`MSR (register)` (B6.6.1) e `TLBI VMALLE1`/`VMALLE1IS`
/// (`Ir64Op.SystemInstruction`) ao {@link TranslatingAddressSpace64} (B6.6.2) — mesmas duas
/// camadas do precedente 32-bit ({@code Cp15VmsaCoprocessorTest}):
/// - Unidade direta (`write`/`read` chamados sem {@link Aarch64Core}).
/// - Integração via {@link Ir64BlockExecutor#step} executando a sequência real de habilitação de
///   MMU (`TTBR0_EL1` -> `TCR_EL1` -> `SCTLR_EL1.M=1`) — o aceite explícito da task: código A64
///   real rodando no interpretador muda a tradução.
class Aarch64VmsaSystemRegistersTest {
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final int AP_SHIFT = 6;
    private static final int AP_FULL_ACCESS = 0b01;
    private static final int AP_EL1_ONLY_READ_WRITE = 0b00;
    private static final int AP_EL1_ONLY_READ_ONLY = 0b10;
    private static final long OUTPUT_ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L;
    private static final long F_BIT = 1L;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long blockDescriptor(long outputBase, int ap) {
        return (outputBase & OUTPUT_ADDRESS_MASK) | ((long) ap << AP_SHIFT) | DESC_VALID;
    }

    private static Aarch64Core coreWithoutCode() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x100)));
    }

    @Test
    void ttbr0TcrForwardedToMmuAndReadBack() {
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        bus.write(Aarch64SystemRegisterId.TTBR0_EL1, 0x4000_0000L);
        bus.write(Aarch64SystemRegisterId.TCR_EL1, 0x1234L);
        bus.write(Aarch64SystemRegisterId.MAIR_EL1, 0xFFL);

        assertEquals(0x4000_0000L, bus.read(Aarch64SystemRegisterId.TTBR0_EL1));
        assertEquals(0x1234L, bus.read(Aarch64SystemRegisterId.TCR_EL1));
        assertEquals(0xFFL, bus.read(Aarch64SystemRegisterId.MAIR_EL1));
    }

    @Test
    void sctlrMBitTogglesMmuEnabled() {
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        assertFalse(mmu.mmuEnabled(), "reset real de hardware: MMU começa desligada");

        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, 1L);
        assertTrue(mmu.mmuEnabled());
        assertEquals(1L, bus.read(Aarch64SystemRegisterId.SCTLR_EL1));

        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, 0L);
        assertFalse(mmu.mmuEnabled());
    }

    @Test
    void esrFarVbarElrSpsrAreStorageOnly() {
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        bus.write(Aarch64SystemRegisterId.ESR_EL1, 0x1111L);
        bus.write(Aarch64SystemRegisterId.FAR_EL1, 0x2222L);
        bus.write(Aarch64SystemRegisterId.VBAR_EL1, 0x3333L);
        bus.write(Aarch64SystemRegisterId.ELR_EL1, 0x4444L);
        bus.write(Aarch64SystemRegisterId.SPSR_EL1, 0x5555L);

        assertEquals(0x1111L, bus.read(Aarch64SystemRegisterId.ESR_EL1));
        assertEquals(0x2222L, bus.read(Aarch64SystemRegisterId.FAR_EL1));
        assertEquals(0x3333L, bus.read(Aarch64SystemRegisterId.VBAR_EL1));
        assertEquals(0x4444L, bus.read(Aarch64SystemRegisterId.ELR_EL1));
        assertEquals(0x5555L, bus.read(Aarch64SystemRegisterId.SPSR_EL1));
    }

    @Test
    void cpacrEl1IsStorageOnlyAndDoesNotTouchMmu() {
        // F11: msr cpacr_el1, xzr (visto no head.S real do kernel8.img do Raspberry Pi 3) não deve
        // lançar nem afetar a MMU — mesma disciplina de CPTR_EL2/CPTR_EL3 (sem trap real modelado).
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        bus.write(Aarch64SystemRegisterId.CPACR_EL1, 0L);
        assertFalse(mmu.mmuEnabled());
        assertEquals(0L, bus.read(Aarch64SystemRegisterId.CPACR_EL1));

        bus.write(Aarch64SystemRegisterId.CPACR_EL1, 0x0030_0000L);
        assertEquals(0x0030_0000L, bus.read(Aarch64SystemRegisterId.CPACR_EL1));
    }

    @Test
    void el2StorageOnlyRegistersRoundTrip() {
        // B10.2: sem side effect nenhum ainda (SCTLR_EL2.M NÃO deve tocar a MMU — essa é stage-1
        // de EL1; ligar stage-2 real é B10.8).
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        bus.write(Aarch64SystemRegisterId.SCTLR_EL2, 1L);
        assertFalse(mmu.mmuEnabled(), "SCTLR_EL2.M não liga a MMU de stage-1 EL1 (B10.8 trata stage-2)");
        assertEquals(1L, bus.read(Aarch64SystemRegisterId.SCTLR_EL2));

        bus.write(Aarch64SystemRegisterId.HCR_EL2, 0x1111_2222L);
        bus.write(Aarch64SystemRegisterId.MDCR_EL2, 0x33L);
        bus.write(Aarch64SystemRegisterId.CPTR_EL2, 0x44L);
        bus.write(Aarch64SystemRegisterId.TCR_EL2, 0x55L);
        bus.write(Aarch64SystemRegisterId.VTTBR_EL2, 0x6000_0000L);
        bus.write(Aarch64SystemRegisterId.VTCR_EL2, 0x77L);
        bus.write(Aarch64SystemRegisterId.CNTHCTL_EL2, 0x88L);

        assertEquals(0x1111_2222L, bus.read(Aarch64SystemRegisterId.HCR_EL2));
        assertEquals(0x33L, bus.read(Aarch64SystemRegisterId.MDCR_EL2));
        assertEquals(0x44L, bus.read(Aarch64SystemRegisterId.CPTR_EL2));
        assertEquals(0x55L, bus.read(Aarch64SystemRegisterId.TCR_EL2));
        assertEquals(0x6000_0000L, bus.read(Aarch64SystemRegisterId.VTTBR_EL2));
        assertEquals(0x77L, bus.read(Aarch64SystemRegisterId.VTCR_EL2));
        assertEquals(0x88L, bus.read(Aarch64SystemRegisterId.CNTHCTL_EL2));
    }

    @Test
    void el2ExceptionRegistersAreIndependentFromEl1() {
        // ESR_EL2/FAR_EL2/VBAR_EL2/ELR_EL2/SPSR_EL2 delegam ao banco por nível de
        // Aarch64ExceptionState (B10.1) — escrever o par EL1 não deve mudar o par EL2.
        Aarch64Core core = coreWithoutCode();
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, core);

        bus.write(Aarch64SystemRegisterId.ESR_EL1, 0x1111L);
        bus.write(Aarch64SystemRegisterId.FAR_EL1, 0x2222L);
        bus.write(Aarch64SystemRegisterId.VBAR_EL1, 0x3333L);
        bus.write(Aarch64SystemRegisterId.ELR_EL1, 0x4444L);
        bus.write(Aarch64SystemRegisterId.SPSR_EL1, 0x5555L);

        bus.write(Aarch64SystemRegisterId.ESR_EL2, 0xAAAAL);
        bus.write(Aarch64SystemRegisterId.FAR_EL2, 0xBBBBL);
        bus.write(Aarch64SystemRegisterId.VBAR_EL2, 0xCCCCL);
        bus.write(Aarch64SystemRegisterId.ELR_EL2, 0xDDDDL);
        bus.write(Aarch64SystemRegisterId.SPSR_EL2, 0xEEEEL);

        assertEquals(0x1111L, bus.read(Aarch64SystemRegisterId.ESR_EL1));
        assertEquals(0x2222L, bus.read(Aarch64SystemRegisterId.FAR_EL1));
        assertEquals(0x3333L, bus.read(Aarch64SystemRegisterId.VBAR_EL1));
        assertEquals(0x4444L, bus.read(Aarch64SystemRegisterId.ELR_EL1));
        assertEquals(0x5555L, bus.read(Aarch64SystemRegisterId.SPSR_EL1));

        assertEquals(0xAAAAL, bus.read(Aarch64SystemRegisterId.ESR_EL2));
        assertEquals(0xBBBBL, bus.read(Aarch64SystemRegisterId.FAR_EL2));
        assertEquals(0xCCCCL, bus.read(Aarch64SystemRegisterId.VBAR_EL2));
        assertEquals(0xDDDDL, bus.read(Aarch64SystemRegisterId.ELR_EL2));
        assertEquals(0xEEEEL, bus.read(Aarch64SystemRegisterId.SPSR_EL2));
    }

    @Test
    void el3StorageOnlyRegistersRoundTrip() {
        // B10.3: sem side effect nenhum ainda (SCTLR_EL3.M NÃO deve tocar nenhuma MMU — stage-1
        // de EL3 não é modelada por este épico; roteamento real de SMC é B10.5).
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        bus.write(Aarch64SystemRegisterId.SCTLR_EL3, 1L);
        assertFalse(mmu.mmuEnabled(), "SCTLR_EL3.M não liga a MMU de stage-1 EL1");
        assertEquals(1L, bus.read(Aarch64SystemRegisterId.SCTLR_EL3));

        bus.write(Aarch64SystemRegisterId.SCR_EL3, 0x1111_2222L);
        bus.write(Aarch64SystemRegisterId.MDCR_EL3, 0x33L);
        bus.write(Aarch64SystemRegisterId.CPTR_EL3, 0x44L);

        assertEquals(0x1111_2222L, bus.read(Aarch64SystemRegisterId.SCR_EL3));
        assertEquals(0x33L, bus.read(Aarch64SystemRegisterId.MDCR_EL3));
        assertEquals(0x44L, bus.read(Aarch64SystemRegisterId.CPTR_EL3));
    }

    @Test
    void el3ExceptionRegistersAreIndependentFromEl1AndEl2() {
        // VBAR_EL3/ELR_EL3/SPSR_EL3 delegam ao banco por nível de Aarch64ExceptionState (B10.1) —
        // escrever os pares EL1/EL2 não deve mudar o par EL3, e vice-versa.
        Aarch64Core core = coreWithoutCode();
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, core);

        bus.write(Aarch64SystemRegisterId.VBAR_EL1, 0x1111L);
        bus.write(Aarch64SystemRegisterId.ELR_EL1, 0x2222L);
        bus.write(Aarch64SystemRegisterId.SPSR_EL1, 0x3333L);

        bus.write(Aarch64SystemRegisterId.VBAR_EL2, 0x4444L);
        bus.write(Aarch64SystemRegisterId.ELR_EL2, 0x5555L);
        bus.write(Aarch64SystemRegisterId.SPSR_EL2, 0x6666L);

        bus.write(Aarch64SystemRegisterId.VBAR_EL3, 0xAAAAL);
        bus.write(Aarch64SystemRegisterId.ELR_EL3, 0xBBBBL);
        bus.write(Aarch64SystemRegisterId.SPSR_EL3, 0xCCCCL);

        assertEquals(0x1111L, bus.read(Aarch64SystemRegisterId.VBAR_EL1));
        assertEquals(0x2222L, bus.read(Aarch64SystemRegisterId.ELR_EL1));
        assertEquals(0x3333L, bus.read(Aarch64SystemRegisterId.SPSR_EL1));

        assertEquals(0x4444L, bus.read(Aarch64SystemRegisterId.VBAR_EL2));
        assertEquals(0x5555L, bus.read(Aarch64SystemRegisterId.ELR_EL2));
        assertEquals(0x6666L, bus.read(Aarch64SystemRegisterId.SPSR_EL2));

        assertEquals(0xAAAAL, bus.read(Aarch64SystemRegisterId.VBAR_EL3));
        assertEquals(0xBBBBL, bus.read(Aarch64SystemRegisterId.ELR_EL3));
        assertEquals(0xCCCCL, bus.read(Aarch64SystemRegisterId.SPSR_EL3));
    }

    @Test
    void debugRegistersRoundTripAndIndependent() {
        // B10.7: armazenamento puro, sem enforcement de RO/WO (OSLAR_EL1 é WO / OSLSR_EL1 é RO no
        // hardware real, aqui os dois aceitam leitura/escrita — decisão explícita da task).
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());

        bus.write(Aarch64SystemRegisterId.MDSCR_EL1, 0x1111L);
        bus.write(Aarch64SystemRegisterId.OSLAR_EL1, 0x2222L);
        bus.write(Aarch64SystemRegisterId.OSLSR_EL1, 0x3333L);
        bus.write(Aarch64SystemRegisterId.DBGBVR0_EL1, 0x4444L);
        bus.write(Aarch64SystemRegisterId.DBGBCR0_EL1, 0x5555L);
        bus.write(Aarch64SystemRegisterId.DBGWVR0_EL1, 0x6666L);
        bus.write(Aarch64SystemRegisterId.DBGWCR0_EL1, 0x7777L);

        assertEquals(0x1111L, bus.read(Aarch64SystemRegisterId.MDSCR_EL1));
        assertEquals(0x2222L, bus.read(Aarch64SystemRegisterId.OSLAR_EL1));
        assertEquals(0x3333L, bus.read(Aarch64SystemRegisterId.OSLSR_EL1));
        assertEquals(0x4444L, bus.read(Aarch64SystemRegisterId.DBGBVR0_EL1));
        assertEquals(0x5555L, bus.read(Aarch64SystemRegisterId.DBGBCR0_EL1));
        assertEquals(0x6666L, bus.read(Aarch64SystemRegisterId.DBGWVR0_EL1));
        assertEquals(0x7777L, bus.read(Aarch64SystemRegisterId.DBGWCR0_EL1));
        assertFalse(mmu.mmuEnabled(), "nenhum registrador de debug toca a MMU");
    }

    @Test
    void tlbiForwardedToMmu() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        physical.write64(0, tableDescriptor(0x1000)); // L0[0] -> L1
        physical.write64(0x1000, blockDescriptor(0, AP_FULL_ACCESS)); // L1[0]: bloco 1GiB identidade
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(0);
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, coreWithoutCode());
        mmu.setMmuEnabled(true); // construtor do bus desliga (M=0); liga para testar a TLB isolada

        mmu.read32(0);
        long afterFirstWalk = mmu.pageWalkCount();
        mmu.read32(0);
        assertEquals(afterFirstWalk, mmu.pageWalkCount(), "HIT antes de invalidar");

        bus.invalidateTlbAll();
        mmu.read32(0);
        assertEquals(afterFirstWalk + 1, mmu.pageWalkCount(), "TLBI VMALLE1 deve forçar novo walk");
    }

    @Test
    void barrierIsNoopAndDoesNotAdvanceProgramCounterByItself() {
        Aarch64Core core = coreWithoutCode();
        core.setProgramCounter(0x40);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        boolean pcChanged = executor.executeOp(core, new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.BARRIER));
        assertFalse(pcChanged);
        assertEquals(0x40, core.pc(), "barreira é NOP puro: não mexe no PC nem em nenhum registrador");
    }

    @Test
    void tlbiAllNoopWhenNoBusInstalled() {
        // Ir64Op.SystemInstruction(TLBI_ALL) executado sem nenhum Aarch64SystemRegisterBus
        // instalado (default Aarch64SystemRegisterBus#none()) não deve lançar — invalidateTlbAll
        // tem default NOP, diferente de SystemRegister (que exige handles()==true).
        Aarch64Core core = coreWithoutCode();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.executeOp(core, new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.TLBI_ALL));
    }

    @Test
    void sequenciaRealDeHabilitacaoDeMmuExecutadaPeloInterpretadorMudaATraducao() {
        // Memória de código independente da memória traduzida sob teste (mesmo padrão do
        // Cp15VmsaCoprocessorTest: o Aarch64Core roda a partir de uma memória plana própria).
        TestAddressSpace codeMemory = new TestAddressSpace(0x1000);
        codeMemory.put32(0x00, 0xd2800000); // movz x0, #0 (TTBR0_EL1 base)
        codeMemory.put32(0x04, 0xd5182000); // msr ttbr0_el1, x0
        codeMemory.put32(0x08, 0xd2800001); // movz x1, #0 (TCR_EL1, armazenamento simples)
        codeMemory.put32(0x0c, 0xd5182041); // msr tcr_el1, x1
        codeMemory.put32(0x10, 0xd2800022); // movz x2, #1 (SCTLR_EL1.M=1)
        codeMemory.put32(0x14, 0xd5181002); // msr sctlr_el1, x2

        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(codeMemory));

        AddressSpace64 mmuPhysical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        mmuPhysical.write64(0, tableDescriptor(0x1000)); // L0[0] -> L1
        mmuPhysical.write64(0x1000, blockDescriptor(0, AP_FULL_ACCESS)); // L1[0]: bloco identidade
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(mmuPhysical);
        Aarch64VmsaSystemRegisters bus = new Aarch64VmsaSystemRegisters(mmu, core);
        core.setSystemRegisterBus(bus);

        assertFalse(mmu.mmuEnabled());

        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.step(core); // movz x0, #0
        executor.step(core); // msr ttbr0_el1, x0
        executor.step(core); // movz x1, #0
        executor.step(core); // msr tcr_el1, x1
        executor.step(core); // movz x2, #1
        executor.step(core); // msr sctlr_el1, x2

        assertTrue(mmu.mmuEnabled(), "SCTLR_EL1.M=1 executado pelo interpretador deve ligar a MMU");
        long walksBeforeAccess = mmu.pageWalkCount();
        mmu.write32(0, 0x1234_5678);
        assertEquals(0x1234_5678, mmu.read32(0));
        assertEquals(0x1234_5678, mmuPhysical.read32(0), "bloco identidade: PA == VA");
        assertTrue(mmu.pageWalkCount() > walksBeforeAccess, "acesso pós-M=1 deve ter percorrido a tabela real");
    }

    // ── B10.6: AT S1E1R/S1E1W/S1E0R/S1E0W (Ir64Op.AddressTranslate) ────────────────────────

    /// Página de 4KiB identity-mapped em `PA=0`, `AP` configurável (índice L3 `0`).
    private static Aarch64Core coreWithMmuPageAtZero(int ap) {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        physical.write64(0, tableDescriptor(0x1000));    // L0[0] -> L1
        physical.write64(0x1000, tableDescriptor(0x2000)); // L1[0] -> L2
        physical.write64(0x2000, tableDescriptor(0x3000)); // L2[0] -> L3
        physical.write64(0x3000, (0L & OUTPUT_ADDRESS_MASK) | ((long) ap << AP_SHIFT) | 0b10L | 0b1L); // L3[0]
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(0);
        mmu.setMmuEnabled(true);
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x100)));
        core.setSystemRegisterBus(new Aarch64VmsaSystemRegisters(mmu, core));
        return core;
    }

    @Test
    void s1e1rBemSucedidoEscrevePar() {
        Aarch64Core core = coreWithMmuPageAtZero(AP_FULL_ACCESS);
        core.setX(0, 0x100L); // VA dentro da página identity-mapped
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1R, 0));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(0, par & F_BIT, "F=0: tradução bem-sucedida");
        assertEquals(0x100L & OUTPUT_ADDRESS_MASK, par & OUTPUT_ADDRESS_MASK);
    }

    @Test
    void s1e0rEmPaginaSoPrivilegiadaFalhaComParFSemLancarExcecao() {
        Aarch64Core core = coreWithMmuPageAtZero(AP_EL1_ONLY_READ_WRITE);
        core.setX(0, 0x100L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        // Não deve lançar: AT nunca gera abort para o guest, só reporta em PAR_EL1.
        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E0R, 0));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(1, par & F_BIT, "F=1: S1E0R numa página EL1-only deve falhar por permissão");
    }

    @Test
    void s1e1rNaMesmaPaginaContinuaOkPoisTraduzComoEl1() {
        Aarch64Core core = coreWithMmuPageAtZero(AP_EL1_ONLY_READ_WRITE);
        core.setX(0, 0x100L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1R, 0));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(0, par & F_BIT, "S1E1R checa como EL1, que tem acesso à página EL1-only");
    }

    @Test
    void s1e1wEmPaginaSomenteLeituraFalha() {
        Aarch64Core core = coreWithMmuPageAtZero(AP_EL1_ONLY_READ_ONLY);
        core.setX(0, 0x100L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1W, 0));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(1, par & F_BIT, "S1E1W numa página só-leitura deve falhar por permissão");
    }

    @Test
    void enderecoSemDescritorValidoFalhaComTranslationFault() {
        Aarch64Core core = coreWithMmuPageAtZero(AP_FULL_ACCESS);
        core.setX(0, 1L << 30); // fora da única página mapeada (L0[0]->L1[0]->L2[0]->L3[0])
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1R, 0));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(1, par & F_BIT, "F=1: sem descritor válido no caminho do walk");
    }

    @Test
    void xzrComoOrigemDoVaTraduzEnderecoZero() {
        Aarch64Core core = coreWithMmuPageAtZero(AP_FULL_ACCESS);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S1E1R, 31));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(0, par & F_BIT);
        assertEquals(0L, par & OUTPUT_ADDRESS_MASK, "XZR (rt=31) deve traduzir VA=0");
    }

    // ── B10.8: AT S12E1R/S12E1W/S12E0R/S12E0W (combinada stage-1+stage-2) ──────────────────

    private static final long S2_L0_BASE = 0x0090_0000L;
    private static final long S2_L1_BASE = 0x0091_0000L;
    private static final long S2_L2_BASE = 0x0092_0000L;
    private static final long S2_L3_BASE = 0x0093_0000L;
    private static final int S2AP_READ_WRITE = 0b11;
    private static final int S2AP_NONE = 0b00;
    private static final long STAGE2_FINAL_PA = 0x0050_0000L;
    private static final long S_STAGE_BIT = 1L << 9;

    /// Mesma página de stage-1 identity-mapped em `PA=0` de {@link #coreWithMmuPageAtZero}, mais
    /// uma tabela de stage-2 completa (`VTTBR_EL2` já escrito) mapeando o MESMO IPA (`0`, já que
    /// stage-1 aqui é identidade) para {@link #STAGE2_FINAL_PA} com o `s2ap` pedido — o teste
    /// decide se liga `HCR_EL2.VM` ou não.
    private static Aarch64Core coreWithStage1AndStage2(int stage1Ap, int s2ap) {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x0100_0000));
        physical.write64(0, tableDescriptor(0x1000));
        physical.write64(0x1000, tableDescriptor(0x2000));
        physical.write64(0x2000, tableDescriptor(0x3000));
        physical.write64(0x3000, (0L & OUTPUT_ADDRESS_MASK) | ((long) stage1Ap << AP_SHIFT) | DESC_TABLE_OR_PAGE
                | DESC_VALID);

        physical.write64(S2_L0_BASE, tableDescriptor(S2_L1_BASE));
        physical.write64(S2_L1_BASE, tableDescriptor(S2_L2_BASE));
        physical.write64(S2_L2_BASE, tableDescriptor(S2_L3_BASE));
        physical.write64(S2_L3_BASE, (STAGE2_FINAL_PA & OUTPUT_ADDRESS_MASK) | ((long) s2ap << AP_SHIFT)
                | DESC_TABLE_OR_PAGE | DESC_VALID);

        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(0);
        mmu.setMmuEnabled(true);
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x100)));
        core.setSystemRegisterBus(new Aarch64VmsaSystemRegisters(mmu, core));
        core.systemRegisterBus().write(Aarch64SystemRegisterId.VTTBR_EL2, S2_L0_BASE);
        return core;
    }

    @Test
    void s12e1rComHcrVmZeroSeComportaComoS1e1r() {
        // HCR_EL2.VM nunca escrito == 0: stage-2 desligada, S12E1R deve ignorar a tabela de
        // stage-2 (que mapearia para STAGE2_FINAL_PA) e devolver o mesmo resultado de S1E1R.
        Aarch64Core core = coreWithStage1AndStage2(AP_FULL_ACCESS, S2AP_READ_WRITE);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S12E1R, 31));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(0, par & F_BIT);
        assertEquals(0L, par & OUTPUT_ADDRESS_MASK, "VM=0: PA deve ser o IPA da stage-1 (identidade), sem stage-2");
    }

    @Test
    void s12e1rComHcrVmUmPassaPelasDuasEtapas() {
        Aarch64Core core = coreWithStage1AndStage2(AP_FULL_ACCESS, S2AP_READ_WRITE);
        core.systemRegisterBus().write(Aarch64SystemRegisterId.HCR_EL2, 1L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S12E1R, 31));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(0, par & F_BIT);
        assertEquals(STAGE2_FINAL_PA, par & OUTPUT_ADDRESS_MASK,
                "VM=1: PA final deve vir da stage-2, não do IPA intermediário");
    }

    @Test
    void s12e1rComStage2SemAcessoFalhaComSBitSetado() {
        Aarch64Core core = coreWithStage1AndStage2(AP_FULL_ACCESS, S2AP_NONE);
        core.systemRegisterBus().write(Aarch64SystemRegisterId.HCR_EL2, 1L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S12E1R, 31));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(1, par & F_BIT, "F=1: stage-2 nega o acesso (S2AP=00)");
        assertEquals(S_STAGE_BIT, par & S_STAGE_BIT, "S=1: falha veio da stage-2, não da stage-1");
    }

    @Test
    void s12e0rEmPaginaSoPrivilegiadaDeStage1FalhaAntesDeChegarNaStage2() {
        // Falha de stage-1 (AP_EL1_ONLY_READ_WRITE nega EL0) — S12E0R nunca chega a consultar a
        // stage-2 (S2AP_READ_WRITE aqui, que teria permitido); o F=1 deve vir sem o bit S setado.
        Aarch64Core core = coreWithStage1AndStage2(AP_EL1_ONLY_READ_WRITE, S2AP_READ_WRITE);
        core.systemRegisterBus().write(Aarch64SystemRegisterId.HCR_EL2, 1L);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.executeOp(core, new Ir64Op.AddressTranslate(Aarch64AddressTranslateForm.S12E0R, 31));

        long par = core.systemRegisterBus().read(Aarch64SystemRegisterId.PAR_EL1);
        assertEquals(1, par & F_BIT);
        assertEquals(0, par & S_STAGE_BIT, "S=0: a falha foi de stage-1 (permissão EL0), não de stage-2");
    }

    @Test
    void manutencaoDeCacheRealContinuaNopAposOCarveOutDeAt() {
        // Regressão do achado desta task: IC IALLU (CRm=1, op1=0, CRn=7, op2=5, != AT CRm=8)
        // precisa continuar caindo no bucket genérico de cache maintenance, não em AT.
        Aarch64Core core = coreWithoutCode();
        core.setProgramCounter(0x20);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        boolean pcChanged = executor.executeOp(
                core, new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.CACHE_MAINTENANCE_NOP));

        assertFalse(pcChanged);
        assertEquals(0x20, core.pc());
    }
}
