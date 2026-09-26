package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.13 — aritmética de ponto flutuante SVE (32 encodings). Palavras conferidas contra `aarch64-none-elf-as`
/// (devkitA64, `-march=armv9.4-a+sve2+faminmax`). Registradores: predicadas/imediato/`FTMAD` em `Zd=Zdn=z1` (`Zm=z3`,
/// `pg=p2`); não predicadas em `z1 = z2 op z3`.
///
/// O oráculo NÃO reusa `SveFloat`: nos modos "mais próximo" usa `double`/`float` nativos do JDK (a conversão
/// `double → float → half` é exata para `+ − × ÷` porque `53 ≥ 2·24+2` e `24 ≥ 2·11+2`) e, nos modos dirigidos,
/// compara o valor exato em `BigDecimal` com o vizinho de `nextUp`/`nextDown`. Toda semântica roda em `VL = 256`
/// **e** `VL = 512`, com predicado aleatório e lixo nos bytes não-baixos de cada elemento.
class Aarch64SveFpArithmeticTest {
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P2 = 2;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final long ESR_EC_UNKNOWN = 0L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture FAMINMAX = Aarch64Architecture.ARMV9_4_A;

    private static final long FPCR_RMODE_PLUS = 1L << 22;
    private static final long FPCR_RMODE_MINUS = 2L << 22;
    private static final long FPCR_RMODE_ZERO = 3L << 22;
    private static final long FPCR_FZ = 1L << 24;
    private static final long FPCR_DN = 1L << 25;
    private static final long FPCR_FZ16 = 1L << 19;
    private static final long[] RMODES = {0L, FPCR_RMODE_PLUS, FPCR_RMODE_MINUS, FPCR_RMODE_ZERO};

    private static final int IOC = 1;
    private static final int DZC = 2;
    private static final int OFC = 4;
    private static final int UFC = 8;
    private static final int IXC = 16;
    private static final int IDC = 128;

    // Opcodes das predicadas (bits 19:16) e das não predicadas (bits 12:10).
    private static final int P_ADD = 0, P_SUB = 1, P_MUL = 2, P_SUBR = 3, P_MAXNM = 4, P_MINNM = 5, P_MAX = 6, P_MIN = 7,
            P_ABD = 8, P_SCALE = 9, P_MULX = 10, P_DIVR = 12, P_DIV = 13, P_AMAX = 14, P_AMIN = 15;
    private static final int U_ADD = 0, U_SUB = 1, U_MUL = 2, U_TSMUL = 3, U_RECPS = 6, U_RSQRTS = 7;

    // ── Palavras ─────────────────────────────────────────────────────────────────────────────────

    private static int predicated(int esz, int opcode) {
        return 0x65008000 | esz << 22 | opcode << 16 | P2 << 10 | Z3 << 5 | Z1;
    }

    private static int unpredicated(int esz, int opcode) {
        return 0x65000000 | esz << 22 | Z3 << 16 | opcode << 10 | Z2 << 5 | Z1;
    }

    private static int immediate(int esz, int opcode, int imm) {
        return 0x65188000 | esz << 22 | opcode << 16 | P2 << 10 | imm << 5 | Z1;
    }

    private static int tmad(int esz, int imm) {
        return 0x65108000 | esz << 22 | imm << 16 | Z3 << 5 | Z1;
    }

    private static int unary(int esz, boolean rsqrte) {
        return 0x650E3000 | esz << 22 | (rsqrte ? 1 : 0) << 16 | Z3 << 5 | Z1;
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
            return decode(architecture, word) instanceof Ir64Op.SveFpArithmetic;
        } catch (UnsupportedOperationException refused) {
            return false;
        }
    }

    private static void setFpcr(Aarch64Core core, long value) {
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR, value);
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

    private static long[] elements(Aarch64Core core, int reg, int esz) {
        long[] out = new long[elementCount(core, esz)];
        for (int i = 0; i < out.length; i++) {
            int bitOffset = i * bits(esz);
            out[i] = (core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64)) & mask(esz);
        }
        return out;
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
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static void allActive(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
    }

    /// Liga em `P2` o bit do byte mais baixo de cada elemento ativo e lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int esz, Random random) {
        boolean[] active = new boolean[elementCount(core, esz)];
        for (int e = 0; e < active.length; e++) {
            active[e] = random.nextBoolean();
            for (int b = 0; b < (1 << esz); b++) {
                int index = (e << esz) + b;
                if ((b == 0 && active[e]) || (b != 0 && random.nextBoolean())) {
                    core.scalable().setPWord(P2, index / 64, core.scalable().pWord(P2, index / 64) | (1L << (index % 64)));
                }
            }
        }
        return active;
    }

    // ── Valores FP ───────────────────────────────────────────────────────────────────────────────

    private static double toDouble(long b, int esz) {
        return switch (esz) {
            case 1 -> Float.float16ToFloat((short) b);
            case 2 -> Float.intBitsToFloat((int) b);
            default -> Double.longBitsToDouble(b);
        };
    }

    private static long fromDouble(double v, int esz) {
        return switch (esz) {
            case 1 -> Float.floatToFloat16((float) v) & 0xFFFFL;
            case 2 -> Float.floatToRawIntBits((float) v) & 0xFFFFFFFFL;
            default -> Double.doubleToRawLongBits(v);
        };
    }

    private static boolean isNaN(long b, int esz) {
        return Double.isNaN(toDouble(b, esz));
    }

    private static boolean isSignaling(long b, int esz) {
        int fracBits = esz == 1 ? 10 : esz == 2 ? 23 : 52;
        return isNaN(b, esz) && ((b >>> (fracBits - 1)) & 1L) == 0L;
    }

    private static final double[] SPECIALS = {0.0, -0.0, 1.0, -1.0, 2.0, 0.5, -0.5, 3.0, 1.5, 100.0, -7.25, 1e-3, 65504.0,
        6.103515625e-5, 5.960464477539063e-8, 1e38, 1e-40, 1e-310, 1.0e300, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.NaN};

    /// Elementos com viés para os casos de borda (zeros, ∞, NaN, denormais, extremos) e bits crus aleatórios.
    private static long[] randomFp(Aarch64Core core, int esz, Random random) {
        long[] out = new long[elementCount(core, esz)];
        for (int i = 0; i < out.length; i++) {
            out[i] = random.nextInt(3) == 0 ? random.nextLong() & mask(esz)
                    : fromDouble(SPECIALS[random.nextInt(SPECIALS.length)], esz);
        }
        return out;
    }

    private static void assertSameFp(long expected, long actual, int esz, String label) {
        if (isNaN(expected, esz)) {
            assertTrue(isNaN(actual, esz), label + ": esperado NaN, veio " + Long.toHexString(actual));
        } else {
            assertEquals(expected, actual, label + " esperado=" + toDouble(expected, esz) + " veio=" + toDouble(actual, esz));
        }
    }

    // ── Oráculos (modo mais próximo) ─────────────────────────────────────────────────────────────

    private static double maxOracle(double a, double b, boolean max) {
        return max ? Math.max(a, b) : Math.min(a, b);
    }

    private static double numberOracle(double a, double b, boolean max) {
        if (Double.isNaN(a)) {
            return b;
        }
        return Double.isNaN(b) ? a : maxOracle(a, b, max);
    }

    /// `op(a, b)` em `double`; o chamador arredonda ao formato. `NaN` = qualquer NaN.
    private static double nearestOracle(int opcode, double a, double b, int esz) {
        return switch (opcode) {
            case P_ADD -> a + b;
            case P_SUB -> a - b;
            case P_MUL -> a * b;
            case P_DIV -> a / b;
            case P_MAX -> maxOracle(a, b, true);
            case P_MIN -> maxOracle(a, b, false);
            case P_MAXNM -> numberOracle(a, b, true);
            case P_MINNM -> numberOracle(a, b, false);
            case P_ABD -> Math.abs(a - b);
            case P_MULX -> (a == 0.0 && Double.isInfinite(b)) || (Double.isInfinite(a) && b == 0.0)
                    ? Math.copySign(2.0, (Double.doubleToRawLongBits(a) < 0) ^ (Double.doubleToRawLongBits(b) < 0) ? -1.0 : 1.0) : a * b;
            case P_AMAX, P_AMIN -> Double.isNaN(a) || Double.isNaN(b) ? Double.NaN
                    : Math.abs(a) > Math.abs(b) ? (opcode == P_AMAX ? a : b)
                    : Math.abs(b) > Math.abs(a) ? (opcode == P_AMAX ? b : a)
                    : maxOracle(a, b, opcode == P_AMAX);
            default -> throw new IllegalArgumentException("opcode " + opcode);
        };
    }

    // ── Decode ───────────────────────────────────────────────────────────────────────────────────

    private record Decoded(int word, Ir64Op.SveFpArithmetic.Op op, int esz, int rd, int rn, int rm, int pg,
            boolean predicated, boolean reversed, boolean immediateForm, int immediate) {
    }

    private static Decoded d(int word, Ir64Op.SveFpArithmetic.Op op, int esz, int rd, int rn, int rm, int pg,
            boolean predicated, boolean reversed, boolean immediateForm, int immediate) {
        return new Decoded(word, op, esz, rd, rn, rm, pg, predicated, reversed, immediateForm, immediate);
    }

    private static Stream<Decoded> decodeTable() {
        var A = Ir64Op.SveFpArithmetic.Op.ADD;
        var S = Ir64Op.SveFpArithmetic.Op.SUB;
        var M = Ir64Op.SveFpArithmetic.Op.MUL;
        var V = Ir64Op.SveFpArithmetic.Op.DIV;
        return Stream.of(
                d(0x65430041, A, 1, 1, 2, 3, 0, false, false, false, 0),       // fadd z1.h, z2.h, z3.h
                d(0x65c30041, A, 3, 1, 2, 3, 0, false, false, false, 0),       // fadd z1.d, z2.d, z3.d
                d(0x658604a4, S, 2, 4, 5, 6, 0, false, false, false, 0),       // fsub z4.s, z5.s, z6.s
                d(0x65c90907, M, 3, 7, 8, 9, 0, false, false, false, 0),       // fmul z7.d, z8.d, z9.d
                d(0x65830c41, Ir64Op.SveFpArithmetic.Op.TSMUL, 2, 1, 2, 3, 0, false, false, false, 0),
                d(0x65831841, Ir64Op.SveFpArithmetic.Op.RECPS, 2, 1, 2, 3, 0, false, false, false, 0),
                d(0x65c31c41, Ir64Op.SveFpArithmetic.Op.RSQRTS, 3, 1, 2, 3, 0, false, false, false, 0),
                d(0x658e3041, Ir64Op.SveFpArithmetic.Op.RECPE, 2, 1, 2, 0, 0, false, false, false, 0),
                d(0x65cf3041, Ir64Op.SveFpArithmetic.Op.RSQRTE, 3, 1, 2, 0, 0, false, false, false, 0),
                d(0x654e3083, Ir64Op.SveFpArithmetic.Op.RECPE, 1, 3, 4, 0, 0, false, false, false, 0),
                d(0x65808c41, A, 2, 1, 1, 2, 3, true, false, false, 0),        // fadd z1.s, p3/m, z1.s, z2.s
                d(0x65818c41, S, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65828c41, M, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65838c41, S, 2, 1, 1, 2, 3, true, true, false, 0),         // fsubr
                d(0x65848c41, Ir64Op.SveFpArithmetic.Op.MAXNM, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65858c41, Ir64Op.SveFpArithmetic.Op.MINNM, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65868c41, Ir64Op.SveFpArithmetic.Op.MAX, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65878c41, Ir64Op.SveFpArithmetic.Op.MIN, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65888c41, Ir64Op.SveFpArithmetic.Op.ABD, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65898c41, Ir64Op.SveFpArithmetic.Op.SCALE, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x658a8c41, Ir64Op.SveFpArithmetic.Op.MULX, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x658c8c41, V, 2, 1, 1, 2, 3, true, true, false, 0),         // fdivr
                d(0x658d8c41, V, 2, 1, 1, 2, 3, true, false, false, 0),
                d(0x65409c41, A, 1, 1, 1, 2, 7, true, false, false, 0),        // fadd z1.h, p7/m
                d(0x65cd9fdf, V, 3, 31, 31, 30, 7, true, false, false, 0),     // fdiv z31.d, p7/m, z31.d, z30.d
                d(0x65988c01, A, 2, 1, 1, 0, 3, true, false, true, 0),         // fadd z1.s, p3/m, z1.s, #0.5
                d(0x65988c21, A, 2, 1, 1, 0, 3, true, false, true, 1),         // ... #1.0
                d(0x65998c21, S, 2, 1, 1, 0, 3, true, false, true, 1),
                d(0x659a8c21, M, 2, 1, 1, 0, 3, true, false, true, 1),         // fmul #2.0
                d(0x659b8c01, S, 2, 1, 1, 0, 3, true, true, true, 0),          // fsubr #0.5
                d(0x659c8c01, Ir64Op.SveFpArithmetic.Op.MAXNM, 2, 1, 1, 0, 3, true, false, true, 0),
                d(0x659d8c21, Ir64Op.SveFpArithmetic.Op.MINNM, 2, 1, 1, 0, 3, true, false, true, 1),
                d(0x659e8c01, Ir64Op.SveFpArithmetic.Op.MAX, 2, 1, 1, 0, 3, true, false, true, 0),
                d(0x659f8c21, Ir64Op.SveFpArithmetic.Op.MIN, 2, 1, 1, 0, 3, true, false, true, 1),
                d(0x65d88c21, A, 3, 1, 1, 0, 3, true, false, true, 1),
                d(0x65958041, Ir64Op.SveFpArithmetic.Op.TMAD, 2, 1, 1, 2, 0, false, false, false, 5),
                d(0x65508041, Ir64Op.SveFpArithmetic.Op.TMAD, 1, 1, 1, 2, 0, false, false, false, 0),
                d(0x65d78041, Ir64Op.SveFpArithmetic.Op.TMAD, 3, 1, 1, 2, 0, false, false, false, 7));
    }

    @ParameterizedTest
    @MethodSource("decodeTable")
    void theWordsMatchTheAssemblerAndDecodeToTheExpectedFields(Decoded expected) {
        Ir64Op.SveFpArithmetic op = assertInstanceOf(Ir64Op.SveFpArithmetic.class, decode(FAMINMAX, expected.word()),
                Integer.toHexString(expected.word()));
        assertEquals(expected.op(), op.op(), "op");
        assertEquals(expected.esz(), op.esz(), "esz");
        assertEquals(expected.rd(), op.rd(), "rd");
        assertEquals(expected.rn(), op.rn(), "rn");
        if (!expected.immediateForm() && expected.op() != Ir64Op.SveFpArithmetic.Op.RECPE
                && expected.op() != Ir64Op.SveFpArithmetic.Op.RSQRTE) {
            assertEquals(expected.rm(), op.rm(), "rm");
        }
        assertEquals(expected.predicated(), op.predicated(), "predicated");
        if (expected.predicated()) {
            assertEquals(expected.pg(), op.pg(), "pg");
        }
        assertEquals(expected.reversed(), op.reversed(), "reversed");
        assertEquals(expected.immediateForm(), op.immediateForm(), "immediateForm");
        assertEquals(expected.immediate(), op.immediate(), "immediate");
    }

    @Test
    void famaxAndFaminDecodeOnlyUnderFeatFaminmax() {
        assertTrue(decodes(FAMINMAX, 0x658e8c41), "famax z1.s");
        assertTrue(decodes(FAMINMAX, 0x65cf8c41), "famin z1.d");
        assertTrue(decodes(FAMINMAX, 0x654e8c41), "famax z1.h");
        assertFalse(decodes(SVE, 0x658e8c41), "ARMV9_0_A não tem FEAT_FAMINMAX");
        assertFalse(decodes(SVE, 0x65cf8c41));
    }

    @Test
    void thirtyTwoRowsDecodeAndEsz0IsRefusedInEveryGroup() {
        int[] eszTwo = {predicated(2, P_ADD), unpredicated(2, U_ADD), immediate(2, 0, 0), tmad(2, 0), unary(2, false)};
        for (int word : eszTwo) {
            assertTrue(decodes(SVE, word), Integer.toHexString(word));
            int esz0 = word & ~(0b11 << 22);
            assertFalse(decodes(SVE, esz0), "esz = 0 é UNALLOCATED em FP: " + Integer.toHexString(esz0));
        }
        // Contagem exata: 15 predicadas (sob FEAT_FAMINMAX) + 8 imediato + 6 não predicadas + 2 unárias + FTMAD = 32
        // (linhas do sve.decode; FSUBR/FDIVR contam por opcode).
        int rows = 0;
        for (int opcode = 0; opcode < 16; opcode++) {
            rows += decodes(FAMINMAX, predicated(2, opcode)) ? 1 : 0;
        }
        for (int opcode = 0; opcode < 8; opcode++) {
            rows += decodes(SVE, immediate(2, opcode, 1)) ? 1 : 0;
            rows += decodes(SVE, unpredicated(2, opcode)) ? 1 : 0;
        }
        rows += decodes(SVE, unary(2, false)) ? 1 : 0;
        rows += decodes(SVE, unary(2, true)) ? 1 : 0;
        rows += decodes(SVE, tmad(2, 3)) ? 1 : 0;
        assertEquals(15 + 8 + 6 + 2 + 1, rows);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x6582_1441, 0x6582_1441 | 1 << 10, 0x658b8c41, 0x658b8c41 | 1 << 22, 0x65e00000})
    void unallocatedOpcodesAreRefused(int word) {
        // 000 100 / 000 101 das não predicadas e o opcode 1011 das predicadas.
        assertFalse(decodes(FAMINMAX, word), Integer.toHexString(word));
    }

    // ── Execução: aritmética em "mais próximo" ───────────────────────────────────────────────────

    static Stream<int[]> predicatedOpcodesByEsz() {
        int[] opcodes = {P_ADD, P_SUB, P_MUL, P_SUBR, P_MAXNM, P_MINNM, P_MAX, P_MIN, P_ABD, P_MULX, P_DIVR, P_DIV,
            P_AMAX, P_AMIN};
        return IntStream.rangeClosed(1, 3).boxed().flatMap(esz -> IntStream.of(opcodes).mapToObj(op -> new int[] {esz, op}));
    }

    @ParameterizedTest
    @MethodSource("predicatedOpcodesByEsz")
    void predicatedBinaryOperationsMatchTheNativeOracleAndLeaveInactiveElementsIntact(int[] parameters) {
        int esz = parameters[0];
        int opcode = parameters[1];
        boolean reversed = opcode == P_SUBR || opcode == P_DIVR;
        int canonical = opcode == P_SUBR ? P_SUB : opcode == P_DIVR ? P_DIV : opcode;
        for (int vl : VECTOR_LENGTHS) {
            for (int seed = 0; seed < 6; seed++) {
                Aarch64Core core = core(FAMINMAX, vl);
                Random random = new Random(seed * 131L + opcode * 17L + esz * 7L + vl);
                setElements(core, Z1, esz, randomFp(core, esz, random));
                setElements(core, Z3, esz, randomFp(core, esz, random));
                boolean[] active = randomPredicate(core, esz, random);
                long[] n = elements(core, Z1, esz);
                long[] m = elements(core, Z3, esz);
                run(FAMINMAX, core, predicated(esz, opcode));
                long[] result = elements(core, Z1, esz);
                for (int e = 0; e < result.length; e++) {
                    String label = "op=" + opcode + " esz=" + esz + " vl=" + vl + " e=" + e;
                    if (!active[e]) {
                        assertEquals(n[e], result[e], label + " inativo: merging");
                        continue;
                    }
                    double a = toDouble(reversed ? m[e] : n[e], esz);
                    double b = toDouble(reversed ? n[e] : m[e], esz);
                    if ((canonical == P_MAXNM || canonical == P_MINNM) && (isSignaling(n[e], esz) || isSignaling(m[e], esz))) {
                        assertTrue(isNaN(result[e], esz), label + ": SNaN vale NaN mesmo no FMAXNM/FMINNM");
                        continue;
                    }
                    assertSameFp(fromDouble(nearestOracle(canonical, a, b, esz), esz), result[e], esz, label);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void unpredicatedAddSubMulMatchTheNativeOracle(int esz) {
        int[] opcodes = {U_ADD, U_SUB, U_MUL};
        int[] canonical = {P_ADD, P_SUB, P_MUL};
        for (int vl : VECTOR_LENGTHS) {
            for (int k = 0; k < opcodes.length; k++) {
                Aarch64Core core = core(SVE, vl);
                Random random = new Random(esz * 91L + k * 13L + vl);
                setElements(core, Z2, esz, randomFp(core, esz, random));
                setElements(core, Z3, esz, randomFp(core, esz, random));
                long[] n = elements(core, Z2, esz);
                long[] m = elements(core, Z3, esz);
                run(SVE, core, unpredicated(esz, opcodes[k]));
                long[] result = elements(core, Z1, esz);
                for (int e = 0; e < result.length; e++) {
                    assertSameFp(fromDouble(nearestOracle(canonical[k], toDouble(n[e], esz), toDouble(m[e], esz), esz), esz),
                            result[e], esz, "op=" + opcodes[k] + " esz=" + esz + " e=" + e);
                }
            }
        }
    }

    @Test
    void unpredicatedDestinationMayAliasAnySource() {
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z1, 3, filled(core, 3, Double.doubleToRawLongBits(10.0)));
        setElements(core, Z3, 3, filled(core, 3, Double.doubleToRawLongBits(4.0)));
        // fsub z1.d, z1.d, z3.d  (Zd = Zn)
        run(SVE, core, 0x65000000 | 3 << 22 | Z3 << 16 | U_SUB << 10 | Z1 << 5 | Z1);
        assertEquals(6.0, Double.longBitsToDouble(elements(core, Z1, 3)[0]));
        // fsub z3.d, z1.d, z3.d  (Zd = Zm): 6 - 4
        run(SVE, core, 0x65000000 | 3 << 22 | Z3 << 16 | U_SUB << 10 | Z1 << 5 | Z3);
        assertEquals(2.0, Double.longBitsToDouble(elements(core, Z3, 3)[0]));
    }

    @Test
    void reversedFormsComputeZmOpZdnWithAsymmetricOperands() {
        for (int esz = 1; esz <= 3; esz++) {
            Aarch64Core core = core(SVE, 256);
            allActive(core);
            setElements(core, Z1, esz, filled(core, esz, fromDouble(10.0, esz)));
            setElements(core, Z3, esz, filled(core, esz, fromDouble(4.0, esz)));
            run(SVE, core, predicated(esz, P_SUBR));
            assertEquals(fromDouble(4.0 - 10.0, esz), elements(core, Z1, esz)[0], "fsubr esz=" + esz);
            setElements(core, Z1, esz, filled(core, esz, fromDouble(8.0, esz)));
            setElements(core, Z3, esz, filled(core, esz, fromDouble(2.0, esz)));
            run(SVE, core, predicated(esz, P_DIVR));
            assertEquals(fromDouble(2.0 / 8.0, esz), elements(core, Z1, esz)[0], "fdivr esz=" + esz);
            setElements(core, Z1, esz, filled(core, esz, fromDouble(8.0, esz)));
            run(SVE, core, predicated(esz, P_DIV));
            assertEquals(fromDouble(8.0 / 2.0, esz), elements(core, Z1, esz)[0], "fdiv esz=" + esz);
            setElements(core, Z1, esz, filled(core, esz, fromDouble(10.0, esz)));
            run(SVE, core, immediate(esz, 3, 0)); // fsubr z1, p2/m, z1, #0.5 = 0.5 - 10
            assertEquals(fromDouble(0.5 - 10.0, esz), elements(core, Z1, esz)[0], "fsubr #0.5 esz=" + esz);
        }
    }

    @Test
    void scaleMultipliesByTwoToTheSignedIntegerOfTheSecondVector() {
        for (int esz = 1; esz <= 3; esz++) {
            Aarch64Core core = core(SVE, 512);
            allActive(core);
            double[] scales = {3, -3, 0, 100, -100, 1000, -1000};
            long[] a = new long[elementCount(core, esz)];
            long[] m = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = fromDouble(i % 2 == 0 ? 1.5 : -0.75, esz);
                m[i] = (long) scales[i % scales.length] & mask(esz);
            }
            setElements(core, Z1, esz, a);
            setElements(core, Z3, esz, m);
            run(SVE, core, predicated(esz, P_SCALE));
            long[] result = elements(core, Z1, esz);
            for (int i = 0; i < a.length; i++) {
                double expected = Math.scalb(toDouble(a[i], esz), (int) scales[i % scales.length]);
                assertSameFp(fromDouble(expected, esz), result[i], esz, "esz=" + esz + " i=" + i);
            }
        }
    }

    @Test
    void maxnmAndMaxTreatANanDifferently() {
        for (int esz = 1; esz <= 3; esz++) {
            Aarch64Core core = core(SVE, 256);
            allActive(core);
            long nan = fromDouble(Double.NaN, esz);
            long one = fromDouble(1.0, esz);
            setElements(core, Z1, esz, filled(core, esz, nan));
            setElements(core, Z3, esz, filled(core, esz, one));
            run(SVE, core, predicated(esz, P_MAXNM));
            assertEquals(one, elements(core, Z1, esz)[0], "FMAXNM(NaN, 1.0) = 1.0");
            setElements(core, Z1, esz, filled(core, esz, nan));
            run(SVE, core, predicated(esz, P_MAX));
            assertTrue(isNaN(elements(core, Z1, esz)[0], esz), "FMAX(NaN, 1.0) propaga o NaN");
            setElements(core, Z1, esz, filled(core, esz, one));
            setElements(core, Z3, esz, filled(core, esz, nan));
            run(SVE, core, predicated(esz, P_MINNM));
            assertEquals(one, elements(core, Z1, esz)[0], "FMINNM(1.0, NaN) = 1.0");
        }
    }

    @Test
    void multiplyExtendedReturnsTwoForZeroTimesInfinityWhereMultiplyGivesTheDefaultNan() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(0.0f) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(Float.NEGATIVE_INFINITY) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_MULX));
        assertEquals(Float.floatToRawIntBits(-2.0f) & 0xFFFFFFFFL, elements(core, Z1, 2)[0], "FMULX(+0, -inf) = -2.0");
        assertEquals(0L, fpsr(core), "FMULX não levanta exceção");
        setElements(core, Z1, 2, filled(core, 2, 0L));
        run(SVE, core, predicated(2, P_MUL));
        assertEquals(0x7fc00000L, elements(core, Z1, 2)[0], "FMUL(0, -inf) = NaN padrão");
        assertEquals(IOC, fpsr(core) & 0xFF, "operação inválida");
    }

    // ── Imediatos de 1 bit ───────────────────────────────────────────────────────────────────────

    @Test
    void theEightOneBitImmediatesSelectTheConstantsOfTheManual() {
        // opcode → {imm=0, imm=1}
        double[][] constants = {{0.5, 1.0}, {0.5, 1.0}, {0.5, 2.0}, {0.5, 1.0}, {0.0, 1.0}, {0.0, 1.0}, {0.0, 1.0},
            {0.0, 1.0}};
        double x = 3.25;
        for (int esz = 1; esz <= 3; esz++) {
            for (int opcode = 0; opcode < 8; opcode++) {
                for (int imm = 0; imm < 2; imm++) {
                    Aarch64Core core = core(SVE, 256);
                    allActive(core);
                    setElements(core, Z1, esz, filled(core, esz, fromDouble(x, esz)));
                    run(SVE, core, immediate(esz, opcode, imm));
                    double c = constants[opcode][imm];
                    double expected = switch (opcode) {
                        case 0 -> x + c;
                        case 1 -> x - c;
                        case 2 -> x * c;
                        case 3 -> c - x;
                        case 4, 6 -> Math.max(x, c);
                        default -> Math.min(x, c);
                    };
                    assertEquals(fromDouble(expected, esz), elements(core, Z1, esz)[0],
                            "esz=" + esz + " opcode=" + opcode + " imm=" + imm);
                }
            }
        }
    }

    // ── Modos de arredondamento e flags ──────────────────────────────────────────────────────────

    private static BigDecimal exact(int opcode, double a, double b) {
        BigDecimal x = new BigDecimal(a);
        BigDecimal y = new BigDecimal(b);
        return switch (opcode) {
            case P_ADD -> x.add(y);
            case P_SUB -> x.subtract(y);
            case P_MUL -> x.multiply(y);
            default -> x.divide(y, new MathContext(2500));
        };
    }

    /// Resultado correto sob `rmode` de um par finito, com o vizinho de `nextUp`/`nextDown` escolhido por
    /// comparação EXATA; `flags` recebe as flags esperadas. Só `esz = 2` e `3`.
    private static long directedOracle(int opcode, double a, double b, int esz, long rmode, int[] flags) {
        BigDecimal e = exact(opcode, a, b);
        double nearest = switch (opcode) {
            case P_ADD -> esz == 2 ? (double) ((float) a + (float) b) : a + b;
            case P_SUB -> esz == 2 ? (double) ((float) a - (float) b) : a - b;
            case P_MUL -> esz == 2 ? (double) ((float) a * (float) b) : a * b;
            default -> esz == 2 ? (double) ((float) a / (float) b) : a / b;
        };
        boolean negative = e.signum() < 0;
        boolean up = rmode == FPCR_RMODE_PLUS || (rmode == FPCR_RMODE_ZERO && negative);
        boolean down = rmode == FPCR_RMODE_MINUS || (rmode == FPCR_RMODE_ZERO && !negative);
        double result = nearest;
        if (e.signum() == 0 && (opcode == P_ADD || opcode == P_SUB)) {
            result = rmode == FPCR_RMODE_MINUS ? -0.0 : 0.0; // soma exata zero: -0 só sob arredondamento para -inf
        } else if (Double.isInfinite(nearest)) {
            boolean toInfinity = rmode == 0L || (up && !negative) || (down && negative);
            result = toInfinity ? nearest : Math.copySign(esz == 2 ? Float.MAX_VALUE : Double.MAX_VALUE, nearest);
            flags[0] |= OFC | IXC;
        } else {
            int cmp = new BigDecimal(nearest).compareTo(e);
            if (up && cmp < 0) {
                result = esz == 2 ? Math.nextUp((float) nearest) : Math.nextUp(nearest);
            } else if (down && cmp > 0) {
                result = esz == 2 ? Math.nextDown((float) nearest) : Math.nextDown(nearest);
            }
            boolean inexact = new BigDecimal(result).compareTo(e) != 0;
            double tinyLimit = esz == 2 ? Float.MIN_NORMAL : Double.MIN_NORMAL;
            if (inexact) {
                flags[0] |= IXC;
                if (e.abs().compareTo(new BigDecimal(tinyLimit)) < 0) {
                    flags[0] |= UFC;
                }
            }
        }
        return fromDouble(result, esz);
    }

    @Test
    void fpcrRModeIsHonouredByAddSubMulAndDivInTheFourModes() {
        double[][] pairs = {{1.0, 1.0e-10}, {-1.0, 1.0e-10}, {1.0, 3.0}, {-2.5, 7.1}, {3.0e38, 3.0e38}, {-3.4e38, 3.4e38},
            {1.0e-30, 1.0e-30}, {1.5e-45, 0.9}, {0.1, 0.2}, {16777217.0, 1.0}, {1.0e300, 1.0e300}, {5.0e-324, 0.5}};
        int[] opcodes = {P_ADD, P_SUB, P_MUL, P_DIV};
        for (int esz = 2; esz <= 3; esz++) {
            for (long rmode : RMODES) {
                for (int opcode : opcodes) {
                    for (double[] pair : pairs) {
                        double a = esz == 2 ? (double) (float) pair[0] : pair[0];
                        double b = esz == 2 ? (double) (float) pair[1] : pair[1];
                        if (Double.isInfinite(a) || Double.isInfinite(b) || Double.isNaN(a) || Double.isNaN(b)
                                || (opcode == P_DIV && b == 0.0) || (esz == 2 && (Float.isInfinite((float) pair[0])
                                || Float.isInfinite((float) pair[1])))) {
                            continue;
                        }
                        int[] flags = {0};
                        long expected = directedOracle(opcode, a, b, esz, rmode, flags);
                        Aarch64Core core = core(SVE, 256);
                        allActive(core);
                        setFpcr(core, rmode);
                        setElements(core, Z1, esz, filled(core, esz, fromDouble(a, esz)));
                        setElements(core, Z3, esz, filled(core, esz, fromDouble(b, esz)));
                        run(SVE, core, predicated(esz, opcode));
                        String label = "esz=" + esz + " rmode=" + (rmode >> 22) + " op=" + opcode + " " + a + "," + b;
                        assertEquals(expected, elements(core, Z1, esz)[0], label);
                        assertEquals(flags[0], fpsr(core) & (OFC | UFC | IXC | IOC | DZC), label + " flags");
                    }
                }
            }
        }
    }

    @Test
    void halfPrecisionRoundsToNearestAndSetsTheFlagsOfThePseudocode() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        // 1.0 + 2^-12 não cabe em meia precisão (10 bits de fração): inexato, arredonda para 1.0.
        setElements(core, Z1, 1, filled(core, 1, 0x3c00));
        setElements(core, Z3, 1, filled(core, 1, fromDouble(Math.scalb(1.0, -12), 1)));
        run(SVE, core, predicated(1, P_ADD));
        assertEquals(0x3c00L, elements(core, Z1, 1)[0]);
        assertEquals(IXC, fpsr(core) & 0xFF);
        // 65504 + 65504 estoura: infinito com OFC e IXC.
        setElements(core, Z1, 1, filled(core, 1, 0x7bff));
        setElements(core, Z3, 1, filled(core, 1, 0x7bff));
        run(SVE, core, predicated(1, P_ADD));
        assertEquals(0x7c00L, elements(core, Z1, 1)[0]);
        assertEquals(IXC | OFC, fpsr(core) & 0xFF);
    }

    @Test
    void everyExceptionFlagIsAccumulatedInTheSameFpsrAsTheA64() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0xF0000000L); // NZCV do FPSR não muda
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(Float.POSITIVE_INFINITY) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(Float.POSITIVE_INFINITY) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_SUB)); // inf - inf
        assertEquals(0x7fc00000L, elements(core, Z1, 2)[0]);
        assertEquals(IOC, fpsr(core) & 0xFF);
        assertEquals(0xF0000000L, fpsr(core) & 0xF0000000L, "bits altos preservados");
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(1.0f) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, 0L));
        run(SVE, core, predicated(2, P_DIV)); // 1 / 0
        assertEquals(0x7f800000L, elements(core, Z1, 2)[0]);
        assertEquals(IOC | DZC, fpsr(core) & 0xFF, "as flags são cumulativas");
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(1.0e-30f) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(1.0e-30f) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_MUL)); // 1e-60: minúsculo e inexato → 0 com UFC|IXC
        assertEquals(0L, elements(core, Z1, 2)[0]);
        assertEquals(IOC | DZC | UFC | IXC, fpsr(core) & 0xFF);
    }

    @Test
    void inactiveElementsNeverRaiseFpExceptionsOrTouchTheFpsr() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE, vl);
            int n = elementCount(core, 2);
            long[] a = new long[n];
            long[] b = new long[n];
            for (int i = 0; i < n; i++) {
                a[i] = Float.floatToRawIntBits(1.0f) & 0xFFFFFFFFL;
                b[i] = i % 2 == 0 ? Float.floatToRawIntBits(2.0f) & 0xFFFFFFFFL : 0L; // 1/0 nos ímpares
            }
            setElements(core, Z1, 2, a);
            setElements(core, Z3, 2, b);
            for (int e = 0; e < n; e += 2) { // só os pares ativos
                core.scalable().setPWord(P2, (e * 4) / 64, core.scalable().pWord(P2, (e * 4) / 64) | (1L << ((e * 4) % 64)));
            }
            run(SVE, core, predicated(2, P_DIV));
            assertEquals(0L, fpsr(core), "os elementos ímpares (1/0) estavam inativos");
            long[] result = elements(core, Z1, 2);
            for (int e = 0; e < n; e++) {
                assertEquals(e % 2 == 0 ? Float.floatToRawIntBits(0.5f) & 0xFFFFFFFFL : a[e], result[e], "e=" + e);
            }
        }
    }

    @Test
    void flushToZeroFlushesDenormalInputsWithIdcAndTinyResultsWithUfc() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setFpcr(core, FPCR_FZ);
        long denormal = 0x00400000L; // 2^-127
        setElements(core, Z1, 2, filled(core, 2, denormal));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(1.0f) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_MUL));
        assertEquals(0L, elements(core, Z1, 2)[0], "denormal de entrada vira zero");
        assertEquals(IDC, fpsr(core) & 0xFF);
        // Resultado minúsculo (normal × normal) também é achatado, com UFC e sem IXC.
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0L);
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(1.0e-20f) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(1.0e-20f) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_MUL));
        assertEquals(0L, elements(core, Z1, 2)[0]);
        assertEquals(UFC, fpsr(core) & 0xFF);
        // Sem FZ o denormal é usado normalmente.
        setFpcr(core, 0L);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0L);
        setElements(core, Z1, 2, filled(core, 2, denormal));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(2.0f) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_MUL));
        assertEquals(0x00800000L, elements(core, Z1, 2)[0], "2^-127 × 2 = 2^-126 (o menor normal)");
        assertEquals(0L, fpsr(core));
    }

    @Test
    void fz16GovernsHalfPrecisionAndNeverRaisesIdc() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setFpcr(core, FPCR_FZ); // FZ NÃO vale para meia precisão
        setElements(core, Z1, 1, filled(core, 1, 0x0200)); // denormal de meia (2^-15)
        setElements(core, Z3, 1, filled(core, 1, 0x3c00));
        run(SVE, core, predicated(1, P_MUL));
        assertEquals(0x0200L, elements(core, Z1, 1)[0], "FZ não achata meia precisão");
        setFpcr(core, FPCR_FZ16);
        run(SVE, core, predicated(1, P_MUL));
        assertEquals(0L, elements(core, Z1, 1)[0], "FZ16 achata");
        assertEquals(0L, fpsr(core) & IDC, "FZ16 não levanta IDC");
    }

    @Test
    void defaultNanModeReplacesAnyNanAndQuietingKeepsThePayload() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long signaling = 0x7f800001L; // SNaN com payload 1
        setElements(core, Z1, 2, filled(core, 2, signaling));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(1.0f) & 0xFFFFFFFFL));
        run(SVE, core, predicated(2, P_ADD));
        assertEquals(0x7fc00001L, elements(core, Z1, 2)[0], "SNaN → silenciado com o payload");
        assertEquals(IOC, fpsr(core) & 0xFF);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0L);
        setElements(core, Z1, 2, filled(core, 2, 0x7fc00005L)); // QNaN: sem IOC
        run(SVE, core, predicated(2, P_ADD));
        assertEquals(0x7fc00005L, elements(core, Z1, 2)[0]);
        assertEquals(0L, fpsr(core));
        setFpcr(core, FPCR_DN);
        run(SVE, core, predicated(2, P_ADD));
        assertEquals(0x7fc00000L, elements(core, Z1, 2)[0], "DN: NaN padrão");
        // Um SNaN em qualquer operando tem prioridade sobre um QNaN.
        setFpcr(core, 0L);
        setElements(core, Z1, 2, filled(core, 2, 0x7fc00005L));
        setElements(core, Z3, 2, filled(core, 2, signaling));
        run(SVE, core, predicated(2, P_ADD));
        assertEquals(0x7fc00001L, elements(core, Z1, 2)[0]);
    }

    // ── FTSMUL / FTMAD / passos e estimativas ────────────────────────────────────────────────────

    @Test
    void ftsmulSquaresAndTakesTheSignFromBit0OfTheSecondOperand() {
        for (int esz = 1; esz <= 3; esz++) {
            Aarch64Core core = core(SVE, 256);
            long[] a = new long[elementCount(core, esz)];
            long[] b = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = fromDouble(i % 2 == 0 ? -3.0 : 1.5, esz);
                b[i] = i % 3; // bit 0: 0, 1, 0, 0, 1, 0 ...
            }
            setElements(core, Z2, esz, a);
            setElements(core, Z3, esz, b);
            run(SVE, core, unpredicated(esz, U_TSMUL));
            long[] r = elements(core, Z1, esz);
            for (int i = 0; i < a.length; i++) {
                double sq = toDouble(a[i], esz) * toDouble(a[i], esz);
                assertEquals(fromDouble((b[i] & 1) != 0 ? -sq : sq, esz), r[i], "esz=" + esz + " i=" + i);
            }
            // NaN: o sinal não é forçado.
            setElements(core, Z2, esz, filled(core, esz, fromDouble(Double.NaN, esz)));
            setElements(core, Z3, esz, filled(core, esz, 1L));
            run(SVE, core, unpredicated(esz, U_TSMUL));
            assertTrue(isNaN(elements(core, Z1, esz)[0], esz));
        }
    }

    @Test
    void ftmadWithAZeroMultiplicandReturnsTheCoefficientOfTheSelectedBank() {
        // m = +0 → coeff[imm] (banco de seno); m = -0 → coeff[imm + 8] (banco de cosseno).
        long[][] expectedSingle = {{0x3f800000L, 0xbe2aaaabL, 0x3c088886L, 0xb95008b9L, 0x36369d6dL, 0L, 0L, 0L},
            {0x3f800000L, 0xbf000000L, 0x3d2aaaa6L, 0xbab60705L, 0x37cd37ccL, 0L, 0L, 0L}};
        long[][] expectedHalf = {{0x3c00L, 0xb155L, 0x2030L, 0, 0, 0, 0, 0}, {0x3c00L, 0xb800L, 0x293aL, 0, 0, 0, 0, 0}};
        long[][] expectedDouble = {{0x3ff0000000000000L, 0xbfc5555555555543L, 0x3f8111111110f30cL, 0xbf2a01a019b92fc6L,
            0x3ec71de351f3d22bL, 0xbe5ae5e2b60f7b91L, 0x3de5d8408868552fL, 0L},
            {0x3ff0000000000000L, 0xbfe0000000000000L, 0x3fa5555555555536L, 0xbf56c16c16c13a0bL, 0x3efa01a019b1e8d8L,
                0xbe927e4f7282f468L, 0x3e21ee96d2641b13L, 0xbda8f76380fbb401L}};
        long[][][] tables = {null, expectedHalf, expectedSingle, expectedDouble};
        for (int esz = 1; esz <= 3; esz++) {
            for (int bank = 0; bank < 2; bank++) {
                int limit = esz == 2 && bank == 0 ? 5 : 8;
                for (int imm = 0; imm < limit; imm++) {
                    Aarch64Core core = core(SVE, 256);
                    setElements(core, Z1, esz, filled(core, esz, fromDouble(2.0, esz))); // n = 2.0
                    long minusZero = 1L << (bits(esz) - 1);
                    setElements(core, Z3, esz, filled(core, esz, bank == 0 ? 0L : minusZero));
                    run(SVE, core, tmad(esz, imm));
                    assertEquals(tables[esz][bank][imm], elements(core, Z1, esz)[0],
                            "esz=" + esz + " banco=" + bank + " imm=" + imm);
                }
            }
        }
    }

    @Test
    void ftmadIsAFusedMultiplyAddOfNTimesAbsMPlusTheCoefficient() {
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(3.0f) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(-0.5f) & 0xFFFFFFFFL));
        run(SVE, core, tmad(2, 1));
        // m < 0: |m| = 0.5 e coeff[1 + 8] = -0.5 → fma(3, 0.5, -0.5) = 1.0
        assertEquals(Float.floatToRawIntBits(1.0f) & 0xFFFFFFFFL, elements(core, Z1, 2)[0]);
        setElements(core, Z1, 3, filled(core, 3, Double.doubleToRawLongBits(2.0)));
        setElements(core, Z3, 3, filled(core, 3, Double.doubleToRawLongBits(0.25)));
        run(SVE, core, tmad(3, 2));
        assertEquals(Double.doubleToRawLongBits(Math.fma(2.0, 0.25, Double.longBitsToDouble(0x3f8111111110f30cL))),
                elements(core, Z1, 3)[0]);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void reciprocalStepsAreFusedAndInfinityTimesZeroGivesTheConstants(int esz) {
        for (int vl : VECTOR_LENGTHS) {
            for (int which = 0; which < 2; which++) {
                Aarch64Core core = core(SVE, vl);
                Random random = new Random(esz * 7L + which * 3L + vl);
                setElements(core, Z2, esz, randomFp(core, esz, random));
                setElements(core, Z3, esz, randomFp(core, esz, random));
                long[] n = elements(core, Z2, esz);
                long[] m = elements(core, Z3, esz);
                run(SVE, core, unpredicated(esz, which == 0 ? U_RECPS : U_RSQRTS));
                long[] r = elements(core, Z1, esz);
                for (int e = 0; e < r.length; e++) {
                    double a = toDouble(n[e], esz);
                    double b = toDouble(m[e], esz);
                    boolean infZero = (Double.isInfinite(a) && b == 0.0) || (a == 0.0 && Double.isInfinite(b));
                    double expected;
                    if (Double.isNaN(a) || Double.isNaN(b)) {
                        expected = Double.NaN;
                    } else if (infZero) {
                        expected = which == 0 ? 2.0 : 1.5;
                    } else if (esz == 2) {
                        float f = Math.fma(-(float) a, (float) b, which == 0 ? 2.0f : 3.0f);
                        expected = which == 0 ? f : Math.scalb(f, -1);
                    } else {
                        double f = Math.fma(-a, b, which == 0 ? 2.0 : 3.0);
                        expected = which == 0 ? f : Math.scalb(f, -1);
                    }
                    if (esz == 1 && !Double.isNaN(expected) && !infZero) {
                        expected = which == 0 ? -a * b + 2.0 : (-a * b + 3.0) * 0.5;
                    }
                    assertSameFp(fromDouble(expected, esz), r[e], esz,
                            "esz=" + esz + " which=" + which + " " + a + "," + b);
                }
            }
        }
    }

    @Test
    void reciprocalEstimatesMatchTheKnownArmTableValues() {
        // FRECPE(1.0f) = 0.99609375, FRECPE(2.0f) = 0.498046875, FRSQRTE(1.0f) = 0.99609375, FRSQRTE(4.0f) = 0.498046875.
        long[][] singles = {{0x3f800000L, 0x3f7f8000L, 0}, {0x40000000L, 0x3eff8000L, 0}, {0x3f800000L, 0x3f7f8000L, 1},
            {0x40800000L, 0x3eff8000L, 1}};
        for (long[] c : singles) {
            Aarch64Core core = core(SVE, 256);
            setElements(core, Z3, 2, filled(core, 2, c[0]));
            run(SVE, core, unary(2, c[2] == 1));
            assertEquals(c[1], elements(core, Z1, 2)[0], Long.toHexString(c[0]) + " op=" + c[2]);
        }
        // Meia: FRECPE(1.0h) = 0.99609375 → 0x3bfc; dupla: 0x3fefF00000000000.
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z3, 1, filled(core, 1, 0x3c00));
        run(SVE, core, unary(1, false));
        assertEquals(0x3bfcL, elements(core, Z1, 1)[0]);
        setElements(core, Z3, 3, filled(core, 3, Double.doubleToRawLongBits(1.0)));
        run(SVE, core, unary(3, false));
        assertEquals(0x3fefF00000000000L, elements(core, Z1, 3)[0]);
        run(SVE, core, unary(3, true));
        assertEquals(0x3fefF00000000000L, elements(core, Z1, 3)[0], "FRSQRTE(1.0)");
    }

    @Test
    void reciprocalEstimatesAreAccurateToAboutEightBitsOverTheNormalRange() {
        Random random = new Random(42);
        for (int esz = 1; esz <= 3; esz++) {
            Aarch64Core core = core(SVE, 512);
            long[] input = new long[elementCount(core, esz)];
            for (int i = 0; i < input.length; i++) {
                double x = Math.scalb(1.0 + random.nextDouble(), random.nextInt(esz == 1 ? 20 : 120) - (esz == 1 ? 10 : 60));
                input[i] = fromDouble(x, esz);
            }
            setElements(core, Z3, esz, input);
            run(SVE, core, unary(esz, false));
            long[] recip = elements(core, Z1, esz);
            run(SVE, core, unary(esz, true));
            long[] rsqrt = elements(core, Z1, esz);
            for (int i = 0; i < input.length; i++) {
                double x = toDouble(input[i], esz);
                assertEquals(1.0, toDouble(recip[i], esz) * x, 1.0 / 128, "1/x esz=" + esz + " x=" + x);
                assertEquals(1.0, toDouble(rsqrt[i], esz) * toDouble(rsqrt[i], esz) * x, 1.0 / 64, "1/sqrt(x) esz=" + esz);
            }
        }
    }

    @Test
    void reciprocalEstimatesHandleSpecialOperands() {
        Aarch64Core core = core(SVE, 256);
        long[][] cases = { // {esz=2 entrada, FRECPE saída, FRECPE flags, FRSQRTE saída, FRSQRTE flags}
            {0x00000000L, 0x7f800000L, DZC, 0x7f800000L, DZC},             // +0
            {0x80000000L, 0xff800000L, DZC, 0xff800000L, DZC},             // -0
            {0x7f800000L, 0x00000000L, 0, 0x00000000L, 0},                 // +inf
            {0xff800000L, 0x80000000L, 0, 0x7fc00000L, IOC},               // -inf: FRSQRTE inválido
            {0x7fc00007L, 0x7fc00007L, 0, 0x7fc00007L, 0},                 // QNaN passa
            {0x7f800007L, 0x7fc00007L, IOC, 0x7fc00007L, IOC},             // SNaN silenciado
            {0xbf800000L, 0xbf7f8000L, 0, 0x7fc00000L, IOC},               // -1.0: FRSQRTE inválido
            {0x00100000L, 0x7f800000L, OFC | IXC, 0x00000000L, -1},        // |x| < 2^-128: FRECPE estoura
        };
        for (long[] c : cases) {
            for (int which = 0; which < 2; which++) {
                core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0L);
                setElements(core, Z3, 2, filled(core, 2, c[0]));
                run(SVE, core, unary(2, which == 1));
                long expected = c[1 + which * 2];
                long flags = c[2 + which * 2];
                if (flags >= 0) {
                    assertEquals(expected, elements(core, Z1, 2)[0], Long.toHexString(c[0]) + " which=" + which);
                    assertEquals(flags, fpsr(core) & 0xFF, Long.toHexString(c[0]) + " which=" + which + " flags");
                }
            }
        }
    }

    // ── Acesso, streaming e integração com blocos ────────────────────────────────────────────────

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
    @ValueSource(ints = {0x65808c41, 0x65830c41, 0x65958041, 0x658e3041, 0x65988c01})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE, 256);
        core.setSystemRegisterBus(new Cpacr());
        setElements(core, Z1, 3, filled(core, 3, 0x1234L));
        run(SVE, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, elements(core, Z1, 3)[0], "a instrução não executou");
    }

    private static Aarch64Core streamingCore(Aarch64Architecture architecture) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        return core;
    }

    @Test
    void streamingCompatibleFormsRunAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = streamingCore(architecture);
        allActive(core);
        setElements(core, Z1, 3, filled(core, 3, Double.doubleToRawLongBits(1.5)));
        setElements(core, Z3, 3, filled(core, 3, Double.doubleToRawLongBits(2.0)));
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, predicated(3, P_MUL));
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção");
        long[] result = elements(core, Z1, 3);
        assertEquals(8, result.length, "SVL = 512: 8 doublewords");
        assertEquals(Double.doubleToRawLongBits(3.0), result[7]);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x65830c41, 0x65958041})
    void ftsmulAndFtmadAreUndefinedInStreamingModeWithoutFa64(int word) {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = streamingCore(architecture);
        core.memory().write32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=0: UNDEFINED");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z1, 2, filled(core, 2, Float.floatToRawIntBits(10.0f) & 0xFFFFFFFFL));
        setElements(core, Z3, 2, filled(core, 2, Float.floatToRawIntBits(4.0f) & 0xFFFFFFFFL));
        core.memory().write32(0, predicated(2, P_SUBR)); // 4 - 10 = -6
        core.memory().write32(4, immediate(2, 0, 1)); // + 1.0 = -5
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(Float.floatToRawIntBits(-5.0f) & 0xFFFFFFFFL, elements(core, Z1, 2)[0]);
    }

    @Test
    void executingWithoutSveRefusesTheWholeSpace() {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_0_A, predicated(2, P_ADD)));
    }
}
