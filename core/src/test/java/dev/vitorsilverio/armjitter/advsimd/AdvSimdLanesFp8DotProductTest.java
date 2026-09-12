package dev.vitorsilverio.armjitter.advsimd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.11c — núcleo `FEAT_FP8DOT2` ({@link AdvSimdLanes#fp8DotProduct}): produto escalar FP8
/// FUNDIDO (`elementsPerLane` pares FP8×FP8, arredondamento ÚNICO), independente do executor (que
/// só resolve QUAIS bytes de `Rn`/`Rm`/`FPMR` alimentam esta função — testado em
/// {@code Ir64VectorFpArithmeticExecutorFp8DotProductTest}). Núcleo GENÉRICO por
/// `elementsPerLane` — os testes aqui cobrem `2` (`FDOT_hb`, B19.11c) e `4` (`FDOT_sb`, B19.11d,
/// ainda sem decoder) para provar que a generalização já funciona para a task irmã reusar.
class AdvSimdLanesFp8DotProductTest {
    private static final boolean E4M3 = true;
    private static final boolean E5M2 = false;

    private static int fp8(float value, boolean e4m3) {
        return AdvSimdLanes.floatToFp8(value, e4m3, false);
    }

    private static long pack(boolean e4m3, float... values) {
        long bytes = 0;
        for (int i = 0; i < values.length; i++) {
            bytes |= ((long) fp8(values[i], e4m3) & 0xFF) << (8 * i);
        }
        return bytes;
    }

    @Test
    void twoWayDotProductMatchesManualSumIntoHalf() {
        // (2*3) + (4*5) = 26, acumulador inicial = 1.0 -> 27.0.
        long n = pack(E4M3, 2.0f, 4.0f);
        long m = pack(E4M3, 3.0f, 5.0f);
        long result = AdvSimdLanes.fp8DotProduct(n, m, E4M3, E4M3, 2, 0, false, AdvSimdLanes.halfBits(1.0f), false);
        assertEquals(27.0f, AdvSimdLanes.halfToFloat(result));
    }

    @Test
    void fourWayDotProductMatchesManualSumIntoSingle() {
        // (1*1)+(2*2)+(3*3)+(4*4) = 30, acumulador inicial = 2.0 -> 32.0.
        long n = pack(E4M3, 1.0f, 2.0f, 3.0f, 4.0f);
        long m = pack(E4M3, 1.0f, 2.0f, 3.0f, 4.0f);
        long result = AdvSimdLanes.fp8DotProduct(
                n, m, E4M3, E4M3, 4, 0, false, AdvSimdLanes.floatBits(2.0f), true);
        assertEquals(32.0f, Float.intBitsToFloat((int) result));
    }

    @Test
    void sourceFormatsAreIndependent() {
        long bits = pack(E4M3, 3.0f, 5.0f); // padrão de bits interpretado sob dois formatos.
        long mixed = AdvSimdLanes.fp8DotProduct(bits, bits, E4M3, E5M2, 2, 0, false, 0L, false);
        long sameE4m3 = AdvSimdLanes.fp8DotProduct(bits, bits, E4M3, E4M3, 2, 0, false, 0L, false);
        long sameE5m2 = AdvSimdLanes.fp8DotProduct(bits, bits, E5M2, E5M2, 2, 0, false, 0L, false);
        assertNotEquals(sameE4m3, mixed);
        assertNotEquals(sameE5m2, mixed);
    }

    @Test
    void lscaleDownscalesTheSumByPowerOfTwo() {
        // (2*2)+(2*2) = 8, lscale=2 -> escala por 2^-2 = 0.25 -> resultado = 2.0.
        long n = pack(E4M3, 2.0f, 2.0f);
        long m = pack(E4M3, 2.0f, 2.0f);
        long unscaled = AdvSimdLanes.fp8DotProduct(n, m, E4M3, E4M3, 2, 0, false, 0L, false);
        long scaled = AdvSimdLanes.fp8DotProduct(n, m, E4M3, E4M3, 2, 2, false, 0L, false);
        assertEquals(8.0f, AdvSimdLanes.halfToFloat(unscaled));
        assertEquals(2.0f, AdvSimdLanes.halfToFloat(scaled));
    }

    @Test
    void dotProductMatchesExtendedPrecisionSum() {
        // Soma de produtos FP8 alargados sempre exata em double (mantissa <= 3 bits por operando) —
        // mesma disciplina de {@link AdvSimdLanes#fp8FusedMultiplyAdd} (B19.11b).
        float a0 = AdvSimdLanes.fp8ToFloat(fp8(6.0f, E4M3), E4M3);
        float a1 = AdvSimdLanes.fp8ToFloat(fp8(7.0f, E4M3), E4M3);
        float b0 = AdvSimdLanes.fp8ToFloat(fp8(1.5f, E4M3), E4M3);
        float b1 = AdvSimdLanes.fp8ToFloat(fp8(2.5f, E4M3), E4M3);
        float acc = 3.5f;
        double expected = (double) a0 * b0 + (double) a1 * b1 + acc;
        long n = pack(E4M3, 6.0f, 7.0f);
        long m = pack(E4M3, 1.5f, 2.5f);
        long result = AdvSimdLanes.fp8DotProduct(n, m, E4M3, E4M3, 2, 0, false, AdvSimdLanes.halfBits(acc), false);
        assertEquals((float) expected, AdvSimdLanes.halfToFloat(result));
    }

    // ── `osm` (`FPMR.OSM`) — satura no máximo normal do DESTINO em vez de Infinito ─────────────────

    @Test
    void osmFalseOverflowsToInfinityInHalf() {
        long n = pack(E4M3, 448f, 448f);
        long m = pack(E4M3, 448f, 448f);
        long result = AdvSimdLanes.fp8DotProduct(n, m, E4M3, E4M3, 2, 0, false, 0L, false);
        assertTrue(Float.isInfinite(AdvSimdLanes.halfToFloat(result)));
    }

    @Test
    void osmTrueSaturatesToMaxNormalInHalf() {
        long n = pack(E4M3, 448f, 448f);
        long m = pack(E4M3, 448f, 448f);
        long result = AdvSimdLanes.fp8DotProduct(n, m, E4M3, E4M3, 2, 0, true, 0L, false);
        assertEquals(65504f, AdvSimdLanes.halfToFloat(result), "máximo normal de binary16");
    }

    // ── `NaN` — canonicalizado (`default_nan_mode` do QEMU real) ────────────────────────────────

    @Test
    void nanAccumulatorProducesCanonicalNanInHalf() {
        long n = pack(E4M3, 1.0f, 1.0f);
        long nanHalf = AdvSimdLanes.halfBits(Float.NaN);
        long result = AdvSimdLanes.fp8DotProduct(n, n, E4M3, E4M3, 2, 0, false, nanHalf, false);
        assertTrue(Float.isNaN(AdvSimdLanes.halfToFloat(result)));
    }
}
