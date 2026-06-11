package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the few behaviours that differ between ARM architecture versions, so an
/// ARMv5 rule never silently regresses the ARMv4T (GBA) path and vice-versa.
class ArchitectureVariantTest {
    @Test
    void clzDecodesOnlyOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE16F_1F10); // CLZ r1, r0

        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        assertEquals(InstructionKind.CLZ,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }

    @Test
    void loadIntoPcInterworksOnArmv5ButNotArmv4() {
        int ldrPcFromR0 = 0xE590_F000;   // LDR pc, [r0]
        int loadedValue = 0x0000_0101;   // bit 0 set -> would request Thumb on ARMv5

        ArmCore v4 = stepLoadPc(ArmArchitecture.ARMV4T, ldrPcFromR0, loadedValue);
        assertFalse(v4.cpsr().isThumbMode(), "ARMv4T: a load into PC must not switch to Thumb");
        assertEquals(0x0000_0100, v4.programCounter());

        ArmCore v5 = stepLoadPc(ArmArchitecture.ARMV5TE, ldrPcFromR0, loadedValue);
        assertTrue(v5.cpsr().isThumbMode(), "ARMv5: a load into PC interworks on bit 0");
        assertEquals(0x0000_0100, v5.programCounter());
    }

    private static ArmCore stepLoadPc(ArmArchitecture architecture, int instruction, int loadedValue) {
        TestAddressSpace memory = new TestAddressSpace(0x80);
        memory.put32(0, instruction);
        memory.put32(0x40, loadedValue);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), architecture);
        core.setRegister(0, 0x40);
        core.step();
        return core;
    }
}
