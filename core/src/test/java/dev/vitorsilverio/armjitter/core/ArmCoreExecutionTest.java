package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArmCoreExecutionTest {
    @Test
    void stepsMultipleInstructionsWithInterpreter() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());

        assertEquals(2, core.step(2));

        assertEquals(3, core.register(0));
        assertEquals(8, core.programCounter());
    }

    @Test
    void runsSingleBlockWithRuntime() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        memory.put32(8, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);

        assertEquals(2, core.runBlocks(runtime, 1));

        assertEquals(3, core.register(0));
        assertEquals(8, core.programCounter());
    }

    @Test
    void tracesInterpreterInstructionsAndRuntimeBlocks() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE7F0_00F0);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        List<String> events = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void beforeInstruction(ArmCore tracedCore, int pc, InstructionSet instructionSet) {
                events.add("before-instruction:%08X:%s".formatted(pc, instructionSet));
            }

            @Override
            public void afterInstruction(ArmCore tracedCore, DecodedInstruction instruction) {
                events.add("after-instruction:%08X:%s".formatted(instruction.address(), instruction.kind()));
            }

            @Override
            public void beforeBlock(ArmCore tracedCore, int pc, InstructionSet instructionSet) {
                events.add("before-block:%08X:%s".formatted(pc, instructionSet));
            }

            @Override
            public void afterBlock(ArmCore tracedCore, int startPc, InstructionSet instructionSet, int cycles) {
                events.add("after-block:%08X:%s:%d".formatted(startPc, instructionSet, cycles));
            }
        });

        core.step();
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(16, 1);
        core.runBlock(runtime);

        assertEquals("before-instruction:00000000:ARM", events.get(0));
        assertEquals("after-instruction:00000000:" + InstructionKind.MOV, events.get(1));
        assertEquals("before-block:00000004:ARM", events.get(2));
        assertEquals("after-block:00000004:ARM:1", events.get(events.size() - 1));
    }
}
