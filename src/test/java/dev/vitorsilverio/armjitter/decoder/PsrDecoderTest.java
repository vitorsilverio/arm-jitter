package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PsrDecoderTest {
    @Test
    void decodesMrsAndMsrRegisterForms() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE10F_1000);
        memory.put32(4, 0xE14F_2000);
        memory.put32(8, 0xE129_F003);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction mrsCpsr = decoder.decode(memory, 0);
        DecodedInstruction mrsSpsr = decoder.decode(memory, 4);
        DecodedInstruction msr = decoder.decode(memory, 8);

        assertEquals(InstructionKind.MRS, mrsCpsr.kind());
        assertEquals(1, mrsCpsr.destinationRegister());
        assertEquals(0, mrsCpsr.immediate());

        assertEquals(InstructionKind.MRS, mrsSpsr.kind());
        assertEquals(2, mrsSpsr.destinationRegister());
        assertEquals(1, mrsSpsr.immediate());

        assertEquals(InstructionKind.MSR, msr.kind());
        assertEquals(3, msr.sourceRegister());
        assertEquals(0x9, msr.immediate());
    }

    @Test
    void decodesMsrImmediateForm() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE321_F013);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.MSR, instruction.kind());
        assertEquals(0x1, instruction.destinationRegister());
        assertEquals(0x13, instruction.immediate());
        assertTrue(instruction.immediateOperand());
    }
}
