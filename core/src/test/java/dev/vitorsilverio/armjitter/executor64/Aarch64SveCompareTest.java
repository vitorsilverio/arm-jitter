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
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SveCompare.Cond;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SveScalarCompare.Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.9 — comparações SVE que produzem predicado (vetor, elemento largo, imediato com e sem sinal) e as de escalares
/// (`WHILE*`, `CTERM`). As palavras da tabela foram montadas com `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1+sme+sme2`) a partir do TEXTO de cada instrução. Os resultados esperados vêm de um
/// oráculo escrito aqui com `BigInteger` (inteiros sem estouro, como o pseudocódigo do manual), independente do
/// executor, que trabalha com `long`.
class Aarch64SveCompareTest {
    private static final int[] VECTOR_LENGTHS = {128, 256, 384, 512};
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int PTEST_BASE = 0x2550C000;
    private static final int X1 = 1;
    private static final int X2 = 2;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int P0 = 0;
    private static final int P1 = 1;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(SVE2, "teste-SVE2p1",
            Aarch64Feature.SVE2_1);

    private record Row(int word, String asm, Ir64Op expected, int tier) {
    }

    private static final int BASE = 0;
    private static final int NEEDS_SVE2 = 1;
    private static final int NEEDS_SVE2P1 = 2;

    private static Row cmp(int word, String asm, Ir64Op.SveCompare.Cond cond, Ir64Op.SveCompare.Form form, int esz,
            int pd, int pg, int rn, int rm, int imm) {
        return new Row(word, asm, new Ir64Op.SveCompare(cond, form, esz, pd, pg, rn, rm, imm, 0L), BASE);
    }

    private static Row scalar(int word, String asm, Ir64Op.SveScalarCompare.Op op, int esz, int rd, int rn, int rm,
            boolean sf, boolean unsigned, boolean flag, int tier) {
        return new Row(word, asm, new Ir64Op.SveScalarCompare(op, esz, rd, rn, rm, sf, unsigned, flag, 0L), tier);
    }

    private static Stream<Row> rows() {
        var v = Ir64Op.SveCompare.Form.VECTOR;
        var w = Ir64Op.SveCompare.Form.WIDE;
        var i = Ir64Op.SveCompare.Form.IMMEDIATE;
        return Stream.of(
                cmp(0x24030440, "cmphs p0.b, p1/z, z2.b, z3.b", Cond.HS, v, 0, 0, 1, 2, 3, 0),
                cmp(0x24431c53, "cmphi p3.h, p7/z, z2.h, z3.h", Cond.HI, v, 1, 3, 7, 2, 3, 0),
                cmp(0x24838440, "cmpge p0.s, p1/z, z2.s, z3.s", Cond.GE, v, 2, 0, 1, 2, 3, 0),
                cmp(0x24c38450, "cmpgt p0.d, p1/z, z2.d, z3.d", Cond.GT, v, 3, 0, 1, 2, 3, 0),
                cmp(0x2403a440, "cmpeq p0.b, p1/z, z2.b, z3.b", Cond.EQ, v, 0, 0, 1, 2, 3, 0),
                cmp(0x2443a450, "cmpne p0.h, p1/z, z2.h, z3.h", Cond.NE, v, 1, 0, 1, 2, 3, 0),
                cmp(0x24032440, "cmpeq p0.b, p1/z, z2.b, z3.d", Cond.EQ, w, 0, 0, 1, 2, 3, 0),
                cmp(0x24432450, "cmpne p0.h, p1/z, z2.h, z3.d", Cond.NE, w, 1, 0, 1, 2, 3, 0),
                cmp(0x24834440, "cmpge p0.s, p1/z, z2.s, z3.d", Cond.GE, w, 2, 0, 1, 2, 3, 0),
                cmp(0x24034450, "cmpgt p0.b, p1/z, z2.b, z3.d", Cond.GT, w, 0, 0, 1, 2, 3, 0),
                cmp(0x24436440, "cmplt p0.h, p1/z, z2.h, z3.d", Cond.LT, w, 1, 0, 1, 2, 3, 0),
                cmp(0x24836450, "cmple p0.s, p1/z, z2.s, z3.d", Cond.LE, w, 2, 0, 1, 2, 3, 0),
                cmp(0x2403c440, "cmphs p0.b, p1/z, z2.b, z3.d", Cond.HS, w, 0, 0, 1, 2, 3, 0),
                cmp(0x2443c450, "cmphi p0.h, p1/z, z2.h, z3.d", Cond.HI, w, 1, 0, 1, 2, 3, 0),
                cmp(0x2483e440, "cmplo p0.s, p1/z, z2.s, z3.d", Cond.LO, w, 2, 0, 1, 2, 3, 0),
                cmp(0x2403e450, "cmpls p0.b, p1/z, z2.b, z3.d", Cond.LS, w, 0, 0, 1, 2, 3, 0),
                cmp(0x25100440, "cmpge p0.b, p1/z, z2.b, #-16", Cond.GE, i, 0, 0, 1, 2, 0, -16),
                cmp(0x254f0450, "cmpgt p0.h, p1/z, z2.h, #15", Cond.GT, i, 1, 0, 1, 2, 0, 15),
                cmp(0x259f2440, "cmplt p0.s, p1/z, z2.s, #-1", Cond.LT, i, 2, 0, 1, 2, 0, -1),
                cmp(0x25c02450, "cmple p0.d, p1/z, z2.d, #0", Cond.LE, i, 3, 0, 1, 2, 0, 0),
                cmp(0x251f8440, "cmpeq p0.b, p1/z, z2.b, #-1", Cond.EQ, i, 0, 0, 1, 2, 0, -1),
                cmp(0x25d08450, "cmpne p0.d, p1/z, z2.d, #-16", Cond.NE, i, 3, 0, 1, 2, 0, -16),
                cmp(0x243fc440, "cmphs p0.b, p1/z, z2.b, #127", Cond.HS, i, 0, 0, 1, 2, 0, 127),
                cmp(0x24600450, "cmphi p0.h, p1/z, z2.h, #0", Cond.HI, i, 1, 0, 1, 2, 0, 0),
                cmp(0x24b02440, "cmplo p0.s, p1/z, z2.s, #64", Cond.LO, i, 2, 0, 1, 2, 0, 64),
                cmp(0x24e06450, "cmpls p0.d, p1/z, z2.d, #1", Cond.LS, i, 3, 0, 1, 2, 0, 1),
                scalar(0x25e22020, "ctermeq x1, x2", Op.CTERM, 0, 0, 1, 2, true, false, false, BASE),
                scalar(0x25a22030, "ctermne w1, w2", Op.CTERM, 0, 0, 1, 2, false, false, true, BASE),
                scalar(0x25221420, "whilelt p0.b, x1, x2", Op.WHILE_LT, 0, 0, 1, 2, true, false, false,
                        BASE),
                scalar(0x25620430, "whilele p0.h, w1, w2", Op.WHILE_LT, 1, 0, 1, 2, false, false, true,
                        BASE),
                scalar(0x25a21c20, "whilelo p0.s, x1, x2", Op.WHILE_LT, 2, 0, 1, 2, true, true, false,
                        BASE),
                scalar(0x25e20c30, "whilels p0.d, w1, w2", Op.WHILE_LT, 3, 0, 1, 2, false, true, true,
                        BASE),
                scalar(0x25221020, "whilege p0.b, x1, x2", Op.WHILE_GT, 0, 0, 1, 2, true, false, false,
                        NEEDS_SVE2),
                scalar(0x25620030, "whilegt p0.h, w1, w2", Op.WHILE_GT, 1, 0, 1, 2, false, false, true,
                        NEEDS_SVE2),
                scalar(0x25a21820, "whilehs p0.s, x1, x2", Op.WHILE_GT, 2, 0, 1, 2, true, true, false,
                        NEEDS_SVE2),
                scalar(0x25e20830, "whilehi p0.d, w1, w2", Op.WHILE_GT, 3, 0, 1, 2, false, true, true,
                        NEEDS_SVE2),
                scalar(0x25223030, "whilerw p0.b, x1, x2", Op.WHILE_PTR, 0, 0, 1, 2, true, false, true,
                        NEEDS_SVE2),
                scalar(0x25623020, "whilewr p0.h, x1, x2", Op.WHILE_PTR, 1, 0, 1, 2, true, false, false,
                        NEEDS_SVE2),
                scalar(0x25225432, "whilelt { p2.b, p3.b }, x1, x2", Op.WHILE_LT_PAIR, 0, 2, 1, 2, true,
                        false, false, NEEDS_SVE2P1),
                scalar(0x25625435, "whilele { p4.h, p5.h }, x1, x2", Op.WHILE_LT_PAIR, 1, 4, 1, 2, true,
                        false, true, NEEDS_SVE2P1),
                scalar(0x25a25c36, "whilelo { p6.s, p7.s }, x1, x2", Op.WHILE_LT_PAIR, 2, 6, 1, 2, true,
                        true, false, NEEDS_SVE2P1),
                scalar(0x25e25c3f, "whilels { p14.d, p15.d }, x1, x2", Op.WHILE_LT_PAIR, 3, 14, 1, 2,
                        true, true, true, NEEDS_SVE2P1),
                scalar(0x25225030, "whilege { p0.b, p1.b }, x1, x2", Op.WHILE_GT_PAIR, 0, 0, 1, 2, true,
                        false, false, NEEDS_SVE2P1),
                scalar(0x25625033, "whilegt { p2.h, p3.h }, x1, x2", Op.WHILE_GT_PAIR, 1, 2, 1, 2, true,
                        false, true, NEEDS_SVE2P1),
                scalar(0x25a25834, "whilehs { p4.s, p5.s }, x1, x2", Op.WHILE_GT_PAIR, 2, 4, 1, 2, true,
                        true, false, NEEDS_SVE2P1),
                scalar(0x25e25837, "whilehi { p6.d, p7.d }, x1, x2", Op.WHILE_GT_PAIR, 3, 6, 1, 2, true,
                        true, true, NEEDS_SVE2P1));
    }

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static void run(Aarch64Architecture architecture, Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(architecture).step(core);
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    private static boolean decodesToCompare(Aarch64Architecture architecture, int word) {
        try {
            Ir64Op op = decode(architecture, word);
            return op instanceof Ir64Op.SveCompare || op instanceof Ir64Op.SveScalarCompare;
        } catch (UnsupportedOperationException refused) {
            return false;
        }
    }

    private static boolean[] predicate(Aarch64Core core, int reg) {
        boolean[] out = new boolean[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((core.scalable().pWord(reg, i / 64) >>> (i % 64)) & 1L) != 0L;
        }
        return out;
    }

    private static void setPredicate(Aarch64Core core, int reg, boolean[] bits) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(reg, w, 0L);
        }
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                long word = core.scalable().pWord(reg, i / 64);
                core.scalable().setPWord(reg, i / 64, word | (1L << (i % 64)));
            }
        }
    }

    private static void setElements(Aarch64Core core, int reg, int esz, long[] values) {
        for (int i = 0; i < values.length; i++) {
            SvePredicateOps.setElementOf(core.scalable(), reg, i, esz, values[i]);
        }
    }

    private static long mask(int esz) {
        return esz == 3 ? -1L : (1L << (8 << esz)) - 1L;
    }

    private static boolean[] flags(Aarch64Core core) {
        return new boolean[] {core.pstate().negative(), core.pstate().zero(), core.pstate().carry(),
                core.pstate().overflow()};
    }

    private static void setFlags(Aarch64Core core, boolean n, boolean z, boolean c, boolean v) {
        core.pstate().setNzcv(n, z, c, v);
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("rows")
    void everyEncodingDecodesToTheFieldsOfItsText(Row row) {
        assertEquals(row.expected(), decode(SVE2P1, row.word()), row.asm());
    }

    @Test
    void theTableCoversEveryOperationAndEveryComparisonKind() {
        assertEquals(46, rows().count());
        assertEquals(Ir64Op.SveScalarCompare.Op.values().length, rows().map(Row::expected)
                .filter(Ir64Op.SveScalarCompare.class::isInstance).map(o -> ((Ir64Op.SveScalarCompare) o).op())
                .distinct().count());
        assertEquals(26, rows().map(Row::expected).filter(Ir64Op.SveCompare.class::isInstance).count());
    }

    @ParameterizedTest
    @MethodSource("rows")
    void theGroupIsRefusedWithoutSve(Row row) {
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_5_A, row.word()));
    }

    /// O gate é POR LINHA: SVE base (comparações, `WHILELT`/`LE`/`LO`/`LS`, `CTERM`), SVE2 (`WHILEGE`/`GT`/`HS`/`HI`,
    /// `WHILERW`/`WHILEWR`) e SVE2.1 (as duas formas de par).
    @ParameterizedTest
    @MethodSource("rows")
    void eachRowDecodesOnlyUnderItsOwnFeature(Row row) {
        assertEquals(row.tier() == BASE, decodesToCompare(SVE, row.word()), "SVE: " + row.asm());
        assertEquals(row.tier() != NEEDS_SVE2P1, decodesToCompare(SVE2, row.word()), "SVE2: " + row.asm());
        assertTrue(decodesToCompare(SVE2P1, row.word()), "SVE2.1: " + row.asm());
    }

    @Test
    void theWideFormsWithDoublewordElementsAreRefused() {
        rows().filter(r -> r.expected() instanceof Ir64Op.SveCompare c && c.form() == Ir64Op.SveCompare.Form.WIDE)
                .forEach(r -> assertThrows(UnsupportedOperationException.class,
                        () -> decode(SVE2P1, r.word() | (3 << 22)), r.asm()));
    }

    /// Predicado-como-contador (`PN8`-`PN15`) é uma pendência nomeada: recusado, nunca lido como `WHILE` comum.
    @ParameterizedTest
    @ValueSource(ints = {
            0x25224430, // whilelt pn8.b, x1, x2, vlx2
            0x25626431, // whilelt pn9.h, x1, x2, vlx4
            0x25a2403a, // whilegt pn10.s, x1, x2, vlx2
            0x25e2683f, // whilehi pn15.d, x1, x2, vlx4
            0x25207010, // pext p0.b, pn8[0]
            0x25607530, // pext { p0.h, p1.h }, pn9[1]
    })
    void predicateAsCounterFormsAreRefusedEvenWithEveryFeature(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE2P1, word), Integer.toHexString(word));
    }

    // ── Comparação que produz predicado ─────────────────────────────────────────────────────────

    private static int compareWord(Ir64Op.SveCompare.Form form, Ir64Op.SveCompare.Cond cond, int esz, int pd, int pg,
            int rn, int rm, int imm) {
        int common = (esz << 22) | (pg << 10) | (rn << 5) | pd;
        return switch (form) {
            case VECTOR -> {
                int[] opcodeAndBit = switch (cond) {
                    case HS -> new int[] {0b000, 0};
                    case HI -> new int[] {0b000, 1};
                    case GE -> new int[] {0b100, 0};
                    case GT -> new int[] {0b100, 1};
                    case EQ -> new int[] {0b101, 0};
                    case NE -> new int[] {0b101, 1};
                    default -> throw new IllegalArgumentException("sem forma vetorial: " + cond);
                };
                yield 0x24000000 | common | (rm << 16) | (opcodeAndBit[0] << 13) | (opcodeAndBit[1] << 4);
            }
            case WIDE -> {
                int[] opcodeAndBit = switch (cond) {
                    case EQ -> new int[] {0b001, 0};
                    case NE -> new int[] {0b001, 1};
                    case GE -> new int[] {0b010, 0};
                    case GT -> new int[] {0b010, 1};
                    case LT -> new int[] {0b011, 0};
                    case LE -> new int[] {0b011, 1};
                    case HS -> new int[] {0b110, 0};
                    case HI -> new int[] {0b110, 1};
                    case LO -> new int[] {0b111, 0};
                    case LS -> new int[] {0b111, 1};
                };
                yield 0x24000000 | common | (rm << 16) | (opcodeAndBit[0] << 13) | (opcodeAndBit[1] << 4);
            }
            case IMMEDIATE -> switch (cond) {
                case HS, HI, LO, LS -> {
                    int low = cond == Ir64Op.SveCompare.Cond.LO || cond == Ir64Op.SveCompare.Cond.LS ? 1 : 0;
                    int second = cond == Ir64Op.SveCompare.Cond.HI || cond == Ir64Op.SveCompare.Cond.LS ? 1 : 0;
                    yield 0x24200000 | common | (imm << 14) | (low << 13) | (second << 4);
                }
                default -> {
                    int[] opcodeAndBit = switch (cond) {
                        case GE -> new int[] {0b000, 0};
                        case GT -> new int[] {0b000, 1};
                        case LT -> new int[] {0b001, 0};
                        case LE -> new int[] {0b001, 1};
                        case EQ -> new int[] {0b100, 0};
                        default -> new int[] {0b100, 1};
                    };
                    yield 0x25000000 | common | ((imm & 0b11111) << 16) | (opcodeAndBit[0] << 13)
                            | (opcodeAndBit[1] << 4);
                }
            };
        };
    }

    private record CompareCase(Ir64Op.SveCompare.Form form, Ir64Op.SveCompare.Cond cond) {
    }

    private static Stream<CompareCase> compareCases() {
        List<CompareCase> cases = new ArrayList<>();
        for (var cond : Ir64Op.SveCompare.Cond.values()) {
            switch (cond) {
                case HS, HI, GE, GT, EQ, NE -> cases.add(new CompareCase(Ir64Op.SveCompare.Form.VECTOR, cond));
                default -> { }
            }
            cases.add(new CompareCase(Ir64Op.SveCompare.Form.WIDE, cond));
            cases.add(new CompareCase(Ir64Op.SveCompare.Form.IMMEDIATE, cond));
        }
        return cases.stream();
    }

    private static boolean isSigned(Ir64Op.SveCompare.Cond cond) {
        return switch (cond) {
            case HS, HI, LO, LS -> false;
            default -> true;
        };
    }

    private static BigInteger unsignedOf(long value, int bits) {
        BigInteger big = new BigInteger(Long.toUnsignedString(value));
        return bits == 64 ? big : big.and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
    }

    private static BigInteger signedOf(long value, int bits) {
        BigInteger unsigned = unsignedOf(value, bits);
        return unsigned.testBit(bits - 1) ? unsigned.subtract(BigInteger.ONE.shiftLeft(bits)) : unsigned;
    }

    private static boolean order(Ir64Op.SveCompare.Cond cond, BigInteger left, BigInteger right) {
        int c = left.compareTo(right);
        return switch (cond) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case GE, HS -> c >= 0;
            case GT, HI -> c > 0;
            case LT, LO -> c < 0;
            case LE, LS -> c <= 0;
        };
    }

    /// O oráculo de um elemento: a interpretação do operando muda com a forma e com a condição (ver o helper do QEMU).
    private static boolean oracle(Ir64Op.SveCompare.Form form, Ir64Op.SveCompare.Cond cond, int esz, long nn, long mm,
            int imm) {
        int bits = 8 << esz;
        boolean signed = isSigned(cond);
        BigInteger left = signed ? signedOf(nn, bits) : unsignedOf(nn, bits);
        BigInteger right;
        switch (form) {
            case VECTOR -> right = signed ? signedOf(mm, bits) : unsignedOf(mm, bits);
            case WIDE -> {
                if (cond == Ir64Op.SveCompare.Cond.EQ || cond == Ir64Op.SveCompare.Cond.NE) {
                    // O helper compara `int8_t` (promovido) com `uint64_t`: iguais só se os 64 bits coincidem.
                    left = left.mod(BigInteger.ONE.shiftLeft(64));
                    right = unsignedOf(mm, 64);
                } else {
                    right = signed ? signedOf(mm, 64) : unsignedOf(mm, 64);
                }
            }
            default -> right = BigInteger.valueOf(imm);
        }
        return order(cond, left, right);
    }

    private static long[] pool(int esz, int imm, Random random, int count) {
        long[] specials = {0L, 1L, 2L, 0x7FL, 0x80L, 0xFFL, 0x7FFFL, 0x8000L, 0xFFFFL, 0x7FFF_FFFFL, 0x8000_0000L,
                0xFFFF_FFFFL, Long.MAX_VALUE, Long.MIN_VALUE, -1L, imm, imm + 1L, imm - 1L, 64L, 63L, 65L};
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(specials.length + 4);
            long value = pick < specials.length ? specials[pick] : random.nextLong();
            if (i > 0 && random.nextInt(4) == 0) {
                value = out[i - 1]; // vizinhos iguais: força a igualdade
            }
            out[i] = value & mask(esz);
        }
        return out;
    }

    private static long[] widePool(Random random, int count) {
        long[] specials = {0L, 1L, -1L, 0xFFL, 0x7FL, 0x80L, -128L, 0xFFFFL, -32768L, 0xFFFF_FFFFL, -2_147_483_648L,
                Long.MAX_VALUE, Long.MIN_VALUE, 0x8000_0000L, 0xFFFF_FFFF_FFFF_FF80L};
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(specials.length + 3);
            out[i] = pick < specials.length ? specials[pick] : random.nextLong();
        }
        return out;
    }

    private static int[] immediatesOf(Ir64Op.SveCompare.Cond cond) {
        return isSigned(cond) ? new int[] {-16, -1, 0, 1, 15} : new int[] {0, 1, 64, 127};
    }

    @ParameterizedTest
    @MethodSource("compareCases")
    void theComparisonMatchesTheBigIntegerOracleForEveryElementSizeAndVectorLength(CompareCase compareCase) {
        var form = compareCase.form();
        var cond = compareCase.cond();
        int maxEsz = form == Ir64Op.SveCompare.Form.WIDE ? 2 : 3;
        int[] immediates = form == Ir64Op.SveCompare.Form.IMMEDIATE ? immediatesOf(cond) : new int[] {0};
        Random random = new Random(0x17_09L + cond.ordinal());
        for (int vl : VECTOR_LENGTHS) {
            for (int esz = 0; esz <= maxEsz; esz++) {
                for (int imm : immediates) {
                    for (int round = 0; round < 3; round++) {
                        Aarch64Core core = core(SVE, vl);
                        int elements = core.vectorLengthBytes() >> esz;
                        long[] nn = pool(esz, imm, random, elements);
                        long[] mm = form == Ir64Op.SveCompare.Form.WIDE
                                ? widePool(random, core.vectorLengthBytes() / 8)
                                : pool(esz, imm, random, elements);
                        setElements(core, Z2, esz, nn);
                        if (form == Ir64Op.SveCompare.Form.WIDE) {
                            setElements(core, Z3, 3, mm);
                        } else if (form == Ir64Op.SveCompare.Form.VECTOR) {
                            setElements(core, Z3, esz, mm);
                        }
                        // `Pg` com lixo nos bytes que não são o mais baixo do elemento: o executor tem que ignorá-los.
                        boolean[] pg = new boolean[core.vectorLengthBytes()];
                        for (int i = 0; i < pg.length; i++) {
                            pg[i] = random.nextInt(3) != 0;
                        }
                        setPredicate(core, P1, pg);
                        setFlags(core, true, true, true, true);
                        int word = compareWord(form, cond, esz, P0, P1, Z2, Z3, imm);
                        assertEquals(Ir64Op.SveCompare.class, decode(SVE, word).getClass());
                        run(SVE, core, word);

                        boolean[] expected = new boolean[pg.length];
                        boolean anyActive = false;
                        boolean first = false;
                        boolean anyTrue = false;
                        boolean last = false;
                        for (int e = 0; e < elements; e++) {
                            int index = e << esz;
                            if (!pg[index]) {
                                continue;
                            }
                            long right = form == Ir64Op.SveCompare.Form.WIDE ? mm[index / 8] : form
                                    == Ir64Op.SveCompare.Form.VECTOR ? mm[e] : 0L;
                            boolean value = oracle(form, cond, esz, nn[e], right, imm);
                            expected[index] = value;
                            if (!anyActive) {
                                first = value;
                                anyActive = true;
                            }
                            anyTrue |= value;
                            last = value;
                        }
                        String where = form + " " + cond + " esz=" + esz + " vl=" + vl + " imm=" + imm;
                        assertArrayEquals(expected, predicate(core, P0), where);
                        assertArrayEquals(new boolean[] {first, !anyTrue, !last, false}, flags(core), where + " flags");
                    }
                }
            }
        }
    }

    /// Teste cruzado da spec: `CMPEQ` seguido de `PTEST` do mesmo predicado dá as MESMAS flags (só com `esz = 0`: o
    /// `PTEST` sempre testa byte a byte).
    @Test
    void theFlagsMatchAPtestOfTheResult() {
        Random random = new Random(7);
        for (int vl : VECTOR_LENGTHS) {
            for (int round = 0; round < 20; round++) {
                Aarch64Core core = core(SVE, vl);
                long[] nn = pool(0, 0, random, core.vectorLengthBytes());
                setElements(core, Z2, 0, nn);
                setElements(core, Z3, 0, pool(0, 0, random, core.vectorLengthBytes()));
                boolean[] pg = new boolean[core.vectorLengthBytes()];
                for (int i = 0; i < pg.length; i++) {
                    pg[i] = random.nextInt(4) != 0;
                }
                setPredicate(core, P1, pg);
                run(SVE, core, compareWord(Ir64Op.SveCompare.Form.VECTOR, Ir64Op.SveCompare.Cond.EQ, 0, P0, P1, Z2, Z3, 0));
                boolean[] afterCompare = flags(core);
                setFlags(core, false, false, false, true);
                run(SVE, core, PTEST_BASE | (P1 << 10) | (P0 << 5));
                assertArrayEquals(afterCompare, flags(core), "vl=" + vl);
            }
        }
    }

    @Test
    void theDestinationMayBeTheGoverningPredicate() {
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z2, 0, new long[] {1, 2, 3, 4, 5, 6, 7, 8});
        setElements(core, Z3, 0, new long[] {1, 0, 3, 0, 5, 0, 7, 0});
        boolean[] pg = new boolean[32];
        java.util.Arrays.fill(pg, true);
        setPredicate(core, P1, pg);
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.VECTOR, Ir64Op.SveCompare.Cond.EQ, 0, P1, P1, Z2, Z3, 0));
        boolean[] expected = new boolean[32];
        for (int i = 0; i < 32; i++) {
            expected[i] = i >= 8 || i % 2 == 0; // dos bytes 8 em diante os dois vetores valem 0: iguais
        }
        assertArrayEquals(expected, predicate(core, P1));
    }

    @Test
    void anEmptyGoverningPredicateGivesAnEmptyResultWithZAndCSet() {
        Aarch64Core core = core(SVE, 256);
        setFlags(core, true, false, false, true);
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.VECTOR, Ir64Op.SveCompare.Cond.EQ, 2, P0, P1, Z2, Z3, 0));
        assertArrayEquals(new boolean[] {false, true, true, false}, flags(core));
    }

    /// `CMPLT`/`CMPLE`/`CMPLO`/`CMPLS` na forma larga NÃO são `CMPGE`/`CMPGT`/`CMPHS`/`CMPHI` com os operandos trocados:
    /// o operando largo é fixo (Achado 3 da spec).
    @Test
    void wideLessThanIsADistinctInstructionFromWideGreaterEqual() {
        Aarch64Core core = core(SVE, 256);
        setElements(core, Z2, 0, new long[] {5, 5, 5, 5});
        setElements(core, Z3, 3, new long[] {5, 0, 0, 0});
        boolean[] pg = new boolean[32];
        java.util.Arrays.fill(pg, true);
        setPredicate(core, P1, pg);
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.WIDE, Ir64Op.SveCompare.Cond.LT, 0, P0, P1, Z2, Z3, 0));
        assertFalse(predicate(core, P0)[0], "5 < 5 é falso");
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.WIDE, Ir64Op.SveCompare.Cond.LE, 0, P0, P1, Z2, Z3, 0));
        assertTrue(predicate(core, P0)[0], "5 <= 5 é verdadeiro");
    }

    @Test
    void immediatesAtTheirLimitsAreDecodedWithTheirOwnWidthAndSign() {
        Aarch64Core core = core(SVE, 128);
        setElements(core, Z2, 0, new long[] {0xF0, 0x0F, 0x7F, 0x80});
        boolean[] pg = new boolean[16];
        java.util.Arrays.fill(pg, true);
        setPredicate(core, P1, pg);
        // cmpeq z.b, #-16: -16 é 0xF0 no elemento
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.IMMEDIATE, Ir64Op.SveCompare.Cond.EQ, 0, P0, P1, Z2, 0, -16));
        assertTrue(predicate(core, P0)[0]);
        assertFalse(predicate(core, P0)[1]);
        // cmphs z.b, #127 (sem sinal): 0x7F e 0x80 passam, 0xF0 também, 0x0F não
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.IMMEDIATE, Ir64Op.SveCompare.Cond.HS, 0, P0, P1, Z2, 0, 127));
        boolean[] p = predicate(core, P0);
        assertTrue(p[0] && !p[1] && p[2] && p[3]);
        // cmpge z.b, #15 (com sinal): 0xF0 = -16 falha
        run(SVE, core, compareWord(Ir64Op.SveCompare.Form.IMMEDIATE, Ir64Op.SveCompare.Cond.GE, 0, P0, P1, Z2, 0, 15));
        p = predicate(core, P0);
        assertTrue(!p[0] && p[1] && p[2] && !p[3], "0xF0=-16 e 0x80=-128 falham; 0x0F=15 (igual) e 0x7F passam");
    }

    // ── WHILE ─────────────────────────────────────────────────────────────────────────────────────

    private static int whileWord(int esz, int rd, int rn, int rm, boolean sf, boolean unsigned, boolean lt,
            boolean flag) {
        return 0x25200000 | (esz << 22) | (rm << 16) | ((sf ? 1 : 0) << 12) | ((unsigned ? 1 : 0) << 11)
                | ((lt ? 1 : 0) << 10) | (rn << 5) | ((flag ? 1 : 0) << 4) | rd;
    }

    private static int pairWord(int esz, int rd, int rn, int rm, boolean unsigned, boolean lt, boolean flag) {
        return 0x25205010 | (esz << 22) | (rm << 16) | ((unsigned ? 1 : 0) << 11) | ((lt ? 1 : 0) << 10) | (rn << 5)
                | ((rd / 2) << 1) | (flag ? 1 : 0);
    }

    private static final long[] SPECIAL_OPERANDS = {0L, 1L, 2L, 5L, 31L, 32L, 33L, 100L, 1000L, -1L, -2L, -5L,
            Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE, Long.MIN_VALUE + 1, 0x7FFF_FFFFL, 0x8000_0000L,
            0xFFFF_FFFFL, 0x1_0000_0000L, 0xFFFF_FFFF_8000_0000L};

    /// O oráculo de `WHILE`: o pseudocódigo do manual, com o contador modular na largura do operando. `LT` varre do elemento 0 para cima
    /// incrementando o primeiro operando; `GT` varre do último para baixo decrementando-o.
    private static boolean[] whileOracle(boolean lt, boolean orEqual, boolean unsigned, boolean sf, long op0, long op1,
            int elements) {
        int bits = sf ? 64 : 32;
        BigInteger a = unsigned ? unsignedOf(op0, bits) : signedOf(op0, bits);
        BigInteger b = unsigned ? unsignedOf(op1, bits) : signedOf(op1, bits);
        boolean[] active = new boolean[elements];
        boolean last = true;
        for (int step = 0; step < elements; step++) {
            BigInteger value = wrap(lt ? a.add(BigInteger.valueOf(step)) : a.subtract(BigInteger.valueOf(step)), bits, !unsigned);
            int c = value.compareTo(b);
            boolean condition = lt ? (orEqual ? c <= 0 : c < 0) : (orEqual ? c >= 0 : c > 0);
            last = last && condition;
            active[lt ? step : elements - 1 - step] = last;
        }
        return active;
    }

    /// O contador do pseudocódigo tem a largura do operando e DÁ A VOLTA (`MAX + 1` = `MIN`): é por isso que o QEMU trata
    /// `op1 == maxval` como predicado todo verdadeiro.
    private static BigInteger wrap(BigInteger value, int bits, boolean signed) {
        BigInteger modulus = BigInteger.ONE.shiftLeft(bits);
        BigInteger reduced = value.mod(modulus);
        return signed && reduced.testBit(bits - 1) ? reduced.subtract(modulus) : reduced;
    }

    private static boolean[] expandToBytes(boolean[] elementsActive, int esz, int totalBytes) {
        boolean[] out = new boolean[totalBytes];
        for (int e = 0; e < elementsActive.length; e++) {
            out[e << esz] = elementsActive[e];
        }
        return out;
    }

    private static boolean[] countTestFlags(boolean[] active) {
        boolean anyTrue = false;
        for (boolean a : active) {
            anyTrue |= a;
        }
        return new boolean[] {active[0], !anyTrue, !active[active.length - 1], false};
    }

    @Test
    void whileMatchesTheOracleForEveryConditionOperandWidthAndVectorLength() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE2, vl);
            for (int esz = 0; esz <= 3; esz++) {
                int elements = core.vectorLengthBytes() >> esz;
                for (boolean lt : new boolean[] {true, false}) {
                    for (boolean sf : new boolean[] {true, false}) {
                        for (boolean unsigned : new boolean[] {false, true}) {
                            for (boolean flag : new boolean[] {false, true}) {
                                boolean orEqual = flag == lt;
                                int word = whileWord(esz, P0, X1, X2, sf, unsigned, lt, flag);
                                for (long op0 : SPECIAL_OPERANDS) {
                                    for (long op1 : SPECIAL_OPERANDS) {
                                        core.setX(X1, op0);
                                        core.setX(X2, op1);
                                        run(SVE2, core, word);
                                        boolean[] active = whileOracle(lt, orEqual, unsigned, sf, op0, op1, elements);
                                        String where = "vl=" + vl + " esz=" + esz + " lt=" + lt + " sf=" + sf + " u="
                                                + unsigned + " eq=" + orEqual + " " + op0 + "," + op1;
                                        assertArrayEquals(expandToBytes(active, esz, core.vectorLengthBytes()),
                                                predicate(core, P0), where);
                                        assertArrayEquals(countTestFlags(active), flags(core), where + " flags");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void whilePairWritesBothPredicatesAndCountsOverTwiceTheVectorLength() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE2P1, vl);
            for (int esz = 0; esz <= 3; esz++) {
                int perRegister = core.vectorLengthBytes() >> esz;
                for (boolean lt : new boolean[] {true, false}) {
                    for (boolean unsigned : new boolean[] {false, true}) {
                        for (boolean flag : new boolean[] {false, true}) {
                            boolean orEqual = flag == lt;
                            for (int rd : new int[] {0, 6, 14}) {
                                int word = pairWord(esz, rd, X1, X2, unsigned, lt, flag);
                                for (long op0 : SPECIAL_OPERANDS) {
                                    for (long op1 : new long[] {0L, 3L, 40L, 200L, -7L, Long.MAX_VALUE, Long.MIN_VALUE}) {
                                        core.setX(X1, op0);
                                        core.setX(X2, op1);
                                        run(SVE2P1, core, word);
                                        boolean[] active = whileOracle(lt, orEqual, unsigned, true, op0, op1,
                                                2 * perRegister);
                                        String where = "vl=" + vl + " esz=" + esz + " lt=" + lt + " u=" + unsigned
                                                + " eq=" + orEqual + " rd=" + rd + " " + op0 + "," + op1;
                                        boolean[] low = java.util.Arrays.copyOfRange(active, 0, perRegister);
                                        boolean[] high = java.util.Arrays.copyOfRange(active, perRegister,
                                                2 * perRegister);
                                        assertArrayEquals(expandToBytes(low, esz, core.vectorLengthBytes()),
                                                predicate(core, rd), where + " Pd");
                                        assertArrayEquals(expandToBytes(high, esz, core.vectorLengthBytes()),
                                                predicate(core, rd + 1), where + " Pd+1");
                                        assertArrayEquals(countTestFlags(active), flags(core), where + " flags");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /// O teste de VLA canônico: `WHILELT` produz `min(rm - rn, VL/esize)` elementos ativos a partir do elemento 0.
    @Test
    void whileltActivatesExactlyTheRemainingIterationsInTwoVectorLengths() {
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(SVE, vl);
            for (long remaining : new long[] {0, 1, 5, vl / 8 - 1, vl / 8, vl / 8 + 1, 1000}) {
                core.setX(X1, 10);
                core.setX(X2, 10 + remaining);
                run(SVE, core, whileWord(0, P0, X1, X2, true, false, true, false));
                int active = 0;
                boolean[] p = predicate(core, P0);
                for (int i = 0; i < p.length; i++) {
                    if (p[i]) {
                        assertEquals(i, active, "os ativos são contíguos a partir do 0");
                        active++;
                    }
                }
                assertEquals(Math.min(remaining, vl / 8), active, "vl=" + vl + " restante=" + remaining);
            }
        }
    }

    @Test
    void anAlreadyFinishedLoopGivesAnEmptyPredicateWithZSet() {
        Aarch64Core core = core(SVE, 256);
        core.setX(X1, 9);
        core.setX(X2, 3);
        run(SVE, core, whileWord(0, P0, X1, X2, true, false, true, false));
        assertArrayEquals(new boolean[32], predicate(core, P0));
        assertArrayEquals(new boolean[] {false, true, true, false}, flags(core));
    }

    @Test
    void whileloAndWhileltDifferWhenTheOperandsCrossTheSign() {
        Aarch64Core core = core(SVE, 256);
        core.setX(X1, -1L);
        core.setX(X2, 3L);
        run(SVE, core, whileWord(0, P0, X1, X2, true, false, true, false)); // whilelt: -1 < 3 → 4 elementos
        assertEquals(4, countActive(predicate(core, P0)));
        run(SVE, core, whileWord(0, P0, X1, X2, true, true, true, false)); // whilelo: 2^64-1 < 3 é falso
        assertEquals(0, countActive(predicate(core, P0)));
    }

    private static int countActive(boolean[] predicate) {
        int active = 0;
        for (boolean b : predicate) {
            active += b ? 1 : 0;
        }
        return active;
    }

    @Test
    void whileleWithTheMaximumLimitIsAllTrueInsteadOfOverflowing() {
        Aarch64Core core = core(SVE, 256);
        core.setX(X1, 5);
        core.setX(X2, Long.MAX_VALUE);
        run(SVE, core, whileWord(0, P0, X1, X2, true, false, true, true)); // whilele
        assertEquals(32, countActive(predicate(core, P0)));
        core.setX(X2, -1L);
        run(SVE, core, whileWord(0, P0, X1, X2, true, true, true, true)); // whilels com o maior sem sinal
        assertEquals(32, countActive(predicate(core, P0)));
    }

    @Test
    void whileGeneratesTheDecreasingPredicateFromTheTop() {
        Aarch64Core core = core(SVE2, 256);
        core.setX(X1, 5);
        core.setX(X2, 2);
        run(SVE2, core, whileWord(0, P0, X1, X2, true, false, false, true)); // whilegt: 5 > 2 → 3 elementos
        boolean[] p = predicate(core, P0);
        for (int i = 0; i < 32; i++) {
            assertEquals(i >= 29, p[i], "elemento " + i);
        }
        // WHILEGT vira N quando o predicado inteiro é verdadeiro (o primeiro elemento é o último a ligar)
        core.setX(X1, 1000);
        run(SVE2, core, whileWord(0, P0, X1, X2, true, false, false, true));
        assertArrayEquals(new boolean[] {true, false, false, false}, flags(core));
    }

    // ── WHILERW / WHILEWR ────────────────────────────────────────────────────────────────────────

    private static int pointerWord(int esz, boolean readAfterWrite) {
        return 0x25203000 | (esz << 22) | (X2 << 16) | (X1 << 5) | ((readAfterWrite ? 1 : 0) << 4) | P0;
    }

    private static int oraclePointerCount(boolean readAfterWrite, long first, long second, int esz, int elements) {
        BigInteger a = unsignedOf(first, 64);
        BigInteger b = unsignedOf(second, 64);
        BigInteger diff = readAfterWrite ? a.subtract(b).abs() : b.subtract(a).max(BigInteger.ZERO);
        BigInteger count = diff.shiftRight(esz);
        return count.signum() == 0 ? elements : count.min(BigInteger.valueOf(elements)).intValueExact();
    }

    @Test
    void pointerConflictWhilesMatchTheOracle() {
        long[] addresses = {0L, 16L, 17L, 100L, 1000L, 0x1000L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x8000_0000L};
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(SVE2, vl);
            for (int esz = 0; esz <= 3; esz++) {
                int elements = core.vectorLengthBytes() >> esz;
                for (boolean rw : new boolean[] {true, false}) {
                    for (long first : addresses) {
                        for (long second : addresses) {
                            core.setX(X1, first);
                            core.setX(X2, second);
                            run(SVE2, core, pointerWord(esz, rw));
                            int count = oraclePointerCount(rw, first, second, esz, elements);
                            boolean[] active = new boolean[elements];
                            for (int e = 0; e < count; e++) {
                                active[e] = true;
                            }
                            String where = "vl=" + vl + " esz=" + esz + " rw=" + rw + " " + first + "," + second;
                            assertArrayEquals(expandToBytes(active, esz, core.vectorLengthBytes()), predicate(core, P0),
                                    where);
                            assertArrayEquals(countTestFlags(active), flags(core), where + " flags");
                        }
                    }
                }
            }
        }
    }

    @Test
    void writeAfterReadWithASecondPointerBehindTheFirstHasNoHazard() {
        Aarch64Core core = core(SVE2, 256);
        core.setX(X1, 4096);
        core.setX(X2, 100); // o segundo (leitura) está ANTES do primeiro: nenhum hazard, todos os elementos
        run(SVE2, core, pointerWord(0, false));
        assertEquals(32, countActive(predicate(core, P0)));
        core.setX(X2, 4096 + 5); // hazard a 5 bytes: só 5 elementos de byte são seguros
        run(SVE2, core, pointerWord(0, false));
        assertEquals(5, countActive(predicate(core, P0)));
    }

    // ── CTERM ─────────────────────────────────────────────────────────────────────────────────────

    private static int ctermWord(boolean sf, boolean ne) {
        return 0x25A02000 | ((sf ? 1 : 0) << 22) | (X2 << 16) | (X1 << 5) | ((ne ? 1 : 0) << 4);
    }

    @Test
    void ctermSetsNAndVFromTheConditionAndKeepsZAndC() {
        for (boolean sf : new boolean[] {true, false}) {
            for (boolean ne : new boolean[] {false, true}) {
                for (boolean equal : new boolean[] {false, true}) {
                    for (int start = 0; start < 16; start++) {
                        Aarch64Core core = core(SVE, 256);
                        core.setX(X1, 0x1_0000_0000L | 7L);
                        core.setX(X2, equal ? (sf ? 0x1_0000_0000L | 7L : 7L) : 8L);
                        boolean z = (start & 2) != 0;
                        boolean c = (start & 4) != 0;
                        setFlags(core, (start & 8) != 0, z, c, (start & 1) != 0);
                        run(SVE, core, ctermWord(sf, ne));
                        boolean condition = ne != equal;
                        assertArrayEquals(new boolean[] {condition, z, c, !condition && !c}, flags(core),
                                "sf=" + sf + " ne=" + ne + " equal=" + equal + " start=" + start);
                    }
                }
            }
        }
    }

    @Test
    void ctermWritesNoPredicate() {
        Aarch64Core core = core(SVE, 256);
        boolean[] marker = new boolean[32];
        marker[3] = true;
        setPredicate(core, P0, marker);
        run(SVE, core, ctermWord(true, false));
        assertArrayEquals(marker, predicate(core, P0));
    }

    // ── Acesso, VL efetivo, streaming e lifter ──────────────────────────────────────────────────

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
    @ValueSource(ints = {0x24030440, 0x25100440, 0x243fc440, 0x25221420, 0x25e22020, 0x25623020})
    void everyFormTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE2, 256);
        core.setSystemRegisterBus(new Cpacr());
        boolean[] marker = new boolean[32];
        marker[1] = true;
        setPredicate(core, P0, marker);
        run(SVE2, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertArrayEquals(marker, predicate(core, P0), "a instrução não executou");
    }

    @Test
    void operationsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(SVE, 1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // LEN=1 => VL efetivo 256
        core.setX(X1, 0);
        core.setX(X2, 1000);
        run(SVE, core, whileWord(0, P0, X1, X2, true, false, true, false));
        assertEquals(32, countActive(predicate(core, P0)), "só o VL efetivo (256 bits = 32 bytes)");
        for (int w = 1; w < core.scalable().wordsPerPredicate(); w++) {
            assertEquals(0L, core.scalable().pWord(P0, w), "nada acima do VL efetivo é escrito");
        }
    }

    @Test
    void comparisonsAreLegalInStreamingModeAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        core.setX(X1, 0);
        core.setX(X2, 1000);
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, whileWord(0, P0, X1, X2, true, false, true, false));
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção");
        assertEquals(64, countActive(predicate(core, P0)), "SVL = 512: 64 bytes");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        core.setX(X1, 0);
        core.setX(X2, 5);
        core.memory().write32(0, whileWord(0, P0, X1, X2, true, false, true, false));
        core.memory().write32(4, 0x25e22020); // ctermeq x1, x2
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(5, countActive(predicate(core, P0)));
    }
}
