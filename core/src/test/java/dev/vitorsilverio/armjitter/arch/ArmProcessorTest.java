package dev.vitorsilverio.armjitter.arch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ArmProcessorTest {
    @Test
    void armv4tFamilyResolvesToArmv4t() {
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM7TDMI.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM710T.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM720T.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM740T.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM9TDMI.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM920T.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM922T.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.ARM940T.architecture());
        assertSame(ArmArchitecture.ARMV4T, ArmProcessor.SC100.architecture());
    }

    @Test
    void armv5teFamilyResolvesToArmv5te() {
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM946E_S.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM966E_S.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM968E_S.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM996HS.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM1020E.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM1022E.architecture());
    }

    @Test
    void armv5tejFamilyApproximatesToArmv5te() {
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM7EJ_S.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM926EJ_S.architecture());
        assertSame(ArmArchitecture.ARMV5TE, ArmProcessor.ARM1026EJ_S.architecture());
    }

    @Test
    void arm11MpCoreResolvesToArm11MpCorePreset() {
        assertSame(ArmArchitecture.ARM11_MPCORE, ArmProcessor.ARM11_MPCORE.architecture());
    }

    @Test
    void cortexAv7FamilyResolvesToArmv7a() {
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A5.architecture());
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A7.architecture());
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A8.architecture());
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A9.architecture());
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A12.architecture());
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A15.architecture());
        assertSame(ArmArchitecture.ARMV7A, ArmProcessor.CORTEX_A17.architecture());
    }

    @Test
    void armv6mFamilyResolvesToArmv6m() {
        assertSame(ArmArchitecture.ARMV6M, ArmProcessor.SC000.architecture());
        assertSame(ArmArchitecture.ARMV6M, ArmProcessor.CORTEX_M0.architecture());
        assertSame(ArmArchitecture.ARMV6M, ArmProcessor.CORTEX_M0PLUS.architecture());
        assertSame(ArmArchitecture.ARMV6M, ArmProcessor.CORTEX_M1.architecture());
    }

    @Test
    void displayNameMatchesCommercialName() {
        assertEquals("Cortex-A9", ArmProcessor.CORTEX_A9.displayName());
        assertEquals("ARM7TDMI", ArmProcessor.ARM7TDMI.displayName());
    }

    @Test
    void toStringReturnsDisplayName() {
        assertEquals("Cortex-A9", ArmProcessor.CORTEX_A9.toString());
    }

    @Test
    void everyEntryHasAUniqueDisplayName() {
        long distinctNames = java.util.Arrays.stream(ArmProcessor.values())
                .map(ArmProcessor::displayName)
                .distinct()
                .count();
        assertEquals(ArmProcessor.values().length, distinctNames);
    }

    @Test
    void valueOfRoundTripsForEveryConstant() {
        for (ArmProcessor processor : ArmProcessor.values()) {
            assertSame(processor, ArmProcessor.valueOf(processor.name()));
        }
    }
}
