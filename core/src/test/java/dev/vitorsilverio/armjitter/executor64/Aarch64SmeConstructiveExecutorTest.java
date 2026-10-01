package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B18.12 — execução das construtivas SME2. Palavras montadas por `aarch64-none-elf-as`; as referências são escritas em
/// Java puro (`Math`, `Float`, aritmética de `long`), nunca chamando os helpers que o executor reusa.
class Aarch64SmeConstructiveExecutorTest {
    private static final Aarch64Architecture ALL = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-constr-exec", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2, Aarch64Feature.SME_I16I64,
            Aarch64Feature.SME_F16F16, Aarch64Feature.FP8, Aarch64Feature.SVE_B16B16);
    private static final long VBAR = 0x400L;
    private static final int[] SVLS = {256, 512};

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

    private static long get(Aarch64Core core, int z, int e, int esz) {
        int bits = Byte.SIZE << esz;
        int bit = e * bits;
        long mask = bits == Long.SIZE ? -1L : (1L << bits) - 1L;
        return (core.scalable().zWord(z, bit >>> 6) >>> (bit & 63)) & mask;
    }

    private static void put(Aarch64Core core, int z, int e, int esz, long value) {
        int bits = Byte.SIZE << esz;
        int bit = e * bits;
        long mask = bits == Long.SIZE ? -1L : (1L << bits) - 1L;
        long word = core.scalable().zWord(z, bit >>> 6);
        core.scalable().setZWord(z, bit >>> 6, word & ~(mask << (bit & 63)) | (value & mask) << (bit & 63));
    }

    private static int elements(Aarch64Core core, int esz) {
        return core.streamingVectorLengthBytes() >>> esz;
    }

    private static void randomize(Aarch64Core core, int z, Random random) {
        for (int w = 0; w < core.streamingVectorLengthBytes() / Long.BYTES; w++) {
            core.scalable().setZWord(z, w, random.nextLong());
        }
    }

    private static long clampS(long v, int bits) {
        long max = (1L << (bits - 1)) - 1;
        return Math.max(-max - 1, Math.min(max, v));
    }

    // sqrshr z3.h, {z4.s-z5.s}, #13 / sqrshrn (intercalado)
    @Test
    void signedRoundingShiftNarrowSequentialAndInterleaved() {
        for (int svl : SVLS) {
            for (int word : new int[] {0xc1e3d483, 0x45b32883}) {
                boolean interleaved = word == 0x45b32883;
                Aarch64Core core = core(svl, interleaved ? 0 : Aarch64Core.SVCR_SM_BIT);
                if (interleaved) {
                    core.setSvcr(Aarch64Core.SVCR_SM_BIT);
                }
                Random random = new Random(1);
                randomize(core, 4, random);
                randomize(core, 5, random);
                put(core, 4, 0, 2, 0x7FFFFFFFL);
                put(core, 5, 1, 2, 0x80000000L);
                int n = elements(core, 2);
                long[][] in = new long[2][n];
                for (int i = 0; i < n; i++) {
                    in[0][i] = (int) get(core, 4, i, 2);
                    in[1][i] = (int) get(core, 5, i, 2);
                }
                run(core, word);
                for (int k = 0; k < 2; k++) {
                    for (int i = 0; i < n; i++) {
                        long expected = clampS(Math.floorDiv(in[k][i] + 4096L, 8192L), 16) & 0xFFFF;
                        int index = interleaved ? 2 * i + k : k * n + i;
                        assertEquals(expected, get(core, 3, index, 1), "k=" + k + " i=" + i);
                    }
                }
            }
        }
    }

    // uqcvt z3.h, {z4.d-z7.d} ; sqcvtu ; sqcvtn z3.b, {z4.s-z7.s}
    @Test
    void integerNarrowFromFourRegisters() {
        for (int svl : SVLS) {
            Aarch64Core core = core(svl, Aarch64Core.SVCR_SM_BIT);
            Random random = new Random(2);
            for (int z = 4; z < 8; z++) {
                randomize(core, z, random);
            }
            int n = elements(core, 3);
            long[][] in = new long[4][n];
            for (int k = 0; k < 4; k++) {
                for (int i = 0; i < n; i++) {
                    in[k][i] = get(core, 4 + k, i, 3);
                }
            }
            run(core, 0xc1b3e0a3); // uqcvt z3.h, {z4.d-z7.d}
            for (int k = 0; k < 4; k++) {
                for (int i = 0; i < n; i++) {
                    long expected = Long.compareUnsigned(in[k][i], 0xFFFF) > 0 ? 0xFFFF : in[k][i];
                    assertEquals(expected, get(core, 3, k * n + i, 1));
                }
            }
            run(core, 0xc1f3e083); // sqcvtu z3.h, {z4.d-z7.d}
            for (int k = 0; k < 4; k++) {
                for (int i = 0; i < n; i++) {
                    long expected = in[k][i] < 0 ? 0 : Math.min(in[k][i], 0xFFFF);
                    assertEquals(expected, get(core, 3, k * n + i, 1));
                }
            }
        }
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        Random random = new Random(3);
        for (int z = 4; z < 8; z++) {
            randomize(core, z, random);
        }
        int n = elements(core, 2);
        long[][] in = new long[4][n];
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < n; i++) {
                in[k][i] = (int) get(core, 4 + k, i, 2);
            }
        }
        run(core, 0xc133e0c3); // sqcvtn z3.b, {z4.s-z7.s}
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < n; i++) {
                assertEquals(clampS(in[k][i], 8) & 0xFF, get(core, 3, 4 * i + k, 0));
            }
        }
    }

    @Test
    void sqrshr64BitShiftOfSixtyFour() {
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        for (int z = 4; z < 8; z++) {
            put(core, z, 0, 3, -1L);
            put(core, z, 1, 3, Long.MIN_VALUE);
            put(core, z, 2, 3, Long.MAX_VALUE);
            put(core, z, 3, 3, 5);
        }
        run(core, 0xc1a0d8a3); // uqrshr z3.h, {z4.d-z7.d}, #64
        // sem sinal, shift 64: o resultado é o bit 63 (arredondamento): -1 e MIN -> 1; MAX e 5 -> 0
        assertEquals(1, get(core, 3, 0, 1));
        assertEquals(1, get(core, 3, 1, 1));
        assertEquals(0, get(core, 3, 2, 1));
        assertEquals(0, get(core, 3, 3, 1));
        run(core, 0xc1e3d883); // sqrshr z3.h, {z4.d-z7.d}, #29
        assertEquals(0, get(core, 3, 0, 1)); // -1 / 2^29 arredonda a 0
        assertEquals(0x8000, get(core, 3, 1, 1)); // MIN satura a -32768
        assertEquals(0x7FFF, get(core, 3, 2, 1));
    }

    @Test
    void unpackWidensWithAndWithoutSign() {
        for (int svl : SVLS) {
            Aarch64Core core = core(svl, Aarch64Core.SVCR_SM_BIT);
            Random random = new Random(4);
            randomize(core, 7, random);
            int n = elements(core, 1);
            long[] in = new long[2 * n];
            for (int i = 0; i < 2 * n; i++) {
                in[i] = get(core, 7, i, 0);
            }
            run(core, 0xc165e0e4); // sunpk {z4.h-z5.h}, z7.b
            for (int r = 0; r < 2; r++) {
                for (int e = 0; e < n; e++) {
                    assertEquals((byte) in[r * n + e] & 0xFFFFL, get(core, 4 + r, e, 1));
                }
            }
            randomize(core, 8, random);
            randomize(core, 9, random);
            int ns = elements(core, 3);
            long[][] src = new long[2][2 * ns];
            for (int r = 0; r < 2; r++) {
                for (int i = 0; i < 2 * ns; i++) {
                    src[r][i] = get(core, 8 + r, i, 2);
                }
            }
            run(core, 0xc1f5e105); // uunpk {z4.d-z7.d}, {z8.s-z9.s}
            for (int r = 0; r < 2; r++) {
                for (int i = 0; i < 2; i++) {
                    for (int e = 0; e < ns; e++) {
                        assertEquals(src[r][i * ns + e], get(core, 4 + 2 * r + i, e, 3));
                    }
                }
            }
        }
    }

    @Test
    void zipAndUzipFourRegisters() {
        for (int svl : SVLS) {
            for (int[] spec : new int[][] {{0xc1b6e104, 2, 1}, {0xc136e106, 0, 0}, {0xc1f6e106, 3, 0}}) {
                int esz = spec[1];
                boolean zip = spec[2] == 1;
                Aarch64Core core = core(svl, Aarch64Core.SVCR_SM_BIT);
                Random random = new Random(5);
                for (int z = 8; z < 12; z++) {
                    randomize(core, z, random);
                }
                int n = elements(core, esz);
                long[][] in = new long[4][n];
                for (int k = 0; k < 4; k++) {
                    for (int i = 0; i < n; i++) {
                        in[k][i] = get(core, 8 + k, i, esz);
                    }
                }
                run(core, spec[0]);
                int quads = n / 4;
                for (int r = 0; r < 4; r++) {
                    for (int q = 0; q < quads; q++) {
                        for (int j = 0; j < 4; j++) {
                            if (zip) {
                                assertEquals(in[j][r * quads + q], get(core, 4 + r, 4 * q + j, esz));
                            } else {
                                assertEquals(in[r][4 * q + j], get(core, 4 + j, r * quads + q, esz));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void zipTwoRegistersAndQuadwordElement() {
        Aarch64Core core = core(512, Aarch64Core.SVCR_SM_BIT);
        Random random = new Random(6);
        randomize(core, 8, random);
        randomize(core, 9, random);
        run(core, 0xc1a9d104); // zip {z4.s-z5.s}, z8.s, z9.s
        int n = elements(core, 2);
        for (int r = 0; r < 2; r++) {
            for (int p = 0; p < n / 2; p++) {
                assertEquals(get(core, 8, r * (n / 2) + p, 2), get(core, 4 + r, 2 * p, 2));
                assertEquals(get(core, 9, r * (n / 2) + p, 2), get(core, 4 + r, 2 * p + 1, 2));
            }
        }
        long[] n0 = {get(core, 8, 0, 3), get(core, 8, 1, 3)};
        long[] n2 = {get(core, 8, 4, 3), get(core, 8, 5, 3)};
        long[] m0 = {get(core, 9, 0, 3), get(core, 9, 1, 3)};
        run(core, 0xc129d505); // uzp {z4.q-z5.q}, z8.q, z9.q
        // quadwords: d0 = [n.q0, n.q2, m.q0, m.q2], d1 = [n.q1, n.q3, m.q1, m.q3] (SVL=512 -> 4 quadwords)
        assertEquals(n0[0], get(core, 4, 0, 3));
        assertEquals(n0[1], get(core, 4, 1, 3));
        assertEquals(n2[0], get(core, 4, 2, 3));
        assertEquals(n2[1], get(core, 4, 3, 3));
        assertEquals(m0[0], get(core, 4, 4, 3));
        // com SVL=256 a forma de 4 registradores em `.q` é UNDEFINED (64 bytes por operação)
    }

    @Test
    void clampKeepsLowerAndUpperBounds() {
        for (int svl : SVLS) {
            Aarch64Core core = core(svl, Aarch64Core.SVCR_SM_BIT);
            Random random = new Random(7);
            for (int z = 4; z < 8; z++) {
                randomize(core, z, random);
            }
            randomize(core, 8, random);
            randomize(core, 9, random);
            int n = elements(core, 0);
            long[][] d = new long[4][n];
            long[] lo = new long[n];
            long[] hi = new long[n];
            for (int i = 0; i < n; i++) {
                lo[i] = (byte) get(core, 8, i, 0);
                hi[i] = (byte) get(core, 9, i, 0);
                for (int k = 0; k < 4; k++) {
                    d[k][i] = (byte) get(core, 4 + k, i, 0);
                }
            }
            run(core, 0xc129cd04); // sclamp {z4.b-z7.b}, z8.b, z9.b
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 4; k++) {
                    assertEquals(Math.min(Math.max(d[k][i], lo[i]), hi[i]) & 0xFF, get(core, 4 + k, i, 0));
                }
            }
        }
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        put(core, 4, 0, 3, 5);
        put(core, 5, 0, 3, -1L);
        put(core, 8, 0, 3, 10);
        put(core, 9, 0, 3, -2L); // uclamp: máximo é 0xFFFF..FE
        run(core, 0xc1e9c505); // uclamp {z4.d-z5.d}, z8.d, z9.d
        assertEquals(10, get(core, 4, 0, 3));
        assertEquals(-2L, get(core, 5, 0, 3));
        // fclamp {z4.s-z5.s}, z8.s, z9.s
        core = core(256, Aarch64Core.SVCR_SM_BIT);
        put(core, 4, 0, 2, Float.floatToRawIntBits(-5f));
        put(core, 5, 0, 2, Float.floatToRawIntBits(9f));
        put(core, 8, 0, 2, Float.floatToRawIntBits(0f));
        put(core, 9, 0, 2, Float.floatToRawIntBits(4f));
        run(core, 0xc1a9c104);
        assertEquals(Float.floatToRawIntBits(0f), (int) get(core, 4, 0, 2));
        assertEquals(Float.floatToRawIntBits(4f), (int) get(core, 5, 0, 2));
    }

    @Test
    void selUsesThePredicateAsCounterAcrossTheGroup() {
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        int n = elements(core, 2);
        for (int i = 0; i < n; i++) {
            put(core, 6, i, 2, 100 + i);
            put(core, 7, i, 2, 200 + i);
            put(core, 8, i, 2, 300 + i);
            put(core, 9, i, 2, 400 + i);
        }
        core.scalable().setPWord(9, 0, SveCounterOps.encode(2 * n, n + 2, 2, false));
        run(core, 0xc1a884c4); // sel {z4.s-z5.s}, pn9, {z6.s-z7.s}, {z8.s-z9.s}
        for (int i = 0; i < n; i++) {
            assertEquals(100 + i, get(core, 4, i, 2));
            assertEquals(i < 2 ? 200 + i : 400 + i, get(core, 5, i, 2));
        }
    }

    @Test
    void floatConversionsAndRounding() {
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        float[] values = {1.5f, -2.5f, 3.75f, 1e20f, Float.NaN, 0.5f, -0.5f, 7f};
        for (int i = 0; i < 8; i++) {
            put(core, 6, i, 2, Float.floatToRawIntBits(values[i]));
            put(core, 7, i, 2, i - 3);
        }
        run(core, 0xc121e0c4); // fcvtzs {z4.s-z5.s}, {z6.s-z7.s}
        assertEquals(1, (int) get(core, 4, 0, 2));
        assertEquals(-2, (int) get(core, 4, 1, 2));
        assertEquals(Integer.MAX_VALUE, (int) get(core, 4, 3, 2));
        assertEquals(0, (int) get(core, 4, 4, 2));
        run(core, 0xc122e0e4); // ucvtf {z4.s-z5.s}, {z6.s-z7.s}: Z7 inteiros -> float sem sinal
        assertEquals(Float.floatToRawIntBits((float) (0xFFFFFFFDL)), (int) get(core, 5, 0, 2));
        assertEquals(Float.floatToRawIntBits(4f), (int) get(core, 5, 7, 2));
        for (int i = 0; i < 8; i++) {
            put(core, 6, i, 2, Float.floatToRawIntBits(values[i]));
        }
        run(core, 0xc1a8e0c4); // frintn {z4.s-z5.s}, {z6.s-z7.s}
        assertEquals(Float.floatToRawIntBits(2f), (int) get(core, 4, 0, 2));
        assertEquals(Float.floatToRawIntBits(-2f), (int) get(core, 4, 1, 2));
        run(core, 0xc1bce104 & ~0); // frinta (4 regs) — Z8-Z11 vazios: só garante execução
        for (int i = 0; i < 8; i++) {
            put(core, 6, i, 2, Float.floatToRawIntBits(values[i]));
        }
        run(core, 0xc1aae0c4); // frintm
        assertEquals(Float.floatToRawIntBits(1f), (int) get(core, 4, 0, 2));
        assertEquals(Float.floatToRawIntBits(-3f), (int) get(core, 4, 1, 2));
        // scvtf / fcvtzu / frintp com 4 registradores
        for (int z = 8; z < 12; z++) {
            put(core, z, 0, 2, -7);
            put(core, z, 1, 2, 9);
        }
        run(core, 0xc132e104); // scvtf {z4.s-z7.s}, {z8.s-z11.s}
        assertEquals(Float.floatToRawIntBits(-7f), (int) get(core, 7, 0, 2));
        assertEquals(Float.floatToRawIntBits(9f), (int) get(core, 4, 1, 2));
        run(core, 0xc131e124); // fcvtzu: float(-7)/(9) -> 0 e 1207959552? usa bits como float
        assertEquals(0, (int) get(core, 4, 0, 2));
        for (int z = 8; z < 12; z++) {
            put(core, z, 0, 2, Float.floatToRawIntBits(1.2f));
        }
        run(core, 0xc1b9e104); // frintp
        assertEquals(Float.floatToRawIntBits(2f), (int) get(core, 4, 0, 2));
    }

    @Test
    void halfAndBFloat16ConversionsBothDirections() {
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        int n = elements(core, 2);
        for (int i = 0; i < n; i++) {
            put(core, 4, i, 2, Float.floatToRawIntBits(1.0f + i));
            put(core, 5, i, 2, Float.floatToRawIntBits(-2.0f - i));
        }
        run(core, 0xc120e083); // fcvt z3.h, {z4.s-z5.s} sequencial
        assertEquals(Float.floatToFloat16(1.0f) & 0xFFFF, get(core, 3, 0, 1));
        assertEquals(Float.floatToFloat16(-2.0f) & 0xFFFF, get(core, 3, n, 1));
        run(core, 0xc120e0a3); // fcvtn intercalado
        assertEquals(Float.floatToFloat16(-2.0f) & 0xFFFF, get(core, 3, 1, 1));
        run(core, 0xc160e083); // bfcvt
        assertEquals((Float.floatToRawIntBits(1.0f) >>> 16) & 0xFFFF, get(core, 3, 0, 1));
        run(core, 0xc160e0a3); // bfcvtn
        assertEquals((Float.floatToRawIntBits(-2.0f) >>> 16) & 0xFFFF, get(core, 3, 1, 1));
        int h = elements(core, 1);
        for (int i = 0; i < h; i++) {
            put(core, 7, i, 1, Float.floatToFloat16(i + 0.5f));
        }
        run(core, 0xc1a0e0e4); // fcvt {z4.s-z5.s}, z7.h (sequencial)
        assertEquals(Float.floatToRawIntBits(0.5f), (int) get(core, 4, 0, 2));
        assertEquals(Float.floatToRawIntBits(n + 0.5f), (int) get(core, 5, 0, 2));
        run(core, 0xc1a0e0e5); // fcvtl (intercalado)
        assertEquals(Float.floatToRawIntBits(1.5f), (int) get(core, 5, 0, 2));
    }

    @Test
    void fp8WidenAndNarrow() {
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT);
        int bytes = core.streamingVectorLengthBytes();
        for (int i = 0; i < bytes; i++) {
            put(core, 7, i, 0, i < bytes / 2 ? 0x3C : 0x40); // E5M2: 1.0 e 2.0
        }
        run(core, 0xc126e0e4); // f1cvt {z4.h-z5.h}, z7.b
        assertEquals(0x3C00, get(core, 4, 0, 1));
        assertEquals(0x4000, get(core, 5, 0, 1));
        for (int i = 0; i < bytes; i++) {
            put(core, 7, i, 0, i % 2 == 0 ? 0x3C : 0x40);
        }
        run(core, 0xc1a6e0e5); // f2cvtl
        assertEquals(0x3C00, get(core, 4, 3, 1));
        assertEquals(0x4000, get(core, 5, 3, 1));
        run(core, 0xc166e0e4); // bf1cvt
        assertEquals(0x3F80, get(core, 4, 0, 1));
        run(core, 0xc1e6e0e5); // bf2cvtl
        assertEquals(0x4000, get(core, 5, 0, 1));
        int halves = elements(core, 1);
        for (int i = 0; i < halves; i++) {
            put(core, 4, i, 1, 0x3C00);
            put(core, 5, i, 1, 0x4000);
        }
        run(core, 0xc124e083); // fcvt z3.b, {z4.h-z5.h}
        assertEquals(0x3C, get(core, 3, 0, 0));
        assertEquals(0x40, get(core, 3, halves, 0));
        int n = elements(core, 2);
        for (int z = 4; z < 8; z++) {
            for (int i = 0; i < n; i++) {
                put(core, z, i, 2, Float.floatToRawIntBits(z == 4 ? 1f : 2f));
            }
        }
        run(core, 0xc134e083); // fcvt z3.b, {z4.s-z7.s}
        assertEquals(0x3C, get(core, 3, 0, 0));
        assertEquals(0x40, get(core, 3, n, 0));
        run(core, 0xc134e0a3); // fcvtn
        assertEquals(0x3C, get(core, 3, 0, 0));
        assertEquals(0x40, get(core, 3, 1, 0));
    }

    @Test
    void addAndSubArrayAccumulatorsUseIntegerArithmetic() {
        Aarch64Core core = core(256, Aarch64Core.SVCR_SM_BIT | Aarch64Core.SVCR_ZA_BIT);
        int n = elements(core, 2);
        int rowsPerMember = core.streamingVectorLengthBytes() / 2;
        for (int i = 0; i < n; i++) {
            put(core, 4, i, 2, 0x7FC00001L + i); // padrão de NaN se fosse FP
            put(core, 5, i, 2, 3);
        }
        for (int member = 0; member < 2; member++) {
            for (int e = 0; e < n; e++) {
                SmeMovaOps.setZaElement(core.matrix(), 1 + member * rowsPerMember, e << 2, 2, 100);
            }
        }
        run(core, 0xc1a01c91); // add za.s[w8, 1, vgx2], {z4.s-z5.s}
        assertEquals((100 + 0x7FC00001L) & 0xFFFFFFFFL, SmeMovaOps.zaElement(core.matrix(), 1, 0, 2));
        assertEquals(103, SmeMovaOps.zaElement(core.matrix(), 1 + rowsPerMember, 0, 2));
        run(core, 0xc1a01c99); // sub za.s[w8, 1, vgx2], {z4.s-z5.s}
        assertEquals(100, SmeMovaOps.zaElement(core.matrix(), 1, 0, 2));
        assertEquals(100, SmeMovaOps.zaElement(core.matrix(), 1 + rowsPerMember, 0, 2));
    }
}
