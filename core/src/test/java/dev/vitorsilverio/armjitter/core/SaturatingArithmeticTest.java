package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// A aritmética saturante ARMv5TE (QADD/QSUB/QDADD/QDSUB), com a flag sticky Q.
class SaturatingArithmeticTest {

    /// Codifica `Q<op> r<rd>, r<rm>, r<rn>` (cond AL). op: 0=QADD, 1=QSUB, 2=QDADD, 3=QDSUB.
    private static int qOp(int op, int rd, int rm, int rn) {
        return 0xE100_0050 | (op << 21) | (rn << 16) | (rd << 12) | rm;
    }

    private static ArmCore run(int instruction, int rm, int rn) {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, instruction);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
        core.setRegister(1, rm);
        core.setRegister(2, rn);
        core.step();
        return core;
    }

    @Test
    void qaddSaturatesAndSetsQ() {
        ArmCore core = run(qOp(0, 0, 1, 2), 0x7FFF_FFFF, 1);
        assertEquals(0x7FFF_FFFF, core.register(0));
        assertTrue(core.cpsr().saturation(), "overflow sets the sticky Q flag");
    }

    @Test
    void qaddWithoutOverflowLeavesQClear() {
        ArmCore core = run(qOp(0, 0, 1, 2), 10, 20);
        assertEquals(30, core.register(0));
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void qsubSubtracts() {
        ArmCore core = run(qOp(1, 0, 1, 2), 20, 5);
        assertEquals(15, core.register(0));
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void qdaddDoublesWithSaturation() {
        ArmCore core = run(qOp(2, 0, 1, 2), 0, 0x4000_0000);
        assertEquals(0x7FFF_FFFF, core.register(0), "2*0x40000000 saturates before the add");
        assertTrue(core.cpsr().saturation());
    }

    @Test
    void qdsubDoublesThenSubtracts() {
        ArmCore core = run(qOp(3, 0, 1, 2), 50, 100); // 50 - (2*100) = -150
        assertEquals(-150, core.register(0));
        assertFalse(core.cpsr().saturation());
    }

    @Test
    void saturatingDecodesOnlyOnArmv5() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, qOp(0, 0, 1, 2));
        assertNotEquals(InstructionKind.SATURATING,
                new ArmDecoder(ArmArchitecture.ARMV4T).decode(memory, 0).kind());
        assertEquals(InstructionKind.SATURATING,
                new ArmDecoder(ArmArchitecture.ARMV5TE).decode(memory, 0).kind());
    }
}
