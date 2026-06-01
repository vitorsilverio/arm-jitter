package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PsrInterpretedCodeEmitterTest {
    @Test
    void executesPsrTransfersThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE129_F003);
        memory.put32(4, 0xE10F_1000);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.setRegister(3, 0xA000_0013);
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(CpuMode.SUPERVISOR, core.mode());
        assertEquals(core.cpsr().get(), core.register(1));
    }

    @Test
    void executesMsrImmediateThroughRuntimeBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE321_F013);
        memory.put32(4, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(1, core.runBlocks(runtime, 1));

        assertEquals(CpuMode.SUPERVISOR, core.mode());
    }
}
