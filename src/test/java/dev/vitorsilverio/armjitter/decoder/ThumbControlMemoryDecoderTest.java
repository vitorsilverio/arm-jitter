package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbControlMemoryDecoderTest {
    @Test
    void decodesThumbConditionalBranch() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xD001);

        DecodedInstruction instruction = new ThumbDecoder().decode(memory, 0);

        assertEquals(InstructionKind.BRANCH, instruction.kind());
        assertEquals(Condition.EQ, instruction.condition());
        assertEquals(6, instruction.immediate());
    }

    @Test
    void decodesThumbByteLoadAndStoreImmediate() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x7041);
        memory.put16(2, 0x7841);
        ThumbDecoder decoder = new ThumbDecoder();

        DecodedInstruction store = decoder.decode(memory, 0);
        DecodedInstruction load = decoder.decode(memory, 2);

        assertEquals(InstructionKind.STORE, store.kind());
        assertEquals(1, store.accessSizeBytes());
        assertEquals(1, store.immediate());

        assertEquals(InstructionKind.LOAD, load.kind());
        assertEquals(1, load.accessSizeBytes());
        assertEquals(1, load.immediate());
    }
}
