package dev.vitorsilverio.armjitter.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpsrRegisterTest {
    @Test
    void evaluatesArmConditionsFromNzcvFlags() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setNzcv(false, true, true, false);

        assertTrue(cpsr.evalCond(Condition.EQ));
        assertFalse(cpsr.evalCond(Condition.NE));
        assertFalse(cpsr.evalCond(Condition.HI));
        assertTrue(cpsr.evalCond(Condition.LS));
        assertTrue(cpsr.evalCond(Condition.AL));
    }

    @Test
    void geFlagsLiveInBits19To16AndIgnoreHighBits() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setGe(0b1010);

        assertEquals(0b1010, cpsr.ge());
        assertEquals(0b1010 << CpsrRegister.GE_FLAGS_SHIFT, cpsr.get() & CpsrRegister.GE_FLAGS_MASK);

        cpsr.setGe(0xF5); // bits altos além dos 4 GE são ignorados
        assertEquals(0b0101, cpsr.ge());
    }

    @Test
    void settingGeDoesNotDisturbOtherCpsrBits() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setMode(CpuMode.SUPERVISOR);
        cpsr.setNzcv(true, false, true, false);
        cpsr.setGe(0b1111);

        assertEquals(CpuMode.SUPERVISOR, cpsr.mode());
        assertTrue(cpsr.negative());
        assertFalse(cpsr.zero());
        assertTrue(cpsr.carry());
    }

    @Test
    void preservesModeBitsWhenChangingThumbMode() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setMode(CpuMode.SUPERVISOR);
        cpsr.setThumbMode(true);

        assertEquals(CpuMode.SUPERVISOR, cpsr.mode());
        assertTrue(cpsr.isThumbMode());
    }

    @Test
    void eciReadsAndWritesTheSameByteAsItState() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setEci(MveVptState.ECI_A0A1A2);

        assertEquals(MveVptState.ECI_A0A1A2, cpsr.eci());
        assertEquals(MveVptState.ECI_A0A1A2 << 4, cpsr.itState());
    }

    @Test
    void eciPreservesLowNibbleOfItState() {
        CpsrRegister cpsr = new CpsrRegister();
        // Byte de ITSTATE "cru": nibble baixo != 0 simula (defensivamente) um resto de IT block.
        cpsr.setItState(0x03);

        cpsr.setEci(MveVptState.ECI_A0);

        assertEquals(0x13, cpsr.itState());
        assertEquals(MveVptState.ECI_A0, cpsr.eci());
    }

    @Test
    void settingEciDoesNotDisturbOtherCpsrBits() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setMode(CpuMode.SUPERVISOR);
        cpsr.setNzcv(true, false, true, false);
        cpsr.setGe(0b1111);

        cpsr.setEci(MveVptState.ECI_A0A1);

        assertEquals(CpuMode.SUPERVISOR, cpsr.mode());
        assertTrue(cpsr.negative());
        assertTrue(cpsr.carry());
        assertEquals(0b1111, cpsr.ge());
    }
}
