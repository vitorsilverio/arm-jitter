package dev.vitorsilverio.armjitter.core64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B10.1: armazenamento por nível de {@link Aarch64ExceptionState} (era só `EL1`, `boolean
/// inEl1`, desde B6.6.4). Complementa {@code Aarch64MemoryAbortTest}/{@code
/// Ir64BlockExecutorB101Test} (mecânica completa via `step()`).
class Aarch64ExceptionStateB101Test {
    @Test
    void el1NamedAccessorsMatchGenericAccessors() {
        Aarch64ExceptionState state = new Aarch64ExceptionState();
        state.setSp1(0x1000);
        state.setElr1(0x2000);
        state.setSpsr1(0x3000);
        state.setEsr1(0x4000);
        state.setFar1(0x5000);
        state.setVbar1(0x6000);

        assertEquals(0x1000, state.sp(Aarch64ExceptionLevel.EL1));
        assertEquals(0x2000, state.elr(Aarch64ExceptionLevel.EL1));
        assertEquals(0x3000, state.spsr(Aarch64ExceptionLevel.EL1));
        assertEquals(0x4000, state.esr(Aarch64ExceptionLevel.EL1));
        assertEquals(0x5000, state.far(Aarch64ExceptionLevel.EL1));
        assertEquals(0x6000, state.vbar(Aarch64ExceptionLevel.EL1));
    }

    @Test
    void el2AndEl3HaveIndependentBanksFromEl1() {
        Aarch64ExceptionState state = new Aarch64ExceptionState();
        state.setElr(Aarch64ExceptionLevel.EL1, 0x1L);
        state.setElr(Aarch64ExceptionLevel.EL2, 0x2L);
        state.setElr(Aarch64ExceptionLevel.EL3, 0x3L);

        assertEquals(0x1L, state.elr1());
        assertEquals(0x2L, state.elr(Aarch64ExceptionLevel.EL2));
        assertEquals(0x3L, state.elr(Aarch64ExceptionLevel.EL3));
    }

    @Test
    void el0HasNoBank() {
        Aarch64ExceptionState state = new Aarch64ExceptionState();
        assertThrows(IllegalArgumentException.class, () -> state.elr(Aarch64ExceptionLevel.EL0));
        assertThrows(IllegalArgumentException.class,
                () -> state.setVbar(Aarch64ExceptionLevel.EL0, 0x100));
    }

    @Test
    void currentElDefaultsToEl0() {
        Aarch64ExceptionState state = new Aarch64ExceptionState();
        assertEquals(Aarch64ExceptionLevel.EL0, state.currentEl());
        assertFalse(state.inEl1());
    }

    @Test
    void inEl1ConvenienceMatchesCurrentEl() {
        Aarch64ExceptionState state = new Aarch64ExceptionState();
        state.setCurrentEl(Aarch64ExceptionLevel.EL1);
        assertTrue(state.inEl1());
        state.setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertFalse(state.inEl1(), "EL2 não é EL1 — inEl1() não deve confundir os dois");
    }
}
