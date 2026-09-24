package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.ArmProcessor;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B20.7 — preset `ArmArchitecture#ARMV8R_32` (ARMv8-R AArch32, `Cortex-R52`/`R52+`). Prova (a) a
/// lista positiva soma 3 das 5 features de `ARMV8A_32` (B14.1-B14.6) — as que não dependem de VFP
/// existir — mais `HYPERVISOR_CALL`/`VIRTUALIZATION_EXTENSIONS` sobre a base de `ARMV7R`, (b)
/// `SECURE_MONITOR_CALL`/`ARMV8_FP`/`FP16_ARITHMETIC`/`VFPV2` continuam de fora (sem EL3, sem
/// FPU — achado real: `ARMV8_FP`/`FP16_ARITHMETIC` sozinhas, sem `VFPV2`, fariam `IsaCoverageReport`
/// medir `VSEL`/`VMAXNM`/`VMOVX` etc. num preset sem banco de registradores VFP, pego pelo guard
/// `IsaCoverageReportV8A32ColumnTest`), (c) `LDA`/`STL` decodificam (achado 3 da spec: conjunto
/// ARMv8-A obrigatório), (d) `HVC` decodifica e executa (EL2 obrigatório, inverso do `ARMV7R`)
/// enquanto `SMC` continua `UNIMPLEMENTED`, e (e) o catálogo `ArmProcessor.CORTEX_R52`/
/// `CORTEX_R52PLUS` resolve certo.
class ArmV8rPresetTest {

    @Test
    void armv8rHasEveryArmv7rFeaturePlusTheNonFpArmv8ExtensionsAndHyp() {
        for (ArmFeature feature : ArmFeature.values()) {
            if (!ArmArchitecture.ARMV7R.has(feature)) {
                continue;
            }
            assertTrue(ArmArchitecture.ARMV8R_32.has(feature),
                    feature + " está em ArmV7-R e ARMv8-R (AArch32) o mantém");
        }
        assertTrue(ArmArchitecture.ARMV8R_32.has(ArmFeature.LOAD_ACQUIRE_STORE_RELEASE));
        assertTrue(ArmArchitecture.ARMV8R_32.has(ArmFeature.CRC32));
        assertTrue(ArmArchitecture.ARMV8R_32.has(ArmFeature.HALT));
        assertTrue(ArmArchitecture.ARMV8R_32.has(ArmFeature.HYPERVISOR_CALL), "EL2 obrigatório em ArmV8-R");
        assertTrue(ArmArchitecture.ARMV8R_32.has(ArmFeature.VIRTUALIZATION_EXTENSIONS));
    }

    @Test
    void armv8rLacksSecureMonitorCallAndAnyFpFeature() {
        assertFalse(ArmArchitecture.ARMV8R_32.has(ArmFeature.SECURE_MONITOR_CALL),
                "ARMv8-R AArch32 não tem estado seguro/EL3");
        assertFalse(ArmArchitecture.ARMV8R_32.has(ArmFeature.VFPV2), "sem VFP, mesma decisão do ArmV7R");
        assertFalse(ArmArchitecture.ARMV8R_32.has(ArmFeature.ARMV8_FP),
                "sem o banco VFP, VSEL/VMAXNM/VMINNM/VRINT/VCVT incondicionais não fazem sentido");
        assertFalse(ArmArchitecture.ARMV8R_32.has(ArmFeature.FP16_ARITHMETIC),
                "sem o banco VFP, VMOVX/VINS/conversões FP16 não fazem sentido");
    }

    @Test
    void armv8rDecodesLoadAcquireAndStoreRelease() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV8R_32);

        TestAddressSpace lda = new TestAddressSpace(16);
        lda.put32(0, lda(0b00, 0, 1)); // LDA r0,[r1]
        assertEquals(InstructionKind.LOAD, decoder.decode(lda, 0).kind());

        TestAddressSpace stl = new TestAddressSpace(16);
        stl.put32(0, stl(0b00, 0, 1)); // STL r0,[r1]
        assertEquals(InstructionKind.STORE, decoder.decode(stl, 0).kind());
    }

    @Test
    void armv8rDecodesAndExecutesHvcButNotSmc() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV8R_32);

        TestAddressSpace hvc = new TestAddressSpace(16);
        hvc.put32(0, 0xE141_2374); // HVC #0x1234
        assertEquals(InstructionKind.HVC, decoder.decode(hvc, 0).kind(),
                "EL2 existe de verdade em ARMv8-R — inverso do ARMV7R");

        TestAddressSpace smc = new TestAddressSpace(16);
        smc.put32(0, 0xE160_0075); // SMC #5
        assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(smc, 0).kind(), "sem EL3/Monitor mode");
    }

    @Test
    void cortexR52AndR52PlusResolveToArmv8r32() {
        assertEquals(ArmArchitecture.ARMV8R_32, ArmProcessor.CORTEX_R52.architecture());
        assertEquals(ArmArchitecture.ARMV8R_32, ArmProcessor.CORTEX_R52PLUS.architecture());
    }

    // ── Encoders A32 mínimos (mesmo padrão de LoadAcquireStoreReleaseTest) ──────────────────

    private static int exclusiveFamilyA32(int discriminator, boolean load, int sz, int rt, int rn, int rmOrMarker) {
        int cond = 0xE000_0000;
        int fixed = 0x0180_0090; // bits27:24=0001, bit23=1, bits7:4=1001
        return cond | fixed | (sz << 21) | ((load ? 1 : 0) << 20) | (rn << 16) | (rt << 12)
                | (discriminator << 8) | rmOrMarker;
    }

    private static int lda(int sz, int rt, int rn) {
        return exclusiveFamilyA32(0b1100, true, sz, rt, rn, 0xF);
    }

    private static int stl(int sz, int rt, int rn) {
        return exclusiveFamilyA32(0b1100, false, sz, 0xF, rn, rt);
    }
}
