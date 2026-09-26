package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.advsimd.AdvSimdRegisterWords;
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

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.14 — multiply-add de ponto flutuante SVE, `FMUL` indexado e aritmética complexa (21 das 24 linhas executam;
/// as 3 de `esz = 0` indexadas e a face `esz = 0` das predicadas são BFloat16 = `FEAT_SVE_B16B16`, recusadas).
/// Palavras conferidas contra `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2`).
///
/// O oráculo NÃO reusa `SveFloat`: usa `Math.fma` nativo (fundido, `float`/`double`; meia precisão via `float` com
/// valores de faixa modesta, onde a soma é exata em `float` e a ida para meia é um único arredondamento) e, para a
/// aritmética complexa, o núcleo COMPARTILHADO do A64/AdvSIMD (`AdvSimdLanes`) — o "teste cruzado" da spec. Tudo roda
/// em `VL = 256` **e** `VL = 512`, com predicado aleatório e lixo nos bytes não-baixos de cada elemento.
class Aarch64SveFpMultiplyAddTest {
    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int Z4 = 4;
    private static final int P2 = 2;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final int IOC = 1;

    // Opcodes de 3 bits (15:13): 0-3 escrevem o acumulador, 4-7 escrevem o multiplicando.
    private static final int FMLA = 0, FMLS = 1, FNMLA = 2, FNMLS = 3, FMAD = 4, FMSB = 5, FNMAD = 6, FNMSB = 7;
    private static final int OP_FMLA = 0b000000, OP_FMLS = 0b000001, OP_FMUL = 0b001000;

    // ── Palavras ─────────────────────────────────────────────────────────────────────────────────

    /// `Zd = z1`, `pg = p2`; acumulador: `rn = z3`, `rm = z4`; multiplicando: `Zm = z3`, `Za = z4`.
    private static int predicated(int esz, int opcode) {
        return 0x65200000 | esz << 22 | Z4 << 16 | opcode << 13 | P2 << 10 | Z3 << 5 | Z1;
    }

    /// `FMLA`/`FMLS`/`FMUL` indexados em `z1 = z2 op z<rm>[index]`.
    private static int indexed(int esz, int op6, int index, int rm) {
        int fields = switch (esz) {
            case 1 -> ((index >> 2) & 1) << 22 | (index & 3) << 19 | rm << 16;
            case 2 -> 0x800000 | (index & 3) << 19 | rm << 16;
            default -> 0xC00000 | (index & 1) << 20 | rm << 16;
        };
        return 0x64200000 | fields | op6 << 10 | Z2 << 5 | Z1;
    }

    private static int fcadd(int esz, int rot) {
        return 0x64008000 | esz << 22 | rot << 16 | P2 << 10 | Z3 << 5 | Z1;
    }

    private static int fcmla(int esz, int rot) {
        return 0x64000000 | esz << 22 | Z3 << 16 | rot << 13 | P2 << 10 | Z2 << 5 | Z1;
    }

    private static int fcmlaIndexed(int esz, int index, int rm, int rot) {
        int fields = esz == 1 ? 0x800000 | (index & 3) << 19 | rm << 16 : 0xC00000 | (index & 1) << 20 | rm << 16;
        return 0x64201000 | fields | rot << 10 | Z2 << 5 | Z1;
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
            return decode(architecture, word) instanceof Ir64Op.SveFpMultiplyAdd;
        } catch (UnsupportedOperationException refused) {
            return false;
        }
    }

    private static Ir64Op.SveFpMultiplyAdd decoded(int word) {
        return assertInstanceOf(Ir64Op.SveFpMultiplyAdd.class, decode(SVE, word));
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

    private static long encode(double value, int esz) {
        return switch (esz) {
            case 1 -> Float.floatToFloat16((float) value) & 0xFFFFL;
            case 2 -> Float.floatToRawIntBits((float) value) & 0xFFFFFFFFL;
            default -> Double.doubleToRawLongBits(value);
        };
    }

    private static double decodeValue(long b, int esz) {
        return switch (esz) {
            case 1 -> Float.float16ToFloat((short) b);
            case 2 -> Float.intBitsToFloat((int) b);
            default -> Double.longBitsToDouble(b);
        };
    }

    private static long negate(long b, int esz) {
        return b ^ (1L << (bits(esz) - 1));
    }

    /// Valor aleatório de faixa modesta (`±[0.25, 4)`), onde toda soma/produto de meia é exata em `float`.
    private static long random(Random random, int esz) {
        double magnitude = 0.25 + random.nextDouble() * 3.75;
        return encode(random.nextBoolean() ? -magnitude : magnitude, esz);
    }

    /// `Math.fma` nativo (fundido) sobre os bits.
    private static long fma(int esz, long n, long m, long a) {
        return switch (esz) {
            case 3 -> Double.doubleToRawLongBits(Math.fma(decodeValue(n, 3), decodeValue(m, 3), decodeValue(a, 3)));
            default -> encode(Math.fma((float) decodeValue(n, esz), (float) decodeValue(m, esz),
                    (float) decodeValue(a, esz)), esz);
        };
    }

    private static void assertSameBits(int esz, long expected, long actual, String message) {
        double e = decodeValue(expected, esz);
        if (Double.isNaN(e)) {
            assertTrue(Double.isNaN(decodeValue(actual, esz)), message + ": esperava NaN");
        } else {
            assertEquals(expected, actual, message);
        }
    }

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0x65a40861, 0x65642861, 0x65e44861, 0x65a46861, 0x65a48861, 0x6564a861, 0x65e4c861, 0x65a4e861})
    void predicatedFmaWordsFromTheAssemblerDecode(int word) {
        Ir64Op.SveFpMultiplyAdd op = decoded(word);
        assertTrue(op.predicated());
        assertFalse(op.indexed());
        assertEquals(Z1, op.rd());
        assertEquals(P2, op.pg());
        boolean writesMultiplicand = ((word >>> 13) & 0b100) != 0;
        if (writesMultiplicand) {
            assertEquals(Z1, op.rn(), "Zdn é o multiplicando");
            assertEquals(Z3, op.rm(), "bits 9:5 = Zm");
            assertEquals(Z4, op.ra(), "bits 20:16 = Za");
        } else {
            assertEquals(Z3, op.rn());
            assertEquals(Z4, op.rm());
            assertEquals(Z1, op.ra(), "Zda é o próprio Zd");
        }
    }

    @Test
    void theEightOpcodesMapToTheFourMnemonics() {
        Ir64Op.SveFpMultiplyAdd.Op[] expected = {Ir64Op.SveFpMultiplyAdd.Op.FMLA, Ir64Op.SveFpMultiplyAdd.Op.FMLS,
            Ir64Op.SveFpMultiplyAdd.Op.FNMLA, Ir64Op.SveFpMultiplyAdd.Op.FNMLS};
        for (int opcode = 0; opcode < 8; opcode++) {
            for (int esz = 1; esz <= 3; esz++) {
                assertEquals(expected[opcode & 3], decoded(predicated(esz, opcode)).op());
                assertEquals(esz, decoded(predicated(esz, opcode)).esz());
            }
        }
    }

    @Test
    void indexedWordsFromTheAssemblerDecodeWithTheirFieldLayouts() {
        Ir64Op.SveFpMultiplyAdd half = decoded(0x647b0041); // fmla z1.h, z2.h, z3.h[7]
        assertEquals(Ir64Op.SveFpMultiplyAdd.Op.FMLA, half.op());
        assertEquals(1, half.esz());
        assertEquals(7, half.index(), "bit 22 : bits 20:19");
        assertEquals(Z3, half.rm());
        assertTrue(half.indexed());
        assertFalse(half.predicated());
        assertEquals(Z1, half.ra());
        Ir64Op.SveFpMultiplyAdd single = decoded(0x64bb0041); // fmla z1.s, z2.s, z3.s[3]
        assertEquals(2, single.esz());
        assertEquals(3, single.index());
        Ir64Op.SveFpMultiplyAdd dbl = decoded(0x64fd0041); // fmla z1.d, z2.d, z13.d[1]
        assertEquals(3, dbl.esz());
        assertEquals(1, dbl.index());
        assertEquals(13, dbl.rm(), "Zm de 4 bits em dupla");
        assertEquals(Ir64Op.SveFpMultiplyAdd.Op.FMLS, decoded(0x646b0441).op()); // fmls z1.h, z2.h, z3.h[5]
        assertEquals(5, decoded(0x646b0441).index());
        Ir64Op.SveFpMultiplyAdd mul = decoded(0x64732041); // fmul z1.h, z2.h, z3.h[6]
        assertEquals(Ir64Op.SveFpMultiplyAdd.Op.FMUL, mul.op());
        assertEquals(6, mul.index());
        assertEquals(1, mul.esz());
    }

    @Test
    void complexWordsFromTheAssemblerDecode() {
        Ir64Op.SveFpMultiplyAdd add90 = decoded(0x64408861); // fcadd z1.h, p2/m, z1.h, z3.h, #90
        assertEquals(Ir64Op.SveFpMultiplyAdd.Op.FCADD, add90.op());
        assertEquals(0, add90.rot());
        assertEquals(Z3, add90.rm());
        assertEquals(Z1, add90.rn());
        assertEquals(1, decoded(0x64818861).rot(), "#270 → rot = 1 (1 bit)");
        Ir64Op.SveFpMultiplyAdd mla = decoded(0x64c34841); // fcmla z1.d, p2/m, z2.d, z3.d, #180
        assertEquals(Ir64Op.SveFpMultiplyAdd.Op.FCMLA, mla.op());
        assertEquals(2, mla.rot(), "#180 → rot = 2 (2 bits)");
        assertEquals(Z2, mla.rn());
        assertEquals(Z3, mla.rm());
        assertEquals(Z1, mla.ra());
        Ir64Op.SveFpMultiplyAdd idxHalf = decoded(0x64bb1441); // fcmla z1.h, z2.h, z3.h[3], #90
        assertTrue(idxHalf.indexed());
        assertEquals(1, idxHalf.esz(), "bits 23:22 = 10 → meia");
        assertEquals(3, idxHalf.index());
        assertEquals(1, idxHalf.rot());
        Ir64Op.SveFpMultiplyAdd idxSingle = decoded(0x64fd1c41); // fcmla z1.s, z2.s, z13.s[1], #270
        assertEquals(2, idxSingle.esz(), "bits 23:22 = 11 → simples");
        assertEquals(1, idxSingle.index());
        assertEquals(13, idxSingle.rm());
        assertEquals(3, idxSingle.rot());
    }

    @Test
    void bfloat16FormsAndUnallocatedSpacesAreRefused() {
        for (int opcode = 0; opcode < 8; opcode++) {
            assertFalse(decodes(SVE, predicated(0, opcode)), "esz = 0 predicado = BFMLA… (FEAT_SVE_B16B16)");
        }
        assertFalse(decodes(SVE, indexed(1, 0b000010, 0, 3) & ~(1 << 22)), "BFMLA indexado");
        assertFalse(decodes(SVE, indexed(1, 0b000011, 0, 3) & ~(1 << 22)), "BFMLS indexado");
        assertFalse(decodes(SVE, indexed(1, 0b001010, 0, 3) & ~(1 << 22)), "BFMUL indexado");
        assertFalse(decodes(SVE, indexed(2, 0b000010, 0, 3)), "opcode de BF16 em `10` não é alocado");
        assertFalse(decodes(SVE, indexed(3, 0b001010, 0, 3)), "opcode de BF16 em `11` não é alocado");
        assertFalse(decodes(SVE, indexed(2, 0b001100, 0, 3)), "opcode 001100 não é alocado");
        assertFalse(decodes(SVE, indexed(1, 0b000010, 4, 3)), "BFMLA indexado com o bit 22 do índice ligado");
        assertFalse(decodes(SVE, fcadd(0, 0)));
        assertFalse(decodes(SVE, fcmla(0, 0)));
        assertFalse(decodes(SVE, 0x6440A000), "espaço `0x64` vizinho (FADDQV…): não é deste grupo");
        assertFalse(decodes(SVE, 0x64A04041), "espaço `0x64` indexado vizinho (FMLALB…): não é deste grupo");
        assertFalse(decodes(SVE, 0x64201000 | 0x000000 | Z2 << 5 | Z1), "FCMLA indexado com `00`");
        assertFalse(decodes(SVE, 0x64201000 | 0x400000 | Z2 << 5 | Z1), "FCMLA indexado com `01`");
    }

    @Test
    void executingWithoutSveRefusesTheWholeSpace() {
        assertThrows(UnsupportedOperationException.class,
                () -> decode(Aarch64Architecture.ARMV8_0_A, predicated(2, FMLA)));
        assertThrows(UnsupportedOperationException.class,
                () -> decode(Aarch64Architecture.ARMV8_0_A, fcadd(2, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> decode(Aarch64Architecture.ARMV8_0_A, indexed(2, OP_FMLA, 0, 3)));
    }

    // ── FMLA/FMLS/FNMLA/FNMLS predicados ─────────────────────────────────────────────────────────

    static Stream<int[]> predicatedCases() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> IntStream.rangeClosed(1, 3).boxed()
                .flatMap(esz -> IntStream.range(0, 8).mapToObj(opcode -> new int[] {vl, esz, opcode})));
    }

    @ParameterizedTest
    @MethodSource("predicatedCases")
    void predicatedFusedMultiplyAddMatchesTheNativeFmaForEveryOperandOrder(int[] c) {
        int vl = c[0], esz = c[1], opcode = c[2];
        Random random = new Random(1000L * vl + 10L * esz + opcode);
        Aarch64Core core = core(SVE, vl);
        int count = elementCount(core, esz);
        long[] d = new long[count], n = new long[count], m = new long[count], a = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            n[i] = random(random, esz);
            m[i] = random(random, esz);
            a[i] = random(random, esz);
        }
        boolean writesMultiplicand = opcode >= FMAD;
        setElements(core, Z1, esz, d);
        // acumulador: Zn = z3, Zm = z4 (Zda = z1); multiplicando: Zm = z3, Za = z4 (Zdn = z1)
        setElements(core, Z3, esz, writesMultiplicand ? m : n);
        setElements(core, Z4, esz, writesMultiplicand ? a : m);
        boolean[] active = randomPredicate(core, esz, random);
        run(SVE, core, predicated(esz, opcode));
        long[] result = elements(core, Z1, esz);
        boolean negateProduct = (opcode & 3) == FMLS || (opcode & 3) == FNMLA;
        boolean negateAddend = (opcode & 3) == FNMLA || (opcode & 3) == FNMLS;
        for (int i = 0; i < count; i++) {
            if (!active[i]) {
                assertEquals(d[i], result[i], "inativo mantém Zd, elemento " + i);
                continue;
            }
            long multiplicand = writesMultiplicand ? d[i] : n[i]; // Zdn antigo nas formas FMAD…
            long multiplier = writesMultiplicand ? m[i] : m[i];
            long addend = writesMultiplicand ? a[i] : d[i]; // Zda antigo nas formas FMLA…
            long expected = fma(esz, negateProduct ? negate(multiplicand, esz) : multiplicand, multiplier,
                    negateAddend ? negate(addend, esz) : addend);
            assertSameBits(esz, expected, result[i], "elemento " + i + " opcode " + opcode);
        }
        assertEquals(writesMultiplicand ? m.length : n.length, count);
        assertEquals(writesMultiplicand ? java.util.Arrays.toString(m) : java.util.Arrays.toString(n),
                java.util.Arrays.toString(elements(core, Z3, esz)), "Zn/Zm de entrada intactos");
    }

    @Test
    void theAccumulatorFormsAndTheMultiplicandFormsWriteDifferentOperands() {
        // FMLA z1 = z1 + z3*z4 (1 + 2*3 = 7) vs FMAD z1 = z4 + z1*z3 (3 + 1*2 = 5) com os MESMOS registradores
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core acc = core(SVE, vl);
            allActive(acc);
            setElements(acc, Z1, 3, filled(acc, 3, encode(1.0, 3)));
            setElements(acc, Z3, 3, filled(acc, 3, encode(2.0, 3)));
            setElements(acc, Z4, 3, filled(acc, 3, encode(3.0, 3)));
            run(SVE, acc, predicated(3, FMLA));
            assertEquals(encode(7.0, 3), elements(acc, Z1, 3)[0]);
            Aarch64Core mul = core(SVE, vl);
            allActive(mul);
            setElements(mul, Z1, 3, filled(mul, 3, encode(1.0, 3)));
            setElements(mul, Z3, 3, filled(mul, 3, encode(2.0, 3)));
            setElements(mul, Z4, 3, filled(mul, 3, encode(3.0, 3)));
            run(SVE, mul, predicated(3, FMAD));
            assertEquals(encode(5.0, 3), elements(mul, Z1, 3)[0], "Zdn = Za + Zdn × Zm");
        }
    }

    @Test
    void fnmlaNegatesTheProductAndTheAddendButTheOtherThreeDoNot() {
        // n = 2, m = 3, a = 10: FMLA 16, FMLS 4, FNMLA -16, FNMLS -4
        double[] expected = {16.0, 4.0, -16.0, -4.0};
        for (int op = 0; op < 4; op++) {
            Aarch64Core core = core(SVE, 256);
            allActive(core);
            setElements(core, Z1, 2, filled(core, 2, encode(10.0, 2)));
            setElements(core, Z3, 2, filled(core, 2, encode(2.0, 2)));
            setElements(core, Z4, 2, filled(core, 2, encode(3.0, 2)));
            run(SVE, core, predicated(2, op));
            assertEquals(encode(expected[op], 2), elements(core, Z1, 2)[0], "opcode " + op);
        }
        // as formas FMAD/FMSB/FNMAD/FNMSB, com (Zdn=2, Zm=3, Za=10): mesmos resultados
        for (int op = 4; op < 8; op++) {
            Aarch64Core core = core(SVE, 256);
            allActive(core);
            setElements(core, Z1, 2, filled(core, 2, encode(2.0, 2)));
            setElements(core, Z3, 2, filled(core, 2, encode(3.0, 2)));
            setElements(core, Z4, 2, filled(core, 2, encode(10.0, 2)));
            run(SVE, core, predicated(2, op));
            assertEquals(encode(expected[op - 4], 2), elements(core, Z1, 2)[0], "opcode " + op);
        }
    }

    @Test
    void theMultiplyAddIsFusedNotRoundedTwice() {
        // n = m = 1 + 2^-12, a = -(1 + 2^-11): n×m = 1 + 2^-11 + 2^-24, EXATO; separado (arredonda o produto) dá 0.
        float n = 1.0f + 0x1p-12f;
        float a = -(1.0f + 0x1p-11f);
        float separate = n * n + a;
        assertEquals(0.0f, separate, "premissa: a conta com dois arredondamentos dá zero");
        Aarch64Core single = core(SVE, 256);
        allActive(single);
        setElements(single, Z1, 2, filled(single, 2, encode(a, 2)));
        setElements(single, Z3, 2, filled(single, 2, encode(n, 2)));
        setElements(single, Z4, 2, filled(single, 2, encode(n, 2)));
        run(SVE, single, predicated(2, FMLA));
        assertEquals(encode(0x1p-24, 2), elements(single, Z1, 2)[0], "fundido: resta exatamente 2^-24");

        double nd = 1.0 + 0x1p-27;
        double ad = -(1.0 + 0x1p-26);
        assertEquals(0.0, nd * nd + ad, "premissa em dupla");
        Aarch64Core dbl = core(SVE, 256);
        allActive(dbl);
        setElements(dbl, Z1, 3, filled(dbl, 3, encode(ad, 3)));
        setElements(dbl, Z3, 3, filled(dbl, 3, encode(nd, 3)));
        setElements(dbl, Z4, 3, filled(dbl, 3, encode(nd, 3)));
        run(SVE, dbl, predicated(3, FMLA));
        assertEquals(encode(0x1p-54, 3), elements(dbl, Z1, 3)[0]);
    }

    @Test
    void inactiveElementsNeitherExecuteNorDirtyTheFpsr() {
        Aarch64Core core = core(SVE, 256);
        long inf = encode(Double.POSITIVE_INFINITY, 2);
        setElements(core, Z1, 2, filled(core, 2, encode(1.0, 2)));
        setElements(core, Z3, 2, filled(core, 2, inf));
        setElements(core, Z4, 2, filled(core, 2, encode(0.0, 2))); // ∞ × 0 = inválido
        run(SVE, core, predicated(2, FMLA)); // P2 = 0: nenhum elemento ativo
        assertEquals(0L, fpsr(core), "nenhum elemento ativo: FPSR limpo");
        assertEquals(encode(1.0, 2), elements(core, Z1, 2)[0]);
        allActive(core);
        run(SVE, core, predicated(2, FMLA));
        assertEquals(IOC, fpsr(core) & 0xFF, "com elemento ativo levanta IOC");
        assertEquals(0x7FC00000L, elements(core, Z1, 2)[0], "NaN padrão");
    }

    // ── FMLA/FMLS/FMUL indexados ─────────────────────────────────────────────────────────────────

    static Stream<int[]> indexedCases() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> IntStream.rangeClosed(1, 3).boxed()
                .flatMap(esz -> IntStream.of(OP_FMLA, OP_FMLS, OP_FMUL).boxed()
                        .flatMap(op6 -> IntStream.range(0, 16 >> esz).mapToObj(index -> new int[] {vl, esz, op6, index}))));
    }

    @ParameterizedTest
    @MethodSource("indexedCases")
    void indexedFormsReadTheElementOfEachSegmentOfZm(int[] c) {
        int vl = c[0], esz = c[1], op6 = c[2], index = c[3];
        Random random = new Random(7L * vl + 100L * esz + 10L * op6 + index);
        int rm = esz == 3 ? 13 : 5;
        Aarch64Core core = core(SVE, vl);
        int count = elementCount(core, esz);
        int perSegment = 16 >> esz;
        long[] d = new long[count], n = new long[count], m = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            n[i] = random(random, esz);
            m[i] = random(random, esz);
        }
        setElements(core, Z1, esz, d);
        setElements(core, Z2, esz, n);
        setElements(core, rm, esz, m);
        run(SVE, core, indexed(esz, op6, index, rm));
        long[] result = elements(core, Z1, esz);
        for (int i = 0; i < count; i++) {
            long selected = m[(i / perSegment) * perSegment + index];
            long expected = switch (op6) {
                case OP_FMLA -> fma(esz, n[i], selected, d[i]);
                case OP_FMLS -> fma(esz, negate(n[i], esz), selected, d[i]);
                default -> fma(esz, n[i], selected, encode(-0.0, esz)); // n × m arredondado uma vez
            };
            assertSameBits(esz, expected, result[i], "elemento " + i + " segmento " + i / perSegment);
        }
        assertEquals(java.util.Arrays.toString(m), java.util.Arrays.toString(elements(core, rm, esz)), "Zm intacto");
    }

    @Test
    void indexedFormsReadZmBeforeTheFirstWriteEvenWhenZdIsZm() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE, vl);
            int count = elementCount(core, 2);
            long[] v = new long[count];
            for (int i = 0; i < count; i++) {
                v[i] = encode(i + 1, 2);
            }
            setElements(core, Z1, 2, v);
            setElements(core, Z2, 2, filled(core, 2, encode(2.0, 2)));
            // fmul z1.s = z2.s × z1.s[0]  (Zd == Zm, índice 0): cada segmento multiplica por SEU elemento 0 ORIGINAL
            run(SVE, core, indexed(2, OP_FMUL, 0, Z1));
            long[] result = elements(core, Z1, 2);
            for (int i = 0; i < count; i++) {
                assertEquals(encode(2.0 * (i / 4 * 4 + 1), 2), result[i], "elemento " + i);
            }
        }
    }

    // ── FCADD ────────────────────────────────────────────────────────────────────────────────────

    /// Vista de palavras sobre um `long[]`: `Zd` em `0..`, `Zn` em `4..`, `Zm` em `8..` (registradores de 128 bits, 2 palavras cada).
    private static final class Words implements AdvSimdRegisterWords {
        final long[] words = new long[16];

        @Override
        public long word(int index) {
            return words[index];
        }

        @Override
        public void setWord(int index, long value) {
            words[index] = value;
        }
    }

    private static final int WORDS_D = 0, WORDS_N = 4, WORDS_M = 8;
    private static final int[] ROTATIONS = {AdvSimdLanes.COMPLEX_ROTATE_0, AdvSimdLanes.COMPLEX_ROTATE_90,
        AdvSimdLanes.COMPLEX_ROTATE_180, AdvSimdLanes.COMPLEX_ROTATE_270};

    private static void loadSegment(Words words, int base, long[] values, int segment, int esz) {
        int perSegment = 16 >> esz;
        for (int i = 0; i < perSegment; i++) {
            AdvSimdLanes.setElement(words, base, i, esz, values[segment * perSegment + i]);
        }
    }

    private static long segmentElement(Words words, int base, int lane, int esz) {
        return AdvSimdLanes.element(words, base, lane, esz);
    }

    static Stream<int[]> complexCases() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> IntStream.rangeClosed(1, 3).boxed()
                .flatMap(esz -> IntStream.range(0, 4).mapToObj(rot -> new int[] {vl, esz, rot})));
    }

    @ParameterizedTest
    @MethodSource("complexCases")
    void fcaddMatchesTheSharedA64CoreOnEverySegment(int[] c) {
        int vl = c[0], esz = c[1], rot = c[2];
        if (rot > 1) {
            return; // FCADD só tem 90° e 270°
        }
        Random random = new Random(31L * vl + 7L * esz + rot);
        Aarch64Core core = core(SVE, vl);
        allActive(core);
        int count = elementCount(core, esz);
        long[] d = new long[count], m = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            m[i] = random(random, esz);
        }
        setElements(core, Z1, esz, d);
        setElements(core, Z3, esz, m);
        run(SVE, core, fcadd(esz, rot));
        long[] result = elements(core, Z1, esz);
        for (int segment = 0; segment < count / (16 >> esz); segment++) {
            Words words = new Words();
            loadSegment(words, WORDS_N, d, segment, esz);
            loadSegment(words, WORDS_M, m, segment, esz);
            AdvSimdLanes.fpComplexAdd(words, esz, 16 >> esz, WORDS_D, WORDS_N, WORDS_M,
                    rot == 0 ? AdvSimdLanes.COMPLEX_ROTATE_90 : AdvSimdLanes.COMPLEX_ROTATE_270);
            for (int lane = 0; lane < (16 >> esz); lane++) {
                assertEquals(segmentElement(words, WORDS_D, lane, esz), result[segment * (16 >> esz) + lane],
                        "segmento " + segment + " lane " + lane);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("complexCases")
    void fcaddPredicatesEachHalfOfThePairIndependently(int[] c) {
        int vl = c[0], esz = c[1], rot = c[2];
        if (rot > 1) {
            return;
        }
        Random random = new Random(53L * vl + 3L * esz + rot);
        Aarch64Core core = core(SVE, vl);
        int count = elementCount(core, esz);
        long[] d = new long[count], m = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            m[i] = random(random, esz);
        }
        setElements(core, Z1, esz, d);
        setElements(core, Z3, esz, m);
        boolean[] active = randomPredicate(core, esz, random);
        run(SVE, core, fcadd(esz, rot));
        long[] result = elements(core, Z1, esz);
        for (int re = 0; re < count; re += 2) {
            int im = re + 1;
            long expectedRe = fma(esz, rot == 0 ? negate(m[im], esz) : m[im], encode(1.0, esz), d[re]);
            long expectedIm = fma(esz, rot == 0 ? m[re] : negate(m[re], esz), encode(1.0, esz), d[im]);
            assertEquals(active[re] ? expectedRe : d[re], result[re], "real " + re);
            assertEquals(active[im] ? expectedIm : d[im], result[im], "imaginário " + im);
        }
    }

    // ── FCMLA ────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("complexCases")
    void fcmlaMatchesTheSharedA64CoreOnEverySegment(int[] c) {
        int vl = c[0], esz = c[1], rot = c[2];
        Random random = new Random(71L * vl + 13L * esz + rot);
        Aarch64Core core = core(SVE, vl);
        allActive(core);
        int count = elementCount(core, esz);
        long[] d = new long[count], n = new long[count], m = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            n[i] = random(random, esz);
            m[i] = random(random, esz);
        }
        setElements(core, Z1, esz, d);
        setElements(core, Z2, esz, n);
        setElements(core, Z3, esz, m);
        run(SVE, core, fcmla(esz, rot));
        long[] result = elements(core, Z1, esz);
        for (int segment = 0; segment < count / (16 >> esz); segment++) {
            Words words = new Words();
            loadSegment(words, WORDS_D, d, segment, esz);
            loadSegment(words, WORDS_N, n, segment, esz);
            loadSegment(words, WORDS_M, m, segment, esz);
            AdvSimdLanes.fpComplexMultiplyAccumulate(words, esz, 16 >> esz, WORDS_D, WORDS_N, WORDS_M, ROTATIONS[rot]);
            for (int lane = 0; lane < (16 >> esz); lane++) {
                assertEquals(segmentElement(words, WORDS_D, lane, esz), result[segment * (16 >> esz) + lane],
                        "rot " + rot + " segmento " + segment + " lane " + lane);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("complexCases")
    void fcmlaPredicatesEachHalfOfThePairIndependently(int[] c) {
        int vl = c[0], esz = c[1], rot = c[2];
        Random random = new Random(97L * vl + 17L * esz + rot);
        Aarch64Core core = core(SVE, vl);
        int count = elementCount(core, esz);
        long[] d = new long[count], n = new long[count], m = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            n[i] = random(random, esz);
            m[i] = random(random, esz);
        }
        setElements(core, Z1, esz, d);
        setElements(core, Z2, esz, n);
        setElements(core, Z3, esz, m);
        boolean[] active = randomPredicate(core, esz, random);
        run(SVE, core, fcmla(esz, rot));
        long[] result = elements(core, Z1, esz);
        boolean flip = (rot & 1) != 0;
        boolean negateImaginary = rot >= 2;
        boolean negateReal = flip ^ negateImaginary;
        for (int re = 0; re < count; re += 2) {
            int im = re + 1;
            long e2 = flip ? n[im] : n[re];
            long e1 = flip ? m[im] : m[re];
            long e3 = flip ? m[re] : m[im];
            long expectedRe = fma(esz, e2, negateReal ? negate(e1, esz) : e1, d[re]);
            long expectedIm = fma(esz, e2, negateImaginary ? negate(e3, esz) : e3, d[im]);
            assertEquals(active[re] ? expectedRe : d[re], result[re], "real " + re + " rot " + rot);
            assertEquals(active[im] ? expectedIm : d[im], result[im], "imaginário " + im + " rot " + rot);
        }
    }

    static Stream<int[]> complexIndexedCases() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> IntStream.rangeClosed(1, 2).boxed()
                .flatMap(esz -> IntStream.range(0, 4).boxed()
                        .flatMap(rot -> IntStream.range(0, 8 >> esz).mapToObj(index -> new int[] {vl, esz, rot, index}))));
    }

    @ParameterizedTest
    @MethodSource("complexIndexedCases")
    void fcmlaIndexedMatchesTheSharedA64CoreByElement(int[] c) {
        int vl = c[0], esz = c[1], rot = c[2], index = c[3];
        Random random = new Random(113L * vl + 19L * esz + 5L * rot + index);
        int rm = esz == 1 ? 5 : 13;
        Aarch64Core core = core(SVE, vl);
        int count = elementCount(core, esz);
        long[] d = new long[count], n = new long[count], m = new long[count];
        for (int i = 0; i < count; i++) {
            d[i] = random(random, esz);
            n[i] = random(random, esz);
            m[i] = random(random, esz);
        }
        setElements(core, Z1, esz, d);
        setElements(core, Z2, esz, n);
        setElements(core, rm, esz, m);
        run(SVE, core, fcmlaIndexed(esz, index, rm, rot));
        long[] result = elements(core, Z1, esz);
        for (int segment = 0; segment < count / (16 >> esz); segment++) {
            Words words = new Words();
            loadSegment(words, WORDS_D, d, segment, esz);
            loadSegment(words, WORDS_N, n, segment, esz);
            loadSegment(words, WORDS_M, m, segment, esz);
            AdvSimdLanes.fpComplexMultiplyAccumulateByElement(words, esz, 16 >> esz, WORDS_D, WORDS_N, WORDS_M, index,
                    ROTATIONS[rot]);
            for (int lane = 0; lane < (16 >> esz); lane++) {
                assertEquals(segmentElement(words, WORDS_D, lane, esz), result[segment * (16 >> esz) + lane],
                        "índice " + index + " rot " + rot + " segmento " + segment + " lane " + lane);
            }
        }
    }

    @Test
    void fcmlaIndexedReadsTheZmPairBeforeTheFirstWriteEvenWhenZdIsZm() {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE, vl);
            int count = elementCount(core, 2);
            long[] v = new long[count];
            for (int i = 0; i < count; i++) {
                v[i] = encode(i + 1, 2);
            }
            setElements(core, Z1, 2, v);
            setElements(core, Z2, 2, filled(core, 2, encode(1.0, 2)));
            // fcmla z1.s, z2.s, z1.s[0], #0: re += n_re × m_re, im += n_re × m_im, m = par 0 ORIGINAL do segmento
            run(SVE, core, fcmlaIndexed(2, 0, Z1, 0));
            long[] result = elements(core, Z1, 2);
            for (int segment = 0; segment < count / 4; segment++) {
                double mRe = segment * 4 + 1;
                double mIm = segment * 4 + 2;
                for (int pair = 0; pair < 2; pair++) {
                    double accRe = segment * 4 + pair * 2 + 1;
                    double accIm = segment * 4 + pair * 2 + 2;
                    assertEquals(encode(accRe + mRe, 2), result[segment * 4 + pair * 2], "real");
                    assertEquals(encode(accIm + mIm, 2), result[segment * 4 + pair * 2 + 1], "imaginário");
                }
            }
        }
    }

    // ── Acesso, streaming e blocos ───────────────────────────────────────────────────────────────

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
    @ValueSource(ints = {0x65a40861, 0x647b0041, 0x64732041, 0x64408861, 0x64430841, 0x64bb1441})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE, 256);
        core.setSystemRegisterBus(new Cpacr());
        setElements(core, Z1, 3, filled(core, 3, 0x1234L));
        run(SVE, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, elements(core, Z1, 3)[0], "a instrução não executou");
    }

    @Test
    void streamingModeRunsTheGroupAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        allActive(core);
        setElements(core, Z1, 3, filled(core, 3, encode(1.0, 3)));
        setElements(core, Z3, 3, filled(core, 3, encode(2.0, 3)));
        setElements(core, Z4, 3, filled(core, 3, encode(3.0, 3)));
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, predicated(3, FMLA));
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção: legal em streaming");
        long[] result = elements(core, Z1, 3);
        assertEquals(8, result.length, "SVL = 512: 8 doublewords");
        assertEquals(encode(7.0, 3), result[7]);
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z1, 2, filled(core, 2, encode(1.0, 2)));
        setElements(core, Z3, 2, filled(core, 2, encode(2.0, 2)));
        setElements(core, Z4, 2, filled(core, 2, encode(3.0, 2)));
        core.memory().write32(0, predicated(2, FMLA)); // 1 + 2×3 = 7
        core.memory().write32(4, predicated(2, FNMLS)); // -(7)… Zn=z3=2, Zm=z4=3: -7 + 6 = -1
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(encode(-1.0, 2), elements(core, Z1, 2)[0]);
    }
}
