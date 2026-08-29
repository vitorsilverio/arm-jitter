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

    /// B9.16 — `ARMV7M` ganhou o bundle DSP completo (achado de cobertura de ISA: os decoders
    /// Thumb-2 já tinham decode+IR+executor prontos, só faltava o preset pedir as features).
    @Test
    void armv7mHasTheFullDspExtensionBundle() {
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.CLZ));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.LDRD_STRD));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.PRELOAD_HINTS));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.PACK_SATURATE));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.PARALLEL_SIMD));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.SIGNED_MULTIPLY_MEDIA));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.DSP_MULTIPLY));
        assertTrue(ArmArchitecture.ARMV7M.has(ArmFeature.UMAAL));
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

    /// B2.6: `ARMV6K_THUMB2` é o preset Thumb-2 COMPLETO do épico B2 — herda tudo de ARMV6K, liga
    /// `THUMB2`+`MEMORY_BARRIERS`, e planta as 4 extensões de decoder de 32 bits juntas
    /// (`Thumb2DataProcessingDecoder`/B2.2, `Thumb2LoadStoreDecoder`/B2.3, `Thumb2BranchDecoder`/
    /// B2.4, `Thumb2MiscDecoder`/B2.5). O fechamento do "fantasma" BL/BLX (decode único de 32
    /// bits) elimina a colisão que antes mantinha 2 das 4 extensões de fora — ver o javadoc de
    /// `ARMV6K_THUMB2`. B2.7 (PR1) acrescenta uma 5ª extensão (`Thumb2RegisterDataProcessingDecoder`,
    /// espaço `0xFA`).
    @Test
    void armv6kThumb2AddsThumb2AndAllFourDecoderExtensionsOnTopOfArmv6k() {
        ArmArchitecture preset = ArmArchitecture.ARMV6K_THUMB2;
        assertTrue(preset.has(ArmFeature.THUMB2));
        assertTrue(preset.has(ArmFeature.MEMORY_BARRIERS));
        for (ArmFeature feature : ARMV6_FEATURES) {
            assertTrue(preset.has(feature), feature + " deve ser herdada de ARMv6K");
        }
        assertEquals(ArmArchitecture.ARMV6K.decoderExtensions().size(), preset.decoderExtensions().size(),
                "extensões de decoder ARM (32-bit clássico) herdadas sem mudança");
        assertEquals(7, preset.thumb32DecoderExtensions().size(),
                "as 4 extensões Thumb-2 (B2.2/B2.3/B2.4/B2.5) + Thumb2RegisterDataProcessingDecoder (B2.7 PR1)"
                        + " + Thumb2MultiplyDecoder (B2.7 PR2) + Thumb2CoprocessorDecoder (B2.7 PR3)");
        assertFalse(ArmArchitecture.ARMV6K.has(ArmFeature.THUMB2), "a base ARMV6K não pode ser mutada");
    }

    /// B5.2: preset do ARM11 MPCore (3DS) — ARMv6K + VFPv2, sem Thumb-2.
    @Test
    void arm11MpCoreHasArmv6kFeaturesPlusVfpButNotThumb2() {
        ArmArchitecture preset = ArmArchitecture.ARM11_MPCORE;
        assertTrue(preset.has(ArmFeature.VFPV2));
        assertTrue(preset.has(ArmFeature.BLX));
        assertTrue(preset.has(ArmFeature.DSP_MULTIPLY));
        for (ArmFeature feature : ARMV6_FEATURES) {
            assertTrue(preset.has(feature), feature + " deve ser herdada de ARMv6K");
        }
        assertFalse(preset.has(ArmFeature.THUMB2), "MPCore do 3DS é ARMv6K, não ARMv6T2");
        assertFalse(ArmArchitecture.ARMV6K.has(ArmFeature.VFPV2), "a base ARMV6K não pode ser mutada");
    }

    /// B9.2/B9.6 (triagem do resto do 32 bits): o ARM11 MPCore real do 3DS é ARMv6K puro (mesmo
    /// `ARM_FEATURE_V6K` do `arm11mpcore_initfn` do QEMU, sem `ARM_FEATURE_THUMB2`) — `MOVW`/
    /// `MOVT`/`MLS`/`SBFX`/`UBFX`/`BFC`/`BFI`/`RBIT` são ARMv6T2 (confirmado contra
    /// `ENABLE_ARCH_6T2` real em `target/arm/tcg/translate.c`), `SDIV`/`UDIV` são a extensão
    /// opcional de divisão do ARMv7-A/R (`dc_isar_feature(aa32_arm_div, ...)`, nem existe antes da
    /// v7), e `VFMA`/`VFMS`/`VFNMA`/`VFNMS` são VFPv4 (comentário literal "Present in VFPv4 only"
    /// em `translate-vfp.c`, cronologicamente posterior à geração ARM11/MPCore) — nenhuma das 11
    /// pertence ao MPCore. Todas ficam de fora deste preset; `ARMV7A` continua tendo as 11 (ver
    /// `arm7aHasAllOfV7Integer`/os testes de `VfpDecoderTest` para as fundidas).
    @Test
    void arm11MpCoreLacksArmv6t2AndDivideAndFusedVfp() {
        ArmArchitecture preset = ArmArchitecture.ARM11_MPCORE;
        assertFalse(preset.has(ArmFeature.MOVW_MOVT), "MOVW/MOVT é ARMv6T2, não ARMv6K");
        assertFalse(preset.has(ArmFeature.MLS_MULTIPLY), "MLS é ARMv6T2, não ARMv6K");
        assertFalse(preset.has(ArmFeature.BIT_FIELD), "SBFX/UBFX/BFC/BFI é ARMv6T2, não ARMv6K");
        assertFalse(preset.has(ArmFeature.BIT_REVERSE), "RBIT é ARMv6T2, não ARMv6K");
        assertFalse(preset.has(ArmFeature.DIVIDE), "SDIV/UDIV é extensão opcional do ARMv7-A/R");
        assertFalse(preset.has(ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE), "VFMA/VFMS/VFNMA/VFNMS é VFPv4");
        assertTrue(ArmArchitecture.ARMV7A.has(ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE),
                "v7-A (Cortex-A15/A7) tem VFPv4 — mesma nota já feita para SDIV/UDIV no preset");
    }

    /// B12.5: `ARMv6` pura (ARM1136J-S) tem o conjunto "ARMv6+" MENOS as extensões que só chegam
    /// com ARMv6K (`EXCLUSIVE_SIZED`/`WAIT_HINTS`/`SECURE_MONITOR_CALL`) e sem Thumb-2.
    @Test
    void armv6PureHasArmv6FeaturesButNotArmv6kExtrasNorThumb2() {
        ArmArchitecture preset = ArmArchitecture.ARMV6;
        for (ArmFeature feature : List.of(ArmFeature.EXTEND_ROTATE, ArmFeature.BYTE_REVERSE,
                ArmFeature.UMAAL, ArmFeature.PARALLEL_SIMD, ArmFeature.PACK_SATURATE,
                ArmFeature.EXCLUSIVE_WORD, ArmFeature.MODE_CHANGE_INSTRUCTIONS,
                ArmFeature.SETEND_BIG_ENDIAN_DATA, ArmFeature.UNALIGNED_ACCESS,
                ArmFeature.SIGNED_MULTIPLY_MEDIA)) {
            assertTrue(preset.has(feature), feature + " deve estar em ARMv6 pura");
        }
        assertFalse(preset.has(ArmFeature.EXCLUSIVE_SIZED), "LDREXB/H/D é ARMv6K, não ARMv6 pura");
        assertFalse(preset.has(ArmFeature.WAIT_HINTS), "WFI/WFE/SEV/YIELD dedicados é ARMv6K");
        assertFalse(preset.has(ArmFeature.SECURE_MONITOR_CALL), "SMC é ARMv6K/ARMv6Z, não ARMv6 pura");
        assertFalse(preset.has(ArmFeature.THUMB2), "Thumb-2 é ARMv6T2+");
    }

    /// B12.5: `ARMv6T2` pura (ARM1156T2-S) tem Thumb-2 mais as 4 features "ARMv6T2 de verdade"
    /// (`MOVW_MOVT`/`MLS_MULTIPLY`/`BIT_FIELD`/`BIT_REVERSE`), mas NÃO `MEMORY_BARRIERS`/`DIVIDE`
    /// (ARMv7) nem as extensões `ARMv6K` (é um ramo diferente de `ARMV6`, não estende `ARMV6K`).
    @Test
    void armv6t2PureHasThumb2AndTheFourArmv6t2FeaturesButNotV7OrV6kExtras() {
        ArmArchitecture preset = ArmArchitecture.ARMV6T2;
        assertTrue(preset.has(ArmFeature.THUMB2));
        assertTrue(preset.has(ArmFeature.MOVW_MOVT));
        assertTrue(preset.has(ArmFeature.MLS_MULTIPLY));
        assertTrue(preset.has(ArmFeature.BIT_FIELD));
        assertTrue(preset.has(ArmFeature.BIT_REVERSE));
        assertFalse(preset.has(ArmFeature.MEMORY_BARRIERS), "DMB/DSB/ISB é ARMv7");
        assertFalse(preset.has(ArmFeature.DIVIDE), "SDIV/UDIV é extensão opcional do ARMv7-A/R");
        assertFalse(preset.has(ArmFeature.EXCLUSIVE_SIZED), "extensões ARMv6K não vêm de ARMV6 pura");
        assertFalse(preset.has(ArmFeature.SECURE_MONITOR_CALL));
        assertEquals(7, preset.thumb32DecoderExtensions().size());
    }

    /// B12.5: `ARMv6Z` pura (ARM1176JZ-S) é `ARMV6` + `SECURE_MONITOR_CALL` (SMC), sem Thumb-2.
    @Test
    void armv6zPureHasSecureMonitorCallButNotThumb2() {
        ArmArchitecture preset = ArmArchitecture.ARMV6Z;
        assertTrue(preset.has(ArmFeature.SECURE_MONITOR_CALL));
        assertTrue(preset.has(ArmFeature.EXTEND_ROTATE), "herda o conjunto ARMv6 pura");
        assertFalse(preset.has(ArmFeature.THUMB2), "ARM1176JZ(F)-S não é ARMv6T2");
        assertFalse(preset.has(ArmFeature.EXCLUSIVE_SIZED), "extensões ARMv6K não vêm de ARMV6 pura");
    }

    /// VFP decodifica (VADD.F32 S2,S0,S1 — mesmo vetor manual de `VfpDecoderTest#addSingleDecodesToVfpAlu`).
    @Test
    void arm11MpCoreDecodesVfpInstructions() {
        int vaddS2S0S1 = 0xEE30_1A20;
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, vaddS2S0S1);
        DecodedInstruction decoded = new ArmDecoder(ArmArchitecture.ARM11_MPCORE).decode(memory, 0);
        assertEquals(InstructionKind.VFP_ALU, decoded.kind());
    }

    /// Sem `THUMB2`, `BL`/`BLX` imediato Thumb continua no comportamento legado de par (B2.6 não
    /// ativa) — mesmos halfwords de `ThumbLongBranchDecoderTest#decodesThumbBlPrefixAndSuffix`.
    @Test
    void arm11MpCoreDecodesThumb32BlAsLegacyPrefixSuffixPairNotAsThumb2() {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, 0xF000);
        memory.put16(2, 0xF801);
        dev.vitorsilverio.armjitter.decoder.ThumbDecoder decoder =
                new dev.vitorsilverio.armjitter.decoder.ThumbDecoder(ArmArchitecture.ARM11_MPCORE);

        assertEquals(InstructionKind.LONG_BRANCH_PREFIX, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.LONG_BRANCH_SUFFIX, decoder.decode(memory, 2).kind());
    }

    /// CP15 continua no bus do hospedeiro: nenhuma extensão de coprocessador de sistema NOVA é
    /// plugada por este preset além do `CoprocessorDecoder` já herdado de ARMv5TE (cobre CP10/11
    /// VFP e deixa o restante do espaço de coprocessador em UNDEFINED controlado, igual a
    /// qualquer outro preset sem MMU) mais o `VfpDecoder` — total 2, não 1 (ARMV6K) nem 3+.
    @Test
    void arm11MpCoreLeavesCp15OffTheDecoder() {
        assertEquals(ArmArchitecture.ARMV6K.decoderExtensions().size() + 1,
                ArmArchitecture.ARM11_MPCORE.decoderExtensions().size(),
                "VfpDecoder soma 1 à extensão de CoprocessorDecoder já herdada; nenhuma extensão de CP15 nova");
    }

    @Test
    void decoderExtensionHandlesEncodingsTheBaseDecoderRejects() {
        int raw = 0xEE00_0001; // espaço de coprocessador: UNIMPLEMENTED no decoder compartilhado
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
