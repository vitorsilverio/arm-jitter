package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbLongBranchInterpreterTest {
    @Test
    void executesThumbBlPair() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0xF801);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);

        core.step(2);

        assertEquals(6, core.programCounter());
        assertEquals(5, core.register(14));
    }
}
