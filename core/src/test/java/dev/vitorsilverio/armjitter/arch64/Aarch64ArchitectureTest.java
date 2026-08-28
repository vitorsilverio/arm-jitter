package dev.vitorsilverio.armjitter.arch64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Aarch64ArchitectureTest {
    @Test
    void armv80aHasNoExtraFeatures() {
        for (Aarch64Feature feature : Aarch64Feature.values()) {
            assertFalse(Aarch64Architecture.ARMV8_0_A.has(feature), feature + " must be off on ARMv8.0-A");
        }
    }

    @Test
    void armv81aAddsOnlyRdm() {
        assertTrue(Aarch64Architecture.ARMV8_1_A.has(Aarch64Feature.RDM));
        for (Aarch64Feature feature : Aarch64Feature.values()) {
            if (feature != Aarch64Feature.RDM) {
                assertFalse(Aarch64Architecture.ARMV8_1_A.has(feature), feature + " must not be on ARMv8.1-A yet");
            }
        }
    }

    @Test
    void armv82aInheritsArmv81aAndAddsItsOwnFeatures() {
        Aarch64Architecture armv82a = Aarch64Architecture.ARMV8_2_A;
        assertTrue(armv82a.has(Aarch64Feature.RDM), "herdada de ARMv8.1-A");
        assertTrue(armv82a.has(Aarch64Feature.FP16));
        assertTrue(armv82a.has(Aarch64Feature.DOT_PRODUCT));
        assertTrue(armv82a.has(Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG));
        assertTrue(armv82a.has(Aarch64Feature.SHA512));
        assertTrue(armv82a.has(Aarch64Feature.SM3));
        assertTrue(armv82a.has(Aarch64Feature.SM4));
        assertFalse(armv82a.has(Aarch64Feature.JAVASCRIPT_CONVERT), "só entra em ARMv8.3-A");
    }

    @Test
    void armv90aBaselineMatchesArmv85aNotArmv86a() {
        Aarch64Architecture armv90a = Aarch64Architecture.ARMV9_0_A;
        assertTrue(armv90a.has(Aarch64Feature.DIRECTED_ROUNDING_TO_INTEGRAL), "herdada de ARMv8.5-A");
        assertTrue(armv90a.has(Aarch64Feature.MEMORY_TAGGING), "herdada de ARMv8.5-A");
        assertFalse(armv90a.has(Aarch64Feature.BFLOAT16), "BFLOAT16 só entra em ARMv8.6-A/ARMv9.1-A");
    }

    @Test
    void armv92aAddsSmeOnTopOfArmv87aBaseline() {
        Aarch64Architecture armv92a = Aarch64Architecture.ARMV9_2_A;
        assertTrue(armv92a.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION));
        assertTrue(armv92a.has(Aarch64Feature.BFLOAT16), "herdada da cadeia ARMv8.6-A/ARMv8.7-A");
        assertFalse(Aarch64Architecture.ARMV8_7_A.has(Aarch64Feature.SCALABLE_MATRIX_EXTENSION),
                "a base 32-bit-style não pode ser mutada pela extensão");
    }

    @Test
    void armv95aAddsCompareAndBranchOnTopOfArmv94a() {
        Aarch64Architecture armv95a = Aarch64Architecture.ARMV9_5_A;
        assertTrue(armv95a.has(Aarch64Feature.COMPARE_AND_BRANCH));
        assertTrue(armv95a.has(Aarch64Feature.FP_ABSOLUTE_MAX_MIN), "herdada de ARMv9.4-A");
        assertTrue(armv95a.has(Aarch64Feature.GUARDED_CONTROL_STACK), "herdada de ARMv9.4-A");
        assertFalse(Aarch64Architecture.ARMV9_4_A.has(Aarch64Feature.COMPARE_AND_BRANCH));
    }

    @Test
    void extendingComposesFeaturesOnTopOfTheBaseWithoutMutatingIt() {
        Aarch64Architecture base = Aarch64Architecture.of("base", Aarch64Feature.RDM);
        Aarch64Architecture extended = Aarch64Architecture.extending(base, "extended", Aarch64Feature.FP16);

        assertEquals("extended", extended.name());
        assertTrue(extended.has(Aarch64Feature.RDM), "feature da base deve ser herdada");
        assertTrue(extended.has(Aarch64Feature.FP16));
        assertFalse(extended.has(Aarch64Feature.SM3));
        assertFalse(base.has(Aarch64Feature.FP16), "a base não pode ser mutada");
    }

    @Test
    void ofBuildsArchitectureFromAFeatureSet() {
        Aarch64Architecture custom = Aarch64Architecture.of("custom", Aarch64Feature.SM4);
        assertEquals("custom", custom.name());
        assertTrue(custom.has(Aarch64Feature.SM4));
        assertFalse(custom.has(Aarch64Feature.RDM));
    }

    @Test
    void toStringReturnsTheName() {
        assertEquals("ARMv8.0-A", Aarch64Architecture.ARMV8_0_A.toString());
    }
}
