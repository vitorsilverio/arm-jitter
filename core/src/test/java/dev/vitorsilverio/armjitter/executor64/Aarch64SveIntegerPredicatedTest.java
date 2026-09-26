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
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.6 — inteiro SVE com predicado (aritmética binária, shifts e unárias; 68 encodings). Palavras
/// conferidas contra `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1`; as `_z` com
/// `armv9.6-a+sve2p2`); registradores fixos: `Zd=Zdn=z1`, `Zm=z3` (nas unárias `Zn=z3`), `pg=p2`.
/// O oráculo é escrito com `BigInteger`, sem repetir a aritmética do executor; toda semântica roda em
/// `VL = 256` **e** `VL = 512`, com predicado aleatório e lixo nos bytes não-baixos de cada elemento.
class Aarch64SveIntegerPredicatedTest {
    private static final int Z1 = 1;
    private static final int Z3 = 3;
    private static final int P2 = 2;
    private static final int GROUP_BINARY = 0x04000861;
    private static final int GROUP_SHIFT = 0x04008861;
    private static final int GROUP_UNARY = 0x0400A861;
    private static final int GROUP_SHIFT_IMMEDIATE = 0x04008801;
    private static final int OPCODE_SHIFT = 16;
    private static final int ESZ_SHIFT = 22;
    private static final int UNARY_MERGING = 0x10;
    private static final int UNARY_BIT_OPS = 0x08;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);
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

    private static boolean decodes(Aarch64Architecture architecture, int word) {
        try {
            return decode(architecture, word) instanceof Ir64Op.SveIntegerPredicated;
        } catch (UnsupportedOperationException refused) {
            return false;
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

    private static BigInteger signedOf(BigInteger unsignedValue, int width) {
        return unsignedValue.testBit(width - 1) ? unsignedValue.subtract(BigInteger.ONE.shiftLeft(width))
                : unsignedValue;
    }

    private static long truncate(BigInteger value, int esz) {
        return value.and(mask(esz)).longValue();
    }

    private static long[] elements(Aarch64Core core, int reg, int esz) {
        long[] out = new long[elementCount(core, esz)];
        for (int i = 0; i < out.length; i++) {
            int bitOffset = i * bits(esz);
            long word = core.scalable().zWord(reg, bitOffset / 64);
            long shifted = word >>> (bitOffset % 64);
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
            long word = core.scalable().zWord(reg, bitOffset / 64);
            core.scalable().setZWord(reg, bitOffset / 64, word | (field << (bitOffset % 64)));
        }
    }

    /// Valores aleatórios com viés para os extremos (0, máximo, mínimo, ±1), onde a saturação acontece.
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

    /// Quantidades de shift: muitas dentro de `[0, esize + 2]` (onde `esize` é a fronteira) e algumas gigantes.
    private static long[] shiftAmounts(Aarch64Core core, int esz, Random random) {
        long[] out = new long[elementCount(core, esz)];
        for (int i = 0; i < out.length; i++) {
            out[i] = random.nextInt(4) == 0 ? random.nextLong() : random.nextInt(bits(esz) + 3);
        }
        return out;
    }

    /// Liga em `P2` o bit do byte mais baixo de cada elemento ativo e um lixo aleatório nos demais bytes.
    private static boolean[] randomPredicate(Aarch64Core core, int esz, Random random) {
        int elements = elementCount(core, esz);
        boolean[] active = new boolean[elements];
        for (int e = 0; e < elements; e++) {
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

    private static void allActive(Aarch64Core core) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(P2, w, -1L);
        }
    }

    /// Executa `word` sobre `Z1` (=`Zdn`, elementos aleatórios) e `Z3` e compara elemento a elemento com o
    /// oráculo `(Zdn0, Z3) -> resultado`. Inativo: intacto (merging) ou zero (`zeroing`).
    private static void check(Aarch64Architecture architecture, int word, int esz, int vl, long seed, long[] z1Values,
            long[] z3Values, boolean zeroing, BiFunction<BigInteger, BigInteger, BigInteger> oracle, String label) {
        Aarch64Core core = core(architecture, vl);
        Random random = new Random(seed * 31 + esz * 7L + vl);
        setElements(core, Z1, esz, z1Values != null ? z1Values : randomElements(core, esz, random));
        setElements(core, Z3, esz, z3Values != null ? z3Values : randomElements(core, esz, random));
        boolean[] active = randomPredicate(core, esz, random);
        long[] d0 = elements(core, Z1, esz);
        long[] z3 = elements(core, Z3, esz);
        run(architecture, core, word);
        long[] d = elements(core, Z1, esz);
        assertEquals(z3.length, d.length);
        for (int e = 0; e < d.length; e++) {
            long expected = !active[e]
                    ? (zeroing ? 0L : d0[e])
                    : truncate(oracle.apply(unsigned(d0[e], esz), unsigned(z3[e], esz)), esz);
            assertEquals(expected, d[e], label + " esz=" + esz + " vl=" + vl + " e=" + e + " Zdn=" + Long.toHexString(d0[e])
                    + " Zm=" + Long.toHexString(z3[e]) + (active[e] ? " (ativo)" : " (inativo)"));
        }
    }

    // ── Palavras ─────────────────────────────────────────────────────────────────────────────────

    private static int binaryWord(int esz, int opcode) {
        return GROUP_BINARY | (esz << ESZ_SHIFT) | (opcode << OPCODE_SHIFT);
    }

    private static int shiftWord(int esz, int opcode) {
        return GROUP_SHIFT | (esz << ESZ_SHIFT) | (opcode << OPCODE_SHIFT);
    }

    private static int unaryWord(int esz, int opcode) {
        return GROUP_UNARY | (esz << ESZ_SHIFT) | (opcode << OPCODE_SHIFT);
    }

    /// Shift por imediato: `tszimm` (7 bits) espalhado em 23:22 e 9:5.
    private static int immediateWord(int opcode, int tszimm) {
        return GROUP_SHIFT_IMMEDIATE | (opcode << OPCODE_SHIFT) | ((tszimm >> 5) << ESZ_SHIFT) | ((tszimm & 0x1F) << 5);
    }

    // ── Decoder ──────────────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> decodeCases() {
        return Stream.of(
                Arguments.of(0x04180861, Ir64Op.SveIntegerPredicated.Op.ORR), // orr z1.b, p2/m, z1.b, z3.b
                Arguments.of(0x04190861, Ir64Op.SveIntegerPredicated.Op.EOR),
                Arguments.of(0x041a0861, Ir64Op.SveIntegerPredicated.Op.AND),
                Arguments.of(0x041b0861, Ir64Op.SveIntegerPredicated.Op.BIC),
                Arguments.of(0x04000861, Ir64Op.SveIntegerPredicated.Op.ADD),
                Arguments.of(0x04010861, Ir64Op.SveIntegerPredicated.Op.SUB),
                Arguments.of(0x04030861, Ir64Op.SveIntegerPredicated.Op.SUB), // subr
                Arguments.of(0x04080861, Ir64Op.SveIntegerPredicated.Op.SMAX),
                Arguments.of(0x04090861, Ir64Op.SveIntegerPredicated.Op.UMAX),
                Arguments.of(0x040a0861, Ir64Op.SveIntegerPredicated.Op.SMIN),
                Arguments.of(0x040b0861, Ir64Op.SveIntegerPredicated.Op.UMIN),
                Arguments.of(0x040c0861, Ir64Op.SveIntegerPredicated.Op.SABD),
                Arguments.of(0x040d0861, Ir64Op.SveIntegerPredicated.Op.UABD),
                Arguments.of(0x04100861, Ir64Op.SveIntegerPredicated.Op.MUL),
                Arguments.of(0x04120861, Ir64Op.SveIntegerPredicated.Op.SMULH),
                Arguments.of(0x04130861, Ir64Op.SveIntegerPredicated.Op.UMULH),
                Arguments.of(0x04940861, Ir64Op.SveIntegerPredicated.Op.SDIV), // sdiv z1.s
                Arguments.of(0x04950861, Ir64Op.SveIntegerPredicated.Op.UDIV),
                Arguments.of(0x04960861, Ir64Op.SveIntegerPredicated.Op.SDIV), // sdivr
                Arguments.of(0x04d70861, Ir64Op.SveIntegerPredicated.Op.UDIV), // udivr z1.d
                Arguments.of(0x04108861, Ir64Op.SveIntegerPredicated.Op.ASR),
                Arguments.of(0x04118861, Ir64Op.SveIntegerPredicated.Op.LSR),
                Arguments.of(0x04138861, Ir64Op.SveIntegerPredicated.Op.LSL),
                Arguments.of(0x04148861, Ir64Op.SveIntegerPredicated.Op.ASR), // asrr
                Arguments.of(0x04158861, Ir64Op.SveIntegerPredicated.Op.LSR), // lsrr
                Arguments.of(0x04178861, Ir64Op.SveIntegerPredicated.Op.LSL), // lslr
                Arguments.of(0x04188861, Ir64Op.SveIntegerPredicated.Op.ASR_WIDE),
                Arguments.of(0x04198861, Ir64Op.SveIntegerPredicated.Op.LSR_WIDE),
                Arguments.of(0x041b8861, Ir64Op.SveIntegerPredicated.Op.LSL_WIDE),
                Arguments.of(0x040089e1, Ir64Op.SveIntegerPredicated.Op.ASR_IMM), // asr z1.b, p2/m, z1.b, #1
                Arguments.of(0x040189a1, Ir64Op.SveIntegerPredicated.Op.LSR_IMM),
                Arguments.of(0x040389e1, Ir64Op.SveIntegerPredicated.Op.LSL_IMM),
                Arguments.of(0x040489c1, Ir64Op.SveIntegerPredicated.Op.ASRD),
                Arguments.of(0x04068961, Ir64Op.SveIntegerPredicated.Op.SQSHL_IMM),
                Arguments.of(0x04078aa1, Ir64Op.SveIntegerPredicated.Op.UQSHL_IMM),
                Arguments.of(0x044c8b21, Ir64Op.SveIntegerPredicated.Op.SRSHR),
                Arguments.of(0x040d8901, Ir64Op.SveIntegerPredicated.Op.URSHR),
                Arguments.of(0x044f8921, Ir64Op.SveIntegerPredicated.Op.SQSHLU),
                Arguments.of(0x0418a861, Ir64Op.SveIntegerPredicated.Op.CLS),
                Arguments.of(0x0459a861, Ir64Op.SveIntegerPredicated.Op.CLZ),
                Arguments.of(0x049aa861, Ir64Op.SveIntegerPredicated.Op.CNT),
                Arguments.of(0x04dba861, Ir64Op.SveIntegerPredicated.Op.CNOT),
                Arguments.of(0x041ea861, Ir64Op.SveIntegerPredicated.Op.NOT),
                Arguments.of(0x045ca861, Ir64Op.SveIntegerPredicated.Op.FABS),
                Arguments.of(0x04dda861, Ir64Op.SveIntegerPredicated.Op.FNEG),
                Arguments.of(0x0416a861, Ir64Op.SveIntegerPredicated.Op.ABS),
                Arguments.of(0x04d7a861, Ir64Op.SveIntegerPredicated.Op.NEG),
                Arguments.of(0x0450a861, Ir64Op.SveIntegerPredicated.Op.SXTB),
                Arguments.of(0x0491a861, Ir64Op.SveIntegerPredicated.Op.UXTB),
                Arguments.of(0x0492a861, Ir64Op.SveIntegerPredicated.Op.SXTH),
                Arguments.of(0x04d3a861, Ir64Op.SveIntegerPredicated.Op.UXTH),
                Arguments.of(0x04d4a861, Ir64Op.SveIntegerPredicated.Op.SXTW),
                Arguments.of(0x04d5a861, Ir64Op.SveIntegerPredicated.Op.UXTW));
    }

    @ParameterizedTest
    @MethodSource("decodeCases")
    void assemblerWordsDecodeToTheExpectedOperation(int word, Ir64Op.SveIntegerPredicated.Op expected) {
        Ir64Op.SveIntegerPredicated op = assertInstanceOf(Ir64Op.SveIntegerPredicated.class, decode(SVE2, word));
        assertEquals(expected, op.op());
        assertEquals(P2, op.pg());
        assertEquals(Z1, op.rd());
        assertEquals(false, op.zeroing(), "as palavras acima são todas merging");
    }

    @Test
    void reverseFormsSwapTheOperandsInsteadOfDecodingAsTheDirectForm() {
        Ir64Op.SveIntegerPredicated direct = (Ir64Op.SveIntegerPredicated) decode(SVE, 0x04010861); // sub
        Ir64Op.SveIntegerPredicated reverse = (Ir64Op.SveIntegerPredicated) decode(SVE, 0x04030861); // subr
        assertEquals(Ir64Op.SveIntegerPredicated.Op.SUB, direct.op());
        assertEquals(Ir64Op.SveIntegerPredicated.Op.SUB, reverse.op());
        assertEquals(Z1, direct.rn());
        assertEquals(Z3, direct.rm());
        assertEquals(Z3, reverse.rn(), "subr Zdn = Zm - Zdn: o campo 9:5 é o primeiro operando");
        assertEquals(Z1, reverse.rm());
    }

    @Test
    void zeroingFormsDecodeOnlyWithSve2p2AndCarryTheFlag() {
        int absZ = 0x0406a861; // abs z1.b, p2/z, z3.b
        Ir64Op.SveIntegerPredicated op = (Ir64Op.SveIntegerPredicated) decode(SVE2P2, absZ);
        assertEquals(Ir64Op.SveIntegerPredicated.Op.ABS, op.op());
        assertTrue(op.zeroing());
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE2, absZ));
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE, absZ));
    }

    @Test
    void theFiveSve2ImmediateShiftsAreGatedBySve2() {
        int[] sve2Only = {0x04068961, 0x04078aa1, 0x044c8b21, 0x040d8901, 0x044f8921};
        for (int word : sve2Only) {
            assertInstanceOf(Ir64Op.SveIntegerPredicated.class, decode(SVE2, word));
            assertThrows(UnsupportedOperationException.class, () -> decode(SVE, word), Integer.toHexString(word));
        }
        assertInstanceOf(Ir64Op.SveIntegerPredicated.class, decode(SVE, 0x040089e1)); // asr é SVE puro
    }

    /// A escada do épico diz 68 encodings: 20 binárias + 18 de shift + 30 unárias (15 `_m` + 15 `_z`).
    @Test
    void theGroupHasExactlySixtyEightDecodableRows() {
        assertEquals(68, decodableRows(SVE2P2));
        assertEquals(68 - 15, decodableRows(SVE2), "sem SVE2p2 as 15 `_z` somem");
        assertEquals(68 - 15 - 5, decodableRows(SVE), "sem SVE2 somem também as 5 de shift por imediato");
    }

    private static long decodableRows(Aarch64Architecture architecture) {
        long binary = IntStream.range(0, 32).filter(opcode -> decodes(architecture, binaryWord(2, opcode))).count();
        long shifts = IntStream.range(0, 32).filter(opcode -> opcode < 0x10
                ? decodes(architecture, immediateWord(opcode, (16 << 2) - 1)) // esz = 2, imm = 1
                : decodes(architecture, shiftWord(opcode >= 0x18 ? 0 : 2, opcode))).count(); // `_zzw` não existe em .d
        long unary = IntStream.range(0, 32).filter(opcode -> decodes(architecture, unaryWord(3, opcode))).count();
        return binary + shifts + unary;
    }

    private static void assertRefused(int word, String label) {
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE2P2, word), label);
    }

    @Test
    void unallocatedOpcodesAreRefusedNotMisdecoded() {
        for (int opcode : new int[] {0x02, 0x04, 0x05, 0x06, 0x07, 0x0E, 0x0F, 0x11, 0x1C, 0x1D, 0x1E, 0x1F}) {
            assertRefused(binaryWord(2, opcode), "binária " + Integer.toHexString(opcode));
        }
        for (int opcode : new int[] {0x02, 0x05, 0x08, 0x09, 0x0A, 0x0B, 0x0E}) { // buracos entre os imediatos
            assertRefused(immediateWord(opcode, (16 << 2) - 1), "shift imediato " + Integer.toHexString(opcode));
        }
        for (int opcode : new int[] {0x12, 0x16, 0x1A, 0x1C, 0x1D, 0x1E, 0x1F}) { // buracos entre os shifts por vetor
            assertRefused(shiftWord(0, opcode), "shift vetor " + Integer.toHexString(opcode));
        }
        assertRefused(unaryWord(3, UNARY_MERGING | UNARY_BIT_OPS | 0x07), "bit-op com seletor 7 (_m)");
        assertRefused(unaryWord(3, UNARY_BIT_OPS | 0x07), "bit-op com seletor 7 (_z)");
        // bit 21 = 1 e nenhuma linha da B17.5: cai no grupo predicado, que só aceita bit 21 = 0
        assertRefused(0x04a07c61, "bit 21 = 1 fora do recorte");
        assertRefused(0x04200861 | (0x18 << 16), "bit 21 = 1 com 15:13 = 000");
    }

    /// O `decodetree` deixa passar estes, o tradutor do QEMU recusa: precisam de recusa explícita (G8).
    @Test
    void elementSizeRestrictionsAreExplicitRefusals() {
        for (int opcode : new int[] {0x14, 0x15, 0x16, 0x17}) {
            assertRefused(binaryWord(0, opcode), "divisão .b " + Integer.toHexString(opcode));
            assertRefused(binaryWord(1, opcode), "divisão .h " + Integer.toHexString(opcode));
            assertTrue(decodes(SVE2P2, binaryWord(2, opcode)), "divisão .s existe");
        }
        for (int opcode : new int[] {0x18, 0x19, 0x1B}) {
            assertRefused(shiftWord(3, opcode), "shift por elemento largo .d " + Integer.toHexString(opcode));
            assertTrue(decodes(SVE2P2, shiftWord(2, opcode)), "shift por elemento largo .s existe");
        }
        for (int selector : new int[] {0x04, 0x05}) { // fabs/fneg
            int opcode = UNARY_MERGING | UNARY_BIT_OPS | selector;
            assertRefused(unaryWord(0, opcode), "fabs/fneg .b " + selector);
            assertTrue(decodes(SVE2P2, unaryWord(1, opcode)), "fabs/fneg .h existe");
        }
    }

    @Test
    void extensionsNeedAnElementLargerThanTheirSource() {
        int[][] invalid = {
                {0x10, 0}, {0x11, 0}, // sxtb/uxtb .b
                {0x12, 0}, {0x12, 1}, {0x13, 0}, {0x13, 1}, // sxth/uxth .b/.h
                {0x14, 0}, {0x14, 1}, {0x14, 2}, {0x15, 0}, {0x15, 1}, {0x15, 2}, // sxtw/uxtw .b/.h/.s
        };
        for (int[] row : invalid) {
            int word = unaryWord(row[1], row[0]);
            assertThrows(UnsupportedOperationException.class, () -> decode(SVE2P2, word),
                    "opcode " + Integer.toHexString(row[0]) + " esz " + row[1]);
        }
    }

    @Test
    void immediateShiftsWithTszZeroAreRefused() {
        for (int opcode : new int[] {0x00, 0x01, 0x03, 0x04, 0x06, 0x07, 0x0C, 0x0D, 0x0F}) {
            int word = immediateWord(opcode, 0b0000101); // tsz = 0, imm3 != 0
            assertThrows(UnsupportedOperationException.class, () -> decode(SVE2, word), Integer.toHexString(opcode));
        }
    }

    @Test
    void unallocatedOpcodesOfTheReductionEncodingSpaceStayRefused() {
        // As 19 linhas desse espaço (`bits[15:13] = 001`) são da B17.7; o buraco `opcode = 0x02` continua recusado.
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE2P2, 0x04022061));
    }

    // ── Binárias: merging + oráculo ─────────────────────────────────────────────────────────────

    private record BinaryCase(String name, int opcode, boolean reverse, int minEsz) {
    }

    private static final BinaryCase[] BINARY_CASES = {
        new BinaryCase("orr", 0x18, false, 0), new BinaryCase("eor", 0x19, false, 0),
        new BinaryCase("and", 0x1A, false, 0), new BinaryCase("bic", 0x1B, false, 0),
        new BinaryCase("add", 0x00, false, 0), new BinaryCase("sub", 0x01, false, 0),
        new BinaryCase("subr", 0x03, true, 0),
        new BinaryCase("smax", 0x08, false, 0), new BinaryCase("umax", 0x09, false, 0),
        new BinaryCase("smin", 0x0A, false, 0), new BinaryCase("umin", 0x0B, false, 0),
        new BinaryCase("sabd", 0x0C, false, 0), new BinaryCase("uabd", 0x0D, false, 0),
        new BinaryCase("mul", 0x10, false, 0), new BinaryCase("smulh", 0x12, false, 0),
        new BinaryCase("umulh", 0x13, false, 0),
        new BinaryCase("sdiv", 0x14, false, 2), new BinaryCase("udiv", 0x15, false, 2),
        new BinaryCase("sdivr", 0x16, true, 2), new BinaryCase("udivr", 0x17, true, 2),
    };

    private static BigInteger binaryOracle(String name, BigInteger n, BigInteger m, int esz) {
        int width = bits(esz);
        BigInteger sn = signedOf(n, width);
        BigInteger sm = signedOf(m, width);
        return switch (name.replace("subr", "sub").replace("sdivr", "sdiv").replace("udivr", "udiv")) {
            case "orr" -> n.or(m);
            case "eor" -> n.xor(m);
            case "and" -> n.and(m);
            case "bic" -> n.andNot(m);
            case "add" -> n.add(m);
            case "sub" -> n.subtract(m);
            case "smax" -> sn.max(sm);
            case "umax" -> n.max(m);
            case "smin" -> sn.min(sm);
            case "umin" -> n.min(m);
            case "sabd" -> sn.subtract(sm).abs();
            case "uabd" -> n.subtract(m).abs();
            case "mul" -> n.multiply(m);
            case "smulh" -> sn.multiply(sm).shiftRight(width);
            case "umulh" -> n.multiply(m).shiftRight(width);
            case "sdiv" -> m.signum() == 0 ? BigInteger.ZERO : sn.divide(sm);
            case "udiv" -> m.signum() == 0 ? BigInteger.ZERO : n.divide(m);
            case "asr" -> sn.shiftRight(m.compareTo(BigInteger.valueOf(width)) >= 0 ? width - 1 : m.intValue());
            case "lsr" -> m.compareTo(BigInteger.valueOf(width)) >= 0 ? BigInteger.ZERO : n.shiftRight(m.intValue());
            case "lsl" -> m.compareTo(BigInteger.valueOf(width)) >= 0 ? BigInteger.ZERO : n.shiftLeft(m.intValue());
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Stream<Arguments> binaryArguments() {
        return Stream.of(BINARY_CASES).flatMap(c -> IntStream.rangeClosed(c.minEsz(), 3)
                .mapToObj(esz -> Arguments.of(c.name(), c.opcode(), c.reverse(), esz)));
    }

    @ParameterizedTest
    @MethodSource("binaryArguments")
    void binaryOperationsAreMergingAndMatchTheOracle(String name, int opcode, boolean reverse, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= 3; seed++) {
                check(SVE, binaryWord(esz, opcode), esz, vl, seed, null, null, false,
                        (d0, z3) -> reverse ? binaryOracle(name, z3, d0, esz) : binaryOracle(name, d0, z3, esz), name);
            }
        }
    }

    private record ShiftVectorCase(String name, int opcode, boolean reverse) {
    }

    private static final ShiftVectorCase[] SHIFT_VECTOR_CASES = {
        new ShiftVectorCase("asr", 0x10, false), new ShiftVectorCase("lsr", 0x11, false),
        new ShiftVectorCase("lsl", 0x13, false), new ShiftVectorCase("asrr", 0x14, true),
        new ShiftVectorCase("lsrr", 0x15, true), new ShiftVectorCase("lslr", 0x17, true),
    };

    static Stream<Arguments> shiftVectorArguments() {
        return Stream.of(SHIFT_VECTOR_CASES).flatMap(c -> IntStream.rangeClosed(0, 3)
                .mapToObj(esz -> Arguments.of(c.name(), c.opcode(), c.reverse(), esz)));
    }

    /// Todos os bits da quantidade contam (não módulo o elemento): valores gigantes preenchem/zeram.
    @ParameterizedTest
    @MethodSource("shiftVectorArguments")
    void shiftsByVectorUseTheWholeAmountAndAreMerging(String name, int opcode, boolean reverse, int esz) {
        String base = reverse ? name.substring(0, name.length() - 1) : name;
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= 3; seed++) {
                Aarch64Core probe = core(SVE, vl);
                long[] amounts = shiftAmounts(probe, esz, new Random(seed));
                long[] data = randomElements(probe, esz, new Random(seed + 100));
                // direta: Zdn = op(Zdn, Zm), quantidades em Zm; reversa: Zdn = op(Zm, Zdn), quantidades em Zdn
                check(SVE, shiftWord(esz, opcode), esz, vl, seed, reverse ? amounts : data, reverse ? data : amounts,
                        false, (d0, z3) -> reverse
                        ? binaryOracle(base, z3, d0, esz) : binaryOracle(base, d0, z3, esz), name);
            }
        }
    }

    @Test
    void reverseSubtractAndDivideUseAsymmetricOperands() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z1, 3, filledLongs(core, 3, 10L));
        setElements(core, Z3, 3, filledLongs(core, 3, 3L));
        run(SVE, core, binaryWord(3, 0x03)); // subr z1.d, p2/m, z1.d, z3.d: 3 - 10
        assertEquals(-7L, elements(core, Z1, 3)[0]);
        setElements(core, Z1, 3, filledLongs(core, 3, 3L));
        setElements(core, Z3, 3, filledLongs(core, 3, 21L));
        run(SVE, core, binaryWord(3, 0x16)); // sdivr: 21 / 3
        assertEquals(7L, elements(core, Z1, 3)[0]);
        setElements(core, Z1, 3, filledLongs(core, 3, 2L));
        setElements(core, Z3, 3, filledLongs(core, 3, 64L));
        run(SVE, core, shiftWord(3, 0x14)); // asrr: 64 >> 2
        assertEquals(16L, elements(core, Z1, 3)[0]);
        run(SVE, core, shiftWord(3, 0x17)); // lslr: Zm=64 << Zdn=16 = 64 << 16
        assertEquals(64L << 16, elements(core, Z1, 3)[0]);
    }

    private static long[] filledLongs(Aarch64Core core, int esz, long value) {
        long[] out = new long[elementCount(core, esz)];
        java.util.Arrays.fill(out, value);
        return out;
    }

    @Test
    void divisionCornerCasesFollowTheArchitecture() {
        for (int esz : new int[] {2, 3}) {
            Aarch64Core core = core(SVE, 256);
            allActive(core);
            long min = 1L << (bits(esz) - 1);
            long allOnes = mask(esz).longValue();
            long[] n = filledLongs(core, esz, min);
            long[] m = filledLongs(core, esz, allOnes); // -1
            m[1] = 0L; // divisão por zero
            setElements(core, Z1, esz, n);
            setElements(core, Z3, esz, m);
            run(SVE, core, binaryWord(esz, 0x14));
            long[] d = elements(core, Z1, esz);
            assertEquals(min, d[0], "INT_MIN / -1 = INT_MIN");
            assertEquals(0L, d[1], "x / 0 = 0");
            setElements(core, Z1, esz, filledLongs(core, esz, allOnes));
            setElements(core, Z3, esz, m);
            run(SVE, core, binaryWord(esz, 0x15));
            d = elements(core, Z1, esz);
            assertEquals(1L, d[0], "udiv: max / max = 1");
            assertEquals(0L, d[1], "udiv por zero = 0");
        }
    }

    // ── Shifts por imediato ──────────────────────────────────────────────────────────────────────

    private record ImmediateCase(String name, int opcode, boolean left, boolean sve2Only) {
    }

    private static final ImmediateCase[] IMMEDIATE_CASES = {
        new ImmediateCase("asr", 0x00, false, false), new ImmediateCase("lsr", 0x01, false, false),
        new ImmediateCase("lsl", 0x03, true, false), new ImmediateCase("asrd", 0x04, false, false),
        new ImmediateCase("sqshl", 0x06, true, true), new ImmediateCase("uqshl", 0x07, true, true),
        new ImmediateCase("srshr", 0x0C, false, true), new ImmediateCase("urshr", 0x0D, false, true),
        new ImmediateCase("sqshlu", 0x0F, true, true),
    };

    private static BigInteger clamp(BigInteger value, BigInteger min, BigInteger max) {
        return value.max(min).min(max);
    }

    private static BigInteger immediateOracle(String name, BigInteger n, int amount, int esz) {
        int width = bits(esz);
        BigInteger sn = signedOf(n, width);
        BigInteger signedMax = BigInteger.ONE.shiftLeft(width - 1).subtract(BigInteger.ONE);
        BigInteger signedMin = signedMax.negate().subtract(BigInteger.ONE);
        BigInteger half = amount == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(amount - 1);
        return switch (name) {
            case "asr" -> sn.shiftRight(amount);
            case "lsr" -> n.shiftRight(amount);
            case "lsl" -> n.shiftLeft(amount);
            case "asrd" -> sn.divide(BigInteger.ONE.shiftLeft(amount)); // trunca para zero, ≠ ASR
            case "srshr" -> sn.add(half).shiftRight(amount);
            case "urshr" -> n.add(half).shiftRight(amount);
            case "sqshl" -> clamp(sn.shiftLeft(amount), signedMin, signedMax);
            case "uqshl" -> clamp(n.shiftLeft(amount), BigInteger.ZERO, mask(esz));
            case "sqshlu" -> clamp(sn.shiftLeft(amount), BigInteger.ZERO, mask(esz));
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Stream<Arguments> immediateArguments() {
        return Stream.of(IMMEDIATE_CASES).map(c -> Arguments.of(c.name(), c.opcode(), c.left()));
    }

    /// Varre TODAS as quantidades válidas de cada `esz` (direita `1..esize`, esquerda `0..esize-1`) — inclui o
    /// shift por `esize` (`ASR` clampa, `LSR`/`ASRD`/`URSHR` mudam de comportamento ali).
    @ParameterizedTest
    @MethodSource("immediateArguments")
    void immediateShiftsMatchTheOracleForEveryAmount(String name, int opcode, boolean left) {
        for (int esz = 0; esz <= 3; esz++) {
            int first = left ? 0 : 1;
            int last = left ? bits(esz) - 1 : bits(esz);
            for (int amount = first; amount <= last; amount++) {
                int tszimm = left ? (8 << esz) + amount : (16 << esz) - amount;
                int vl = VECTOR_LENGTHS[amount % 2];
                int width = esz;
                int shift = amount;
                check(SVE2, immediateWord(opcode, tszimm), esz, vl, amount + 1, null, null, false,
                        (d0, z3) -> immediateOracle(name, d0, shift, width), name + " #" + amount);
            }
        }
    }

    @Test
    void asrdRoundsTowardZeroWhileAsrRoundsTowardMinusInfinity() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z1, 2, filledLongs(core, 2, mask(2).longValue() - 6L)); // -7 (.s)
        run(SVE, core, immediateWord(0x04, (16 << 2) - 1)); // asrd z1.s, p2/m, z1.s, #1
        assertEquals(truncate(BigInteger.valueOf(-3), 2), elements(core, Z1, 2)[0], "asrd -7 >> 1 = -3");
        setElements(core, Z1, 2, filledLongs(core, 2, mask(2).longValue() - 6L));
        run(SVE, core, immediateWord(0x00, (16 << 2) - 1)); // asr z1.s, p2/m, z1.s, #1
        assertEquals(truncate(BigInteger.valueOf(-4), 2), elements(core, Z1, 2)[0], "asr -7 >> 1 = -4");
    }

    // ── Shifts por elemento largo ────────────────────────────────────────────────────────────────

    static Stream<Arguments> wideArguments() {
        return Stream.of(Arguments.of("asr", 0x18), Arguments.of("lsr", 0x19), Arguments.of("lsl", 0x1B))
                .flatMap(a -> IntStream.rangeClosed(0, 2).mapToObj(esz -> Arguments.of(a.get()[0], a.get()[1], esz)));
    }

    /// `Zm` é lido em doublewords: a quantidade de cada elemento é o doubleword que o CONTÉM — e um
    /// doubleword com o bit 63 ligado é um shift enorme (sem sinal), não um negativo.
    @ParameterizedTest
    @MethodSource("wideArguments")
    void wideShiftsTakeTheContainingDoublewordAsAnUnsignedAmount(String name, int opcode, int esz) {
        for (int vl : VECTOR_LENGTHS) {
            Aarch64Core core = core(SVE, vl);
            Random random = new Random(esz * 13L + vl);
            setElements(core, Z1, esz, randomElements(core, esz, random));
            long[] amounts = shiftAmounts(core, 3, random);
            amounts[0] = -1L; // bit 63 ligado
            setElements(core, Z3, 3, amounts);
            boolean[] active = randomPredicate(core, esz, random);
            long[] d0 = elements(core, Z1, esz);
            run(SVE, core, shiftWord(esz, opcode));
            long[] d = elements(core, Z1, esz);
            int perDoubleword = 8 >> esz;
            for (int e = 0; e < d.length; e++) {
                BigInteger amount = new BigInteger(Long.toUnsignedString(amounts[e / perDoubleword]));
                long expected = active[e]
                        ? truncate(binaryOracle(name, unsigned(d0[e], esz), amount, esz), esz)
                        : d0[e];
                assertEquals(expected, d[e], name + " wide esz=" + esz + " vl=" + vl + " e=" + e);
            }
        }
    }

    @Test
    void wideShiftReadsZmBeforeWritingEvenWhenZmIsTheDestination() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        long[] words = {0x0102030405060708L, 3L, 0xFFFFFFFFFFFFFFFFL, 0x1L};
        for (int w = 0; w < 4; w++) {
            core.scalable().setZWord(Z1, w, words[w]);
        }
        run(SVE, core, 0x04188821); // asr z1.b, p2/m, z1.b, z1.d  (Zm = Zdn = z1)
        for (int w = 0; w < 4; w++) {
            long expected = 0L;
            for (int b = 0; b < 8; b++) {
                long element = (words[w] >> (b * 8)) & 0xFF;
                BigInteger amount = new BigInteger(Long.toUnsignedString(words[w]));
                BigInteger shifted = signedOf(BigInteger.valueOf(element), 8)
                        .shiftRight(amount.compareTo(BigInteger.valueOf(8)) >= 0 ? 7 : amount.intValue());
                expected |= (shifted.longValue() & 0xFF) << (b * 8);
            }
            assertEquals(expected, core.scalable().zWord(Z1, w), "doubleword " + w);
        }
    }

    // ── Unárias ──────────────────────────────────────────────────────────────────────────────────

    private record UnaryCase(String name, int selector, boolean bitOperation, int minEsz, boolean wordOnly) {
    }

    private static final UnaryCase[] UNARY_CASES = {
        new UnaryCase("cls", 0, true, 0, false), new UnaryCase("clz", 1, true, 0, false),
        new UnaryCase("cnt", 2, true, 0, false), new UnaryCase("cnot", 3, true, 0, false),
        new UnaryCase("fabs", 4, true, 1, false), new UnaryCase("fneg", 5, true, 1, false),
        new UnaryCase("not", 6, true, 0, false),
        new UnaryCase("sxtb", 0, false, 1, false), new UnaryCase("uxtb", 1, false, 1, false),
        new UnaryCase("sxth", 2, false, 2, false), new UnaryCase("uxth", 3, false, 2, false),
        new UnaryCase("sxtw", 4, false, 3, false), new UnaryCase("uxtw", 5, false, 3, false),
        new UnaryCase("abs", 6, false, 0, false), new UnaryCase("neg", 7, false, 0, false),
    };

    private static BigInteger unaryOracle(String name, BigInteger n, int esz) {
        int width = bits(esz);
        BigInteger sn = signedOf(n, width);
        return switch (name) {
            case "cls" -> {
                boolean sign = n.testBit(width - 1);
                int count = 0;
                for (int i = width - 2; i >= 0 && n.testBit(i) == sign; i--) {
                    count++;
                }
                yield BigInteger.valueOf(count);
            }
            case "clz" -> BigInteger.valueOf(width - n.bitLength());
            case "cnt" -> BigInteger.valueOf(n.bitCount());
            case "cnot" -> n.signum() == 0 ? BigInteger.ONE : BigInteger.ZERO;
            case "not" -> n.not();
            case "fabs" -> n.clearBit(width - 1);
            case "fneg" -> n.flipBit(width - 1);
            case "abs" -> sn.abs();
            case "neg" -> sn.negate();
            case "sxtb" -> signedOf(n.and(BigInteger.valueOf(0xFF)), 8);
            case "uxtb" -> n.and(BigInteger.valueOf(0xFF));
            case "sxth" -> signedOf(n.and(BigInteger.valueOf(0xFFFF)), 16);
            case "uxth" -> n.and(BigInteger.valueOf(0xFFFF));
            case "sxtw" -> signedOf(n.and(BigInteger.valueOf(0xFFFFFFFFL)), 32);
            case "uxtw" -> n.and(BigInteger.valueOf(0xFFFFFFFFL));
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Stream<Arguments> unaryArguments() {
        return Stream.of(UNARY_CASES).flatMap(c -> IntStream.rangeClosed(c.minEsz(), 3).boxed()
                .flatMap(esz -> Stream.of(true, false).map(merging -> Arguments.of(c.name(), c.selector(),
                        c.bitOperation(), esz, merging))));
    }

    /// `_m` (merging, SVE) preserva o elemento inativo; `_z` (zeroing, SVE2p2) o zera — a diferença é observável.
    @ParameterizedTest
    @MethodSource("unaryArguments")
    void unaryOperationsMatchTheOracleAndDifferOnInactiveElements(String name, int selector, boolean bitOperation,
            int esz, boolean merging) {
        int opcode = (merging ? UNARY_MERGING : 0) | (bitOperation ? UNARY_BIT_OPS : 0) | selector;
        for (int vl : VECTOR_LENGTHS) {
            for (long seed = 1; seed <= 3; seed++) {
                check(SVE2P2, unaryWord(esz, opcode), esz, vl, seed, null, null, !merging,
                        (d0, z3) -> unaryOracle(name, z3, esz), name + (merging ? "_m" : "_z"));
            }
        }
    }

    @Test
    void unaryCornerValuesAtEachElementSize() {
        for (int esz = 0; esz <= 3; esz++) {
            Aarch64Core core = core(SVE, 256);
            allActive(core);
            long min = 1L << (bits(esz) - 1);
            long[] values = filledLongs(core, esz, min);
            values[1] = 0L;
            values[2] = mask(esz).longValue(); // -1
            setElements(core, Z3, esz, values);
            run(SVE, core, unaryWord(esz, UNARY_MERGING | UNARY_BIT_OPS)); // cls
            long[] cls = elements(core, Z1, esz);
            assertEquals(0L, cls[0], "cls(MIN) = 0");
            assertEquals(bits(esz) - 1, cls[1], "cls(0) = esize-1");
            assertEquals(bits(esz) - 1, cls[2], "cls(-1) = esize-1");
            run(SVE, core, unaryWord(esz, UNARY_MERGING | 0x06)); // abs
            assertEquals(min, elements(core, Z1, esz)[0], "abs(MIN) = MIN (não satura)");
            run(SVE, core, unaryWord(esz, UNARY_MERGING | 0x07)); // neg
            assertEquals(min, elements(core, Z1, esz)[0], "neg(MIN) = MIN");
        }
    }

    @Test
    void fabsAndFnegOnlyTouchTheSignBitOfHalfSingleAndDouble() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        setElements(core, Z3, 2, filledLongs(core, 2, 0xFFC00001L)); // NaN negativa: os bits de NaN passam intactos
        run(SVE, core, unaryWord(2, UNARY_MERGING | UNARY_BIT_OPS | 0x04));
        assertEquals(0x7FC00001L, elements(core, Z1, 2)[0]);
        setElements(core, Z3, 2, filledLongs(core, 2, 0x7FC00001L));
        run(SVE, core, unaryWord(2, UNARY_MERGING | UNARY_BIT_OPS | 0x05));
        assertEquals(0xFFC00001L, elements(core, Z1, 2)[0], "fneg liga o sinal e não mexe nos demais bits (nem em NaN)");
    }

    // ── Vetor efetivo, acesso, streaming e blocos ────────────────────────────────────────────────

    @Test
    void operationsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(SVE, 1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // LEN=1 => VL efetivo 256
        allActive(core);
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(Z1, w, 0xDEAD_BEEFL);
            core.scalable().setZWord(Z3, w, 1L);
        }
        run(SVE, core, binaryWord(3, 0x00)); // add z1.d, p2/m, z1.d, z3.d
        for (int w = 0; w < 4; w++) {
            assertEquals(0xDEAD_BEF0L, core.scalable().zWord(Z1, w));
        }
        assertEquals(0xDEAD_BEEFL, core.scalable().zWord(Z1, 4), "nada acima do VL efetivo é escrito");
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
    @ValueSource(ints = {0x04000861, 0x04108861, 0x040089e1, 0x04188861, 0x0418a861, 0x04d4a861})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(SVE2, 256);
        core.setSystemRegisterBus(new Cpacr());
        setElements(core, Z1, 3, filledLongs(core, 3, 0x1234L));
        run(SVE2, core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, elements(core, Z1, 3)[0], "a instrução não executou");
    }

    @Test
    void predicatedIntegerOperationsAreLegalInStreamingModeAtTheStreamingVectorLength() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        allActive(core);
        for (int w = 0; w < 8; w++) {
            core.scalable().setZWord(Z1, w, 5L);
            core.scalable().setZWord(Z3, w, 2L);
        }
        core.setProgramCounter(0x10);
        core.memory().write32(0x10, binaryWord(3, 0x10)); // mul z1.d, p2/m, z1.d, z3.d
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc(), "sem exceção");
        for (int w = 0; w < 8; w++) {
            assertEquals(10L, core.scalable().zWord(Z1, w), "SVL = 512: 8 doublewords");
        }
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(SVE, 256);
        allActive(core);
        for (int w = 0; w < 4; w++) {
            core.scalable().setZWord(Z1, w, 100L);
            core.scalable().setZWord(Z3, w, 7L);
        }
        core.memory().write32(0, binaryWord(3, 0x03)); // subr: 7 - 100
        core.memory().write32(4, unaryWord(3, UNARY_MERGING | 0x06)); // abs z1.d, p2/m, z3.d
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(core.memory(), 0, 2);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertEquals(8L, core.pc());
        assertEquals(7L, core.scalable().zWord(Z1, 0), "abs(z3) = 7 sobrescreve o resultado do subr");
    }
}
