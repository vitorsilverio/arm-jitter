package dev.vitorsilverio.armjitter.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FpscrRegisterTest {
    @Test
    void writesAndReadsBackNzcvDnAndCumulativeFlags() {
        FpscrRegister fpscr = new FpscrRegister();
        fpscr.setValue(FpscrRegister.NEGATIVE_FLAG
                | FpscrRegister.ZERO_FLAG
                | FpscrRegister.DEFAULT_NAN_FLAG
                | FpscrRegister.INEXACT_CUMULATIVE_FLAG
                | FpscrRegister.INVALID_OPERATION_CUMULATIVE_FLAG);

        assertTrue(fpscr.n());
        assertTrue(fpscr.z());
        assertFalse(fpscr.c());
        assertFalse(fpscr.v());
        assertEquals(FpscrRegister.NEGATIVE_FLAG
                        | FpscrRegister.ZERO_FLAG
                        | FpscrRegister.DEFAULT_NAN_FLAG
                        | FpscrRegister.INEXACT_CUMULATIVE_FLAG
                        | FpscrRegister.INVALID_OPERATION_CUMULATIVE_FLAG,
                fpscr.value());
    }

    @Test
    void rejectsUnsupportedRoundingModeFlushToZeroLenAndStride() {
        assertThrows(UnsupportedOperationException.class,
                () -> new FpscrRegister().setValue(1 << FpscrRegister.ROUNDING_MODE_SHIFT));
        assertThrows(UnsupportedOperationException.class,
                () -> new FpscrRegister().setValue(FpscrRegister.FLUSH_TO_ZERO_FLAG));
        assertThrows(UnsupportedOperationException.class,
                () -> new FpscrRegister().setValue(1 << FpscrRegister.LEN_SHIFT));
        assertThrows(UnsupportedOperationException.class,
                () -> new FpscrRegister().setValue(1 << FpscrRegister.STRIDE_SHIFT));
    }

    @Test
    void setNzcvUpdatesOnlyTheFourComparisonFlags() {
        FpscrRegister fpscr = new FpscrRegister();
        fpscr.setValue(FpscrRegister.DEFAULT_NAN_FLAG);

        fpscr.setNzcv(FpscrRegister.NEGATIVE_FLAG | FpscrRegister.CARRY_FLAG);

        assertTrue(fpscr.n());
        assertFalse(fpscr.z());
        assertTrue(fpscr.c());
        assertFalse(fpscr.v());
        assertEquals(FpscrRegister.DEFAULT_NAN_FLAG,
                fpscr.value() & FpscrRegister.DEFAULT_NAN_FLAG);
    }
}
