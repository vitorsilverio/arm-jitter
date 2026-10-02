package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.SmeOp64.ArrayMultiVector.Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.SmeArrayIndexedWords;
import dev.vitorsilverio.armjitter.support.SmeArrayIndexedWords.Word;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.11 — SME2 multi-vetor **indexado** (`_nx`) e dot **vertical**, 113 linhas. O programa de cada teste é uma das 339
/// palavras desmontadas pelo `objdump` do devkitA64 ({@link SmeArrayIndexedWords}), com os campos (`W<rv>`, `off`, `zn`,
/// `zm`, índice) lidos do TEXTO do disassembler. Cada operação é conferida contra uma referência escrita AQUI — inteiros
/// em `long`, ponto flutuante em `Math.fma`/`float`/`double`, `FP8` decodificado à mão — com a indexação por segmento
/// de 128 bits escrita por DIVISÃO (`(e / lanesPerSegment) * ...`), não pela máscara que o executor usa, e o dot vertical
/// transcrito do pseudocódigo do manual da Arm (`SVDOT`/`FVDOT`/`FVDOTB`/`FVDOT_hb`). O endereçamento (`((W arredondado
/// para baixo a nsel) + off) MOD (SVL/n)`, membros a `SVL/n` linhas de distância) é conferido contra `ZA` INTEIRO.
class Aarch64SmeArrayIndexedExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-azx-exec", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_I16I64,
            Aarch64Feature.SME_F64F64, Aarch64Feature.SME_F16F16, Aarch64Feature.SME_B16B16,
            Aarch64Feature.SME_F8F32, Aarch64Feature.SME_F8F16);

    private static final long SVCR_SM = Aarch64Core.SVCR_SM_BIT;
    private static final long SVCR_ZA = Aarch64Core.SVCR_ZA_BIT;
    private static final long VBAR = 0x400L;
    private static final int EC_SME = 0x1D;
    private static final int ESR_EC_SHIFT = 26;
    private static final int Z_REGISTERS = 32;
    private static final int[] SVLS = {256, 512};
    private static final int TRIALS = 3;
    private static final long M32 = 0xFFFF_FFFFL;
    private static final long M16 = 0xFFFFL;
    private static final long M8 = 0xFFL;
    private static final int ESZ_BYTE = 0;
    private static final int ESZ_HALF = 1;
    private static final int ESZ_SINGLE = 2;
    private static final int ESZ_DOUBLE = 3;
    private static final int E5M2_ONE = 0x3C;
    private static final int E5M2_TWO = 0x40;

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

    static Stream<Arguments> wordsAndSvl() {
        return SmeArrayIndexedWords.all().stream()
                .flatMap(word -> Arrays.stream(SVLS).mapToObj(svl -> Arguments.of(word, svl)));
    }

    private static Word word(Op op, int count) {
        return SmeArrayIndexedWords.all().stream().filter(w -> w.op() == op && w.count() == count
                && (w.zm() < w.zn() || w.zm() > w.zn() + 3)).findFirst()
                .orElseThrow();
    }

    // ── propriedades de cada operação, escritas AQUI (independentes do IR) ──────────────────────

    /// Vetores de `ZA` escritos POR MEMBRO (`nsel`): quantos produtos distintos cada membro do grupo consome.
    private static int step(Op op) {
        return switch (op) {
            case FMLAL, FMLSL, BFMLAL, BFMLSL, SMLAL, SMLSL, UMLAL, UMLSL, FMLAL_HB -> 2;
            case SMLALL_S, SMLSLL_S, UMLALL_S, UMLSLL_S, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D, USMLALL, SUMLALL,
                    FMLALL_B -> 4;
            default -> 1;
        };
    }

    /// Tamanho do elemento do vetor de `ZA` (`1` = half/bfloat16, `2` = word, `3` = doubleword).
    private static int accumulatorEsz(Op op) {
        return switch (op) {
            case SDOT_4H, UDOT_4H, SVDOT_4H, UVDOT_4H, SMLALL_D, SMLSLL_D, UMLALL_D, UMLSLL_D, FMLA_D, FMLS_D ->
                    ESZ_DOUBLE;
            case FMLA_H, FMLS_H, BFMLA, BFMLS, FMLAL_HB, FDOT_HB, FVDOT_HB -> ESZ_HALF;
            default -> ESZ_SINGLE;
        };
    }

    private enum Fmt { INT, HALF, BF16, SINGLE, SINGLE_RANDOM, DOUBLE, FP8 }

    private static Fmt source(Op op) {
        return switch (op) {
            case FMLAL, FMLSL, FDOT, FMLA_H, FMLS_H, FVDOT_SH -> Fmt.HALF;
            case BFMLAL, BFMLSL, BFDOT, BFMLA, BFMLS, BFVDOT -> Fmt.BF16;
            case FMLA_S, FMLS_S -> Fmt.SINGLE_RANDOM;
            case FMLA_D, FMLS_D -> Fmt.DOUBLE;
            case FMLALL_B, FDOT_SB, FMLAL_HB, FDOT_HB, FVDOTB, FVDOTT, FVDOT_HB -> Fmt.FP8;
            default -> Fmt.INT;
        };
    }

    private static Fmt accumulator(Op op) {
        return switch (op) {
            case FMLAL, FMLSL, BFMLAL, BFMLSL, FDOT, BFDOT, FMLALL_B, FDOT_SB, FVDOT_SH, BFVDOT, FVDOTB, FVDOTT ->
                    Fmt.SINGLE;
            case FMLA_H, FMLS_H, FMLAL_HB, FDOT_HB, FVDOT_HB -> Fmt.HALF;
            case BFMLA, BFMLS -> Fmt.BF16;
            case FMLA_S, FMLS_S -> Fmt.SINGLE_RANDOM;
            case FMLA_D, FMLS_D -> Fmt.DOUBLE;
            default -> Fmt.INT;
        };
    }

    private static int elementSize(Fmt format) {
        return switch (format) {
            case INT, DOUBLE -> ESZ_DOUBLE;
            case HALF, BF16 -> ESZ_HALF;
            case SINGLE, SINGLE_RANDOM -> ESZ_SINGLE;
            case FP8 -> ESZ_BYTE;
        };
    }

    private static long value(Fmt format, Random random) {
        return switch (format) {
            case INT -> random.nextLong();
            case HALF -> Float.floatToFloat16((random.nextInt(33) - 16) / 4f) & M16;
            case BF16 -> (Float.floatToRawIntBits((random.nextInt(13) - 6) / 2f) >>> 16) & M16;
            case SINGLE -> Float.floatToRawIntBits((random.nextInt(33) - 16) / 4f) & M32;
            case SINGLE_RANDOM -> Float.floatToRawIntBits(random.nextFloat() * 200f - 100f) & M32;
            case DOUBLE -> Double.doubleToRawLongBits(random.nextDouble() * 200.0 - 100.0);
            // E5M2: expoente 14..16 e fração 0 (±0,5 / ±1 / ±2) — somas de até 4 produtos continuam exatas em half.
            case FP8 -> (random.nextBoolean() ? 0x80 : 0) | (14 + random.nextInt(3)) << 2;
        };
    }

    // ── acesso a Z e ZA (independente do código de produção) ─────────────────────────────────────

    private static long mask(int bits) {
        return bits == Long.SIZE ? -1L : (1L << bits) - 1L;
    }

    private static long lane(long[] words, int esz, int index) {
        int bits = Byte.SIZE << esz;
        int bit = index * bits;
        return (words[bit / Long.SIZE] >>> (bit % Long.SIZE)) & mask(bits);
    }

    private static void put(long[] words, int esz, int index, long value) {
        int bits = Byte.SIZE << esz;
        int bit = index * bits;
        long place = mask(bits) << (bit % Long.SIZE);
        words[bit / Long.SIZE] = words[bit / Long.SIZE] & ~place | value << (bit % Long.SIZE) & place;
    }

    private static long[][] snapshotZ(Aarch64Core core) {
        long[][] regs = new long[Z_REGISTERS][core.streamingVectorLengthBytes() / Long.BYTES];
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int w = 0; w < regs[z].length; w++) {
                regs[z][w] = core.scalable().zWord(z, w);
            }
        }
        return regs;
    }

    private static long[][] snapshotZa(Aarch64Core core) {
        int rows = core.streamingVectorLengthBytes();
        int rowWords = rows / Long.BYTES;
        long[][] za = new long[rows][rowWords];
        for (int row = 0; row < rows; row++) {
            for (int w = 0; w < rowWords; w++) {
                za[row][w] = core.matrix().zaWord(row * rowWords + w);
            }
        }
        return za;
    }

    private static void fillRandom(Aarch64Core core, Op op, Random random) {
        Fmt src = source(op);
        int esz = elementSize(src);
        for (int z = 0; z < Z_REGISTERS; z++) {
            long[] words = new long[core.streamingVectorLengthBytes() / Long.BYTES];
            for (int i = 0; i < (core.streamingVectorLengthBytes() >>> esz); i++) {
                put(words, esz, i, value(src, random));
            }
            for (int w = 0; w < words.length; w++) {
                core.scalable().setZWord(z, w, words[w]);
            }
        }
        Fmt acc = accumulator(op);
        int accEsz = accumulatorEsz(op);
        int rows = core.streamingVectorLengthBytes();
        int rowWords = rows / Long.BYTES;
        for (int row = 0; row < rows; row++) {
            long[] words = new long[rowWords];
            for (int i = 0; i < (rows >>> accEsz); i++) {
                put(words, accEsz, i, value(acc, random));
            }
            for (int w = 0; w < rowWords; w++) {
                core.matrix().setZaWord(row * rowWords + w, words[w]);
            }
        }
    }

    // ── conversões ───────────────────────────────────────────────────────────────────────────────

    private static float h(long bits) {
        return Float.float16ToFloat((short) bits);
    }

    private static float bf(long bits) {
        return Float.intBitsToFloat((int) (bits << 16));
    }

    private static float f(long bits) {
        return Float.intBitsToFloat((int) bits);
    }

    private static double d(long bits) {
        return Double.longBitsToDouble(bits);
    }

    private static long fb(float v) {
        return Float.floatToRawIntBits(v) & M32;
    }

    private static long hb(float v) {
        return Float.floatToFloat16(v) & M16;
    }

    private static long bb(float v) {
        return (Float.floatToRawIntBits(v) >>> 16) & M16;
    }

    /// `E5M2` normalizado: `(−1)^s × 2^(e−15) × (1 + f/4)`.
    private static double fp8(long bits) {
        double magnitude = Math.scalb(1.0 + (bits & 3) / 4.0, (int) (bits >> 2 & 31) - 15);
        return (bits & 0x80) != 0 ? -magnitude : magnitude;
    }

    private static long s8(long v) {
        return (byte) v;
    }

    private static long u8(long v) {
        return v & M8;
    }

    private static long s16(long v) {
        return (short) v;
    }

    private static long u16(long v) {
        return v & M16;
    }

    // ── referência ───────────────────────────────────────────────────────────────────────────────

    /// Novo valor da lane `e` do vetor de `ZA` escrito pelo vetor `sel` do membro cujo `Zn` é `n`, `Zm` = `m` e o
    /// índice é `idx` (SEGMENTO de 128 bits por divisão: `e / (lanes de acumulador por segmento)`).
    private static long reference(Op op, long acc, int e, int sel, long[] n, long[] m, int idx) {
        return switch (op) {
            case FMLAL -> fb(Math.fma(h(lane(n, ESZ_HALF, 2 * e + sel)), h(lane(m, ESZ_HALF, e / 4 * 8 + idx)), f(acc)));
            case FMLSL -> fb(Math.fma(-h(lane(n, ESZ_HALF, 2 * e + sel)), h(lane(m, ESZ_HALF, e / 4 * 8 + idx)), f(acc)));
            case BFMLAL -> fb(Math.fma(bf(lane(n, ESZ_HALF, 2 * e + sel)), bf(lane(m, ESZ_HALF, e / 4 * 8 + idx)), f(acc)));
            case BFMLSL ->
                    fb(Math.fma(-bf(lane(n, ESZ_HALF, 2 * e + sel)), bf(lane(m, ESZ_HALF, e / 4 * 8 + idx)), f(acc)));
            case FDOT -> {
                int g = 2 * (e / 4 * 4 + idx);
                yield fb((float) (f(acc) + (double) h(lane(n, ESZ_HALF, 2 * e)) * h(lane(m, ESZ_HALF, g))
                        + (double) h(lane(n, ESZ_HALF, 2 * e + 1)) * h(lane(m, ESZ_HALF, g + 1))));
            }
            case BFDOT -> {
                int g = 2 * (e / 4 * 4 + idx);
                yield fb((float) (f(acc) + (double) bf(lane(n, ESZ_HALF, 2 * e)) * bf(lane(m, ESZ_HALF, g))
                        + (double) bf(lane(n, ESZ_HALF, 2 * e + 1)) * bf(lane(m, ESZ_HALF, g + 1))));
            }
            case USDOT -> acc + bytes4(n, m, e, e / 4 * 4 + idx, false, true) & M32;
            case SUDOT -> acc + bytes4(n, m, e, e / 4 * 4 + idx, true, false) & M32;
            case SDOT_4B -> acc + bytes4(n, m, e, e / 4 * 4 + idx, true, true) & M32;
            case UDOT_4B -> acc + bytes4(n, m, e, e / 4 * 4 + idx, false, false) & M32;
            case SDOT_4H -> acc + halves4(n, m, e, e / 2 * 2 + idx, true);
            case UDOT_4H -> acc + halves4(n, m, e, e / 2 * 2 + idx, false);
            case SDOT_2H -> {
                int g = 2 * (e / 4 * 4 + idx);
                yield acc + s16(lane(n, ESZ_HALF, 2 * e)) * s16(lane(m, ESZ_HALF, g))
                        + s16(lane(n, ESZ_HALF, 2 * e + 1)) * s16(lane(m, ESZ_HALF, g + 1)) & M32;
            }
            case UDOT_2H -> {
                int g = 2 * (e / 4 * 4 + idx);
                yield acc + u16(lane(n, ESZ_HALF, 2 * e)) * u16(lane(m, ESZ_HALF, g))
                        + u16(lane(n, ESZ_HALF, 2 * e + 1)) * u16(lane(m, ESZ_HALF, g + 1)) & M32;
            }
            case SMLAL -> acc + s16(lane(n, ESZ_HALF, 2 * e + sel)) * s16(lane(m, ESZ_HALF, e / 4 * 8 + idx)) & M32;
            case SMLSL -> acc - s16(lane(n, ESZ_HALF, 2 * e + sel)) * s16(lane(m, ESZ_HALF, e / 4 * 8 + idx)) & M32;
            case UMLAL -> acc + u16(lane(n, ESZ_HALF, 2 * e + sel)) * u16(lane(m, ESZ_HALF, e / 4 * 8 + idx)) & M32;
            case UMLSL -> acc - u16(lane(n, ESZ_HALF, 2 * e + sel)) * u16(lane(m, ESZ_HALF, e / 4 * 8 + idx)) & M32;
            case SMLALL_S -> acc + s8(lane(n, ESZ_BYTE, 4 * e + sel)) * s8(lane(m, ESZ_BYTE, e / 4 * 16 + idx)) & M32;
            case SMLSLL_S -> acc - s8(lane(n, ESZ_BYTE, 4 * e + sel)) * s8(lane(m, ESZ_BYTE, e / 4 * 16 + idx)) & M32;
            case UMLALL_S -> acc + u8(lane(n, ESZ_BYTE, 4 * e + sel)) * u8(lane(m, ESZ_BYTE, e / 4 * 16 + idx)) & M32;
            case UMLSLL_S -> acc - u8(lane(n, ESZ_BYTE, 4 * e + sel)) * u8(lane(m, ESZ_BYTE, e / 4 * 16 + idx)) & M32;
            case USMLALL -> acc + u8(lane(n, ESZ_BYTE, 4 * e + sel)) * s8(lane(m, ESZ_BYTE, e / 4 * 16 + idx)) & M32;
            case SUMLALL -> acc + s8(lane(n, ESZ_BYTE, 4 * e + sel)) * u8(lane(m, ESZ_BYTE, e / 4 * 16 + idx)) & M32;
            case SMLALL_D -> acc + s16(lane(n, ESZ_HALF, 4 * e + sel)) * s16(lane(m, ESZ_HALF, e / 2 * 8 + idx));
            case SMLSLL_D -> acc - s16(lane(n, ESZ_HALF, 4 * e + sel)) * s16(lane(m, ESZ_HALF, e / 2 * 8 + idx));
            case UMLALL_D -> acc + u16(lane(n, ESZ_HALF, 4 * e + sel)) * u16(lane(m, ESZ_HALF, e / 2 * 8 + idx));
            case UMLSLL_D -> acc - u16(lane(n, ESZ_HALF, 4 * e + sel)) * u16(lane(m, ESZ_HALF, e / 2 * 8 + idx));
            case BFMLA -> bb(bf(acc) + bf(lane(n, ESZ_HALF, e)) * bf(lane(m, ESZ_HALF, e / 8 * 8 + idx)));
            case BFMLS -> bb(bf(acc) - bf(lane(n, ESZ_HALF, e)) * bf(lane(m, ESZ_HALF, e / 8 * 8 + idx)));
            case FMLA_H -> hb(h(acc) + h(lane(n, ESZ_HALF, e)) * h(lane(m, ESZ_HALF, e / 8 * 8 + idx)));
            case FMLS_H -> hb(h(acc) - h(lane(n, ESZ_HALF, e)) * h(lane(m, ESZ_HALF, e / 8 * 8 + idx)));
            case FMLA_S -> fb(Math.fma(f(lane(n, ESZ_SINGLE, e)), f(lane(m, ESZ_SINGLE, e / 4 * 4 + idx)), f(acc)));
            case FMLS_S -> fb(Math.fma(-f(lane(n, ESZ_SINGLE, e)), f(lane(m, ESZ_SINGLE, e / 4 * 4 + idx)), f(acc)));
            case FMLA_D -> Double.doubleToRawLongBits(
                    Math.fma(d(lane(n, ESZ_DOUBLE, e)), d(lane(m, ESZ_DOUBLE, e / 2 * 2 + idx)), d(acc)));
            case FMLS_D -> Double.doubleToRawLongBits(
                    Math.fma(-d(lane(n, ESZ_DOUBLE, e)), d(lane(m, ESZ_DOUBLE, e / 2 * 2 + idx)), d(acc)));
            case FMLALL_B -> fb((float) (f(acc)
                    + fp8(lane(n, ESZ_BYTE, 4 * e + sel)) * fp8(lane(m, ESZ_BYTE, e / 4 * 16 + idx))));
            case FDOT_SB -> {
                double sum = f(acc);
                for (int k = 0; k < 4; k++) {
                    sum += fp8(lane(n, ESZ_BYTE, 4 * e + k)) * fp8(lane(m, ESZ_BYTE, 4 * (e / 4 * 4 + idx) + k));
                }
                yield fb((float) sum);
            }
            case FMLAL_HB -> hb((float) (h(acc)
                    + fp8(lane(n, ESZ_BYTE, 2 * e + sel)) * fp8(lane(m, ESZ_BYTE, e / 8 * 16 + idx))));
            case FDOT_HB -> {
                double sum = h(acc);
                for (int k = 0; k < 2; k++) {
                    sum += fp8(lane(n, ESZ_BYTE, 2 * e + k)) * fp8(lane(m, ESZ_BYTE, 2 * (e / 8 * 8 + idx) + k));
                }
                yield hb((float) sum);
            }
            default -> throw new IllegalArgumentException(op + ": não é uma forma não vertical");
        };
    }

    /// Dot vertical (pseudocódigo do manual): `member` = `r`, `z` = o banco inteiro (os `k` registradores `zn..zn+k-1`
    /// do grupo e `Zm`).
    private static long vertical(Op op, long acc, int e, int member, long[][] z, int zn, int zm, int idx) {
        long[] m = z[zm];
        return switch (op) {
            case SVDOT_2H -> acc + vsum(z, zn, 2, 2 * e + member, m, 2 * (e / 4 * 4 + idx), ESZ_HALF, true, true) & M32;
            case UVDOT_2H -> acc + vsum(z, zn, 2, 2 * e + member, m, 2 * (e / 4 * 4 + idx), ESZ_HALF, false, false) & M32;
            case SVDOT_4B -> acc + vsum(z, zn, 4, 4 * e + member, m, 4 * (e / 4 * 4 + idx), ESZ_BYTE, true, true) & M32;
            case UVDOT_4B -> acc + vsum(z, zn, 4, 4 * e + member, m, 4 * (e / 4 * 4 + idx), ESZ_BYTE, false, false) & M32;
            case SUVDOT -> acc + vsum(z, zn, 4, 4 * e + member, m, 4 * (e / 4 * 4 + idx), ESZ_BYTE, true, false) & M32;
            case USVDOT -> acc + vsum(z, zn, 4, 4 * e + member, m, 4 * (e / 4 * 4 + idx), ESZ_BYTE, false, true) & M32;
            case SVDOT_4H -> acc + vsum(z, zn, 4, 4 * e + member, m, 4 * (e / 2 * 2 + idx), ESZ_HALF, true, true);
            case UVDOT_4H -> acc + vsum(z, zn, 4, 4 * e + member, m, 4 * (e / 2 * 2 + idx), ESZ_HALF, false, false);
            case FVDOT_SH -> {
                int s = e / 4 * 4 + idx;
                yield fb((float) (f(acc) + (double) h(lane(z[zn], ESZ_HALF, 2 * e + member)) * h(lane(m, ESZ_HALF, 2 * s))
                        + (double) h(lane(z[zn + 1], ESZ_HALF, 2 * e + member)) * h(lane(m, ESZ_HALF, 2 * s + 1))));
            }
            case BFVDOT -> {
                int s = e / 4 * 4 + idx;
                yield fb((float) (f(acc) + (double) bf(lane(z[zn], ESZ_HALF, 2 * e + member)) * bf(lane(m, ESZ_HALF, 2 * s))
                        + (double) bf(lane(z[zn + 1], ESZ_HALF, 2 * e + member)) * bf(lane(m, ESZ_HALF, 2 * s + 1))));
            }
            case FVDOTB, FVDOTT -> {
                // op1 = {Zn+1[4e+r], Zn[4e+r]}; op2 = elemento de 16 bits `2*s (+1 no top)` de Zm, s = segmento + idx.
                int s = e / 4 * 4 + idx;
                int mByte = 2 * (2 * s + (op == Op.FVDOTT ? 1 : 0));
                yield fb((float) (f(acc) + fp8(lane(z[zn], ESZ_BYTE, 4 * e + member)) * fp8(lane(m, ESZ_BYTE, mByte))
                        + fp8(lane(z[zn + 1], ESZ_BYTE, 4 * e + member)) * fp8(lane(m, ESZ_BYTE, mByte + 1))));
            }
            case FVDOT_HB -> {
                int s = e / 8 * 8 + idx;
                yield hb((float) (h(acc) + fp8(lane(z[zn], ESZ_BYTE, 2 * e + member)) * fp8(lane(m, ESZ_BYTE, 2 * s))
                        + fp8(lane(z[zn + 1], ESZ_BYTE, 2 * e + member)) * fp8(lane(m, ESZ_BYTE, 2 * s + 1))));
            }
            default -> throw new IllegalArgumentException(op + ": não é dot vertical");
        };
    }

    /// `Σ_i  Zn+i[nIndex] × Zm[mBase + i]` para `k` registradores consecutivos.
    private static long vsum(long[][] z, int zn, int k, int nIndex, long[] m, int mBase, int esz, boolean nSigned,
            boolean mSigned) {
        long sum = 0;
        for (int i = 0; i < k; i++) {
            long a = lane(z[zn + i], esz, nIndex);
            long b = lane(m, esz, mBase + i);
            long x = esz == ESZ_BYTE ? (nSigned ? s8(a) : u8(a)) : nSigned ? s16(a) : u16(a);
            long y = esz == ESZ_BYTE ? (mSigned ? s8(b) : u8(b)) : mSigned ? s16(b) : u16(b);
            sum += x * y;
        }
        return sum;
    }

    private static long bytes4(long[] n, long[] m, int e, int group, boolean nSigned, boolean mSigned) {
        long sum = 0;
        for (int k = 0; k < 4; k++) {
            long a = lane(n, ESZ_BYTE, 4 * e + k);
            long b = lane(m, ESZ_BYTE, 4 * group + k);
            sum += (nSigned ? s8(a) : u8(a)) * (mSigned ? s8(b) : u8(b));
        }
        return sum;
    }

    private static long halves4(long[] n, long[] m, int e, int group, boolean signed) {
        long sum = 0;
        for (int k = 0; k < 4; k++) {
            long a = lane(n, ESZ_HALF, 4 * e + k);
            long b = lane(m, ESZ_HALF, 4 * group + k);
            sum += signed ? s16(a) * s16(b) : u16(a) * u16(b);
        }
        return sum;
    }

    /// Aplica a instrução ao modelo: devolve o `ZA` esperado (e as linhas escritas em `written`).
    private static long[][] expectedZa(Word w, int svl, long registerValue, long[][] z, long[][] before,
            Set<Integer> written) {
        Op op = w.op();
        int svlBytes = svl / Byte.SIZE;
        int step = step(op);
        int rowsPerMember = svlBytes / w.count();
        int base = (int) (((registerValue & M32 & ~(step - 1L)) + w.off()) % rowsPerMember);
        int accEsz = accumulatorEsz(op);
        long[][] expected = new long[before.length][];
        for (int row = 0; row < before.length; row++) {
            expected[row] = before[row].clone();
        }
        for (int member = 0; member < w.count(); member++) {
            for (int sel = 0; sel < step; sel++) {
                int row = base + member * rowsPerMember + sel;
                written.add(row);
                for (int e = 0; e < (svlBytes >>> accEsz); e++) {
                    long acc = lane(expected[row], accEsz, e);
                    long result = op.vertical() ? vertical(op, acc, e, member, z, w.zn(), w.zm(), w.index())
                            : reference(op, acc, e, sel, z[(w.zn() + member) % Z_REGISTERS], z[w.zm()], w.index());
                    put(expected[row], accEsz, e, result);
                }
            }
        }
        return expected;
    }

    private static void assertZa(long[][] expected, Aarch64Core core, Set<Integer> written, String what) {
        int rowWords = expected[0].length;
        for (int row = 0; row < expected.length; row++) {
            for (int wd = 0; wd < rowWords; wd++) {
                assertEquals(expected[row][wd], core.matrix().zaWord(row * rowWords + wd),
                        what + ": linha " + row + " palavra " + wd + (written.contains(row) ? " (escrita)" : " (INTACTA)"));
            }
        }
    }

    private static void assertZUnchanged(long[][] before, Aarch64Core core) {
        for (int z = 0; z < Z_REGISTERS; z++) {
            for (int wd = 0; wd < before[z].length; wd++) {
                assertEquals(before[z][wd], core.scalable().zWord(z, wd), "Z" + z + " palavra " + wd + " não podia mudar");
            }
        }
    }

    // ── as 339 palavras × SVL 256/512 contra a referência ────────────────────────────────────────

    @ParameterizedTest(name = "{0} @SVL{1}")
    @MethodSource("wordsAndSvl")
    void matchesTheReferenceForEveryEncoding(Word w, int svl) {
        Random random = new Random(w.word() * 1_000_003L + svl);
        for (int trial = 0; trial < TRIALS; trial++) {
            Aarch64Core core = core(svl, SVCR_SM | SVCR_ZA);
            fillRandom(core, w.op(), random);
            long registerValue = trial == 0 ? 0L : random.nextLong();
            core.setX(w.register(), registerValue);
            long[][] z = snapshotZ(core);
            Set<Integer> written = new HashSet<>();
            long[][] expected = expectedZa(w, svl, registerValue, z, snapshotZa(core), written);
            run(core, w.word());
            String what = w + " W" + w.register() + "=" + (registerValue & M32);
            assertZa(expected, core, written, what);
            assertZUnchanged(z, core);
            assertEquals(w.count() * step(w.op()), written.size(), what + ": nº de vetores escritos");
        }
    }

    // ── propriedades pedidas no Aceite ───────────────────────────────────────────────────────────

    private static void fillZ(Aarch64Core core, int z, int esz, long value) {
        long[] words = new long[core.streamingVectorLengthBytes() / Long.BYTES];
        for (int i = 0; i < (core.streamingVectorLengthBytes() >>> esz); i++) {
            put(words, esz, i, value);
        }
        for (int wd = 0; wd < words.length; wd++) {
            core.scalable().setZWord(z, wd, words[wd]);
        }
    }

    private static void setElement(Aarch64Core core, int z, int esz, int index, long value) {
        long[] words = new long[core.streamingVectorLengthBytes() / Long.BYTES];
        for (int wd = 0; wd < words.length; wd++) {
            words[wd] = core.scalable().zWord(z, wd);
        }
        put(words, esz, index, value);
        for (int wd = 0; wd < words.length; wd++) {
            core.scalable().setZWord(z, wd, words[wd]);
        }
    }

    /// Primeira linha de `ZA` que a palavra escreve com `W<rv> = 0` (`off MOD (SVL/n)`).
    private static int firstRow(Word w, int svlBytes) {
        return w.off() % (svlBytes / w.count());
    }

    private static long zaLane(Aarch64Core core, int row, int esz, int index) {
        int rowWords = core.streamingVectorLengthBytes() / Long.BYTES;
        long[] words = new long[rowWords];
        for (int wd = 0; wd < rowWords; wd++) {
            words[wd] = core.matrix().zaWord(row * rowWords + wd);
        }
        return lane(words, esz, index);
    }

    @Test
    void indexReplicatesTheElementOfEachSegmentNotTheFirstSegmentsElement() {
        // SVL = 512 (4 segmentos de 128 bits): cada segmento usa o SEU elemento `idx`. `FMLA_S` n = 2, Zn = 1.0, ZA = 0.
        Word w = word(Op.FMLA_S, 2);
        Aarch64Core core = core(512, SVCR_SM | SVCR_ZA);
        for (int reg = 0; reg < Z_REGISTERS; reg++) {
            fillZ(core, reg, ESZ_SINGLE, fb(1f));
        }
        for (int i = 0; i < 16; i++) {
            setElement(core, w.zm(), ESZ_SINGLE, i, fb(i + 1f));
        }
        core.setX(w.register(), 0);
        run(core, w.word());
        int row = firstRow(w, 64);
        for (int e = 0; e < 16; e++) {
            assertEquals(fb(e / 4 * 4 + w.index() + 1f), zaLane(core, row, ESZ_SINGLE, e),
                    "lane " + e + " (segmento " + e / 4 + ", índice " + w.index() + ")");
        }
    }

    @Test
    void verticalDotIsNotTheHorizontalDot() {
        // Zn+i tem todos os bytes = i + 1 e Zm tem todos os bytes = 1. Horizontal (SDOT): o membro r soma os 4 bytes do
        // SEU registrador => 4(r+1). Vertical (SVDOT): soma UM byte de cada um dos 4 registradores => 1+2+3+4 = 10, igual
        // para todos os membros.
        Word horizontal = word(Op.SDOT_4B, 4);
        Word vertical = word(Op.SVDOT_4B, 4);
        for (Word w : new Word[] {horizontal, vertical}) {
            Aarch64Core core = core(256, SVCR_SM | SVCR_ZA);
            for (int i = 0; i < 4; i++) {
                fillZ(core, w.zn() + i, ESZ_BYTE, i + 1);
            }
            fillZ(core, w.zm(), ESZ_BYTE, 1);
            core.setX(w.register(), 0);
            run(core, w.word());
            int rowsPerMember = 32 / 4;
            for (int member = 0; member < 4; member++) {
                long expected = w == horizontal ? 4L * (member + 1) : 10L;
                assertEquals(expected, zaLane(core, firstRow(w, 32) + member * rowsPerMember, ESZ_SINGLE, 0),
                        w.op() + " membro " + member);
            }
        }
    }

    @Test
    void fvdotbAndFvdottTakeTheLowAndHighHalfOfTheIndexedGroup() {
        // Zn e Zn+1 = fp8 1.0; Zm: elemento de 16 bits `2s` = (1.0, 1.0) e `2s+1` = (2.0, 2.0) em todo o vetor.
        for (Op op : new Op[] {Op.FVDOTB, Op.FVDOTT}) {
            Word w = word(op, 4);
            Aarch64Core core = core(256, SVCR_SM | SVCR_ZA);
            fillZ(core, w.zn(), ESZ_BYTE, E5M2_ONE);
            fillZ(core, w.zn() + 1, ESZ_BYTE, E5M2_ONE);
            for (int i = 0; i < 8; i++) {
                setElement(core, w.zm(), ESZ_HALF, 2 * i, E5M2_ONE | E5M2_ONE << 8);
                setElement(core, w.zm(), ESZ_HALF, 2 * i + 1, E5M2_TWO | E5M2_TWO << 8);
            }
            core.setX(w.register(), 0);
            run(core, w.word());
            float expected = op == Op.FVDOTB ? 2f : 4f;
            assertEquals(fb(expected), zaLane(core, firstRow(w, 32), ESZ_SINGLE, 0), op.name());
        }
    }

    @Test
    void suvdotAndUsvdotDifferInWhichOperandIsSigned() {
        // Zn = 0xFF, Zm = 0x02. SUVDOT (n signed, m unsigned): 4 × (-1 × 2) = -8. USVDOT (n unsigned, m signed): 4 × (255 × 2).
        Word su = word(Op.SUVDOT, 4);
        Word us = word(Op.USVDOT, 4);
        for (Word w : new Word[] {su, us}) {
            Aarch64Core core = core(256, SVCR_SM | SVCR_ZA);
            for (int i = 0; i < 4; i++) {
                fillZ(core, w.zn() + i, ESZ_BYTE, 0xFF);
            }
            fillZ(core, w.zm(), ESZ_BYTE, 2);
            core.setX(w.register(), 0);
            run(core, w.word());
            assertEquals(w == su ? -8L & M32 : 2040L, zaLane(core, firstRow(w, 32), ESZ_SINGLE, 0), w.op().name());
        }
    }

    @Test
    void executingTwiceAccumulatesTwice() {
        Word w = word(Op.SDOT_4B, 2);
        Aarch64Core core = core(256, SVCR_SM | SVCR_ZA);
        fillZ(core, w.zn(), ESZ_BYTE, 3);
        fillZ(core, w.zn() + 1, ESZ_BYTE, 3);
        fillZ(core, w.zm(), ESZ_BYTE, 5);
        core.setX(w.register(), 0);
        run(core, w.word());
        long once = zaLane(core, firstRow(w, 32), ESZ_SINGLE, 0);
        run(core, w.word());
        assertEquals(60L, once);
        assertEquals(120L, zaLane(core, firstRow(w, 32), ESZ_SINGLE, 0));
    }

    @Test
    void outsideStreamingModeEntersTheSmeTrapAndTouchesNothing() {
        Word w = word(Op.SMLALL_S, 2);
        Aarch64Core core = core(256, SVCR_ZA);
        fillZ(core, w.zn(), ESZ_BYTE, 1);
        long[][] before = snapshotZa(core);
        run(core, w.word());
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(before.length, snapshotZa(core).length);
        assertEquals(Arrays.deepToString(before), Arrays.deepToString(snapshotZa(core)));
    }

    @Test
    void withZaDisabledEntersTheSmeTrapAndTouchesNothing() {
        Word w = word(Op.SVDOT_4B, 4);
        Aarch64Core core = core(256, SVCR_SM);
        fillZ(core, w.zn(), ESZ_BYTE, 1);
        run(core, w.word());
        assertEquals(EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
    }
}
