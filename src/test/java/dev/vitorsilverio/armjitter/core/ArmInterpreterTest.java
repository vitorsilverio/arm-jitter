package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmInterpreterTest {
    @Test
    void stepsArmMovAddCmpSequence() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_000A);
        memory.put32(4, 0xE280_0005);
        memory.put32(8, 0xE350_000F);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        core.step();
        core.step();
        core.step();

        assertEquals(15, core.register(0));
        assertEquals(12, core.programCounter());
        assertTrue(core.cpsr().zero());
        assertEquals(3, core.cycles());
    }

    @Test
    void skipsArmInstructionWhenConditionFails() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0x03A0_0001);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        core.step();

        assertEquals(0, core.register(0));
        assertEquals(4, core.programCounter());
    }

    @Test
    void stepsThumbMovAndAddImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x2020);
        memory.put16(2, 0x3002);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);

        core.step();
        core.step();

        assertEquals(34, core.register(0));
        assertEquals(4, core.programCounter());
        assertFalse(core.cpsr().zero());
    }

    @Test
    void dispatchesSwiAndAppliesReturnedState() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xEF00_0008);
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.register(0x08, state -> state.withR0(99));
        ArmCore core = new ArmCore(memory, dispatcher);

        core.step();

        assertEquals(99, core.register(0));
        assertEquals(4, core.programCounter());
    }
}
