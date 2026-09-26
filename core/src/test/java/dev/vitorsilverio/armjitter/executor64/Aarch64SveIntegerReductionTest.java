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
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Random;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.7 — reduções inteiras SVE (`ORV`/`EORV`/`ANDV`/`SADDV`/`UADDV`/`SMAXV`/…, as 8 `*QV` por segmento
/// e o `MOVPRFX` predicado; 19 encodings). Palavras conferidas contra `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1`); registradores fixos: `Vd = z0`, `Zn = z3`, `pg = p2`
/// (`MOVPRFX`: `Zd = z1`). O oráculo é escrito com `BigInteger`, sem repetir a aritmética do executor.
class Aarch64SveIntegerReductionTest {
    private static final int Z1 = 1;
    private static final int Z3 = 3;
    private static final int P2 = 2;
    private static final int V0 = 0;
    private static final int GROUP = 0x04002860;
    private static final int OPCODE_SHIFT = 16;
    private static final int ESZ_SHIFT = 22;
    private static final int OP_SADDV = 0x00;
    private static final int OP_UADDV = 0x01;
    private static final int OP_ADDQV = 0x05;
    private static final int OP_SMAXV = 0x08;
    private static final int OP_UMAXV = 0x09;
    private static final int OP_SMINV = 0x0A;
    private static final int OP_UMINV = 0x0B;
    private static final int OP_SMAXQV = 0x0C;
    private static final int OP_UMAXQV = 0x0D;
    private static final int OP_SMINQV = 0x0E;
    private static final int OP_UMINQV = 0x0F;
    private static final int OP_MOVPRFX_Z = 0x10;
    private static final int OP_MOVPRFX_M = 0x11;
    private static final int OP_ORV = 0x18;
    private static final int OP_EORV = 0x19;
    private static final int OP_ANDV = 0x1A;
    private static final int OP_ORQV = 0x1C;
    private static final int OP_EORQV = 0x1D;
    private static final int OP_ANDQV = 0x1E;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final long GARBAGE = 0xA5A5_A5A5_A5A5_A5A5L;
    private static final int[] VECTOR_LENGTHS = {128, 256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(SVE2, "teste-SVE2p1",
            Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture SVE2P2 = Aarch64Architecture.extending(SVE2, "teste-SVE2p2",
            Aarch64Feature.SVE2_2);

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

    private static boolean refused(Aarch64Architecture architecture, int word) {
        try {
            decode(architecture, word);
            return false;
        } catch (UnsupportedOperationException refusal) {
            return true;
        }
    }

    private static int word(int esz, int opcode) {
        return GROUP | (esz << ESZ_SHIFT) | (opcode << OPCODE_SHIFT);
    }

    private static int bits(int esz) {
        return 8 << esz;
    }

    private static BigInteger mask(int esz) {
        return BigInteger.ONE.shiftLeft(bits(esz)).subtract(BigInteger.ONE);
    }

    private static BigInteger unsigned(long value, int esz) {
        return new BigInteger(Long.toUnsignedString(value)).and(mask(esz));
    }

    private static BigInteger signed(long value, int esz) {
        BigInteger unsignedValue = unsigned(value, esz);
        return unsignedValue.testBit(bits(esz) - 1) ? unsignedValue.subtract(BigInteger.ONE.shiftLeft(bits(esz)))
                : unsignedValue;
    }

    private static long truncate(BigInteger value, int esz) {
        return value.and(mask(esz)).longValue();
    }

    private static long[] elements(Aarch64Core core, int reg, int esz) {
        long[] out = new long[core.vectorLengthBytes() >> esz];
        for (int i = 0; i < out.length; i++) {
            int bitOffset = i * bits(esz);
            long shifted = core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64);
            out[i] = bits(esz) == 64 ? shifted : shifted & ((1L << bits(esz)) - 1);
        }
        return out;
    }

    private static void setElements(Aarch64Core core, int reg, int esz, long[] values) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, 0L);
        }
        for (int i = 0; i < values.length; i++) {
            int bitOffset = i * bits(esz);
            long field = bits(esz) == 64 ? values[i] : values[i] & ((1L << bits(esz)) - 1);
            core.scalable().setZWord(reg, bitOffset / 64,
                    core.scalable().zWord(reg, bitOffset / 64) | (field << (bitOffset % 64)));
        }
    }

    private static long[] randomElements(Aarch64Core core, int esz, Random random) {
        long[] out = new long[core.vectorLengthBytes() >> esz];
        long max = mask(esz).longValue();
        long half = 1L << (bits(esz) - 1);
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (random.nextInt(8)) {
                case 0 -> 0L;
                case 1 -> max;
                case 2 -> half;
                case 3 -> half - 1;
                case 4 -> 1L;
                default -> random.nextLong();
            } & max;
        }
        return out;
    }

    /// Liga em `P2` o bit do byte mais baixo de cada elemento ativo e lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int esz, Random random, boolean allInactive) {
        boolean[] active = new boolean[core.vectorLengthBytes() >> esz];
        for (int e = 0; e < active.length; e++) {
            active[e] = !allInactive && random.nextBoolean();
            for (int b = 0; b < (1 << esz); b++) {
                int index = (e << esz) + b;
                if ((b == 0 && active[e]) || (b != 0 && random.nextBoolean())) {
                    core.scalable().setPWord(P2, index / 64,
                            core.scalable().pWord(P2, index / 64) | (1L << (index % 64)));
                }
            }
        }
        return active;
    }

    /// Enche `Vd` (e o resto de `Z0`) com lixo para provar que a escrita zera o que não é resultado.
    private static void dirtyDestination(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(V0, w, GARBAGE);
        }
    }

    private static void assertZeroAbove(Aarch64Core core, int firstZeroBit, String label) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            long word = core.scalable().zWord(V0, w);
            if (w * 64 >= firstZeroBit) {
                assertEquals(0L, word, label + " palavra " + w + " deveria estar zerada");
            } else if (firstZeroBit - w * 64 < 64) {
                assertEquals(0L, word >>> (firstZeroBit - w * 64), label + " bits acima do resultado");
            }
        }
    }

    // ── Oráculos (BigInteger, sem repetir a aritmética do executor) ─────────────────────────────

    private static BigInteger neutral(int opcode, int esz) {
        return switch (opcode) {
            case OP_ANDV, OP_ANDQV, OP_UMINV, OP_UMINQV -> mask(esz);
            case OP_SMAXV, OP_SMAXQV -> BigInteger.ONE.shiftLeft(bits(esz) - 1);
            case OP_SMINV, OP_SMINQV -> BigInteger.ONE.shiftLeft(bits(esz) - 1).subtract(BigInteger.ONE);
            default -> BigInteger.ZERO;
        };
    }

    /// Um passo da redução; os operandos chegam como padrões de bits de `esz` bits (não negativos).
    private static BigInteger step(int opcode, BigInteger accumulator, BigInteger element, int esz) {
        BinaryOperator<BigInteger> signedMax = (a, b) -> signed(a.longValue(), esz).compareTo(signed(b.longValue(), esz)) >= 0 ? a : b;
        BinaryOperator<BigInteger> signedMin = (a, b) -> signed(a.longValue(), esz).compareTo(signed(b.longValue(), esz)) <= 0 ? a : b;
        return switch (opcode) {
            case OP_ORV, OP_ORQV -> accumulator.or(element);
            case OP_EORV, OP_EORQV -> accumulator.xor(element);
            case OP_ANDV, OP_ANDQV -> accumulator.and(element);
            case OP_ADDQV -> accumulator.add(element).and(mask(esz));
            case OP_SMAXV, OP_SMAXQV -> signedMax.apply(accumulator, element);
            case OP_SMINV, OP_SMINQV -> signedMin.apply(accumulator, element);
            case OP_UMAXV, OP_UMAXQV -> accumulator.max(element);
            case OP_UMINV, OP_UMINQV -> accumulator.min(element);
            default -> throw new IllegalArgumentException("opcode " + opcode);
        };
    }

    // ── Decoder ──────────────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> scalarDecodeCases() {
        return Stream.of(
                Arguments.of(0x04182860, Ir64Op.SveIntegerReduction.Op.ORV, 0),
                Arguments.of(0x04592860, Ir64Op.SveIntegerReduction.Op.EORV, 1),
                Arguments.of(0x049a2860, Ir64Op.SveIntegerReduction.Op.ANDV, 2),
                Arguments.of(0x04012860, Ir64Op.SveIntegerReduction.Op.UADDV, 0),
                Arguments.of(0x04802860, Ir64Op.SveIntegerReduction.Op.SADDV, 2),
                Arguments.of(0x04082860, Ir64Op.SveIntegerReduction.Op.SMAXV, 0),
                Arguments.of(0x04492860, Ir64Op.SveIntegerReduction.Op.UMAXV, 1),
                Arguments.of(0x048a2860, Ir64Op.SveIntegerReduction.Op.SMINV, 2),
                Arguments.of(0x04cb2860, Ir64Op.SveIntegerReduction.Op.UMINV, 3));
    }

    @ParameterizedTest
    @MethodSource("scalarDecodeCases")
    void scalarWordsFromTheAssemblerDecodeUnderPlainSve(int word, Ir64Op.SveIntegerReduction.Op expected, int esz) {
        Ir64Op.SveIntegerReduction op = assertInstanceOf(Ir64Op.SveIntegerReduction.class, decode(SVE, word));
        assertEquals(expected, op.op());
        assertEquals(esz, op.esz());
        assertEquals(V0, op.rd());
        assertEquals(Z3, op.rn());
        assertEquals(P2, op.pg());
    }

    static Stream<Arguments> segmentDecodeCases() {
        return Stream.of(
                Arguments.of(0x041c2860, Ir64Op.SveIntegerReduction.Op.ORQV),
                Arguments.of(0x045d2860, Ir64Op.SveIntegerReduction.Op.EORQV),
                Arguments.of(0x049e2860, Ir64Op.SveIntegerReduction.Op.ANDQV),
                Arguments.of(0x04052860, Ir64Op.SveIntegerReduction.Op.ADDQV),
                Arguments.of(0x044c2860, Ir64Op.SveIntegerReduction.Op.SMAXQV),
                Arguments.of(0x048e2860, Ir64Op.SveIntegerReduction.Op.SMINQV),
                Arguments.of(0x04cd2860, Ir64Op.SveIntegerReduction.Op.UMAXQV),
                Arguments.of(0x040f2860, Ir64Op.SveIntegerReduction.Op.UMINQV));
    }

    @ParameterizedTest
    @MethodSource("segmentDecodeCases")
    void segmentReductionsNeedSve2p1AndSve2p2ImpliesIt(int word, Ir64Op.SveIntegerReduction.Op expected) {
        assertEquals(expected, assertInstanceOf(Ir64Op.SveIntegerReduction.class, decode(SVE2P1, word)).op());
        assertEquals(expected, assertInstanceOf(Ir64Op.SveIntegerReduction.class, decode(SVE2P2, word)).op());
        assertTrue(refused(SVE2, word), "SVE2 sem SVE2p1 recusa (Armadilha 3 da spec)");
        assertTrue(refused(SVE, word));
    }

    @Test
    void predicatedMovprfxDecodesToTheZdWritingRecordNotToAReduction() {
        Ir64Op.SveIntegerPredicated zeroing = assertInstanceOf(Ir64Op.SveIntegerPredicated.class,
                decode(SVE, 0x04102861)); // movprfx z1.b, p2/z, z3.b
        Ir64Op.SveIntegerPredicated merging = assertInstanceOf(Ir64Op.SveIntegerPredicated.class,
                decode(SVE, 0x04512861)); // movprfx z1.h, p2/m, z3.h
        assertEquals(Ir64Op.SveIntegerPredicated.Op.MOVPRFX, zeroing.op());
        assertTrue(zeroing.zeroing());
        assertEquals(Ir64Op.SveIntegerPredicated.Op.MOVPRFX, merging.op());
        assertEquals(false, merging.zeroing());
        assertEquals(1, merging.esz());
        assertEquals(Z1, merging.rd());
        assertEquals(Z3, merging.rn());
        assertEquals(P2, merging.pg());
    }

    @Test
    void saddvWithDoublewordElementsIsRefusedAndUaddvIsNot() {
        assertTrue(refused(SVE, word(3, OP_SADDV)));
        assertInstanceOf(Ir64Op.SveIntegerReduction.class, decode(SVE, word(3, OP_UADDV)));
        for (int esz = 0; esz < 3; esz++) {
            assertInstanceOf(Ir64Op.SveIntegerReduction.class, decode(SVE, word(esz, OP_SADDV)));
        }
    }

    @Test
    void unallocatedOpcodesOfTheGroupAreRefusedAndTheAllocatedOnesCountNineteen() {
        int[] allocated = {OP_SADDV, OP_UADDV, OP_ADDQV, OP_SMAXV, OP_UMAXV, OP_SMINV, OP_UMINV, OP_SMAXQV, OP_UMAXQV,
                OP_SMINQV, OP_UMINQV, OP_MOVPRFX_Z, OP_MOVPRFX_M, OP_ORV, OP_EORV, OP_ANDV, OP_ORQV, OP_EORQV, OP_ANDQV};
        int decoded = 0;
        for (int opcode = 0; opcode < 32; opcode++) {
            final int candidate = opcode;
            boolean isAllocated = java.util.Arrays.stream(allocated).anyMatch(a -> a == candidate);
            boolean decodes = !refused(SVE2P1, word(1, opcode));
            assertEquals(isAllocated, decodes, "opcode " + Integer.toHexString(opcode));
            if (decodes) {
                decoded++;
            }
        }
        assertEquals(19, decoded);
    }

    // ── Reduções escalares ───────────────────────────────────────────────────────────────────────

    static Stream<Arguments> scalarOpcodes() {
        return Stream.of(OP_ORV, OP_EORV, OP_ANDV, OP_SADDV, OP_UADDV, OP_SMAXV, OP_UMAXV, OP_SMINV, OP_UMINV)
                .flatMap(opcode -> Stream.of(0, 1, 2, 3).map(esz -> Arguments.of(opcode, esz)))
                .filter(args -> !((int) args.get()[0] == OP_SADDV && (int) args.get()[1] == 3));
    }

    /// `SADDV`/`UADDV` devolvem 64 bits (`d0`); as demais devolvem `esz` bits, com o resto de `V0`/`Z0` zerado.
    private static BigInteger scalarOracle(int opcode, int esz, long[] values, boolean[] active) {
        BigInteger accumulator = neutral(opcode, esz);
        BigInteger sum = BigInteger.ZERO;
        for (int e = 0; e < values.length; e++) {
            if (!active[e]) {
                continue;
            }
            switch (opcode) {
                case OP_SADDV -> sum = sum.add(signed(values[e], esz));
                case OP_UADDV -> sum = sum.add(unsigned(values[e], esz));
                default -> accumulator = step(opcode, accumulator, unsigned(values[e], esz), esz);
            }
        }
        return opcode == OP_SADDV || opcode == OP_UADDV ? sum.and(mask(3)) : accumulator;
    }

    @ParameterizedTest
    @MethodSource("scalarOpcodes")
    void scalarReductionsMatchTheBigIntegerOracle(int opcode, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 0; seed < 12; seed++) {
                Aarch64Core core = core(SVE, vl);
                Random random = new Random(seed * 131 + esz * 7L + vl + opcode);
                long[] values = randomElements(core, esz, random);
                setElements(core, Z3, esz, values);
                boolean[] active = randomPredicate(core, esz, random, false);
                dirtyDestination(core);
                run(SVE, core, word(esz, opcode));
                boolean wide = opcode == OP_SADDV || opcode == OP_UADDV;
                String label = "op=" + Integer.toHexString(opcode) + " esz=" + esz + " vl=" + vl + " seed=" + seed;
                assertEquals(scalarOracle(opcode, esz, values, active).longValue(), core.scalable().zWord(V0, 0), label);
                assertZeroAbove(core, wide ? 64 : bits(esz), label);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scalarOpcodes")
    void emptyPredicateYieldsTheNeutralValueAtTheElementSize(int opcode, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE, vl);
            setElements(core, Z3, esz, randomElements(core, esz, new Random(5)));
            dirtyDestination(core);
            randomPredicate(core, esz, new Random(9), true); // nenhum ativo (só lixo nos bytes não-baixos)
            run(SVE, core, word(esz, opcode));
            assertEquals(neutral(opcode, esz).longValue(), core.scalable().zWord(V0, 0),
                    "op=" + Integer.toHexString(opcode) + " esz=" + esz + " vl=" + vl);
            assertZeroAbove(core, opcode == OP_SADDV || opcode == OP_UADDV ? 64 : bits(esz), "vazio");
        }
    }

    @Test
    void neutralValuesAreTheTabulatedOnesNotAllOnesInSixtyFourBits() {
        // ANDV b0 com predicado vazio devolve 0xFF (não 0xFFFF_FFFF_FFFF_FFFF); SMAXV h0 devolve 0x8000; SMINV s0 0x7FFFFFFF.
        int[][] cases = {{OP_ANDV, 0, 0xFF}, {OP_ANDV, 1, 0xFFFF}, {OP_UMINV, 2, -1}, {OP_SMAXV, 1, 0x8000},
                {OP_SMINV, 2, 0x7FFF_FFFF}, {OP_SMAXV, 0, 0x80}, {OP_SMINV, 0, 0x7F}, {OP_ORV, 3, 0}, {OP_UADDV, 0, 0}};
        for (int[] c : cases) {
            Aarch64Core core = core(SVE, 256);
            dirtyDestination(core);
            run(SVE, core, word(c[1], c[0])); // P2 = 0: nenhum elemento ativo
            long expected = c[2] & 0xFFFF_FFFFL;
            assertEquals(expected, core.scalable().zWord(V0, 0), "op=" + Integer.toHexString(c[0]) + " esz=" + c[1]);
        }
    }

    @Test
    void uaddvOfSixtyFourFullBytesSumsIn64BitsWithoutTruncatingToTheElementSize() {
        Aarch64Core core = core(SVE, 512);
        for (int w = 0; w < 8; w++) {
            core.scalable().setZWord(Z3, w, -1L); // 64 bytes 0xFF
            core.scalable().setPWord(P2, 0, -1L);
        }
        run(SVE, core, word(0, OP_UADDV));
        assertEquals(64 * 0xFFL, core.scalable().zWord(V0, 0));
    }

    @Test
    void saddvSumsSignedElementsAndUaddvUnsignedOnesOnTheSameData() {
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z3, 1, new long[]{0xFFFF, 0x0001, 0x8000, 0x7FFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        core.scalable().setPWord(P2, 0, -1L);
        run(SVE, core, word(1, OP_SADDV));
        assertEquals(-1L + 1 - 32768 + 32767, core.scalable().zWord(V0, 0));
        run(SVE, core, word(1, OP_UADDV));
        assertEquals(0xFFFFL + 1 + 0x8000 + 0x7FFF, core.scalable().zWord(V0, 0));
    }

    @Test
    void inactiveElementsNeverContribute() {
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z3, 3, new long[]{1, 2, 4, 8});
        core.scalable().setPWord(P2, 0, 0x0000_0001_0000_0001L); // bytes 0 e 32: o byte 32 está fora do VL de 256 bits
        run(SVE, core, word(3, OP_UADDV));
        assertEquals(1L, core.scalable().zWord(V0, 0), "só o elemento 0");
        core.scalable().setPWord(P2, 0, 0x0000_0000_0001_0101L); // bytes 0, 8 e 16 → elementos 0, 1 e 2 (1 + 2 + 4)
        run(SVE, core, word(3, OP_UADDV));
        assertEquals(7L, core.scalable().zWord(V0, 0));
    }

    // ── Reduções por segmento ────────────────────────────────────────────────────────────────────

    static Stream<Arguments> segmentOpcodes() {
        return Stream.of(OP_ORQV, OP_EORQV, OP_ANDQV, OP_ADDQV, OP_SMAXQV, OP_SMINQV, OP_UMAXQV, OP_UMINQV)
                .flatMap(opcode -> Stream.of(0, 1, 2, 3).map(esz -> Arguments.of(opcode, esz)));
    }

    private static long[] segmentOracle(int opcode, int esz, long[] values, boolean[] active) {
        int perSegment = 16 >> esz;
        long[] lanes = new long[perSegment];
        for (int lane = 0; lane < perSegment; lane++) {
            BigInteger accumulator = neutral(opcode, esz);
            for (int e = lane; e < values.length; e += perSegment) {
                if (active[e]) {
                    accumulator = step(opcode, accumulator, unsigned(values[e], esz), esz);
                }
            }
            lanes[lane] = accumulator.longValue();
        }
        return lanes;
    }

    @ParameterizedTest
    @MethodSource("segmentOpcodes")
    void segmentReductionsProduceOneResultPerPositionInsideTheSegment(int opcode, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 0; seed < 12; seed++) {
                Aarch64Core core = core(SVE2P1, vl);
                Random random = new Random(seed * 977 + esz * 13L + vl + opcode);
                long[] values = randomElements(core, esz, random);
                setElements(core, Z3, esz, values);
                boolean[] active = randomPredicate(core, esz, random, false);
                dirtyDestination(core);
                run(SVE2P1, core, word(esz, opcode));
                long[] expected = segmentOracle(opcode, esz, values, active);
                String label = "op=" + Integer.toHexString(opcode) + " esz=" + esz + " vl=" + vl + " seed=" + seed;
                long[] actual = new long[expected.length];
                for (int lane = 0; lane < actual.length; lane++) {
                    int bitOffset = lane * bits(esz);
                    long shifted = core.scalable().zWord(V0, bitOffset / 64) >>> (bitOffset % 64);
                    actual[lane] = bits(esz) == 64 ? shifted : shifted & mask(esz).longValue();
                }
                assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual), label);
                assertZeroAbove(core, 128, label);
            }
        }
    }

    @Test
    void segmentReductionDiffersFromTheScalarOneWhenThereIsMoreThanOneSegment() {
        // 4 segmentos de 4 words, todos ativos, valores 1..16: a soma por posição é {1+5+9+13, 2+6+10+14, ...}.
        Aarch64Core core = core(SVE2P1, 512);
        long[] values = new long[16];
        for (int i = 0; i < values.length; i++) {
            values[i] = i + 1;
        }
        setElements(core, Z3, 2, values);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
        run(SVE2P1, core, word(2, OP_ADDQV));
        assertEquals(28L | (32L << 32), core.scalable().zWord(V0, 0));
        assertEquals(36L | (40L << 32), core.scalable().zWord(V0, 1));
        run(SVE2P1, core, word(2, OP_UADDV));
        assertEquals(136L, core.scalable().zWord(V0, 0), "a escalar soma tudo num escalar só");
        assertNotEquals(28L | (32L << 32), core.scalable().zWord(V0, 0));
    }

    // ── MOVPRFX predicado ────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void predicatedMovprfxCopiesActiveElementsAndZeroesOrKeepsTheInactiveOnes(int esz) {
        for (int vl : new int[]{256, 512}) {
            for (boolean zeroing : new boolean[]{true, false}) {
                for (Aarch64Architecture architecture : new Aarch64Architecture[]{SVE, SVE2}) {
                    Aarch64Core core = core(architecture, vl);
                    Random random = new Random(esz * 31L + vl);
                    long[] destination = randomElements(core, esz, random);
                    long[] source = randomElements(core, esz, random);
                    setElements(core, Z1, esz, destination);
                    setElements(core, Z3, esz, source);
                    boolean[] active = randomPredicate(core, esz, random, false);
                    run(architecture, core, word(esz, zeroing ? OP_MOVPRFX_Z : OP_MOVPRFX_M) | Z1);
                    long[] result = elements(core, Z1, esz);
                    for (int e = 0; e < result.length; e++) {
                        long expected = active[e] ? source[e] : zeroing ? 0L : destination[e];
                        assertEquals(expected, result[e], "esz=" + esz + " vl=" + vl + " zeroing=" + zeroing + " e=" + e);
                    }
                    assertEquals(source.length, elements(core, Z3, esz).length);
                    assertEquals(source[0], elements(core, Z3, esz)[0], "Zn não é alterado");
                }
            }
        }
    }

    @Test
    void movprfxAndAnUnaryDestructiveOperationEqualTheConstructiveZeroingForm() {
        // movprfx z1.s, p2/z, z3.s ; add z1.s, p2/m, z1.s, z3.s   ==   z1 = active ? 2*z3 : 0
        Aarch64Core core = core(SVE, 256);
        Random random = new Random(1);
        long[] source = randomElements(core, 2, random);
        setElements(core, Z3, 2, source);
        setElements(core, Z1, 2, randomElements(core, 2, random));
        boolean[] active = randomPredicate(core, 2, random, false);
        run(SVE, core, word(2, OP_MOVPRFX_Z) | Z1, 0x04800861); // add z1.s, p2/m, z1.s, z3.s
        long[] result = elements(core, Z1, 2);
        for (int e = 0; e < result.length; e++) {
            assertEquals(active[e] ? (source[e] * 2) & 0xFFFF_FFFFL : 0L, result[e], "e=" + e);
        }
    }

    // ── Vetor efetivo, acesso, streaming e blocos ────────────────────────────────────────────────

    @Test
    void reductionsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(SVE, 1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // LEN=1 => VL efetivo 256
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(Z3, w, 1L);
        }
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, 0x0101_0101_0101_0101L);
        }
        run(SVE, core, word(3, OP_UADDV));
        assertEquals(4L, core.scalable().zWord(V0, 0), "só os 4 doublewords do VL efetivo");
    }

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
    @ValueSource(ints = {0x04182860, 0x04012860, 0x041c2860, 0x04102861})
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE2P1, 256);
        core.setSystemRegisterBus(new Cpacr());
        core.scalable().setZWord(V0, 0, 0x1234L);
        run(SVE2P1, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, core.scalable().zWord(V0, 0), "a instrução não executou");
    }

    @Test
    void reductionsAreLegalInStreamingModeAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        for (int w = 0; w < 8; w++) {
            core.scalable().setZWord(Z3, w, 3L);
        }
        core.scalable().setPWord(P2, 0, 0x0101_0101_0101_0101L);
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, word(3, OP_UADDV));
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção");
        assertEquals(24L, core.scalable().zWord(V0, 0), "SVL = 512: 8 doublewords × 3");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        for (int w = 0; w < 4; w++) {
            core.scalable().setZWord(Z3, w, 5L);
        }
        core.scalable().setPWord(P2, 0, 0x0101_0101L);
        core.memory().write32(0, word(3, OP_UADDV));
        core.memory().write32(4, word(3, OP_ORV));
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(5L, core.scalable().zWord(V0, 0), "orv d0 sobrescreve o uaddv (20)");
    }
}
