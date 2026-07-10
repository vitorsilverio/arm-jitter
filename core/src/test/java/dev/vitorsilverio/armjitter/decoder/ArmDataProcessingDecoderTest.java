package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmDataProcessingDecoderTest {
    @Test
    void decodesLogicalAndTestOperations() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE200_100F);
        memory.put32(4, 0xE220_100F);
        memory.put32(8, 0xE380_100F);
        memory.put32(12, 0xE3C0_100F);
        memory.put32(16, 0xE3E0_100F);
        memory.put32(20, 0xE310_000F);
        ArmDecoder decoder = new ArmDecoder();

        assertEquals(InstructionKind.AND, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.EOR, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.ORR, decoder.decode(memory, 8).kind());
        assertEquals(InstructionKind.BIC, decoder.decode(memory, 12).kind());
        assertEquals(InstructionKind.MVN, decoder.decode(memory, 16).kind());
        assertEquals(InstructionKind.TST, decoder.decode(memory, 20).kind());
    }

    @Test
    void decodesCarryAndCmnOperations() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE2A0_100F);
        memory.put32(4, 0xE2C0_100F);
        memory.put32(8, 0xE370_000F);
        memory.put32(12, 0xE2E0_100F);
        ArmDecoder decoder = new ArmDecoder();

        assertEquals(InstructionKind.ADC, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.SBC, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.CMN, decoder.decode(memory, 8).kind());
        assertEquals(InstructionKind.RSC, decoder.decode(memory, 12).kind());
    }

    @Test
    void decodesReverseSubtractImmediate() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE260_100A);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.RSB, instruction.kind());
        assertEquals(1, instruction.destinationRegister());
        assertEquals(0, instruction.sourceRegister());
        assertEquals(10, instruction.immediate());
        assertTrue(instruction.immediateOperand());
    }

    @Test
    void decodesArmRegisterOperandWithImmediateShift() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE081_0102);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.ADD, instruction.kind());
        assertEquals(0, instruction.destinationRegister());
        assertEquals(1, instruction.sourceRegister());
        assertEquals(2, instruction.secondSourceRegister());
        assertFalse(instruction.immediateOperand());
    }

    @Test
    void decodesArmRegisterOperandWithRegisterShiftAndRrx() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE081_0312);
        memory.put32(4, 0xE1A0_0061);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction registerShift = decoder.decode(memory, 0);
        DecodedInstruction rrx = decoder.decode(memory, 4);

        assertEquals(InstructionKind.ADD, registerShift.kind());
        assertEquals(2, registerShift.secondSourceRegister());
        assertFalse(registerShift.immediateOperand());

        assertEquals(InstructionKind.MOV, rrx.kind());
        assertEquals(1, rrx.secondSourceRegister());
        assertFalse(rrx.immediateOperand());
    }
}
