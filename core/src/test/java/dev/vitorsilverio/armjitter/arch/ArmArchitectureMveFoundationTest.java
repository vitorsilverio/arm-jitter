package dev.vitorsilverio.armjitter.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/// B16.1 — fundação MVE (Helium): `ArmFeature.MVE_INTEGER`/`MVE_FLOAT` só existem no preset
/// `ARMV8_1M_MVE`, que herda TODAS as features/decoders de `ARMV8_1M` (G3: `ARMV8_1M` intocado).
/// Nenhum encoding é decodificado aqui — só a composição de features do preset novo.
class ArmArchitectureMveFoundationTest {

    /// Enumera TODOS os presets públicos declarados em `ArmArchitecture` por reflexão, em vez de
    /// uma lista manual — evita que este teste fique desatualizado quando um preset novo nascer.
    private static List<ArmArchitecture> allPublicPresets() throws IllegalAccessException {
        List<ArmArchitecture> presets = new ArrayList<>();
        for (Field field : ArmArchitecture.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && field.getType() == ArmArchitecture.class) {
                presets.add((ArmArchitecture) field.get(null));
            }
        }
        return presets;
    }

    @Test
    void onlyArmv81mMveDeclaresMveFeatures() throws IllegalAccessException {
        for (ArmArchitecture preset : allPublicPresets()) {
            if (preset == ArmArchitecture.ARMV8_1M_MVE) {
                continue;
            }
            assertFalse(preset.has(ArmFeature.MVE_INTEGER), preset + " não deve ter MVE_INTEGER");
            assertFalse(preset.has(ArmFeature.MVE_FLOAT), preset + " não deve ter MVE_FLOAT");
        }
        assertTrue(ArmArchitecture.ARMV8_1M_MVE.has(ArmFeature.MVE_INTEGER));
        assertTrue(ArmArchitecture.ARMV8_1M_MVE.has(ArmFeature.MVE_FLOAT));
    }

    @Test
    void armv81mMveInheritsEverythingFromArmv81mUnchanged() {
        // G3: ARMV8_1M continua exatamente como a B15.6 deixou (sem MVE).
        assertFalse(ArmArchitecture.ARMV8_1M.has(ArmFeature.MVE_INTEGER));
        assertFalse(ArmArchitecture.ARMV8_1M.has(ArmFeature.MVE_FLOAT));
        assertTrue(ArmArchitecture.ARMV8_1M.has(ArmFeature.LOW_OVERHEAD_BRANCH));

        // ARMV8_1M_MVE herda tudo que ARMV8_1M já tinha, mais as 2 features MVE.
        assertTrue(ArmArchitecture.ARMV8_1M_MVE.has(ArmFeature.LOW_OVERHEAD_BRANCH));
        assertTrue(ArmArchitecture.ARMV8_1M_MVE.has(ArmFeature.M_PROFILE));
        assertTrue(ArmArchitecture.ARMV8_1M_MVE.has(ArmFeature.M_PROFILE_SECURITY));
        assertTrue(ArmArchitecture.ARMV8_1M_MVE.has(ArmFeature.THUMB2));
    }

    @Test
    void neitherAProfileNorRProfilePresetEverGainsMve() {
        // G2: MVE é exclusivamente perfil M (ao contrário de NEON, exclusivamente A/R).
        for (ArmArchitecture preset : new ArmArchitecture[]{
                ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE, ArmArchitecture.ARMV6K,
                ArmArchitecture.ARM11_MPCORE, ArmArchitecture.ARMV7A}) {
            assertFalse(preset.has(ArmFeature.MVE_INTEGER), preset + " (A/R) nunca tem MVE_INTEGER");
            assertFalse(preset.has(ArmFeature.MVE_FLOAT), preset + " (A/R) nunca tem MVE_FLOAT");
        }
    }
}
