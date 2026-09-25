package dev.vitorsilverio.armjitter.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/// B22.7 (item 4) — os presets ARMv8-M modernos declaram {@link ArmFeature#HALT} (`HLT`, Halting
/// debug, existe em ARMv8-A e ARMv8-M, ARM DDI 0487): `HLT` passa a decodificar como
/// {@link InstructionKind#HALT} em vez de `UNIMPLEMENTED`. Os presets pré-v8 de perfil M seguem sem
/// a feature (G3) e continuam recusando o encoding.
class ArmV8MHaltPresetTest {
    /// `HLT #0x2A` — T16 `1011 1010 10 imm6`.
    private static final int HLT_0X2A = 0xBAAA;

    private static final List<ArmArchitecture> V8_M_PRESETS = List.of(ArmArchitecture.ARMV8M_BASELINE,
            ArmArchitecture.ARMV8M_MAINLINE, ArmArchitecture.ARMV8_1M, ArmArchitecture.ARMV8_1M_MVE);

    private static DecodedInstruction decode(ArmArchitecture architecture) {
        TestAddressSpace memory = new TestAddressSpace(4);
        memory.put16(0, HLT_0X2A);
        return new ThumbDecoder(architecture).decode(memory, 0);
    }

    @Test
    void everyArmv8MPresetDeclaresHaltAndDecodesHlt() {
        for (ArmArchitecture architecture : V8_M_PRESETS) {
            assertTrue(architecture.has(ArmFeature.HALT), architecture.name());
            DecodedInstruction decoded = decode(architecture);
            assertEquals(InstructionKind.HALT, decoded.kind(), architecture.name());
            assertEquals(0x2A, decoded.immediate(), architecture.name());
        }
    }

    @Test
    void preV8MProfilePresetsStillRefuseHlt() {
        for (ArmArchitecture architecture : List.of(ArmArchitecture.ARMV6M, ArmArchitecture.ARMV7M,
                ArmArchitecture.ARMV7M_PURE)) {
            assertFalse(architecture.has(ArmFeature.HALT), architecture.name());
            assertEquals(InstructionKind.UNIMPLEMENTED, decode(architecture).kind(), architecture.name());
        }
    }
}
