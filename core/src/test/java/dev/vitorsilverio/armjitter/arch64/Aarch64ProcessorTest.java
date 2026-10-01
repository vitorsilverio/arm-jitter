package dev.vitorsilverio.armjitter.arch64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Aarch64ProcessorTest {
    @Test
    void armv80aFamilyResolvesToArmv80a() {
        assertSame(Aarch64Architecture.ARMV8_0_A, Aarch64Processor.CORTEX_A34.architecture());
        assertSame(Aarch64Architecture.ARMV8_0_A, Aarch64Processor.CORTEX_A35.architecture());
        assertSame(Aarch64Architecture.ARMV8_0_A, Aarch64Processor.CORTEX_A53.architecture());
        assertSame(Aarch64Architecture.ARMV8_0_A, Aarch64Processor.CORTEX_A57.architecture());
        assertSame(Aarch64Architecture.ARMV8_0_A, Aarch64Processor.CORTEX_A72.architecture());
        assertSame(Aarch64Architecture.ARMV8_0_A, Aarch64Processor.CORTEX_A73.architecture());
    }

    @Test
    void armv82aFamilyResolvesToArmv82a() {
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A55.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A75.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_X1.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.NEOVERSE_N1.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.NEOVERSE_E1.architecture());
    }

    @Test
    void armv82aGapFillFromB121ResolvesToArmv82a() {
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A65.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A65AE.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A76.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A76AE.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A77.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A78.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A78AE.architecture());
        assertSame(Aarch64Architecture.ARMV8_2_A, Aarch64Processor.CORTEX_A78C.architecture());
    }

    @Test
    void armv84aFamilyResolvesToArmv84aWithSve() {
        // B17.26: Neoverse V1 tem FEAT_SVE de verdade (VL=256) — a entrada anterior (ARMV8_4_A
        // puro) omitia isso; o preset de catálogo soma SVE sobre a base ARMv8.4-A.
        assertSame(Aarch64Architecture.ARMV8_4_A_SVE, Aarch64Processor.NEOVERSE_V1.architecture());
        assertTrue(Aarch64Processor.NEOVERSE_V1.architecture().has(Aarch64Feature.SVE));
    }

    @Test
    void a64fxResolvesToArmv82aWithSve() {
        // B17.26: Fujitsu A64FX — primeiro silício SVE do mundo, ARMv8.2-A + FEAT_SVE (VL=512),
        // sem SVE2 (predata a extensão). Não estava no catálogo antes desta task.
        assertSame(Aarch64Architecture.ARMV8_2_A_SVE, Aarch64Processor.A64FX.architecture());
        assertTrue(Aarch64Processor.A64FX.architecture().has(Aarch64Feature.SVE));
        assertFalse(Aarch64Processor.A64FX.architecture().has(Aarch64Feature.SVE2));
    }

    @Test
    void genericArmv9aFamilyResolvesToArmv90aConservatively() {
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.CORTEX_A510.architecture());
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.CORTEX_A710.architecture());
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.CORTEX_A715.architecture());
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.CORTEX_X2.architecture());
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.CORTEX_X3.architecture());
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.NEOVERSE_N2.architecture());
        assertSame(Aarch64Architecture.ARMV9_0_A, Aarch64Processor.NEOVERSE_V2.architecture());
    }

    @Test
    void armv92aFamilyResolvesToArmv92a() {
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.CORTEX_A320.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.CORTEX_A520.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.CORTEX_A720.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.CORTEX_A725.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.CORTEX_X4.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.CORTEX_X925.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.NEOVERSE_N3.architecture());
        assertSame(Aarch64Architecture.ARMV9_2_A, Aarch64Processor.NEOVERSE_V3.architecture());
    }

    @Test
    void cSeriesFamilyResolvesToArmv93a() {
        assertSame(Aarch64Architecture.ARMV9_3_A, Aarch64Processor.C1_ULTRA.architecture());
        assertSame(Aarch64Architecture.ARMV9_3_A, Aarch64Processor.C1_PREMIUM.architecture());
        assertSame(Aarch64Architecture.ARMV9_3_A, Aarch64Processor.C1_PRO.architecture());
        assertSame(Aarch64Architecture.ARMV9_3_A, Aarch64Processor.C1_NANO.architecture());
    }

    @Test
    void displayNameMatchesCommercialName() {
        assertEquals("Cortex-A53", Aarch64Processor.CORTEX_A53.displayName());
        assertEquals("Neoverse N1", Aarch64Processor.NEOVERSE_N1.displayName());
        assertEquals("C1-Ultra", Aarch64Processor.C1_ULTRA.displayName());
    }

    @Test
    void toStringReturnsDisplayName() {
        assertEquals("Cortex-A53", Aarch64Processor.CORTEX_A53.toString());
    }

    @Test
    void everyEntryHasAUniqueDisplayName() {
        long distinctNames = java.util.Arrays.stream(Aarch64Processor.values())
                .map(Aarch64Processor::displayName)
                .distinct()
                .count();
        assertEquals(Aarch64Processor.values().length, distinctNames);
    }

    @Test
    void valueOfRoundTripsForEveryConstant() {
        for (Aarch64Processor processor : Aarch64Processor.values()) {
            assertSame(processor, Aarch64Processor.valueOf(processor.name()));
        }
    }
}
