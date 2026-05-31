package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbAddressingDecoderTest {
    @Test
    void decodesAddSubRegisterImmediateAndCmpImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x1888);
        memory.put16(2, 0x1E88);
        memory.put16(4, 0x2807);
        ThumbDecoder decoder = new ThumbDecoder();

        assertEquals(InstructionKind.ADD, decoder.decode(memory, 0).kind());
        assertFalse(decoder.decode(memory, 0).immediateOperand());

        assertEquals(InstructionKind.SUB, decoder.decode(memory, 2).kind());
        assertTrue(decoder.decode(memory, 2).immediateOperand());

        assertEquals(InstructionKind.CMP, decoder.decode(memory, 4).kind());
        assertEquals(7, decoder.decode(memory, 4).immediate());
    }

    @Test
    void decodesHalfwordSpRelativeAndLoadAddress() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x8041);
        memory.put16(2, 0x8841);
        memory.put16(4, 0x9001);
        memory.put16(6, 0x9801);
        memory.put16(8, 0xA001);
        memory.put16(10, 0xA801);
        ThumbDecoder decoder = new ThumbDecoder();

        assertEquals(2, decoder.decode(memory, 0).accessSizeBytes());
        assertEquals(InstructionKind.STORE, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.LOAD, decoder.decode(memory, 2).kind());
        assertEquals(13, decoder.decode(memory, 4).sourceRegister());
        assertEquals(13, decoder.decode(memory, 6).sourceRegister());
        assertEquals(InstructionKind.MOV, decoder.decode(memory, 8).kind());
        assertEquals(16, decoder.decode(memory, 8).immediate());
        assertEquals(InstructionKind.ADD, decoder.decode(memory, 10).kind());
        assertEquals(13, decoder.decode(memory, 10).sourceRegister());
    }
}
