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
}
