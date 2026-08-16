package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.FpRoundingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes de {@link DirectedFpRounding} (task B3.8): valores concretos calculados por um
/// programa independente (fora da árvore de produção, ver o `Contexto` da spec) para `1/3` —
/// escolhido porque o resultado NÃO é exato em `float` nem em `double`, e a direção do erro
/// (`exato` acima ou abaixo do candidato round-to-nearest) é OPOSTA entre `float` e `double`
/// para essa mesma fração, cobrindo os dois casos com um único valor de entrada — e um property
/// test que verifica, para uma grande amostra aleatória, os invariantes matemáticos que TODA
/// implementação correta de arredondamento dirigido tem que satisfazer (não repete o algoritmo
/// de {@link DirectedFpRounding}, verifica sua consequência): `RM ≤ RN ≤ RP` sempre, e
/// `RM`/`RP` nunca distam mais de 1 ULP do candidato round-to-nearest.
class DirectedFpRoundingTest {
    private static final int RANDOM_SAMPLE_COUNT = 20_000;
    private static final long FIXED_SEED = 0xB3_8L; // determinístico entre execuções

    // ── 1. Valores concretos (1/3), calculados por programa independente ────────

    @Test
    void float1Div3RoundsAsExpectedInAllFourModes() {
        float rn = 1.0f / 3.0f;
        double exact = (double) 1.0f / (double) 3.0f;
        assertEquals(0x3eaaaaab, Float.floatToRawIntBits(rn), "pré-condição do vetor de teste");
        assertTrue(exact < rn, "pré-condição: exato < candidato round-to-nearest para 1.0f/3.0f");

        assertEquals(rn, DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TO_NEAREST));
        // exato < rn: rn já é o teto correto -> RP não muda nada.
        assertEquals(rn, DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY));
        // exato < rn: o piso correto é o vizinho de baixo.
        assertEquals(Math.nextDown(rn), DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY));
        // exato ≥0 -> RZ = RM.
        assertEquals(Math.nextDown(rn), DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TOWARD_ZERO));
    }

    @Test
    void double1Div3RoundsAsExpectedInAllFourModes() {
        double rn = 1.0 / 3.0;
        BigDecimal exact = BigDecimal.ONE.divide(BigDecimal.valueOf(3), new java.math.MathContext(60));
        assertEquals(0x3fd5555555555555L, Double.doubleToRawLongBits(rn), "pré-condição do vetor de teste");
        assertTrue(exact.compareTo(new BigDecimal(rn)) > 0, "pré-condição: exato > candidato round-to-nearest para 1.0/3.0");

        assertEquals(rn, DirectedFpRounding.roundDouble(rn, exact, FpRoundingMode.ROUND_TO_NEAREST));
        // exato > rn: o teto correto é o vizinho de cima.
        assertEquals(Math.nextUp(rn), DirectedFpRounding.roundDouble(rn, exact, FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY));
        // exato > rn: rn já é o piso correto -> RM não muda nada.
        assertEquals(rn, DirectedFpRounding.roundDouble(rn, exact, FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY));
        // exato ≥0 -> RZ = RM.
        assertEquals(rn, DirectedFpRounding.roundDouble(rn, exact, FpRoundingMode.ROUND_TOWARD_ZERO));
    }

    @Test
    void exactResultIsUnchangedRegardlessOfMode() {
        // 4.0f / 2.0f = 2.0f é exato: nenhum modo deveria mexer.
        float rn = 4.0f / 2.0f;
        double exact = 4.0 / 2.0;
        for (FpRoundingMode mode : FpRoundingMode.values()) {
            assertEquals(rn, DirectedFpRounding.roundFloat(rn, exact, mode), mode.toString());
        }
    }

    @Test
    void nanAndInfinityPassThroughUnchangedInAllModes() {
        for (FpRoundingMode mode : FpRoundingMode.values()) {
            assertTrue(Float.isNaN(DirectedFpRounding.roundFloat(Float.NaN, 0.0, mode)));
            assertEquals(Float.POSITIVE_INFINITY,
                    DirectedFpRounding.roundFloat(Float.POSITIVE_INFINITY, 1e300, mode));
            assertEquals(Double.NEGATIVE_INFINITY,
                    DirectedFpRounding.roundDouble(Double.NEGATIVE_INFINITY, BigDecimal.valueOf(-1e300), mode));
        }
    }

    // ── 2. Property test: invariantes de RM ≤ RN ≤ RP e distância ≤1 ULP ────────

    @Test
    void directedRoundingObeysOrderingInvariantsAcrossRandomDivisions() {
        Random random = new Random(FIXED_SEED);
        int nonTrivialCount = 0;
        for (int i = 0; i < RANDOM_SAMPLE_COUNT; i++) {
            float a = randomFiniteNonZeroFloat(random);
            float b = randomFiniteNonZeroFloat(random);
            float rn = a / b;
            if (Float.isNaN(rn) || Float.isInfinite(rn)) {
                continue;
            }
            double exact = (double) a / (double) b;
            float rp = DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY);
            float rm = DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY);
            float rz = DirectedFpRounding.roundFloat(rn, exact, FpRoundingMode.ROUND_TOWARD_ZERO);

            assertTrue(rm <= rn && rn <= rp,
                    "RM<=RN<=RP violado: a=" + a + " b=" + b + " rm=" + rm + " rn=" + rn + " rp=" + rp);
            assertTrue((double) exact >= (double) rm, "RM deve ser <= exato: " + exact + " vs " + rm);
            assertTrue((double) exact <= (double) rp, "RP deve ser >= exato: " + exact + " vs " + rp);
            // RM e RP nunca distam mais de 1 ULP de RN (o candidato já está a <=0.5 ULP do exato).
            assertTrue(rm == rn || rm == Math.nextDown(rn), "RM a mais de 1 ULP de RN");
            assertTrue(rp == rn || rp == Math.nextUp(rn), "RP a mais de 1 ULP de RN");
            // RZ = RM quando exato>=0, RP quando exato<0.
            assertEquals(exact >= 0 ? rm : rp, rz, "RZ deveria ser RM (exato>=0) ou RP (exato<0)");
            if (rm != rp) {
                nonTrivialCount++;
            }
        }
        // Sanidade: a amostra realmente exercitou casos onde os modos discordam (senão o teste
        // não estaria testando nada além do caminho exato).
        assertTrue(nonTrivialCount > RANDOM_SAMPLE_COUNT / 4,
                "amostra aleatória não gerou divisões suficientemente não-exatas: " + nonTrivialCount);
    }

    @Test
    void directedRoundingObeysOrderingInvariantsAcrossRandomDoubleDivisions() {
        Random random = new Random(FIXED_SEED + 1);
        int nonTrivialCount = 0;
        for (int i = 0; i < RANDOM_SAMPLE_COUNT; i++) {
            double a = randomFiniteNonZeroDouble(random);
            double b = randomFiniteNonZeroDouble(random);
            double rn = a / b;
            if (Double.isNaN(rn) || Double.isInfinite(rn)) {
                continue;
            }
            BigDecimal exact = DirectedFpRounding.approxDiv(a, b);
            double rp = DirectedFpRounding.roundDouble(rn, exact, FpRoundingMode.ROUND_TOWARD_PLUS_INFINITY);
            double rm = DirectedFpRounding.roundDouble(rn, exact, FpRoundingMode.ROUND_TOWARD_MINUS_INFINITY);

            assertTrue(rm <= rn && rn <= rp,
                    "RM<=RN<=RP violado: a=" + a + " b=" + b + " rm=" + rm + " rn=" + rn + " rp=" + rp);
            assertTrue(rm == rn || rm == Math.nextDown(rn), "RM a mais de 1 ULP de RN");
            assertTrue(rp == rn || rp == Math.nextUp(rn), "RP a mais de 1 ULP de RN");
            if (rm != rp) {
                nonTrivialCount++;
            }
        }
        assertTrue(nonTrivialCount > RANDOM_SAMPLE_COUNT / 4,
                "amostra aleatória não gerou divisões suficientemente não-exatas: " + nonTrivialCount);
    }

    /// Amplitude de expoente sorteada em torno de `2^0` (±`EXPONENT_SPREAD`), grande o bastante
    /// para cobrir várias décadas de magnitude sem esbarrar com frequência em overflow/underflow
    /// de `a/b` (que só descartaria a amostra sem invalidar o teste, mas empobreceria a amostra).
    private static final int EXPONENT_SPREAD = 20;

    private static float randomFiniteNonZeroFloat(Random random) {
        float mantissa = random.nextFloat() * 2f - 1f;
        while (mantissa == 0f) {
            mantissa = random.nextFloat() * 2f - 1f;
        }
        int exponent = random.nextInt(2 * EXPONENT_SPREAD + 1) - EXPONENT_SPREAD;
        return (float) (mantissa * Math.pow(2, exponent));
    }

    private static double randomFiniteNonZeroDouble(Random random) {
        double mantissa = random.nextDouble() * 2 - 1;
        while (mantissa == 0.0) {
            mantissa = random.nextDouble() * 2 - 1;
        }
        int exponent = random.nextInt(2 * EXPONENT_SPREAD + 1) - EXPONENT_SPREAD;
        return mantissa * Math.pow(2, exponent);
    }
}
