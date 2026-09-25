package dev.vitorsilverio.armjitter.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B14.1 — fundação do preset `ARMV8A_32` (ARMv8-A executando em AArch32): três `ArmFeature`
/// novas (`ARMV8_FP`/`LOAD_ACQUIRE_STORE_RELEASE`/`CRC32`) e o preset em si, **sem decode novo**
/// além da diferença já esperada de `HLT` (a feature `HALT`, B22.1, já tinha o decode escrito —
/// ver Javadoc de {@link ArmFeature#HALT}). `LDA`/`STL`/`CRC32`/`VSEL`/`VMAXNM`/`VMINNM`/`VRINT`/
/// `VCVT` ficam para B14.2-B14.5.
class ArmArchitectureArmv8a32Test {

    /// Enumera TODOS os presets públicos declarados em `ArmArchitecture` por reflexão, mesmo
    /// padrão de `ArmArchitectureMveFoundationTest` (B16.1) — evita que este teste fique
    /// desatualizado quando um preset novo nascer.
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

    // ── composição de features: só ARMV8A_32 (e o ARMV8R_32 da B20.7, que soma as mesmas 3
    // features sobre a base do perfil R, e o ARMV8_6A_32 da B22.7, que estende ARMV8A_32) declaram
    // as 3 novas ─────────────────────────────────────────────────────────────────────────────
    @Test
    void onlyArmv8a32DeclaresTheThreeNewFeatures() throws IllegalAccessException {
        for (ArmArchitecture preset : allPublicPresets()) {
            if (preset == ArmArchitecture.ARMV8A_32 || preset == ArmArchitecture.ARMV8R_32
                    || preset == ArmArchitecture.ARMV8_6A_32) {
                continue;
            }
            assertFalse(preset.has(ArmFeature.ARMV8_FP), preset + " não deve ter ARMV8_FP");
            assertFalse(preset.has(ArmFeature.LOAD_ACQUIRE_STORE_RELEASE),
                    preset + " não deve ter LOAD_ACQUIRE_STORE_RELEASE");
            assertFalse(preset.has(ArmFeature.CRC32), preset + " não deve ter CRC32");
        }
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.ARMV8_FP));
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.LOAD_ACQUIRE_STORE_RELEASE));
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.CRC32));
    }

    // ── ARMV8A_32 herda TUDO de ARMV7A, que continua intocado (G3) ──────────────────────────
    @Test
    void armv8a32InheritsEverythingFromArmv7aUnchanged() {
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.MLS_MULTIPLY));
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.VFPV2));
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.HYPERVISOR_CALL));
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.VIRTUALIZATION_EXTENSIONS));
        assertFalse(ArmArchitecture.ARMV7A.has(ArmFeature.ARMV8_FP), "ARMV7A continua intocado (G3)");
        assertFalse(ArmArchitecture.ARMV7A.has(ArmFeature.LOAD_ACQUIRE_STORE_RELEASE),
                "ARMV7A continua intocado (G3)");
        assertFalse(ArmArchitecture.ARMV7A.has(ArmFeature.CRC32), "ARMV7A continua intocado (G3)");
        assertFalse(ArmArchitecture.ARMV7A.has(ArmFeature.HALT), "ARMV7A continua intocado (G3)");

        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.MLS_MULTIPLY), "herda ARMV7A");
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.VFPV2), "herda ARMV7A");
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.HYPERVISOR_CALL), "herda ARMV7A");
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.VIRTUALIZATION_EXTENSIONS), "herda ARMV7A");
        assertTrue(ArmArchitecture.ARMV8A_32.has(ArmFeature.HALT), "declarada pela B14.1 (Armadilha 3)");
    }

    // ── zero-diff de decode: encodings representativos decodificam IDÊNTICO em ARMV7A e ─────
    // ── ARMV8A_32 (nenhuma das 3 features novas tem decoder ainda) ──────────────────────────
    @Test
    void representativeEncodingsDecodeIdenticallyOnArmv7aAndArmv8a32() {
        // VADD.F32 S2,S0,S1 (VfpDecoder).
        assertSameArmDecode(0xEE30_1A20, InstructionKind.VFP_ALU);
        // LDREX r0,[r1] (carve-out ArmDecoder de LDREX/STREX, B1.4).
        assertSameArmDecode(0xE191_0F9F, InstructionKind.LOAD_EXCLUSIVE);
        // UBFX r0,r1,#4,#8 (carve-out ArmDecoder de bitfield, B3.1).
        assertSameArmDecode(0xE7E7_0251, InstructionKind.BIT_FIELD_EXTRACT);

        // ADD.W r0,r1,#0 (Thumb2DataProcessingDecoder, B3.1/B3.2 — mesmo vetor da B15.1).
        assertSameThumbDecode(0xF101, 0x0000, InstructionKind.ADD);
    }

    private static void assertSameArmDecode(int raw, InstructionKind expectedKind) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, raw);
        DecodedInstruction onArmv7a = new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);
        DecodedInstruction onArmv8a32 = new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0);
        assertEquals(expectedKind, onArmv7a.kind(), "ARMV7A deveria decodificar " + expectedKind);
        assertEquals(onArmv7a.kind(), onArmv8a32.kind(),
                "ARMV8A_32 deve decodificar IDÊNTICO a ARMV7A para raw=0x" + Integer.toHexString(raw));
    }

    private static void assertSameThumbDecode(int hi, int lo, InstructionKind expectedKind) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, hi);
        memory.put16(2, lo);
        DecodedInstruction onArmv7a = new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);
        DecodedInstruction onArmv8a32 = new ThumbDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0);
        assertEquals(expectedKind, onArmv7a.kind(), "ARMV7A deveria decodificar " + expectedKind);
        assertEquals(onArmv7a.kind(), onArmv8a32.kind(),
                "ARMV8A_32 deve decodificar IDÊNTICO a ARMV7A (Thumb-2) para hi=0x"
                        + Integer.toHexString(hi) + " lo=0x" + Integer.toHexString(lo));
    }

    // ── única diferença esperada: HLT decodifica HALT em ARMV8A_32, não em ARMV7A ───────────
    // (Armadilha 3 — a feature HALT já existe (B22.1) e o decode já estava escrito, esperando
    // um preset que a declarasse; ARMV8A_32 é esse preset).
    @Test
    void hltDecodesAsHaltOnlyOnArmv8a32NotOnArmv7a() {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, 0xE101_2374); // HLT #0x1234 (mesmo vetor de ArmDecoderTest#decodesArmHltWithHaltFeature)

        DecodedInstruction onArmv7a = new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0);
        DecodedInstruction onArmv8a32 = new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0);

        assertEquals(InstructionKind.UNIMPLEMENTED, onArmv7a.kind(),
                "ARMV7A não declara HALT (B22.1), recusa explícita G8");
        assertEquals(InstructionKind.HALT, onArmv8a32.kind(),
                "ARMV8A_32 declara HALT (B14.1) — mesmo decode já escrito pela B22.1");
        assertEquals(0x1234, onArmv8a32.immediate());
    }
}
