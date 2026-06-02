package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmDecoderTest {
    @Test
    void decodesArmMovImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE3A0_002A);

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionSet.ARM, instruction.instructionSet());
        assertEquals(Condition.AL, instruction.condition());
        assertEquals(InstructionKind.MOV, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(42, instruction.immediate());
        assertTrue(instruction.immediateOperand());
    }

    @Test
    void decodesArmBranchWithPipelineOffset() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xEA00_0001);

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.BRANCH, instruction.kind());
        assertEquals(12, instruction.immediate());
    }

    @Test
    void decodesArmClz() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE16F_1F10);

        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);

        assertEquals(InstructionKind.CLZ, instruction.kind());
        assertEquals(1, instruction.destinationRegister());
        assertEquals(0, instruction.sourceRegister());
    }
}
