package dev.vitorsilverio.armjitter.advsimd;

import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.7 — núcleo `bfloat16` compartilhado ({@link AdvSimdLanes#bf16Bits}/{@link
/// AdvSimdLanes#bf16ToFloat}): teste diferencial varrendo os 2^16 padrões (`bf16→f32`, exato por
/// construção) e casos dirigidos de arredondamento round-to-nearest-even (`f32→bf16`).
class AdvSimdLanesBFloat16Test {
    @Test
    void bf16ToFloatIsExactForAllSixteenBitPatterns() {
        for (int bits = 0; bits <= 0xFFFF; bits++) {
            float expected = Float.intBitsToFloat(bits << 16);
            float actual = AdvSimdLanes.bf16ToFloat(bits);
            if (Float.isNaN(expected)) {
                assertTrue(Float.isNaN(actual), "bits=0x" + Integer.toHexString(bits));
            } else {
                assertEquals(expected, actual, "bits=0x" + Integer.toHexString(bits));
            }
        }
    }

    @Test
    void bf16BitsTruncatesExactValues() {
        assertEquals(0x3F80L, AdvSimdLanes.bf16Bits(1.0f));
        assertEquals(0x4000L, AdvSimdLanes.bf16Bits(2.0f));
        assertEquals(0xBF80L, AdvSimdLanes.bf16Bits(-1.0f));
        assertEquals(0x0000L, AdvSimdLanes.bf16Bits(0.0f));
        assertEquals(0x8000L, AdvSimdLanes.bf16Bits(-0.0f));
    }

    @Test
    void bf16BitsRoundsToNearestEvenAtExactMidpoint() {
        // 0x3F808000: truncamento daria 0x3F80 (PAR) — na metade exata, fica no PAR: NÃO arredonda.
        float tieToEven = Float.intBitsToFloat(0x3F808000);
        assertEquals(0x3F80L, AdvSimdLanes.bf16Bits(tieToEven));

        // 0x3F818000: truncamento daria 0x3F81 (ÍMPAR) — na metade exata, arredonda para CIMA
        // (0x3F82, que é PAR) em vez de truncar.
        float tieRoundsUp = Float.intBitsToFloat(0x3F818000);
        assertEquals(0x3F82L, AdvSimdLanes.bf16Bits(tieRoundsUp));
    }

    @Test
    void bf16BitsPreservesNanAsQuietWithSign() {
        long positiveNan = AdvSimdLanes.bf16Bits(Float.intBitsToFloat(0x7FC00001));
        assertEquals(0x7FC0L, positiveNan);
        long negativeNan = AdvSimdLanes.bf16Bits(Float.intBitsToFloat(0xFFC00001));
        assertEquals(0xFFC0L, negativeNan);
    }

    @Test
    void bf16BitsOverflowsToInfinity() {
        // Float.MAX_VALUE arredondado para cima estoura o campo de expoente -> +Infinito, mesmo
        // comportamento do hardware (não é tratado como caso especial).
        assertEquals(0x7F80L, AdvSimdLanes.bf16Bits(Float.MAX_VALUE));
        assertTrue(Float.isInfinite(AdvSimdLanes.bf16ToFloat(0x7F80L)));

        assertEquals(0x7F80L, AdvSimdLanes.bf16Bits(Float.POSITIVE_INFINITY));
        assertEquals(0xFF80L, AdvSimdLanes.bf16Bits(Float.NEGATIVE_INFINITY));
    }

    @Test
    void bfDotProductAccumulatesTwoProductsInFloat() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        AdvSimdLanes.setElement(fp, 0, 0, 1, AdvSimdLanes.bf16Bits(1.0f));
        AdvSimdLanes.setElement(fp, 0, 1, 1, AdvSimdLanes.bf16Bits(2.0f));
        AdvSimdLanes.setElement(fp, 1, 0, 1, AdvSimdLanes.bf16Bits(3.0f));
        AdvSimdLanes.setElement(fp, 1, 1, 1, AdvSimdLanes.bf16Bits(4.0f));
        AdvSimdLanes.setElement(fp, 2, 0, 2, AdvSimdLanes.floatBits(10.0f));
        AdvSimdLanes.bfDotProduct(fp, 1, 2, 0, 1);
        assertEquals(21.0f, Float.intBitsToFloat((int) AdvSimdLanes.element(fp, 2, 0, 2)), "10 + 1*3 + 2*4");
    }
}
