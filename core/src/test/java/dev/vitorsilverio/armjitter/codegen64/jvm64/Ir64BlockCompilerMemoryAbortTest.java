package dev.vitorsilverio.armjitter.codegen64.jvm64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.codegen.jvm.Jvm64BlockLoader;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// F11 (sessão de retomada, 2026-08-26): achado real — {@link Ir64BlockCompiler} nunca cercava o
/// bloco nativo com um `try/catch` de {@code MemoryTranslationException64} (nem das outras 4
/// exceções de controle A64), ao contrário do precedente 32-bit
/// ({@link dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler}, que faz isso desde B4.1.3).
/// Uma falta de tradução no MEIO de um bloco promovido a nativo (o caso comum: `MOVZ` cacheia `X1`,
/// `LDR` no mesmo bloco falta) escapava como {@code RuntimeException} do host em vez de entrar no
/// handler do guest — divergência observável entre os backends JIT/INTERPRETED que bloqueava o
/// boot real da F11 (`virtual-arm-box --machine=raspi3-64`, `TRANSLATION_FAULT_L3 em 0x200`).
///
/// Mesmo layout físico/instruções de {@code Aarch64MemoryAbortTest} (B6.6.4, oráculo interpretado)
/// — aqui o bloco é LIFTED e COMPILADO nativamente (nunca passa por
/// {@link dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor#step}), provando que o backend
/// ASM entra na mesma exceção de guest que o interpretador (G1).
class Ir64BlockCompilerMemoryAbortTest {
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
    /// Exatamente as 2 instruções do teste (`MOVZ`+`LDR`) — o lifter não teria como saber que a
    /// memória depois delas está zerada/não-instrução real, então o `maxInstructions` do CHAMADOR
    /// que delimita o bloco aqui, não um terminador natural (mesmo cuidado de qualquer teste de
    /// lifter que usa memória de teste sintética).
    private static final int MAX_BLOCK_INSTRUCTIONS = 2;

    private static final int MOVZ_X1_1SHL44 = 0xd2c2_0001; // movz x1, #0x1000, lsl #32
    private static final int LDR_X0_X1 = 0xf940_0020;       // ldr x0, [x1]
    private static final int ERET = 0xd69f_03e0;

    private static final long DATA_ABORT_VA = 1L << 44; // L0 index 32: sem descritor
    private static final long HANDLER_PHYSICAL_ADDRESS = 0x400L;
    private static final long ESR_EC_SHIFT = 26;
    private static final long ESR_EC_DATA_ABORT_LOWER_EL = 0x24L;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long identityPageDescriptor() {
        return ((long) AP_FULL_ACCESS << AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

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
    void nativeCompiledBlockEntersGuestAbortHandlerInsteadOfThrowingToHost() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        physical.write32(0x0, MOVZ_X1_1SHL44);
        physical.write32(0x4, LDR_X0_X1);
        physical.write32(HANDLER_PHYSICAL_ADDRESS, ERET);
        TranslatingAddressSpace64 mmu = identityMappedMmu(physical);
        Aarch64Core core = new Aarch64Core(mmu);

        Ir64Block block = new StandardIr64BlockLifter().lift(core.memory(), 0L, MAX_BLOCK_INSTRUCTIONS);
        assertTrue(Ir64NativePolicy.supports(block), "MOVZ+LDR devem ser nativamente suportados");

        byte[] bytecode = new Ir64BlockCompiler().compile(
                "dev/vitorsilverio/armjitter/codegen/generated/Ir64BlockCompilerMemoryAbortTestBlock", block);
        CompiledBlock64 compiled = new Jvm64BlockLoader().load(bytecode,
                "dev/vitorsilverio/armjitter/codegen/generated/Ir64BlockCompilerMemoryAbortTestBlock");

        // B6.3.4/B6.6.4: um LDXR pendente deve ser aberto por QUALQUER entrada de exceção — prova
        // que o handler gerado chama core.enterMemoryAbort de verdade, não só "não lança".
        core.markExclusiveMonitor(0x100, 8);

        compiled.execute(core); // MOVZ cacheia X1; LDR falta -> deve entrar em EL1, NÃO lançar

        assertEquals(DATA_ABORT_VA, core.x(1), "MOVZ deve ter executado antes da falta");
        assertTrue(core.exceptionState().inEl1(), "abort de memória deve entrar em EL1");
        assertEquals(HANDLER_PHYSICAL_ADDRESS, core.pc(),
                "PC deve saltar para VBAR_EL1(0) + offset síncrono de nível inferior (0x400)");
        assertEquals(4, core.exceptionState().elr1(), "ELR_EL1 deve ser o endereço da PRÓPRIA LDR faltosa");
        assertEquals(DATA_ABORT_VA, core.exceptionState().far1(), "FAR_EL1 deve ser o VA faltoso");
        long ec = core.exceptionState().esr1() >>> ESR_EC_SHIFT;
        assertEquals(ESR_EC_DATA_ABORT_LOWER_EL, ec, "ESR_EL1.EC deve ser Data Abort de EL inferior (0x24)");
        assertEquals(-1L, core.exclusiveMonitorAddress(),
                "entrada de exceção deve abrir o monitor de exclusividade (B6.3.4)");
    }
}
