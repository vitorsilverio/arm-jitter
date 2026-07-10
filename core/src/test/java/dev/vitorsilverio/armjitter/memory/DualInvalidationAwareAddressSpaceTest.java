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
}
