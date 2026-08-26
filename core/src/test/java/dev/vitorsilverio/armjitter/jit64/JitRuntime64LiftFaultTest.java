package dev.vitorsilverio.armjitter.jit64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// F11 (sessão de retomada, 2026-08-26): achado real — o `lift()` de um bloco QUENTE em
/// {@link JitRuntime64#execute} nunca teve a proteção de
/// {@code MemoryTranslationException64} que o precedente 32-bit já tem desde B4.1.5
/// ({@code JitRuntime#LIFT_FAULT_CYCLES}, ver o Javadoc daquela constante). O lifter decodifica
/// instruções À FRENTE do `pc` atual só para decidir onde o bloco termina (até
/// `maxBlockInstructions`) — se esse lookahead cruzar para uma página SEM descritor válido (nunca
/// de fato alcançada pela execução real, já que um desvio poderia ter ocorrido antes), a exceção
/// escapava do runtime inteiro em vez de entrar no handler do guest. Isto bloqueava o boot real da
/// F11 (`virtual-arm-box --machine=raspi3-64`, backend JIT): um bloco reto e longo o bastante para
/// cruzar a borda da última página mapeada travava com `MemoryTranslationException64` não
/// capturada, divergindo do backend INTERPRETED (que nunca faz lookahead — só toca a página quando
/// a execução de fato chega lá).
class JitRuntime64LiftFaultTest {
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

    private static final int MOVZ_X0_1 = 0xd2800020; // movz x0, #1 -- nunca terminal (não desvia)
    /// Maior que o número de instruções que cabem na ÚNICA página mapeada (`0x1000` / `4` =
    /// `1024`) — força o lookahead do lifter a cruzar para a página seguinte, SEM descritor.
    private static final int MAX_BLOCK_INSTRUCTIONS = 2000;

    private static long tableDescriptor(long nextTableBase) {
        return (nextTableBase & OUTPUT_ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    private static long identityPageDescriptor() {
        return ((long) AP_FULL_ACCESS << AP_SHIFT) | DESC_TABLE_OR_PAGE | DESC_VALID;
    }

    /// Mapeia identidade SÓ a página `[0x000,0x1000)` (código) — a página seguinte, onde o
    /// lookahead do lifter tropeça, fica deliberadamente SEM descritor (índice `L0` diferente,
    /// inválido).
    private static TranslatingAddressSpace64 singlePageMmu(AddressSpace64 physical) {
        physical.write64(L0_TABLE_BASE, tableDescriptor(L1_TABLE_BASE));
        physical.write64(L1_TABLE_BASE, tableDescriptor(L2_TABLE_BASE));
        physical.write64(L2_TABLE_BASE, tableDescriptor(L3_TABLE_BASE));
        physical.write64(L3_TABLE_BASE, identityPageDescriptor());
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(L0_TABLE_BASE);
        return mmu;
    }

    @Test
    void liftFaultBeyondLastMappedPageEntersGuestAbortInsteadOfThrowingToHost() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(PHYSICAL_SIZE_BYTES));
        for (long addr = 0; addr < 0x1000; addr += 4) {
            physical.write32(addr, MOVZ_X0_1);
        }
        TranslatingAddressSpace64 mmu = singlePageMmu(physical);
        Aarch64Core core = new Aarch64Core(mmu);
        JitRuntime64 runtime = new JitRuntime64(
                new BlockCache64(),
                new StandardIr64BlockLifter(),
                new Ir64BlockExecutor(),
                new Asm64CodeEmitter(),
                new ExecutionThreshold(1),
                MAX_BLOCK_INSTRUCTIONS);

        int cycles = runtime.execute(0, core); // NÃO deve lançar MemoryTranslationException64

        assertEquals(1, cycles, "ciclos parciais do lift faltoso, mesmo valor do precedente 32-bit");
        assertEquals(0, runtime.blockCache().size(), "nenhum bloco (ruim) deve ter sido cacheado");
        assertTrue(core.exceptionState().inEl1(), "a falta de lift deve entrar no handler do guest, não escapar");
    }
}
