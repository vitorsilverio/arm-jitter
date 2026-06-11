package dev.vitorsilverio.armjitter.arch;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmArchitectureTest {
    @Test
    void armv4tHasNoExtraFeatures() {
        for (ArmFeature feature : ArmFeature.values()) {
            assertFalse(ArmArchitecture.ARMV4T.has(feature), feature + " must be off on ARMv4T");
        }
    }

    @Test
    void armv5teHasTheArmv5FeatureSetButNotThumb2() {
        assertTrue(ArmArchitecture.ARMV5TE.has(ArmFeature.BLX));
        assertTrue(ArmArchitecture.ARMV5TE.has(ArmFeature.CLZ));
        assertTrue(ArmArchitecture.ARMV5TE.has(ArmFeature.DSP_MULTIPLY));
        assertTrue(ArmArchitecture.ARMV5TE.has(ArmFeature.LOAD_PC_INTERWORKING));
        assertFalse(ArmArchitecture.ARMV5TE.has(ArmFeature.THUMB2));
    }

    @Test
    void ofBuildsArchitectureFromAFeatureSet() {
        ArmArchitecture custom = ArmArchitecture.of("custom", ArmFeature.CLZ);
        assertEquals("custom", custom.name());
        assertTrue(custom.has(ArmFeature.CLZ));
        assertFalse(custom.has(ArmFeature.BLX));
    }

    @Test
    void decoderExtensionHandlesEncodingsTheBaseDecoderRejects() {
        int raw = 0xEE00_0001; // coprocessor space: UNIMPLEMENTED in the shared decoder
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, raw);

        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());

        ArmArchitecture extended = ArmArchitecture.of("ext").withDecoderExtensions(List.of(
                (word, address, condition) -> word == raw
                        ? new DecodedInstruction(address, word, InstructionSet.ARM, condition,
                        InstructionKind.NEG, 0, 0, -1, 0, false, false, false)
                        : null));
        assertEquals(InstructionKind.NEG, new ArmDecoder(extended).decode(memory, 0).kind());
    }
}
