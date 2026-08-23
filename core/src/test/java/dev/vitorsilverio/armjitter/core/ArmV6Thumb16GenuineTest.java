package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B9.3 — execução ponta-a-ponta (decode + interpretado) das instruções T16 genuínas de ARMv6:
/// `SETEND`, `CPS` A/R-profile, `REV`/`REV16`/`REVSH`, `SXTH`/`SXTB`/`UXTH`/`UXTB`. Os 4 grupos
/// reaproveitam o MESMO `IrOp`/executor das formas A32 já testadas em `ArmV6SystemInstructionsTest`
/// e `ArmV6ExtendReverseTest` (B1.2/B1.5) — aqui a prova é que o decode T16 novo (`ThumbDecoder`,
/// B9.3) alimenta o mesmo caminho corretamente em modo Thumb. Encodings conferidos com corpus real
/// (`arm-none-eabi-as -mcpu=arm1176jzf-s` + `objdump`, devkitARM).
class ArmV6Thumb16GenuineTest {
    private static ArmCore newCore(ArmArchitecture architecture) {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), architecture);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static ArmCore run(ArmCore core, int raw) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        memory.put16(core.programCounter(), raw);
        core.step();
        return core;
    }

    // ── SETEND: `setend le`=0xb650, `setend be`=0xb658 ──────────────────────────────────────

    @Test
    void setendTogglesTheEBitInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        assertFalse(core.cpsr().isBigEndian());
        run(core, 0xB658); // setend be
        assertTrue(core.cpsr().isBigEndian());
        run(core, 0xB650); // setend le
        assertFalse(core.cpsr().isBigEndian());
    }

    // ── CPS A/R-profile: `cpsid aif`=0xb677, `cpsie i`=0xb662 ───────────────────────────────

    @Test
    void cpsidAifDisablesAllThreeFlagsInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.cpsr().setIrqDisabled(false);
        core.cpsr().setFiqDisabled(false);
        core.cpsr().setAbortDisabled(false);

        run(core, 0xB677); // cpsid aif

        assertTrue(core.cpsr().irqDisabled());
        assertTrue(core.cpsr().fiqDisabled());
        assertTrue(core.cpsr().abortDisabled());
    }

    @Test
    void cpsieIEnablesOnlyTheIrqFlagInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.cpsr().setIrqDisabled(true);
        core.cpsr().setFiqDisabled(true);

        run(core, 0xB662); // cpsie i

        assertFalse(core.cpsr().irqDisabled());
        assertTrue(core.cpsr().fiqDisabled(), "cpsie i não deve mexer em F");
    }

    // ── REV/REV16/REVSH: mesmos vetores de ArmV6ExtendReverseTest, via Thumb ────────────────

    @Test
    void revReversesTheFourBytesInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(1, 0x12345678);
        run(core, 0xBA08); // rev r0, r1
        assertEquals(0x78563412, core.register(0));
    }

    @Test
    void rev16ReversesTheBytesOfEachHalfwordInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(3, 0x12345678);
        run(core, 0xBA5A); // rev16 r2, r3
        assertEquals(0x34127856, core.register(2));
    }

    @Test
    void revshReversesTheLowHalfwordAndSignExtendsInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(5, 0x00008380);
        run(core, 0xBAEC); // revsh r4, r5
        assertEquals(0xFFFF8083, core.register(4));
    }

    // ── SXTH/SXTB/UXTH/UXTB: sem acumulador, sem rotação (T16 fixa rot=0) ───────────────────

    @Test
    void sxthSignExtendsTheLowHalfwordInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(1, 0x00008000);
        run(core, 0xB208); // sxth r0, r1
        assertEquals(0xFFFF8000, core.register(0));
    }

    @Test
    void sxtbSignExtendsTheLowByteInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(3, 0x000000FF);
        run(core, 0xB25A); // sxtb r2, r3
        assertEquals(0xFFFFFFFF, core.register(2));
    }

    @Test
    void uxthZeroExtendsTheLowHalfwordInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(5, 0x12345678);
        run(core, 0xB2AC); // uxth r4, r5
        assertEquals(0x00005678, core.register(4));
    }

    @Test
    void uxtbZeroExtendsTheLowByteInThumbMode() {
        ArmCore core = newCore(ArmArchitecture.ARMV6K);
        core.setRegister(7, 0xFFFFFFAB);
        run(core, 0xB2FE); // uxtb r6, r7
        assertEquals(0x000000AB, core.register(6));
    }

    // ── Gating G2: os 4 grupos seguem UNDEFINED em ARMv4T/ARMv5TE (nunca vazar ARMv6 p/ trás) ─

    @Test
    void allEncodingsRaiseUndefinedExceptionOnArmv4tAndArmv5te() {
        int[] encodings = {0xB650, 0xB662, 0xBA08, 0xB208};
        for (ArmArchitecture architecture : new ArmArchitecture[] {ArmArchitecture.ARMV4T, ArmArchitecture.ARMV5TE}) {
            for (int raw : encodings) {
                ArmCore core = newCore(architecture);
                run(core, raw);
                assertEquals(CpuMode.UNDEFINED, core.mode(),
                        () -> architecture + " deveria entrar em UNDEFINED com 0x" + Integer.toHexString(raw));
            }
        }
    }

    // ── Decode puro: confirma o `Kind`/gate a partir do decoder, sem depender de execução ───

    @Test
    void decoderRecognizesAllFourGroupsUnderArmv6k() {
        ThumbDecoder decoder = new ThumbDecoder(ArmArchitecture.ARMV6K);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put16(0, 0xB650);
        memory.put16(2, 0xB662);
        memory.put16(4, 0xBA08);
        memory.put16(6, 0xB208);
        assertEquals(InstructionKind.SETEND, decoder.decode(memory, 0).kind());
        assertEquals(InstructionKind.CPS, decoder.decode(memory, 2).kind());
        assertEquals(InstructionKind.BYTE_REVERSE, decoder.decode(memory, 4).kind());
        assertEquals(InstructionKind.EXTEND, decoder.decode(memory, 6).kind());
    }
}
