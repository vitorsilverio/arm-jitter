package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultipleTransferDecoderTest {
    @Test
    void decodesArmBlockTransferModes() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE8A0_0003);
        memory.put32(4, 0xE9A0_0003);
        memory.put32(8, 0xE820_0003);
        memory.put32(12, 0xE920_0003);
        ArmDecoder decoder = new ArmDecoder();

        assertEquals(BlockTransferMode.IA, decoder.decode(memory, 0).blockTransferMode());
        assertEquals(BlockTransferMode.IB, decoder.decode(memory, 4).blockTransferMode());
        assertEquals(BlockTransferMode.DA, decoder.decode(memory, 8).blockTransferMode());
        assertEquals(BlockTransferMode.DB, decoder.decode(memory, 12).blockTransferMode());
    }

    @Test
    void decodesArmBlockTransferCaretBit() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE8D0_8002);
        ArmDecoder decoder = new ArmDecoder();

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.LOAD_MULTIPLE, instruction.kind());
        assertTrue(instruction.link());
    }

    @Test
    void decodesEmptyRegisterListsAsArm7TdmiMultipleTransfers() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE8B0_0000);
        memory.put32(4, 0xE8A0_0000);

        DecodedInstruction armLoad = new ArmDecoder().decode(memory, 0);
        DecodedInstruction armStore = new ArmDecoder().decode(memory, 4);

        assertEquals(InstructionKind.LOAD_MULTIPLE, armLoad.kind());
        assertTrue(armLoad.emptyRegisterList());
        assertEquals(InstructionKind.STORE_MULTIPLE, armStore.kind());
        assertTrue(armStore.emptyRegisterList());

        memory.put16(0, 0xC800);
        memory.put16(2, 0xC000);
        ThumbDecoder thumbDecoder = new ThumbDecoder();

        DecodedInstruction thumbLoad = thumbDecoder.decode(memory, 0);
        DecodedInstruction thumbStore = thumbDecoder.decode(memory, 2);

        assertEquals(InstructionKind.LOAD_MULTIPLE, thumbLoad.kind());
        assertTrue(thumbLoad.emptyRegisterList());
        assertEquals(InstructionKind.STORE_MULTIPLE, thumbStore.kind());
        assertTrue(thumbStore.emptyRegisterList());
    }
}
