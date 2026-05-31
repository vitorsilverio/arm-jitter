package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryDecoderTest {
    @Test
    void decodesArmLdrAndStrImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE590_1004);
        memory.put32(4, 0xE580_1008);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction ldr = decoder.decode(memory, 0);
        DecodedInstruction str = decoder.decode(memory, 4);

        assertEquals(InstructionKind.LOAD, ldr.kind());
        assertEquals(1, ldr.destinationRegister());
        assertEquals(0, ldr.sourceRegister());
        assertEquals(4, ldr.immediate());
        assertEquals(4, ldr.accessSizeBytes());

        assertEquals(InstructionKind.STORE, str.kind());
        assertEquals(1, str.destinationRegister());
        assertEquals(0, str.sourceRegister());
        assertEquals(8, str.immediate());
        assertEquals(4, str.accessSizeBytes());
    }

    @Test
    void decodesThumbLdrAndStrWordImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x6041);
        memory.put16(2, 0x6841);
        ThumbDecoder decoder = new ThumbDecoder();

        DecodedInstruction str = decoder.decode(memory, 0);
        DecodedInstruction ldr = decoder.decode(memory, 2);

        assertEquals(InstructionKind.STORE, str.kind());
        assertEquals(1, str.destinationRegister());
        assertEquals(0, str.sourceRegister());
        assertEquals(4, str.immediate());

        assertEquals(InstructionKind.LOAD, ldr.kind());
        assertEquals(1, ldr.destinationRegister());
        assertEquals(0, ldr.sourceRegister());
        assertEquals(4, ldr.immediate());
    }
}
