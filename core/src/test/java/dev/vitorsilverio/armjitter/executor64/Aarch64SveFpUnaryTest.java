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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.16 — unárias de ponto flutuante SVE predicadas: as 105 linhas de `### SVE FP Unary Operations Predicated Group`
/// (`sve.decode` do QEMU, linhas 1211-1339). A tabela `ROWS` abaixo é a do `.decode` (nome + bits fixos); cada linha é
/// EXECUTADA contra um oráculo escrito à parte sobre os operadores nativos do JDK (`float`/`double`, `Math.rint`/
/// `ceil`/`floor`/`sqrt`, `Float.floatToFloat16`, `BigDecimal`/`BigInteger`), nunca contra `SveFloat`.
///
/// O oráculo do JDK só cobre arredondamento ao mais próximo (o `FPCR` inicial é zero); os modos de arredondamento,
/// `FZ`/`FZ16`, `DN`, as flags do `FPSR` e o arredondamento ímpar têm testes dirigidos com o valor esperado escrito à
/// mão. Palavras montadas conferidas contra `aarch64-none-elf-as` (`-march=armv9.4-a+sve2+sve2p1+sve2p2+bf16`).
class Aarch64SveFpUnaryTest {
    private static final int Z1 = 1;
    private static final int Z3 = 3;
    private static final int P2 = 2;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final long GARBAGE = 0xA5A5_A5A5_A5A5_A5A5L;
    private static final int RANDOM_PASSES = 6;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture FULL = Aarch64Architecture.extending(
            Aarch64Architecture.extending(SVE, "teste-BF16", Aarch64Feature.BFLOAT16), "teste-SVE2p2", Aarch64Feature.SVE2_2);

    private static final int IOC = 1;
    private static final int IXC = 1 << 4;
    private static final int IDC = 1 << 7;
    private static final long FPCR_RMODE_PLUS = 1L << 22;
    private static final long FPCR_RMODE_MINUS = 2L << 22;
    private static final long FPCR_RMODE_ZERO = 3L << 22;
    private static final long FPCR_FZ = 1L << 24;
    private static final long FPCR_DN = 1L << 25;
    private static final long FPCR_FZ16 = 1L << 19;

    /// `{nome, palavra com rd=rn=pg=0, bits 23:22 são curinga (esz)}`, na ordem do `.decode`.
    private static final Object[][] ROWS = {
        {"FCVT_sh_m", 0x6588A000L, false},
        {"FCVT_hs_m", 0x6589A000L, false},
        {"BFCVT_m", 0x658AA000L, false},
        {"FCVT_dh_m", 0x65C8A000L, false},
        {"FCVT_hd_m", 0x65C9A000L, false},
        {"FCVT_ds_m", 0x65CAA000L, false},
        {"FCVT_sd_m", 0x65CBA000L, false},
        {"FCVTX_ds_z", 0x641AC000L, false},
        {"FCVT_sh_z", 0x649A8000L, false},
        {"FCVT_hs_z", 0x649AA000L, false},
        {"BFCVT_z", 0x649AC000L, false},
        {"FCVT_dh_z", 0x64DA8000L, false},
        {"FCVT_hd_z", 0x64DAA000L, false},
        {"FCVT_ds_z", 0x64DAC000L, false},
        {"FCVT_sd_z", 0x64DAE000L, false},
        {"FCVTZS_hh_m", 0x655AA000L, false},
        {"FCVTZU_hh_m", 0x655BA000L, false},
        {"FCVTZS_hs_m", 0x655CA000L, false},
        {"FCVTZU_hs_m", 0x655DA000L, false},
        {"FCVTZS_hd_m", 0x655EA000L, false},
        {"FCVTZU_hd_m", 0x655FA000L, false},
        {"FCVTZS_ss_m", 0x659CA000L, false},
        {"FCVTZU_ss_m", 0x659DA000L, false},
        {"FCVTZS_ds_m", 0x65D8A000L, false},
        {"FCVTZU_ds_m", 0x65D9A000L, false},
        {"FCVTZS_sd_m", 0x65DCA000L, false},
        {"FCVTZU_sd_m", 0x65DDA000L, false},
        {"FCVTZS_dd_m", 0x65DEA000L, false},
        {"FCVTZU_dd_m", 0x65DFA000L, false},
        {"FCVTZS_hh_z", 0x645EC000L, false},
        {"FCVTZU_hh_z", 0x645EE000L, false},
        {"FCVTZS_hs_z", 0x645F8000L, false},
        {"FCVTZU_hs_z", 0x645FA000L, false},
        {"FCVTZS_hd_z", 0x645FC000L, false},
        {"FCVTZU_hd_z", 0x645FE000L, false},
        {"FCVTZS_ss_z", 0x649F8000L, false},
        {"FCVTZU_ss_z", 0x649FA000L, false},
        {"FCVTZS_sd_z", 0x64DF8000L, false},
        {"FCVTZU_sd_z", 0x64DFA000L, false},
        {"FCVTZS_ds_z", 0x64DE8000L, false},
        {"FCVTZU_ds_z", 0x64DEA000L, false},
        {"FCVTZS_dd_z", 0x64DFC000L, false},
        {"FCVTZU_dd_z", 0x64DFE000L, false},
        {"FRINTN_m", 0x6500A000L, true},
        {"FRINTP_m", 0x6501A000L, true},
        {"FRINTM_m", 0x6502A000L, true},
        {"FRINTZ_m", 0x6503A000L, true},
        {"FRINTA_m", 0x6504A000L, true},
        {"FRINTX_m", 0x6506A000L, true},
        {"FRINTI_m", 0x6507A000L, true},
        {"FRINTN_z", 0x64188000L, true},
        {"FRINTP_z", 0x6418A000L, true},
        {"FRINTM_z", 0x6418C000L, true},
        {"FRINTZ_z", 0x6418E000L, true},
        {"FRINTA_z", 0x64198000L, true},
        {"FRINTX_z", 0x6419C000L, true},
        {"FRINTI_z", 0x6419E000L, true},
        {"FRINT32X_s_m", 0x6511A000L, false},
        {"FRINT32X_d_m", 0x6513A000L, false},
        {"FRINT64X_s_m", 0x6515A000L, false},
        {"FRINT64X_d_m", 0x6517A000L, false},
        {"FRINT32X_s_z", 0x641CA000L, false},
        {"FRINT32X_d_z", 0x641CE000L, false},
        {"FRINT64X_s_z", 0x641DA000L, false},
        {"FRINT64X_d_z", 0x641DE000L, false},
        {"FRINT32Z_s_m", 0x6510A000L, false},
        {"FRINT32Z_d_m", 0x6512A000L, false},
        {"FRINT64Z_s_m", 0x6514A000L, false},
        {"FRINT64Z_d_m", 0x6516A000L, false},
        {"FRINT32Z_s_z", 0x641C8000L, false},
        {"FRINT32Z_d_z", 0x641CC000L, false},
        {"FRINT64Z_s_z", 0x641D8000L, false},
        {"FRINT64Z_d_z", 0x641DC000L, false},
        {"FRECPX_m", 0x650CA000L, true},
        {"FSQRT_m", 0x650DA000L, true},
        {"FRECPX_z", 0x641B8000L, true},
        {"FSQRT_z", 0x641BA000L, true},
        {"SCVTF_hh_m", 0x6552A000L, false},
        {"SCVTF_sh_m", 0x6554A000L, false},
        {"SCVTF_dh_m", 0x6556A000L, false},
        {"SCVTF_ss_m", 0x6594A000L, false},
        {"SCVTF_sd_m", 0x65D0A000L, false},
        {"SCVTF_ds_m", 0x65D4A000L, false},
        {"SCVTF_dd_m", 0x65D6A000L, false},
        {"UCVTF_hh_m", 0x6553A000L, false},
        {"UCVTF_sh_m", 0x6555A000L, false},
        {"UCVTF_dh_m", 0x6557A000L, false},
        {"UCVTF_ss_m", 0x6595A000L, false},
        {"UCVTF_sd_m", 0x65D1A000L, false},
        {"UCVTF_ds_m", 0x65D5A000L, false},
        {"UCVTF_dd_m", 0x65D7A000L, false},
        {"SCVTF_hh_z", 0x645CC000L, false},
        {"SCVTF_sh_z", 0x645D8000L, false},
        {"SCVTF_ss_z", 0x649D8000L, false},
        {"SCVTF_sd_z", 0x64DC8000L, false},
        {"SCVTF_dh_z", 0x645DC000L, false},
        {"SCVTF_ds_z", 0x64DD8000L, false},
        {"SCVTF_dd_z", 0x64DDC000L, false},
        {"UCVTF_hh_z", 0x645CE000L, false},
        {"UCVTF_sh_z", 0x645DA000L, false},
        {"UCVTF_ss_z", 0x649DA000L, false},
        {"UCVTF_sd_z", 0x64DCA000L, false},
        {"UCVTF_dh_z", 0x645DE000L, false},
        {"UCVTF_ds_z", 0x64DDA000L, false},
        {"UCVTF_dd_z", 0x64DDE000L, false},
    };

    // ── Linhas ───────────────────────────────────────────────────────────────────────────────────

    /// Uma linha do `.decode` já interpretada a partir do NOME (o mesmo esquema do `.decode`: `X_ab_m`, `X_s_z`, `X_m`).
    private record Spec(String name, String base, boolean zeroing, int source, int destination, int word) {
        int container() {
            return Math.max(source, destination);
        }
    }

    private static int size(char letter) {
        return letter == 'h' ? 1 : letter == 's' ? 2 : 3;
    }

    /// `wildEsz` só vale nas linhas cujos bits 23:22 são curinga.
    private static Spec spec(Object[] row, int wildEsz) {
        String name = (String) row[0];
        String[] parts = name.split("_");
        String base = parts[0];
        boolean zeroing = parts[parts.length - 1].equals("z");
        int word = (int) (long) (Long) row[1];
        boolean wild = (Boolean) row[2];
        int source;
        int destination;
        if (wild) {
            source = wildEsz;
            destination = wildEsz;
            word |= wildEsz << 22;
        } else if (parts.length == 3 && base.startsWith("FRINT")) {
            source = size(parts[1].charAt(0));
            destination = source;
        } else if (parts.length == 3) {
            source = size(parts[1].charAt(0));
            destination = size(parts[1].charAt(1));
        } else { // BFCVT_m / BFCVT_z
            source = 2;
            destination = 0;
        }
        return new Spec(name, base, zeroing, source, destination, word | P2 << 10 | Z3 << 5 | Z1);
    }

    private static Spec spec(String name, int wildEsz) {
        for (Object[] row : ROWS) {
            if (row[0].equals(name)) {
                return spec(row, wildEsz);
            }
        }
        throw new IllegalArgumentException(name);
    }

    static Stream<Arguments> everyRowAtEveryVectorLength() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Object[] row : ROWS) {
            for (int esz : (Boolean) row[2] ? new int[] {1, 2, 3} : new int[] {0}) {
                for (int vl : VECTOR_LENGTHS) {
                    Spec spec = spec(row, esz);
                    cases.add(Arguments.of(spec.name() + (esz == 0 ? "" : " esz=" + esz) + " VL=" + vl, row, esz, vl));
                }
            }
        }
        return cases.build();
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
            return decode(architecture, word) instanceof Ir64Op.SveFpUnary;
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

    private static long mask(int esz) {
        return esz == 3 ? -1L : (1L << bits(esz)) - 1L;
    }

    private static int elementCount(Aarch64Core core, int esz) {
        return core.vectorLengthBytes() >> esz;
    }

    private static void setElement(Aarch64Core core, int reg, int esz, int index, long value) {
        int bitOffset = index * bits(esz);
        int word = bitOffset / 64;
        int shift = bitOffset % 64;
        long cleared = core.scalable().zWord(reg, word) & ~(mask(esz) << shift);
        core.scalable().setZWord(reg, word, cleared | ((value & mask(esz)) << shift));
    }

    private static long getElement(Aarch64Core core, int reg, int esz, int index) {
        int bitOffset = index * bits(esz);
        return (core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64)) & mask(esz);
    }

    private static void fillGarbage(Aarch64Core core, int reg) {
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(reg, w, GARBAGE);
        }
    }

    private static void allActive(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
    }

    /// `P2` com o bit do byte mais baixo de cada elemento ativo e lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int esz, Random random) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, 0L);
        }
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

    private static long encode(double value, int esz) {
        return switch (esz) {
            case 1 -> Float.floatToFloat16((float) value) & 0xFFFFL;
            case 2 -> Float.floatToRawIntBits((float) value) & 0xFFFFFFFFL;
            default -> Double.doubleToRawLongBits(value);
        };
    }

    private static double decodeValue(long b, int esz) {
        return switch (esz) {
            case 0 -> Float.intBitsToFloat((int) (b << 16));
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

    private static final double[] SPECIALS = {0.0, -0.0, 1.0, -1.0, 2.5, -3.5, 0.5, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.NaN};

    /// Entrada FP de formato `fmt`. `fullMantissa` deixa a fração inteira aleatória (o resto tem 10 bits, exatos em
    /// qualquer formato); o expoente vai de `2^-26` a `2^40` (estoura meia precisão e cobre denormais).
    private static long fpInput(Random random, int fmt, boolean fullMantissa) {
        int kind = random.nextInt(10);
        if (kind < 2) {
            return kind == 0 && fmt != 0 ? signalingNaN(fmt) : encode(SPECIALS[random.nextInt(SPECIALS.length)], fmt);
        }
        if (kind == 2) {
            double half = random.nextInt(20) + 0.5; // empates de arredondamento
            return encode(random.nextBoolean() ? -half : half, fmt);
        }
        double mantissa = fullMantissa ? 1.0 + random.nextDouble() : 1.0 + random.nextInt(1 << 10) / 1024.0;
        double magnitude = mantissa * Math.pow(2.0, random.nextInt(67) - 26);
        return encode(random.nextBoolean() ? -magnitude : magnitude, fmt);
    }

    private static long integerInput(Random random, int size) {
        return switch (random.nextInt(8)) {
            case 0 -> 0L;
            case 1 -> -1L;
            case 2 -> 1L << (bits(size) - 1);
            case 3 -> (1L << (bits(size) - 1)) - 1L;
            default -> random.nextLong() >> random.nextInt(64);
        } & mask(size);
    }

    private static boolean sourceIsInteger(String base) {
        return base.equals("SCVTF") || base.equals("UCVTF");
    }

    private static boolean resultIsInteger(String base) {
        return base.startsWith("FCVTZ");
    }

    // ── Oráculo (JDK) ────────────────────────────────────────────────────────────────────────────

    private static long oracle(Spec spec, long raw) {
        String base = spec.base();
        int src = spec.source();
        int dst = spec.destination();
        double v = sourceIsInteger(base) ? 0.0 : decodeValue(raw, src);
        switch (base) {
            case "FCVT":
                return encode(v, dst);
            case "FCVTX":
                return roundToOdd(v);
            case "BFCVT": {
                int b = (int) raw;
                if (Float.isNaN(Float.intBitsToFloat(b))) {
                    return ((b >>> 16) | 0x40) & 0xFFFFL;
                }
                return ((b + 0x7FFF + ((b >>> 16) & 1)) >>> 16) & 0xFFFFL;
            }
            case "FCVTZS":
                return truncateToInteger(v, bits(dst), false);
            case "FCVTZU":
                return truncateToInteger(v, bits(dst), true);
            case "SCVTF": {
                long value = (raw << (64 - bits(src))) >> (64 - bits(src));
                return dst == 2 ? Float.floatToRawIntBits((float) value) & 0xFFFFFFFFL
                        : dst == 3 ? Double.doubleToRawLongBits((double) value) : encode((float) value, 1);
            }
            case "UCVTF": {
                BigInteger value = new BigInteger(Long.toUnsignedString(raw));
                return dst == 2 ? Float.floatToRawIntBits(value.floatValue()) & 0xFFFFFFFFL
                        : dst == 3 ? Double.doubleToRawLongBits(value.doubleValue()) : encode(value.floatValue(), 1);
            }
            case "FRINTN", "FRINTI", "FRINTX":
                return encode(Math.rint(v), dst);
            case "FRINTP":
                return encode(Math.ceil(v), dst);
            case "FRINTM":
                return encode(Math.floor(v), dst);
            case "FRINTZ":
                return encode(truncate(v), dst);
            case "FRINTA":
                return encode(awayFromZero(v), dst);
            case "FRINT32X", "FRINT64X":
                return encode(bounded(Math.rint(v), base.startsWith("FRINT32") ? 32 : 64), dst);
            case "FRINT32Z", "FRINT64Z":
                return encode(bounded(truncate(v), base.startsWith("FRINT32") ? 32 : 64), dst);
            case "FRECPX":
                return Double.isNaN(v) ? encode(Double.NaN, dst) : reciprocalExponent(raw, dst);
            default: // FSQRT
                return encode(Math.sqrt(v), dst);
        }
    }

    private static double truncate(double v) {
        return v < 0 || (v == 0 && 1 / v < 0) ? Math.ceil(v) : Math.floor(v);
    }

    private static double awayFromZero(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v == 0) {
            return v;
        }
        double rounded = new BigDecimal(v).setScale(0, RoundingMode.HALF_UP).doubleValue();
        return rounded == 0 ? Math.copySign(0.0, v) : rounded;
    }

    /// `FRINT32*`/`FRINT64*`: fora do intervalo do inteiro com sinal (ou NaN/infinito) vira `-2^(n-1)`.
    private static double bounded(double rounded, int intBits) {
        double limit = Math.pow(2.0, intBits - 1);
        boolean fits = !Double.isNaN(rounded) && !Double.isInfinite(rounded) && rounded >= -limit && rounded < limit;
        return fits ? rounded : -limit;
    }

    private static long truncateToInteger(double v, int intBits, boolean unsigned) {
        if (Double.isNaN(v)) {
            return 0L;
        }
        BigInteger min = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(intBits - 1).negate();
        BigInteger max = (unsigned ? BigInteger.ONE.shiftLeft(intBits) : BigInteger.ONE.shiftLeft(intBits - 1))
                .subtract(BigInteger.ONE);
        BigInteger value;
        if (Double.isInfinite(v)) {
            value = v > 0 ? max : min;
        } else {
            value = new BigDecimal(v).toBigInteger(); // trunca para zero
        }
        value = value.max(min).min(max);
        return value.longValue() & mask(intBits == 64 ? 3 : intBits == 32 ? 2 : 1);
    }

    /// Dupla → simples com arredondamento ímpar (`FCVTX`): trunca e, se inexato, força o bit baixo a 1.
    private static long roundToOdd(double v) {
        float nearest = (float) v;
        if (Float.isNaN(nearest) || Double.isInfinite(v) || nearest == v) {
            return Float.floatToRawIntBits(nearest) & 0xFFFFFFFFL;
        }
        float truncated = Math.abs((double) nearest) > Math.abs(v) ? Math.nextAfter(nearest, 0.0) : nearest;
        int b = Float.floatToRawIntBits(truncated);
        return (b | 1) & 0xFFFFFFFFL;
    }

    private static long reciprocalExponent(long raw, int fmt) {
        int expBits = fmt == 1 ? 5 : fmt == 2 ? 8 : 11;
        int fracBits = fmt == 1 ? 10 : fmt == 2 ? 23 : 52;
        long sign = raw & (1L << (expBits + fracBits));
        long exp = (raw >>> fracBits) & ((1L << expBits) - 1);
        long maxExp = (1L << expBits) - 1;
        long inverted = exp == 0 ? maxExp - 1 : ~exp & maxExp;
        return sign | (inverted << fracBits);
    }

    // ── Execução de todas as 105 linhas ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRowAtEveryVectorLength")
    void everyRowMatchesTheJdkOracleWithPredicationAndLayout(String label, Object[] row, int wildEsz, int vl) {
        Spec spec = spec(row, wildEsz);
        int container = spec.container();
        Random random = new Random(label.hashCode());
        boolean fullMantissa = (spec.base().equals("FCVT") && spec.source() == 3 && spec.destination() == 2)
                || spec.base().equals("FCVTX") || (spec.base().equals("FCVT") && spec.source() == 2 && spec.destination() == 1);
        for (int pass = 0; pass < RANDOM_PASSES; pass++) {
            Aarch64Core core = core(FULL, vl);
            int elements = elementCount(core, container);
            long[] raws = new long[elements];
            fillGarbage(core, Z1);
            for (int e = 0; e < elements; e++) {
                raws[e] = sourceIsInteger(spec.base()) ? integerInput(random, spec.source())
                        : fpInput(random, spec.source(), fullMantissa);
                // Os bits do elemento ACIMA do formato de origem são lixo: têm que ser ignorados.
                long upper = spec.source() < container ? (random.nextLong() << bits(spec.source())) & mask(container) : 0L;
                setElement(core, Z3, container, e, raws[e] | upper);
            }
            boolean[] active = randomPredicate(core, container, random);
            run(FULL, core, spec.word());
            for (int e = 0; e < elements; e++) {
                long actual = getElement(core, Z1, container, e);
                String where = label + " elemento " + e + " entrada=0x" + Long.toHexString(raws[e]);
                if (!active[e]) {
                    assertEquals(spec.zeroing() ? 0L : GARBAGE & mask(container), actual, where + " (inativo)");
                    continue;
                }
                long expected = oracle(spec, raws[e]);
                if (!resultIsInteger(spec.base()) && Double.isNaN(decodeValue(expected, spec.destination()))) {
                    assertTrue(Double.isNaN(decodeValue(actual, spec.destination())), where + ": esperava NaN, veio 0x"
                            + Long.toHexString(actual));
                    assertEquals(0L, actual & ~mask(Math.max(spec.destination(), 1)), where + ": resto do elemento zerado");
                } else {
                    assertEquals(expected, actual, where);
                }
            }
        }
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @Test
    void allOneHundredFiveRowsDecodeToTheirOperationWhenEveryFeatureIsDeclared() {
        assertEquals(105, ROWS.length);
        for (Object[] row : ROWS) {
            for (int esz : (Boolean) row[2] ? new int[] {1, 2, 3} : new int[] {0}) {
                Spec spec = spec(row, esz);
                Ir64Op.SveFpUnary op = assertInstanceOf(Ir64Op.SveFpUnary.class, decode(FULL, spec.word()), spec.name());
                assertEquals(Ir64Op.SveFpUnary.Op.valueOf(spec.base()), op.op(), spec.name());
                assertEquals(spec.zeroing(), op.zeroing(), spec.name());
                assertEquals(spec.source(), op.source(), spec.name());
                assertEquals(spec.destination(), op.destination(), spec.name());
                assertEquals(Z1, op.rd());
                assertEquals(Z3, op.rn());
                assertEquals(P2, op.pg());
            }
        }
    }

    @Test
    void featuresGateTheRowsThatNeedThem() {
        int sve2p2Rows = 0;
        for (Object[] row : ROWS) {
            Spec spec = spec(row, 2);
            boolean needsSve2p2 = spec.zeroing() || spec.base().startsWith("FRINT32") || spec.base().startsWith("FRINT64");
            boolean needsBf16 = spec.name().equals("BFCVT_m");
            assertEquals(!needsSve2p2 && !needsBf16, decodes(SVE, spec.word()), spec.name() + " sob SVE puro");
            if (needsSve2p2) {
                sve2p2Rows++;
            }
        }
        assertEquals(61, sve2p2Rows, "as 61 linhas SVE2p2 do .decode");
    }

    @ParameterizedTest
    @ValueSource(ints = {
        0x6500A861, // FRINTN esz = 0: não alocado (G8)
        0x650CA861, // FRECPX esz = 0
        0x650DA861, // FSQRT esz = 0
        0x64188861, // FRINTN_z esz = 0
        0x6548A861, // FCVT com esz=01 e opc 0010 00: não está na tabela
        0x6508A861, // FCVT com esz=00
        0x658BA861, // FCVT esz=10 opc 0011: não alocado
    })
    void unallocatedNeighboursAreRefused(int word) {
        assertFalse(decodes(FULL, word), Integer.toHexString(word));
        assertTrue(decodes(FULL, 0x6580A061), "controle: FRINTN.S é válido");
    }


    static Stream<Arguments> assembled() {
        return Stream.of(
                Arguments.of(0x6588a861, Ir64Op.SveFpUnary.Op.FCVT, 2, 1, false),
                Arguments.of(0x6589a861, Ir64Op.SveFpUnary.Op.FCVT, 1, 2, false),
                Arguments.of(0x658aa861, Ir64Op.SveFpUnary.Op.BFCVT, 2, 0, false),
                Arguments.of(0x65d8a861, Ir64Op.SveFpUnary.Op.FCVTZS, 3, 2, false),
                Arguments.of(0x65dda861, Ir64Op.SveFpUnary.Op.FCVTZU, 2, 3, false),
                Arguments.of(0x65d0a861, Ir64Op.SveFpUnary.Op.SCVTF, 2, 3, false),
                Arguments.of(0x6557a861, Ir64Op.SveFpUnary.Op.UCVTF, 3, 1, false),
                Arguments.of(0x6580a861, Ir64Op.SveFpUnary.Op.FRINTN, 2, 2, false),
                Arguments.of(0x65c7a861, Ir64Op.SveFpUnary.Op.FRINTI, 3, 3, false),
                Arguments.of(0x654ca861, Ir64Op.SveFpUnary.Op.FRECPX, 1, 1, false),
                Arguments.of(0x658da861, Ir64Op.SveFpUnary.Op.FSQRT, 2, 2, false),
                Arguments.of(0x649a8861, Ir64Op.SveFpUnary.Op.FCVT, 2, 1, true),
                Arguments.of(0x6511a861, Ir64Op.SveFpUnary.Op.FRINT32X, 2, 2, false),
                Arguments.of(0x641dc861, Ir64Op.SveFpUnary.Op.FRINT64Z, 3, 3, true),
                Arguments.of(0x641ac861, Ir64Op.SveFpUnary.Op.FCVTX, 3, 2, true),
                Arguments.of(0x64dba861, Ir64Op.SveFpUnary.Op.FSQRT, 3, 3, true),
                Arguments.of(0x645d8861, Ir64Op.SveFpUnary.Op.SCVTF, 2, 1, true));
    }

    @ParameterizedTest
    @MethodSource("assembled")
    void everyAssembledWordDecodesToItsOperation(int word, Ir64Op.SveFpUnary.Op expected, int source, int destination,
            boolean zeroing) {
        Ir64Op.SveFpUnary op = assertInstanceOf(Ir64Op.SveFpUnary.class, decode(FULL, word));
        assertEquals(expected, op.op());
        assertEquals(source, op.source());
        assertEquals(destination, op.destination());
        assertEquals(zeroing, op.zeroing());
        assertEquals(1, op.rd());
        assertEquals(3, op.rn());
        assertEquals(2, op.pg());
    }

    @Test
    void registerFieldsAreDecodedFromTheRightBits() {
        // frintx z31.d, p7/m, z30.d
        Ir64Op.SveFpUnary op = assertInstanceOf(Ir64Op.SveFpUnary.class, decode(SVE, 0x65c6bfdf));
        assertEquals(Ir64Op.SveFpUnary.Op.FRINTX, op.op());
        assertEquals(31, op.rd());
        assertEquals(30, op.rn());
        assertEquals(7, op.pg());
    }

    // ── Semântica dirigida ───────────────────────────────────────────────────────────────────────

    /// Executa a linha `name` (formato `wildEsz` nas de tamanho variável) com VL 256, todos os elementos ativos e
    /// `inputs` nos primeiros elementos do contêiner.
    private static Aarch64Core directed(String name, int wildEsz, long fpcr, long... inputs) {
        Spec spec = spec(name, wildEsz);
        Aarch64Core core = core(FULL, 256);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPCR, fpcr);
        allActive(core);
        for (int e = 0; e < inputs.length; e++) {
            setElement(core, Z3, spec.container(), e, inputs[e]);
        }
        run(FULL, core, spec.word());
        return core;
    }

    private static long out(Aarch64Core core, String name, int wildEsz, int index) {
        return getElement(core, Z1, spec(name, wildEsz).container(), index);
    }

    @Test
    void frintiFollowsRModeButTheModesInTheOpcodeIgnoreIt() {
        long threeAndAHalf = encode(3.5, 2);
        assertEquals(encode(3.0, 2), out(directed("FRINTI_m", 2, FPCR_RMODE_ZERO, threeAndAHalf), "FRINTI_m", 2, 0));
        assertEquals(encode(4.0, 2), out(directed("FRINTI_m", 2, 0, threeAndAHalf), "FRINTI_m", 2, 0));
        assertEquals(encode(4.0, 2), out(directed("FRINTN_m", 2, FPCR_RMODE_ZERO, threeAndAHalf), "FRINTN_m", 2, 0),
                "FRINTN ignora FPCR.RMode");
        assertEquals(encode(4.0, 2), out(directed("FRINTP_m", 2, FPCR_RMODE_MINUS, threeAndAHalf), "FRINTP_m", 2, 0));
        assertEquals(encode(3.0, 2), out(directed("FRINTM_m", 2, FPCR_RMODE_PLUS, threeAndAHalf), "FRINTM_m", 2, 0));
        assertEquals(encode(3.0, 2), out(directed("FRINTZ_m", 2, FPCR_RMODE_PLUS, threeAndAHalf), "FRINTZ_m", 2, 0));
        assertEquals(encode(3.0, 2), out(directed("FRINTA_m", 2, FPCR_RMODE_ZERO, encode(2.5, 2)), "FRINTA_m", 2, 0),
                "FRINTA: empate para longe do zero (RN-par daria 2.0)");
        assertEquals(encode(-3.0, 2), out(directed("FRINTA_m", 2, 0, encode(-2.5, 2)), "FRINTA_m", 2, 0));
    }

    @Test
    void onlyFrintxRaisesInexact() {
        long input = encode(2.25, 3);
        assertEquals(IXC, fpsr(directed("FRINTX_m", 3, 0, input)));
        for (String name : new String[] {"FRINTN_m", "FRINTP_m", "FRINTM_m", "FRINTZ_m", "FRINTA_m", "FRINTI_m"}) {
            assertEquals(0L, fpsr(directed(name, 3, 0, input)), name);
        }
        assertEquals(0L, fpsr(directed("FRINTX_m", 3, 0, encode(4.0, 3))), "valor já inteiro não é inexato");
    }

    @Test
    void frintOfMinusZeroKeepsTheSignAndSmallNegativesBecomeMinusZero() {
        assertEquals(encode(-0.0, 2), out(directed("FRINTZ_m", 2, 0, encode(-0.25, 2)), "FRINTZ_m", 2, 0));
        assertEquals(encode(-0.0, 2), out(directed("FRINTN_m", 2, 0, encode(-0.0, 2)), "FRINTN_m", 2, 0));
        assertEquals(encode(-0.0, 2), out(directed("FRINTP_m", 2, 0, encode(-0.75, 2)), "FRINTP_m", 2, 0));
    }

    @Test
    void narrowingFcvtRoundsByFpcrRMode() {
        long halfway = 0x3F801000L; // 1 + 2^-11: exatamente entre 0x3C00 e 0x3C01 em meia precisão
        assertEquals(0x3C00L, out(directed("FCVT_sh_m", 0, 0, halfway), "FCVT_sh_m", 0, 0), "RN-par");
        assertEquals(0x3C01L, out(directed("FCVT_sh_m", 0, FPCR_RMODE_PLUS, halfway), "FCVT_sh_m", 0, 0));
        assertEquals(0x3C00L, out(directed("FCVT_sh_m", 0, FPCR_RMODE_MINUS, halfway), "FCVT_sh_m", 0, 0));
        assertEquals(0x3C00L, out(directed("FCVT_sh_m", 0, FPCR_RMODE_ZERO, halfway), "FCVT_sh_m", 0, 0));
        assertEquals(0x3C01L, out(directed("FCVT_sh_m", 0, 0, halfway + 1), "FCVT_sh_m", 0, 0), "um ulp acima: sobe");
        assertEquals(IXC, fpsr(directed("FCVT_sh_m", 0, 0, halfway)));
        assertEquals(0L, fpsr(directed("FCVT_sh_m", 0, 0, 0x3F800000L)), "1.0 é exato");
    }

    @Test
    void fcvtOverflowToHalfSetsOverflowAndInexact() {
        Aarch64Core core = directed("FCVT_sh_m", 0, 0, encode(70000.0, 2));
        assertEquals(0x7C00L, out(core, "FCVT_sh_m", 0, 0), "+∞ em RN");
        assertEquals(0x14L, fpsr(core), "OFC|IXC");
        assertEquals(0x7BFFL, out(directed("FCVT_sh_m", 0, FPCR_RMODE_ZERO, encode(70000.0, 2)), "FCVT_sh_m", 0, 0),
                "RZ dá o maior finito");
    }

    @Test
    void fcvtxRoundsToOdd() {
        long inexactUp = Double.doubleToRawLongBits(1.0 + Math.pow(2.0, -30)); // RN daria 0x3F800000
        assertEquals(0x3F800001L, out(directed("FCVTX_ds_z", 0, 0, inexactUp), "FCVTX_ds_z", 0, 0));
        assertEquals(0x3F800000L, out(directed("FCVTX_ds_z", 0, 0, Double.doubleToRawLongBits(1.0)), "FCVTX_ds_z", 0, 0),
                "exato não muda");
        assertEquals(0xBF800001L, out(directed("FCVTX_ds_z", 0, 0, inexactUp | (1L << 63)), "FCVTX_ds_z", 0, 0));
        // O modo ímpar ignora FPCR.RMode.
        assertEquals(0x3F800001L, out(directed("FCVTX_ds_z", 0, FPCR_RMODE_PLUS, inexactUp), "FCVTX_ds_z", 0, 0));
        assertEquals(0x7F7FFFFFL, out(directed("FCVTX_ds_z", 0, 0, Double.doubleToRawLongBits(1e300)), "FCVTX_ds_z", 0, 0),
                "estouro em ímpar dá o maior finito, não infinito");
    }

    @Test
    void bfcvtRoundsToNearestEvenAndConvertsNaN() {
        assertEquals(0x3F80L, out(directed("BFCVT_m", 0, 0, 0x3F808000L), "BFCVT_m", 0, 0), "empate: par");
        assertEquals(0x3F82L, out(directed("BFCVT_m", 0, 0, 0x3F818000L), "BFCVT_m", 0, 0), "empate: par (sobe)");
        assertEquals(0x3F81L, out(directed("BFCVT_m", 0, 0, 0x3F808001L), "BFCVT_m", 0, 0));
        Aarch64Core core = directed("BFCVT_m", 0, 0, 0x7F800001L);
        assertEquals(0x7FC0L, out(core, "BFCVT_m", 0, 0), "sNaN vira qNaN");
        assertEquals(IOC, fpsr(core));
        assertEquals(0x7F80L, out(directed("BFCVT_m", 0, 0, 0x7F800000L), "BFCVT_m", 0, 0));
    }

    @Test
    void fcvtzsSaturatesAndFlagsInvalidForNaNAndOutOfRange() {
        Aarch64Core core = directed("FCVTZS_ss_m", 0, 0, encode(Double.NaN, 2), 0x7F800000L, 0xFF800000L,
                encode(3.0e9, 2), encode(-3.0e9, 2), encode(-1.5, 2));
        assertEquals(0L, out(core, "FCVTZS_ss_m", 0, 0), "NaN → 0");
        assertEquals(0x7FFFFFFFL, out(core, "FCVTZS_ss_m", 0, 1), "+∞ satura");
        assertEquals(0x80000000L, out(core, "FCVTZS_ss_m", 0, 2), "-∞ satura");
        assertEquals(0x7FFFFFFFL, out(core, "FCVTZS_ss_m", 0, 3));
        assertEquals(0x80000000L, out(core, "FCVTZS_ss_m", 0, 4));
        assertEquals(0xFFFFFFFFL, out(core, "FCVTZS_ss_m", 0, 5), "-1.5 trunca para -1");
        assertEquals(IOC, fpsr(core) & IOC);
        assertEquals(IXC, fpsr(directed("FCVTZS_ss_m", 0, 0, encode(-1.5, 2))), "só a fração: IXC sem IOC");
        assertEquals(0L, fpsr(directed("FCVTZS_ss_m", 0, 0, encode(-4.0, 2))));
    }

    @Test
    void fcvtzuTreatsNegativeTruncationAsOutOfRange() {
        Aarch64Core negative = directed("FCVTZU_ss_m", 0, 0, encode(-1.5, 2));
        assertEquals(0L, out(negative, "FCVTZU_ss_m", 0, 0));
        assertEquals(IOC, fpsr(negative), "trunca para -1: fora de faixa sem sinal");
        assertEquals(IXC, fpsr(directed("FCVTZU_ss_m", 0, 0, encode(-0.5, 2))), "trunca para 0: só inexato");
        assertEquals(0xFFFFFFFFL, out(directed("FCVTZU_ss_m", 0, 0, encode(5.0e9, 2)), "FCVTZU_ss_m", 0, 0));
    }

    @Test
    void fcvtzsDoubleToInt32IsZeroExtendedInTheDoubleword() {
        assertEquals(0x00000000FFFFFFFFL, out(directed("FCVTZS_ds_m", 0, 0, encode(-1.0, 3)), "FCVTZS_ds_m", 0, 0),
                "int32 -1 zero-estendido em 64 bits");
        assertEquals(0x00000000FFFFFFFFL, out(directed("FCVTZU_ds_m", 0, 0, encode(1.0e12, 3)), "FCVTZU_ds_m", 0, 0));
    }

    @Test
    void fcvtzsFromHalfDenormalHonoursFz16() {
        long denormal = 0x0001L;
        assertEquals(0L, fpsr(directed("FCVTZS_hh_m", 0, 0, denormal)) & IDC, "sem FZ16 não há IDC em meia");
        Aarch64Core flushed = directed("FCVTZS_hh_m", 0, FPCR_FZ16, denormal);
        assertEquals(0L, out(flushed, "FCVTZS_hh_m", 0, 0));
        assertEquals(0L, fpsr(flushed) & IDC, "meia precisão nunca levanta IDC");
    }

    @Test
    void inactiveElementsNeverRaiseFlagsButActiveOnesDo() {
        for (String name : new String[] {"FCVTZS_ss_m", "FSQRT_m", "FCVT_ds_m", "FRINTX_m"}) {
            int wild = name.equals("FSQRT_m") || name.equals("FRINTX_m") ? 2 : 0;
            Spec spec = spec(name, wild);
            Aarch64Core core = core(FULL, 256);
            for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
                core.scalable().setPWord(P2, w, 0L);
            }
            for (int e = 0; e < elementCount(core, spec.container()); e++) {
                long nan = spec.source() == 3 ? 0x7FF0000000000001L : 0x7F800001L; // sNaN
                setElement(core, Z3, spec.container(), e, nan);
            }
            run(FULL, core, spec.word());
            assertEquals(0L, fpsr(core), name + " com todo elemento inativo");
            core.scalable().setPWord(P2, 0, 1L); // só o elemento 0
            run(FULL, core, spec.word());
            assertEquals(IOC, fpsr(core) & IOC, name + " com o elemento 0 ativo (sNaN)");
        }
    }

    @Test
    void fsqrtOfNegativeIsInvalidAndOfTwoIsInexact() {
        Aarch64Core negative = directed("FSQRT_m", 2, 0, encode(-1.0, 2));
        assertEquals(0x7FC00000L, out(negative, "FSQRT_m", 2, 0), "NaN padrão");
        assertEquals(IOC, fpsr(negative));
        Aarch64Core two = directed("FSQRT_m", 2, 0, encode(2.0, 2));
        assertEquals(Float.floatToRawIntBits((float) Math.sqrt(2.0)) & 0xFFFFFFFFL, out(two, "FSQRT_m", 2, 0));
        assertEquals(IXC, fpsr(two));
        assertEquals(0L, fpsr(directed("FSQRT_m", 2, 0, encode(4.0, 2))), "raiz exata não é inexata");
        assertEquals(encode(-0.0, 2), out(directed("FSQRT_m", 2, 0, encode(-0.0, 2)), "FSQRT_m", 2, 0), "√-0 = -0");
        assertEquals(encode(Double.POSITIVE_INFINITY, 3), out(directed("FSQRT_m", 3, 0, encode(Double.POSITIVE_INFINITY, 3)),
                "FSQRT_m", 3, 0));
    }

    @Test
    void fsqrtRoundsByRModeInHalfAndDouble() {
        // √2 em meia: entre 0x3DA8 (1.4140625) e 0x3DA9 (1.4150390625); o valor exato 1.41421356… está mais perto de 0x3DA8.
        assertEquals(0x3DA8L, out(directed("FSQRT_m", 1, 0, encode(2.0, 1)), "FSQRT_m", 1, 0));
        assertEquals(0x3DA9L, out(directed("FSQRT_m", 1, FPCR_RMODE_PLUS, encode(2.0, 1)), "FSQRT_m", 1, 0));
        assertEquals(Double.doubleToRawLongBits(Math.sqrt(3.0)), out(directed("FSQRT_m", 3, 0, encode(3.0, 3)), "FSQRT_m", 3, 0));
    }

    @Test
    void frecpxInvertsTheExponentAndZeroesTheFraction() {
        // 1.0f: exp 127 → ~127 & 0xFF = 0x80; 2.0f: 128 → 0x7F; 0.0f: exp 0 → 0xFE.
        assertEquals(0x40000000L, out(directed("FRECPX_m", 2, 0, encode(1.0, 2)), "FRECPX_m", 2, 0));
        assertEquals(0x3F800000L, out(directed("FRECPX_m", 2, 0, encode(2.0, 2)), "FRECPX_m", 2, 0));
        assertEquals(0x7F000000L, out(directed("FRECPX_m", 2, 0, 0L), "FRECPX_m", 2, 0));
        assertEquals(0xFF000000L, out(directed("FRECPX_m", 2, 0, 0x80000000L), "FRECPX_m", 2, 0), "sinal preservado");
        assertEquals(0L, out(directed("FRECPX_m", 2, 0, 0x7F800000L), "FRECPX_m", 2, 0), "+∞ → +0");
    }

    @Test
    void frint32xAndFrint64xReturnMinimumIntegerAsFloatWhenOutOfRange() {
        long minInt32 = Float.floatToRawIntBits(-2147483648.0f) & 0xFFFFFFFFL; // 0xCF000000
        Aarch64Core core = directed("FRINT32X_s_m", 0, 0, encode(3.0e9, 2), encode(Double.NaN, 2), 0x7F800000L,
                encode(-2147483648.0, 2), encode(1.5, 2), encode(2147483520.0, 2));
        assertEquals(minInt32, out(core, "FRINT32X_s_m", 0, 0), "3e9 > INT32_MAX");
        assertEquals(minInt32, out(core, "FRINT32X_s_m", 0, 1), "NaN");
        assertEquals(minInt32, out(core, "FRINT32X_s_m", 0, 2), "+∞");
        assertEquals(minInt32, out(core, "FRINT32X_s_m", 0, 3), "-2^31 cabe e volta igual");
        assertEquals(encode(2.0, 2), out(core, "FRINT32X_s_m", 0, 4), "1.5 → 2 (RN-par)");
        assertEquals(encode(2147483520.0, 2), out(core, "FRINT32X_s_m", 0, 5), "maior float que cabe em int32");
        assertEquals(IOC | IXC, fpsr(core) & (IOC | IXC), "IOC pelos fora de faixa e IXC pelo 1.5");
        // O IXC do arredondamento é DESFEITO quando o resultado estoura: 2^31 - 0.5 arredonda para 2^31.
        Aarch64Core overflow = directed("FRINT32X_d_m", 0, 0, encode(2147483647.5, 3));
        assertEquals(Double.doubleToRawLongBits(-2147483648.0), out(overflow, "FRINT32X_d_m", 0, 0));
        assertEquals(IOC, fpsr(overflow), "sem IXC");
        assertEquals(Double.doubleToRawLongBits(-9223372036854775808.0),
                out(directed("FRINT64Z_d_m", 0, 0, encode(1.0e19, 3)), "FRINT64Z_d_m", 0, 0));
        // FRINT32Z trunca ignorando FPCR.RMode.
        assertEquals(encode(2.0, 2), out(directed("FRINT32Z_s_m", 0, FPCR_RMODE_PLUS, encode(2.5, 2)), "FRINT32Z_s_m", 0, 0));
    }

    @Test
    void flushToZeroAndDefaultNanFollowFpcr() {
        // FZ achata a entrada denormal SIMPLES de FCVT e levanta IDC.
        Aarch64Core flushed = directed("FCVT_sh_m", 0, FPCR_FZ, 0x00000001L);
        assertEquals(0L, out(flushed, "FCVT_sh_m", 0, 0));
        assertEquals(IDC, fpsr(flushed) & IDC);
        // FZ16 NÃO vale na origem meia-precisão de uma conversão (FPUnpackCV): 2^-24 continua 2^-24 em simples.
        Aarch64Core half = directed("FCVT_hs_m", 0, FPCR_FZ16, 0x0001L);
        assertEquals(0x33800000L, out(half, "FCVT_hs_m", 0, 0));
        assertEquals(0L, fpsr(half));
        // ... mas vale em FSQRT (operação, não conversão).
        assertEquals(0L, out(directed("FSQRT_m", 1, FPCR_FZ16, 0x0001L), "FSQRT_m", 1, 0), "denormal achatado → √0 = 0");
        // DN: qualquer NaN vira o NaN padrão; sem DN o payload é preservado (e re-escalado na conversão).
        long payload = 0x7FC12345L;
        assertEquals(0x7FC00000L, out(directed("FSQRT_m", 2, FPCR_DN, payload), "FSQRT_m", 2, 0));
        assertEquals(payload, out(directed("FSQRT_m", 2, 0, payload), "FSQRT_m", 2, 0));
        assertEquals(0x7FF0000000000000L | ((payload & 0x7FFFFFL) << 29), out(directed("FCVT_sd_m", 0, 0, payload), "FCVT_sd_m", 0, 0));
        assertEquals(0x7FF8000000000000L, out(directed("FCVT_sd_m", 0, FPCR_DN, payload), "FCVT_sd_m", 0, 0));
    }

    @Test
    void scvtfAndUcvtfRoundByFpcrRModeAndOverflowHalf() {
        assertEquals(0x7C00L, out(directed("SCVTF_sh_m", 0, 0, 65520L), "SCVTF_sh_m", 0, 0), "65520 = meio entre 65504 e 2^16: RN-par → ∞");
        assertEquals(0x7BFFL, out(directed("SCVTF_sh_m", 0, FPCR_RMODE_ZERO, 65520L), "SCVTF_sh_m", 0, 0));
        assertEquals(0xC000L, out(directed("SCVTF_hh_m", 0, 0, 0xFFFEL), "SCVTF_hh_m", 0, 0), "int16 -2 → -2.0");
        assertEquals(encode(65534.0, 2), out(directed("UCVTF_ss_m", 0, 0, 65534L), "UCVTF_ss_m", 0, 0));
        assertEquals(Float.floatToRawIntBits(1.8446744e19f) & 0xFFFFFFFFL,
                out(directed("UCVTF_ds_m", 0, 0, -1L), "UCVTF_ds_m", 0, 0), "uint64 máximo → 2^64");
        assertEquals(encode(0.0, 3), out(directed("SCVTF_sd_m", 0, 0, 0L), "SCVTF_sd_m", 0, 0));
        Aarch64Core inexact = directed("SCVTF_ss_m", 0, 0, 16777217L); // 2^24 + 1 não cabe em float
        assertEquals(encode(16777216.0, 2), out(inexact, "SCVTF_ss_m", 0, 0));
        assertEquals(IXC, fpsr(inexact));
    }

    @Test
    void zeroingFormZeroesInactiveElementsAndMergingKeepsThem() {
        for (String name : new String[] {"FSQRT_z", "FSQRT_m"}) {
            Aarch64Core core = core(FULL, 256);
            fillGarbage(core, Z1);
            for (int e = 0; e < 8; e++) {
                setElement(core, Z3, 2, e, encode(16.0, 2));
            }
            core.scalable().setPWord(P2, 0, 0x10L); // só o elemento 1 (bit 4 = byte 4)
            run(FULL, core, spec(name, 2).word());
            assertEquals(encode(4.0, 2), getElement(core, Z1, 2, 1), name);
            long inactive = name.endsWith("_z") ? 0L : GARBAGE & 0xFFFFFFFFL;
            assertEquals(inactive, getElement(core, Z1, 2, 0), name + " elemento 0 inativo");
            assertEquals(inactive, getElement(core, Z1, 2, 7), name + " elemento 7 inativo");
        }
    }

    // ── Acesso e blocos ──────────────────────────────────────────────────────────────────────────

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
    @ValueSource(ints = {0x6588a861, 0x65d8a861, 0x65d0a861, 0x6580a861, 0x658da861, 0x649a8861, 0x6511a861})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(FULL, 256);
        core.setSystemRegisterBus(new Cpacr());
        fillGarbage(core, Z1);
        run(FULL, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(GARBAGE, core.scalable().zWord(Z1, 0), "a instrução não executou");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(FULL, 256);
        allActive(core);
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, 2, e, encode(9.0, 2));
        }
        int fsqrt = spec("FSQRT_m", 2).word();
        int frinti = spec("FRINTI_m", 2).word() & ~0x1F | 5; // z5 = frinti(z3)
        core.memory().write32(0, fsqrt);
        core.memory().write32(4, frinti);
        Ir64Block block = new StandardIr64BlockLifter(FULL).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(FULL).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(encode(3.0, 2), getElement(core, Z1, 2, 3));
        assertEquals(encode(9.0, 2), getElement(core, 5, 2, 3));
        assertFalse(decodes(SVE, 0x6511a861), "FRINT32X exige SVE2p2");
    }
}
