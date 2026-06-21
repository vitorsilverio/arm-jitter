package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Exercita o inline cache de dispatch do {@link JitRuntime} ponta-a-ponta via `execute`:
/// a re-execução acerta o IC e o código automodificável (SMC) força recompilação — provando
/// que uma entrada obsoleta do inline cache nunca executa o bloco velho.
class JitRuntimeInlineCacheTest {
    private static final int MOV_R0_10 = 0xE3A0_000A; // mov r0, #10
    private static final int MOV_R0_20 = 0xE3A0_0014; // mov r0, #20
    private static final int UNDEF = 0xE7F0_00F0;     // instrução indefinida: encerra o bloco

    @Test
    void selfModifyingCodeAtSamePcRecompilesUnderInterpreter() {
        assertSmcRecompiles(JitRuntimeFactory.interpretedArm(64, 1));
    }

    @Test
    void selfModifyingCodeAtSamePcRecompilesUnderJvmBytecode() {
        assertSmcRecompiles(JitRuntimeFactory.armThumb(64, 1));
    }

    private void assertSmcRecompiles(JitRuntime runtime) {
        TestAddressSpace backing = new TestAddressSpace(64);
        backing.put32(0, MOV_R0_10);
        backing.put32(4, UNDEF);
        AddressSpace memory = new InvalidationAwareAddressSpace(backing, runtime);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        // 1ª execução: compila o bloco em pc=0 e popula o inline cache.
        runtime.execute(0, core);
        assertEquals(10, core.register(0));
        assertEquals(1, runtime.blockCache().size());

        // 2ª execução: acerta o inline cache (nenhum bloco novo entra no cache).
        core.setRegister(0, 0);
        runtime.execute(0, core);
        assertEquals(10, core.register(0));
        assertEquals(1, runtime.blockCache().size());

        // SMC: reescreve o código em pc=0. A escrita invalida o bloco e bumpa a geração;
        // o inline cache precisa descartar a entrada obsoleta.
        long genBefore = runtime.blockCache().generation();
        memory.write32(0, MOV_R0_20);
        assertTrue(runtime.blockCache().generation() > genBefore, "SMC deve bumpar a geração");
        assertEquals(0, runtime.blockCache().size(), "bloco obsoleto removido do cache");

        // 3ª execução: recompila a partir do novo código — não pode devolver o bloco velho.
        core.setRegister(0, 0);
        runtime.execute(0, core);
        assertEquals(20, core.register(0), "inline cache não pode devolver o bloco obsoleto após SMC");
    }

    @Test
    void unrelatedDataWriteKeepsInlineCacheWarm() {
        TestAddressSpace backing = new TestAddressSpace(0x2000);
        backing.put32(0, MOV_R0_10);
        backing.put32(4, UNDEF);
        JitRuntime runtime = JitRuntimeFactory.armThumb(64, 1);
        AddressSpace memory = new InvalidationAwareAddressSpace(backing, runtime);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        runtime.execute(0, core);
        long gen = runtime.blockCache().generation();

        // Escrita numa página de dados distante, sem código compilado: no-op para a
        // invalidação (geração intacta ⇒ o inline cache permanece válido).
        memory.write32(0x1000, 0xDEAD_BEEF);
        assertEquals(gen, runtime.blockCache().generation());
        assertEquals(1, runtime.blockCache().size());

        core.setRegister(0, 0);
        runtime.execute(0, core);
        assertEquals(10, core.register(0));
    }

    @Test
    void armAndThumbBlocksAtSamePcStayDistinctThroughInlineCache() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_202A); // mov r2, #42 (ARM)
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(64, 1);

        ArmCore armCore = new ArmCore(memory, SwiDispatcher.empty());
        ArmCore thumbCore = new ArmCore(memory, SwiDispatcher.empty());
        thumbCore.cpsr().setThumbMode(true);

        // Alterna ARM/THUMB no mesmo pc=0 (mesmo slot do IC, tags distintas) várias vezes:
        // a tag do inline cache precisa distinguir os conjuntos de instruções.
        for (int i = 0; i < 4; i++) {
            runtime.execute(0, armCore);
            runtime.execute(0, thumbCore);
        }

        assertEquals(42, armCore.register(2)); // bloco ARM
        assertEquals(42, thumbCore.register(0)); // bloco THUMB (mov r0, #42)
        assertEquals(2, runtime.blockCache().size());
    }
}
