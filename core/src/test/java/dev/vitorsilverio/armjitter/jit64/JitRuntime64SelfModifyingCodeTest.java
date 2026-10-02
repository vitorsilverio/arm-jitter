package dev.vitorsilverio.armjitter.jit64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// F11 (2026-10-02): o `kernel8.img` do Raspberry Pi 3 troca `b` por `nop` em tempo de execução
/// (jump labels/static keys) e publica a mudança com `IC IVAU`. O interpretado decodifica a
/// memória a cada passo, então via o código novo; o JIT A64 não tinha invalidação nenhuma e
/// seguia executando o bloco compilado antigo — a divergência que travava o boot em
/// `Mountpoint-cache hash table`. Aqui o ciclo completo: compila, reescreve SEM manutenção (o
/// bloco antigo continua valendo, como no hardware), depois invalida e vê o código novo.
class JitRuntime64SelfModifyingCodeTest {
    private static final int MOVZ_X0_1 = 0xd2800020;
    private static final int MOVZ_X0_2 = 0xd2800040;
    private static final int MOVZ_X0_3 = 0xd2800060;
    private static final int BRANCH_TO_SELF = 0x1400_0000; // b .

    private static final long L0 = 0x10000L;
    private static final long L1 = 0x11000L;
    private static final long L2 = 0x12000L;
    private static final long L3 = 0x13000L;
    private static final long DESC_VALID = 0b1L;
    private static final long DESC_TABLE_OR_PAGE = 0b10L;
    private static final long ACCESS_FLAG_AND_AP = (0b01L << 6);
    private static final long ADDRESS_MASK = 0x0000_FFFF_FFFF_F000L;
    private static final long PAGE = 0x1000L;

    private static JitRuntime64 newRuntime() {
        return new JitRuntime64(new BlockCache64(), new StandardIr64BlockLifter(), new Ir64BlockExecutor(),
                new Asm64CodeEmitter(), new ExecutionThreshold(1), 64);
    }

    /// `movz x0, #N ; b .` em 0x0 — o laço fecha o bloco e `x0` mostra qual versão do código rodou.
    private static Aarch64Core coreWithLoop(int firstInstruction) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        core.memory().write32(0, firstInstruction);
        core.memory().write32(4, BRANCH_TO_SELF);
        return core;
    }

    private static long runBlockAndReadX0(JitRuntime64 runtime, Aarch64Core core) {
        core.setX(0, 0);
        runtime.execute(0, core);
        return core.x(0);
    }

    @Test
    void staleBlockKeepsRunningUntilTheGuestInvalidatesTheInstructionCache() {
        Aarch64Core core = coreWithLoop(MOVZ_X0_1);
        JitRuntime64 runtime = newRuntime();
        runtime.execute(0, core);
        assertEquals(1L, runBlockAndReadX0(runtime, core), "bloco compilado com a versão 1");

        core.memory().write32(0, MOVZ_X0_2); // reescreve SEM manutenção de cache

        assertEquals(1L, runBlockAndReadX0(runtime, core), "sem IC, o hardware também executaria o código velho");
    }

    @Test
    void icIvauOnTheModifiedPageMakesTheNewCodeRun() {
        Aarch64Core core = coreWithLoop(MOVZ_X0_1);
        JitRuntime64 runtime = newRuntime();
        runtime.execute(0, core);
        assertEquals(1L, runBlockAndReadX0(runtime, core));

        core.memory().write32(0, MOVZ_X0_2);
        core.invalidateInstructionCacheByVirtualAddress(0);

        assertEquals(2L, runBlockAndReadX0(runtime, core));
    }

    @Test
    void icIalluDropsEveryCompiledBlock() {
        Aarch64Core core = coreWithLoop(MOVZ_X0_1);
        JitRuntime64 runtime = newRuntime();
        runtime.execute(0, core);
        assertEquals(1L, runBlockAndReadX0(runtime, core));
        assertEquals(1, runtime.blockCache().size());

        core.memory().write32(0, MOVZ_X0_3);
        core.invalidateInstructionCacheAll();

        assertEquals(0, runtime.blockCache().size());
        assertEquals(3L, runBlockAndReadX0(runtime, core));
    }

    @Test
    void invalidatingAnUnrelatedPageKeepsTheCompiledBlock() {
        Aarch64Core core = coreWithLoop(MOVZ_X0_1);
        JitRuntime64 runtime = newRuntime();
        runtime.execute(0, core);
        runBlockAndReadX0(runtime, core);

        core.memory().write32(0, MOVZ_X0_2);
        core.invalidateInstructionCacheByVirtualAddress(0x800_0000L);

        assertEquals(1L, runBlockAndReadX0(runtime, core), "outra página: o bloco continua válido");
    }

    @Test
    void runtimeInstallsItselfAsTheListenerOfTheCore() {
        Aarch64Core core = coreWithLoop(MOVZ_X0_1);
        JitRuntime64 runtime = newRuntime();

        runtime.execute(0, core);

        assertSame(runtime, core.instructionCacheListener());
    }

    @Test
    void invalidationWithoutAnyListenerIsANoOp() {
        Aarch64Core core = coreWithLoop(MOVZ_X0_1);

        core.invalidateInstructionCacheAll();
        core.invalidateInstructionCacheByVirtualAddress(0);

        assertEquals(null, core.instructionCacheListener());
    }

    /// Tabelas de página à mão: VA `0x0000` → PA `0x0000` (código) e um ALIAS de VA `0x4000` para a
    /// MESMA página física. `IC IVAU` pelo alias tem de derrubar o bloco compilado pelo VA original.
    private static TranslatingAddressSpace64 aliasedMmu(AddressSpace64 physical) {
        physical.write64(L0, (L1 & ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID);
        physical.write64(L1, (L2 & ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID);
        physical.write64(L2, (L3 & ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID);
        long page = DESC_TABLE_OR_PAGE | DESC_VALID | ACCESS_FLAG_AND_AP;
        physical.write64(L3, 0x0000L | page);                 // VA 0x0000 → PA 0x0000
        physical.write64(L3 + 4 * 8, 0x0000L | page);         // VA 0x4000 → PA 0x0000 (alias)
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(L0);
        return mmu;
    }

    @Test
    void icIvauThroughAnAliasInvalidatesTheBlockCompiledAtTheOriginalVirtualAddress() {
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x20000));
        physical.write32(0, MOVZ_X0_1);
        physical.write32(4, BRANCH_TO_SELF);
        Aarch64Core core = new Aarch64Core(aliasedMmu(physical));
        JitRuntime64 runtime = newRuntime();
        runtime.execute(0, core);
        assertEquals(1L, runBlockAndReadX0(runtime, core));

        physical.write32(0, MOVZ_X0_2);
        core.invalidateInstructionCacheByVirtualAddress(4 * PAGE); // alias, não o VA de execução

        assertEquals(2L, runBlockAndReadX0(runtime, core));
    }

    @Test
    void blockSpanningTwoPagesIsDroppedByInvalidatingEitherOne() {
        // Duas páginas mapeadas em identidade, cheias de `movz x0,#1` (nunca termina o bloco); um bloco
        // que começa 16 bytes antes de 0x1000 atravessa a fronteira (limite padrão de 64 instruções).
        AddressSpace64 physical = AddressSpace64.wrapping(new TestAddressSpace(0x20000));
        for (long address = 0; address < 2 * PAGE; address += 4) {
            physical.write32(address, MOVZ_X0_1);
        }
        physical.write64(L0, (L1 & ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID);
        physical.write64(L1, (L2 & ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID);
        physical.write64(L2, (L3 & ADDRESS_MASK) | DESC_TABLE_OR_PAGE | DESC_VALID);
        long page = DESC_TABLE_OR_PAGE | DESC_VALID | ACCESS_FLAG_AND_AP;
        physical.write64(L3, 0x0000L | page);
        physical.write64(L3 + 8, PAGE | page);
        TranslatingAddressSpace64 mmu = new TranslatingAddressSpace64(physical);
        mmu.setTtbr0(L0);
        Aarch64Core core = new Aarch64Core(mmu);
        JitRuntime64 runtime = newRuntime();

        runtime.execute(PAGE - 16, core);
        assertEquals(1, runtime.blockCache().size());

        core.invalidateInstructionCacheByVirtualAddress(PAGE + 0x40); // só a SEGUNDA página
        assertEquals(0, runtime.blockCache().size(), "o bloco cobre as duas páginas");
    }
}
