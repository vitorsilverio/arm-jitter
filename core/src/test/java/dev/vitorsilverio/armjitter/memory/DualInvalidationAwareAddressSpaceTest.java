package dev.vitorsilverio.armjitter.memory;

import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DualInvalidationAwareAddressSpaceTest {
    @Test
    void writeInvalidatesTheCachedBlockInBothRuntimes() {
        TestAddressSpace delegate = new TestAddressSpace(32);
        JitRuntime first = JitRuntimeFactory.interpretedArmThumb(16, 1);
        JitRuntime second = JitRuntimeFactory.interpretedArmThumb(16, 1);
        CompiledBlock block = core -> 1;
        first.blockCache().put(0, block);
        second.blockCache().put(0, block);
        AddressSpace memory = new DualInvalidationAwareAddressSpace(delegate, first, second);

        memory.write32(0, 0xE3A0_0001);

        assertEquals(0, first.blockCache().size(), "runtime próprio deve invalidar");
        assertEquals(0, second.blockCache().size(), "runtime da OUTRA CPU deve invalidar");
        assertEquals(0xE3A0_0001, memory.read32(0), "a escrita chega ao delegado");
    }

    @Test
    void alignedWordCopyInvalidatesSingleThumbBlockBetweenWriteBases() {
        // Regressão (loop da Buneary): a troca de overlay copia código em words alinhadas;
        // um bloco THUMB de 1 instrução em [X+2, X+4) não contém NENHUM endereço-base da
        // cópia (X e X+4) e sobrevivia — bloco do overlay antigo executava no overlay novo.
        TestAddressSpace delegate = new TestAddressSpace(32);
        JitRuntime first = JitRuntimeFactory.interpretedArmThumb(16, 1);
        JitRuntime second = JitRuntimeFactory.interpretedArmThumb(16, 1);
        first.blockCache().put(new dev.vitorsilverio.armjitter.jit.BlockKey(6,
                dev.vitorsilverio.armjitter.decoder.InstructionSet.THUMB), core -> 1, 6, 8);
        AddressSpace memory = new DualInvalidationAwareAddressSpace(delegate, first, second);

        memory.write32(4, 0x46C0_46C0); // word alinhada cobrindo [4, 8)

        assertEquals(0, first.blockCache().size(),
                "o bloco de 1 instrução THUMB no meio da word deve ser invalidado");
    }

    @Test
    void writeOutsideAnyCodePageStillReachesTheDelegate() {
        TestAddressSpace delegate = new TestAddressSpace(32);
        JitRuntime first = JitRuntimeFactory.interpretedArmThumb(16, 1);
        JitRuntime second = JitRuntimeFactory.interpretedArmThumb(16, 1);
        AddressSpace memory = new DualInvalidationAwareAddressSpace(delegate, first, second);

        memory.write16(4, 0x1234);
        memory.write8(6, 0x56);

        assertEquals(0x1234, memory.read16(4));
        assertEquals(0x56, memory.read8(6));
    }

    /// Mesmo bug real corrigido em `InvalidationAwareAddressSpaceTest
    /// #delegatesInstructionFetchToTheFetchPathNotTheReadPath` -- ver Javadoc daquele teste.
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
        JitRuntime first = JitRuntimeFactory.interpretedArmThumb(16, 1);
        JitRuntime second = JitRuntimeFactory.interpretedArmThumb(16, 1);
        AddressSpace memory = new DualInvalidationAwareAddressSpace(delegate, first, second);

        assertEquals(0xF00D, memory.fetch16(0));
        assertEquals(0xF00D_F00D, memory.fetch32(0));
    }

    /// Mesmo bug real corrigido em `InvalidationAwareAddressSpaceTest#delegatesTranslationGeneration`.
    @Test
    void delegatesTranslationGeneration() {
        TestAddressSpace delegate = new TestAddressSpace(32);
        JitRuntime first = JitRuntimeFactory.interpretedArmThumb(16, 1);
        JitRuntime second = JitRuntimeFactory.interpretedArmThumb(16, 1);
        AddressSpace memory = new DualInvalidationAwareAddressSpace(delegate, first, second);

        assertEquals(delegate.translationGeneration(), memory.translationGeneration());
    }
}
