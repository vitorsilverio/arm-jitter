package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbLongBranchDecoderTest {
    @Test
    void decodesThumbBlPrefixAndSuffix() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0xF801);
        ThumbDecoder decoder = new ThumbDecoder();

        DecodedInstruction prefix = decoder.decode(memory, 0);
        DecodedInstruction suffix = decoder.decode(memory, 2);

        assertEquals(InstructionKind.LONG_BRANCH_PREFIX, prefix.kind());
        assertEquals(0, prefix.immediate());

        assertEquals(InstructionKind.LONG_BRANCH_SUFFIX, suffix.kind());
        assertEquals(2, suffix.immediate());
    }
}
