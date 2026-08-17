package dev.vitorsilverio.armjitter.memory;

import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvalidationAwareAddressSpaceTest {
    @Test
    void invalidatesCachedBlockOnWrite() {
        TestAddressSpace delegate = new TestAddressSpace(32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);
        CompiledBlock block = core -> 1;
        runtime.blockCache().put(0, block);
        AddressSpace memory = new InvalidationAwareAddressSpace(delegate, runtime);

        memory.write32(0, 0xE3A0_0001);

        assertEquals(0, runtime.blockCache().size());
    }

    /// Achado real (F3/`virtual-arm-box`): antes deste teste existir, `fetch16`/`fetch32` caíam
    /// no `default` de {@link AddressSpace} (que delega a `read16`/`read32` DESTE MESMO
    /// decorador, não do delegado) — sob um delegado com MMU (`TranslatingAddressSpace`) isso
    /// desviava toda busca de instrução do caminho de INSTRUCTION_FETCH para o de DATA_READ,
    /// trocando `PREFETCH_ABORT` por `DATA_ABORT` numa falha de tradução. Delegado de teste com
    /// valores DIFERENTES em `fetchNN` vs. `readNN` prova que o decorador chama o método certo.
    @Test
    void delegatesInstructionFetchToTheFetchPathNotTheReadPath() {
        AddressSpace delegate = new AddressSpace() {
            @Override
            public int read8(int address) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read16(int address) {
                return 0xDEAD;
            }

            @Override
            public int read32(int address) {
                return 0xDEAD_DEAD;
            }

            @Override
            public void write8(int address, int value) {
            }

            @Override
            public void write16(int address, int value) {
            }

            @Override
            public void write32(int address, int value) {
            }

            @Override
            public int fetch16(int address) {
                return 0xF00D;
            }

            @Override
            public int fetch32(int address) {
                return 0xF00D_F00D;
            }
        };
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);
        AddressSpace memory = new InvalidationAwareAddressSpace(delegate, runtime);

        assertEquals(0xF00D, memory.fetch16(0));
        assertEquals(0xF00D_F00D, memory.fetch32(0));
    }

    /// Achado real (F3/`virtual-arm-box`): `translationGeneration()` também caía no `default`
    /// (constante `0`) em vez de encaminhar ao delegado — o `JitRuntime` nunca invalidaria blocos
    /// compilados sob uma tabela de páginas antiga após uma troca de `TTBR0`/`CONTEXTIDR`
    /// (RFC-SOFTMMU §5, B4.1.4) para qualquer consumidor que envolvesse uma
    /// `TranslatingAddressSpace` neste decorador.
    @Test
    void delegatesTranslationGeneration() {
        TestAddressSpace delegate = new TestAddressSpace(32);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);
        AddressSpace memory = new InvalidationAwareAddressSpace(delegate, runtime);

        assertEquals(delegate.translationGeneration(), memory.translationGeneration());
    }
}
