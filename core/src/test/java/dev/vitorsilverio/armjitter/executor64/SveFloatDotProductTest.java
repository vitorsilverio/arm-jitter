package dev.vitorsilverio.armjitter.executor64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `SveFloat#fusedDotProduct2` (B18.5) e o ambiente de arredondamento ímpar com estouro para infinito — o núcleo
/// de `FMOPA`/`BFMOPA` widening. Referências escritas à mão a partir da definição (`FPDot`: soma exata de dois
/// produtos, UM arredondamento).
class SveFloatDotProductTest {
    private static final long FPCR_DN = 1L << 25;
    private static final long FPCR_FZ16 = 1L << 19;
    private static final long FPCR_RMODE_UP = 1L << 22;
    private static final long FPCR_RMODE_DOWN = 2L << 22;

    private static final long H_ONE = 0x3C00L;
    private static final long H_TWO = 0x4000L;
    private static final long H_THREE = 0x4200L;
    private static final long H_FOUR = 0x4400L;
    private static final long H_MINUS_ONE = 0xBC00L;
    private static final long H_INFINITY = 0x7C00L;
    private static final long H_MIN_SUBNORMAL = 0x0001L; // 2^-24
    private static final long H_QUIET_NAN = 0x7E00L;
    private static final long H_SIGNALING_NAN = 0x7C01L;
    private static final long H_ZERO = 0L;
    private static final long H_MINUS_ZERO = 0x8000L;

    private static final long F_ONE = 0x3F80_0000L;
    private static final long F_DEFAULT_NAN = 0x7FC0_0000L;
    private static final long F_INFINITY = 0x7F80_0000L;

    private static SveFloat.Env half(long fpcr) {
        return SveFloat.Env.ofFpcr(fpcr | FPCR_DN, SveFloat.ESZ_HALF);
    }

    private static SveFloat.Env single(long fpcr) {
        return SveFloat.Env.ofFpcr(fpcr | FPCR_DN, SveFloat.ESZ_SINGLE);
    }

    private static long dot(long a0, long b0, long a1, long b1, long fpcr) {
        return SveFloat.fusedDotProduct2(a0, b0, a1, b1, half(fpcr), single(fpcr));
    }

    @Test
    void sumsTwoExactProducts() {
        assertEquals(Float.floatToRawIntBits(11f) & 0xFFFF_FFFFL, dot(H_ONE, H_THREE, H_TWO, H_FOUR, 0L));
    }

    @Test
    void roundsTheExactSumOnceHonouringTheRoundingMode() {
        // 1*1 + 2^-24 * 2^-24 = 1 + 2^-48: ao mais próximo é 1; para cima é o próximo float.
        assertEquals(F_ONE, dot(H_ONE, H_ONE, H_MIN_SUBNORMAL, H_MIN_SUBNORMAL, 0L));
        assertEquals(F_ONE + 1, dot(H_ONE, H_ONE, H_MIN_SUBNORMAL, H_MIN_SUBNORMAL, FPCR_RMODE_UP));
        assertEquals(F_ONE, dot(H_ONE, H_ONE, H_MIN_SUBNORMAL, H_MIN_SUBNORMAL, FPCR_RMODE_DOWN));
    }

    @Test
    void anExactZeroSumIsPositiveExceptWhenRoundingDown() {
        assertEquals(0L, dot(H_ONE, H_ONE, H_MINUS_ONE, H_ONE, 0L));
        assertEquals(0x8000_0000L, dot(H_ONE, H_ONE, H_MINUS_ONE, H_ONE, FPCR_RMODE_DOWN));
    }

    @Test
    void twoZeroProductsFollowTheSignRulesOfAnAddition() {
        assertEquals(0L, dot(H_ZERO, H_ONE, H_ZERO, H_ONE, 0L));
        assertEquals(0x8000_0000L, dot(H_MINUS_ZERO, H_ONE, H_MINUS_ZERO, H_ONE, 0L));
        assertEquals(0L, dot(H_ZERO, H_ONE, H_MINUS_ZERO, H_ONE, 0L));
        assertEquals(0x8000_0000L, dot(H_ZERO, H_ONE, H_MINUS_ZERO, H_ONE, FPCR_RMODE_DOWN));
    }

    @Test
    void aSingleZeroProductLeavesTheOtherExact() {
        assertEquals(Float.floatToRawIntBits(6f) & 0xFFFF_FFFFL, dot(H_ZERO, H_ONE, H_TWO, H_THREE, 0L));
        assertEquals(Float.floatToRawIntBits(2f) & 0xFFFF_FFFFL, dot(H_TWO, H_ONE, H_ZERO, H_ONE, 0L));
    }

    @Test
    void infinitiesPropagateAndOppositeInfinitiesAreInvalid() {
        assertEquals(F_INFINITY, dot(H_INFINITY, H_ONE, H_ONE, H_ONE, 0L));
        assertEquals(F_INFINITY, dot(H_ONE, H_ONE, H_INFINITY, H_ONE, 0L));
        assertEquals(F_INFINITY, dot(H_INFINITY, H_ONE, H_INFINITY, H_ONE, 0L), "dois infinitos de mesmo sinal");
        assertEquals(0xFF80_0000L, dot(H_ONE, H_ONE, H_INFINITY, H_MINUS_ONE, 0L));
        assertEquals(F_DEFAULT_NAN, dot(H_INFINITY, H_ONE, H_INFINITY, H_MINUS_ONE, 0L));
    }

    @Test
    void infinityTimesZeroIsInvalid() {
        assertEquals(F_DEFAULT_NAN, dot(H_INFINITY, H_ZERO, H_ONE, H_ONE, 0L));
        assertEquals(F_DEFAULT_NAN, dot(H_ONE, H_ONE, H_ZERO, H_INFINITY, 0L));
    }

    @Test
    void anyNanOperandYieldsTheDefaultNanAndASignalingOneRaisesInvalid() {
        assertEquals(F_DEFAULT_NAN, dot(H_QUIET_NAN, H_ONE, H_ONE, H_ONE, 0L));
        SveFloat.Env out = single(0L);
        assertEquals(F_DEFAULT_NAN, SveFloat.fusedDotProduct2(H_ONE, H_ONE, H_SIGNALING_NAN, H_ONE, half(0L), out));
        assertEquals(SveFloat.FLAG_IOC, out.flags & SveFloat.FLAG_IOC);
    }

    @Test
    void halfPrecisionFlushToZeroAppliesToTheInputsOnly() {
        // 2^-24 * 1 + 1 * 1: sem FZ16 a soma é 1 + 2^-24 (empata para par = 1); o ponto é o produto sozinho.
        long denormalOnly = dot(H_MIN_SUBNORMAL, H_ONE, H_ZERO, H_ONE, 0L);
        assertEquals(Float.floatToRawIntBits((float) Math.scalb(1.0, -24)) & 0xFFFF_FFFFL, denormalOnly);
        assertEquals(0L, dot(H_MIN_SUBNORMAL, H_ONE, H_ZERO, H_ONE, FPCR_FZ16), "FZ16 achata a entrada denormal");
    }

    @Test
    void theNonExtendedBFloatEnvironmentOverflowsToInfinityAndRoundsToOdd() {
        SveFloat.Env env = SveFloat.Env.ofBFloat16NonExtended();
        assertEquals(F_INFINITY, SveFloat.add(0x7F7F_FFFFL, 0x7F7F_FFFFL, false, env), "estouro vai a infinito");
        assertEquals(0x3F80_0001L, SveFloat.add(F_ONE, 0x3280_0000L, false, env), "1 + 2^-26 inexato: LSB forçado a 1");
        assertTrue(env.defaultNan);
        assertTrue(env.flushToZero);
    }
}
