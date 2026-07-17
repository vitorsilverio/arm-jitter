package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes de {@link JitRuntime#hotBlockKeys(int)} e {@link JitRuntime#precompile} (task C10 —
/// warm-start do JIT). Espelha o padrão de {@link JitRuntimeTest} (blocos sintéticos MOV+ADD+trap).
class JitRuntimeWarmStartTest {
    private static void writeSimpleBlock(TestAddressSpace memory, int pc) {
        memory.put32(pc, 0xE3A0_000A);      // MOV R0, #10
        memory.put32(pc + 4, 0xE280_0005);  // ADD R0, R0, #5
        memory.put32(pc + 8, 0xE7F0_00F0);  // trap: fecha o bloco (mesmo padrão de JitRuntimeTest)
    }

    @Test
    void hotBlockKeysDelegatesToBlockCache() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeSimpleBlock(memory, 0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 1);

        runtime.execute(0, core); // threshold=1: já promove a chave no primeiro hit

        List<BlockKey> hot = runtime.hotBlockKeys(10);
        assertEquals(List.of(new BlockKey(0, InstructionSet.ARM)), hot);
    }

    @Test
    void precompileSchedulesBackgroundCompilationOfAnUnvisitedKey() throws InterruptedException {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeSimpleBlock(memory, 0);
        // Threshold alto: sem precompile, o bloco nunca compilaria nas poucas execuções do teste.
        JitRuntime runtime = JitRuntimeFactory.armThumb(16, 100, ArmArchitecture.ARMV4T);
        BlockKey key = new BlockKey(0, InstructionSet.ARM);

        runtime.precompile(List.of(key), memory);

        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        BlockCache.CacheEntry entry = waitForCompiled(runtime, core, key);
        assertTrue(entry.compiled());
        // A MESMA chamada de `execute` que integrou o resultado de background já rodou o bloco
        // pelo caminho quente (integração acontece antes do despacho, dentro de `executeTiered`).
        assertEquals(15, core.register(0));
    }

    @Test
    void precompileIgnoresKeysThatFailToDecode() {
        TestAddressSpace memory = new TestAddressSpace(4); // muito pequeno: qualquer PC>0 estoura
        JitRuntime runtime = JitRuntimeFactory.armThumb(16, 100, ArmArchitecture.ARMV4T);
        BlockKey outOfBounds = new BlockKey(0x1000, InstructionSet.ARM);

        assertDoesNotThrow(() -> runtime.precompile(List.of(outOfBounds), memory));

        assertNull(runtime.blockCache().entry(outOfBounds));
    }

    @Test
    void precompileIsNoOpOnNonTieredRuntime() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeSimpleBlock(memory, 0);
        JitRuntime runtime = JitRuntimeFactory.interpretedArm(16, 1); // sem coldEmitter: não-tiered
        BlockKey key = new BlockKey(0, InstructionSet.ARM);

        assertDoesNotThrow(() -> runtime.precompile(List.of(key), memory));

        assertEquals(0, runtime.blockCache().size());
    }

    @Test
    void precompileSkipsKeysAlreadyInCache() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeSimpleBlock(memory, 0);
        JitRuntime runtime = JitRuntimeFactory.armThumb(16, 100, ArmArchitecture.ARMV4T);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        BlockKey key = new BlockKey(0, InstructionSet.ARM);

        runtime.execute(0, core); // cria a entrada fria
        assertEquals(1, runtime.blockCache().size());

        runtime.precompile(List.of(key), memory); // já presente: ignora, não duplica

        assertEquals(1, runtime.blockCache().size());
    }

    /// Espera (com timeout) o pool de compilação de background integrar o resultado: `execute`
    /// no próprio `pc` da chave drena `compiled` via `integrateCompiled` (chamado no topo de
    /// `executeTiered`) ANTES de despachar, então a chamada que observa `compiled()==true`
    /// já rodou o bloco pelo caminho quente na mesma volta.
    private static BlockCache.CacheEntry waitForCompiled(JitRuntime runtime, ArmCore core, BlockKey key)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            core.setRegister(0, 0);
            runtime.execute(key.pc(), core);
            BlockCache.CacheEntry entry = runtime.blockCache().entry(key);
            if (entry != null && entry.compiled()) {
                return entry;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("timed out waiting for background compile of " + key);
    }
}
