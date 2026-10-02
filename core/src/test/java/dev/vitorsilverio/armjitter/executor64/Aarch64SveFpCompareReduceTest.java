package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.ir64.SveFpOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.15 — comparação de ponto flutuante SVE (que produz predicado) e reduções de ponto flutuante (rápida em árvore,
/// por quadword e acumulativa serial): as 24 linhas. Palavras conferidas contra `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1`).
///
/// O oráculo NÃO reusa `SveFloat`: a comparação usa os operadores nativos de `float`/`double` do JDK (`>=`, `==`, `!=`
/// com NaN têm a mesma semântica do manual) e a redução usa uma árvore recursiva escrita à parte sobre `float`/`double`
/// nativos (meia precisão por `float` com valores de faixa modesta, em que cada nó é exato e há um só arredondamento).
class Aarch64SveFpCompareReduceTest {
    private static final int Z3 = 3;
    private static final int Z4 = 4;
    private static final int Z1 = 1;
    private static final int P1 = 1;
    private static final int P2 = 2;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final long ESR_EC_UNKNOWN = 0L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final long GARBAGE = 0xA5A5_A5A5_A5A5_A5A5L;
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(
            Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2), "teste-SVE2p1", Aarch64Feature.SVE2_1);
    private static final int IOC = 1;

    // Comparação vetor×vetor: bits 15:13 e bit 4.
    private static final int C_GE = 0b010 << 1, C_GT = C_GE | 1, C_EQ = 0b011 << 1, C_NE = C_EQ | 1,
            C_UO = 0b110 << 1, C_ACGE = C_UO | 1, C_ACGT = 0b111 << 1 | 1;
    // Comparação com zero: bits 17:16 e bit 4.
    private static final int Z_GE = 0b00 << 1, Z_GT = Z_GE | 1, Z_LT = 0b01 << 1, Z_LE = Z_LT | 1, Z_EQ = 0b10 << 1,
            Z_NE = 0b11 << 1;
    // Reduções: bits 18:16.
    private static final int R_ADD = 0, R_MAXNM = 4, R_MINNM = 5, R_MAX = 6, R_MIN = 7;

    // ── Palavras ─────────────────────────────────────────────────────────────────────────────────

    /// `p1 = FCM<cc> p2/z, z3, z4`; `code` traz os bits 15:13 (acima do bit 0) e o bit 4 (bit 0).
    private static int compare(int esz, int code) {
        return 0x65000000 | esz << 22 | Z4 << 16 | (code >> 1) << 13 | P2 << 10 | Z3 << 5 | (code & 1) << 4 | P1;
    }

    /// `p1 = FCM<cc> p2/z, z3, #0.0`; `code` traz os bits 17:16 (acima do bit 0) e o bit 4 (bit 0).
    private static int compareZero(int esz, int code) {
        return 0x65102000 | esz << 22 | (code >> 1) << 16 | P2 << 10 | Z3 << 5 | (code & 1) << 4 | P1;
    }

    /// `V1 = F<op>V p2, z3`.
    private static int fast(int esz, int opcode) {
        return 0x65002000 | esz << 22 | opcode << 16 | P2 << 10 | Z3 << 5 | Z1;
    }

    /// `V1 = F<op>QV p2, z3`.
    private static int quad(int esz, int opcode) {
        return 0x6410A000 | esz << 22 | opcode << 16 | P2 << 10 | Z3 << 5 | Z1;
    }

    /// `FADDA V1, p2, V1, z3`.
    private static int fadda(int esz) {
        return 0x65182000 | esz << 22 | P2 << 10 | Z3 << 5 | Z1;
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int... words) {
        for (int i = 0; i < words.length; i++) {
            core.memory().write32(i * 4L, words[i]);
        }
        core.setProgramCounter(0);
        Ir64BlockExecutor executor = new Ir64BlockExecutor(architecture);
        for (int i = 0; i < words.length; i++) {
            executor.step(core);
        }
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    private static boolean decodes(Aarch64Architecture architecture, int word) {
        try {
            return decode(architecture, word) instanceof SveFpOp64.FpCompareReduce;
        } catch (UnsupportedOperationException refused) {
            return false;
        }
    }

    private static long fpsr(Aarch64Core core) {
        return core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR);
    }

    private static int bits(int esz) {
        return 8 << esz;
    }

    private static int elementCount(Aarch64Core core, int esz) {
        return core.vectorLengthBytes() >> esz;
    }

    private static long mask(int esz) {
        return esz == 3 ? -1L : (1L << bits(esz)) - 1L;
    }

    private static void setElements(Aarch64Core core, int reg, int esz, long[] values) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
        for (int i = 0; i < values.length; i++) {
            int bitOffset = i * bits(esz);
            long word = core.scalable().zWord(reg, bitOffset / 64);
            core.scalable().setZWord(reg, bitOffset / 64, word | ((values[i] & mask(esz)) << (bitOffset % 64)));
        }
    }

    private static long[] filled(Aarch64Core core, int esz, long value) {
        long[] out = new long[elementCount(core, esz)];
        Arrays.fill(out, value);
        return out;
    }

    private static void allActive(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
    }

    /// `P2` com o bit do byte mais baixo de cada elemento ativo e lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int esz, Random random) {
        boolean[] active = new boolean[elementCount(core, esz)];
        for (int e = 0; e < active.length; e++) {
            active[e] = random.nextBoolean();
            setPredicateElement(core, e, esz, active[e], random);
        }
        return active;
    }

    private static void setPredicateElement(Aarch64Core core, int e, int esz, boolean active, Random random) {
        for (int b = 0; b < (1 << esz); b++) {
            int index = (e << esz) + b;
            if ((b == 0 && active) || (b != 0 && random.nextBoolean())) {
                core.scalable().setPWord(P2, index / 64, core.scalable().pWord(P2, index / 64) | (1L << (index % 64)));
            }
        }
    }

    private static void clearPredicate(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, 0L);
        }
    }

    private static boolean resultBit(Aarch64Core core, int index) {
        return ((core.scalable().pWord(P1, index / 64) >>> (index % 64)) & 1L) != 0L;
    }

    private static void fillGarbage(Aarch64Core core, int reg) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, GARBAGE);
        }
    }

    private static long scalar(Aarch64Core core, int reg) {
        return core.scalable().zWord(reg, 0);
    }

    private static void assertUpperClear(Aarch64Core core, int reg, int fromWord, String message) {
        for (int w = fromWord; w < core.scalable().wordsPerVector(); w++) {
            assertEquals(0L, core.scalable().zWord(reg, w), message + ": palavra " + w);
        }
    }

    // ── Valores FP ───────────────────────────────────────────────────────────────────────────────

    private static long encode(double value, int esz) {
        return switch (esz) {
            case 1 -> Float.floatToFloat16((float) value) & 0xFFFFL;
            case 2 -> Float.floatToRawIntBits((float) value) & 0xFFFFFFFFL;
            default -> Double.doubleToRawLongBits(value);
        };
    }

    private static double decodeValue(long b, int esz) {
        return switch (esz) {
            case 1 -> Float.float16ToFloat((short) b);
            case 2 -> Float.intBitsToFloat((int) b);
            default -> Double.longBitsToDouble(b);
        };
    }

    private static long signalingNaN(int esz) {
        return switch (esz) {
            case 1 -> 0x7C01L;
            case 2 -> 0x7F800001L;
            default -> 0x7FF0000000000001L;
        };
    }

    private static long defaultNaN(int esz) {
        return switch (esz) {
            case 1 -> 0x7E00L;
            case 2 -> 0x7FC00000L;
            default -> 0x7FF8000000000000L;
        };
    }

    private static final double[] SPECIALS = {0.0, -0.0, 1.0, -1.0, 2.0, -3.0, 3.0, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.NaN};

    private static long special(Random random, int esz) {
        return encode(SPECIALS[random.nextInt(SPECIALS.length)], esz);
    }

    /// Valor aleatório de faixa modesta (`±[0.25, 4)`), onde toda soma de meia é exata em `float`.
    private static long modest(Random random, int esz) {
        double magnitude = 0.25 + random.nextDouble() * 3.75;
        return encode(random.nextBoolean() ? -magnitude : magnitude, esz);
    }

    private static void assertSameBits(int esz, long expected, long actual, String message) {
        if (Double.isNaN(decodeValue(expected, esz))) {
            assertTrue(Double.isNaN(decodeValue(actual, esz)), message + ": esperava NaN");
        } else {
            assertEquals(expected, actual, message);
        }
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> assembled() {
        return Stream.of(
                Arguments.of(0x65444861, SveFpOp64.FpCompareReduce.Op.FCMGE, 1, false),
                Arguments.of(0x65844871, SveFpOp64.FpCompareReduce.Op.FCMGT, 2, false),
                Arguments.of(0x65c46861, SveFpOp64.FpCompareReduce.Op.FCMEQ, 3, false),
                Arguments.of(0x65de7fff, SveFpOp64.FpCompareReduce.Op.FCMNE, 3, false),
                Arguments.of(0x6584c861, SveFpOp64.FpCompareReduce.Op.FCMUO, 2, false),
                Arguments.of(0x6584c871, SveFpOp64.FpCompareReduce.Op.FACGE, 2, false),
                Arguments.of(0x6584e871, SveFpOp64.FpCompareReduce.Op.FACGT, 2, false),
                Arguments.of(0x65902861, SveFpOp64.FpCompareReduce.Op.FCMGE, 2, true),
                Arguments.of(0x65902871, SveFpOp64.FpCompareReduce.Op.FCMGT, 2, true),
                Arguments.of(0x65912861, SveFpOp64.FpCompareReduce.Op.FCMLT, 2, true),
                Arguments.of(0x65912871, SveFpOp64.FpCompareReduce.Op.FCMLE, 2, true),
                Arguments.of(0x65922861, SveFpOp64.FpCompareReduce.Op.FCMEQ, 2, true),
                Arguments.of(0x65533fe1, SveFpOp64.FpCompareReduce.Op.FCMNE, 1, true),
                Arguments.of(0x65402861, SveFpOp64.FpCompareReduce.Op.FADDV, 1, false),
                Arguments.of(0x65802861, SveFpOp64.FpCompareReduce.Op.FADDV, 2, false),
                Arguments.of(0x65c42861, SveFpOp64.FpCompareReduce.Op.FMAXNMV, 3, false),
                Arguments.of(0x65852861, SveFpOp64.FpCompareReduce.Op.FMINNMV, 2, false),
                Arguments.of(0x65862861, SveFpOp64.FpCompareReduce.Op.FMAXV, 2, false),
                Arguments.of(0x65872861, SveFpOp64.FpCompareReduce.Op.FMINV, 2, false),
                Arguments.of(0x6490a861, SveFpOp64.FpCompareReduce.Op.FADDQV, 2, false),
                Arguments.of(0x64d4a861, SveFpOp64.FpCompareReduce.Op.FMAXNMQV, 3, false),
                Arguments.of(0x6455a861, SveFpOp64.FpCompareReduce.Op.FMINNMQV, 1, false),
                Arguments.of(0x6496a861, SveFpOp64.FpCompareReduce.Op.FMAXQV, 2, false),
                Arguments.of(0x6497a861, SveFpOp64.FpCompareReduce.Op.FMINQV, 2, false),
                Arguments.of(0x65982861, SveFpOp64.FpCompareReduce.Op.FADDA, 2, false),
                Arguments.of(0x65d83fdf, SveFpOp64.FpCompareReduce.Op.FADDA, 3, false));
    }

    @ParameterizedTest
    @MethodSource("assembled")
    void everyAssembledWordDecodesToItsOperation(int word, SveFpOp64.FpCompareReduce.Op expected, int esz, boolean zero) {
        SveFpOp64.FpCompareReduce op = assertInstanceOf(SveFpOp64.FpCompareReduce.class, decode(SVE2P1, word));
        assertEquals(expected, op.op());
        assertEquals(esz, op.esz());
        assertEquals(zero, op.zero());
    }

    @Test
    void registerFieldsAreDecodedFromTheRightBits() {
        // fcmne p15.d, p7/z, z31.d, z30.d
        SveFpOp64.FpCompareReduce compare = assertInstanceOf(SveFpOp64.FpCompareReduce.class, decode(SVE, 0x65de7fff));
        assertEquals(15, compare.rd());
        assertEquals(7, compare.pg());
        assertEquals(31, compare.rn());
        assertEquals(30, compare.rm());
        // fadda d31, p7, d31, z30.d: Vdn nos bits 4:0, Zm nos bits 9:5
        SveFpOp64.FpCompareReduce serial = assertInstanceOf(SveFpOp64.FpCompareReduce.class, decode(SVE, 0x65d83fdf));
        assertEquals(31, serial.rd());
        assertEquals(31, serial.rn());
        assertEquals(30, serial.rm());
        assertEquals(7, serial.pg());
    }

    @ParameterizedTest
    @ValueSource(ints = {
        0x6580e861, // 111 com bit 4 = 0: não alocado
        0x6580a861, // bits 15:13 = 101 (esz = 2): não é comparação
        0x65a04861, // bit 21 = 1: é o multiply-add da B17.14, não este grupo
        0x65922871, // FCMEQ com zero com bit 4 = 1: não alocado
        0x65932871, // FCMNE com zero com bit 4 = 1: não alocado
        0x65812861, // FADDV opcode 001
        0x65832861, // FADDV opcode 011
        0x65002861, // FADDV esz = 0
        0x65182861, // FADDA esz = 0
    })
    void unallocatedEncodingsAreRefused(int word) {
        assertTrue(!decodes(SVE2P1, word), Integer.toHexString(word));
    }

    @Test
    void esz0IsRefusedInEveryGroup() {
        int[] words = {compare(0, C_GE), compare(0, C_UO), compareZero(0, Z_LT), fast(0, R_ADD), fast(0, R_MAX),
            quad(0, R_ADD), fadda(0)};
        for (int word : words) {
            assertTrue(!decodes(SVE2P1, word), Integer.toHexString(word));
        }
    }

    @Test
    void theQuadwordReductionsNeedSve2p1() {
        for (int opcode : new int[] {R_ADD, R_MAXNM, R_MINNM, R_MAX, R_MIN}) {
            assertTrue(!decodes(SVE, quad(2, opcode)), "sem SVE2p1");
            assertTrue(decodes(SVE2P1, quad(2, opcode)), "com SVE2p1");
        }
        Aarch64Architecture sve2p2 = Aarch64Architecture.extending(Aarch64Architecture.extending(SVE, "t2", Aarch64Feature.SVE2),
                "t2p2", Aarch64Feature.SVE2_2);
        assertTrue(decodes(sve2p2, quad(2, R_ADD)), "SVE2p2 implica SVE2p1");
        assertTrue(!decodes(SVE2P1, 0x6410A000 | 2 << 22 | 1 << 16), "opcode 001 não alocado");
    }

    @Test
    void executingWithoutSveRefusesTheWholeSpace() {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_0_A, compare(2, C_GE)));
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_0_A, fast(2, R_ADD)));
    }

    // ── Comparação vetor×vetor ───────────────────────────────────────────────────────────────────

    private static boolean expected(int code, boolean zero, double n, double m) {
        return switch (code) {
            case C_GE -> n >= m;
            case C_GT -> n > m;
            case C_EQ -> n == m;
            case C_NE -> n != m;
            case C_UO -> Double.isNaN(n) || Double.isNaN(m);
            case C_ACGE -> Math.abs(n) >= Math.abs(m);
            default -> Math.abs(n) > Math.abs(m); // C_ACGT
        };
    }

    static Stream<Arguments> vectorCompares() {
        return IntStream.of(C_GE, C_GT, C_EQ, C_NE, C_UO, C_ACGE, C_ACGT)
                .boxed().flatMap(code -> IntStream.rangeClosed(1, 3).mapToObj(esz -> Arguments.of(code, esz)));
    }

    @ParameterizedTest
    @MethodSource("vectorCompares")
    void vectorCompareMatchesNativeOperatorsForActiveElementsOnly(int code, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Random random = new Random(code * 31L + esz * 7L + vl);
            for (int round = 0; round < 40; round++) {
                Aarch64Core core = core(SVE, vl);
                int count = elementCount(core, esz);
                long[] n = new long[count];
                long[] m = new long[count];
                for (int e = 0; e < count; e++) {
                    n[e] = special(random, esz);
                    m[e] = special(random, esz);
                }
                setElements(core, Z3, esz, n);
                setElements(core, Z4, esz, m);
                boolean[] active = randomPredicate(core, esz, random);
                core.pstate().setNzcv(true, false, true, false);
                // P1 começa suja: o resultado ZERA os elementos inativos e os bytes altos de cada elemento.
                for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
                    core.scalable().setPWord(P1, w, -1L);
                }
                run(SVE, core, compare(esz, code));
                for (int e = 0; e < count; e++) {
                    boolean want = active[e] && expected(code, false, decodeValue(n[e], esz), decodeValue(m[e], esz));
                    assertEquals(want, resultBit(core, e << esz), "elemento " + e + " vl " + vl);
                    for (int b = 1; b < (1 << esz); b++) {
                        assertEquals(false, resultBit(core, (e << esz) + b), "byte alto do elemento " + e);
                    }
                }
                assertEquals(true, core.pstate().negative() && core.pstate().carry() && !core.pstate().zero() && !core.pstate().overflow(),
                        "a comparação FP NÃO altera NZCV");
            }
        }
    }

    @Test
    void fcmuoIsTheOnlyOrderedTestThatIsTrueForTwoNaNs() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long nan = defaultNaN(2);
        setElements(core, Z3, 2, filled(core, 2, nan));
        setElements(core, Z4, 2, filled(core, 2, nan));
        for (int code : new int[] {C_GE, C_GT, C_EQ, C_ACGE, C_ACGT}) {
            run(SVE, core, compare(2, code));
            assertEquals(false, resultBit(core, 0), "código " + code + ": NaN×NaN é falso");
        }
        run(SVE, core, compare(2, C_UO));
        assertEquals(true, resultBit(core, 0), "FCMUO: NaN×NaN");
        run(SVE, core, compare(2, C_NE));
        assertEquals(true, resultBit(core, 0), "FCMNE: não ordenado é diferente");
    }

    @Test
    void absoluteCompareUsesMagnitudes() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z3, 2, filled(core, 2, encode(-3.0, 2)));
        setElements(core, Z4, 2, filled(core, 2, encode(2.0, 2)));
        run(SVE, core, compare(2, C_GE));
        assertEquals(false, resultBit(core, 0), "-3.0 >= 2.0 é falso");
        run(SVE, core, compare(2, C_ACGE));
        assertEquals(true, resultBit(core, 0), "|-3.0| >= |2.0|");
        run(SVE, core, compare(2, C_ACGT));
        assertEquals(true, resultBit(core, 0), "|-3.0| > |2.0|");
        setElements(core, Z3, 2, filled(core, 2, encode(2.0, 2)));
        setElements(core, Z4, 2, filled(core, 2, encode(-3.0, 2)));
        run(SVE, core, compare(2, C_ACGE));
        assertEquals(false, resultBit(core, 0), "|2.0| >= |-3.0| é falso");
    }

    // ── Comparação com zero ──────────────────────────────────────────────────────────────────────

    private static boolean expectedZero(int code, double n) {
        return switch (code) {
            case Z_GE -> n >= 0.0;
            case Z_GT -> n > 0.0;
            case Z_LT -> n < 0.0;
            case Z_LE -> n <= 0.0;
            case Z_EQ -> n == 0.0;
            default -> n != 0.0; // Z_NE
        };
    }

    static Stream<Arguments> zeroCompares() {
        return IntStream.of(Z_GE, Z_GT, Z_LT, Z_LE, Z_EQ, Z_NE)
                .boxed().flatMap(code -> IntStream.rangeClosed(1, 3).mapToObj(esz -> Arguments.of(code, esz)));
    }

    @ParameterizedTest
    @MethodSource("zeroCompares")
    void compareWithZeroMatchesNativeOperators(int code, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Random random = new Random(code * 13L + esz + vl);
            for (int round = 0; round < 40; round++) {
                Aarch64Core core = core(SVE, vl);
                int count = elementCount(core, esz);
                long[] n = new long[count];
                for (int e = 0; e < count; e++) {
                    n[e] = special(random, esz);
                }
                setElements(core, Z3, esz, n);
                boolean[] active = randomPredicate(core, esz, random);
                run(SVE, core, compareZero(esz, code));
                for (int e = 0; e < count; e++) {
                    assertEquals(active[e] && expectedZero(code, decodeValue(n[e], esz)), resultBit(core, e << esz),
                            "elemento " + e + " valor " + decodeValue(n[e], esz));
                }
            }
        }
    }

    @Test
    void compareWithZeroTreatsBothZerosAsEqualAndLessThanIsAValueOrder() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z3, 3, filled(core, 3, encode(-0.0, 3)));
        run(SVE, core, compareZero(3, Z_EQ));
        assertEquals(true, resultBit(core, 0), "-0 == 0");
        run(SVE, core, compareZero(3, Z_LT));
        assertEquals(false, resultBit(core, 0), "-0 < 0 é falso");
        run(SVE, core, compareZero(3, Z_LE));
        assertEquals(true, resultBit(core, 0), "-0 <= 0");
    }

    // ── FPSR na comparação ───────────────────────────────────────────────────────────────────────

    @Test
    void signalingComparesRaiseInvalidForAnyNaNButQuietOnesOnlyForSignalingNaN() {
        for (int esz = 1; esz <= 3; esz++) {
            for (int code : new int[] {C_GE, C_GT, C_ACGE, C_ACGT}) {
                Aarch64Core core = core(SVE, 256);
                allActive(core);
                setElements(core, Z3, esz, filled(core, esz, defaultNaN(esz)));
                setElements(core, Z4, esz, filled(core, esz, encode(1.0, esz)));
                run(SVE, core, compare(esz, code));
                assertEquals(IOC, fpsr(core) & IOC, "sinalizante com qNaN: esz " + esz + " código " + code);
            }
            for (int code : new int[] {C_EQ, C_NE, C_UO}) {
                Aarch64Core core = core(SVE, 256);
                allActive(core);
                setElements(core, Z3, esz, filled(core, esz, defaultNaN(esz)));
                setElements(core, Z4, esz, filled(core, esz, encode(1.0, esz)));
                run(SVE, core, compare(esz, code));
                assertEquals(0, fpsr(core) & IOC, "quieta com qNaN: esz " + esz + " código " + code);
                setElements(core, Z3, esz, filled(core, esz, signalingNaN(esz)));
                run(SVE, core, compare(esz, code));
                assertEquals(IOC, fpsr(core) & IOC, "quieta com sNaN: esz " + esz + " código " + code);
            }
        }
    }

    @Test
    void compareWithZeroRaisesInvalidForQuietNaNOnlyInTheSignalingForms() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z3, 2, filled(core, 2, defaultNaN(2)));
        run(SVE, core, compareZero(2, Z_EQ));
        assertEquals(0, fpsr(core) & IOC, "FCMEQ com zero é quieta");
        run(SVE, core, compareZero(2, Z_LT));
        assertEquals(IOC, fpsr(core) & IOC, "FCMLT com zero é sinalizante");
    }

    @Test
    void inactiveElementsNeitherRaiseFlagsNorSetTheResult() {
        Aarch64Core core = core(SVE, 256);
        clearPredicate(core);
        setElements(core, Z3, 2, filled(core, 2, signalingNaN(2)));
        setElements(core, Z4, 2, filled(core, 2, signalingNaN(2)));
        for (int code : new int[] {C_GE, C_EQ, C_UO, C_NE}) {
            run(SVE, core, compare(2, code));
            for (int i = 0; i < 32; i++) {
                assertEquals(false, resultBit(core, i), "predicado vazio: sem resultado");
            }
        }
        assertEquals(0L, fpsr(core), "elemento inativo não suja o FPSR");
    }

    @Test
    void flushToZeroTurnsADenormalInputIntoZeroForTheComparison() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long denormal = 0x00000001L; // menor denormal de simples precisão
        setElements(core, Z3, 2, filled(core, 2, denormal));
        setElements(core, Z4, 2, filled(core, 2, 0L));
        run(SVE, core, compare(2, C_EQ));
        assertEquals(false, resultBit(core, 0), "sem FZ: denormal != 0");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR, 1L << 24);
        run(SVE, core, compare(2, C_EQ));
        assertEquals(true, resultBit(core, 0), "com FZ: denormal vira 0");
        assertEquals(1L << 7, fpsr(core) & (1L << 7), "IDC levantada");
    }

    // ── Reduções rápidas (árvore) ────────────────────────────────────────────────────────────────

    private static int powerOfTwoCeiling(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    private static double node(int opcode, int esz, double low, double high) {
        double result = switch (opcode) {
            case R_ADD -> low + high;
            case R_MAXNM -> Double.isNaN(low) ? high : Double.isNaN(high) ? low : Math.max(low, high);
            case R_MINNM -> Double.isNaN(low) ? high : Double.isNaN(high) ? low : Math.min(low, high);
            case R_MAX -> Math.max(low, high);
            default -> Math.min(low, high);
        };
        return esz == 3 ? result : decodeValue(encode(result, esz), esz);
    }

    private static double identity(int opcode) {
        return switch (opcode) {
            case R_ADD -> 0.0;
            case R_MAXNM, R_MINNM -> Double.NaN;
            case R_MAX -> Double.NEGATIVE_INFINITY;
            default -> Double.POSITIVE_INFINITY;
        };
    }

    /// Árvore recursiva escrita à parte (metade baixa, metade alta) sobre `double`, arredondando ao formato a cada nó.
    private static double referenceTree(int opcode, int esz, double[] data, int start, int count) {
        if (count == 1) {
            return data[start];
        }
        int half = count / 2;
        return node(opcode, esz, referenceTree(opcode, esz, data, start, half),
                referenceTree(opcode, esz, data, start + half, half));
    }

    private static double reference(int opcode, int esz, double[] values, boolean[] active) {
        double[] data = new double[powerOfTwoCeiling(values.length)];
        Arrays.fill(data, identity(opcode));
        for (int e = 0; e < values.length; e++) {
            if (active[e]) {
                data[e] = values[e];
            }
        }
        return referenceTree(opcode, esz, data, 0, data.length);
    }

    static Stream<Arguments> reductions() {
        return IntStream.of(R_ADD, R_MAXNM, R_MINNM, R_MAX, R_MIN)
                .boxed().flatMap(opcode -> IntStream.rangeClosed(1, 3).mapToObj(esz -> Arguments.of(opcode, esz)));
    }

    @ParameterizedTest
    @MethodSource("reductions")
    void fastReductionMatchesTheRecursiveTreeAndZeroesTheRestOfTheRegister(int opcode, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Random random = new Random(opcode * 101L + esz * 11L + vl);
            for (int round = 0; round < 30; round++) {
                Aarch64Core core = core(SVE, vl);
                int count = elementCount(core, esz);
                long[] raw = new long[count];
                double[] values = new double[count];
                for (int e = 0; e < count; e++) {
                    // Soma: faixa modesta (exata em meia). Máx/mín: inclui NaN e infinitos.
                    raw[e] = opcode == R_ADD ? modest(random, esz) : (random.nextInt(4) == 0 ? special(random, esz) : modest(random, esz));
                    values[e] = decodeValue(raw[e], esz);
                }
                setElements(core, Z3, esz, raw);
                boolean[] active = randomPredicate(core, esz, random);
                fillGarbage(core, Z1);
                run(SVE, core, fast(esz, opcode));
                double want = reference(opcode, esz, values, active);
                assertSameBits(esz, encode(want, esz), scalar(core, Z1) & mask(esz),
                        "opcode " + opcode + " esz " + esz + " vl " + vl);
                assertEquals(0L, scalar(core, Z1) & ~mask(esz), "os bits acima do elemento do V ficam zerados");
                assertUpperClear(core, Z1, 1, "o resto do Z");
            }
        }
    }

    @Test
    void fastAddIsATreeNotASerialSum() {
        // [1e30, 1, -1e30, 1] ativos e o resto inativo: árvore = (1e30 + 1) + (-1e30 + 1) = 0; serial daria 1.
        Aarch64Core core = core(SVE, 256);
        long[] v = new long[8];
        v[0] = encode(1e30, 2);
        v[1] = encode(1.0, 2);
        v[2] = encode(-1e30, 2);
        v[3] = encode(1.0, 2);
        setElements(core, Z3, 2, v);
        clearPredicate(core);
        Random random = new Random(1);
        for (int e = 0; e < 4; e++) {
            setPredicateElement(core, e, 2, true, random);
        }
        run(SVE, core, fast(2, R_ADD));
        assertEquals(encode(0.0, 2), scalar(core, Z1), "FADDV: árvore");
    }

    @Test
    void faddaAccumulatesSeriallyFromTheScalarAndDiffersFromFaddv() {
        Aarch64Core core = core(SVE, 256);
        long[] v = new long[8];
        v[0] = encode(1e30, 2);
        v[1] = encode(1.0, 2);
        v[2] = encode(-1e30, 2);
        v[3] = encode(1.0, 2);
        setElements(core, Z3, 2, v);
        clearPredicate(core);
        Random random = new Random(1);
        for (int e = 0; e < 4; e++) {
            setPredicateElement(core, e, 2, true, random);
        }
        fillGarbage(core, Z1);
        core.scalable().setZWord(Z1, 0, encode(0.0, 2)); // escalar inicial 0.0 (o resto do Z fica lixo)
        run(SVE, core, fadda(2));
        assertEquals(encode(1.0, 2), scalar(core, Z1), "FADDA: ((((0 + 1e30) + 1) + -1e30) + 1) = 1");
        assertUpperClear(core, Z1, 1, "o resto do Z");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void faddaMatchesASerialSumWithTheInitialScalarAndInactiveElementsSkipped(int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Random random = new Random(esz * 17L + vl);
            for (int round = 0; round < 30; round++) {
                Aarch64Core core = core(SVE, vl);
                int count = elementCount(core, esz);
                long[] raw = new long[count];
                for (int e = 0; e < count; e++) {
                    raw[e] = modest(random, esz);
                }
                setElements(core, Z3, esz, raw);
                boolean[] active = randomPredicate(core, esz, random);
                long initial = modest(random, esz);
                fillGarbage(core, Z1);
                core.scalable().setZWord(Z1, 0, initial);
                run(SVE, core, fadda(esz));
                double acc = decodeValue(initial, esz);
                for (int e = 0; e < count; e++) {
                    if (active[e]) {
                        acc = node(R_ADD, esz, acc, decodeValue(raw[e], esz));
                    }
                }
                assertEquals(encode(acc, esz), scalar(core, Z1) & mask(esz), "esz " + esz + " vl " + vl);
            }
        }
    }

    @Test
    void faddaWithAnEmptyPredicateReturnsTheInitialScalar() {
        Aarch64Core core = core(SVE, 256);
        clearPredicate(core);
        setElements(core, Z3, 3, filled(core, 3, encode(5.0, 3)));
        fillGarbage(core, Z1);
        core.scalable().setZWord(Z1, 0, encode(-2.5, 3));
        run(SVE, core, fadda(3));
        assertEquals(encode(-2.5, 3), scalar(core, Z1));
        assertUpperClear(core, Z1, 1, "o resto do Z");
    }

    @Test
    void emptyPredicateGivesTheNeutralValueOfEachReduction() {
        for (int esz = 1; esz <= 3; esz++) {
            long[] want = {encode(0.0, esz), defaultNaN(esz), defaultNaN(esz), encode(Double.NEGATIVE_INFINITY, esz),
                encode(Double.POSITIVE_INFINITY, esz)};
            int[] opcodes = {R_ADD, R_MAXNM, R_MINNM, R_MAX, R_MIN};
            for (int i = 0; i < opcodes.length; i++) {
                Aarch64Core core = core(SVE, 256);
                clearPredicate(core);
                setElements(core, Z3, esz, filled(core, esz, encode(7.0, esz)));
                run(SVE, core, fast(esz, opcodes[i]));
                assertEquals(want[i], scalar(core, Z1), "esz " + esz + " opcode " + opcodes[i]);
            }
        }
    }

    @Test
    void maxnmIgnoresAQuietNaNButMaxPropagatesIt() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long[] v = filled(core, 2, encode(1.0, 2));
        v[3] = defaultNaN(2);
        v[5] = encode(4.0, 2);
        setElements(core, Z3, 2, v);
        run(SVE, core, fast(2, R_MAXNM));
        assertEquals(encode(4.0, 2), scalar(core, Z1), "FMAXNMV ignora o NaN");
        run(SVE, core, fast(2, R_MINNM));
        assertEquals(encode(1.0, 2), scalar(core, Z1), "FMINNMV ignora o NaN");
        run(SVE, core, fast(2, R_MAX));
        assertTrue(Float.isNaN(Float.intBitsToFloat((int) scalar(core, Z1))), "FMAXV propaga o NaN");
        run(SVE, core, fast(2, R_MIN));
        assertTrue(Float.isNaN(Float.intBitsToFloat((int) scalar(core, Z1))), "FMINV propaga o NaN");
    }

    @Test
    void maxAndMinOrderSignedZeros() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long[] v = filled(core, 3, encode(-0.0, 3));
        v[1] = encode(0.0, 3);
        setElements(core, Z3, 3, v);
        run(SVE, core, fast(3, R_MAX));
        assertEquals(encode(0.0, 3), scalar(core, Z1), "max(+0, -0) = +0");
        run(SVE, core, fast(3, R_MIN));
        assertEquals(encode(-0.0, 3), scalar(core, Z1), "min(+0, -0) = -0");
    }

    @Test
    void reductionRaisesInvalidForASignalingNaNAndIgnoresInactiveOnes() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long[] v = filled(core, 2, encode(1.0, 2));
        v[2] = signalingNaN(2);
        setElements(core, Z3, 2, v);
        run(SVE, core, fast(2, R_ADD));
        assertEquals(IOC, fpsr(core) & IOC);
        Aarch64Core inactive = core(SVE, 256);
        clearPredicate(inactive);
        setElements(inactive, Z3, 2, filled(inactive, 2, signalingNaN(2)));
        run(SVE, inactive, fast(2, R_ADD));
        assertEquals(0L, fpsr(inactive), "sNaN inativo não sujou o FPSR");
    }

    @Test
    void atTheMinimumVectorLengthTheQuadwordReductionIsTheIdentityOnOneSegment() {
        Aarch64Core core = core(SVE2P1, 128);
        allActive(core);
        long[] v = {encode(1.0, 2), encode(2.0, 2), encode(3.0, 2), encode(4.0, 2)};
        setElements(core, Z3, 2, v);
        run(SVE2P1, core, quad(2, R_ADD));
        assertEquals(v[0] | v[1] << 32, core.scalable().zWord(Z1, 0), "um segmento: cada posição é ela mesma");
        assertEquals(v[2] | v[3] << 32, core.scalable().zWord(Z1, 1));
        run(SVE2P1, core, fast(2, R_ADD));
        assertEquals(encode(10.0, 2), scalar(core, Z1), "a escalar soma as 4 posições");
    }

    @Test
    void aVectorLengthThatIsNotAPowerOfTwoIsPaddedWithTheNeutralValue() {
        Aarch64Core core = core(SVE, 384);
        allActive(core);
        int count = elementCount(core, 2);
        assertEquals(12, count);
        long[] raw = new long[count];
        double[] values = new double[count];
        for (int e = 0; e < count; e++) {
            values[e] = e + 1;
            raw[e] = encode(values[e], 2);
        }
        setElements(core, Z3, 2, raw);
        boolean[] active = new boolean[count];
        Arrays.fill(active, true);
        run(SVE, core, fast(2, R_ADD));
        assertEquals(encode(reference(R_ADD, 2, values, active), 2), scalar(core, Z1));
        assertEquals(encode(78.0, 2), scalar(core, Z1), "1 + … + 12");
    }

    // ── Reduções por quadword ────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("reductions")
    void quadwordReductionReducesEachLanePositionAcrossAllSegments(int opcode, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Random random = new Random(opcode * 7L + esz * 3L + vl);
            for (int round = 0; round < 20; round++) {
                Aarch64Core core = core(SVE2P1, vl);
                int count = elementCount(core, esz);
                int perSegment = 16 >> esz;
                int segments = count / perSegment;
                long[] raw = new long[count];
                double[] values = new double[count];
                for (int e = 0; e < count; e++) {
                    raw[e] = opcode == R_ADD ? modest(random, esz) : (random.nextInt(4) == 0 ? special(random, esz) : modest(random, esz));
                    values[e] = decodeValue(raw[e], esz);
                }
                setElements(core, Z3, esz, raw);
                boolean[] active = randomPredicate(core, esz, random);
                fillGarbage(core, Z1);
                run(SVE2P1, core, quad(esz, opcode));
                for (int lane = 0; lane < perSegment; lane++) {
                    double[] laneValues = new double[segments];
                    boolean[] laneActive = new boolean[segments];
                    for (int s = 0; s < segments; s++) {
                        laneValues[s] = values[s * perSegment + lane];
                        laneActive[s] = active[s * perSegment + lane];
                    }
                    int bitOffset = lane * bits(esz);
                    long got = (core.scalable().zWord(Z1, bitOffset / 64) >>> (bitOffset % 64)) & mask(esz);
                    assertSameBits(esz, encode(reference(opcode, esz, laneValues, laneActive), esz), got,
                            "opcode " + opcode + " esz " + esz + " lane " + lane + " vl " + vl);
                }
                assertUpperClear(core, Z1, 2, "acima de 128 bits");
            }
        }
    }

    @Test
    void quadwordReductionDiffersFromTheScalarOneWhenThereIsMoreThanOneSegment() {
        Aarch64Core core = core(SVE2P1, 256);
        allActive(core);
        long[] v = new long[8];
        for (int e = 0; e < 8; e++) {
            v[e] = encode(e + 1, 2);
        }
        setElements(core, Z3, 2, v);
        run(SVE2P1, core, quad(2, R_ADD));
        // lane 0 = v[0] + v[4] = 1 + 5; lane 3 = v[3] + v[7] = 4 + 8
        assertEquals(encode(6.0, 2), core.scalable().zWord(Z1, 0) & 0xFFFFFFFFL);
        assertEquals(encode(12.0, 2), core.scalable().zWord(Z1, 1) >>> 32);
        run(SVE2P1, core, fast(2, R_ADD));
        assertEquals(encode(36.0, 2), scalar(core, Z1), "a escalar soma tudo");
    }

    @Test
    void aSegmentCountThatIsNotAPowerOfTwoIsPaddedForTheQuadwordReduction() {
        Aarch64Core core = core(SVE2P1, 384);
        allActive(core);
        long[] v = new long[12];
        for (int e = 0; e < 12; e++) {
            v[e] = encode(e + 1, 2);
        }
        setElements(core, Z3, 2, v);
        run(SVE2P1, core, quad(2, R_ADD));
        // 3 segmentos: lane 0 = 1 + 5 + 9
        assertEquals(encode(15.0, 2), core.scalable().zWord(Z1, 0) & 0xFFFFFFFFL);
    }

    // ── Acesso, streaming e blocos ───────────────────────────────────────────────────────────────

    private static final class Cpacr implements Aarch64SystemRegisterBus {
        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return 0L;
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long newValue) {
            throw new UnsupportedOperationException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x65844871, 0x65902861, 0x65802861, 0x6490a861, 0x65982861})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE2P1, 256);
        core.setSystemRegisterBus(new Cpacr());
        core.scalable().setPWord(P1, 0, 0x55L);
        run(SVE2P1, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x55L, core.scalable().pWord(P1, 0), "a instrução não executou");
    }

    private static Aarch64Core streamingCore(Aarch64Architecture architecture) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        return core;
    }

    @Test
    void compareAndTheTreeReductionsAreLegalInStreamingModeAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = streamingCore(architecture);
        allActive(core);
        setElements(core, Z3, 3, filled(core, 3, encode(2.0, 3)));
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, fast(3, R_ADD));
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção");
        assertEquals(encode(16.0, 3), scalar(core, Z1), "SVL = 512: 8 doublewords × 2.0");
        core.memory().write32(0x14, compareZero(3, Z_GT));
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x18L, core.pc(), "FCMGT com zero é legal em streaming");
        assertEquals(true, resultBit(core, 56), "o último elemento de 8 doublewords");
    }

    @Test
    void faddaIsUndefinedInStreamingModeWithoutFa64() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = streamingCore(architecture);
        core.memory().write32(0x10, fadda(2));
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=0: UNDEFINED");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z3, 2, filled(core, 2, encode(2.0, 2)));
        core.memory().write32(0, fast(2, R_ADD)); // 8 × 2.0 = 16.0
        core.memory().write32(4, compareZero(2, Z_GT));
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(encode(16.0, 2), scalar(core, Z1));
        assertEquals(true, resultBit(core, 0));
    }
}
