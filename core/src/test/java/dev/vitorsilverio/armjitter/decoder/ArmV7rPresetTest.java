package dev.vitorsilverio.armjitter.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B20.1 — preset `ArmArchitecture#ARMV7R` (perfil R, primeiro degrau do épico B20). Nenhum
/// decoder novo nesta task: o conjunto de instruções é o de `ARMV7A` menos as três features de
/// virtualização/segurança (achado central da spec, ver Javadoc do preset). Este teste prova (a)
/// a lista positiva não esqueceu nenhuma feature herdável de `ARMV7A`, (b) `HVC`/`SMC`/`ERET`
/// continuam `UNIMPLEMENTED` sob `ARMV7R` (G8), (c) `SDIV`/`UDIV` decodificam em A32 e T32, e (d)
/// `PMSA`/`R_PROFILE` são exclusivas deste preset.
class ArmV7rPresetTest {
    private static final List<ArmFeature> ARMV7R_POSITIVE_FEATURES = List.of(
            // ARMv5TE
            ArmFeature.BLX, ArmFeature.BLX_IMMEDIATE, ArmFeature.CLZ, ArmFeature.DSP_MULTIPLY,
            ArmFeature.SATURATING, ArmFeature.LDRD_STRD, ArmFeature.LOAD_PC_INTERWORKING,
            ArmFeature.MUL_PRESERVES_CARRY, ArmFeature.LDM_WRITEBACK_BASE_IN_LIST,
            ArmFeature.EMPTY_RLIST_NO_TRANSFER, ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL,
            ArmFeature.BREAKPOINT, ArmFeature.PRELOAD_HINTS,
            // ARMv6K (menos SECURE_MONITOR_CALL)
            ArmFeature.EXTEND_ROTATE, ArmFeature.BYTE_REVERSE, ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD, ArmFeature.PACK_SATURATE, ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.EXCLUSIVE_SIZED, ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA, ArmFeature.WAIT_HINTS, ArmFeature.UNALIGNED_ACCESS,
            ArmFeature.SIGNED_MULTIPLY_MEDIA,
            // ARMv6K+Thumb2
            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS, ArmFeature.MOVW_MOVT,
            // ARMv7-A "inteiro v7" (menos VFPV2/VFP_FUSED_MULTIPLY_ACCUMULATE/HYPERVISOR_CALL/
            // VIRTUALIZATION_EXTENSIONS)
            ArmFeature.MLS_MULTIPLY, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE, ArmFeature.DIVIDE,
            // Perfil R
            ArmFeature.R_PROFILE, ArmFeature.PMSA);

    private static final List<ArmFeature> KNOWN_EXCLUSIONS_FROM_ARMV7A = List.of(
            ArmFeature.HYPERVISOR_CALL, ArmFeature.VIRTUALIZATION_EXTENSIONS,
            ArmFeature.SECURE_MONITOR_CALL, ArmFeature.VFPV2, ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE);

    @Test
    void armv7rHasThePositiveFeatureList() {
        for (ArmFeature feature : ARMV7R_POSITIVE_FEATURES) {
            assertTrue(ArmArchitecture.ARMV7R.has(feature), feature + " deve estar em ARMv7-R");
        }
    }

    @Test
    void armv7rLacksHypervisorVirtualizationSecureMonitorAndFp() {
        assertFalse(ArmArchitecture.ARMV7R.has(ArmFeature.HYPERVISOR_CALL), "ARMv7-R não tem Hyp mode");
        assertFalse(ArmArchitecture.ARMV7R.has(ArmFeature.VIRTUALIZATION_EXTENSIONS), "ARMv7-R não tem Hyp mode");
        assertFalse(ArmArchitecture.ARMV7R.has(ArmFeature.SECURE_MONITOR_CALL),
                "ARMv7-R não tem Security Extensions/Monitor mode");
        assertFalse(ArmArchitecture.ARMV7R.has(ArmFeature.VFPV2), "preset base nasce sem FP (variantes 'F' de fora)");
        assertFalse(ArmArchitecture.ARMV7R.has(ArmFeature.M_PROFILE), "perfil R não é perfil M");
    }

    /// Prova que a lista positiva não esqueceu nada: TODA feature que `ARMV7A` declara e não é uma
    /// das exclusões conhecidas também está em `ARMV7R` (varre `ArmFeature.values()` em vez de uma
    /// lista manual, para não escapar de features novas adicionadas depois).
    @Test
    void armv7rHasEveryArmv7aFeatureExceptTheKnownExclusions() {
        for (ArmFeature feature : ArmFeature.values()) {
            if (!ArmArchitecture.ARMV7A.has(feature) || KNOWN_EXCLUSIONS_FROM_ARMV7A.contains(feature)) {
                continue;
            }
            assertTrue(ArmArchitecture.ARMV7R.has(feature),
                    feature + " está em ARMv7-A e não é uma exclusão conhecida — a lista positiva de ARMv7-R"
                            + " esqueceu ela");
        }
    }

    /// Enumera TODOS os presets públicos declarados em `ArmArchitecture` por reflexão (mesmo padrão
    /// de `ArmArchitectureMveFoundationTest`/`ArmArchitectureArmv8a32Test`).
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
    void onlyArmv7rDeclaresPmsaOrRProfile() throws IllegalAccessException {
        for (ArmArchitecture preset : allPublicPresets()) {
            if (preset == ArmArchitecture.ARMV7R) {
                continue;
            }
            assertFalse(preset.has(ArmFeature.PMSA), preset + " não deve ter PMSA");
            assertFalse(preset.has(ArmFeature.R_PROFILE), preset + " não deve ter R_PROFILE");
        }
        assertTrue(ArmArchitecture.ARMV7R.has(ArmFeature.PMSA));
        assertTrue(ArmArchitecture.ARMV7R.has(ArmFeature.R_PROFILE));
    }

    // ── HVC/SMC/ERET continuam UNIMPLEMENTED sob ARMV7R (G8) — mesmos vetores de ArmDecoderTest/
    // Thumb2MiscDecoderTest ──────────────────────────────────────────────────────────────────

    @Test
    void armv7rDoesNotDecodeHvcSmcOrEretInA32() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV7R);

        TestAddressSpace hvc = new TestAddressSpace(16);
        hvc.put32(0, 0xE141_2374); // HVC #0x1234
        assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(hvc, 0).kind());

        TestAddressSpace smc = new TestAddressSpace(16);
        smc.put32(0, 0xE160_0075); // SMC #5
        assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(smc, 0).kind());

        TestAddressSpace eret = new TestAddressSpace(16);
        eret.put32(0, 0xE160_006E); // ERET
        assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(eret, 0).kind());
    }

    @Test
    void armv7rDoesNotDecodeHvcOrSmcInThumb2() {
        ThumbDecoder decoder = new ThumbDecoder(ArmArchitecture.ARMV7R);

        TestAddressSpace hvc = new TestAddressSpace(16);
        hvc.put16(0, 0xF7E1); // HVC.W #0x1234
        hvc.put16(2, 0x8234);
        assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(hvc, 0).kind());

        TestAddressSpace smc = new TestAddressSpace(16);
        smc.put16(0, 0xF7F5); // SMC.W #5
        smc.put16(2, 0x8000);
        assertEquals(InstructionKind.UNIMPLEMENTED, decoder.decode(smc, 0).kind());
    }

    // ── SDIV/UDIV decodificam sob ARMV7R, A32 e T32 (Achado 2 da spec: carve-out gateado só por
    // DIVIDE, sem checagem de perfil) ────────────────────────────────────────────────────────

    @Test
    void armv7rDecodesSdivAndUdivInA32() {
        ArmDecoder decoder = new ArmDecoder(ArmArchitecture.ARMV7R);

        TestAddressSpace sdiv = new TestAddressSpace(16);
        sdiv.put32(0, 0xE710_F211); // SDIV r0, r1, r2
        DecodedInstruction sdivDecoded = decoder.decode(sdiv, 0);
        assertEquals(InstructionKind.DIVIDE, sdivDecoded.kind());
        assertTrue(sdivDecoded.signedAccess(), "SDIV é a forma com sinal");

        TestAddressSpace udiv = new TestAddressSpace(16);
        udiv.put32(0, 0xE730_F211); // UDIV r0, r1, r2
        DecodedInstruction udivDecoded = decoder.decode(udiv, 0);
        assertEquals(InstructionKind.DIVIDE, udivDecoded.kind());
        assertFalse(udivDecoded.signedAccess(), "UDIV é a forma sem sinal");
    }

    @Test
    void armv7rDecodesSdivAndUdivInThumb2() {
        ThumbDecoder decoder = new ThumbDecoder(ArmArchitecture.ARMV7R);

        TestAddressSpace sdiv = new TestAddressSpace(16);
        sdiv.put16(0, 0xFB91); // SDIV r0, r1, r2 (hi: family=0x9, rn=1)
        sdiv.put16(2, 0xF0F2); // lo: p15_12=0xF, p11_8=0, op=0xF, rm=2
        DecodedInstruction sdivDecoded = decoder.decode(sdiv, 0);
        assertEquals(InstructionKind.DIVIDE, sdivDecoded.kind());
        assertTrue(sdivDecoded.signedAccess(), "SDIV é a forma com sinal");

        TestAddressSpace udiv = new TestAddressSpace(16);
        udiv.put16(0, 0xFBB1); // UDIV r0, r1, r2 (hi: family=0xB, rn=1)
        udiv.put16(2, 0xF0F2); // lo: p15_12=0xF, p11_8=0, op=0xF, rm=2
        DecodedInstruction udivDecoded = decoder.decode(udiv, 0);
        assertEquals(InstructionKind.DIVIDE, udivDecoded.kind());
        assertFalse(udivDecoded.signedAccess(), "UDIV é a forma sem sinal");
    }
}
