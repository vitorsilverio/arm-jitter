package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbBootstrapDecoderTest {
    @Test
    void decodesThumbShiftImmediateAndAluRegister() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x0081);
        memory.put16(2, 0x4041);
        ThumbDecoder decoder = new ThumbDecoder();

        DecodedInstruction shift = decoder.decode(memory, 0);
        DecodedInstruction eor = decoder.decode(memory, 2);

        assertEquals(InstructionKind.LSL, shift.kind());
        assertEquals(1, shift.destinationRegister());
        assertEquals(0, shift.sourceRegister());
        assertEquals(2, shift.immediate());

        assertEquals(InstructionKind.EOR, eor.kind());
        assertEquals(1, eor.destinationRegister());
        assertEquals(1, eor.sourceRegister());
        assertEquals(0, eor.secondSourceRegister());
    }

    @Test
    void decodesZeroHalfwordAsThumbLslZero() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x0000);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.LSL, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(0, instruction.sourceRegister());
        assertEquals(0, instruction.immediate());
        assertTrue(instruction.immediateOperand());
        assertTrue(instruction.setFlags());
    }

    @Test
    void decodesThumbLiteralLoadSpAdjustPushAndPop() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x4801);
        memory.put16(2, 0xB081);
        memory.put16(4, 0xB503);
        memory.put16(6, 0xBD03);
        ThumbDecoder decoder = new ThumbDecoder();

        assertEquals(InstructionKind.LOAD_LITERAL, decoder.decode(memory, 0).kind());
        assertEquals(8, decoder.decode(memory, 0).immediate());

        DecodedInstruction sp = decoder.decode(memory, 2);
        assertEquals(InstructionKind.ADD, sp.kind());
        assertEquals(13, sp.destinationRegister());
        assertEquals(-4, sp.immediate());

        DecodedInstruction push = decoder.decode(memory, 4);
        assertEquals(InstructionKind.PUSH, push.kind());
        assertEquals(0x03, push.immediate());
        assertTrue(push.link());

        DecodedInstruction pop = decoder.decode(memory, 6);
        assertEquals(InstructionKind.POP, pop.kind());
        assertEquals(0x03, pop.immediate());
        assertTrue(pop.link());
    }
}
