package dev.vitorsilverio.armjitter.advsimd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.11 — núcleo FP8 compartilhado ({@link AdvSimdLanes#fp8ToFloat}/{@link
/// AdvSimdLanes#floatToFp8}): teste diferencial varrendo os 256 padrões de cada formato (`E5M2`/
/// `E4M3`) e casos dirigidos de arredondamento/overflow/NaN — mesma disciplina de
/// {@link AdvSimdLanesBFloat16Test}.
class AdvSimdLanesFp8Test {
    @Test
    void fp8ToFloatRoundTripsExactlyForAllE5M2Patterns() {
        for (int bits = 0; bits <= 0xFF; bits++) {
            float value = AdvSimdLanes.fp8ToFloat(bits, false);
            int roundTripped = AdvSimdLanes.floatToFp8(value, false, false);
            if (Float.isNaN(value)) {
                assertTrue(Float.isNaN(AdvSimdLanes.fp8ToFloat(roundTripped, false)),
                        "bits=0x" + Integer.toHexString(bits));
            } else {
                assertEquals(bits, roundTripped, "bits=0x" + Integer.toHexString(bits));
            }
        }
    }

    @Test
    void fp8ToFloatRoundTripsExactlyForAllE4M3Patterns() {
        for (int bits = 0; bits <= 0xFF; bits++) {
            float value = AdvSimdLanes.fp8ToFloat(bits, true);
            int roundTripped = AdvSimdLanes.floatToFp8(value, true, false);
            if (Float.isNaN(value)) {
                assertTrue(Float.isNaN(AdvSimdLanes.fp8ToFloat(roundTripped, true)),
                        "bits=0x" + Integer.toHexString(bits));
            } else {
                assertEquals(bits, roundTripped, "bits=0x" + Integer.toHexString(bits));
            }
        }
    }

    @Test
    void e4m3HasNoInfinityAndExtendedRangeUpTo448() {
        assertEquals(448f, AdvSimdLanes.fp8ToFloat(0b0_1111_110, true));
        assertTrue(Float.isNaN(AdvSimdLanes.fp8ToFloat(0b0_1111_111, true)));
        assertFalse(Float.isInfinite(AdvSimdLanes.fp8ToFloat(0b0_1111_111, true)));
        assertFalse(Float.isInfinite(AdvSimdLanes.fp8ToFloat(0b1_1111_110, true)));
    }

    @Test
    void e5m2HasStandardInfinityAndNaN() {
        assertEquals(Float.POSITIVE_INFINITY, AdvSimdLanes.fp8ToFloat(0b0_11111_00, false));
        assertEquals(Float.NEGATIVE_INFINITY, AdvSimdLanes.fp8ToFloat(0b1_11111_00, false));
        assertTrue(Float.isNaN(AdvSimdLanes.fp8ToFloat(0b0_11111_01, false)));
        assertTrue(Float.isNaN(AdvSimdLanes.fp8ToFloat(0b0_11111_10, false)));
        assertEquals(57344f, AdvSimdLanes.fp8ToFloat(0b0_11110_11, false));
    }

    @Test
    void zeroAndSubnormalsMatchTheFormula() {
        assertEquals(0f, AdvSimdLanes.fp8ToFloat(0b0_0000_000, true));
        assertEquals(-0f, AdvSimdLanes.fp8ToFloat(0b1_0000_000, true), 0f);
        assertTrue(1 / AdvSimdLanes.fp8ToFloat(0b1_0000_000, true) < 0, "sinal do zero negativo preservado");
        // E4M3 subnormal: frac=001, viés=7 -> valor = 1 * 2^(1-7-3) = 2^-9.
        assertEquals((float) Math.scalb(1.0, -9), AdvSimdLanes.fp8ToFloat(0b0_0000_001, true));
        // E5M2 subnormal: frac=01, viés=15 -> valor = 1 * 2^(1-15-2) = 2^-16.
        assertEquals((float) Math.scalb(1.0, -16), AdvSimdLanes.fp8ToFloat(0b0_00000_01, false));
    }

    @Test
    void sameBitsMeanDifferentValuesInEachFormat() {
        int bits = 0b0111_1010;
        float asE5M2 = AdvSimdLanes.fp8ToFloat(bits, false);
        float asE4M3 = AdvSimdLanes.fp8ToFloat(bits, true);
        assertNotEquals(asE5M2, asE4M3, "F1CVTL×F2CVTL (e BF1CVTL×BF2CVTL) não podem confundir os formatos");
    }

    @Test
    void floatToFp8OverflowSaturatesOrGeneratesInfinityOrNaNPerOsc() {
        assertEquals(0b0_11110_11, AdvSimdLanes.floatToFp8(1_000_000f, false, true), "E5M2 satura no máximo normal");
        assertEquals(0b0_11111_00, AdvSimdLanes.floatToFp8(1_000_000f, false, false), "E5M2 sem saturar vira Infinito");
        assertEquals(0b0_1111_110, AdvSimdLanes.floatToFp8(1_000_000f, true, true), "E4M3 satura no máximo normal (448)");
        assertEquals(0b0_1111_111, AdvSimdLanes.floatToFp8(1_000_000f, true, false),
                "E4M3 nunca tem Infinito: sem saturar vira NaN");
    }

    @Test
    void floatToFp8RoundsToNearestEven() {
        // E4M3, expoente real 0 (viés 7 => bits `0111`): valores `1.frac` em passos de 1/8.
        // Ponto médio EXATO entre frac=010(1.25, PAR) e frac=011(1.375, ÍMPAR) é 1.3125 -> fica no PAR.
        assertEquals(0b0_0111_010, AdvSimdLanes.floatToFp8(1.3125f, true, false));
        // Ponto médio EXATO entre frac=011(1.375, ÍMPAR) e frac=100(1.5, PAR) é 1.4375 -> arredonda
        // para CIMA (PAR), não trunca.
        assertEquals(0b0_0111_100, AdvSimdLanes.floatToFp8(1.4375f, true, false));
    }

    @Test
    void floatToFp8PreservesSignOfNaNAndZero() {
        assertEquals(0b1_0000_000, AdvSimdLanes.floatToFp8(-0f, true, false));
        int negNan = AdvSimdLanes.floatToFp8(Float.intBitsToFloat(0xFFC00000), true, false);
        assertEquals(0b1_1111_111, negNan);
    }
}
