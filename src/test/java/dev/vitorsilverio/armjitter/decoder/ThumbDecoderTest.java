package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbDecoderTest {
    @Test
    void decodesThumbMovImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x202A);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionSet.THUMB, instruction.instructionSet());
        assertEquals(InstructionKind.MOV, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(42, instruction.immediate());
        assertTrue(instruction.setFlags());
    }

    @Test
    void decodesThumbUnconditionalBranchWithPipelineOffset() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xE001);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.BRANCH, instruction.kind());
        assertEquals(6, instruction.immediate());
    }

    @Test
    void decodesReservedConditionalBranchAsUnimplemented() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xDE00);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
    }
}
