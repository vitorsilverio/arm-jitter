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
    void decodesArmLdrAndStrRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE790_2001);
        memory.put32(4, 0xE780_2001);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction ldr = decoder.decode(memory, 0);
        DecodedInstruction str = decoder.decode(memory, 4);

        assertEquals(InstructionKind.LOAD, ldr.kind());
        assertEquals(2, ldr.destinationRegister());
        assertEquals(0, ldr.sourceRegister());
        assertEquals(1, ldr.secondSourceRegister());
        assertFalse(ldr.immediateOperand());
        assertEquals(4, ldr.accessSizeBytes());

        assertEquals(InstructionKind.STORE, str.kind());
        assertEquals(2, str.destinationRegister());
        assertEquals(0, str.sourceRegister());
        assertEquals(1, str.secondSourceRegister());
        assertFalse(str.immediateOperand());
        assertEquals(4, str.accessSizeBytes());
    }

    @Test
    void decodesArmRegisterOffsetSubtractAndShift() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE710_2001);
        memory.put32(4, 0xE790_2101);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction subtract = decoder.decode(memory, 0);
        DecodedInstruction shifted = decoder.decode(memory, 4);

        assertEquals(InstructionKind.LOAD, subtract.kind());
        assertEquals(1, subtract.secondSourceRegister());
        assertEquals(-1, subtract.immediate());
        assertFalse(subtract.immediateOperand());

        assertEquals(InstructionKind.LOAD, shifted.kind());
        assertEquals(1, shifted.secondSourceRegister());
        assertEquals(1, shifted.immediate());
        assertFalse(shifted.immediateOperand());
    }

    @Test
    void decodesArmRegisterOffsetRrx() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE790_2061);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertEquals(1, instruction.secondSourceRegister());
        assertEquals(1, instruction.immediate());
        assertFalse(instruction.immediateOperand());
    }

    @Test
    void decodesArmPreIndexedWriteback() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE5A0_1004);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.STORE, instruction.kind());
        assertEquals(4, instruction.immediate());
        assertTrue(instruction.immediateOperand());
        assertTrue(instruction.writeback());
    }

    @Test
    void decodesArmPostIndexedImmediate() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE490_1004);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertEquals(4, instruction.immediate());
        assertTrue(instruction.immediateOperand());
        assertTrue(instruction.writeback());
        assertTrue(instruction.postIndexed());
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
