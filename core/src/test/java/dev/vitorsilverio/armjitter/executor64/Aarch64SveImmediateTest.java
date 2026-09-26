package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.8 — SVE com imediato: bitmask (`ORR`/`EOR`/`AND`/`DUPM`), cópia predicada (`CPY`/`FCPY`), broadcast
/// (`DUP`/`FDUP`), aritmética com imediato (`ADD`…`UQSUB`), `SMAX`/`UMAX`/`SMIN`/`UMIN` e `MUL`. As palavras da
/// tabela foram montadas com `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1+i8mm`) a partir do
/// texto de cada linha — os campos esperados vêm do TEXTO da instrução, nunca do decoder. O oráculo de execução
/// é escrito com `BigInteger`, sem repetir a aritmética do executor.
class Aarch64SveImmediateTest {
    private static final int[] VECTOR_LENGTHS = {128, 256, 512};
    private static final int BIT_SHIFT_FLAG = 13;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;

    private record Row(int word, String asm, Ir64Op.SveImmediate.Op op, int esz, int rd, int pg, long imm) {
    }

    private static Row row(int word, String asm, Ir64Op.SveImmediate.Op op, int esz, int rd, int pg, long imm) {
        return new Row(word, asm, op, esz, rd, pg, imm);
    }

    private static final Ir64Op.SveImmediate.Op ORR = Ir64Op.SveImmediate.Op.ORR;
    private static final Ir64Op.SveImmediate.Op EOR = Ir64Op.SveImmediate.Op.EOR;
    private static final Ir64Op.SveImmediate.Op AND = Ir64Op.SveImmediate.Op.AND;
    private static final Ir64Op.SveImmediate.Op DUPM = Ir64Op.SveImmediate.Op.DUPM;
    private static final Ir64Op.SveImmediate.Op CPY_M = Ir64Op.SveImmediate.Op.CPY_MERGING;
    private static final Ir64Op.SveImmediate.Op CPY_Z = Ir64Op.SveImmediate.Op.CPY_ZEROING;
    private static final Ir64Op.SveImmediate.Op FCPY = Ir64Op.SveImmediate.Op.FCPY;
    private static final Ir64Op.SveImmediate.Op DUP = Ir64Op.SveImmediate.Op.DUP;
    private static final Ir64Op.SveImmediate.Op FDUP = Ir64Op.SveImmediate.Op.FDUP;
    private static final Ir64Op.SveImmediate.Op ADD = Ir64Op.SveImmediate.Op.ADD;
    private static final Ir64Op.SveImmediate.Op SUB = Ir64Op.SveImmediate.Op.SUB;
    private static final Ir64Op.SveImmediate.Op SUBR = Ir64Op.SveImmediate.Op.SUBR;
    private static final Ir64Op.SveImmediate.Op SQADD = Ir64Op.SveImmediate.Op.SQADD;
    private static final Ir64Op.SveImmediate.Op UQADD = Ir64Op.SveImmediate.Op.UQADD;
    private static final Ir64Op.SveImmediate.Op SQSUB = Ir64Op.SveImmediate.Op.SQSUB;
    private static final Ir64Op.SveImmediate.Op UQSUB = Ir64Op.SveImmediate.Op.UQSUB;
    private static final Ir64Op.SveImmediate.Op SMAX = Ir64Op.SveImmediate.Op.SMAX;
    private static final Ir64Op.SveImmediate.Op UMAX = Ir64Op.SveImmediate.Op.UMAX;
    private static final Ir64Op.SveImmediate.Op SMIN = Ir64Op.SveImmediate.Op.SMIN;
    private static final Ir64Op.SveImmediate.Op UMIN = Ir64Op.SveImmediate.Op.UMIN;
    private static final Ir64Op.SveImmediate.Op MUL = Ir64Op.SveImmediate.Op.MUL;

    private static Stream<Row> rows() {
        return Stream.of(
                row(0x050044e0, "orr z0.d, z0.d, #0xff00ff00ff00ff00", ORR, 3, 0, 0, 0xff00ff00ff00ff00L),
                row(0x05420061, "eor z1.d, z1.d, #0xf", EOR, 3, 1, 0, 0xfL),
                row(0x0583ffa2, "and z2.d, z2.d, #0x7ffffffffffffffe", AND, 3, 2, 0, 0x7ffffffffffffffeL),
                row(0x05c004e3, "dupm z3.d, #0x00ff00ff00ff00ff", DUPM, 3, 3, 0, 0x00ff00ff00ff00ffL),
                row(0x05115f60, "cpy z0.b, p1/m, #-5", CPY_M, 0, 0, 1, -5L),
                row(0x05527001, "cpy z1.h, p2/m, #-128, lsl #8", CPY_M, 1, 1, 2, -32768L),
                row(0x059f4fe2, "cpy z2.s, p15/m, #127", CPY_M, 2, 2, 15, 127L),
                row(0x05d96063, "cpy z3.d, p9/m, #3, lsl #8", CPY_M, 3, 3, 9, 768L),
                row(0x05111fe4, "cpy z4.b, p1/z, #-1", CPY_Z, 0, 4, 1, -1L),
                row(0x05522c85, "cpy z5.h, p2/z, #100, lsl #8", CPY_Z, 1, 5, 2, 25600L),
                row(0x059f1006, "cpy z6.s, p15/z, #-128", CPY_Z, 2, 6, 15, -128L),
                row(0x05d93fa7, "cpy z7.d, p9/z, #-3, lsl #8", CPY_Z, 3, 7, 9, -768L),
                row(0x0551ce00, "fcpy z0.h, p1/m, #1.0", FCPY, 1, 0, 1, 0x3C00L),
                row(0x0592dc01, "fcpy z1.s, p2/m, #-0.5", FCPY, 2, 1, 2, 0xBF000000L),
                row(0x05dfc002, "fcpy z2.d, p15/m, #2.0", FCPY, 3, 2, 15, 0x4000000000000000L),
                row(0x05d3c7e3, "fcpy z3.d, p3/m, #31.0", FCPY, 3, 3, 3, 0x403F000000000000L),
                row(0x2538d000, "dup z0.b, #-128", DUP, 0, 0, 0, -128L),
                row(0x2578e0a1, "dup z1.h, #5, lsl #8", DUP, 1, 1, 0, 1280L),
                row(0x25b8dfe2, "dup z2.s, #-1", DUP, 2, 2, 0, -1L),
                row(0x25f8efe3, "dup z3.d, #127, lsl #8", DUP, 3, 3, 0, 32512L),
                row(0x2579ce00, "fdup z0.h, #1.0", FDUP, 1, 0, 0, 0x3C00L),
                row(0x25b9d801, "fdup z1.s, #-0.125", FDUP, 2, 1, 0, 0xBE000000L),
                row(0x25f9c002, "fdup z2.d, #2.0", FDUP, 3, 2, 0, 0x4000000000000000L),
                row(0x25f9d7e3, "fdup z3.d, #-31.0", FDUP, 3, 3, 0, 0xC03F000000000000L),
                row(0x2520dfe0, "add z0.b, z0.b, #255", ADD, 0, 0, 0, 255L),
                row(0x2560ffe1, "add z1.h, z1.h, #255, lsl #8", ADD, 1, 1, 0, 65280L),
                row(0x25a1c022, "sub z2.s, z2.s, #1", SUB, 2, 2, 0, 1L),
                row(0x25e1e0e3, "sub z3.d, z3.d, #7, lsl #8", SUB, 3, 3, 0, 1792L),
                row(0x2523d904, "subr z4.b, z4.b, #200", SUBR, 0, 4, 0, 200L),
                row(0x2563e025, "subr z5.h, z5.h, #1, lsl #8", SUBR, 1, 5, 0, 256L),
                row(0x2524dfe6, "sqadd z6.b, z6.b, #255", SQADD, 0, 6, 0, 255L),
                row(0x25a4e227, "sqadd z7.s, z7.s, #17, lsl #8", SQADD, 2, 7, 0, 4352L),
                row(0x2525dfe8, "uqadd z8.b, z8.b, #255", UQADD, 0, 8, 0, 255L),
                row(0x25e5ffe9, "uqadd z9.d, z9.d, #255, lsl #8", UQADD, 3, 9, 0, 65280L),
                row(0x2526dfea, "sqsub z10.b, z10.b, #255", SQSUB, 0, 10, 0, 255L),
                row(0x2566e06b, "sqsub z11.h, z11.h, #3, lsl #8", SQSUB, 1, 11, 0, 768L),
                row(0x2527cc8c, "uqsub z12.b, z12.b, #100", UQSUB, 0, 12, 0, 100L),
                row(0x25e7e02d, "uqsub z13.d, z13.d, #1, lsl #8", UQSUB, 3, 13, 0, 256L),
                row(0x25a8d000, "smax z0.s, z0.s, #-128", SMAX, 2, 0, 0, -128L),
                row(0x2528cfe1, "smax z1.b, z1.b, #127", SMAX, 0, 1, 0, 127L),
                row(0x2529dfe2, "umax z2.b, z2.b, #255", UMAX, 0, 2, 0, 255L),
                row(0x25e9c0e3, "umax z3.d, z3.d, #7", UMAX, 3, 3, 0, 7L),
                row(0x256adfe4, "smin z4.h, z4.h, #-1", SMIN, 1, 4, 0, -1L),
                row(0x25ead005, "smin z5.d, z5.d, #-128", SMIN, 3, 5, 0, -128L),
                row(0x25abd906, "umin z6.s, z6.s, #200", UMIN, 2, 6, 0, 200L),
                row(0x256bc007, "umin z7.h, z7.h, #0", UMIN, 1, 7, 0, 0L),
                row(0x25f0dfe8, "mul z8.d, z8.d, #-1", MUL, 3, 8, 0, -1L),
                row(0x2530cfe9, "mul z9.b, z9.b, #127", MUL, 0, 9, 0, 127L),
                row(0x2570d00a, "mul z10.h, z10.h, #-128", MUL, 1, 10, 0, -128L),
                row(0x25e4dfe1, "sqadd z1.d, z1.d, #255", SQADD, 3, 1, 0, 255L),
                row(0x25e6f902, "sqsub z2.d, z2.d, #200, lsl #8", SQSUB, 3, 2, 0, 51200L),
                row(0x2521dfe0, "sub z0.b, z0.b, #255", SUB, 0, 0, 0, 255L));
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), SVE, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(SVE).step(core);
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    private static boolean refused(int word) {
        try {
            decode(SVE, word);
            return false;
        } catch (UnsupportedOperationException refusal) {
            return true;
        }
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
        BigInteger u = unsigned(value, esz);
        return u.testBit(bits(esz) - 1) ? u.subtract(BigInteger.ONE.shiftLeft(bits(esz))) : u;
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

    /// Liga em `pg` o bit do byte mais baixo de cada elemento ativo e lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int pg, int esz, Random random) {
        boolean[] active = new boolean[core.vectorLengthBytes() >> esz];
        for (int e = 0; e < active.length; e++) {
            active[e] = random.nextBoolean();
            for (int b = 0; b < (1 << esz); b++) {
                int index = (e << esz) + b;
                if ((b == 0 && active[e]) || (b != 0 && random.nextBoolean())) {
                    core.scalable().setPWord(pg, index / 64, core.scalable().pWord(pg, index / 64) | (1L << (index % 64)));
                }
            }
        }
        return active;
    }

    private static BigInteger clamp(BigInteger value, BigInteger min, BigInteger max) {
        return value.max(min).min(max);
    }

    /// Resultado esperado de um elemento (`old` = valor atual de `Zd`, que é também a fonte).
    private static long expected(Row row, long old, boolean active) {
        int esz = row.esz();
        BigInteger signedOld = signed(old, esz);
        BigInteger unsignedOld = unsigned(old, esz);
        BigInteger immediate = BigInteger.valueOf(row.imm());
        BigInteger unsignedImm = unsigned(row.imm(), esz);
        BigInteger signedMin = BigInteger.ONE.shiftLeft(bits(esz) - 1).negate();
        BigInteger signedMax = BigInteger.ONE.shiftLeft(bits(esz) - 1).subtract(BigInteger.ONE);
        return switch (row.op()) {
            case CPY_MERGING, FCPY -> active ? truncate(immediate, esz) : old;
            case CPY_ZEROING -> active ? truncate(immediate, esz) : 0L;
            case DUP, FDUP, DUPM -> truncate(immediate, esz);
            case ADD -> truncate(unsignedOld.add(unsignedImm), esz);
            case SUB -> truncate(unsignedOld.subtract(unsignedImm), esz);
            case SUBR -> truncate(unsignedImm.subtract(unsignedOld), esz);
            case SQADD -> truncate(clamp(signedOld.add(unsignedImm), signedMin, signedMax), esz);
            case SQSUB -> truncate(clamp(signedOld.subtract(unsignedImm), signedMin, signedMax), esz);
            case UQADD -> truncate(clamp(unsignedOld.add(unsignedImm), BigInteger.ZERO, mask(esz)), esz);
            case UQSUB -> truncate(clamp(unsignedOld.subtract(unsignedImm), BigInteger.ZERO, mask(esz)), esz);
            case SMAX -> truncate(signedOld.max(signed(row.imm(), esz)), esz);
            case SMIN -> truncate(signedOld.min(signed(row.imm(), esz)), esz);
            case UMAX -> truncate(unsignedOld.max(unsignedImm), esz);
            case UMIN -> truncate(unsignedOld.min(unsignedImm), esz);
            case MUL -> truncate(signedOld.multiply(signed(row.imm(), esz)), esz);
            default -> throw new AssertionError("bitmask: tratado por palavra");
        };
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void decodesEveryAssembledWordToItsExpandedImmediate(Row row) {
        assertEquals(new Ir64Op.SveImmediate(row.op(), row.esz(), row.rd(), row.pg(), row.imm(), 0L),
                decode(SVE, row.word()), row.asm());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x05115f60, 0x05111fe4, 0x2538d000, 0x2520dfe0, 0x2521dfe0, 0x2523d904, 0x2524dfe6,
            0x2525dfe8, 0x2526dfea, 0x2527cc8c})
    void refusesTheTenInvalidPatternsWhereAByteAsksForTheEightBitShift(int byteWord) {
        assertTrue(refused(byteWord | (1 << BIT_SHIFT_FLAG)), "esz=0 com sh=1 (INVALID) deve ser recusado");
        int halfWord = byteWord | (1 << 22) | (1 << BIT_SHIFT_FLAG);
        decode(SVE, halfWord); // o par: o mesmo padrão com esz=1 continua sendo a forma geral (não lança)
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0x0513c7e3, // FCPY em byte
            0x2539ce00, // FDUP em byte
            0x05000000 | (0b111110 << 5), // ORR com N=0, imms=111110: reservado
            0x05000000 | (0b011111 << 5), // ORR com N=0, imms=011111: a corrida ocupa o elemento inteiro
            0x0510a000, // cópia com bits[15:14] = 10: não alocado
            0x0510e000, // FCPY com bit 13 ligado
            0x25000000 | (3 << 22) | (0b100 << 19) | (0b010 << 16) | (0b110 << 13), // grupo 100, opcode 010
            0x25000000 | (3 << 22) | (0b100 << 19) | (0b000 << 16) | (0b011 << 13), // ADD com bits[15:14] = 01
            0x25000000 | (3 << 22) | (0b110 << 19) | (0b001 << 16) | (0b110 << 13), // MUL com opcode != 000
            0x25000000 | (3 << 22) | (0b110 << 19) | (0b000 << 16) | (0b111 << 13), // MUL com cauda 111
            0x25000000 | (3 << 22) | (0b011 << 19) | (0b000 << 16) | (0b110 << 13), // grupo 011: nenhum imediato
            0x25000000 | (3 << 22) | (0b101 << 19) | (0b100 << 16) | (0b110 << 13), // min/max com opcode 100
            0x25000000 | (3 << 22) | (0b101 << 19) | (0b000 << 16) | (0b111 << 13), // min/max com cauda 111
            0x25000000 | (3 << 22) | (0b111 << 19) | (0b00 << 17) | (0b010 << 14), // broadcast com bits[16:14] = 010
            0x25000000 | (3 << 22) | (0b111 << 19) | (0b01 << 17) | (0b011 << 14), // broadcast com bits[18:17] != 00
    })
    void refusesTheUnallocatedNeighboursOfTheGroups(int word) {
        assertTrue(refused(word), () -> Integer.toHexString(word));
    }

    @Test
    void otherPatternsOfTheSamePrefixStayForOtherGroups() {
        assertTrue(refused(0x05a01000), "prefixo 0x05 com bits[21:20] = 10 (CPY escalar etc.) não é deste grupo");
        assertTrue(refused(0x05300000), "prefixo 0x05 com bits[21:18] = 0011");
    }

    // ── Execução ─────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void executesEveryRowLikeTheBigIntegerOracleAtEveryVectorLength(Row row) {
        for (int vl : VECTOR_LENGTHS) {
            Random random = new Random(row.word() * 31L + vl);
            for (int trial = 0; trial < 8; trial++) {
                Aarch64Core core = core(vl);
                long[] before = randomElements(core, row.esz(), random);
                setElements(core, row.rd(), row.esz(), before);
                boolean[] active = randomPredicate(core, row.pg(), row.esz(), random);
                run(core, row.word());
                long[] after = elements(core, row.rd(), row.esz());
                boolean bitmask = row.op() == ORR || row.op() == EOR || row.op() == AND || row.op() == DUPM;
                if (bitmask) {
                    long[] words = elements(core, row.rd(), 3);
                    long[] oldWords = new long[words.length];
                    for (int i = 0; i < oldWords.length; i++) {
                        oldWords[i] = doubleword(before, row.esz(), i);
                    }
                    for (int i = 0; i < words.length; i++) {
                        long want = switch (row.op()) {
                            case ORR -> oldWords[i] | row.imm();
                            case EOR -> oldWords[i] ^ row.imm();
                            case AND -> oldWords[i] & row.imm();
                            default -> row.imm();
                        };
                        assertEquals(want, words[i], row.asm() + " VL=" + vl + " dw" + i);
                    }
                    continue;
                }
                for (int e = 0; e < after.length; e++) {
                    assertEquals(expected(row, before[e], active[e]), after[e],
                            row.asm() + " VL=" + vl + " e" + e + " old=" + Long.toHexString(before[e]));
                }
                assertEquals(4L, core.pc());
            }
        }
    }

    /// Doubleword `i` do vetor descrito por `values` (elementos de `esz`).
    private static long doubleword(long[] values, int esz, int i) {
        int perDoubleword = 64 / bits(esz);
        long out = 0;
        for (int k = 0; k < perDoubleword; k++) {
            long field = values[i * perDoubleword + k] & mask(esz).longValue();
            out |= field << (k * bits(esz));
        }
        return out;
    }

    @Test
    void saturatingAddTreatsTheImmediateAsAnUnsignedIntegerNotAsMinusOne() {
        Aarch64Core core = core(128);
        setElements(core, 6, 0, new long[16]); // z6 = 0
        run(core, 0x2524dfe6); // sqadd z6.b, z6.b, #255
        assertEquals(127L, elements(core, 6, 0)[0], "0 + 255 satura em 127; se o 255 virasse -1, daria 255 (0xFF)");
        core = core(128);
        setElements(core, 10, 0, new long[] {(byte) 0x80 & 0xFFL});
        run(core, 0x2526dfea); // sqsub z10.b, z10.b, #255
        assertEquals(0x80L, elements(core, 10, 0)[0], "-128 - 255 satura em -128");
    }

    @Test
    void the64BitSaturatingFormsDoNotWrap() {
        Aarch64Core core = core(128);
        setElements(core, 9, 3, new long[] {-1L, 5L}); // uqadd z9.d, #65280
        run(core, 0x25e5ffe9);
        assertEquals(-1L, elements(core, 9, 3)[0], "UQADD satura em 2^64-1");
        assertEquals(5L + 65280L, elements(core, 9, 3)[1]);
        core = core(128);
        setElements(core, 13, 3, new long[] {100L, 300L}); // uqsub z13.d, #256
        run(core, 0x25e7e02d);
        assertEquals(0L, elements(core, 13, 3)[0]);
        assertEquals(44L, elements(core, 13, 3)[1]);
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
    @ValueSource(ints = {0x050044e0, 0x05115f60, 0x0551ce00, 0x2538d000, 0x2520dfe0, 0x25a8d000, 0x25f0dfe8})
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(256);
        core.setSystemRegisterBus(new Cpacr());
        core.scalable().setZWord(0, 0, 0x1234L);
        run(core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, core.scalable().zWord(0, 0), "a instrução não executou");
    }

    @Test
    void theImmediateGroupIsRefusedWithoutSve() {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_5_A, 0x05115f60));
    }
}
