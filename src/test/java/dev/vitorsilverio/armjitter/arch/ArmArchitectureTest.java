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

    private static final List<ArmFeature> ARMV6_FEATURES = List.of(
            ArmFeature.EXTEND_ROTATE,
            ArmFeature.BYTE_REVERSE,
            ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD,
            ArmFeature.PACK_SATURATE,
            ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.EXCLUSIVE_SIZED,
            ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA,
            ArmFeature.WAIT_HINTS);

    @Test
    void armv6kHasTheArmv5teAndArmv6FeatureSetsButNotThumb2() {
        assertTrue(ArmArchitecture.ARMV6K.has(ArmFeature.BLX));
        assertTrue(ArmArchitecture.ARMV6K.has(ArmFeature.DSP_MULTIPLY));
        assertTrue(ArmArchitecture.ARMV6K.has(ArmFeature.LOAD_PC_INTERWORKING));
        for (ArmFeature feature : ARMV6_FEATURES) {
            assertTrue(ArmArchitecture.ARMV6K.has(feature), feature + " must be on on ARMv6K");
        }
        assertFalse(ArmArchitecture.ARMV6K.has(ArmFeature.THUMB2), "Thumb-2 is ARMv6T2+, not ARMv6K");
    }

    @Test
    void olderArchitecturesLackTheArmv6Features() {
        for (ArmFeature feature : ARMV6_FEATURES) {
            assertFalse(ArmArchitecture.ARMV4T.has(feature), feature + " must be off on ARMv4T");
            assertFalse(ArmArchitecture.ARMV5TE.has(feature), feature + " must be off on ARMv5TE");
        }
    }

    @Test
    void armv6kInheritsTheArmv5teDecoderExtensions() {
        assertEquals(ArmArchitecture.ARMV5TE.decoderExtensions().size(),
                ArmArchitecture.ARMV6K.decoderExtensions().size());
    }

    @Test
    void extendingComposesFeaturesAndDecoderExtensionsOnTopOfTheBase() {
        ArmArchitecture base = ArmArchitecture.of("base", ArmFeature.CLZ).withDecoderExtensions(List.of(
                (word, address, condition) -> null));
        ArmArchitecture extended = ArmArchitecture.extending(base, "extended", ArmFeature.BYTE_REVERSE);
        assertEquals("extended", extended.name());
        assertTrue(extended.has(ArmFeature.CLZ), "feature da base deve ser herdada");
        assertTrue(extended.has(ArmFeature.BYTE_REVERSE));
        assertFalse(extended.has(ArmFeature.BLX));
        assertEquals(1, extended.decoderExtensions().size(), "extensões de decoder da base devem ser herdadas");
        assertFalse(base.has(ArmFeature.BYTE_REVERSE), "a base não pode ser mutada");
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
