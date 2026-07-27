package dev.vitorsilverio.armjitter.core64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B6.6.4: captura de {@code MemoryTranslationException64} em {@link Ir64BlockExecutor#step} —
/// convertida numa entrada de exceção síncrona EL0→EL1 real (`Aarch64Core#enterMemoryAbort`) e um
/// `ERET` de volta a EL0 — mesmo aceite explícito do precedente 32-bit
/// (`dev.vitorsilverio.armjitter.core.ArmCoreMemoryAbortTest`), mas rodando via `TranslatingAddressSpace64`
/// (B6.6.2) real (não um decorator de teste que só lança em endereços marcados).
///
/// ### Layout físico usado por todos os testes
/// Uma única tabela de páginas VMSA64 de 4 níveis (`L0..L3`, todas com só a entrada de índice `0`
/// preenchida) mapeia identidade `VA[0x000,0x1000) == PA[0x000,0x1000)` — cobrindo o código
/// principal (`PA 0x000`) E o handler de abort (`PA 0x400`, já que `VBAR_EL1` fica no default `0`
/// nesta task — a única entrada de vetor usada é `+0x400`, "Synchronous, lower EL AArch64"). Um
/// acesso a qualquer VA fora dessa página de 4KiB (ex. `VA = 1L<<44`) cai num índice `L0` diferente
/// de `0`, sempre inválido — falta de tradução determinística sem precisar de decorator de teste.
///
/// Instruções REAIS (montadas e conferidas via `aarch64-none-elf-as`/`objdump`, devkitA64):
/// `movz x1, #0x1000, lsl #32` (`0xd2c20001`, `x1 = 1<<44`), `ldr x0, [x1]` (`0xf9400020`),
/// `movz x2, #0x2000, lsl #32` (`0xd2c40002`, `x2 = 1<<45`), `br x2` (`0xd61f0040`),
/// `eret` (`0xd69f03e0`).
class Aarch64MemoryAbortTest {
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final int AP_SHIFT = 6;
    private static final int AP_FULL_ACCESS = 0b01;
    private static final long OUTPUT_ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L;

    private static final long L0_TABLE_BASE = 0x1000L;
    private static final long L1_TABLE_BASE = 0x2000L;
    private static final long L2_TABLE_BASE = 0x3000L;
    private static final long L3_TABLE_BASE = 0x4000L;
    private static final int PHYSICAL_SIZE_BYTES = 0x6000;

    private static final int MOVZ_X1_1SHL44 = 0xd2c2_0001; // movz x1, #0x1000, lsl #32
    private static final int LDR_X0_X1 = 0xf940_0020;       // ldr x0, [x1]
    private static final int MOVZ_X2_1SHL45 = 0xd2c4_0002; // movz x2, #0x2000, lsl #32
    private static final int BR_X2 = 0xd61f_0040;           // br x2
    private static final int ERET = 0xd69f_03e0;

    private static final long DATA_ABORT_VA = 1L << 44;         // L0 index 32: sem descritor
    private static final long INSTRUCTION_ABORT_VA = 1L << 45;  // L0 index 64: sem descritor
    private static final long HANDLER_PHYSICAL_ADDRESS = 0x400L;
    private static final long ESR_EC_SHIFT = 26;
    private static final long ESR_EC_DATA_ABORT_LOWER_EL = 0x24L;
    private static final long ESR_EC_INSTRUCTION_ABORT_LOWER_EL = 0x20L;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long identityPageDescriptor() {
        return ((long) AP_FULL_ACCESS << AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    /// Monta a tabela de páginas de 4 níveis (só índice `0` em cada nível) mapeando identidade a
    /// página `[0x000,0x1000)` — código principal e handler (`+0x400`) vivem nela.
    private static TranslatingAddressSpace64 identityMappedMmu(AddressSpace64 physical) {
        physical.write64(L0_TABLE_BASE, tableDescriptor(L1_TABLE_BASE));
        physical.write64(L1_TABLE_BASE, tableDescriptor(L2_TABLE_BASE));
        physical.write64(L2_TABLE_BASE, tableDescriptor(L3_TABLE_BASE));
        physical.write64(L3_TABLE_BASE, identityPageDescriptor());
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(L0_TABLE_BASE);
        return mmu;
    }

    @Test
    void dataAbortOnLoadEntersEl1HandlerWithEsrFarElrSpsrAndReturnsViaEret() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        physical.write32(0x0, MOVZ_X1_1SHL44);
        physical.write32(0x4, LDR_X0_X1);
        physical.write32(HANDLER_PHYSICAL_ADDRESS, ERET);
        TranslatingAddressSpace64 mmu = identityMappedMmu(physical);
        Aarch64Core core = new Aarch64Core(mmu);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.step(core); // movz x1, #0x1000, lsl #32
        assertEquals(DATA_ABORT_VA, core.x(1));
        assertEquals(4, core.pc());

        // B6.3.4/B6.6.4: um LDXR pendente deve ser aberto por QUALQUER entrada de exceção.
        core.markExclusiveMonitor(0x100, 8);

        executor.step(core); // ldr x0, [x1] -> MemoryTranslationException64 (DATA_READ)

        assertTrue(core.exceptionState().inEl1(), "abort de memória deve entrar em EL1");
        assertEquals(HANDLER_PHYSICAL_ADDRESS, core.pc(),
                "PC deve saltar para VBAR_EL1(0) + offset síncrono de nível inferior (0x400)");
        assertEquals(4, core.exceptionState().elr1(), "ELR_EL1 deve ser o endereço da PRÓPRIA LDR faltosa");
        assertEquals(DATA_ABORT_VA, core.exceptionState().far1(), "FAR_EL1 deve ser o VA faltoso");
        long ec = core.exceptionState().esr1() >>> ESR_EC_SHIFT;
        assertEquals(ESR_EC_DATA_ABORT_LOWER_EL, ec, "ESR_EL1.EC deve ser Data Abort de EL inferior (0x24)");
        assertEquals(-1L, core.exclusiveMonitorAddress(), "entrada de exceção deve abrir o monitor de exclusividade (B6.3.4)");

        executor.step(core); // eret -> volta a EL0 no endereço da LDR faltosa

        assertFalse(core.exceptionState().inEl1(), "ERET deve sair de EL1");
        assertEquals(4, core.pc(), "ERET deve retomar exatamente na instrução faltosa (PC<-ELR_EL1)");

        // Continuação real: aponta x1 para um endereço mapeado e reexecuta a MESMA LDR — deve
        // completar sem lançar (prova que o round-trip abort->handler->ERET->continuação funciona
        // de ponta a ponta, não só que os registradores de exceção foram preenchidos).
        core.setX(1, 0x0);
        executor.step(core);
        assertEquals(8, core.pc(), "load bem-sucedido deve avançar o PC normalmente");
    }

    @Test
    void instructionAbortOnFetchUsesInstructionAbortEsrEc() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        physical.write32(0x0, MOVZ_X2_1SHL45);
        physical.write32(0x4, BR_X2);
        physical.write32(HANDLER_PHYSICAL_ADDRESS, ERET);
        TranslatingAddressSpace64 mmu = identityMappedMmu(physical);
        Aarch64Core core = new Aarch64Core(mmu);
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.step(core); // movz x2, #0x2000, lsl #32
        executor.step(core); // br x2 (desvio tomado; a falta só ocorre no PRÓXIMO fetch)
        assertEquals(INSTRUCTION_ABORT_VA, core.pc());

        executor.step(core); // fetch de INSTRUCTION_ABORT_VA -> MemoryTranslationException64 (INSTRUCTION_FETCH)

        assertTrue(core.exceptionState().inEl1());
        assertEquals(HANDLER_PHYSICAL_ADDRESS, core.pc());
        assertEquals(INSTRUCTION_ABORT_VA, core.exceptionState().elr1(),
                "ELR_EL1 deve ser o endereço da PRÓPRIA instrução faltosa (o alvo do BR, nunca executado)");
        assertEquals(INSTRUCTION_ABORT_VA, core.exceptionState().far1());
        long ec = core.exceptionState().esr1() >>> ESR_EC_SHIFT;
        assertEquals(ESR_EC_INSTRUCTION_ABORT_LOWER_EL, ec,
                "ESR_EL1.EC deve ser Instruction Abort de EL inferior (0x20), DIFERENTE do data abort (0x24)");

        executor.step(core); // eret
        assertFalse(core.exceptionState().inEl1());
        assertEquals(INSTRUCTION_ABORT_VA, core.pc());
    }

    @Test
    void spEl1IsSeparateFromSpEl0AcrossExceptionEntryAndReturn() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        physical.write32(0x0, MOVZ_X1_1SHL44);
        physical.write32(0x4, LDR_X0_X1);
        physical.write32(HANDLER_PHYSICAL_ADDRESS, ERET);
        TranslatingAddressSpace64 mmu = identityMappedMmu(physical);
        Aarch64Core core = new Aarch64Core(mmu);
        core.setSp(0x9000_0000L); // SP_EL0
        core.exceptionState().setSp1(0xA000_0000L); // SP_EL1, configurado pelo host antes do boot
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.step(core);
        executor.step(core); // entra em EL1

        assertEquals(0xA000_0000L, core.sp(), "sp() deve resolver para SP_EL1 dentro do handler");

        executor.step(core); // eret

        assertEquals(0x9000_0000L, core.sp(), "sp() deve voltar a SP_EL0 depois do ERET");
    }
}
