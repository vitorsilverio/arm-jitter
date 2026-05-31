package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultiplyInterpreterTest {
    @Test
    void executesArmAndThumbMul() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE001_0290);
        memory.put16(8, 0x4348);
        ArmCore arm = new ArmCore(memory, SwiDispatcher.empty());
        arm.setRegister(0, 6);
        arm.setRegister(2, 7);

        arm.step();

        assertEquals(42, arm.register(1));

        ArmCore thumb = new ArmCore(memory, SwiDispatcher.empty());
        thumb.cpsr().setThumbMode(true);
        thumb.setProgramCounter(8);
        thumb.setRegister(0, 6);
        thumb.setRegister(1, 7);

        thumb.step();

        assertEquals(42, thumb.register(0));
    }
}
