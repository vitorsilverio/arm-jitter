package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.20 — SVE2 inteiro I (21 encodings): multiply não-predicado, `SADALP`/`UADALP`, unárias `URECPE`/`URSQRTE`/`SQABS`/
/// `SQNEG` (`_m` e `_z`) e pairwise predicado. Palavras conferidas contra `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1`; as `_z` com `armv9.6-a+sve2p2`); registradores fixos: `Zd=Zdn=z1`, `Zn=z2` (multiply)
/// ou `z3` (predicadas), `Zm=z3`, `pg=p2`. O oráculo é escrito com `BigInteger`, sem repetir a aritmética do executor;
/// toda semântica roda em `VL = 256` **e** `VL = 512`, com predicado aleatório e lixo nos bytes não-baixos.
class Aarch64Sve2IntegerTest {
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P2 = 2;
    private static final int UNPREDICATED_BASE = 0x04206000;
    private static final int PREDICATED_BASE = 0x4400A000;
    private static final int ESZ_SHIFT = 22;
    private static final int RM_SHIFT = 16;
    private static final int RN_SHIFT = 5;
    private static final int PG_SHIFT = 10;
    private static final int OPCODE_SHIFT = 10;
    private static final int PREDICATED_OPCODE_SHIFT = 16;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final int SEEDS = 6;

    private static final int OPC_MUL = 0b011000;
    private static final int OPC_PMUL = 0b011001;
    private static final int OPC_SMULH = 0b011010;
    private static final int OPC_UMULH = 0b011011;
    private static final int OPC_SQDMULH = 0b011100;
    private static final int OPC_SQRDMULH = 0b011101;
    private static final int PRED_URECPE_M = 0b000000;
    private static final int PRED_URSQRTE_M = 0b000001;
    private static final int PRED_SADALP = 0b000100;
    private static final int PRED_UADALP = 0b000101;
    private static final int PRED_SQABS_M = 0b001000;
    private static final int PRED_SQNEG_M = 0b001001;
    private static final int PRED_ZEROING = 0b000010;
    private static final int PRED_ADDP = 0b010001;
    private static final int PRED_SMAXP = 0b010100;
    private static final int PRED_UMAXP = 0b010101;
    private static final int PRED_SMINP = 0b010110;
    private static final int PRED_UMINP = 0b010111;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P2 = Aarch64Architecture.extending(SVE2, "teste-SVE2p2",
            Aarch64Feature.SVE2_2);

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x400L);
        return core;
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(architecture).step(core);
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

    private static int bits(int esz) {
        return 8 << esz;
    }

    private static int elementCount(Aarch64Core core, int esz) {
        return core.vectorLengthBytes() >> esz;
    }

    private static BigInteger mask(int esz) {
        return BigInteger.ONE.shiftLeft(bits(esz)).subtract(BigInteger.ONE);
    }

    private static BigInteger unsigned(long value, int esz) {
        return new BigInteger(Long.toUnsignedString(value)).and(mask(esz));
    }

    private static long truncate(BigInteger value, int esz) {
        return value.and(mask(esz)).longValue();
    }

    private static BigInteger saturate(BigInteger value, int esz) {
        BigInteger max = BigInteger.ONE.shiftLeft(bits(esz) - 1).subtract(BigInteger.ONE);
        BigInteger min = max.negate().subtract(BigInteger.ONE);
        return value.max(min).min(max);
    }

    private static long[] elements(Aarch64Core core, int reg, int esz) {
        long[] out = new long[elementCount(core, esz)];
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

    /// Aleatórios com viés para os extremos (0, máximo, mínimo, ±1), onde a saturação acontece.
    private static long[] randomElements(Aarch64Core core, int esz, Random random) {
        long[] out = new long[elementCount(core, esz)];
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

    /// Liga em `P2` o bit do byte mais baixo de cada elemento ativo e um lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int esz, Random random) {
        boolean[] active = new boolean[elementCount(core, esz)];
        for (int e = 0; e < active.length; e++) {
            active[e] = random.nextBoolean();
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

    private static int unpredicatedWord(int esz, int opcode, int rd, int rn, int rm) {
        return UNPREDICATED_BASE | (esz << ESZ_SHIFT) | (rm << RM_SHIFT) | (opcode << OPCODE_SHIFT) | (rn << RN_SHIFT)
                | rd;
    }

    private static int predicatedWord(int esz, int opcode, int rd, int field) {
        return PREDICATED_BASE | (esz << ESZ_SHIFT) | (opcode << PREDICATED_OPCODE_SHIFT) | (P2 << PG_SHIFT)
                | (field << RN_SHIFT) | rd;
    }

    // ── Decoder ──────────────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> assemblerWords() {
        return Stream.of(
                Arguments.of(0x04236041, Ir64Op.SveIntegerUnpredicated.Op.MUL),
                Arguments.of(0x04e36041, Ir64Op.SveIntegerUnpredicated.Op.MUL),
                Arguments.of(0x04636841, Ir64Op.SveIntegerUnpredicated.Op.SMULH),
                Arguments.of(0x04a36c41, Ir64Op.SveIntegerUnpredicated.Op.UMULH),
                Arguments.of(0x04236441, Ir64Op.SveIntegerUnpredicated.Op.PMUL),
                Arguments.of(0x04a37041, Ir64Op.SveIntegerUnpredicated.Op.SQDMULH),
                Arguments.of(0x04e37441, Ir64Op.SveIntegerUnpredicated.Op.SQRDMULH),
                Arguments.of(0x4444a861, Ir64Op.SveIntegerPredicated.Op.SADALP),
                Arguments.of(0x4485a861, Ir64Op.SveIntegerPredicated.Op.UADALP),
                Arguments.of(0x44c5a861, Ir64Op.SveIntegerPredicated.Op.UADALP),
                Arguments.of(0x4480a861, Ir64Op.SveIntegerPredicated.Op.URECPE),
                Arguments.of(0x4481a861, Ir64Op.SveIntegerPredicated.Op.URSQRTE),
                Arguments.of(0x4408a861, Ir64Op.SveIntegerPredicated.Op.SQABS),
                Arguments.of(0x44c9a861, Ir64Op.SveIntegerPredicated.Op.SQNEG),
                Arguments.of(0x4411a861, Ir64Op.SveIntegerPredicated.Op.ADDP),
                Arguments.of(0x4454a861, Ir64Op.SveIntegerPredicated.Op.SMAXP),
                Arguments.of(0x4495a861, Ir64Op.SveIntegerPredicated.Op.UMAXP),
                Arguments.of(0x44d6a861, Ir64Op.SveIntegerPredicated.Op.SMINP),
                Arguments.of(0x4417a861, Ir64Op.SveIntegerPredicated.Op.UMINP));
    }

    @ParameterizedTest
    @MethodSource("assemblerWords")
    void assemblerWordsDecodeUnderSve2AndAreRefusedUnderPlainSve(int word, Enum<?> expected) {
        Ir64Op decoded = decodeOrNull(SVE2, word);
        assertTrue(decoded != null, "decodifica sob SVE2");
        Enum<?> actual = decoded instanceof Ir64Op.SveIntegerUnpredicated u ? u.op()
                : ((Ir64Op.SveIntegerPredicated) decoded).op();
        assertEquals(expected, actual);
        assertEquals(null, decodeOrNull(SVE, word), "recusada sob SVE puro (G8)");
    }

    static Stream<Arguments> zeroingWords() {
        return Stream.of(
                Arguments.of(0x4482a861, Ir64Op.SveIntegerPredicated.Op.URECPE),
                Arguments.of(0x4483a861, Ir64Op.SveIntegerPredicated.Op.URSQRTE),
                Arguments.of(0x440aa861, Ir64Op.SveIntegerPredicated.Op.SQABS),
                Arguments.of(0x44cba861, Ir64Op.SveIntegerPredicated.Op.SQNEG));
    }

    @ParameterizedTest
    @MethodSource("zeroingWords")
    void zeroingFormsNeedSve2p2(int word, Ir64Op.SveIntegerPredicated.Op expected) {
        Ir64Op.SveIntegerPredicated decoded = (Ir64Op.SveIntegerPredicated) decodeOrNull(SVE2P2, word);
        assertEquals(expected, decoded.op());
        assertTrue(decoded.zeroing());
        assertEquals(null, decodeOrNull(SVE2, word), "`_z` não é SVE2, é SVE2p2");
        assertEquals(null, decodeOrNull(SVE, word));
    }

    /// Varre TODO o espaço `esz × bits[15:10]` do prefixo `0x04` (`bit 21 = 1`, `bits[15:12] = 011x`): só as 21 linhas
    /// (5 opcodes × 4 `esz` + `PMUL` byte) decodificam, e só sob SVE2 — o decoder da B17.5 não engole nem deixa
    /// escapar nenhuma outra palavra (Achado 5 da spec).
    @Test
    void unpredicatedSpaceHoldsExactly21Encodings() {
        int decoded = 0;
        for (int esz = 0; esz < 4; esz++) {
            for (int opcode = 0b011000; opcode <= 0b011111; opcode++) {
                Ir64Op op = decodeOrNull(SVE2, unpredicatedWord(esz, opcode, Z1, Z2, Z3));
                if (op != null) {
                    decoded++;
                    assertTrue(op instanceof Ir64Op.SveIntegerUnpredicated, "opcode " + opcode);
                }
                assertEquals(null, decodeOrNull(SVE, unpredicatedWord(esz, opcode, Z1, Z2, Z3)));
            }
        }
        assertEquals(21, decoded);
    }

    /// O mesmo para o prefixo `0x44`, `bits[15:13] = 101`, `bits[21:16]` inteiros: 36 linhas sob SVE2 (as `_m`) e 46 sob
    /// SVE2p2 (mais as 10 `_z`); nenhuma outra palavra vira uma das operações novas.
    @Test
    void predicatedSpaceHoldsExactly36And46Encodings() {
        assertEquals(36, countNewPredicated(SVE2));
        assertEquals(46, countNewPredicated(SVE2P2));
        assertEquals(0, countNewPredicated(SVE));
    }

    private static int countNewPredicated(Aarch64Architecture architecture) {
        int count = 0;
        for (int esz = 0; esz < 4; esz++) {
            for (int opcode = 0; opcode < 64; opcode++) {
                Ir64Op op = decodeOrNull(architecture, predicatedWord(esz, opcode, Z1, Z3));
                if (op instanceof Ir64Op.SveIntegerPredicated p && isNew(p.op())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isNew(Ir64Op.SveIntegerPredicated.Op op) {
        return switch (op) {
            case SQABS, SQNEG, URECPE, URSQRTE, SADALP, UADALP, ADDP, SMAXP, UMAXP, SMINP, UMINP -> true;
            default -> false;
        };
    }

    @Test
    void esizeRestrictionsAreRefused() {
        assertEquals(null, decodeOrNull(SVE2, unpredicatedWord(1, OPC_PMUL, Z1, Z2, Z3)), "PMUL só existe em byte");
        assertEquals(null, decodeOrNull(SVE2, predicatedWord(0, PRED_SADALP, Z1, Z3)));
        assertEquals(null, decodeOrNull(SVE2, predicatedWord(0, PRED_UADALP, Z1, Z3)));
        for (int esz : new int[] {0, 1, 3}) {
            assertEquals(null, decodeOrNull(SVE2, predicatedWord(esz, PRED_URECPE_M, Z1, Z3)));
            assertEquals(null, decodeOrNull(SVE2, predicatedWord(esz, PRED_URSQRTE_M, Z1, Z3)));
            assertEquals(null, decodeOrNull(SVE2P2, predicatedWord(esz, PRED_URECPE_M | PRED_ZEROING, Z1, Z3)));
        }
    }

    /// Campos: `SADALP` acumula em `Zda` (`rm = rd`); a pairwise é destrutiva (`rn = rd`, `Zm` em `9:5`).
    @Test
    void operandsFollowTheFormats() {
        Ir64Op.SveIntegerPredicated accumulate = (Ir64Op.SveIntegerPredicated) decodeOrNull(SVE2, 0x4444a861);
        assertEquals(1, accumulate.rd());
        assertEquals(3, accumulate.rn());
        assertEquals(1, accumulate.rm());
        assertEquals(2, accumulate.pg());
        Ir64Op.SveIntegerPredicated pairwise = (Ir64Op.SveIntegerPredicated) decodeOrNull(SVE2, 0x4411a861);
        assertEquals(1, pairwise.rd());
        assertEquals(1, pairwise.rn());
        assertEquals(3, pairwise.rm());
    }

    // ── Multiply não-predicado ───────────────────────────────────────────────────────────────────

    private static void checkMultiply(int opcode, int esz, BiFunction<BigInteger, BigInteger, BigInteger> oracle,
            String label) {
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                Aarch64Core core = core(SVE2, vl);
                Random random = new Random(seed * 131 + esz * 7L + vl);
                setElements(core, Z2, esz, randomElements(core, esz, random));
                setElements(core, Z3, esz, randomElements(core, esz, random));
                setElements(core, Z1, esz, randomElements(core, esz, random));
                long[] n = elements(core, Z2, esz);
                long[] m = elements(core, Z3, esz);
                run(SVE2, core, unpredicatedWord(esz, opcode, Z1, Z2, Z3));
                long[] d = elements(core, Z1, esz);
                for (int e = 0; e < d.length; e++) {
                    assertEquals(truncate(oracle.apply(unsigned(n[e], esz), unsigned(m[e], esz)), esz), d[e],
                            label + " esz=" + esz + " vl=" + vl + " e=" + e + " n=" + Long.toHexString(n[e])
                                    + " m=" + Long.toHexString(m[e]));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void mulKeepsTheLowHalf(int esz) {
        checkMultiply(OPC_MUL, esz, BigInteger::multiply, "MUL");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void smulhKeepsTheSignedHighHalf(int esz) {
        checkMultiply(OPC_SMULH, esz, (n, m) -> signedOf(n, esz).multiply(signedOf(m, esz)).shiftRight(bits(esz)),
                "SMULH");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void umulhKeepsTheUnsignedHighHalf(int esz) {
        checkMultiply(OPC_UMULH, esz, (n, m) -> n.multiply(m).shiftRight(bits(esz)), "UMULH");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void sqdmulhDoublesAndSaturates(int esz) {
        checkMultiply(OPC_SQDMULH, esz, (n, m) -> saturate(
                signedOf(n, esz).multiply(signedOf(m, esz)).shiftLeft(1).shiftRight(bits(esz)), esz), "SQDMULH");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void sqrdmulhRoundsAndSaturates(int esz) {
        BigInteger round = BigInteger.ONE.shiftLeft(bits(esz) - 1);
        checkMultiply(OPC_SQRDMULH, esz, (n, m) -> saturate(
                signedOf(n, esz).multiply(signedOf(m, esz)).shiftLeft(1).add(round).shiftRight(bits(esz)), esz),
                "SQRDMULH");
    }

    private static BigInteger signedOf(BigInteger unsignedValue, int esz) {
        return unsignedValue.testBit(bits(esz) - 1) ? unsignedValue.subtract(BigInteger.ONE.shiftLeft(bits(esz)))
                : unsignedValue;
    }

    /// `PMUL` é o produto polinomial (`GF(2)`, sem carry) truncado ao byte: oráculo escrito aqui e cruzado com o
    /// `polynomialMultiply8` do `PMULL` do AdvSIMD (a mesma operação, Achado 2).
    @Test
    void pmulMatchesThePolynomialProductOfPmull() {
        checkMultiply(OPC_PMUL, 0, (n, m) -> {
            int result = 0;
            for (int i = 0; i < 8; i++) {
                if (m.testBit(i)) {
                    result ^= n.intValue() << i;
                }
            }
            long viaPmull = AdvSimdLanes.polynomialMultiply8(n.longValue(), m.longValue()) & 0xFF;
            assertEquals(viaPmull, result & 0xFF);
            return BigInteger.valueOf(result & 0xFF);
        }, "PMUL");
    }

    @Test
    void multiplyWorksWithDestinationEqualToSources() {
        Aarch64Core core = core(SVE2, 256);
        long[] values = randomElements(core, 3, new Random(7));
        setElements(core, Z1, 3, values);
        run(SVE2, core, unpredicatedWord(3, OPC_MUL, Z1, Z1, Z1));
        long[] d = elements(core, Z1, 3);
        for (int e = 0; e < d.length; e++) {
            assertEquals(values[e] * values[e], d[e]);
        }
    }

    // ── Predicadas: execução ─────────────────────────────────────────────────────────────────────

    /// Unária: `Z1 = op(Z3)` nos elementos ativos; inativo preservado (`_m`) ou zero (`_z`).
    private static void checkUnary(int opcode, int esz, boolean zeroing, Function<BigInteger, BigInteger> oracle,
            String label) {
        Aarch64Architecture architecture = zeroing ? SVE2P2 : SVE2;
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                Aarch64Core core = core(architecture, vl);
                Random random = new Random(seed * 17 + esz * 7L + vl);
                setElements(core, Z1, esz, randomElements(core, esz, random));
                setElements(core, Z3, esz, randomElements(core, esz, random));
                boolean[] active = randomPredicate(core, esz, random);
                long[] d0 = elements(core, Z1, esz);
                long[] n = elements(core, Z3, esz);
                run(architecture, core, predicatedWord(esz, zeroing ? opcode | PRED_ZEROING : opcode, Z1, Z3));
                long[] d = elements(core, Z1, esz);
                for (int e = 0; e < d.length; e++) {
                    long expected = !active[e] ? (zeroing ? 0L : d0[e])
                            : truncate(oracle.apply(unsigned(n[e], esz)), esz);
                    assertEquals(expected, d[e], label + " esz=" + esz + " vl=" + vl + " e=" + e + " n="
                            + Long.toHexString(n[e]) + (active[e] ? " (ativo)" : " (inativo)"));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void sqabsSaturatesTheMinimum(int esz) {
        for (boolean zeroing : new boolean[] {false, true}) {
            checkUnary(PRED_SQABS_M, esz, zeroing, n -> saturate(signedOf(n, esz).abs(), esz), "SQABS");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void sqnegSaturatesTheMinimum(int esz) {
        for (boolean zeroing : new boolean[] {false, true}) {
            checkUnary(PRED_SQNEG_M, esz, zeroing, n -> saturate(signedOf(n, esz).negate(), esz), "SQNEG");
        }
    }

    @Test
    void sqabsOfTheMinimumIsTheMaximum() {
        for (int esz = 0; esz < 4; esz++) {
            Aarch64Core core = core(SVE2, 256);
            long minimum = 1L << (bits(esz) - 1);
            long[] source = new long[elementCount(core, esz)];
            java.util.Arrays.fill(source, minimum);
            setElements(core, Z3, esz, source);
            for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
                core.scalable().setPWord(P2, w, -1L);
            }
            run(SVE2, core, predicatedWord(esz, PRED_SQABS_M, Z1, Z3));
            assertEquals(minimum - 1, elements(core, Z1, esz)[0], "esz=" + esz);
        }
    }

    /// Vetores transcritos do manual (`RecipEstimate`/`RecipSqrtEstimate` são tabelas, não `1/x`): o elemento é sempre
    /// de 32 bits.
    @Test
    void reciprocalEstimatesMatchTheManualTables() {
        long[][] recpe = {
            {0x00000000L, 0xFFFFFFFFL}, {0x7FFFFFFFL, 0xFFFFFFFFL}, {0x80000000L, 0xFF800000L},
            {0xFFFFFFFFL, 0x80000000L}};
        long[][] rsqrte = {
            {0x00000000L, 0xFFFFFFFFL}, {0x3FFFFFFFL, 0xFFFFFFFFL}, {0x40000000L, 0xFF800000L},
            {0x80000000L, 0xB4800000L}, {0xFFFFFFFFL, 0x80000000L}};
        assertEstimates(PRED_URECPE_M, recpe);
        assertEstimates(PRED_URSQRTE_M, rsqrte);
    }

    private static void assertEstimates(int opcode, long[][] vectors) {
        Aarch64Core core = core(SVE2, 256);
        long[] source = new long[elementCount(core, 2)];
        for (int i = 0; i < source.length; i++) {
            source[i] = vectors[i % vectors.length][0];
        }
        setElements(core, Z3, 2, source);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
        run(SVE2, core, predicatedWord(2, opcode, Z1, Z3));
        long[] d = elements(core, Z1, 2);
        for (int i = 0; i < d.length; i++) {
            assertEquals(vectors[i % vectors.length][1], d[i], "entrada " + Long.toHexString(source[i]));
        }
    }

    @Test
    void reciprocalEstimatesHonourMergingAndZeroing() {
        checkUnary(PRED_URECPE_M, 2, false, n -> BigInteger.valueOf(
                AdvSimdLanes.unsignedRecipEstimate32(n.longValue())), "URECPE_m");
        checkUnary(PRED_URECPE_M, 2, true, n -> BigInteger.valueOf(
                AdvSimdLanes.unsignedRecipEstimate32(n.longValue())), "URECPE_z");
        checkUnary(PRED_URSQRTE_M, 2, false, n -> BigInteger.valueOf(
                AdvSimdLanes.unsignedRSqrtEstimate32(n.longValue())), "URSQRTE_m");
        checkUnary(PRED_URSQRTE_M, 2, true, n -> BigInteger.valueOf(
                AdvSimdLanes.unsignedRSqrtEstimate32(n.longValue())), "URSQRTE_z");
    }

    /// `SADALP`/`UADALP`: `Zda += soma das duas metades de Zn`, com o destino NÃO zerado (Armadilha 1).
    private static void checkAccumulate(int opcode, int esz, boolean signedHalves) {
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                Random random = new Random(seed * 53 + esz * 7L + vl);
                Aarch64Core core = core(SVE2, vl);
                setElements(core, Z1, esz, randomElements(core, esz, random));
                setElements(core, Z3, esz, randomElements(core, esz, random));
                boolean[] active = randomPredicate(core, esz, random);
                long[] acc = elements(core, Z1, esz);
                long[] n = elements(core, Z3, esz);
                run(SVE2, core, predicatedWord(esz, opcode, Z1, Z3));
                long[] d = elements(core, Z1, esz);
                int half = bits(esz) / 2;
                for (int e = 0; e < d.length; e++) {
                    BigInteger value = unsigned(n[e], esz);
                    BigInteger low = value.and(BigInteger.ONE.shiftLeft(half).subtract(BigInteger.ONE));
                    BigInteger high = value.shiftRight(half);
                    if (signedHalves) {
                        low = halfSigned(low, half);
                        high = halfSigned(high, half);
                    }
                    long expected = active[e] ? truncate(unsigned(acc[e], esz).add(low).add(high), esz) : acc[e];
                    assertEquals(expected, d[e], "esz=" + esz + " vl=" + vl + " e=" + e + " n="
                            + Long.toHexString(n[e]) + " acc=" + Long.toHexString(acc[e]));
                }
            }
        }
    }

    private static BigInteger halfSigned(BigInteger value, int half) {
        return value.testBit(half - 1) ? value.subtract(BigInteger.ONE.shiftLeft(half)) : value;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void sadalpAccumulatesSignedPairs(int esz) {
        checkAccumulate(PRED_SADALP, esz, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void uadalpAccumulatesUnsignedPairs(int esz) {
        checkAccumulate(PRED_UADALP, esz, false);
    }

    // ── Pairwise ─────────────────────────────────────────────────────────────────────────────────

    /// `Zdn[2k] = op(Zdn[2k], Zdn[2k+1])` e `Zdn[2k+1] = op(Zm[2k], Zm[2k+1])`, cada um sob o próprio bit de predicado
    /// (o do elemento de DESTINO); inativo preservado. `sameRegisters` faz `Zm = Zdn` (todas as fontes lidas antes de
    /// qualquer escrita).
    private static void checkPairwise(int opcode, int esz, BiFunction<BigInteger, BigInteger, BigInteger> oracle,
            boolean sameRegisters) {
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                Random random = new Random(seed * 71 + esz * 7L + vl);
                Aarch64Core core = core(SVE2, vl);
                setElements(core, Z1, esz, randomElements(core, esz, random));
                setElements(core, Z3, esz, randomElements(core, esz, random));
                boolean[] active = randomPredicate(core, esz, random);
                long[] n = elements(core, Z1, esz);
                long[] m = sameRegisters ? n : elements(core, Z3, esz);
                run(SVE2, core, predicatedWord(esz, opcode, Z1, sameRegisters ? Z1 : Z3));
                long[] d = elements(core, Z1, esz);
                for (int e = 0; e < d.length; e++) {
                    int even = e & ~1;
                    long[] source = (e & 1) == 0 ? n : m;
                    long expected = active[e]
                            ? truncate(oracle.apply(unsigned(source[even], esz), unsigned(source[even + 1], esz)), esz)
                            : n[e];
                    assertEquals(expected, d[e], "esz=" + esz + " vl=" + vl + " e=" + e + " same=" + sameRegisters
                            + (active[e] ? " (ativo)" : " (inativo)"));
                }
            }
        }
    }

    private static void checkPairwiseBothForms(int opcode, int esz, BiFunction<BigInteger, BigInteger, BigInteger> oracle) {
        checkPairwise(opcode, esz, oracle, false);
        checkPairwise(opcode, esz, oracle, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void addpAddsAdjacentElementsPerSegment(int esz) {
        checkPairwiseBothForms(PRED_ADDP, esz, BigInteger::add);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void smaxpAndSminpUseSignedOrder(int esz) {
        checkPairwiseBothForms(PRED_SMAXP, esz, (a, b) -> signedOf(a, esz).max(signedOf(b, esz)));
        checkPairwiseBothForms(PRED_SMINP, esz, (a, b) -> signedOf(a, esz).min(signedOf(b, esz)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void umaxpAndUminpUseUnsignedOrder(int esz) {
        checkPairwiseBothForms(PRED_UMAXP, esz, BigInteger::max);
        checkPairwiseBothForms(PRED_UMINP, esz, BigInteger::min);
    }

    /// Com o predicado todo ativo, a pairwise de `VL = 512` combina só elementos do mesmo par — cruzar segmentos de
    /// 128 bits seria um erro que o oráculo acima também pegaria, mas aqui ele fica explícito com valores conhecidos.
    @Test
    void addpPairsNeverCrossElementBoundariesOfThePair() {
        Aarch64Core core = core(SVE2, 512);
        long[] n = new long[64];
        long[] m = new long[64];
        for (int i = 0; i < 64; i++) {
            n[i] = i + 1;
            m[i] = 100 + i;
        }
        setElements(core, Z1, 0, n);
        setElements(core, Z3, 0, m);
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
        run(SVE2, core, predicatedWord(0, PRED_ADDP, Z1, Z3));
        long[] d = elements(core, Z1, 0);
        for (int k = 0; k < 32; k++) {
            assertEquals((n[2 * k] + n[2 * k + 1]) & 0xFF, d[2 * k]);
            assertEquals((m[2 * k] + m[2 * k + 1]) & 0xFF, d[2 * k + 1]);
        }
    }
}
