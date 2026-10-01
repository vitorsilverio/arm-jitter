package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.27 — SVE BFloat16 (`FEAT_SVE_B16B16`): `esz = 0` nas linhas de FP de B17.13/B17.14. Palavras de arquitetura
/// (não sintéticas) conferidas contra `aarch64-none-elf-as`/`objdump` reais (devkitA64, binutils 2.46,
/// `-march=armv9.4-a+sve2+sve-b16b16+faminmax`); as não cobertas por uma linha de assembly real foram varridas
/// EXAUSTIVAMENTE por opcode via `.inst` + `objdump` para separar "existe"/"undefined" — ver `## Resultado` da task.
///
/// **Achado que corrige a spec da task e a leitura ingênua do QEMU**: só existem `ADD`/`SUB`/`MUL` não predicadas,
/// `ADD`/`SUB`/`MUL`/`MAXNM`/`MINNM`/`MAX`/`MIN`/`SCALE` predicadas (`FSUBR` reversa é `UNDEFINED`, apesar de
/// decodificar para o MESMO `trans_FSUB_zpzz` da forma direta) e só `FMLA`/`FMLS` predicados + `FMLA`/`FMLS`/`FMUL`
/// indexados do multiply-add (`FNMLA`/`FNMLS` e as 4 formas `FMAD`/`FMSB`/`FNMAD`/`FNMSB` são `UNDEFINED` mesmo o
/// QEMU anotando helper `_b16` para elas — o assembler/desmontador real é o oráculo mais forte).
///
/// O oráculo NÃO reusa `SveFloat`: computa em `double` (`53 ≥ 2×8+2`, a mesma folga de dupla-arredondamento que a
/// B17.13 já usa para meia precisão, só que contra os 8 bits de significando do BFloat16) e arredonda ao formato
/// truncando/arredondando os 16 bits baixos de um `float` (par-mais-próximo), que é exato porque `BFloat16` é
/// literalmente o prefixo de 16 bits de um `float`.
class Aarch64SveFpBFloat16Test {
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P3 = 3;
    private static final long VBAR = 0x400L;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    /// `esz = 1` (meia): a LARGURA DE ARMAZENAMENTO do BFloat16 (16 bits), nunca `0` — ver javadoc da classe.
    private static final int STORAGE_ESZ = 1;

    private static final Aarch64Architecture BASE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture B16B16 = Aarch64Architecture.extending(BASE, "teste-SVE-B16B16",
            Aarch64Feature.SVE_B16B16);

    // ── Palavras reais, medidas contra `aarch64-none-elf-as`/`objdump` (ver javadoc da classe) ─────
    // `bfadd z1.h, z2.h, z3.h` / `bfsub` / `bfmul`
    private static final int WORD_BFADD_UNPRED = 0x65030041;
    private static final int WORD_BFSUB_UNPRED = 0x65030441;
    private static final int WORD_BFMUL_UNPRED = 0x65030841;
    // `bfadd z1.h, p3/m, z1.h, z2.h` / ...
    private static final int WORD_BFADD_PRED = 0x65008c41;
    private static final int WORD_BFSUB_PRED = 0x65018c41;
    private static final int WORD_BFMUL_PRED = 0x65028c41;
    private static final int WORD_BFSUBR_PRED_UNDEFINED = 0x65038c41; // `.inst`: UNDEFINED
    private static final int WORD_BFMAXNM_PRED = 0x65048c41;
    private static final int WORD_BFMINNM_PRED = 0x65058c41;
    private static final int WORD_BFMAX_PRED = 0x65068c41;
    private static final int WORD_BFMIN_PRED = 0x65078c41;
    private static final int WORD_BFABD_PRED_UNDEFINED = 0x65088c41;
    private static final int WORD_BFSCALE_PRED = 0x65098c41; // `bfscale`: EXISTE (QEMU não anota `_b16`, achado)
    // `bfmla z1.h, p3/m, z2.h, z3.h` / `bfmls`
    private static final int WORD_BFMLA_PRED = 0x65230c41;
    private static final int WORD_BFMLS_PRED = 0x65232c41;
    private static final int WORD_BFNMLA_PRED_UNDEFINED = 0x65234c41;
    private static final int WORD_BFNMLS_PRED_UNDEFINED = 0x65236c41;
    private static final int WORD_BFMAD_PRED_UNDEFINED = 0x65238c41;
    private static final int WORD_BFMSB_PRED_UNDEFINED = 0x6523ac41;
    private static final int WORD_BFNMAD_PRED_UNDEFINED = 0x6523cc41;
    private static final int WORD_BFNMSB_PRED_UNDEFINED = 0x6523ec41;
    // `bfmla z1.h, z2.h, z3.h[2]` / `bfmls` / `bfmul`
    private static final int WORD_BFMLA_INDEXED = 0x64330841;
    private static final int WORD_BFMLS_INDEXED = 0x64330c41;
    private static final int WORD_BFMUL_INDEXED = 0x64332841;

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    // ── Decode: feature e lista exata de opcodes aceitos ────────────────────────────────────────────

    @Test
    void arithmeticWordsRequireTheFeature() {
        int[] accepted = {WORD_BFADD_UNPRED, WORD_BFSUB_UNPRED, WORD_BFMUL_UNPRED, WORD_BFADD_PRED, WORD_BFSUB_PRED,
            WORD_BFMUL_PRED, WORD_BFMAXNM_PRED, WORD_BFMINNM_PRED, WORD_BFMAX_PRED, WORD_BFMIN_PRED,
            WORD_BFSCALE_PRED};
        for (int word : accepted) {
            assertFalse(decodeOrNull(BASE, word) instanceof Ir64Op.SveFpArithmetic,
                    "sem FEAT_SVE_B16B16: " + Integer.toHexString(word));
            assertTrue(decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpArithmetic,
                    "com FEAT_SVE_B16B16: " + Integer.toHexString(word));
        }
    }

    @Test
    void onlyTheRealOpcodesExistEvenWithTheFeature() {
        // `FSUBR`/`FABD` em BFloat16: UNDEFINED mesmo com a feature (achado, ver javadoc da classe).
        int[] undefinedEvenWithFeature = {WORD_BFSUBR_PRED_UNDEFINED, WORD_BFABD_PRED_UNDEFINED};
        for (int word : undefinedEvenWithFeature) {
            assertFalse(decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpArithmetic, Integer.toHexString(word));
        }
    }

    @Test
    void multiplyAddWordsRequireTheFeatureAndOnlyFmlaFmlsExist() {
        int[] accepted = {WORD_BFMLA_PRED, WORD_BFMLS_PRED, WORD_BFMLA_INDEXED, WORD_BFMLS_INDEXED,
            WORD_BFMUL_INDEXED};
        for (int word : accepted) {
            assertFalse(decodeOrNull(BASE, word) instanceof Ir64Op.SveFpMultiplyAdd,
                    "sem FEAT_SVE_B16B16: " + Integer.toHexString(word));
            assertTrue(decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpMultiplyAdd,
                    "com FEAT_SVE_B16B16: " + Integer.toHexString(word));
        }
        // `FNMLA`/`FNMLS`/`FMAD`/`FMSB`/`FNMAD`/`FNMSB`: UNDEFINED mesmo com a feature (achado, ver javadoc).
        int[] undefinedEvenWithFeature = {WORD_BFNMLA_PRED_UNDEFINED, WORD_BFNMLS_PRED_UNDEFINED,
            WORD_BFMAD_PRED_UNDEFINED, WORD_BFMSB_PRED_UNDEFINED, WORD_BFNMAD_PRED_UNDEFINED,
            WORD_BFNMSB_PRED_UNDEFINED};
        for (int word : undefinedEvenWithFeature) {
            assertFalse(decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpMultiplyAdd, Integer.toHexString(word));
        }
    }

    @Test
    void decodedFieldsMatchTheRealWords() {
        var arithmetic = (Ir64Op.SveFpArithmetic) decodeOrNull(B16B16, WORD_BFADD_PRED);
        assertEquals(0, arithmetic.esz());
        assertEquals(Ir64Op.SveFpArithmetic.Op.ADD, arithmetic.op());
        assertEquals(Z1, arithmetic.rd());
        assertTrue(arithmetic.predicated());
        assertEquals(P3, arithmetic.pg());

        var mulAdd = (Ir64Op.SveFpMultiplyAdd) decodeOrNull(B16B16, WORD_BFMLA_PRED);
        assertEquals(0, mulAdd.esz());
        assertEquals(Ir64Op.SveFpMultiplyAdd.Op.FMLA, mulAdd.op());
        assertEquals(Z1, mulAdd.rd());
        assertEquals(Z2, mulAdd.rn());
        assertEquals(Z3, mulAdd.rm());
        assertEquals(P3, mulAdd.pg());
    }

    // ── Execução: valores BFloat16 ───────────────────────────────────────────────────────────────

    private static int elementCount(Aarch64Core core) {
        return core.vectorLengthBytes() >> STORAGE_ESZ;
    }

    private static long[] elements(Aarch64Core core, int reg) {
        long[] out = new long[elementCount(core)];
        for (int i = 0; i < out.length; i++) {
            int bitOffset = i * 16;
            out[i] = (core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64)) & 0xFFFFL;
        }
        return out;
    }

    private static void setElements(Aarch64Core core, int reg, long[] values) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
        for (int i = 0; i < values.length; i++) {
            int bitOffset = i * 16;
            long word = core.scalable().zWord(reg, bitOffset / 64);
            core.scalable().setZWord(reg, bitOffset / 64, word | ((values[i] & 0xFFFFL) << (bitOffset % 64)));
        }
    }

    private static void allActive(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P3, w, -1L);
        }
    }

    /// Liga, para cada elemento, o bit do byte mais baixo em `P3` (e lixo aleatório nos outros 15 bits do elemento).
    private static boolean[] randomPredicate(Aarch64Core core, Random random) {
        boolean[] active = new boolean[elementCount(core)];
        for (int e = 0; e < active.length; e++) {
            active[e] = random.nextBoolean();
            for (int b = 0; b < 2; b++) {
                int index = (e << STORAGE_ESZ) + b;
                if ((b == 0 && active[e]) || (b != 0 && random.nextBoolean())) {
                    core.scalable().setPWord(P3, index / 64, core.scalable().pWord(P3, index / 64) | (1L << (index % 64)));
                }
            }
        }
        return active;
    }

    /// `bfloat16 → double`: `bfloat16` é o prefixo de 16 bits de um `float` (expoente de 8 bits, como `float`).
    private static double toDouble(long bits) {
        return Float.intBitsToFloat((int) (bits << 16));
    }

    /// `double → bfloat16`, par-mais-próximo, NUM SÓ PASSO (direto dos 53 bits do `double`, sem passar por
    /// `float`): o `double` já é o valor correto (ele mesmo o único arredondamento do oráculo até aqui, por
    /// exemplo `Math.fma`), e `53 ≥ 2×8+2` garante que arredondar direto para `BFloat16` é seguro. Passar por
    /// `float` no meio criaria uma TERCEIRA rodada de arredondamento (achado desta task: um `double→float→bf16`
    /// aparentemente inofensivo — `24 ≥ 2×8+2` também — divergiu da conta exata num caso real, porque a cadeia de
    /// 3 arredondamentos não é coberta pelo lema de "arredondamento duplo seguro", só o par imediato é).
    private static long fromDouble(double v) {
        if (Double.isNaN(v)) {
            return 0x7FC0L; // NaN canônico quieto (QEMU/ARM): bit alto da fração ligado, resto zero.
        }
        boolean sign = Double.doubleToRawLongBits(v) < 0;
        double absV = Math.abs(v);
        if (absV == 0.0) {
            return sign ? 0x8000L : 0L;
        }
        if (Double.isInfinite(absV)) {
            return (sign ? 0x8000L : 0L) | 0x7F80L;
        }
        long bits = Double.doubleToRawLongBits(absV);
        int exponent = (int) (bits >>> 52) - 1023; // double normal: expoente sem viés (entradas nunca são denormais)
        long full = (bits & 0xFFFFFFFFFFFFFL) | (1L << 52); // significando de 53 bits (bit implícito incluso)
        int bf16Exponent = exponent + 127;
        int shift = 45; // mantém os 8 bits mais altos dos 53 por padrão (bit implícito + 7 de fração)
        if (bf16Exponent <= 0) {
            shift += 1 - bf16Exponent; // resultado subnormal em BFloat16: desloca os bits extras do expoente negativo
            bf16Exponent = 0;
            if (shift >= Long.SIZE) {
                return sign ? 0x8000L : 0L; // underflow total
            }
        }
        long kept = full >>> shift;
        long remainder = full & ((1L << shift) - 1L);
        long half = 1L << (shift - 1);
        if (remainder > half || (remainder == half && (kept & 1L) != 0L)) {
            kept++;
        }
        if (bf16Exponent == 0 && (kept >>> 7) != 0L) {
            bf16Exponent = 1; // arredondar um subnormal para cima atingiu o menor normal
        } else if ((kept >>> 8) != 0L) {
            kept >>>= 1;
            bf16Exponent++;
        }
        if (bf16Exponent >= 0xFF) {
            return (sign ? 0x8000L : 0L) | 0x7F80L; // overflow para infinito
        }
        return (sign ? 0x8000L : 0L) | ((long) bf16Exponent << 7) | (kept & 0x7FL);
    }

    private static boolean isNaN(long bits) {
        return Double.isNaN(toDouble(bits));
    }

    private static void assertSameBf16(long expected, long actual, String label) {
        if (isNaN(expected)) {
            assertTrue(isNaN(actual), label + ": esperado NaN, veio " + Long.toHexString(actual));
        } else {
            assertEquals(expected, actual, label + " esperado=" + toDouble(expected) + " veio=" + toDouble(actual));
        }
    }

    private static final double[] SPECIALS = {0.0, -0.0, 1.0, -1.0, 2.0, 0.5, -0.5, 3.0, 1.5, 100.0, -7.25, 1e-3,
        3.3895314e38, 1.1754944e-38, 1e30, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};

    // ── Oráculo exato do multiply-add (`BigInteger`) ────────────────────────────────────────────
    //
    // `Math.fma(double,...)` NÃO basta aqui: o `double` intermediário (53 bits) é correto, mas um `FMA` em
    // `BFloat16` pode exigir ~100+ bits de precisão exata antes do arredondamento final de 8 bits quando o
    // produto e o addend têm expoentes MUITO distantes (achado desta task, pego ao vivo: um caso real divergiu
    // em 1 ULP porque o `double` caiu exatamente numa "meia" que a conta exata não tinha — arredondamento duplo
    // inseguro apesar de `53 ≥ 2×8+2`, porque a margem da fórmula não cobre a cadeia de cancelamento de um FMA
    // de grande amplitude). Por isso o oráculo do FMA refaz o arredondamento em `BigInteger` (precisão
    // arbitrária), independente de `SveFloat`.

    private record Unpacked(boolean sign, boolean zero, long mantissa, int exponent) {
    }

    private static Unpacked unpackBf16(long bits) {
        boolean sign = (bits & 0x8000L) != 0L;
        int biased = (int) ((bits >>> 7) & 0xFF);
        long frac = bits & 0x7FL;
        if (biased == 0) {
            return frac == 0 ? new Unpacked(sign, true, 0L, 0) : new Unpacked(sign, false, frac, 1 - 127 - 7);
        }
        return new Unpacked(sign, false, frac | 0x80L, biased - 127 - 7);
    }

    /// `c + a×b` arredondado par-mais-próximo, em `BigInteger` exato. Casos especiais (`NaN`/`∞`/zero) delegam
    /// para `double`, onde `Math.fma` É exato (sem a amplitude que exige precisão extra).
    private static long exactFusedMultiplyAdd(long cBits, long aBits, long bBits) {
        double cd = toDouble(cBits);
        double ad = toDouble(aBits);
        double bd = toDouble(bBits);
        if (Double.isNaN(cd) || Double.isNaN(ad) || Double.isNaN(bd) || Double.isInfinite(ad) || Double.isInfinite(bd)
                || Double.isInfinite(cd) || ad == 0.0 || bd == 0.0 || cd == 0.0) {
            return fromDouble(Math.fma(ad, bd, cd));
        }
        Unpacked a = unpackBf16(aBits);
        Unpacked b = unpackBf16(bBits);
        Unpacked c = unpackBf16(cBits);
        boolean signProduct = a.sign() != b.sign();
        java.math.BigInteger product = java.math.BigInteger.valueOf(a.mantissa()).multiply(java.math.BigInteger.valueOf(b.mantissa()));
        int productExponent = a.exponent() + b.exponent();
        int exponent = Math.min(productExponent, c.exponent());
        java.math.BigInteger productSigned = (signProduct ? product.negate() : product)
                .shiftLeft(productExponent - exponent);
        java.math.BigInteger cSigned = (c.sign() ? java.math.BigInteger.valueOf(c.mantissa()).negate()
                : java.math.BigInteger.valueOf(c.mantissa())).shiftLeft(c.exponent() - exponent);
        java.math.BigInteger sum = productSigned.add(cSigned);
        if (sum.signum() == 0) {
            return 0L;
        }
        boolean sign = sum.signum() < 0;
        java.math.BigInteger magnitude = sum.abs();
        int precision = 8;
        int topExponent = exponent + magnitude.bitLength() - 1;
        int quantum = topExponent - precision + 1;
        int shift = quantum - exponent;
        java.math.BigInteger kept;
        int comparedToHalf;
        if (shift <= 0) {
            kept = magnitude.shiftLeft(-shift);
            comparedToHalf = -1;
        } else {
            kept = magnitude.shiftRight(shift);
            java.math.BigInteger remainder = magnitude.subtract(kept.shiftLeft(shift));
            comparedToHalf = remainder.compareTo(java.math.BigInteger.ONE.shiftLeft(shift - 1));
        }
        if (comparedToHalf > 0 || (comparedToHalf == 0 && kept.testBit(0))) {
            kept = kept.add(java.math.BigInteger.ONE);
        }
        if (kept.bitLength() > precision) {
            kept = kept.shiftRight(1);
            quantum++;
        }
        long significand = kept.longValue();
        int biased = quantum + 7 + 127;
        if (biased >= 0xFF) {
            return (sign ? 0x8000L : 0L) | 0x7F80L; // overflow: satura em infinito (modo nearest)
        }
        return (sign ? 0x8000L : 0L) | ((long) biased << 7) | (significand & 0x7FL);
    }

    private static long[] randomBf16(Aarch64Core core, Random random) {
        long[] out = new long[elementCount(core)];
        for (int i = 0; i < out.length; i++) {
            out[i] = random.nextInt(3) == 0 ? random.nextLong() & 0xFFFFL
                    : fromDouble(SPECIALS[random.nextInt(SPECIALS.length)]);
        }
        return out;
    }

    // ── Aritmética não predicada: `ADD`/`SUB`/`MUL` ──────────────────────────────────────────────

    static Stream<Integer> unpredicatedWords() {
        return Stream.of(WORD_BFADD_UNPRED, WORD_BFSUB_UNPRED, WORD_BFMUL_UNPRED);
    }

    @ParameterizedTest
    @MethodSource("unpredicatedWords")
    void unpredicatedOperationsMatchTheOracle(int word) {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(B16B16, vl);
            Random random = new Random(word * 31L + vl);
            long[] n = randomBf16(core, random);
            long[] m = randomBf16(core, random);
            setElements(core, Z2, n);
            setElements(core, Z3, m);
            core.memory().write32(0, word);
            core.setProgramCounter(0);
            new Ir64BlockExecutor(B16B16).step(core);
            long[] result = elements(core, Z1);
            for (int e = 0; e < result.length; e++) {
                double a = toDouble(n[e]);
                double b = toDouble(m[e]);
                double expected = word == WORD_BFADD_UNPRED ? a + b : word == WORD_BFSUB_UNPRED ? a - b : a * b;
                assertSameBf16(fromDouble(expected), result[e], "elemento " + e);
            }
        }
    }

    // ── Aritmética predicada: `ADD`/`SUB`/`MUL`/`MAXNM`/`MINNM`/`MAX`/`MIN`/`SCALE` ──────────────

    static Stream<Integer> predicatedWords() {
        return Stream.of(WORD_BFADD_PRED, WORD_BFSUB_PRED, WORD_BFMUL_PRED, WORD_BFMAXNM_PRED, WORD_BFMINNM_PRED,
                WORD_BFMAX_PRED, WORD_BFMIN_PRED);
    }

    private static double oracle(int word, double a, double b) {
        if (word == WORD_BFADD_PRED) {
            return a + b;
        }
        if (word == WORD_BFSUB_PRED) {
            return a - b;
        }
        if (word == WORD_BFMUL_PRED) {
            return a * b;
        }
        if (word == WORD_BFMAX_PRED) {
            return Math.max(a, b);
        }
        if (word == WORD_BFMIN_PRED) {
            return Math.min(a, b);
        }
        boolean max = word == WORD_BFMAXNM_PRED;
        if (Double.isNaN(a)) {
            return b;
        }
        return Double.isNaN(b) ? a : (max ? Math.max(a, b) : Math.min(a, b));
    }

    @ParameterizedTest
    @MethodSource("predicatedWords")
    void predicatedOperationsMatchTheOracleAndPreserveInactiveElements(int word) {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(B16B16, vl);
            Random random = new Random(word * 131L + vl);
            long[] n = randomBf16(core, random);
            long[] m = randomBf16(core, random);
            setElements(core, Z1, n);
            setElements(core, Z2, m);
            boolean[] active = randomPredicate(core, random);
            core.memory().write32(0, word);
            core.setProgramCounter(0);
            new Ir64BlockExecutor(B16B16).step(core);
            long[] result = elements(core, Z1);
            for (int e = 0; e < result.length; e++) {
                if (!active[e]) {
                    assertEquals(n[e], result[e], "elemento inativo " + e + " deveria ficar como estava");
                    continue;
                }
                double expected = oracle(word, toDouble(n[e]), toDouble(m[e]));
                assertSameBf16(fromDouble(expected), result[e], "elemento " + e);
            }
        }
    }

    @Test
    void scaleMultipliesByAPowerOfTwo() {
        Aarch64Core core = core(B16B16, 256);
        setElements(core, Z1, new long[] {fromDouble(1.5)});
        setElements(core, Z2, new long[] {3L}); // expoente +3, como inteiro de 16 bits
        allActive(core);
        core.memory().write32(0, WORD_BFSCALE_PRED);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(B16B16).step(core);
        assertSameBf16(fromDouble(1.5 * 8.0), elements(core, Z1)[0], "fbscale 1.5 << 3");
    }

    // ── Multiply-add predicado: `FMLA`/`FMLS` ───────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("multiplyAddWords")
    void multiplyAddOperationsMatchTheFusedOracleAndPreserveInactiveElements(int[] wordAndSign) {
        int word = wordAndSign[0];
        boolean negateProduct = wordAndSign[1] != 0;
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(B16B16, vl);
            Random random = new Random(word * 257L + vl);
            long[] a = randomBf16(core, random);
            long[] n = randomBf16(core, random);
            long[] m = randomBf16(core, random);
            setElements(core, Z1, a);
            setElements(core, Z2, n);
            setElements(core, Z3, m);
            boolean[] active = randomPredicate(core, random);
            core.memory().write32(0, word);
            core.setProgramCounter(0);
            new Ir64BlockExecutor(B16B16).step(core);
            long[] result = elements(core, Z1);
            for (int e = 0; e < result.length; e++) {
                if (!active[e]) {
                    assertEquals(a[e], result[e], "elemento inativo " + e);
                    continue;
                }
                long signedN = negateProduct ? (n[e] ^ 0x8000L) : n[e];
                long expected = exactFusedMultiplyAdd(a[e], signedN, m[e]);
                assertSameBf16(expected, result[e], "elemento " + e);
            }
        }
    }

    static Stream<int[]> multiplyAddWords() {
        return Stream.of(new int[] {WORD_BFMLA_PRED, 0}, new int[] {WORD_BFMLS_PRED, 1});
    }

    // ── Multiply-add indexado: `FMLA`/`FMLS`/`FMUL` ─────────────────────────────────────────────

    @Test
    void indexedMultiplyAddReadsTheElementWithinTheSegment() {
        // `bfmla z1.h, z2.h, z3.h[2]`: segmento de 128 bits = 8 elementos de 16 bits; índice 2.
        Aarch64Core core = core(B16B16, 256);
        long[] a = {fromDouble(1.0), fromDouble(2.0), fromDouble(3.0), fromDouble(4.0),
            fromDouble(5.0), fromDouble(6.0), fromDouble(7.0), fromDouble(8.0),
            fromDouble(9.0), fromDouble(10.0), fromDouble(11.0), fromDouble(12.0),
            fromDouble(13.0), fromDouble(14.0), fromDouble(15.0), fromDouble(16.0)};
        long[] n = a.clone();
        long[] m = new long[16];
        m[2] = fromDouble(2.0);
        m[8 + 2] = fromDouble(10.0);
        setElements(core, Z1, a);
        setElements(core, Z2, n);
        setElements(core, Z3, m);
        core.memory().write32(0, WORD_BFMLA_INDEXED);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(B16B16).step(core);
        long[] result = elements(core, Z1);
        for (int e = 0; e < 16; e++) {
            int segmentBase = (e / 8) * 8;
            double expected = Math.fma(toDouble(n[e]), toDouble(m[segmentBase + 2]), toDouble(a[e]));
            assertSameBf16(fromDouble(expected), result[e], "elemento " + e);
        }
    }

    @Test
    void indexedMultiplyWithoutAddendMatchesTheOracle() {
        Aarch64Core core = core(B16B16, 256);
        long[] n = {fromDouble(3.0)};
        long[] m = new long[8];
        m[2] = fromDouble(4.0);
        setElements(core, Z2, n);
        setElements(core, Z3, m);
        core.memory().write32(0, WORD_BFMUL_INDEXED);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(B16B16).step(core);
        assertSameBf16(fromDouble(12.0), elements(core, Z1)[0], "fmul indexado 3×4");
    }

    // ── Contagem exata (achado da task) ─────────────────────────────────────────────────────────

    @Test
    void exactlyElevenArithmeticOpcodesAndTwoMultiplyAddFormsExist() {
        int accepted = 0;
        for (int opcode = 0; opcode < 16; opcode++) {
            int word = (WORD_BFADD_PRED & ~(0b1111 << 16)) | (opcode << 16);
            accepted += decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpArithmetic ? 1 : 0;
        }
        for (int opcode = 0; opcode < 8; opcode++) {
            int word = (WORD_BFADD_UNPRED & ~(0b111 << 10)) | (opcode << 10);
            accepted += decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpArithmetic ? 1 : 0;
        }
        // 8 predicadas (ADD/SUB/MUL/MAXNM/MINNM/MAX/MIN/SCALE) + 3 não predicadas (ADD/SUB/MUL) = 11.
        assertEquals(11, accepted);

        int mulAdd = IntStream.of(WORD_BFMLA_PRED, WORD_BFMLS_PRED, WORD_BFNMLA_PRED_UNDEFINED,
                WORD_BFNMLS_PRED_UNDEFINED, WORD_BFMAD_PRED_UNDEFINED, WORD_BFMSB_PRED_UNDEFINED,
                WORD_BFNMAD_PRED_UNDEFINED, WORD_BFNMSB_PRED_UNDEFINED)
                .map(word -> decodeOrNull(B16B16, word) instanceof Ir64Op.SveFpMultiplyAdd ? 1 : 0)
                .sum();
        assertEquals(2, mulAdd); // só FMLA/FMLS predicados das 8 formas do grupo (ver javadoc da classe)
    }
}
