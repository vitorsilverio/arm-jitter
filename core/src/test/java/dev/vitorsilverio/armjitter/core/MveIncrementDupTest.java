package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B16.5 — `VIDUP`/`VDDUP`/`VIWDUP`/`VDWDUP` (geradores de vetor incremental/decremental),
/// fim-a-fim sobre o preset real `ARMV8_1M_MVE`.
class MveIncrementDupTest {
    private static final int NO_RM = 0b111;
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV8_1M_MVE);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int raw) {
        ((TestAddressSpace) core.memory()).put16(address, raw >>> 16);
        ((TestAddressSpace) core.memory()).put16(address + 2, raw & 0xFFFF);
    }

    private static int raw(int qd, int size, int rnRaw, boolean decrement, int rmField, int rawImm) {
        return (0b1110_1110 << 24)
                | (((qd >>> 3) & 1) << 22)
                | ((size & 0x3) << 20)
                | ((rnRaw & 0x7) << 17)
                | (1 << 16)
                | ((qd & 0x7) << 13)
                | ((decrement ? 1 : 0) << 12)
                | (0b1111 << 8)
                | (((rawImm >>> 1) & 1) << 7)
                | (0b110 << 4)
                | ((rmField & 0x7) << 1)
                | (rawImm & 1);
    }

    @Test
    void vidupFillsSuccessiveWordsAndWritesBackRn() {
        ArmCore core = newCore();
        core.setRegister(6, 100); // Rn=6 (rnRaw=3)
        int r = raw(0, 2, 3, false, NO_RM, 0b00); // size=word, imm=1<<0=1
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(100 + i, (int) core.vfp().element(0, i, 2), "lane " + i);
        }
        assertEquals(104, core.register(6));
    }

    @Test
    void vddupDecrementsAndWritesBackNegativeAccumulation() {
        ArmCore core = newCore();
        core.setRegister(6, 100);
        int r = raw(0, 2, 3, true, NO_RM, 0b00); // VDDUP, imm=1<<0=1 -> negado = -1
        put32(core, CODE_BASE, r);

        core.step();

        for (int i = 0; i < 4; i++) {
            assertEquals(100 - i, (int) core.vfp().element(0, i, 2), "lane " + i);
        }
        assertEquals(96, core.register(6));
    }

    @Test
    void viwdupWrapsToZeroWhenReachingRm() {
        ArmCore core = newCore();
        core.setRegister(6, 6); // Rn=6
        core.setRegister(11, 8); // Rm=11 (rmField=5 -> 5*2+1=11), wrap=8
        int r = raw(0, 2, 3, false, 5, 0b01); // imm=1<<1=2
        put32(core, CODE_BASE, r);

        core.step();

        // do_add_wrap verbatim: offset=6; lane0=6, depois 6+2=8==wrap->0; lane1=0, depois 0+2=2;
        // lane2=2, depois 2+2=4; lane3=4, depois 4+2=6; final Rn=6.
        assertEquals(6, (int) core.vfp().element(0, 0, 2));
        assertEquals(0, (int) core.vfp().element(0, 1, 2));
        assertEquals(2, (int) core.vfp().element(0, 2, 2));
        assertEquals(4, (int) core.vfp().element(0, 3, 2));
        assertEquals(6, core.register(6));
    }

    @Test
    void vdwdupWrapsToRmWhenReachingZero() {
        ArmCore core = newCore();
        core.setRegister(6, 2); // Rn=2
        core.setRegister(11, 8); // Rm=11, wrap=8
        int r = raw(0, 2, 3, true, 5, 0b01); // imm=2, VDWDUP
        put32(core, CODE_BASE, r);

        core.step();

        // do_sub_wrap verbatim: offset=2; lane0=2, depois (2!=0) 2-2=0; lane1=0, depois (0==0)
        // offset=wrap(8), 8-2=6; lane2=6, depois 6-2=4; lane3=4, depois 4-2=2; final Rn=2.
        assertEquals(2, (int) core.vfp().element(0, 0, 2));
        assertEquals(0, (int) core.vfp().element(0, 1, 2));
        assertEquals(6, (int) core.vfp().element(0, 2, 2));
        assertEquals(4, (int) core.vfp().element(0, 3, 2));
        assertEquals(2, core.register(6));
    }

    @Test
    void predicatedLanesPreserveExistingQdValueButRnStillAccumulates() {
        ArmCore core = newCore();
        core.vfp().setElement(0, 2, 2, 0xDEAD);
        core.vfp().setElement(0, 3, 2, 0xBEEF);
        core.setRegister(6, 100);
        core.vpr().setP0(0x00FF); // só lanes 0-1 (bytes 0-7) ativas -> lanes 2-3 mascaradas
        core.vpr().setMask01(2);
        core.vpr().setMask23(2);
        int r = raw(0, 2, 3, false, NO_RM, 0b00);
        put32(core, CODE_BASE, r);

        core.step();

        assertEquals(100, (int) core.vfp().element(0, 0, 2));
        assertEquals(101, (int) core.vfp().element(0, 1, 2));
        assertEquals(0xDEAD, (int) core.vfp().element(0, 2, 2), "lane mascarada preserva valor antigo");
        assertEquals(0xBEEF, (int) core.vfp().element(0, 3, 2), "lane mascarada preserva valor antigo");
        assertEquals(104, core.register(6), "Rn acumula mesmo com lanes mascaradas (mergemask só afeta Qd)");
    }
}
