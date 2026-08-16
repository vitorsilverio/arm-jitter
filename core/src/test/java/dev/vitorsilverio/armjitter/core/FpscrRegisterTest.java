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
    void acceptsRoundingModeFlushToZeroLenAndStrideWithoutThrowing() {
        // B3.8: revisitação da decisão nº 3 do B3 — nenhum desses bits lança mais exceção.
        FpscrRegister fpscr = new FpscrRegister();
        fpscr.setValue(FpscrRegister.ROUNDING_MODE_MASK | FpscrRegister.FLUSH_TO_ZERO_FLAG
                | FpscrRegister.LEN_MASK | FpscrRegister.STRIDE_MASK);
        assertEquals(FpscrRegister.ROUNDING_MODE_MASK | FpscrRegister.FLUSH_TO_ZERO_FLAG
                        | FpscrRegister.LEN_MASK | FpscrRegister.STRIDE_MASK,
                fpscr.value());
        assertTrue(fpscr.flushToZero());
    }

    @Test
    void decodesAllFourRoundingModeFieldValues() {
        FpscrRegister fpscr = new FpscrRegister();
        fpscr.setValue(0);
        assertEquals(FpRoundingMode.ROUND_TO_NEAREST, fpscr.roundingMode());

        fpscr.setValue(0b01 << FpscrRegister.ROUNDING_MODE_SHIFT);
        assertEquals(FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY, fpscr.roundingMode());

        fpscr.setValue(0b10 << FpscrRegister.ROUNDING_MODE_SHIFT);
        assertEquals(FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY, fpscr.roundingMode());

        fpscr.setValue(0b11 << FpscrRegister.ROUNDING_MODE_SHIFT);
        assertEquals(FpRoundingMode.ROUND_TOWARD_ZERO, fpscr.roundingMode());
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
