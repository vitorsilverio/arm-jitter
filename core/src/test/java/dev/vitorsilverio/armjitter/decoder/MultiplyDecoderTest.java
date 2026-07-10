package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultiplyDecoderTest {
    @Test
    void decodesArmMulAndMla() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE001_0290);
        memory.put32(4, 0xE021_3290);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction mul = decoder.decode(memory, 0);
        DecodedInstruction mla = decoder.decode(memory, 4);

        assertEquals(InstructionKind.MUL, mul.kind());
        assertEquals(1, mul.destinationRegister());
        assertEquals(0, mul.sourceRegister());
        assertEquals(2, mul.secondSourceRegister());

        assertEquals(InstructionKind.MLA, mla.kind());
        assertEquals(1, mla.destinationRegister());
        assertEquals(0, mla.sourceRegister());
        assertEquals(2, mla.secondSourceRegister());
        assertEquals(3, mla.immediate());
    }

    @Test
    void decodesArmLongMultiplyFamily() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE083_2190);
        memory.put32(4, 0xE0A3_2190);
        memory.put32(8, 0xE0C3_2190);
        memory.put32(12, 0xE0E3_2190);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction umull = decoder.decode(memory, 0);
        DecodedInstruction umlal = decoder.decode(memory, 4);
        DecodedInstruction smull = decoder.decode(memory, 8);
        DecodedInstruction smlal = decoder.decode(memory, 12);

        assertEquals(InstructionKind.UMULL, umull.kind());
        assertEquals(InstructionKind.UMLAL, umlal.kind());
        assertEquals(InstructionKind.SMULL, smull.kind());
        assertEquals(InstructionKind.SMLAL, smlal.kind());
        assertEquals(2, umull.destinationRegister());
        assertEquals(3, umull.immediate());
        assertEquals(0, umull.sourceRegister());
        assertEquals(1, umull.secondSourceRegister());
    }
}
