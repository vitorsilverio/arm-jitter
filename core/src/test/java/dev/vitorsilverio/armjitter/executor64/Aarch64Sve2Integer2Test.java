package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// B17.21a — SVE2 inteiro II, metade "predicadas + Accumulate" (46 encodings): shift por vetor saturante/arredondado
/// (12), halving (8), saturating add/sub (8) e `#### SVE2 Accumulate` (18). Palavras conferidas contra
/// `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1`); registradores fixos `Zd = Zdn = Zda = z1`, `Zn = z2`,
/// `Zm = z3`, `pg = p2`. O oráculo é escrito com `BigInteger`, sem repetir a aritmética do executor, em `VL = 256` e `512`,
/// com predicado aleatório e lixo nos bytes não-baixos.
class Aarch64Sve2Integer2Test {
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P2 = 2;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final int SEEDS = 6;

    private static final int VECTOR_BASE = 0x44008000;
    private static final int COMPLEX_ADD_BASE = 0x4500D800;
    private static final int ABS_DIFF_LONG_BASE = 0x4500C000;
    private static final int CARRY_BASE = 0x4500D000;
    private static final int SHIFT_ACCUMULATE_BASE = 0x4500E000;
    private static final int INSERT_BASE = 0x4500F000;
    private static final int ABS_DIFF_BASE = 0x4500F800;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);

    /// Oráculo: recebe `n` e `m` como valores SEM sinal do elemento e devolve o resultado matemático (qualquer inteiro);
    /// o teste o reduz módulo `2^bits`.
    private interface Oracle {
        BigInteger apply(BigInteger n, BigInteger m, int esz);
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), SVE2, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, 0x400L);
        return core;
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(SVE2).step(core);
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

    private static BigInteger modulus(int esz) {
        return BigInteger.ONE.shiftLeft(bits(esz));
    }

    private static BigInteger unsigned(long value, int esz) {
        return new BigInteger(Long.toUnsignedString(value)).mod(modulus(esz));
    }

    private static BigInteger signed(BigInteger unsigned, int esz) {
        return unsigned.testBit(bits(esz) - 1) ? unsigned.subtract(modulus(esz)) : unsigned;
    }

    private static BigInteger maxSigned(int esz) {
        return BigInteger.ONE.shiftLeft(bits(esz) - 1).subtract(BigInteger.ONE);
    }

    private static BigInteger minSigned(int esz) {
        return maxSigned(esz).negate().subtract(BigInteger.ONE);
    }

    private static BigInteger clampSigned(BigInteger value, int esz) {
        return value.max(minSigned(esz)).min(maxSigned(esz));
    }

    private static BigInteger clampUnsigned(BigInteger value, int esz) {
        return value.max(BigInteger.ZERO).min(modulus(esz).subtract(BigInteger.ONE));
    }

    private static long truncate(BigInteger value, int esz) {
        return value.mod(modulus(esz)).longValue();
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

    /// Aleatórios com viés para os extremos, onde a saturação e os estouros acontecem.
    private static long[] randomElements(Aarch64Core core, int esz, Random random) {
        long[] out = new long[elementCount(core, esz)];
        long max = bits(esz) == 64 ? -1L : (1L << bits(esz)) - 1;
        long half = 1L << (bits(esz) - 1);
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (random.nextInt(9)) {
                case 0 -> 0L;
                case 1 -> max;
                case 2 -> half;
                case 3 -> half - 1;
                case 4 -> 1L;
                case 5 -> random.nextInt(256); // quantidades de shift pequenas, positivas e negativas
                default -> random.nextLong();
            } & max;
        }
        return out;
    }

    /// Liga em `P2` o bit do byte mais baixo de cada elemento ativo e lixo aleatório nos demais bytes.
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

    private static int predicatedWord(int esz, int opcode, int rd, int field) {
        return VECTOR_BASE | (esz << 22) | (opcode << 16) | (P2 << 10) | (field << 5) | rd;
    }

    // ── Decoder: palavras do assembler ───────────────────────────────────────────────────────────

    static Stream<Arguments> assemblerWords() {
        return Stream.of(
                Arguments.of(0x44028861, "SRSHL"), Arguments.of(0x44c28861, "SRSHL"), Arguments.of(0x44468861, "SRSHL"),
                Arguments.of(0x44838861, "URSHL"), Arguments.of(0x44878861, "URSHL"),
                Arguments.of(0x44088861, "SQSHL_VECTOR"), Arguments.of(0x440c8861, "SQSHL_VECTOR"),
                Arguments.of(0x44098861, "UQSHL_VECTOR"), Arguments.of(0x440d8861, "UQSHL_VECTOR"),
                Arguments.of(0x440a8861, "SQRSHL"), Arguments.of(0x440e8861, "SQRSHL"),
                Arguments.of(0x440b8861, "UQRSHL"), Arguments.of(0x440f8861, "UQRSHL"),
                Arguments.of(0x44108861, "SHADD"), Arguments.of(0x44118861, "UHADD"),
                Arguments.of(0x44128861, "SHSUB"), Arguments.of(0x44168861, "SHSUB"),
                Arguments.of(0x44138861, "UHSUB"), Arguments.of(0x44178861, "UHSUB"),
                Arguments.of(0x44148861, "SRHADD"), Arguments.of(0x44158861, "URHADD"),
                Arguments.of(0x44188861, "SQADD"), Arguments.of(0x44198861, "UQADD"),
                Arguments.of(0x441a8861, "SQSUB"), Arguments.of(0x441e8861, "SQSUB"),
                Arguments.of(0x441b8861, "UQSUB"), Arguments.of(0x441f8861, "UQSUB"),
                Arguments.of(0x441c8861, "SUQADD"), Arguments.of(0x441d8861, "USQADD"),
                Arguments.of(0x4540d861, "CADD"), Arguments.of(0x4540dc61, "CADD"),
                Arguments.of(0x4541d861, "SQCADD"), Arguments.of(0x45c1dc61, "SQCADD"),
                Arguments.of(0x4543c041, "SABAL"), Arguments.of(0x4543c441, "SABAL"),
                Arguments.of(0x4583c841, "UABAL"), Arguments.of(0x45c3cc41, "UABAL"),
                Arguments.of(0x4503d041, "ADCL"), Arguments.of(0x4543d441, "ADCL"),
                Arguments.of(0x4583d041, "SBCL"), Arguments.of(0x45c3d441, "SBCL"),
                Arguments.of(0x450de041, "SSRA"), Arguments.of(0x451be441, "USRA"),
                Arguments.of(0x4559e841, "SRSRA"), Arguments.of(0x459fec41, "URSRA"),
                Arguments.of(0x450df041, "SRI"), Arguments.of(0x45c8f441, "SLI"),
                Arguments.of(0x4543f841, "SABA"), Arguments.of(0x45c3fc41, "UABA"));
    }

    @ParameterizedTest
    @MethodSource("assemblerWords")
    void assemblerWordsDecodeUnderSve2AndAreRefusedUnderPlainSve(int word, String expected) {
        Ir64Op decoded = decodeOrNull(SVE2, word);
        assertNotNull(decoded, "decodifica sob SVE2");
        String actual = decoded instanceof SveIntegerOp64.IntegerUnpredicated u ? u.op().name()
                : ((SveIntegerOp64.IntegerPredicated) decoded).op().name();
        assertEquals(expected, actual);
        assertNull(decodeOrNull(SVE, word), "recusada sob SVE puro (G8)");
    }

    @Test
    void shiftAndInsertImmediatesFollowTheTszimmEncoding() {
        SveIntegerOp64.IntegerUnpredicated ssra = (SveIntegerOp64.IntegerUnpredicated) decodeOrNull(SVE2, 0x450de041);
        assertEquals(0, ssra.esz());
        assertEquals(3L, ssra.imm());
        SveIntegerOp64.IntegerUnpredicated ursra = (SveIntegerOp64.IntegerUnpredicated) decodeOrNull(SVE2, 0x459fec41);
        assertEquals(3, ursra.esz());
        assertEquals(33L, ursra.imm());
        SveIntegerOp64.IntegerUnpredicated sli = (SveIntegerOp64.IntegerUnpredicated) decodeOrNull(SVE2, 0x45c8f441);
        assertEquals(3, sli.esz());
        assertEquals(40L, sli.imm());
    }

    @Test
    void adclSelectsAdcOrSbcByBit23AndTheSizeByBit22() {
        SveIntegerOp64.IntegerUnpredicated adclS = (SveIntegerOp64.IntegerUnpredicated) decodeOrNull(SVE2, 0x4503d041);
        assertEquals(SveIntegerOp64.IntegerUnpredicated.Op.ADCL, adclS.op());
        assertEquals(2, adclS.esz());
        SveIntegerOp64.IntegerUnpredicated sbclD = (SveIntegerOp64.IntegerUnpredicated) decodeOrNull(SVE2, 0x45c3d441);
        assertEquals(SveIntegerOp64.IntegerUnpredicated.Op.SBCL, sbclD.op());
        assertEquals(3, sbclD.esz());
        assertEquals(1L, sbclD.imm(), "T");
    }

    // ── Decoder: varredura dos espaços ───────────────────────────────────────────────────────────

    private static boolean isNewPredicated(SveIntegerOp64.IntegerPredicated.Op op) {
        return switch (op) {
            case SRSHL, URSHL, SQSHL_VECTOR, UQSHL_VECTOR, SQRSHL, UQRSHL, SHADD, UHADD, SHSUB, UHSUB, SRHADD, URHADD,
                    SQADD, UQADD, SQSUB, UQSUB, SUQADD, USQADD -> true;
            default -> false;
        };
    }

    private static boolean isNewAccumulate(SveIntegerOp64.IntegerUnpredicated.Op op) {
        return switch (op) {
            case CADD, SQCADD, SABAL, UABAL, ADCL, SBCL, SSRA, USRA, SRSRA, URSRA, SRI, SLI, SABA, UABA -> true;
            default -> false;
        };
    }

    /// Varre `esz × bits[21:16]` do espaço `0x44`, `bits[15:13] = 100`: as 28 linhas × 4 tamanhos = 112 palavras, e nenhuma
    /// outra. Sob SVE puro nenhuma vira uma das operações novas.
    @Test
    void vectorSpaceHoldsExactly112WordsAndOnlyUnderSve2() {
        int decoded = 0;
        int underPlainSve = 0;
        for (int esz = 0; esz < 4; esz++) {
            for (int opcode = 0; opcode < 64; opcode++) {
                int word = predicatedWord(esz, opcode, Z1, Z3);
                if (decodeOrNull(SVE2, word) instanceof SveIntegerOp64.IntegerPredicated p && isNewPredicated(p.op())) {
                    decoded++;
                }
                if (decodeOrNull(SVE, word) instanceof SveIntegerOp64.IntegerPredicated p && isNewPredicated(p.op())) {
                    underPlainSve++;
                }
            }
        }
        assertEquals(112, decoded);
        assertEquals(0, underPlainSve);
    }

    /// Varre o espaço `0x45` (`bit 21 = 0`, `esz × bits[15:10]`). Com `Zm = z3` (`bits[20:16] = 00011`) são 46 palavras:
    /// `SABAL`/`UABAL` (3 tamanhos × 4 = 12), `ADCL`/`SBCL` (`esz` × `T` = 8), shift-acumula (`tsz ≠ 0`: 3 × 4 = 12),
    /// `SRI`/`SLI` (3 × 2 = 6), `SABA`/`UABA` (4 × 2 = 8). Com `bits[20:16] = 0000x`, as 16 de `CADD`/`SQCADD`.
    @Test
    void accumulateSpaceHoldsExactlyTheExpectedWords() {
        int other = 0;
        int complex = 0;
        int underPlainSve = 0;
        for (int esz = 0; esz < 4; esz++) {
            for (int low = 0; low < 64; low++) {
                for (int rm : new int[] {0, 1, 3}) {
                    int word = 0x45000000 | (esz << 22) | (rm << 16) | (low << 10) | (Z2 << 5) | Z1;
                    if (decodeOrNull(SVE2, word) instanceof SveIntegerOp64.IntegerUnpredicated u && isNewAccumulate(u.op())) {
                        boolean isComplex = u.op() == SveIntegerOp64.IntegerUnpredicated.Op.CADD
                                || u.op() == SveIntegerOp64.IntegerUnpredicated.Op.SQCADD;
                        if (isComplex) {
                            complex++;
                        } else if (rm == 3) {
                            other++;
                        }
                    }
                    if (decodeOrNull(SVE, word) instanceof SveIntegerOp64.IntegerUnpredicated u && isNewAccumulate(u.op())) {
                        underPlainSve++;
                    }
                }
            }
        }
        assertEquals(46, other, "as não-complexas com Zm = z3");
        assertEquals(16, complex);
        assertEquals(0, underPlainSve);
    }

    /// `tsz = 0` (`SSRA`/`SRI`/…) e `esz = 0` em `SABAL` são não alocados; `bit 21 = 1` sai do espaço.
    @Test
    void unallocatedCornersAreRefused() {
        assertNull(decodeOrNull(SVE2, SHIFT_ACCUMULATE_BASE | (Z2 << 5) | Z1), "SSRA com tsz = 0");
        assertNull(decodeOrNull(SVE2, INSERT_BASE | (Z2 << 5) | Z1), "SRI com tsz = 0");
        assertNull(decodeOrNull(SVE2, INSERT_BASE | (1 << 10) | (Z2 << 5) | Z1), "SLI com tsz = 0");
        assertNull(decodeOrNull(SVE2, ABS_DIFF_LONG_BASE | (Z3 << 16) | (Z2 << 5) | Z1), "SABALB com esz = 0");
        assertNull(decodeOrNull(SVE2, ABS_DIFF_LONG_BASE | (1 << 21) | (1 << 22) | (Z3 << 16) | (Z2 << 5) | Z1),
                "bit 21 = 1 sai do espaço");
    }

    // ── Semântica: predicadas ────────────────────────────────────────────────────────────────────

    private static BigInteger shiftOracle(BigInteger n, BigInteger m, int esz, boolean signed, boolean round,
            boolean saturate) {
        BigInteger value = signed ? signed(n, esz) : n;
        int amount = (byte) m.longValue();
        BigInteger result;
        if (amount >= 0) {
            result = value.shiftLeft(amount);
        } else {
            BigInteger biased = round ? value.add(BigInteger.ONE.shiftLeft(-amount - 1)) : value;
            result = biased.shiftRight(-amount);
        }
        if (saturate) {
            return signed ? clampSigned(result, esz) : clampUnsigned(result, esz);
        }
        return result;
    }

    private static BigInteger halvingOracle(BigInteger n, BigInteger m, int esz, boolean signed, int kind) {
        BigInteger a = signed ? signed(n, esz) : n;
        BigInteger b = signed ? signed(m, esz) : m;
        BigInteger sum = switch (kind) {
            case 0 -> a.add(b);
            case 1 -> a.add(b).add(BigInteger.ONE);
            default -> a.subtract(b);
        };
        return sum.shiftRight(1);
    }

    /// (nome, opcode direto, opcode reverso ou -1, oráculo)
    static Stream<Arguments> predicatedOperations() {
        return Stream.of(
                op("SRSHL", 0b000010, 0b000110, (n, m, e) -> shiftOracle(n, m, e, true, true, false)),
                op("URSHL", 0b000011, 0b000111, (n, m, e) -> shiftOracle(n, m, e, false, true, false)),
                op("SQSHL", 0b001000, 0b001100, (n, m, e) -> shiftOracle(n, m, e, true, false, true)),
                op("UQSHL", 0b001001, 0b001101, (n, m, e) -> shiftOracle(n, m, e, false, false, true)),
                op("SQRSHL", 0b001010, 0b001110, (n, m, e) -> shiftOracle(n, m, e, true, true, true)),
                op("UQRSHL", 0b001011, 0b001111, (n, m, e) -> shiftOracle(n, m, e, false, true, true)),
                op("SHADD", 0b010000, -1, (n, m, e) -> halvingOracle(n, m, e, true, 0)),
                op("UHADD", 0b010001, -1, (n, m, e) -> halvingOracle(n, m, e, false, 0)),
                op("SRHADD", 0b010100, -1, (n, m, e) -> halvingOracle(n, m, e, true, 1)),
                op("URHADD", 0b010101, -1, (n, m, e) -> halvingOracle(n, m, e, false, 1)),
                op("SHSUB", 0b010010, 0b010110, (n, m, e) -> halvingOracle(n, m, e, true, 2)),
                op("UHSUB", 0b010011, 0b010111, (n, m, e) -> halvingOracle(n, m, e, false, 2)),
                op("SQADD", 0b011000, -1, (n, m, e) -> clampSigned(signed(n, e).add(signed(m, e)), e)),
                op("UQADD", 0b011001, -1, (n, m, e) -> clampUnsigned(n.add(m), e)),
                op("SQSUB", 0b011010, 0b011110, (n, m, e) -> clampSigned(signed(n, e).subtract(signed(m, e)), e)),
                op("UQSUB", 0b011011, 0b011111, (n, m, e) -> clampUnsigned(n.subtract(m), e)),
                op("SUQADD", 0b011100, -1, (n, m, e) -> clampSigned(signed(n, e).add(m), e)),
                op("USQADD", 0b011101, -1, (n, m, e) -> clampUnsigned(n.add(signed(m, e)), e)));
    }

    private static Arguments op(String name, int direct, int reverse, Oracle oracle) {
        return Arguments.of(name, direct, reverse, oracle);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("predicatedOperations")
    void predicatedOperationMatchesTheBigIntegerOracle(String name, int direct, int reverse, Oracle oracle) {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz < 4; esz++) {
                for (int seed = 0; seed < SEEDS; seed++) {
                    checkPredicated(name, direct, false, oracle, vl, esz, new Random(seed * 131L + esz));
                    if (reverse >= 0) {
                        checkPredicated(name, reverse, true, oracle, vl, esz, new Random(seed * 977L + esz));
                    }
                }
            }
        }
    }

    private static void checkPredicated(String name, int opcode, boolean reversed, Oracle oracle, int vl, int esz,
            Random random) {
        Aarch64Core core = core(vl);
        long[] zdn = randomElements(core, esz, random);
        long[] zm = randomElements(core, esz, random);
        setElements(core, Z1, esz, zdn);
        setElements(core, Z3, esz, zm);
        boolean[] active = randomPredicate(core, esz, random);
        run(core, predicatedWord(esz, opcode, Z1, Z3));
        long[] result = elements(core, Z1, esz);
        for (int e = 0; e < result.length; e++) {
            long expected = zdn[e];
            if (active[e]) {
                BigInteger first = reversed ? unsigned(zm[e], esz) : unsigned(zdn[e], esz);
                BigInteger second = reversed ? unsigned(zdn[e], esz) : unsigned(zm[e], esz);
                expected = truncate(oracle.apply(first, second, esz), esz);
            }
            assertEquals(expected, result[e], name + (reversed ? "R" : "") + " esz=" + esz + " VL=" + vl + " e=" + e
                    + " n=" + Long.toHexString(zdn[e]) + " m=" + Long.toHexString(zm[e]));
        }
    }

    /// Limites que o aleatório raramente acerta: saturação exata e o shift por `-128`/`+127`.
    @Test
    void shiftVectorEdgeAmounts() {
        for (int esz = 0; esz < 4; esz++) {
            for (int amount : new int[] {-128, -127, -bits(esz) - 1, -bits(esz), -bits(esz) + 1, -1, 0, 1,
                    bits(esz) - 1, bits(esz), 127}) {
                Aarch64Core core = core(256);
                int count = elementCount(core, esz);
                long[] values = new long[count];
                long[] amounts = new long[count];
                for (int e = 0; e < count; e++) {
                    values[e] = e % 2 == 0 ? (bits(esz) == 64 ? Long.MIN_VALUE : 1L << (bits(esz) - 1)) : -1L;
                    amounts[e] = amount & 0xFF;
                }
                setElements(core, Z1, esz, values);
                setElements(core, Z3, esz, amounts);
                boolean[] active = new boolean[count];
                java.util.Arrays.fill(active, true);
                for (int b = 0; b < core.vectorLengthBytes(); b++) {
                    core.scalable().setPWord(P2, b / 64, core.scalable().pWord(P2, b / 64) | (1L << (b % 64)));
                }
                run(core, predicatedWord(esz, 0b001010, Z1, Z3)); // SQRSHL
                long[] result = elements(core, Z1, esz);
                for (int e = 0; e < count; e++) {
                    assertEquals(truncate(shiftOracle(unsigned(values[e], esz), unsigned(amounts[e], esz), esz, true,
                            true, true), esz), result[e], "SQRSHL esz=" + esz + " amount=" + amount + " e=" + e);
                }
            }
        }
    }

    // ── Semântica: Accumulate ────────────────────────────────────────────────────────────────────

    private static int accumulateWord(int base, int esz, int rm, int rn, int rd, int flags) {
        return base | (esz << 22) | (rm << 16) | flags | (rn << 5) | rd;
    }

    private static int shiftWord(int base, int flags, int esz, int amount, boolean right, int rn, int rd) {
        int tszimm = right ? (16 << esz) - amount : (8 << esz) + amount;
        return base | flags | ((tszimm >> 5) << 22) | ((tszimm & 0b11111) << 16) | (rn << 5) | rd;
    }

    private interface AccumulateOracle {
        /// Devolve o elemento `e` do destino, dados os vetores de origem (SEM sinal), o acumulador e o índice.
        BigInteger element(BigInteger[] zn, BigInteger[] zm, BigInteger[] zda, int e, int esz);
    }

    private static BigInteger[] unsignedElements(Aarch64Core core, int reg, int esz) {
        long[] raw = elements(core, reg, esz);
        BigInteger[] out = new BigInteger[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = unsigned(raw[i], esz);
        }
        return out;
    }

    /// Roda uma operação sem predicado e confere CADA elemento do destino contra o oráculo (que só vê os valores originais).
    private static void checkAccumulate(String name, int word, int destinationEsz, int sourceEsz, AccumulateOracle oracle,
            int vl, Random random) {
        Aarch64Core core = core(vl);
        setElements(core, Z1, destinationEsz, randomElements(core, destinationEsz, random));
        setElements(core, Z2, sourceEsz, randomElements(core, sourceEsz, random));
        setElements(core, Z3, sourceEsz, randomElements(core, sourceEsz, random));
        BigInteger[] zda = unsignedElements(core, Z1, destinationEsz);
        BigInteger[] zn = unsignedElements(core, Z2, sourceEsz);
        BigInteger[] zm = unsignedElements(core, Z3, sourceEsz);
        run(core, word);
        long[] result = elements(core, Z1, destinationEsz);
        for (int e = 0; e < result.length; e++) {
            assertEquals(truncate(oracle.element(zn, zm, zda, e, destinationEsz), destinationEsz), result[e],
                    name + " esz=" + destinationEsz + " VL=" + vl + " e=" + e);
        }
    }

    @Test
    void complexAddRotatesTheSecondOperand() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz < 4; esz++) {
                for (int saturate = 0; saturate < 2; saturate++) {
                    for (int rotation = 0; rotation < 2; rotation++) {
                        final int sat = saturate;
                        final int rot = rotation;
                        for (int seed = 0; seed < SEEDS; seed++) {
                            // `Zdn` é `Zd`; a fonte `Zm` fica em `bits[9:5]` = Z3 (mesmo elemento de `Z3` no oráculo).
                            Aarch64Core core = core(vl);
                            Random random = new Random(seed * 17L + esz);
                            setElements(core, Z1, esz, randomElements(core, esz, random));
                            setElements(core, Z3, esz, randomElements(core, esz, random));
                            BigInteger[] n = unsignedElements(core, Z1, esz);
                            BigInteger[] m = unsignedElements(core, Z3, esz);
                            run(core, COMPLEX_ADD_BASE | (esz << 22) | (sat << 16) | (rot << 10) | (Z3 << 5) | Z1);
                            long[] result = elements(core, Z1, esz);
                            for (int e = 0; e < result.length; e += 2) {
                                BigInteger nr = signed(n[e], esz);
                                BigInteger ni = signed(n[e + 1], esz);
                                BigInteger mr = signed(m[e], esz);
                                BigInteger mi = signed(m[e + 1], esz);
                                BigInteger real = rot == 0 ? nr.subtract(mi) : nr.add(mi);
                                BigInteger imaginary = rot == 0 ? ni.add(mr) : ni.subtract(mr);
                                if (sat == 1) {
                                    real = clampSigned(real, esz);
                                    imaginary = clampSigned(imaginary, esz);
                                }
                                String where = "CADD sat=" + sat + " rot=" + rot + " esz=" + esz + " VL=" + vl + " e=" + e;
                                assertEquals(truncate(real, esz), result[e], where + " re");
                                assertEquals(truncate(imaginary, esz), result[e + 1], where + " im");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void absoluteDifferenceLongAccumulatesBottomOrTopElementsOnly() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 1; esz < 4; esz++) {
                for (int unsignedForm = 0; unsignedForm < 2; unsignedForm++) {
                    for (int top = 0; top < 2; top++) {
                        final boolean isUnsigned = unsignedForm == 1;
                        final int t = top;
                        int word = accumulateWord(ABS_DIFF_LONG_BASE, esz, Z3, Z2, Z1, (unsignedForm << 11) | (top << 10));
                        for (int seed = 0; seed < SEEDS; seed++) {
                            checkAccumulate((isUnsigned ? "UABAL" : "SABAL") + (t == 1 ? "T" : "B"), word, esz, esz - 1,
                                    (zn, zm, zda, e, dest) -> {
                                        int src = dest - 1;
                                        BigInteger a = isUnsigned ? zn[2 * e + t] : signed(zn[2 * e + t], src);
                                        BigInteger b = isUnsigned ? zm[2 * e + t] : signed(zm[2 * e + t], src);
                                        return zda[e].add(a.subtract(b).abs());
                                    }, vl, new Random(seed * 31L + esz));
                        }
                    }
                }
            }
        }
    }

    @Test
    void addWithCarryLongSplitsTheSumIntoLowAndCarry() {
        for (int vl : VECTOR_LENGTHS) {
            for (int size = 0; size < 2; size++) {
                for (int subtract = 0; subtract < 2; subtract++) {
                    for (int top = 0; top < 2; top++) {
                        final int esz = 2 + size;
                        final boolean sub = subtract == 1;
                        final int t = top;
                        int word = CARRY_BASE | (subtract << 23) | (size << 22) | (Z3 << 16) | (top << 10) | (Z2 << 5) | Z1;
                        for (int seed = 0; seed < SEEDS * 3; seed++) {
                            checkAccumulate((sub ? "SBCL" : "ADCL") + (t == 1 ? "T" : "B"), word, esz, esz,
                                    (zn, zm, zda, e, dest) -> {
                                        int pair = e & ~1;
                                        BigInteger addend = zn[pair + t];
                                        if (sub) {
                                            addend = modulus(dest).subtract(BigInteger.ONE).subtract(addend);
                                        }
                                        BigInteger carryIn = zm[pair + 1].and(BigInteger.ONE);
                                        BigInteger sum = zda[pair].add(addend).add(carryIn);
                                        return (e & 1) == 0 ? sum : sum.shiftRight(bits(dest));
                                    }, vl, new Random(seed * 53L + esz));
                        }
                    }
                }
            }
        }
    }

    @Test
    void shiftRightAndAccumulateAddsTheShiftedSourceToTheDestination() {
        int[] codes = {0b00, 0b01, 0b10, 0b11}; // SSRA, USRA, SRSRA, URSRA
        String[] names = {"SSRA", "USRA", "SRSRA", "URSRA"};
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz < 4; esz++) {
                for (int amount : new int[] {1, 2, bits(esz) / 2, bits(esz) - 1, bits(esz)}) {
                    for (int kind = 0; kind < 4; kind++) {
                        final boolean signed = kind == 0 || kind == 2;
                        final boolean round = kind >= 2;
                        final int shift = amount;
                        int word = shiftWord(SHIFT_ACCUMULATE_BASE, codes[kind] << 10, esz, amount, true, Z2, Z1);
                        checkAccumulate(names[kind] + " #" + amount, word, esz, esz, (zn, zm, zda, e, dest) -> {
                            BigInteger value = signed ? signed(zn[e], dest) : zn[e];
                            BigInteger biased = round ? value.add(BigInteger.ONE.shiftLeft(shift - 1)) : value;
                            return zda[e].add(biased.shiftRight(shift));
                        }, vl, new Random(amount * 7L + kind + esz));
                    }
                }
            }
        }
    }

    @Test
    void shiftAndInsertKeepTheBitsTheShiftDoesNotReach() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz < 4; esz++) {
                for (int amount : new int[] {1, 3, bits(esz) / 2, bits(esz) - 1, bits(esz)}) {
                    final int shift = amount;
                    int sri = shiftWord(INSERT_BASE, 0, esz, amount, true, Z2, Z1);
                    checkAccumulate("SRI #" + amount, sri, esz, esz, (zn, zm, zda, e, dest) -> {
                        BigInteger reached = modulus(dest).subtract(BigInteger.ONE).shiftRight(shift);
                        return zda[e].andNot(reached).or(zn[e].shiftRight(shift).and(reached));
                    }, vl, new Random(amount + esz));
                }
                for (int amount : new int[] {0, 1, 3, bits(esz) / 2, bits(esz) - 1}) {
                    final int shift = amount;
                    int sli = shiftWord(INSERT_BASE, 1 << 10, esz, amount, false, Z2, Z1);
                    checkAccumulate("SLI #" + amount, sli, esz, esz, (zn, zm, zda, e, dest) -> {
                        BigInteger kept = BigInteger.ONE.shiftLeft(shift).subtract(BigInteger.ONE);
                        return zda[e].and(kept).or(zn[e].shiftLeft(shift).andNot(kept));
                    }, vl, new Random(amount + esz + 100));
                }
            }
        }
    }

    @Test
    void absoluteDifferenceAndAccumulateAddsToTheDestination() {
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz < 4; esz++) {
                for (int unsignedForm = 0; unsignedForm < 2; unsignedForm++) {
                    final boolean isUnsigned = unsignedForm == 1;
                    int word = accumulateWord(ABS_DIFF_BASE, esz, Z3, Z2, Z1, unsignedForm << 10);
                    for (int seed = 0; seed < SEEDS; seed++) {
                        checkAccumulate(isUnsigned ? "UABA" : "SABA", word, esz, esz, (zn, zm, zda, e, dest) -> {
                            BigInteger a = isUnsigned ? zn[e] : signed(zn[e], dest);
                            BigInteger b = isUnsigned ? zm[e] : signed(zm[e], dest);
                            return zda[e].add(a.subtract(b).abs());
                        }, vl, new Random(seed * 13L + esz));
                    }
                }
            }
        }
    }

    /// `Zm = Zd` (o carry sai do próprio acumulador): as fontes são lidas antes das escritas.
    @Test
    void addWithCarryReadsTheCarryFromTheAccumulatorWhenTheyAlias() {
        Aarch64Core core = core(256);
        long[] zda = {0xFFFFFFFFL, 1L, 5L, 0L, 0xFFFFFFFFL, 1L, 7L, 1L}; // .S
        setElements(core, Z1, 2, zda);
        setElements(core, Z2, 2, new long[] {1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L});
        int word = CARRY_BASE | (Z1 << 16) | (Z2 << 5) | Z1; // ADCLB .S com Zm = Zda
        run(core, word);
        long[] result = elements(core, Z1, 2);
        // par 0: 0xFFFFFFFF + 1 + carry (zda[1] & 1 = 1) = 0x1_0000_0001 -> low 1, carry 1
        assertEquals(1L, result[0]);
        assertEquals(1L, result[1]);
    }
}
