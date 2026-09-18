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
    void classicArmv6BranchesResolveToTheirOwnPurePresets() {
        assertSame(ArmArchitecture.ARMV6, ArmProcessor.ARM1136J_S.architecture());
        assertSame(ArmArchitecture.ARMV6T2, ArmProcessor.ARM1156T2_S.architecture());
        assertSame(ArmArchitecture.ARMV6Z, ArmProcessor.ARM1176JZ_S.architecture());
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
    void armv7mPureFamilyResolvesToArmv7mPure() {
        assertSame(ArmArchitecture.ARMV7M_PURE, ArmProcessor.SC300.architecture());
        assertSame(ArmArchitecture.ARMV7M_PURE, ArmProcessor.CORTEX_M3.architecture());
    }

    @Test
    void armv7emFamilyResolvesToArmv7em() {
        assertSame(ArmArchitecture.ARMV7EM, ArmProcessor.CORTEX_M4.architecture());
        assertSame(ArmArchitecture.ARMV7EM, ArmProcessor.CORTEX_M7.architecture());
    }

    @Test
    void armv8mBaselineFamilyResolvesToArmv8mBaseline() {
        assertSame(ArmArchitecture.ARMV8M_BASELINE, ArmProcessor.CORTEX_M23.architecture());
    }

    @Test
    void armv8mMainlineFamilyResolvesToArmv8mMainline() {
        assertSame(ArmArchitecture.ARMV8M_MAINLINE, ArmProcessor.CORTEX_M33.architecture());
        assertSame(ArmArchitecture.ARMV8M_MAINLINE, ArmProcessor.CORTEX_M35P.architecture());
    }

    @Test
    void armv8_1mFamilyResolvesToArmv8_1mMve() {
        // B16.14: Helium/MVE fechou decode+execução (épico B16) — M52/M55/M85 passam a resolver
        // para o preset COM MVE, não mais o parcial sem Helium (mesma simplificação de SKU que
        // já assume a variante mais capaz, ver Javadoc de ArmProcessor).
        assertSame(ArmArchitecture.ARMV8_1M_MVE, ArmProcessor.CORTEX_M52.architecture());
        assertSame(ArmArchitecture.ARMV8_1M_MVE, ArmProcessor.CORTEX_M55.architecture());
        assertSame(ArmArchitecture.ARMV8_1M_MVE, ArmProcessor.CORTEX_M85.architecture());
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
