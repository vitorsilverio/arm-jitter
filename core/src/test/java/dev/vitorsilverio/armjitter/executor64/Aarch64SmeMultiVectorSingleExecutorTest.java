package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op.SmeMultiVectorSingle.Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.7 — SME2 multi-vetor "multiple-and-single" destrutivo. As palavras são montadas campo a campo por {@link #word}
/// (a montagem é amarrada ao assembler em {@link #wordBuilderMatchesAssembler}) e cada operação é conferida contra uma
/// referência escrita em `BigInteger`/`Math` — NÃO contra as operações de lane do SVE que o executor reusa.
class Aarch64SmeMultiVectorSingleExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-mvs-exec", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.FP8);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long VBAR = 0x400L;
    private static final int EC_SME = 0x1D;
    private static final int ESR_EC_SHIFT = 26;
    private static final long SMTC_MASK = 0x7;
    private static final long SMTC_NOT_STREAMING = 2;
    private static final int Z_REGISTERS = 32;
    private static final int WORD_INDEX_SHIFT = 6;
    private static final int WORD_BIT_MASK = Long.SIZE - 1;

    private static final int FAMILY_BASE = 0xC120A000;
    private static final int ESZ_SHIFT = 22;
    private static final int ZM_SHIFT = 16;
    private static final int X4_BIT = 1 << 11;
    private static final int[] SVLS = {256, 512};
    private static final long FPSR_IOC = 1L;

    private static Aarch64Core core(int svlBits, long svcr) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), ALL, svlBits,
                svlBits);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        core.setSvcr(svcr);
        return core;
    }

    private static void run(Aarch64Core core, int word) {
        core.memory().write32(0, word);
        core.setProgramCounter(0);
        new Ir64BlockExecutor(ALL).step(core);
    }

    /// Bits `[10:5]` (chave da operação) e `[0]` (`U`) de cada uma das 13 linhas do `.decode`.
    private static int operationBits(Op op) {
        return switch (op) {
            case SMAX -> 0b000000 << 5;
            case UMAX -> 0b000000 << 5 | 1;
            case SMIN -> 0b000001 << 5;
            case UMIN -> 0b000001 << 5 | 1;
            case FMAX -> 0b001000 << 5;
            case FMIN -> 0b001000 << 5 | 1;
            case FMAXNM -> 0b001001 << 5;
            case FMINNM -> 0b001001 << 5 | 1;
            case SRSHL -> 0b010001 << 5;
            case URSHL -> 0b010001 << 5 | 1;
            case ADD -> 0b011000 << 5;
            case SQDMULH -> 0b100000 << 5;
            case FSCALE -> 0b001100 << 5;
        };
    }

    /// `zdn` é o registrador-base (múltiplo de `count`); o campo do encoding é `zdn / count`.
    private static int word(Op op, int esz, int count, int zdn, int zm) {
        int field = zdn / count << (count == 2 ? 1 : 2);
        return FAMILY_BASE | esz << ESZ_SHIFT | zm << ZM_SHIFT | (count == 4 ? X4_BIT : 0) | operationBits(op) | field;
    }

    @Test
    void wordBuilderMatchesAssembler() {
        // aarch64-none-elf-as: smax {z2.b-z3.b}, {z2.b-z3.b}, z4.b
        assertEquals(0xC124A002, word(Op.SMAX, 0, 2, 2, 4));
        // umin {z4.d-z7.d}, {z4.d-z7.d}, z9.d
        assertEquals(0xC1E9A825, word(Op.UMIN, 3, 4, 4, 9));
        // fmaxnm {z28.s-z31.s}, {z28.s-z31.s}, z15.s
        assertEquals(0xC1AFA93C, word(Op.FMAXNM, 2, 4, 28, 15));
        // sqdmulh {z4.h-z7.h}, {z4.h-z7.h}, z9.h
        assertEquals(0xC169AC04, word(Op.SQDMULH, 1, 4, 4, 9));
        // fscale {z2.h-z3.h}, {z2.h-z3.h}, z4.h
        assertEquals(0xC164A182, word(Op.FSCALE, 1, 2, 2, 4));
    }

    // ── acesso a elementos ───────────────────────────────────────────────────────────────────────

    private static long mask(int bits) {
        return bits == Long.SIZE ? -1L : (1L << bits) - 1L;
    }

    private static long get(Aarch64Core core, int z, int e, int esz) {
        int bits = Byte.SIZE << esz;
        int bit = e * bits;
        return (core.scalable().zWord(z, bit >>> WORD_INDEX_SHIFT) >>> (bit & WORD_BIT_MASK)) & mask(bits);
    }

    private static void put(Aarch64Core core, int z, int e, int esz, long value) {
        int bits = Byte.SIZE << esz;
        int bit = e * bits;
        long place = mask(bits) << (bit & WORD_BIT_MASK);
        long word = core.scalable().zWord(z, bit >>> WORD_INDEX_SHIFT);
        core.scalable().setZWord(z, bit >>> WORD_INDEX_SHIFT, word & ~place | (value << (bit & WORD_BIT_MASK)) & place);
    }

    private static int elements(Aarch64Core core, int esz) {
        return core.streamingVectorLengthBytes() >>> esz;
    }

    private static long[][] snapshot(Aarch64Core core) {
        long[][] regs = new long[Z_REGISTERS][core.streamingVectorLengthBytes() / Long.BYTES];
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int w = 0; w < regs[z].length; w++) {
                regs[z][w] = core.scalable().zWord(z, w);
            }
        }
        return regs;
    }

    private static void assertRegisterUnchanged(Aarch64Core core, long[][] before, int z) {
        for (int w = 0; w < before[z].length; w++) {
            assertEquals(before[z][w], core.scalable().zWord(z, w), "Z" + z + " palavra " + w + " não podia mudar");
        }
    }

    // ── referência inteira (BigInteger) ──────────────────────────────────────────────────────────

    private static BigInteger asUnsigned(long value, int bits) {
        return bits == Long.SIZE ? new BigInteger(Long.toUnsignedString(value)) : BigInteger.valueOf(value & mask(bits));
    }

    private static BigInteger asSigned(BigInteger unsignedValue, int bits) {
        return unsignedValue.testBit(bits - 1) ? unsignedValue.subtract(BigInteger.ONE.shiftLeft(bits)) : unsignedValue;
    }

    /// `v × 2^amount` com arredondamento à direita (soma meio e trunca em direção a −∞), SEM saturar; `amount` é o
    /// elemento inteiro com sinal. Magnitudes acima de 300 já empurram o elemento inteiro para fora.
    private static BigInteger roundingShift(BigInteger v, BigInteger amount) {
        int a = amount.max(BigInteger.valueOf(-300)).min(BigInteger.valueOf(300)).intValue();
        if (a >= 0) {
            return v.shiftLeft(a);
        }
        int k = -a;
        return v.add(BigInteger.ONE.shiftLeft(k - 1)).shiftRight(k);
    }

    private static long referenceInteger(Op op, long n, long m, int esz) {
        int bits = Byte.SIZE << esz;
        BigInteger un = asUnsigned(n, bits);
        BigInteger um = asUnsigned(m, bits);
        BigInteger sn = asSigned(un, bits);
        BigInteger sm = asSigned(um, bits);
        BigInteger result = switch (op) {
            case SMAX -> sn.max(sm);
            case UMAX -> un.max(um);
            case SMIN -> sn.min(sm);
            case UMIN -> un.min(um);
            case ADD -> un.add(um);
            case SRSHL -> roundingShift(sn, sm);
            case URSHL -> roundingShift(un, sm);
            case SQDMULH -> sn.multiply(sm).shiftLeft(1).shiftRight(bits)
                    .min(BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE));
            default -> throw new IllegalArgumentException(op.name());
        };
        return result.mod(BigInteger.ONE.shiftLeft(bits)).longValue();
    }

    // ── referência de ponto flutuante ────────────────────────────────────────────────────────────

    private static double decodeFp(long bits, int esz) {
        return switch (esz) {
            case 1 -> Float.float16ToFloat((short) bits);
            case 2 -> Float.intBitsToFloat((int) bits);
            default -> Double.longBitsToDouble(bits);
        };
    }

    private static long encodeFp(double value, int esz) {
        return switch (esz) {
            case 1 -> Float.floatToFloat16((float) value) & 0xFFFFL;
            case 2 -> Float.floatToRawIntBits((float) value) & 0xFFFF_FFFFL;
            default -> Double.doubleToRawLongBits(value);
        };
    }

    private static long referenceFp(Op op, long n, long m, int esz) {
        double a = decodeFp(n, esz);
        double b = decodeFp(m, esz);
        return switch (op) {
            case FMAX, FMAXNM -> encodeFp(Math.max(a, b), esz);
            case FMIN, FMINNM -> encodeFp(Math.min(a, b), esz);
            default -> throw new IllegalArgumentException(op.name());
        };
    }

    // ── geração de dados ─────────────────────────────────────────────────────────────────────────

    private static void fillRandom(Aarch64Core core, Random random) {
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int w = 0; w < core.streamingVectorLengthBytes() / Long.BYTES; w++) {
                core.scalable().setZWord(z, w, random.nextLong());
            }
        }
    }

    /// Elementos de `Zm` alternando bits aleatórios e quantidades PEQUENAS (os dois regimes de `SRSHL`/`URSHL`), com
    /// o mínimo e o máximo com sinal injetados (saturação do `SQDMULH`).
    private static void fillIntegerOperand(Aarch64Core core, int z, int esz, Random random) {
        int bits = Byte.SIZE << esz;
        for (int e = 0; e < elements(core, esz); e++) {
            long value = e % 2 == 0 ? random.nextLong() : random.nextInt(41) - 20;
            if (e % 7 == 3) {
                value = 1L << (bits - 1);
            } else if (e % 5 == 1) {
                value = mask(bits) >>> 1;
            }
            put(core, z, e, esz, value);
        }
    }

    private static void fillFpOperand(Aarch64Core core, int z, int esz, Random random) {
        for (int e = 0; e < elements(core, esz); e++) {
            double value = e % 9 == 4 ? -0.0 : e % 9 == 5 ? 0.0 : (random.nextDouble() - 0.5) * 200.0;
            put(core, z, e, esz, encodeFp(value, esz));
        }
    }

    // ── as 13 operações × esz × x2/x4 × SVL ──────────────────────────────────────────────────────

    @Test
    void everyOperationMatchesTheReferenceForEveryElementSize() {
        Random random = new Random(0x18_07);
        for (int svl : SVLS) {
            for (Op op : Op.values()) {
                for (int esz = op.isFloatingPoint() ? 1 : 0; esz <= 3; esz++) {
                    for (int count : new int[] {2, 4}) {
                        checkOperation(svl, op, esz, count, count == 2 ? 6 : 12, 9, random);
                    }
                }
            }
        }
    }

    private static void checkOperation(int svl, Op op, int esz, int count, int zdn, int zm, Random random) {
        Aarch64Core core = core(svl, SVCR_SM);
        fillRandom(core, random);
        for (int i = 0; i < count; i++) {
            if (op.isFloatingPoint()) {
                fillFpOperand(core, zdn + i, esz, random);
            } else {
                fillIntegerOperand(core, zdn + i, esz, random);
            }
        }
        if (op == Op.FSCALE) {
            fillScaleExponents(core, zm, esz, random);
            for (int i = 0; i < count; i++) {
                fillScaleMantissas(core, zdn + i, esz, random);
            }
        } else if (op.isFloatingPoint()) {
            fillFpOperand(core, zm, esz, random);
        } else {
            fillIntegerOperand(core, zm, esz, random);
        }
        long[][] before = snapshot(core);

        run(core, word(op, esz, count, zdn, zm));

        String context = op + " esz=" + esz + " x" + count + " svl=" + svl;
        for (int z = 0; z < Z_REGISTERS; z++) {
            if (z < zdn || z >= zdn + count) {
                assertRegisterUnchanged(core, before, z);
            }
        }
        for (int i = 0; i < count; i++) {
            for (int e = 0; e < elements(core, esz); e++) {
                long n = elementOf(before, zdn + i, e, esz);
                long m = elementOf(before, zm, e, esz);
                long expected = switch (op) {
                    case FMAX, FMIN, FMAXNM, FMINNM -> referenceFp(op, n, m, esz);
                    case FSCALE -> referenceScale(n, m, esz);
                    default -> referenceInteger(op, n, m, esz);
                };
                assertEquals(expected, get(core, zdn + i, e, esz), context + " Z" + (zdn + i) + "[" + e + "]");
            }
        }
    }

    private static long elementOf(long[][] regs, int z, int e, int esz) {
        int bits = Byte.SIZE << esz;
        int bit = e * bits;
        return (regs[z][bit >>> WORD_INDEX_SHIFT] >>> (bit & WORD_BIT_MASK)) & mask(bits);
    }

    // FSCALE: `Zm` guarda o EXPOENTE inteiro com sinal; os mantissas ficam longe de subnormais/estouro.
    private static void fillScaleExponents(Aarch64Core core, int z, int esz, Random random) {
        for (int e = 0; e < elements(core, esz); e++) {
            put(core, z, e, esz, random.nextInt(13) - 6);
        }
    }

    private static void fillScaleMantissas(Aarch64Core core, int z, int esz, Random random) {
        for (int e = 0; e < elements(core, esz); e++) {
            put(core, z, e, esz, encodeFp(1.0 + random.nextInt(63) * (random.nextBoolean() ? 1 : -1), esz));
        }
    }

    private static long referenceScale(long n, long m, int esz) {
        int bits = Byte.SIZE << esz;
        int exponent = (int) asSigned(asUnsigned(m, bits), bits).longValue();
        return encodeFp(Math.scalb(decodeFp(n, esz), exponent), esz);
    }

    // ── forma do grupo ───────────────────────────────────────────────────────────────────────────

    @Test
    void x2WritesExactlyTwoRegistersAndX4ExactlyFour() {
        for (int count : new int[] {2, 4}) {
            Aarch64Core core = core(256, SVCR_SM);
            for (int z = 0; z < 8; z++) {
                for (int w = 0; w < 4; w++) {
                    core.scalable().setZWord(z, w, 0x0101010101010101L * (z + 1));
                }
            }
            long[][] before = snapshot(core);
            int zdn = count == 2 ? 2 : 4;
            run(core, word(Op.ADD, 0, count, zdn, 1));
            for (int z = 0; z < 8; z++) {
                boolean inGroup = z >= zdn && z < zdn + count;
                if (inGroup) {
                    assertEquals(before[z][0] + before[1][0], core.scalable().zWord(z, 0), "Z" + z + " devia somar Z1");
                } else {
                    assertRegisterUnchanged(core, before, z);
                }
            }
        }
    }

    @Test
    void baseExtractorAddressesTwiceTheFieldNotTheFieldItself() {
        // %zd_ax2 com campo 0b0011 endereça Z6/Z7, não Z3/Z4.
        Aarch64Core core = core(256, SVCR_SM);
        for (int z = 0; z < 12; z++) {
            core.scalable().setZWord(z, 0, z);
        }
        run(core, 0xC124A300 | 0b0011 << 1);
        assertEquals(3L, core.scalable().zWord(3, 0), "Z3 intacto");
        assertEquals(6L + 4L, core.scalable().zWord(6, 0), "Z6 += Z4");
        assertEquals(7L + 4L, core.scalable().zWord(7, 0), "Z7 += Z4");
        assertEquals(8L, core.scalable().zWord(8, 0), "Z8 intacto");
    }

    @Test
    void anOperandThatIsAlsoAGroupMemberIsReadBeforeAnyWrite() {
        Aarch64Core core = core(256, SVCR_SM);
        core.scalable().setZWord(2, 0, 10);
        core.scalable().setZWord(3, 0, 100);
        // add {z2.d-z3.d}, {z2.d-z3.d}, z3.d — Zm é o segundo membro.
        run(core, word(Op.ADD, 3, 2, 2, 3));
        assertEquals(110L, core.scalable().zWord(2, 0), "Z2 = 10 + Z3 original");
        assertEquals(200L, core.scalable().zWord(3, 0), "Z3 = 100 + Z3 original");
    }

    // ── semântica específica ─────────────────────────────────────────────────────────────────────

    @Test
    void srshlUsesTheWholeElementAsTheShiftAmountUnlikeSve() {
        Aarch64Core core = core(256, SVCR_SM);
        put(core, 6, 0, 1, 0x0001);
        put(core, 4, 0, 1, 0x0100); // 256: o byte baixo seria 0 (sem deslocamento), o elemento inteiro empurra para fora
        run(core, word(Op.SRSHL, 1, 2, 6, 4));
        assertEquals(0L, get(core, 6, 0, 1), "SME2 usa os 16 bits de Zm: deslocar 256 apaga o elemento");

        put(core, 6, 1, 1, 0x0003);
        put(core, 4, 1, 1, 0xFFFF); // -1 → arredonda 3/2 = 1.5 → 2
        run(core, word(Op.SRSHL, 1, 2, 6, 4));
        assertEquals(2L, get(core, 6, 1, 1), "deslocamento negativo arredonda");
    }

    @Test
    void fmaxPropagatesTheNanWhileFmaxnmReturnsTheNumber() {
        long quietNan = 0x7FC00000L;
        long one = Float.floatToRawIntBits(1.0f);
        for (Op op : new Op[] {Op.FMAX, Op.FMAXNM, Op.FMIN, Op.FMINNM}) {
            boolean propagates = op == Op.FMAX || op == Op.FMIN;
            for (boolean nanInMultiple : new boolean[] {true, false}) {
                Aarch64Core core = core(256, SVCR_SM);
                put(core, 2, 0, 2, nanInMultiple ? quietNan : one);
                put(core, 4, 0, 2, nanInMultiple ? one : quietNan);
                run(core, word(op, 2, 2, 2, 4));
                assertEquals(propagates ? quietNan : one, get(core, 2, 0, 2), op + " nanNoGrupo=" + nanInMultiple);
            }
        }
    }

    @Test
    void signalingNanRaisesInvalidOperationInFpsrAndComesOutQuiet() {
        Aarch64Core core = core(256, SVCR_SM);
        put(core, 2, 0, 2, 0x7FA00000L);
        put(core, 4, 0, 2, Float.floatToRawIntBits(1.0f));
        run(core, word(Op.FMAX, 2, 2, 2, 4));
        assertEquals(0x7FE00000L, get(core, 2, 0, 2), "sNaN sai silenciado");
        assertEquals(FPSR_IOC, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR) & FPSR_IOC);
    }

    // ── acesso ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void outsideStreamingModeEntersTheSmeTrapAndWritesNothing() {
        Aarch64Core core = core(256, 0);
        core.scalable().setZWord(2, 0, 1);
        long[][] before = snapshot(core);
        run(core, word(Op.ADD, 0, 2, 2, 4));
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(SMTC_NOT_STREAMING, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & SMTC_MASK);
        for (int z = 0; z < Z_REGISTERS; z++) {
            assertRegisterUnchanged(core, before, z);
        }
    }
}
