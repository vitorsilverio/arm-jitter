package dev.vitorsilverio.armjitter.arch64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    void displayNameMatchesCommercialName() {
        assertEquals("Cortex-A53", Aarch64Processor.CORTEX_A53.displayName());
        assertEquals("Neoverse N1", Aarch64Processor.NEOVERSE_N1.displayName());
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
