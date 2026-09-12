package dev.vitorsilverio.armjitter.advsimd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.11b — núcleo `FEAT_FP8FMA` ({@link AdvSimdLanes#fp8FusedMultiplyAdd}): a fusão
/// produto·escala+acumulador (largura mista FP8→meia/simples precisão), independente do
/// executor (que só resolve QUAIS bytes de `Rn`/`Rm`/`FPMR` alimentam esta função — testado em
/// {@code Ir64VectorFpArithmeticExecutorFp8FusedMultiplyAddTest}).
class AdvSimdLanesFp8FusedMultiplyAddTest {
    private static final boolean E4M3 = true;
    private static final boolean E5M2 = false;

    private static int fp8(float value, boolean e4m3) {
        return AdvSimdLanes.floatToFp8(value, e4m3, false);
    }

    @Test
    void multipliesAndAccumulatesIntoHalf() {
        // 2.0(E4M3) * 3.0(E4M3) = 6.0, acumulador inicial = 1.0 -> 7.0.
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(2.0f, E4M3), E4M3, fp8(3.0f, E4M3), E4M3, 0, false, AdvSimdLanes.halfBits(1.0f), false);
        assertEquals(7.0f, AdvSimdLanes.halfToFloat(result));
    }

    @Test
    void multipliesAndAccumulatesIntoSingle() {
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(2.0f, E4M3), E4M3, fp8(3.0f, E4M3), E4M3, 0, false,
                AdvSimdLanes.floatBits(1.0f), true);
        assertEquals(7.0f, Float.intBitsToFloat((int) result));
    }

    @Test
    void sourceFormatsAreIndependent() {
        // Mesmo padrão de bits, formatos DIFERENTES para Rn (E4M3) e Rm (E5M2) — devolve valores
        // diferentes de usar o MESMO formato para os dois, provando que F8S1/F8S2 são consumidos
        // de forma independente.
        int bits = 0b0_1111_10; // 6 bits baixos usados pelos dois formatos
        long mixed = AdvSimdLanes.fp8FusedMultiplyAdd(bits, E4M3, bits, E5M2, 0, false, 0L, false);
        long sameE4m3 = AdvSimdLanes.fp8FusedMultiplyAdd(bits, E4M3, bits, E4M3, 0, false, 0L, false);
        long sameE5m2 = AdvSimdLanes.fp8FusedMultiplyAdd(bits, E5M2, bits, E5M2, 0, false, 0L, false);
        assertNotEquals(sameE4m3, mixed);
        assertNotEquals(sameE5m2, mixed);
    }

    @Test
    void lscaleDownscalesTheProductByPowerOfTwo() {
        // 4.0 * 1.0, lscale=2 -> escala por 2^-2 = 0.25 -> resultado = 1.0.
        long unscaled = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(4.0f, E4M3), E4M3, fp8(1.0f, E4M3), E4M3, 0, false, 0L, false);
        long scaled = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(4.0f, E4M3), E4M3, fp8(1.0f, E4M3), E4M3, 2, false, 0L, false);
        assertEquals(4.0f, AdvSimdLanes.halfToFloat(unscaled));
        assertEquals(1.0f, AdvSimdLanes.halfToFloat(scaled));
    }

    @Test
    void fusedAccumulationMatchesMathFmaSemantics() {
        // Produto FP8 alargado sempre exato em double (mantissa <= 3 bits) — a fusão aqui NUNCA
        // difere de multiplicar+somar em precisão dupla e arredondar uma vez, mesma disciplina de
        // nota registrada por B13.20/B19.13 (produto de fontes estreitas sempre exato).
        float a = AdvSimdLanes.fp8ToFloat(fp8(6.0f, E4M3), E4M3);
        float b = AdvSimdLanes.fp8ToFloat(fp8(7.0f, E4M3), E4M3);
        float acc = 3.5f;
        double expected = Math.fma((double) a, (double) b, (double) acc);
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(6.0f, E4M3), E4M3, fp8(7.0f, E4M3), E4M3, 0, false, AdvSimdLanes.halfBits(acc), false);
        assertEquals((float) expected, AdvSimdLanes.halfToFloat(result));
    }

    // ── `osm` (`FPMR.OSM`) — satura no máximo normal do DESTINO em vez de Infinito ─────────────────

    @Test
    void osmFalseOverflowsToInfinityInHalf() {
        // 448(E4M3 máximo) * 448 estoura MUITO o alcance de binary16 (max ~65504).
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(448f, E4M3), E4M3, fp8(448f, E4M3), E4M3, 0, false, 0L, false);
        assertTrue(Float.isInfinite(AdvSimdLanes.halfToFloat(result)));
    }

    @Test
    void osmTrueSaturatesToMaxNormalInHalf() {
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(448f, E4M3), E4M3, fp8(448f, E4M3), E4M3, 0, true, 0L, false);
        float value = AdvSimdLanes.halfToFloat(result);
        assertEquals(65504f, value, "máximo normal de binary16");
    }

    @Test
    void osmDoesNotAffectGenuineInfinityFromAccumulator() {
        // Acumulador já é Infinito de verdade (não overflow de arredondamento) — OSM não deve
        // mexer nisso, mesma distinção que {@link AdvSimdLanes#floatToFp8} já faz para `OSC`.
        long positiveInfinityHalf = AdvSimdLanes.halfBits(Float.POSITIVE_INFINITY);
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(1.0f, E4M3), E4M3, fp8(1.0f, E4M3), E4M3, 0, true, positiveInfinityHalf, false);
        assertTrue(Float.isInfinite(AdvSimdLanes.halfToFloat(result)));
    }

    @Test
    void osmSaturatesToMaxNormalInSingleToo() {
        // Escolhe entradas cujo produto exato (sem escala) já estoura `Float.MAX_VALUE`: o maior
        // FP8 finito é 448 (E4M3); ao multiplicar por si mesmo e acumular sobre o próprio
        // `Float.MAX_VALUE`, o resultado exato ultrapassa o alcance de `binary32`.
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(448f, E4M3), E4M3, fp8(448f, E4M3), E4M3, 0, true,
                AdvSimdLanes.floatBits(Float.MAX_VALUE), true);
        assertEquals(Float.MAX_VALUE, Float.intBitsToFloat((int) result));
    }

    // ── `NaN` — canonicalizado (`default_nan_mode` do QEMU real) ────────────────────────────────

    @Test
    void nanAccumulatorProducesCanonicalNanInHalf() {
        long nanHalf = AdvSimdLanes.halfBits(Float.NaN);
        long result = AdvSimdLanes.fp8FusedMultiplyAdd(
                fp8(1.0f, E4M3), E4M3, fp8(1.0f, E4M3), E4M3, 0, false, nanHalf, false);
        assertTrue(Float.isNaN(AdvSimdLanes.halfToFloat(result)));
    }
}
