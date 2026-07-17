package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbAluExecutionTest {
    @Test
    void executesCarryNegateRotateAndCmnWithInterpreter() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0x4148);
        memory.put16(2, 0x4188);
        memory.put16(4, 0x41CA);
        memory.put16(6, 0x4253);
        memory.put16(8, 0x42D3);
        memory.put16(10, 0x438C);
        memory.put16(12, 0x428C);
        ArmCore core = thumbCore(memory);
        core.setRegister(0, 5);
        core.setRegister(1, 2);
        core.setRegister(2, 0x8000_0001);
        core.setRegister(3, 0);
        core.setRegister(4, 0b1111);
        core.cpsr().setNzcv(false, false, true, false);

        assertEquals(7, core.step(7));

        assertEquals(5, core.register(0));
        assertEquals(0x6000_0000, core.register(2));
        assertEquals(-0x6000_0000, core.register(3));
        assertEquals(0b1101, core.register(4));
        assertFalse(core.cpsr().zero());
        assertTrue(core.cpsr().carry());
    }

    @Test
    void executesSignedRegisterOffsetLoadsThroughRuntime() {
        TestAddressSpace memory = new TestAddressSpace(128);
        memory.put16(0, 0x5642);
        memory.put16(2, 0x5E43);
        memory.put16(4, 0xB100);
        memory.put16(33, 0xFF80); // configuração bruta: byte com sinal 0x80 no endereço ímpar 33
        ArmCore core = thumbCore(memory);
        core.setRegister(0, 32);
        core.setRegister(1, 1);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(-128, core.register(2));
        assertEquals(-128, core.register(3));
        assertEquals(4, core.programCounter());
    }

    private ArmCore thumbCore(TestAddressSpace memory) {
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        return core;
    }
}
