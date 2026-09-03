package dev.vitorsilverio.armjitter.advsimd;

import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64FpRegisters;
import org.junit.jupiter.api.Test;

import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes do núcleo vetorial COMPARTILHADO (RFC B13.2, D1): a MESMA função de lane roda sobre os
/// dois bancos de registradores, que só diferem no mapeamento registrador→palavra.
class AdvSimdLanesTest {
    @Test
    void elementAccessCrossesTheWordBoundaryOnBothBanks() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, 0x1122_3344_5566_7788L);
        vfp.setD(1, 0x99AA_BBCC_DDEE_FF00L);
        // `Q0` = palavras 0 e 1; lane 8 (byte) é o primeiro byte da palavra ALTA.
        assertEquals(0x00L, AdvSimdLanes.element(vfp, 0, 8, 0));
        assertEquals(0x88L, AdvSimdLanes.element(vfp, 0, 0, 0));
        assertEquals(0x99AAL, AdvSimdLanes.element(vfp, 0, 7, 1));

        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setQ(0, 0x1122_3344_5566_7788L, 0x99AA_BBCC_DDEE_FF00L);
        assertEquals(0x00L, AdvSimdLanes.element(fp, 0, 8, 0));
        assertEquals(0x88L, AdvSimdLanes.element(fp, 0, 0, 0));
        assertEquals(0x99AAL, AdvSimdLanes.element(fp, 0, 7, 1));
    }

    @Test
    void setElementTouchesOnlyItsOwnLane() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(2, -1L);
        vfp.setD(3, -1L);
        AdvSimdLanes.setElement(vfp, 2, 1, 1, 0x1234);
        assertEquals(0xFFFF_FFFF_1234_FFFFL, vfp.d(2));
        assertEquals(-1L, vfp.d(3));
    }

    /// O caso que a API `Q`-indexada da B13.1 NÃO expressa: um operando NEON de 64 bits em `D`
    /// ÍMPAR (aqui `D5`, que é a metade ALTA de `Q2`). Na vista plana é só `baseWord = 5`.
    @Test
    void threeSameOperatesOnAnOddDoubleRegister() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(5, 0x0001_0002_0003_0004L);
        vfp.setD(7, 0x0010_0020_0030_0040L);
        AdvSimdLanes.threeSame(vfp, AdvSimdThreeSameOp.ADD, 1, 4, 9, 5, 7);
        assertEquals(0x0011_0022_0033_0044L, vfp.d(9));
        // Nenhum vizinho do par foi tocado (VFP32 nunca escreve fora do registrador nomeado).
        assertEquals(0L, vfp.d(8));
        assertEquals(0L, vfp.d(4));
    }

    @Test
    void threeSameWrapsAroundInsideEachLane() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, 0x0000_0000_0000_00FFL);
        vfp.setD(1, 0x0000_0000_0000_0001L);
        AdvSimdLanes.threeSame(vfp, AdvSimdThreeSameOp.ADD, 0, 8, 2, 0, 1);
        // `VADD.I8`: o carry NÃO atravessa para a lane vizinha.
        assertEquals(0x0000_0000_0000_0000L, vfp.d(2));

        AdvSimdLanes.threeSame(vfp, AdvSimdThreeSameOp.SUB, 0, 8, 3, 1, 0);
        assertEquals(0x0000_0000_0000_0002L, vfp.d(3));
    }

    /// Mesmo kernel, banco do A64: `V<n>` começa na palavra `2n`.
    @Test
    void threeSameOnAarch64BankUsesTwoWordsPerRegister() {
        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setQ(1, 0x0000_0001_0000_0002L, 0x0000_0003_0000_0004L);
        fp.setQ(2, 0x0000_0010_0000_0020L, 0x0000_0030_0000_0040L);
        AdvSimdLanes.threeSame(fp, AdvSimdThreeSameOp.ADD, 2, 4,
                3 * Aarch64FpRegisters.WORDS_PER_REGISTER,
                1 * Aarch64FpRegisters.WORDS_PER_REGISTER,
                2 * Aarch64FpRegisters.WORDS_PER_REGISTER);
        assertEquals(0x0000_0011_0000_0022L, fp.low64(3));
        assertEquals(0x0000_0033_0000_0044L, fp.high64(3));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // MEIA PRECISÃO (`esz=1`, binary16) — B19.5.1. O núcleo ganha o ramo F16 de `fpThreeSame`/
    // `fpCombinePair`; nenhum decoder o alimenta ainda.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private static int half(float value) {
        return Float.floatToFloat16(value) & 0xFFFF;
    }

    private static float h2f(int bits) {
        return Float.float16ToFloat((short) bits);
    }

    private static boolean isNaN16(int bits) {
        return (bits & 0x7C00) == 0x7C00 && (bits & 0x03FF) != 0;
    }

    private static long packHalf(int h0, int h1, int h2, int h3) {
        return (h0 & 0xFFFFL) | (h1 & 0xFFFFL) << 16 | (h2 & 0xFFFFL) << 32 | (h3 & 0xFFFFL) << 48;
    }

    /// `fpThreeSame` F16 sobre uma única lane, no banco VFP de 32 bits (`D0`/`D1` = operandos,
    /// `D2` = destino, que também é o acumulador RMW).
    private static int threeSameHalf(AdvSimdFpThreeSameOp op, int aBits, int bBits, int dBits) {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, aBits & 0xFFFFL);
        vfp.setD(1, bBits & 0xFFFFL);
        vfp.setD(2, dBits & 0xFFFFL);
        AdvSimdLanes.fpThreeSame(vfp, op, 1, 1, 2, 0, 1);
        return (int) (vfp.d(2) & 0xFFFF);
    }

    @Test
    void fpThreeSameHalfPrecisionAddAndMulAcrossFourAndEightLanes() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, packHalf(half(1f), half(2f), half(3f), half(4f)));
        vfp.setD(1, packHalf(half(10f), half(20f), half(30f), half(40f)));
        AdvSimdLanes.fpThreeSame(vfp, AdvSimdFpThreeSameOp.ADD, 1, 4, 2, 0, 1);
        assertEquals(packHalf(half(11f), half(22f), half(33f), half(44f)), vfp.d(2));
        assertEquals(0L, vfp.d(3), "lane vizinha (VFP32 não zera bits altos)");

        // Q (8 lanes): palavras 4/5 × 6/7 → 12/13.
        vfp.setD(4, packHalf(half(1f), half(1f), half(1f), half(1f)));
        vfp.setD(5, packHalf(half(2f), half(2f), half(2f), half(2f)));
        vfp.setD(6, packHalf(half(0.5f), half(0.5f), half(0.5f), half(0.5f)));
        vfp.setD(7, packHalf(half(0.25f), half(0.25f), half(0.25f), half(0.25f)));
        AdvSimdLanes.fpThreeSame(vfp, AdvSimdFpThreeSameOp.MUL, 1, 8, 12, 4, 6);
        long halfPacked = packHalf(half(0.5f), half(0.5f), half(0.5f), half(0.5f));
        assertEquals(halfPacked, vfp.d(12));
        assertEquals(halfPacked, vfp.d(13));
    }

    @Test
    void fpThreeSameHalfPrecisionRoundsResultToNearestEven() {
        int oneH = half(1f);
        // 1.0h + 2^-11 (0x1000) = ponto médio exato entre 1.0h (par) e 1.0h+2^-10 (ímpar) →
        // round-to-nearest-EVEN → 1.0h.
        assertEquals(oneH, threeSameHalf(AdvSimdFpThreeSameOp.ADD, oneH, 0x1000, 0));
        // resultados que não cabem em F16 batem com o estreitamento direto do resultado `float`.
        int[][] cases = {{0x4200, 0x4900}, {0x3555, 0x3C01}, {0x2C01, 0x2E03}, {0x4048, 0x3A21}};
        for (int[] p : cases) {
            assertEquals(half(h2f(p[0]) + h2f(p[1])), threeSameHalf(AdvSimdFpThreeSameOp.ADD, p[0], p[1], 0));
            assertEquals(half(h2f(p[0]) * h2f(p[1])), threeSameHalf(AdvSimdFpThreeSameOp.MUL, p[0], p[1], 0));
            assertEquals(half(h2f(p[0]) / h2f(p[1])), threeSameHalf(AdvSimdFpThreeSameOp.DIV, p[0], p[1], 0));
        }
    }

    @Test
    void fpThreeSameHalfPrecisionSubnormalsOverflowAndUnderflow() {
        int minSub = 0x0001;   // 2^-24
        assertEquals(0x0002, threeSameHalf(AdvSimdFpThreeSameOp.ADD, minSub, minSub, 0));
        assertEquals(2f * 0x1p-24f, h2f(threeSameHalf(AdvSimdFpThreeSameOp.ADD, minSub, minSub, 0)));
        // overflow → +Inf
        assertEquals(0x7C00, threeSameHalf(AdvSimdFpThreeSameOp.ADD, 0x7BFF, 0x7BFF, 0));
        // underflow → +0
        assertEquals(0x0000, threeSameHalf(AdvSimdFpThreeSameOp.MUL, minSub, minSub, 0));
    }

    @Test
    void fpThreeSameHalfPrecisionNaNPropagation() {
        int nan = 0x7E00;
        int one = half(1f);
        assertTrue(isNaN16(threeSameHalf(AdvSimdFpThreeSameOp.ADD, nan, one, 0)));
        assertTrue(isNaN16(threeSameHalf(AdvSimdFpThreeSameOp.MUL, nan, one, 0)));
        assertTrue(isNaN16(threeSameHalf(AdvSimdFpThreeSameOp.MAX, nan, one, 0)));
        assertTrue(isNaN16(threeSameHalf(AdvSimdFpThreeSameOp.MIN, one, nan, 0)));
        // MAXNM/MINNM: só um NaN → devolve o outro; os dois → NaN.
        assertEquals(one, threeSameHalf(AdvSimdFpThreeSameOp.MAXNM, nan, one, 0));
        assertEquals(one, threeSameHalf(AdvSimdFpThreeSameOp.MINNM, one, nan, 0));
        assertTrue(isNaN16(threeSameHalf(AdvSimdFpThreeSameOp.MAXNM, nan, nan, 0)));
    }

    @Test
    void fpThreeSameHalfPrecisionSignedZero() {
        assertEquals(0x0000, threeSameHalf(AdvSimdFpThreeSameOp.MAX, 0x8000, 0x0000, 0));
        assertEquals(0x8000, threeSameHalf(AdvSimdFpThreeSameOp.MIN, 0x8000, 0x0000, 0));
    }

    @Test
    void fpThreeSameHalfPrecisionComparisonsYieldSixteenBitMask() {
        int one = half(1f);
        int two = half(2f);
        assertEquals(0xFFFF, threeSameHalf(AdvSimdFpThreeSameOp.CMEQ, one, one, 0));
        assertEquals(0x0000, threeSameHalf(AdvSimdFpThreeSameOp.CMEQ, one, two, 0));
        assertEquals(0xFFFF, threeSameHalf(AdvSimdFpThreeSameOp.CMGT, two, one, 0));
        assertEquals(0xFFFF, threeSameHalf(AdvSimdFpThreeSameOp.CMGE, one, one, 0));
        assertEquals(0x0000, threeSameHalf(AdvSimdFpThreeSameOp.CMGT, 0x7E00, one, 0), "NaN sempre falso");
        assertEquals(0xFFFF, threeSameHalf(AdvSimdFpThreeSameOp.FACGE, half(-2f), one, 0), "|-2| >= |1|");
    }

    /// A distinção fundido × NÃO fundido para F16: `f16 × f16` é EXATO em binary32, então o
    /// não-fundido só difere do fundido se o produto for estreitado a binary16 antes do acumulador
    /// (dois arredondamentos F16). Sem isso, `MLA`/`MLS` colapsariam no valor fundido.
    @Test
    void fpThreeSameHalfPrecisionMlaIsNotFusedUnlikeFmla() {
        // a = 1 + 2^-9 + 2^-10 (0x3C03), b = 1 + 2^-10 (0x3C01).
        // produto exato   = 1 + 2^-8 + 2^-19 + 2^-20;  estreitado a F16 = 1 + 2^-8 (0x3C04).
        int a = 0x3C03;
        int b = 0x3C01;
        // acumulador -1.0h: não-fundido = round16(2^-8) = 0x1C00;
        //                   fundido     = round16(2^-8 + 0,75 ULP) = 0x1C01.
        assertEquals(0x1C00, threeSameHalf(AdvSimdFpThreeSameOp.MLA, a, b, 0xBC00));
        assertEquals(0x1C01, threeSameHalf(AdvSimdFpThreeSameOp.FMLA, a, b, 0xBC00));
        // MLS é o espelho (acumulador +1.0h, subtrai o produto).
        assertEquals(0x9C00, threeSameHalf(AdvSimdFpThreeSameOp.MLS, a, b, 0x3C00));
        assertEquals(0x9C01, threeSameHalf(AdvSimdFpThreeSameOp.FMLS, a, b, 0x3C00));

        // não é caso isolado: a vizinhança de 1.0h tem centenas de divergências.
        int divergences = 0;
        for (int x = 0x3C00; x <= 0x3D00; x++) {
            for (int y = 0x3C00; y <= 0x3D00; y++) {
                if (threeSameHalf(AdvSimdFpThreeSameOp.MLA, x, y, 0xBC00)
                        != threeSameHalf(AdvSimdFpThreeSameOp.FMLA, x, y, 0xBC00)) {
                    divergences++;
                }
            }
        }
        assertTrue(divergences > 0, "MLA (não fundido) deveria divergir de FMLA em ao menos um caso");
    }

    /// Teste diferencial que sustenta a decisão de projeto "calcular em `float` e estreitar uma
    /// vez" para `+`/`-`/`*`/`/` e para o `FMA` fundido: para TODOS os 65536 valores binary16 de
    /// `a` × uma amostra estriada de `b` (passo 1151) + valores especiais (≈ 4,9M pares por
    /// operação), o resultado do núcleo bate BIT A BIT com uma rota independente que calcula em
    /// `double` (53 bits ≫ 2·11+2) e estreita via `Float.floatToFloat16`.
    @Test
    void fpThreeSameHalfPrecisionDoubleRoundingIsHarmless() {
        int[] bSamples = strideSamplesWithSpecials(1151);
        int[] cSamples = {0x3C00, 0xBC00};
        long pairs = 0;
        for (int a = 0; a <= 0xFFFF; a++) {
            float fa = h2f(a);
            double da = fa;
            for (int b : bSamples) {
                float fb = h2f(b);
                double db = fb;
                assertHalfEq(Float.floatToFloat16((float) (da + db)), AdvSimdLanes.halfBits(fa + fb), "ADD", a, b);
                assertHalfEq(Float.floatToFloat16((float) (da - db)), AdvSimdLanes.halfBits(fa - fb), "SUB", a, b);
                assertHalfEq(Float.floatToFloat16((float) (da * db)), AdvSimdLanes.halfBits(fa * fb), "MUL", a, b);
                assertHalfEq(Float.floatToFloat16((float) (da / db)), AdvSimdLanes.halfBits(fa / fb), "DIV", a, b);
                for (int c : cSamples) {
                    float fc = h2f(c);
                    double dc = fc;
                    assertHalfEq(Float.floatToFloat16((float) Math.fma(da, db, dc)),
                            AdvSimdLanes.halfBits(Math.fma(fa, fb, fc)), "FMLA", a, b);
                    assertHalfEq(Float.floatToFloat16((float) Math.fma(-da, db, dc)),
                            AdvSimdLanes.halfBits(Math.fma(-fa, fb, fc)), "FMLS", a, b);
                }
                pairs++;
            }
        }
        assertEquals((long) 0x10000 * bSamples.length, pairs);
    }

    private static void assertHalfEq(int expected, long actual, String op, int a, int b) {
        int exp = expected & 0xFFFF;
        int act = (int) (actual & 0xFFFF);
        if (isNaN16(exp) && isNaN16(act)) {
            return;
        }
        assertEquals(exp, act,
                () -> op + " a=0x" + Integer.toHexString(a) + " b=0x" + Integer.toHexString(b));
    }

    private static int[] strideSamplesWithSpecials(int step) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int v = 0; v <= 0xFFFF; v += step) {
            s.add(v);
        }
        for (int sp : new int[]{0x0000, 0x8000, 0x0001, 0x8001, 0x03FF, 0x83FF, 0x0400, 0x8400,
                0x3C00, 0xBC00, 0x4000, 0xC000, 0x7BFF, 0xFBFF, 0x7C00, 0xFC00, 0x7E00, 0xFE00,
                0x3555, 0xB555}) {
            s.add(sp);
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void fpPairwiseHalfPrecisionCombinesAdjacentLanes() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, packHalf(half(1f), half(2f), half(3f), half(4f)));
        vfp.setD(1, packHalf(half(10f), half(20f), half(30f), half(40f)));
        AdvSimdLanes.fpPairwise(vfp, AdvSimdFpPairwiseOp.ADD, 1, 4, 2, 0, 1);
        assertEquals(packHalf(half(3f), half(7f), half(30f), half(70f)), vfp.d(2));

        vfp.setD(4, packHalf(half(1f), half(5f), 0x7E00, half(2f)));
        vfp.setD(5, packHalf(half(9f), half(9f), half(9f), half(9f)));
        AdvSimdLanes.fpPairwise(vfp, AdvSimdFpPairwiseOp.MAXNM, 1, 4, 6, 4, 5);
        assertEquals(half(5f), (int) (vfp.d(6) & 0xFFFF), "maxNum(1,5)");
        assertEquals(half(2f), (int) ((vfp.d(6) >>> 16) & 0xFFFF), "maxNum(NaN,2)");
    }

    @Test
    void fpThreeSameAndCombinePairRejectInvalidEsz() {
        VfpRegisters vfp = new VfpRegisters();
        assertThrows(IllegalArgumentException.class,
                () -> AdvSimdLanes.fpThreeSame(vfp, AdvSimdFpThreeSameOp.ADD, 0, 1, 2, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AdvSimdLanes.fpCombinePair(AdvSimdFpPairwiseOp.ADD, 0L, 0L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AdvSimdLanes.fpThreeSame(vfp, AdvSimdFpThreeSameOp.ADD, 4, 1, 2, 0, 1));
        // `esz=1` NÃO lança mais (é o caminho novo de B19.5.1).
        AdvSimdLanes.fpThreeSame(vfp, AdvSimdFpThreeSameOp.ADD, 1, 1, 2, 0, 1);
    }

    /// Prova de que o refactor do `if (esz == 2) … else …` para `switch (esz)` não mudou F32/F64.
    @Test
    void fpThreeSameSingleAndDoublePrecisionSurviveTheRefactor() {
        VfpRegisters vfp = new VfpRegisters();
        vfp.setD(0, (Float.floatToRawIntBits(3f) & 0xFFFFFFFFL) | ((long) Float.floatToRawIntBits(4f) << 32));
        vfp.setD(1, (Float.floatToRawIntBits(1.5f) & 0xFFFFFFFFL) | ((long) Float.floatToRawIntBits(0.5f) << 32));
        AdvSimdLanes.fpThreeSame(vfp, AdvSimdFpThreeSameOp.ADD, 2, 2, 2, 0, 1);
        assertEquals(4.5f, Float.intBitsToFloat((int) vfp.d(2)));
        assertEquals(4.5f, Float.intBitsToFloat((int) (vfp.d(2) >>> 32)));

        Aarch64FpRegisters fp = new Aarch64FpRegisters();
        fp.setQ(0, Double.doubleToRawLongBits(2.0), Double.doubleToRawLongBits(3.0));
        fp.setQ(1, Double.doubleToRawLongBits(0.25), Double.doubleToRawLongBits(0.5));
        AdvSimdLanes.fpThreeSame(fp, AdvSimdFpThreeSameOp.MUL, 3, 2,
                2 * Aarch64FpRegisters.WORDS_PER_REGISTER, 0, Aarch64FpRegisters.WORDS_PER_REGISTER);
        assertEquals(0.5, Double.longBitsToDouble(fp.low64(2)));
        assertEquals(1.5, Double.longBitsToDouble(fp.high64(2)));
    }
}
