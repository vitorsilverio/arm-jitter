package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
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

    @Test
    void blxImmediateLinksAndSwitchesToThumb() {
        TestAddressSpace memory = new TestAddressSpace(0x200);
        memory.put32(0, 0xFA00_003E); // BLX #0x100 (unconditional, always Thumb)
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.step();
        assertTrue(core.cpsr().isThumbMode(), "BLX immediate always switches to Thumb");
        assertEquals(0x100, core.programCounter());
        assertEquals(0x4, core.register(14), "LR holds the return address");
    }

    @Test
    void blxRegisterLinksAndInterworks() {
        TestAddressSpace memory = new TestAddressSpace(0x10);
        memory.put32(0, 0xE12F_FF30); // BLX r0
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.setRegister(0, 0x201); // bit 0 set -> Thumb at 0x200
        core.step();
        assertTrue(core.cpsr().isThumbMode());
        assertEquals(0x200, core.programCounter());
        assertEquals(0x4, core.register(14), "LR holds the return address");
    }

    @Test
    void blxDecodesOnlyOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, 0xE12F_FF30); // BLX r0
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        assertEquals(InstructionKind.BRANCH_EXCHANGE,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }

    @Test
    void thumbBlxRegisterSwitchesToArmAndLinks() {
        TestAddressSpace memory = new TestAddressSpace(0x10);
        memory.put16(0, 0x4788); // BLX r1
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.cpsr().setThumbMode(true);
        core.setRegister(1, 0x100); // bit 0 clear -> ARM at 0x100
        core.step();
        assertFalse(core.cpsr().isThumbMode(), "BLX to an even target switches to ARM");
        assertEquals(0x100, core.programCounter());
        assertEquals(0x3, core.register(14), "Thumb return address keeps bit 0 set");
    }

    @Test
    void thumbBlxImmediateSwitchesToArm() {
        TestAddressSpace memory = new TestAddressSpace(0x200);
        memory.put16(0, 0xF000); // BL/BLX prefix, high offset 0
        memory.put16(2, 0xE87E); // BLX suffix -> target 0x100 (ARM)
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.cpsr().setThumbMode(true);
        core.step(); // prefix: LR = 4
        core.step(); // suffix: BLX
        assertFalse(core.cpsr().isThumbMode(), "BLX immediate switches to ARM");
        assertEquals(0x100, core.programCounter());
        assertEquals(0x5, core.register(14), "LR is the Thumb return address");
    }

    @Test
    void thumbBlxDecodesOnlyOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put16(0, 0x4780); // BLX r0
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        assertEquals(InstructionKind.BRANCH_EXCHANGE,
                new ThumbDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
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
