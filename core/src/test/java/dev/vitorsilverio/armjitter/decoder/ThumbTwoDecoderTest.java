package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B2.1 — infra de fetch/gate Thumb-2 (`ArmFeature#THUMB2`): reconhecimento de candidatos de
/// 32 bits (hw1[15:11] em {0b11101, 0b11110, 0b11111}), desambiguação com o par legado BL/BLX de
/// 16+16 bits e UNDEFINED controlado quando nenhuma extensão de B2.2+ está registrada ainda.
class ThumbTwoDecoderTest {
    private static final ArmArchitecture THUMB2_ARCH =
            ArmArchitecture.extending(ArmArchitecture.ARMV6K, "ARMv7-TestThumb2", ArmFeature.THUMB2);

    @Test
    void legacyBlPrefixAndSuffixDecodeIdenticallyWithThumb2Enabled() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0xF801);
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction prefix = decoder.decode(memory, 0);
        DecodedInstruction suffix = decoder.decode(memory, 2);

        assertEquals(InstructionKind.LONG_BRANCH_PREFIX, prefix.kind());
        assertEquals(0, prefix.immediate());
        assertEquals(InstructionKind.LONG_BRANCH_SUFFIX, suffix.kind());
        assertEquals(2, suffix.immediate());
        assertTrue(suffix.link());
    }

    @Test
    void legacyBlxImmediateSuffixDecodesIdenticallyWithThumb2Enabled() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0xE801);
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction prefix = decoder.decode(memory, 0);
        DecodedInstruction suffix = decoder.decode(memory, 2);

        assertEquals(InstructionKind.LONG_BRANCH_PREFIX, prefix.kind());
        assertEquals(InstructionKind.LONG_BRANCH_SUFFIX, suffix.kind());
        assertEquals(2, suffix.immediate());
        assertTrue(suffix.link());
    }

    @Test
    void genuineThumb2DataProcessingImmediateCandidateIsUndefinedWithoutExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // hw1[15:11] = 0b11110
        memory.put16(2, 0x0000); // hw2[15] = 0 -> Data processing: immediate (not BL/BLX)
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertEquals(InstructionSet.THUMB, instruction.instructionSet());
        assertEquals(0, instruction.address());
        assertEquals(0xF0000000, instruction.raw());
    }

    @Test
    void genuineThumb2BranchWithoutLinkCandidateIsUndefinedWithoutExtension() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000); // hw1[15:11] = 0b11110
        memory.put16(2, 0x8000); // hw2[15:14] = 0b10 -> Branches/misc, not "with link"
        ThumbDecoder decoder = new ThumbDecoder(THUMB2_ARCH);

        DecodedInstruction instruction = decoder.decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, instruction.kind());
        assertEquals(0xF0008000, instruction.raw());
    }

    @Test
    void presetsWithoutThumb2DecodeSameCandidateAsLegacyPrefixNoRegression() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put16(0, 0xF000);
        memory.put16(2, 0x0000);

        for (ArmArchitecture architecture : new ArmArchitecture[] {
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV6K}) {
            assertFalse(architecture.has(ArmFeature.THUMB2), architecture.name());
            DecodedInstruction instruction = new ThumbDecoder(architecture).decode(memory, 0);

            assertEquals(InstructionKind.LONG_BRANCH_PREFIX, instruction.kind(), architecture.name());
            assertEquals(0, instruction.immediate(), architecture.name());
            assertEquals(0xF000, instruction.raw(), architecture.name());
        }
    }
}
