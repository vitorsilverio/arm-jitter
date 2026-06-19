package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// The ARMv5TE DSP multiplies: SMUL/SMLA/SMLAW/SMULW/SMLAL with 16-bit half selection.
class Armv5DspMultiplyTest {

    /// Encodes a DSP multiply. op2: 0=SMLA, 1=SMLAW/SMULW, 2=SMLAL, 3=SMUL.
    private static int dsp(int op2, int rd, int rn, int rs, int rm, int x, int y) {
        return 0xE100_0080 | (op2 << 21) | (rd << 16) | (rn << 12) | (rs << 8) | (y << 6) | (x << 5) | rm;
    }

    private static ArmCore core(int instruction) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, instruction);
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
    }

    @Test
    void smulbbMultipliesLowHalves() {
        ArmCore core = core(dsp(3, 0, 0, 2, 1, 0, 0)); // SMULBB r0, r1, r2
        core.setRegister(1, 3);
        core.setRegister(2, 4);
        core.step();
        assertEquals(12, core.register(0));
    }

    @Test
    void smulbbHandlesSignedHalves() {
        ArmCore core = core(dsp(3, 0, 0, 2, 1, 0, 0));
        core.setRegister(1, 0xFFFF); // low half = -1
        core.setRegister(2, 2);
        core.step();
        assertEquals(-2, core.register(0));
    }

    @Test
    void smulttMultipliesHighHalves() {
        ArmCore core = core(dsp(3, 0, 0, 2, 1, 1, 1)); // SMULTT r0, r1, r2
        core.setRegister(1, 0x0003_0000);
        core.setRegister(2, 0x0004_0000);
        core.step();
        assertEquals(12, core.register(0));
    }

    @Test
    void smlabbAccumulates() {
        ArmCore core = core(dsp(0, 0, 3, 2, 1, 0, 0)); // SMLABB r0, r1, r2, r3
        core.setRegister(1, 3);
        core.setRegister(2, 4);
        core.setRegister(3, 100);
        core.step();
        assertEquals(112, core.register(0));
    }

    @Test
    void smulwbTakesTheTop32OfAWordTimesHalf() {
        ArmCore core = core(dsp(1, 0, 0, 2, 1, 1, 0)); // SMULWB r0, r1, r2 (x=1 -> SMULW)
        core.setRegister(1, 0x0001_0000); // 65536
        core.setRegister(2, 2);
        core.step();
        assertEquals(2, core.register(0), "(65536*2) >> 16");
    }

    @Test
    void smlawbAddsTheAccumulator() {
        ArmCore core = core(dsp(1, 0, 3, 2, 1, 0, 0)); // SMLAWB r0, r1, r2, r3 (x=0 -> SMLAW)
        core.setRegister(1, 0x0001_0000);
        core.setRegister(2, 2);
        core.setRegister(3, 10);
        core.step();
        assertEquals(12, core.register(0));
    }

    @Test
    void smlalbbAccumulatesInto64Bits() {
        ArmCore core = core(dsp(2, 0, 3, 2, 1, 0, 0)); // SMLALBB rdLo=r3, rdHi=r0, r1, r2
        core.setRegister(0, 0);            // RdHi
        core.setRegister(3, 0xFFFF_FFFF);  // RdLo
        core.setRegister(1, 1);
        core.setRegister(2, 1);            // product = 1
        core.step();
        assertEquals(0, core.register(3), "low word wraps");
        assertEquals(1, core.register(0), "carry into the high word");
    }

    @Test
    void dspMultiplyDecodesOnlyOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, dsp(3, 0, 0, 2, 1, 0, 0));
        assertNotEquals(InstructionKind.DSP_MULTIPLY,
                new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        assertEquals(InstructionKind.DSP_MULTIPLY,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }
}
