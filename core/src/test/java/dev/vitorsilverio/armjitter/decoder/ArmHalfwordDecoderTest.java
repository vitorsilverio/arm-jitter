package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmHalfwordDecoderTest {
    @Test
    void decodesArmHalfwordLoadAndStoreImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE1D0_10B4);
        memory.put32(4, 0xE1C0_10B8);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction load = decoder.decode(memory, 0);
        DecodedInstruction store = decoder.decode(memory, 4);

        assertEquals(InstructionKind.LOAD, load.kind());
        assertEquals(2, load.accessSizeBytes());
        assertEquals(4, load.immediate());

        assertEquals(InstructionKind.STORE, store.kind());
        assertEquals(2, store.accessSizeBytes());
        assertEquals(8, store.immediate());
    }

    @Test
    void decodesArmHalfwordAndSignedLoadsWithRegisterOffset() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE190_20B1);
        memory.put32(4, 0xE190_20D1);
        memory.put32(8, 0xE190_20F1);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction unsignedHalf = decoder.decode(memory, 0);
        DecodedInstruction signedByte = decoder.decode(memory, 4);
        DecodedInstruction signedHalf = decoder.decode(memory, 8);

        assertEquals(InstructionKind.LOAD, unsignedHalf.kind());
        assertEquals(1, unsignedHalf.secondSourceRegister());
        assertFalse(unsignedHalf.immediateOperand());
        assertEquals(2, unsignedHalf.accessSizeBytes());
        assertFalse(unsignedHalf.signedAccess());

        assertEquals(1, signedByte.accessSizeBytes());
        assertTrue(signedByte.signedAccess());
        assertEquals(2, signedHalf.accessSizeBytes());
        assertTrue(signedHalf.signedAccess());
    }

    @Test
    void decodesArmHalfwordPostIndexedImmediate() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE0D0_10B4);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.LOAD, instruction.kind());
        assertEquals(2, instruction.accessSizeBytes());
        assertEquals(4, instruction.immediate());
        assertTrue(instruction.writeback());
        assertTrue(instruction.postIndexed());
    }
}
