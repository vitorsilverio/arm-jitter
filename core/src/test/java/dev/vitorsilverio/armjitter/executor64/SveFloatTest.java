package dev.vitorsilverio.armjitter.executor64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Casos de canto do soft-float de SVE (B17.13) exercitados DIRETO, sem passar pelo decoder: NaN/∞ no addendo do
/// multiply-add fundido, soma exata zero e o sinal do zero por modo de arredondamento, `FSCALE` de NaN/∞/0, e as
/// estimativas `FRECPE`/`FRSQRTE` em entradas denormais, com resultado denormal, sob `FZ` e sob os modos dirigidos.
/// Precisão simples (`esz = 2`) salvo onde dito; os valores de referência são bits IEEE 754 conhecidos.
class SveFloatTest {
    private static final long FPCR_RMODE_PLUS = 1L << 22;
    private static final long FPCR_RMODE_MINUS = 2L << 22;
    private static final long FPCR_RMODE_ZERO = 3L << 22;
    private static final long FPCR_FZ = 1L << 24;

    private static final long POSITIVE_ZERO = 0x00000000L;
    private static final long NEGATIVE_ZERO = 0x80000000L;
    private static final long ONE = 0x3f800000L;
    private static final long TWO = 0x40000000L;
    private static final long THREE = 0x40400000L;
    private static final long MINUS_ONE = 0xbf800000L;
    private static final long POSITIVE_INFINITY = 0x7f800000L;
    private static final long NEGATIVE_INFINITY = 0xff800000L;
    private static final long DEFAULT_NAN = 0x7fc00000L;
    private static final long MAX_FINITE = 0x7f7fffffL;

    private static SveFloat.Env env(long fpcr) {
        return SveFloat.Env.ofFpcr(fpcr, 2);
    }

    private static double asDouble(long bits) {
        return Float.intBitsToFloat((int) bits);
    }

    // ── multiply-add fundido ─────────────────────────────────────────────────────────────────────

    @Test
    void anInfinityTimesZeroProductWithAQuietNanAddendIsAnInvalidOperationNotAPropagatedNan() {
        SveFloat.Env env = env(0L);
        assertEquals(DEFAULT_NAN, SveFloat.fusedMultiplyAdd(0x7fc00007L, POSITIVE_INFINITY, POSITIVE_ZERO, 0, env));
        assertEquals(SveFloat.FLAG_IOC, env.flags);
    }

    @Test
    void zeroTimesInfinityInEitherOrderIsInvalidAndASignalingAddendWinsOverIt() {
        SveFloat.Env env = env(0L);
        assertEquals(DEFAULT_NAN, SveFloat.fusedMultiplyAdd(ONE, POSITIVE_INFINITY, POSITIVE_ZERO, 0, env), "inf × 0");
        assertEquals(DEFAULT_NAN, SveFloat.fusedMultiplyAdd(ONE, POSITIVE_ZERO, NEGATIVE_INFINITY, 0, env), "0 × inf");
        assertEquals(SveFloat.FLAG_IOC, env.flags);
        env = env(0L);
        assertEquals(0x7fc00007L, SveFloat.fusedMultiplyAdd(0x7f800007L, POSITIVE_ZERO, POSITIVE_INFINITY, 0, env),
                "SNaN no addendo: propagado (só o QNaN vira NaN padrão)");
        assertEquals(SveFloat.FLAG_IOC, env.flags);
        env = env(0L);
        assertEquals(0x7fc00007L, SveFloat.fusedMultiplyAdd(0x7fc00007L, TWO, THREE, 0, env), "QNaN com produto comum");
        assertEquals(0, env.flags);
    }

    @Test
    void aNanAddendIsPropagatedQuietedWhenTheProductIsOrdinary() {
        SveFloat.Env env = env(0L);
        assertEquals(0x7fc00007L, SveFloat.fusedMultiplyAdd(0x7f800007L, TWO, THREE, 0, env));
        assertEquals(SveFloat.FLAG_IOC, env.flags, "SNaN levanta IOC");
    }

    @Test
    void infinitiesOfOppositeSignsAreInvalidAndAnInfiniteAddendOrProductWins() {
        SveFloat.Env env = env(0L);
        assertEquals(DEFAULT_NAN, SveFloat.fusedMultiplyAdd(NEGATIVE_INFINITY, POSITIVE_INFINITY, ONE, 0, env), "+inf + -inf");
        assertEquals(SveFloat.FLAG_IOC, env.flags);
        env = env(0L);
        assertEquals(NEGATIVE_INFINITY, SveFloat.fusedMultiplyAdd(NEGATIVE_INFINITY, TWO, THREE, 0, env), "addendo infinito");
        assertEquals(NEGATIVE_INFINITY, SveFloat.fusedMultiplyAdd(NEGATIVE_INFINITY, POSITIVE_INFINITY, MINUS_ONE, 0, env),
                "produto -inf com addendo -inf");
        assertEquals(POSITIVE_INFINITY, SveFloat.fusedMultiplyAdd(ONE, POSITIVE_INFINITY, TWO, 0, env), "produto infinito");
        assertEquals(0, env.flags);
    }

    @Test
    void theSignOfAnExactZeroDependsOnTheOperandsAndOnTheRoundingMode() {
        // produto zero + addendo zero
        assertEquals(POSITIVE_ZERO, SveFloat.fusedMultiplyAdd(POSITIVE_ZERO, POSITIVE_ZERO, ONE, 0, env(0L)));
        assertEquals(NEGATIVE_ZERO, SveFloat.fusedMultiplyAdd(NEGATIVE_ZERO, NEGATIVE_ZERO, ONE, 0, env(0L)), "-0 + -0");
        assertEquals(POSITIVE_ZERO, SveFloat.fusedMultiplyAdd(NEGATIVE_ZERO, POSITIVE_ZERO, ONE, 0, env(0L)), "+0 + -0 = +0");
        assertEquals(NEGATIVE_ZERO, SveFloat.fusedMultiplyAdd(NEGATIVE_ZERO, POSITIVE_ZERO, ONE, 0, env(FPCR_RMODE_MINUS)),
                "+0 + -0 = -0 sob RM");
        // 1 × 1 + (-1): cancelamento exato
        assertEquals(POSITIVE_ZERO, SveFloat.fusedMultiplyAdd(MINUS_ONE, ONE, ONE, 0, env(0L)));
        assertEquals(NEGATIVE_ZERO, SveFloat.fusedMultiplyAdd(MINUS_ONE, ONE, ONE, 0, env(FPCR_RMODE_MINUS)));
        // produto zero com addendo diferente de zero, e o contrário
        assertEquals(THREE, SveFloat.fusedMultiplyAdd(THREE, POSITIVE_ZERO, TWO, 0, env(0L)));
        assertEquals(0x40c00000L, SveFloat.fusedMultiplyAdd(POSITIVE_ZERO, TWO, THREE, 0, env(0L)), "0 + 2×3 = 6");
    }

    @Test
    void theScaleOfTheFusedMultiplyAddHalvesBeforeTheSingleRounding() {
        // (3 - 1×1)/2 = 1.0 e (3 - 3×1)/2 = 0
        assertEquals(ONE, SveFloat.fusedMultiplyAdd(THREE, MINUS_ONE, ONE, -1, env(0L)));
        assertEquals(POSITIVE_ZERO, SveFloat.fusedMultiplyAdd(THREE, MINUS_ONE, THREE, -1, env(0L)));
    }

    // ── FSCALE, FMAXNM ───────────────────────────────────────────────────────────────────────────

    @Test
    void scaleOfNanInfinityAndZeroKeepsTheOperandKind() {
        SveFloat.Env env = env(0L);
        assertEquals(0x7fc00001L, SveFloat.scale(0x7f800001L, 4, env), "SNaN silenciado");
        assertEquals(SveFloat.FLAG_IOC, env.flags);
        assertEquals(NEGATIVE_INFINITY, SveFloat.scale(NEGATIVE_INFINITY, -100, env));
        assertEquals(NEGATIVE_ZERO, SveFloat.scale(NEGATIVE_ZERO, 100, env));
        assertEquals(0x41000000L, SveFloat.scale(ONE, 3, env), "1 × 2^3");
        assertEquals(POSITIVE_INFINITY, SveFloat.scale(ONE, Long.MAX_VALUE, env), "expoente gigante grampeado");
        assertEquals(POSITIVE_ZERO, SveFloat.scale(ONE, Long.MIN_VALUE, env));
    }

    @Test
    void maxnmWithTwoQuietNansOrANanOnTheRightBehavesAsThePseudocodeSays() {
        SveFloat.Env env = env(0L);
        assertEquals(0x7fc00003L, SveFloat.maxMinNumber(0x7fc00003L, 0x7fc00005L, true, env), "dois QNaN: o primeiro");
        assertEquals(TWO, SveFloat.maxMinNumber(TWO, 0x7fc00005L, true, env), "QNaN à direita vale -inf");
        assertEquals(TWO, SveFloat.maxMinNumber(TWO, 0x7fc00005L, false, env), "QNaN à direita vale +inf no mínimo");
        assertEquals(0, env.flags);
    }

    // ── FRSQRTS ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void reciprocalSquareRootStepOfInfinityTimesZeroIsOnePointFive() {
        assertEquals(0x3fc00000L, SveFloat.reciprocalSquareRootStep(POSITIVE_INFINITY, POSITIVE_ZERO, env(0L)));
        assertEquals(0x3fc00000L, SveFloat.reciprocalSquareRootStep(NEGATIVE_ZERO, NEGATIVE_INFINITY, env(0L)));
        assertEquals(TWO, SveFloat.reciprocalStep(POSITIVE_INFINITY, POSITIVE_ZERO, env(0L)));
    }

    // ── FRECPE ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void reciprocalEstimateOverflowFollowsTheRoundingMode() {
        long tiny = 0x00100000L; // |x| < 2^-128: o inverso não cabe
        long[][] cases = {// {fpcr, entrada, esperado}
            {0L, tiny, POSITIVE_INFINITY}, {FPCR_RMODE_PLUS, tiny, POSITIVE_INFINITY},
            {FPCR_RMODE_MINUS, tiny, MAX_FINITE}, {FPCR_RMODE_ZERO, tiny, MAX_FINITE},
            {0L, tiny | NEGATIVE_ZERO, NEGATIVE_INFINITY}, {FPCR_RMODE_PLUS, tiny | NEGATIVE_ZERO, 0xff7fffffL},
            {FPCR_RMODE_MINUS, tiny | NEGATIVE_ZERO, NEGATIVE_INFINITY}, {FPCR_RMODE_ZERO, tiny | NEGATIVE_ZERO, 0xff7fffffL}};
        for (long[] c : cases) {
            SveFloat.Env env = env(c[0]);
            assertEquals(c[2], SveFloat.reciprocalEstimate(c[1], env), "fpcr=" + Long.toHexString(c[0]) + " x=" + Long.toHexString(c[1]));
            assertEquals(SveFloat.FLAG_OFC | SveFloat.FLAG_IXC, env.flags);
        }
    }

    @Test
    void reciprocalEstimateFlushesHugeInputsUnderFzWithUnderflow() {
        SveFloat.Env env = env(FPCR_FZ);
        assertEquals(POSITIVE_ZERO, SveFloat.reciprocalEstimate(0x7e800000L, env), "2^126: o inverso é denormal");
        assertEquals(SveFloat.FLAG_UFC, env.flags);
        env = env(FPCR_FZ);
        assertEquals(NEGATIVE_ZERO, SveFloat.reciprocalEstimate(0xfe800000L, env));
    }

    @Test
    void reciprocalEstimateWorksOnDenormalInputsAndWithDenormalResults() {
        // Denormais com o bit mais alto da fração ligado/desligado (dois caminhos de normalização), e entradas
        // enormes cujo inverso é denormal (expoente do resultado 0 e -1).
        long[] inputs = {0x00400000L, 0x00200000L, 0x007fffffL, 0x7e800000L, 0x7f000000L, 0x7f7fffffL};
        for (long input : inputs) {
            SveFloat.Env env = env(0L);
            long estimate = SveFloat.reciprocalEstimate(input, env);
            double x = asDouble(input);
            double product = asDouble(estimate) * x;
            assertEquals(1.0, product, 1.0 / 128, "1/x para x=" + Long.toHexString(input) + " estimativa=" + Long.toHexString(estimate));
            assertEquals(0, env.flags, "sem exceção para " + Long.toHexString(input));
        }
        assertTrue((SveFloat.reciprocalEstimate(0x7e800000L, env(0L)) >>> 23 & 0xFF) == 0, "resultado denormal (expoente 0)");
        assertTrue((SveFloat.reciprocalEstimate(0x7f000000L, env(0L)) >>> 23 & 0xFF) == 0, "resultado denormal (expoente -1)");
    }

    @Test
    void reciprocalSquareRootEstimateWorksOnDenormalInputs() {
        long[] inputs = {0x00000001L, 0x00000400L, 0x00200000L, 0x00400000L, 0x007fffffL};
        for (long input : inputs) {
            SveFloat.Env env = env(0L);
            double estimate = asDouble(SveFloat.reciprocalSquareRootEstimate(input, env));
            double x = asDouble(input);
            assertEquals(1.0, estimate * estimate * x, 1.0 / 64, "1/sqrt(x) para x=" + Long.toHexString(input));
            assertEquals(0, env.flags);
        }
    }
}
