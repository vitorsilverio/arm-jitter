package dev.vitorsilverio.armjitter.core;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B16.16 — {@link MveWideShifts}, porta verbatim de `do_sqrshl_bhs`/`do_uqrshl_bhs`/`do_sqrshl_d`/
/// `do_uqrshl_d`/`do_sqrshl48_d`/`do_uqrshl48_d` do QEMU. Duas frentes: (1) comparação em massa
/// contra um modelo de precisão infinita (`BigInteger`) — o que o C implementa com truques de
/// 64 bits — e (2) vetores dirigidos das bordas que a task lista (shift 0/1/31/32/63, quantidades
/// de 8 bits negativas, saturação positiva/negativa, não-saturação).
class MveWideShiftsTest {
    private static final int SAMPLES = 40_000;
    private static final int MIN_SHIFT = -128;
    /// `-(int8_t)0x80` = `128`: a maior quantidade que o helper vê.
    private static final int MAX_SHIFT = 128;

    private static final long[] EDGE_VALUES = {
            0L, 1L, -1L, 2L, 3L, 0x7FL, 0x80L, 0xFFL, 0x7FFFL, 0x8000L, 0xFFFFL, 0x7FFF_FFFFL, 0x8000_0000L,
            0xFFFF_FFFFL, 0x1_0000_0000L, 0x7FFF_FFFF_FFFFL, 0x8000_0000_0000L, 0xFFFF_FFFF_FFFFL,
            0x1_0000_0000_0000L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1, 0x4000_0000_0000_0000L,
            -0x8000_0000L, -0x8000_0001L, -0x7FFF_FFFF_FFFFL, -0x8000_0000_0000L, -0x8000_0000_0001L
    };

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }

    /// Modelo de referência: `src` já interpretado (com/sem sinal), quantidade `shift`, largura de
    /// saturação `bits`, largura de saída `outBits`. Devolve {valor como long, saturou?}.
    private static Object[] reference(BigInteger src, int shift, boolean round, boolean sat, boolean signed,
            int bits, int outBits) {
        BigInteger value;
        if (shift >= 0) {
            value = src.shiftLeft(shift);
        } else {
            int n = -shift;
            BigInteger biased = round ? src.add(BigInteger.ONE.shiftLeft(n - 1)) : src;
            value = biased.shiftRight(n);
        }
        BigInteger max = signed ? BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger min = signed ? max.negate().subtract(BigInteger.ONE) : BigInteger.ZERO;
        boolean saturated = false;
        if (sat) {
            if (value.compareTo(max) > 0) {
                value = max;
                saturated = true;
            } else if (value.compareTo(min) < 0) {
                value = min;
                saturated = true;
            }
        }
        long truncated = value.longValue();
        if (outBits == 32) {
            truncated &= 0xFFFF_FFFFL;
        }
        return new Object[] {truncated, saturated};
    }

    private static void check(MveWideShifts.Result actual, Object[] expected, String context) {
        assertEquals(expected[0], actual.value(), "valor: " + context);
        assertEquals(expected[1], actual.saturated(), "saturou: " + context);
    }

    private static long sample(Random random) {
        return random.nextInt(4) == 0 ? EDGE_VALUES[random.nextInt(EDGE_VALUES.length)] : random.nextLong();
    }

    // ── 32 bits ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void signedShift32MatchesInfinitePrecisionModel() {
        Random random = new Random(0x5A32);
        for (int i = 0; i < SAMPLES; i++) {
            int src = (int) sample(random);
            int shift = MIN_SHIFT + random.nextInt(MAX_SHIFT - MIN_SHIFT + 1);
            boolean round = random.nextBoolean();
            boolean sat = random.nextBoolean();
            check(MveWideShifts.signedShift32(src, shift, round, sat),
                    reference(BigInteger.valueOf(src), shift, round, sat, true, 32, 32),
                    "sqrshl32 src=" + src + " shift=" + shift + " round=" + round + " sat=" + sat);
        }
    }

    @Test
    void unsignedShift32MatchesInfinitePrecisionModel() {
        Random random = new Random(0x5A33);
        for (int i = 0; i < SAMPLES; i++) {
            int src = (int) sample(random);
            int shift = MIN_SHIFT + random.nextInt(MAX_SHIFT - MIN_SHIFT + 1);
            boolean round = random.nextBoolean();
            boolean sat = random.nextBoolean();
            check(MveWideShifts.unsignedShift32(src, shift, round, sat),
                    reference(BigInteger.valueOf(src & 0xFFFF_FFFFL), shift, round, sat, false, 32, 32),
                    "uqrshl32 src=" + src + " shift=" + shift + " round=" + round + " sat=" + sat);
        }
    }

    @Test
    void signedShift32SaturatesPositiveAndNegative() {
        MveWideShifts.Result positive = MveWideShifts.signedShift32(0x4000_0000, 1, false, true);
        assertEquals(0x7FFF_FFFFL, positive.value());
        assertTrue(positive.saturated());
        MveWideShifts.Result negative = MveWideShifts.signedShift32(0xBFFF_FFFF, 1, false, true);
        assertEquals(0x8000_0000L, negative.value());
        assertTrue(negative.saturated());
    }

    @Test
    void signedShift32WithoutSaturationDoesNotFlag() {
        MveWideShifts.Result result = MveWideShifts.signedShift32(0x4000_0000, 1, false, false);
        assertEquals(0x8000_0000L, result.value(), "só trunca");
        assertFalse(result.saturated());
    }

    @Test
    void unsignedShift32FullWidthShiftOfNonZeroSaturatesAndZeroDoesNot() {
        MveWideShifts.Result nonZero = MveWideShifts.unsignedShift32(1, 32, false, true);
        assertEquals(0xFFFF_FFFFL, nonZero.value());
        assertTrue(nonZero.saturated());
        MveWideShifts.Result zero = MveWideShifts.unsignedShift32(0, 32, false, true);
        assertEquals(0L, zero.value());
        assertFalse(zero.saturated());
    }

    @Test
    void roundingRightShiftByFullWidthReadsTheTopBitForUnsigned() {
        // URSHR #32: round(x / 2^32) = bit 31 de x.
        assertEquals(1L, MveWideShifts.unsignedShift32(0x8000_0000, -32, true, false).value());
        assertEquals(0L, MveWideShifts.unsignedShift32(0x7FFF_FFFF, -32, true, false).value());
        // SRSHR #32 sempre produz 0 (o sinal arredondado some).
        assertEquals(0L, MveWideShifts.signedShift32(0x8000_0000, -32, true, false).value());
        assertEquals(0L, MveWideShifts.signedShift32(0x7FFF_FFFF, -32, true, false).value());
    }

    // ── 64 bits ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void signedShift64MatchesInfinitePrecisionModel() {
        Random random = new Random(0x5A64);
        for (int i = 0; i < SAMPLES; i++) {
            long src = sample(random);
            int shift = MIN_SHIFT + random.nextInt(MAX_SHIFT - MIN_SHIFT + 1);
            boolean round = random.nextBoolean();
            boolean sat = random.nextBoolean();
            check(MveWideShifts.signedShift64(src, shift, round, sat),
                    reference(BigInteger.valueOf(src), shift, round, sat, true, 64, 64),
                    "sqrshl64 src=" + src + " shift=" + shift + " round=" + round + " sat=" + sat);
        }
    }

    @Test
    void unsignedShift64MatchesInfinitePrecisionModel() {
        Random random = new Random(0x5A65);
        for (int i = 0; i < SAMPLES; i++) {
            long src = sample(random);
            int shift = MIN_SHIFT + random.nextInt(MAX_SHIFT - MIN_SHIFT + 1);
            boolean round = random.nextBoolean();
            boolean sat = random.nextBoolean();
            check(MveWideShifts.unsignedShift64(src, shift, round, sat),
                    reference(unsigned(src), shift, round, sat, false, 64, 64),
                    "uqrshl64 src=" + src + " shift=" + shift + " round=" + round + " sat=" + sat);
        }
    }

    @Test
    void shift64AtTheWordBoundaryBehavesLikeAPlainShift() {
        assertEquals(0x1_0000_0000L, MveWideShifts.unsignedShift64(1, 32, false, false).value());
        assertEquals(0x1L, MveWideShifts.unsignedShift64(0x1_0000_0000L, -32, false, false).value());
        assertEquals(-1L, MveWideShifts.signedShift64(Long.MIN_VALUE, -63, false, false).value());
        assertEquals(0L, MveWideShifts.unsignedShift64(Long.MIN_VALUE, -64, false, false).value());
    }

    @Test
    void signedShift64SaturatesToTheSignedLimits() {
        MveWideShifts.Result positive = MveWideShifts.signedShift64(Long.MAX_VALUE, 1, false, true);
        assertEquals(Long.MAX_VALUE, positive.value());
        assertTrue(positive.saturated());
        MveWideShifts.Result negative = MveWideShifts.signedShift64(Long.MIN_VALUE, 1, false, true);
        assertEquals(Long.MIN_VALUE, negative.value());
        assertTrue(negative.saturated());
    }

    // ── 48 bits ─────────────────────────────────────────────────────────────────────────────────
    //
    // `do_sqrshl48_d`/`do_uqrshl48_d` são chamadas SEMPRE com round=true e sat!=NULL. O QEMU tem um
    // corte que NÃO é a matemática exata: `shift <= -48` (com sinal) e `shift <= -49` (sem sinal)
    // devolvem 0 direto — mesmo quando o valor de 64 bits ainda tem bits significativos acima do
    // bit 48. Portado verbatim (a task manda o QEMU ser o oráculo); os testes abaixo cobrem a faixa
    // em que o modelo exato e o QEMU coincidem, e fixam o corte separadamente.

    @Test
    void signedShift48MatchesInfinitePrecisionModelOutsideTheZeroCut() {
        Random random = new Random(0x5A48);
        for (int i = 0; i < SAMPLES; i++) {
            long src = sample(random);
            int shift = -47 + random.nextInt(MAX_SHIFT + 47 + 1);
            check(MveWideShifts.signedShift48(src, shift),
                    reference(BigInteger.valueOf(src), shift, true, true, true, 48, 64),
                    "sqrshl48 src=" + src + " shift=" + shift);
        }
    }

    @Test
    void unsignedShift48MatchesInfinitePrecisionModelOutsideTheZeroCut() {
        Random random = new Random(0x5A49);
        for (int i = 0; i < SAMPLES; i++) {
            long src = sample(random);
            int shift = -48 + random.nextInt(MAX_SHIFT + 48 + 1);
            check(MveWideShifts.unsignedShift48(src, shift),
                    reference(unsigned(src), shift, true, true, false, 48, 64),
                    "uqrshl48 src=" + src + " shift=" + shift);
        }
    }

    @Test
    void shift48ZeroCutFollowsQemuNotTheExactModel() {
        // Um valor com bits acima do 48º: o modelo exato daria não-zero, o QEMU devolve 0.
        assertEquals(0L, MveWideShifts.signedShift48(0x7FFF_FFFF_FFFF_FFFFL, -48).value());
        assertFalse(MveWideShifts.signedShift48(0x7FFF_FFFF_FFFF_FFFFL, -48).saturated());
        assertEquals(0L, MveWideShifts.signedShift48(Long.MIN_VALUE, -128).value());
        assertEquals(0L, MveWideShifts.unsignedShift48(-1L, -49).value());
        assertFalse(MveWideShifts.unsignedShift48(-1L, -49).saturated());
        // Sem sinal, -48 AINDA calcula (o corte é -49).
        assertEquals(0x1_0000L, MveWideShifts.unsignedShift48(-1L, -48).value(),
                "(2^64-1 + 2^47) >> 48 = 0x10000, cabe em 48 bits");
    }

    @Test
    void shift48SaturatesAtFortyEightBitsNotSixtyFour() {
        MveWideShifts.Result unsignedResult = MveWideShifts.unsignedShift48(0x1_0000_0000_0000L, 1);
        assertEquals(0xFFFF_FFFF_FFFFL, unsignedResult.value());
        assertTrue(unsignedResult.saturated());
        MveWideShifts.Result positive = MveWideShifts.signedShift48(0x7FFF_FFFF_FFFFL, 1);
        assertEquals(0x7FFF_FFFF_FFFFL, positive.value());
        assertTrue(positive.saturated());
        MveWideShifts.Result negative = MveWideShifts.signedShift48(-0x8000_0000_0000L, 1);
        assertEquals(0xFFFF_8000_0000_0000L, negative.value());
        assertTrue(negative.saturated());
    }

    @Test
    void shift48FullWidthLeftShiftOnlySaturatesNonZero() {
        assertEquals(0L, MveWideShifts.unsignedShift48(0, 48).value());
        assertFalse(MveWideShifts.unsignedShift48(0, 48).saturated());
        assertTrue(MveWideShifts.unsignedShift48(1, 48).saturated());
        assertEquals(0L, MveWideShifts.signedShift48(0, 100).value());
        assertFalse(MveWideShifts.signedShift48(0, 100).saturated());
        assertEquals(0x7FFF_FFFF_FFFFL, MveWideShifts.signedShift48(5, 48).value());
        assertEquals(0xFFFF_8000_0000_0000L, MveWideShifts.signedShift48(-5, 48).value());
    }
}
