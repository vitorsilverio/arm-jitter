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
}
