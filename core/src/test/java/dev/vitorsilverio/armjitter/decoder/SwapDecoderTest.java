package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwapDecoderTest {
    @Test
    void decodesArmSwpAndSwpb() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE100_1092);
        memory.put32(4, 0xE140_1092);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction word = decoder.decode(memory, 0);
        DecodedInstruction byteSwap = decoder.decode(memory, 4);

        assertEquals(InstructionKind.SWAP, word.kind());
        assertEquals(1, word.destinationRegister());
        assertEquals(0, word.sourceRegister());
        assertEquals(2, word.secondSourceRegister());
        assertEquals(4, word.accessSizeBytes());

        assertEquals(InstructionKind.SWAP, byteSwap.kind());
        assertEquals(1, byteSwap.accessSizeBytes());
    }
}
