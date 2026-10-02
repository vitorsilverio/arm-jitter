package dev.vitorsilverio.armjitter.arch;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Preset {@link ArmArchitecture#ARMV8_6A_32_NEON} (B13.25): ARMv8.6-A AArch32 COM NEON e as 7
/// features irmãs — o único preset de 32 bits em que as 23 linhas de `neon-shared.decode` são
/// medíveis. Os presets existentes ficam intocados (G3).
class ArmV8_6aNeonPresetTest {
    /// `VUDOT_scalar`/`VSUDOT_scalar`/`VFML_scalar`/`VFMA_b16_scal`: formas `_scalar` com bit 4 = 1,
    /// de formato igual ao de `MCR2`/`MRC2` — o caso que media `⚠️` (`COPROCESSOR`) com o decoder
    /// de coprocessador na frente do `neon-shared`.
    private static final int VUDOT_SCALAR = 0xFE20_0D10;
    private static final int VSUDOT_SCALAR = 0xFE80_0D10;
    private static final int VFML_SCALAR = 0xFE00_0810;
    private static final int VFMA_B16_SCALAR = 0xFE30_0810;
    /// `mcr2 p15, 0, r0, c1, c0, 0` — coprocessador de verdade, tem que continuar `COPROCESSOR`.
    private static final int MCR2_P15 = 0xFE01_0F10;
    /// `mcr p15, 0, r0, c1, c0, 0` condicional (cond=AL) — mesmo caso protegido pela B13.22.
    private static final int MCR_P15 = 0xEE01_0F10;

    private static DecodedInstruction decode(ArmArchitecture architecture, int word) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put32(0, word);
        return new ArmDecoder(architecture).decode(memory, 0);
    }

    @Test
    void declaresNeonAndAllSevenSiblingFeaturesOnTopOfV86() {
        ArmArchitecture preset = ArmArchitecture.ARMV8_6A_32_NEON;
        for (ArmFeature feature : new ArmFeature[] {ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32,
                ArmFeature.ADVANCED_SIMD_RDM, ArmFeature.CRYPTO, ArmFeature.COMPLEX_NUMBER_ARITHMETIC,
                ArmFeature.DOT_PRODUCT, ArmFeature.INT8_MATRIX_MULTIPLY,
                ArmFeature.FP16_FUSED_MULTIPLY_ADD_LONG, ArmFeature.BFLOAT16, ArmFeature.JAVASCRIPT_CONVERT,
                ArmFeature.ARMV8_FP}) {
            assertTrue(preset.has(feature), feature.name());
        }
    }

    @Test
    void existingPresetsAreUntouched() {
        assertFalse(ArmArchitecture.ARMV8_6A_32.has(ArmFeature.ADVANCED_SIMD));
        assertFalse(ArmArchitecture.ARMV7A_NEON.has(ArmFeature.DOT_PRODUCT));
        assertFalse(ArmArchitecture.ARMV7A_NEON.has(ArmFeature.BFLOAT16));
    }

    @Test
    void scalarSharedFormsDecodeAsThemselvesNotAsCoprocessor() {
        for (int word : new int[] {VUDOT_SCALAR, VSUDOT_SCALAR, VFML_SCALAR, VFMA_B16_SCALAR}) {
            DecodedInstruction decoded = decode(ArmArchitecture.ARMV8_6A_32_NEON, word);
            assertNotEquals(InstructionKind.COPROCESSOR, decoded.kind(), Integer.toHexString(word));
            assertNotEquals(InstructionKind.UNIMPLEMENTED, decoded.kind(), Integer.toHexString(word));
        }
    }

    /// O contraponto do teste acima: o mesmo encoding sob o preset SEM as features continua sendo o
    /// que sempre foi (nenhuma mudança de comportamento para `ARMV7A_NEON`, G3).
    @Test
    void sameEncodingsStayCoprocessorUnderV7aNeon() {
        assertEquals(InstructionKind.COPROCESSOR, decode(ArmArchitecture.ARMV7A_NEON, VUDOT_SCALAR).kind());
    }

    /// Fall-through de verdade: o que o `neon-shared` não reivindica chega ao `CoprocessorDecoder`.
    @Test
    void genuineCoprocessorTransfersStillReachTheCoprocessorDecoder() {
        assertEquals(InstructionKind.COPROCESSOR, decode(ArmArchitecture.ARMV8_6A_32_NEON, MCR_P15).kind());
        assertEquals(InstructionKind.COPROCESSOR, decode(ArmArchitecture.ARMV8_6A_32_NEON, MCR2_P15).kind());
    }

    /// Forma `Q` com `Vd` ímpar é UNDEFINED nas 4 linhas de `neon-shared` que o JaCoCo mostrou sem
    /// teste (B13.25): `VFML`/`VFML_scalar`/`VDOT_b16_scal` com `q=1` e `VFMA_b16_scal` (sempre `Q`).
    /// Sob o preset novo as 4 palavras chegam ao `NeonSharedDecoder` (as features existem) e são
    /// recusadas explicitamente, nunca confundidas com outra instrução (G8).
    @Test
    void quadFormsWithOddDestinationRegisterAreUnimplemented() {
        int oddVd = 1 << 12;
        int quad = 1 << 6;
        for (int word : new int[] {
                0xFC20_0810 | quad | oddVd, // VFML, q=1
                0xFE00_0810 | quad | oddVd, // VFML_scalar, q=1
                0xFE00_0D00 | quad | oddVd, // VDOT_b16_scal, q=1
                VFMA_B16_SCALAR | oddVd}) { // VFMA_b16_scal
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    decode(ArmArchitecture.ARMV8_6A_32_NEON, word).kind(), Integer.toHexString(word));
        }
    }
}
