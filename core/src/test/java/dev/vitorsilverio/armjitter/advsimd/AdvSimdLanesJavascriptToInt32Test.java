package dev.vitorsilverio.armjitter.advsimd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// B22.7 — núcleo `ToInt32` do ECMAScript (`FJCVTZS`/`VJCVT`) compartilhado por A64 e A32/T32.
/// Oráculo: QEMU `HELPER(fjcvtzs)` (`float64_to_int32_modulo`, "the result is supplied modulo 2^32").
class AdvSimdLanesJavascriptToInt32Test {
    @Test
    void truncatesTowardZero() {
        assertEquals(3, AdvSimdLanes.javascriptToInt32(3.99));
        assertEquals(-3, AdvSimdLanes.javascriptToInt32(-3.99));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(0.999));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(-0.999));
    }

    @Test
    void nanAndInfinityAreZero() {
        assertEquals(0, AdvSimdLanes.javascriptToInt32(Double.NaN));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(Double.POSITIVE_INFINITY));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(Double.NEGATIVE_INFINITY));
    }

    @Test
    void wrapsModuloTwoPowThirtyTwoAroundTheInt32Boundaries() {
        assertEquals(Integer.MAX_VALUE, AdvSimdLanes.javascriptToInt32(2147483647.0));
        assertEquals(Integer.MIN_VALUE, AdvSimdLanes.javascriptToInt32(2147483648.0));
        assertEquals(Integer.MIN_VALUE, AdvSimdLanes.javascriptToInt32(-2147483648.0));
        assertEquals(Integer.MAX_VALUE, AdvSimdLanes.javascriptToInt32(-2147483649.0));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(4294967296.0));
        assertEquals(1, AdvSimdLanes.javascriptToInt32(4294967297.0));
        assertEquals(-1, AdvSimdLanes.javascriptToInt32(4294967295.0));
        assertEquals(-1, AdvSimdLanes.javascriptToInt32(-4294967297.0));
    }

    /// Acima de `2^63` o `long` não representa o inteiro: ramo `BigInteger`. O `double` só tem
    /// múltiplos de `2^11` ali, então o resto módulo `2^32` ainda é não-trivial.
    @Test
    void wrapsModuloTwoPowThirtyTwoAboveLongRange() {
        assertEquals(5 * 2048, AdvSimdLanes.javascriptToInt32(0x1p63 + 5 * 0x1p11));
        assertEquals(-5 * 2048, AdvSimdLanes.javascriptToInt32(-(0x1p63 + 5 * 0x1p11)));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(0x1p64));
        assertEquals(0, AdvSimdLanes.javascriptToInt32(Double.MAX_VALUE), "múltiplo de 2^971");
        assertEquals(Integer.MIN_VALUE, AdvSimdLanes.javascriptToInt32(0x1p63 + 0x1p31));
    }

    @Test
    void exactnessIsFalseForNegativeZeroFractionsOutOfRangeAndNonFinite() {
        assertTrue(AdvSimdLanes.javascriptToInt32IsExact(0.0));
        assertTrue(AdvSimdLanes.javascriptToInt32IsExact(-5.0));
        assertTrue(AdvSimdLanes.javascriptToInt32IsExact(2147483647.0));
        assertTrue(AdvSimdLanes.javascriptToInt32IsExact(-2147483648.0));
        assertFalse(AdvSimdLanes.javascriptToInt32IsExact(-0.0), "-0.0 é inexato para JavaScript");
        assertFalse(AdvSimdLanes.javascriptToInt32IsExact(1.5));
        assertFalse(AdvSimdLanes.javascriptToInt32IsExact(2147483648.0));
        assertFalse(AdvSimdLanes.javascriptToInt32IsExact(-2147483649.0));
        assertFalse(AdvSimdLanes.javascriptToInt32IsExact(Double.NaN));
        assertFalse(AdvSimdLanes.javascriptToInt32IsExact(Double.POSITIVE_INFINITY));
    }
}
