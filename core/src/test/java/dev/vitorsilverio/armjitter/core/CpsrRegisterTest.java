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
    void preservesModeBitsWhenChangingThumbMode() {
        CpsrRegister cpsr = new CpsrRegister();
        cpsr.setMode(CpuMode.SUPERVISOR);
        cpsr.setThumbMode(true);

        assertEquals(CpuMode.SUPERVISOR, cpsr.mode());
        assertTrue(cpsr.isThumbMode());
    }
}
