package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbAluDecoderTest {
    @Test
    void decodesCarryNegateRotateBicAndCmnOperations() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x4148);
        memory.put16(2, 0x4188);
        memory.put16(4, 0x41CA);
        memory.put16(6, 0x4253);
        memory.put16(8, 0x42D3);
        memory.put16(10, 0x438C);
        memory.put16(12, 0x428C);
        ThumbDecoder decoder = new ThumbDecoder();

        assertEquals(InstructionKind.ADC, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.SBC, decoder.decode(memory, 2).kind());
        assertEquals(InstructionKind.ROR, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.NEG, decoder.decode(memory, 6).kind());
        assertEquals(InstructionKind.CMN, decoder.decode(memory, 8).kind());
        DecodedInstruction bic = decoder.decode(memory, 10);
        assertEquals(InstructionKind.BIC, bic.kind());
        assertEquals(4, bic.destinationRegister());
        assertEquals(4, bic.sourceRegister());
        assertEquals(1, bic.secondSourceRegister());
        DecodedInstruction cmp = decoder.decode(memory, 12);
        assertEquals(InstructionKind.CMP, cmp.kind());
        assertEquals(4, cmp.sourceRegister());
        assertEquals(1, cmp.secondSourceRegister());
    }

    @Test
    void decodesRegisterOffsetAndSignedLoads() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0x5842);
        memory.put16(2, 0x5642);
        memory.put16(4, 0x5E42);
        ThumbDecoder decoder = new ThumbDecoder();

        DecodedInstruction wordLoad = decoder.decode(memory, 0);
        DecodedInstruction signedByte = decoder.decode(memory, 2);
        DecodedInstruction signedHalf = decoder.decode(memory, 4);

        assertEquals(InstructionKind.LOAD, wordLoad.kind());
        assertEquals(0, wordLoad.sourceRegister());
        assertEquals(1, wordLoad.secondSourceRegister());
        assertFalse(wordLoad.immediateOperand());
        assertEquals(4, wordLoad.accessSizeBytes());

        assertEquals(1, signedByte.accessSizeBytes());
        assertTrue(signedByte.signedAccess());
        assertEquals(2, signedHalf.accessSizeBytes());
        assertTrue(signedHalf.signedAccess());
    }
}
