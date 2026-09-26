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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B17.5 — inteiro SVE sem predicado governante (aritmética, lógica, shifts, ternárias SVE2, `MLA`/`MLS`/
/// `MAD`/`MSB`, `MOVPRFX`, `FEXPA`/`FTSSEL`, `INDEX`). Palavras conferidas contra `aarch64-none-elf-as`
/// (devkitA64, `-march=armv9.4-a+sve2+sve2p1`); registradores fixos: `Zd=z1`, `Zn=z2`, `Zm=z3` (nas ternárias
/// `Zm=z3`, `Zk=z4`; em `MLA`: `Zn=z3`, `Zm=z4`, `pg=p2`). O oráculo é escrito com `BigInteger`, sem repetir
/// a aritmética do executor. Toda semântica roda em `VL = 256` **e** `VL = 512`.
class Aarch64SveIntegerTest {
    private static final int ADD_B = 0x04230041;
    private static final int ADD_H = 0x04630041;
    private static final int ADD_S = 0x04a30041;
    private static final int ADD_D = 0x04e30041;
    private static final int SUB_B = 0x04230441;
    private static final int SUB_H = 0x04630441;
    private static final int SUB_S = 0x04a30441;
    private static final int SUB_D = 0x04e30441;
    private static final int SQADD_B = 0x04231041;
    private static final int SQADD_H = 0x04631041;
    private static final int SQADD_S = 0x04a31041;
    private static final int SQADD_D = 0x04e31041;
    private static final int UQADD_B = 0x04231441;
    private static final int UQADD_H = 0x04631441;
    private static final int UQADD_S = 0x04a31441;
    private static final int UQADD_D = 0x04e31441;
    private static final int SQSUB_B = 0x04231841;
    private static final int SQSUB_H = 0x04631841;
    private static final int SQSUB_S = 0x04a31841;
    private static final int SQSUB_D = 0x04e31841;
    private static final int UQSUB_B = 0x04231c41;
    private static final int UQSUB_H = 0x04631c41;
    private static final int UQSUB_S = 0x04a31c41;
    private static final int UQSUB_D = 0x04e31c41;
    private static final int AND_Z = 0x04233041;
    private static final int ORR_Z = 0x04633041;
    private static final int EOR_Z = 0x04a33041;
    private static final int BIC_Z = 0x04e33041;
    private static final int XAR_B_1 = 0x042f3461;
    private static final int XAR_B_8 = 0x04283461;
    private static final int XAR_H_3 = 0x043d3461;
    private static final int XAR_S_17 = 0x046f3461;
    private static final int XAR_D_33 = 0x04bf3461;
    private static final int EOR3 = 0x04233881;
    private static final int BCAX = 0x04633881;
    private static final int BSL = 0x04233c81;
    private static final int BSL1N = 0x04633c81;
    private static final int BSL2N = 0x04a33c81;
    private static final int NBSL = 0x04e33c81;
    private static final int ASR_B_1 = 0x042f9041;
    private static final int ASR_B_8 = 0x04289041;
    private static final int ASR_H_5 = 0x043b9041;
    private static final int ASR_S_32 = 0x04609041;
    private static final int ASR_D_64 = 0x04a09041;
    private static final int LSR_B_3 = 0x042d9441;
    private static final int LSR_H_16 = 0x04309441;
    private static final int LSR_S_7 = 0x04799441;
    private static final int LSR_D_40 = 0x04b89441;
    private static final int LSL_B_7 = 0x042f9c41;
    private static final int LSL_H_9 = 0x04399c41;
    private static final int LSL_S_31 = 0x047f9c41;
    private static final int LSL_D_63 = 0x04ff9c41;
    private static final int ASR_WIDE_B = 0x04238041;
    private static final int ASR_WIDE_H = 0x04638041;
    private static final int ASR_WIDE_S = 0x04a38041;
    private static final int LSR_WIDE_B = 0x04238441;
    private static final int LSR_WIDE_S = 0x04a38441;
    private static final int LSL_WIDE_H = 0x04638c41;
    private static final int LSL_WIDE_S = 0x04a38c41;
    private static final int MLA_B = 0x04044861;
    private static final int MLA_H = 0x04444861;
    private static final int MLA_S = 0x04844861;
    private static final int MLA_D = 0x04c44861;
    private static final int MLS_S = 0x04846861;
    private static final int MLS_D = 0x04c46861;
    private static final int MAD_S = 0x0484c861;
    private static final int MAD_D = 0x04c4c861;
    private static final int MSB_H = 0x0444e861;
    private static final int MSB_D = 0x04c4e861;
    private static final int MOVPRFX = 0x0420bc61;
    private static final int FEXPA_H = 0x0460b861;
    private static final int FEXPA_S = 0x04a0b861;
    private static final int FEXPA_D = 0x04e0b861;
    private static final int FTSSEL_H = 0x0464b061;
    private static final int FTSSEL_S = 0x04a4b061;
    private static final int FTSSEL_D = 0x04e4b061;
    private static final int INDEX_II_B = 0x042f4201; // index z1.b, #-16, #15
    private static final int INDEX_II_H = 0x047e4061; // index z1.h, #3, #-2
    private static final int INDEX_II_S = 0x04a143e1; // index z1.s, #-1, #1
    private static final int INDEX_II_D = 0x04f041e1; // index z1.d, #15, #-16
    private static final int INDEX_IR_B = 0x042448a1; // index z1.b, #5, w4
    private static final int INDEX_IR_D = 0x04e44ba1; // index z1.d, #-3, x4
    private static final int INDEX_RI_S = 0x04a74461; // index z1.s, w3, #7
    private static final int INDEX_RI_D = 0x04f84461; // index z1.d, x3, #-8
    private static final int INDEX_RR_B = 0x04244c61;
    private static final int INDEX_RR_H = 0x04644c61;
    private static final int INDEX_RR_S = 0x04a44c61;
    private static final int INDEX_RR_D = 0x04e44c61;
    private static final int INDEX_RR_XZR = 0x04e44fe1; // index z1.d, xzr, x4

    private static final int Z1 = 1;
    private static final int Z2 = 2;
    private static final int Z3 = 3;
    private static final int Z4 = 4;
    private static final int P2 = 2;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SVE = 0x19L;
    private static final long ESR_EC_UNKNOWN = 0x00L;
    private static final int SMSTART_SM = 0xd503437f;
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2",
            Aarch64Feature.SVE2);

    // ── Infra ────────────────────────────────────────────────────────────────────────────────────

    private static Aarch64Core core(Aarch64Architecture architecture, int vl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static Aarch64Core core(int vl) {
        return core(SVE2, vl);
    }

    private static void run(Aarch64Core core, int... words) {
        run(SVE2, core, words);
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

    private static BigInteger signed(long value, int esz) {
        BigInteger u = unsigned(value, esz);
        return u.testBit(bits(esz) - 1) ? u.subtract(BigInteger.ONE.shiftLeft(bits(esz))) : u;
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

    private static void fillRandom(Aarch64Core core, int esz, long seed, int... registers) {
        Random random = new Random(seed);
        for (int reg : registers) {
            setElements(core, reg, esz, randomElements(core, esz, random));
        }
    }

    private static long[] allZ(Aarch64Core core, int reg) {
        return elements(core, reg, 3);
    }

    // ── Decoder ──────────────────────────────────────────────────────────────────────────────────

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        AddressSpace64 memory = AddressSpace64.wrapping(new TestAddressSpace(0x100));
        memory.write32(0, word);
        return new Aarch64Decoder(architecture).decode(memory, 0);
    }

    static Stream<Arguments> decodeCases() {
        return Stream.of(
                Arguments.of(ADD_B, Ir64Op.SveIntegerUnpredicated.Op.ADD), Arguments.of(SUB_D, Ir64Op.SveIntegerUnpredicated.Op.SUB),
                Arguments.of(SQADD_B, Ir64Op.SveIntegerUnpredicated.Op.SQADD),
                Arguments.of(UQADD_H, Ir64Op.SveIntegerUnpredicated.Op.UQADD),
                Arguments.of(SQSUB_B, Ir64Op.SveIntegerUnpredicated.Op.SQSUB),
                Arguments.of(UQSUB_S, Ir64Op.SveIntegerUnpredicated.Op.UQSUB),
                Arguments.of(AND_Z, Ir64Op.SveIntegerUnpredicated.Op.AND),
                Arguments.of(ORR_Z, Ir64Op.SveIntegerUnpredicated.Op.ORR),
                Arguments.of(EOR_Z, Ir64Op.SveIntegerUnpredicated.Op.EOR),
                Arguments.of(BIC_Z, Ir64Op.SveIntegerUnpredicated.Op.BIC),
                Arguments.of(XAR_B_1, Ir64Op.SveIntegerUnpredicated.Op.XAR),
                Arguments.of(EOR3, Ir64Op.SveIntegerUnpredicated.Op.EOR3),
                Arguments.of(BCAX, Ir64Op.SveIntegerUnpredicated.Op.BCAX),
                Arguments.of(BSL, Ir64Op.SveIntegerUnpredicated.Op.BSL),
                Arguments.of(BSL1N, Ir64Op.SveIntegerUnpredicated.Op.BSL1N),
                Arguments.of(BSL2N, Ir64Op.SveIntegerUnpredicated.Op.BSL2N),
                Arguments.of(NBSL, Ir64Op.SveIntegerUnpredicated.Op.NBSL),
                Arguments.of(ASR_B_1, Ir64Op.SveIntegerUnpredicated.Op.ASR_IMM),
                Arguments.of(LSR_B_3, Ir64Op.SveIntegerUnpredicated.Op.LSR_IMM),
                Arguments.of(LSL_B_7, Ir64Op.SveIntegerUnpredicated.Op.LSL_IMM),
                Arguments.of(ASR_WIDE_B, Ir64Op.SveIntegerUnpredicated.Op.ASR_WIDE),
                Arguments.of(LSR_WIDE_B, Ir64Op.SveIntegerUnpredicated.Op.LSR_WIDE),
                Arguments.of(LSL_WIDE_H, Ir64Op.SveIntegerUnpredicated.Op.LSL_WIDE),
                Arguments.of(MLA_B, Ir64Op.SveIntegerUnpredicated.Op.MLA),
                Arguments.of(MLS_S, Ir64Op.SveIntegerUnpredicated.Op.MLS),
                Arguments.of(MAD_S, Ir64Op.SveIntegerUnpredicated.Op.MAD),
                Arguments.of(MSB_H, Ir64Op.SveIntegerUnpredicated.Op.MSB),
                Arguments.of(MOVPRFX, Ir64Op.SveIntegerUnpredicated.Op.MOVPRFX),
                Arguments.of(FEXPA_H, Ir64Op.SveIntegerUnpredicated.Op.FEXPA),
                Arguments.of(FTSSEL_D, Ir64Op.SveIntegerUnpredicated.Op.FTSSEL),
                Arguments.of(INDEX_II_B, Ir64Op.SveIntegerUnpredicated.Op.INDEX_II),
                Arguments.of(INDEX_IR_B, Ir64Op.SveIntegerUnpredicated.Op.INDEX_IR),
                Arguments.of(INDEX_RI_S, Ir64Op.SveIntegerUnpredicated.Op.INDEX_RI),
                Arguments.of(INDEX_RR_D, Ir64Op.SveIntegerUnpredicated.Op.INDEX_RR));
    }

    @ParameterizedTest
    @MethodSource("decodeCases")
    void wordsDecodeToTheirOperationUnderSve2(int word, Ir64Op.SveIntegerUnpredicated.Op expected) {
        Ir64Op op = decode(SVE2, word);
        assertInstanceOf(Ir64Op.SveIntegerUnpredicated.class, op);
        assertEquals(expected, ((Ir64Op.SveIntegerUnpredicated) op).op());
    }

    @Test
    void thirtyFourEncodingsOfTheSliceAreAllReachable() {
        int[] words = {ADD_B, SUB_B, SQADD_B, UQADD_B, SQSUB_B, UQSUB_B, AND_Z, ORR_Z, EOR_Z, BIC_Z, XAR_B_1, EOR3,
                BSL, BCAX, BSL1N, BSL2N, NBSL, ASR_B_1, LSR_B_3, LSL_B_7, ASR_WIDE_B, LSR_WIDE_B, LSL_WIDE_H, MLA_B,
                MLS_S, MAD_S, MSB_H, MOVPRFX, FEXPA_H, FTSSEL_H, INDEX_II_B, INDEX_IR_B, INDEX_RI_S, INDEX_RR_B};
        java.util.Set<Ir64Op.SveIntegerUnpredicated.Op> seen = new java.util.HashSet<>();
        for (int word : words) {
            seen.add(((Ir64Op.SveIntegerUnpredicated) decode(SVE2, word)).op());
        }
        assertEquals(Ir64Op.SveIntegerUnpredicated.Op.values().length, seen.size());
        assertEquals(34, seen.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {XAR_B_1, EOR3, BCAX, BSL, BSL1N, BSL2N, NBSL})
    void theSevenTernaryAndRotateOperationsNeedFeatSve2(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE, word));
        assertInstanceOf(Ir64Op.SveIntegerUnpredicated.class, decode(SVE2, word));
    }

    @ParameterizedTest
    @ValueSource(ints = {ADD_B, AND_Z, ASR_B_1, ASR_WIDE_B, MLA_B, MOVPRFX, FEXPA_H, FTSSEL_H, INDEX_II_B})
    void baseSveOperationsDecodeWithSveAloneAndAreRefusedWithoutIt(int word) {
        assertInstanceOf(Ir64Op.SveIntegerUnpredicated.class, decode(SVE, word));
        assertThrows(UnsupportedOperationException.class, () -> decode(Aarch64Architecture.ARMV8_5_A, word));
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0x04e38041, // asr z1.d, z2.d, z3.d: `_zzw` com esz=3 não existe
            0x0420b861, // fexpa com esz=0
            0x0424b061, // ftssel com esz=0
            0x04209041, // asr por imediato com tsz=0
            0x04209441, // lsr por imediato com tsz=0
            0x04209c41, // lsl por imediato com tsz=0
            0x04203461, // xar com tsz=0
            0x04230841, // opcode 000010: não alocado
            0x04230c41, // opcode 000011: não alocado
            0x04238841, // opcode 100010 (buraco entre os `_zzw`)
            0x04239841, // opcode 100110 (buraco entre os imediatos)
            0x04a33881, // EOR3/BCAX com seletor 10: não alocado
            0x04e33881, // EOR3/BCAX com seletor 11: não alocado
            0x0460bc61, // movprfx tem esz=00 fixo
            0x0421bc61, // movprfx tem rm=00000 fixo
            0x04205061, // opcode 010100: não alocado
            0x04a07c61, // opcode 011111: não alocado (fora do recorte)
            0x04002061, // bit 21 = 0, 15:13 = 001: reduções (outra task), ainda recusadas
            0x04000061, // bit 21 = 0, 15:13 = 000: aritmética predicada (B17.6), ainda recusada
    })
    void unallocatedEncodingsAreRefusedNotMisdecoded(int word) {
        assertThrows(UnsupportedOperationException.class, () -> decode(SVE2, word));
    }

    @Test
    void multiplyAddDecodesTheAccumulatorAndMultiplicandForms() {
        Ir64Op.SveIntegerUnpredicated mla = (Ir64Op.SveIntegerUnpredicated) decode(SVE2, MLA_S);
        assertEquals(Z1, mla.rd());
        assertEquals(Z3, mla.rn());
        assertEquals(Z4, mla.rm());
        assertEquals(P2, mla.pg());
        Ir64Op.SveIntegerUnpredicated mad = (Ir64Op.SveIntegerUnpredicated) decode(SVE2, MAD_S);
        assertEquals(Z1, mad.rd());
        assertEquals(Z1, mad.rn(), "MAD escreve o multiplicando: Zdn é a fonte");
        assertEquals(Z4, mad.rm());
        assertEquals(Z3, mad.ra());
    }

    @Test
    void immediateShiftsDeriveEszAndAmountFromTheSameTszimmField() {
        record Expected(int word, int esz, long amount) {
        }
        for (Expected e : new Expected[] {new Expected(ASR_B_1, 0, 1), new Expected(ASR_B_8, 0, 8),
                new Expected(ASR_H_5, 1, 5), new Expected(ASR_S_32, 2, 32), new Expected(ASR_D_64, 3, 64),
                new Expected(LSR_B_3, 0, 3), new Expected(LSR_H_16, 1, 16), new Expected(LSR_S_7, 2, 7),
                new Expected(LSR_D_40, 3, 40), new Expected(LSL_B_7, 0, 7), new Expected(LSL_H_9, 1, 9),
                new Expected(LSL_S_31, 2, 31), new Expected(LSL_D_63, 3, 63)}) {
            Ir64Op.SveIntegerUnpredicated op = (Ir64Op.SveIntegerUnpredicated) decode(SVE2, e.word());
            assertEquals(e.esz(), op.esz(), Integer.toHexString(e.word()));
            assertEquals(e.amount(), op.imm(), Integer.toHexString(e.word()));
        }
    }

    @Test
    void xarDerivesEszAndRotationFromTszimmAndNeedsNoSizeField() {
        record Expected(int word, int esz, long amount) {
        }
        for (Expected e : new Expected[] {new Expected(XAR_B_1, 0, 1), new Expected(XAR_B_8, 0, 8),
                new Expected(XAR_H_3, 1, 3), new Expected(XAR_S_17, 2, 17), new Expected(XAR_D_33, 3, 33)}) {
            Ir64Op.SveIntegerUnpredicated op = (Ir64Op.SveIntegerUnpredicated) decode(SVE2, e.word());
            assertEquals(e.esz(), op.esz());
            assertEquals(e.amount(), op.imm());
            assertEquals(Z1, op.rd());
            assertEquals(Z3, op.rm(), "Zm em 9:5");
        }
    }

    // ── Aritmética ───────────────────────────────────────────────────────────────────────────────

    private enum Arithmetic { ADD, SUB, SQADD, UQADD, SQSUB, UQSUB }

    private static long arithmeticOracle(Arithmetic kind, long n, long m, int esz) {
        BigInteger min = BigInteger.ONE.shiftLeft(bits(esz) - 1).negate();
        BigInteger max = BigInteger.ONE.shiftLeft(bits(esz) - 1).subtract(BigInteger.ONE);
        BigInteger umax = mask(esz);
        return switch (kind) {
            case ADD -> truncate(unsigned(n, esz).add(unsigned(m, esz)), esz);
            case SUB -> truncate(unsigned(n, esz).subtract(unsigned(m, esz)), esz);
            case SQADD -> truncate(signed(n, esz).add(signed(m, esz)).max(min).min(max), esz);
            case SQSUB -> truncate(signed(n, esz).subtract(signed(m, esz)).max(min).min(max), esz);
            case UQADD -> truncate(unsigned(n, esz).add(unsigned(m, esz)).min(umax), esz);
            default -> truncate(unsigned(n, esz).subtract(unsigned(m, esz)).max(BigInteger.ZERO), esz);
        };
    }

    static Stream<Arguments> arithmeticCases() {
        return Stream.of(
                Arguments.of(ADD_B, Arithmetic.ADD, 0), Arguments.of(ADD_H, Arithmetic.ADD, 1),
                Arguments.of(ADD_S, Arithmetic.ADD, 2), Arguments.of(ADD_D, Arithmetic.ADD, 3),
                Arguments.of(SUB_B, Arithmetic.SUB, 0), Arguments.of(SUB_H, Arithmetic.SUB, 1),
                Arguments.of(SUB_S, Arithmetic.SUB, 2), Arguments.of(SUB_D, Arithmetic.SUB, 3),
                Arguments.of(SQADD_B, Arithmetic.SQADD, 0), Arguments.of(SQADD_H, Arithmetic.SQADD, 1),
                Arguments.of(SQADD_S, Arithmetic.SQADD, 2), Arguments.of(SQADD_D, Arithmetic.SQADD, 3),
                Arguments.of(UQADD_B, Arithmetic.UQADD, 0), Arguments.of(UQADD_H, Arithmetic.UQADD, 1),
                Arguments.of(UQADD_S, Arithmetic.UQADD, 2), Arguments.of(UQADD_D, Arithmetic.UQADD, 3),
                Arguments.of(SQSUB_B, Arithmetic.SQSUB, 0), Arguments.of(SQSUB_H, Arithmetic.SQSUB, 1),
                Arguments.of(SQSUB_S, Arithmetic.SQSUB, 2), Arguments.of(SQSUB_D, Arithmetic.SQSUB, 3),
                Arguments.of(UQSUB_B, Arithmetic.UQSUB, 0), Arguments.of(UQSUB_H, Arithmetic.UQSUB, 1),
                Arguments.of(UQSUB_S, Arithmetic.UQSUB, 2), Arguments.of(UQSUB_D, Arithmetic.UQSUB, 3));
    }

    @ParameterizedTest
    @MethodSource("arithmeticCases")
    void arithmeticMatchesTheBigIntegerOracleForEveryElementAtBothVectorLengths(int word, Arithmetic kind, int esz) {
        for (int vl : new int[] {256, 512}) {
            for (long seed = 0; seed < 6; seed++) {
                Aarch64Core core = core(vl);
                fillRandom(core, esz, seed, Z2, Z3);
                long[] n = elements(core, Z2, esz);
                long[] m = elements(core, Z3, esz);
                run(core, word);
                long[] d = elements(core, Z1, esz);
                assertEquals(vl / 8 >> esz, d.length);
                for (int i = 0; i < d.length; i++) {
                    assertEquals(arithmeticOracle(kind, n[i], m[i], esz), d[i],
                            kind + " esz=" + esz + " vl=" + vl + " i=" + i + " n=" + n[i] + " m=" + m[i]);
                }
            }
        }
    }

    @Test
    void saturatingOperationsHitTheExactLimits() {
        Aarch64Core core = core(256);
        setElements(core, Z2, 0, filled(core, 0, 127));
        setElements(core, Z3, 0, filled(core, 0, 1));
        run(core, SQADD_B);
        assertEquals(127L, elements(core, Z1, 0)[0], "127 + 1 satura em +127");
        setElements(core, Z2, 0, filled(core, 0, 0x80));
        run(core, SQSUB_B);
        assertEquals(0x80L, elements(core, Z1, 0)[0], "-128 - 1 satura em -128");
        setElements(core, Z2, 3, filled(core, 3, Long.MAX_VALUE));
        setElements(core, Z3, 3, filled(core, 3, 1));
        run(core, SQADD_D);
        assertEquals(Long.MAX_VALUE, elements(core, Z1, 3)[0]);
        setElements(core, Z2, 3, filled(core, 3, Long.MIN_VALUE));
        run(core, SQSUB_D);
        assertEquals(Long.MIN_VALUE, elements(core, Z1, 3)[0]);
        setElements(core, Z2, 3, filled(core, 3, -1L));
        run(core, UQADD_D);
        assertEquals(-1L, elements(core, Z1, 3)[0], "0xFFFF... + 1 satura em 2^64-1");
        setElements(core, Z2, 3, filled(core, 3, 0L));
        run(core, UQSUB_D);
        assertEquals(0L, elements(core, Z1, 3)[0], "0 - 1 satura em 0");
    }

    private static long[] filled(Aarch64Core core, int esz, long value) {
        long[] out = new long[elementCount(core, esz)];
        java.util.Arrays.fill(out, value);
        return out;
    }

    // ── Lógica ───────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void logicalOperationsAreBitwiseOverTheWholeVector(int vl) {
        Aarch64Core core = core(vl);
        fillRandom(core, 3, 7, Z2, Z3);
        long[] n = allZ(core, Z2);
        long[] m = allZ(core, Z3);
        int[] words = {AND_Z, ORR_Z, EOR_Z, BIC_Z};
        for (int op = 0; op < words.length; op++) {
            run(core, words[op]);
            long[] d = allZ(core, Z1);
            for (int i = 0; i < d.length; i++) {
                long expected = switch (op) {
                    case 0 -> n[i] & m[i];
                    case 1 -> n[i] | m[i];
                    case 2 -> n[i] ^ m[i];
                    default -> n[i] & ~m[i];
                };
                assertEquals(expected, d[i], "op " + op + " word " + i);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ternaryOperationsMatchTheArmPseudocode(int vl) {
        Aarch64Core core = core(vl);
        fillRandom(core, 3, 11, Z1, Z3, Z4);
        long[] n = allZ(core, Z1);
        long[] m = allZ(core, Z3);
        long[] k = allZ(core, Z4);
        int[] words = {EOR3, BCAX, BSL, BSL1N, BSL2N, NBSL};
        for (int op = 0; op < words.length; op++) {
            setElements(core, Z1, 3, n);
            run(core, words[op]);
            long[] d = allZ(core, Z1);
            for (int i = 0; i < d.length; i++) {
                long expected = switch (op) {
                    case 0 -> n[i] ^ m[i] ^ k[i];
                    case 1 -> n[i] ^ (m[i] & ~k[i]);
                    case 2 -> (n[i] & k[i]) | (m[i] & ~k[i]);
                    case 3 -> (~n[i] & k[i]) | (m[i] & ~k[i]);
                    case 4 -> (n[i] & k[i]) | (~m[i] & ~k[i]);
                    default -> ~((n[i] & k[i]) | (m[i] & ~k[i]));
                };
                assertEquals(expected, d[i], "op " + op + " word " + i);
            }
        }
    }

    @Test
    void bslSelectsBitsOfZdnWhereZkIsOneAndOfZmWhereItIsZero() {
        Aarch64Core core = core(256);
        setElements(core, Z1, 3, filled(core, 3, 0xAAAA_AAAA_AAAA_AAAAL));
        setElements(core, Z3, 3, filled(core, 3, 0x5555_5555_5555_5555L));
        setElements(core, Z4, 3, filled(core, 3, 0xFFFF_FFFF_0000_0000L));
        run(core, BSL);
        assertEquals(0xAAAA_AAAA_5555_5555L, allZ(core, Z1)[0]);
    }

    static Stream<Arguments> xarCases() {
        return Stream.of(Arguments.of(XAR_B_1, 0, 1), Arguments.of(XAR_B_8, 0, 8), Arguments.of(XAR_H_3, 1, 3),
                Arguments.of(XAR_S_17, 2, 17), Arguments.of(XAR_D_33, 3, 33));
    }

    @ParameterizedTest
    @MethodSource("xarCases")
    void xarRotatesTheXorRightWithinEachElement(int word, int esz, int amount) {
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(vl);
            fillRandom(core, esz, 5, Z1, Z3);
            long[] n = elements(core, Z1, esz);
            long[] m = elements(core, Z3, esz);
            run(core, word);
            long[] d = elements(core, Z1, esz);
            for (int i = 0; i < d.length; i++) {
                long x = n[i] ^ m[i];
                long expected = 0;
                for (int b = 0; b < bits(esz); b++) { // o bit b do resultado é o bit (b + amount) mod esize da entrada
                    expected |= ((x >>> ((b + amount) % bits(esz))) & 1L) << b;
                }
                assertEquals(expected, d[i], "esz=" + esz + " vl=" + vl + " i=" + i);
            }
        }
    }

    // ── Shifts ───────────────────────────────────────────────────────────────────────────────────

    private enum Shift { ASR, LSR, LSL }

    private static long shiftOracle(Shift kind, long value, BigInteger amountUnsigned, int esz) {
        int amount = amountUnsigned.min(BigInteger.valueOf(bits(esz) * 2L)).intValue();
        return switch (kind) {
            case ASR -> truncate(signed(value, esz).shiftRight(amount), esz);
            case LSR -> truncate(unsigned(value, esz).shiftRight(amount), esz);
            default -> truncate(unsigned(value, esz).shiftLeft(amount), esz);
        };
    }

    static Stream<Arguments> immediateShiftCases() {
        return Stream.of(
                Arguments.of(ASR_B_1, Shift.ASR, 0, 1), Arguments.of(ASR_B_8, Shift.ASR, 0, 8),
                Arguments.of(ASR_H_5, Shift.ASR, 1, 5), Arguments.of(ASR_S_32, Shift.ASR, 2, 32),
                Arguments.of(ASR_D_64, Shift.ASR, 3, 64),
                Arguments.of(LSR_B_3, Shift.LSR, 0, 3), Arguments.of(LSR_H_16, Shift.LSR, 1, 16),
                Arguments.of(LSR_S_7, Shift.LSR, 2, 7), Arguments.of(LSR_D_40, Shift.LSR, 3, 40),
                Arguments.of(LSL_B_7, Shift.LSL, 0, 7), Arguments.of(LSL_H_9, Shift.LSL, 1, 9),
                Arguments.of(LSL_S_31, Shift.LSL, 2, 31), Arguments.of(LSL_D_63, Shift.LSL, 3, 63));
    }

    @ParameterizedTest
    @MethodSource("immediateShiftCases")
    void immediateShiftsMatchTheOracleIncludingTheShiftByElementSize(int word, Shift kind, int esz, int amount) {
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(vl);
            fillRandom(core, esz, 3, Z2);
            long[] n = elements(core, Z2, esz);
            run(core, word);
            long[] d = elements(core, Z1, esz);
            for (int i = 0; i < d.length; i++) {
                assertEquals(shiftOracle(kind, n[i], BigInteger.valueOf(amount), esz), d[i],
                        kind + " esz=" + esz + " #" + amount + " i=" + i);
            }
        }
    }

    static Stream<Arguments> wideShiftCases() {
        return Stream.of(Arguments.of(ASR_WIDE_B, Shift.ASR, 0), Arguments.of(ASR_WIDE_H, Shift.ASR, 1),
                Arguments.of(ASR_WIDE_S, Shift.ASR, 2), Arguments.of(LSR_WIDE_B, Shift.LSR, 0),
                Arguments.of(LSR_WIDE_S, Shift.LSR, 2), Arguments.of(LSL_WIDE_H, Shift.LSL, 1),
                Arguments.of(LSL_WIDE_S, Shift.LSL, 2));
    }

    @ParameterizedTest
    @MethodSource("wideShiftCases")
    void wideShiftsUseTheDoublewordOfZmThatContainsEachElement(int word, Shift kind, int esz) {
        long[] amounts = {0L, 1L, 7L, 8L, 15L, 16L, 31L, 32L, 33L, 63L, 64L, 200L, -1L, Long.MIN_VALUE, 1L << 40};
        for (int vl : new int[] {256, 512}) {
            for (int round = 0; round + 4 <= amounts.length; round++) {
                Aarch64Core core = core(vl);
                fillRandom(core, esz, round, Z2);
                long[] wide = new long[vl / 64];
                for (int i = 0; i < wide.length; i++) {
                    wide[i] = amounts[(round + i) % amounts.length];
                }
                setElements(core, Z3, 3, wide);
                long[] n = elements(core, Z2, esz);
                run(core, word);
                long[] d = elements(core, Z1, esz);
                int perDoubleword = 8 >> esz;
                for (int i = 0; i < d.length; i++) {
                    BigInteger amount = unsigned(wide[i / perDoubleword], 3);
                    assertEquals(shiftOracle(kind, n[i], amount, esz), d[i],
                            kind + " esz=" + esz + " amount=" + Long.toUnsignedString(wide[i / perDoubleword]));
                }
            }
        }
    }

    // ── Multiply-add ─────────────────────────────────────────────────────────────────────────────

    private enum MultiplyAdd { MLA, MLS, MAD, MSB }

    static Stream<Arguments> multiplyAddCases() {
        return Stream.of(Arguments.of(MLA_B, MultiplyAdd.MLA, 0), Arguments.of(MLA_H, MultiplyAdd.MLA, 1),
                Arguments.of(MLA_S, MultiplyAdd.MLA, 2), Arguments.of(MLA_D, MultiplyAdd.MLA, 3),
                Arguments.of(MLS_S, MultiplyAdd.MLS, 2), Arguments.of(MLS_D, MultiplyAdd.MLS, 3),
                Arguments.of(MAD_S, MultiplyAdd.MAD, 2), Arguments.of(MAD_D, MultiplyAdd.MAD, 3),
                Arguments.of(MSB_H, MultiplyAdd.MSB, 1), Arguments.of(MSB_D, MultiplyAdd.MSB, 3));
    }

    @ParameterizedTest
    @MethodSource("multiplyAddCases")
    void multiplyAddRespectsThePredicateAndLeavesInactiveElementsUntouched(int word, MultiplyAdd kind, int esz) {
        for (int vl : new int[] {256, 512}) {
            Aarch64Core core = core(vl);
            fillRandom(core, esz, 21, Z1, Z3, Z4);
            Random random = new Random(vl + esz);
            int elements = elementCount(core, esz);
            boolean[] active = new boolean[elements];
            for (int e = 0; e < elements; e++) {
                active[e] = random.nextBoolean();
                for (int b = 0; b < (1 << esz); b++) { // só o bit do byte mais baixo conta; os demais são lixo
                    int index = (e << esz) + b;
                    if ((b == 0 && active[e]) || (b != 0 && random.nextBoolean())) {
                        core.scalable().setPWord(P2, index / 64,
                                core.scalable().pWord(P2, index / 64) | (1L << (index % 64)));
                    }
                }
            }
            long[] d0 = elements(core, Z1, esz);
            long[] z3 = elements(core, Z3, esz);
            long[] z4 = elements(core, Z4, esz);
            run(core, word);
            long[] d = elements(core, Z1, esz);
            for (int e = 0; e < elements; e++) {
                if (!active[e]) {
                    assertEquals(d0[e], d[e], "elemento inativo " + e);
                    continue;
                }
                BigInteger expected = switch (kind) {
                    case MLA -> unsigned(d0[e], esz).add(unsigned(z3[e], esz).multiply(unsigned(z4[e], esz)));
                    case MLS -> unsigned(d0[e], esz).subtract(unsigned(z3[e], esz).multiply(unsigned(z4[e], esz)));
                    case MAD -> unsigned(z3[e], esz).add(unsigned(d0[e], esz).multiply(unsigned(z4[e], esz)));
                    default -> unsigned(z3[e], esz).subtract(unsigned(d0[e], esz).multiply(unsigned(z4[e], esz)));
                };
                assertEquals(truncate(expected, esz), d[e], kind + " esz=" + esz + " e=" + e);
            }
        }
    }

    // ── MOVPRFX ──────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void movprfxFollowedByADestructiveOperationEqualsTheConstructiveForm(int vl) {
        // `movprfx z1, z3` + `eor3 z1, z1, z3, z4`?  Aqui: `movprfx z1, z2; bsl z1, z1, z3, z4`
        // deve dar o mesmo que `bsl` com Zdn já carregado com z2.
        Aarch64Core prefixed = core(vl);
        fillRandom(prefixed, 3, 2, Z2, Z3, Z4);
        Aarch64Core direct = core(vl);
        for (int reg : new int[] {Z2, Z3, Z4}) {
            setElements(direct, reg, 3, allZ(prefixed, reg));
        }
        setElements(prefixed, Z3, 3, allZ(prefixed, Z3));
        setElements(direct, Z1, 3, allZ(prefixed, Z2));
        run(prefixed, 0x0420bc41, BSL); // movprfx z1, z2 ; bsl z1, z1, z3, z4
        run(direct, BSL);
        assertArrayEquals(allZ(direct, Z1), allZ(prefixed, Z1));
        assertArrayEquals(allZ(prefixed, Z2), allZ(direct, Z2), "o fonte do prefixo não é alterado");
    }

    @Test
    void movprfxCopiesTheWholeEffectiveVector() {
        Aarch64Core core = core(512);
        fillRandom(core, 3, 9, Z3);
        run(core, MOVPRFX);
        assertArrayEquals(allZ(core, Z3), allZ(core, Z1));
    }

    // ── FEXPA / FTSSEL ───────────────────────────────────────────────────────────────────────────

    @Test
    void fexpaMatchesTheTranscribedManualTables() {
        long[][] half = {{0x0000L, 0x0000L}, {0x0001L, 0x0016L}, {0x001fL, 0x03d4L}, {(0x1fL << 5) | 31, 0x7fd4L},
                {(0x0fL << 5) | 5, 0x3c00L | 0x0075L}};
        long[][] single = {{0L, 0L}, {1L, 0x0164d2L}, {63L, 0x7d3e0cL}, {(0xffL << 6) | 63, 0x7d3e0cL | (0xffL << 23)},
                {(0x7fL << 6) | 16, 0x1837f0L | (0x7fL << 23)}};
        long[][] dbl = {{0L, 0L}, {1L, 0x02C9A3E778061L}, {32L, 0x6A09E667F3BCDL},
                {(0x3ffL << 6) | 32, 0x3FF6A09E667F3BCDL}, {(0x7ffL << 6) | 63, 0x7FFFA7C1819E90D8L}};
        long[][][] tables = {half, single, dbl};
        int[] words = {FEXPA_H, FEXPA_S, FEXPA_D};
        for (int t = 0; t < 3; t++) {
            int esz = t + 1;
            for (int vl : new int[] {256, 512}) {
                for (long[] pair : tables[t]) {
                    Aarch64Core core = core(vl);
                    setElements(core, Z3, esz, filled(core, esz, pair[0]));
                    run(core, words[t]);
                    for (long value : elements(core, Z1, esz)) {
                        assertEquals(pair[1], value, "esz=" + esz + " in=" + Long.toHexString(pair[0]));
                    }
                }
            }
        }
    }

    @Test
    void fexpaIgnoresBitsAboveTheIndexAndExponentFields() {
        Aarch64Core core = core(256);
        setElements(core, Z3, 2, filled(core, 2, 0xFFFF_C000L | 1L)); // bits 14+ ignorados (índice 6 + expoente 8)
        run(core, FEXPA_S);
        assertEquals(0x0164d2L | (0x00L << 23), elements(core, Z1, 2)[0]);
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ftsselSelectsOneAndFlipsTheSignFromTheTwoLowBitsOfZm(int vl) {
        long[] threes = {0x4200L, 0x40400000L, 0x4008000000000000L};
        long[] ones = {0x3C00L, 0x3F800000L, 0x3FF0000000000000L};
        int[] words = {FTSSEL_H, FTSSEL_S, FTSSEL_D};
        for (int t = 0; t < 3; t++) {
            int esz = t + 1;
            long sign = 1L << (bits(esz) - 1);
            for (long m = 0; m < 8; m++) {
                Aarch64Core core = core(vl);
                setElements(core, Z3, esz, filled(core, esz, threes[t]));
                setElements(core, Z4, esz, filled(core, esz, m));
                run(core, words[t]);
                long expected = (m & 1) != 0 ? ones[t] : threes[t];
                if ((m & 2) != 0) {
                    expected ^= sign;
                }
                for (long value : elements(core, Z1, esz)) {
                    assertEquals(expected, value, "esz=" + esz + " m=" + m);
                }
            }
        }
    }

    // ── INDEX ────────────────────────────────────────────────────────────────────────────────────

    private static void assertIndex(Aarch64Core core, int esz, long start, long increment, String message) {
        long[] d = elements(core, Z1, esz);
        assertEquals(core.vectorLengthBytes() >> esz, d.length, "preenche TODO o VL: " + message);
        for (int i = 0; i < d.length; i++) {
            BigInteger expected = BigInteger.valueOf(start).add(BigInteger.valueOf(increment)
                    .multiply(BigInteger.valueOf(i)));
            assertEquals(truncate(expected, esz), d[i], message + " i=" + i);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void indexFillsEveryElementOfTheVectorInAllFourForms(int vl) {
        Aarch64Core core = core(vl);
        run(core, INDEX_II_B);
        assertIndex(core, 0, -16, 15, "ii.b");
        run(core, INDEX_II_H);
        assertIndex(core, 1, 3, -2, "ii.h");
        run(core, INDEX_II_S);
        assertIndex(core, 2, -1, 1, "ii.s");
        run(core, INDEX_II_D);
        assertIndex(core, 3, 15, -16, "ii.d");

        core.setX(4, 1000);
        run(core, INDEX_IR_B);
        assertIndex(core, 0, 5, 1000, "ir.b (Wm truncado ao byte)");
        core.setX(4, -7);
        run(core, INDEX_IR_D);
        assertIndex(core, 3, -3, -7, "ir.d");
        core.setX(3, 0x1_0000_0002L);
        run(core, INDEX_RI_S);
        assertIndex(core, 2, 0x1_0000_0002L, 7, "ri.s (início truncado a 32)");
        core.setX(3, Long.MAX_VALUE - 3);
        run(core, INDEX_RI_D);
        assertIndex(core, 3, Long.MAX_VALUE - 3, -8, "ri.d");

        core.setX(3, 200);
        core.setX(4, 3);
        run(core, INDEX_RR_B);
        assertIndex(core, 0, 200, 3, "rr.b (estoura o byte e dá a volta)");
        run(core, INDEX_RR_H);
        assertIndex(core, 1, 200, 3, "rr.h");
        run(core, INDEX_RR_S);
        assertIndex(core, 2, 200, 3, "rr.s");
        core.setX(3, -1L);
        core.setX(4, 0x4000_0000_0000_0000L);
        run(core, INDEX_RR_D);
        assertIndex(core, 3, -1L, 0x4000_0000_0000_0000L, "rr.d (64 bits com dobra)");
    }

    @Test
    void indexReadsRegister31AsTheZeroRegister() {
        Aarch64Core core = core(256);
        core.setX(4, 5);
        run(core, INDEX_RR_XZR);
        assertIndex(core, 3, 0, 5, "xzr");
    }

    @Test
    void operationsUseTheEffectiveVectorLengthNotTheImplementedOne() {
        Aarch64Core core = core(1024);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 1); // LEN=1 => VL efetivo 256
        assertEquals(256, core.vectorLengthBits());
        for (int w = 0; w < core.scalable().wordsPerVector(); w++) {
            core.scalable().setZWord(Z1, w, 0xDEAD_BEEFL);
        }
        run(core, INDEX_II_D);
        for (int w = 0; w < 4; w++) {
            assertEquals(15L - 16L * w, core.scalable().zWord(Z1, w));
        }
        assertEquals(0xDEAD_BEEFL, core.scalable().zWord(Z1, 4), "nada acima do VL efetivo é escrito");
    }

    // ── Acesso, streaming e integração com blocos ────────────────────────────────────────────────

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
    @ValueSource(ints = {ADD_B, AND_Z, XAR_B_1, ASR_B_1, ASR_WIDE_B, MLA_B, MOVPRFX, FEXPA_H, FTSSEL_H, INDEX_II_B})
    void everyGroupTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        Aarch64Core core = core(256);
        core.setSystemRegisterBus(new Cpacr());
        setElements(core, Z1, 3, filled(core, 3, 0x1234L));
        run(core, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SVE, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(0x1234L, allZ(core, Z1)[0], "a instrução não executou");
    }

    private static Aarch64Core streamingCore(Aarch64Architecture architecture) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture, 256,
                512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, SMSTART_SM);
        return core;
    }

    @Test
    void integerOperationsRunAtTheStreamingVectorLengthInStreamingMode() {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = streamingCore(architecture);
        run(architecture, core, INDEX_II_D);
        assertIndex(core, 3, 15, -16, "SVL = 512 em streaming");
        assertEquals(8, elements(core, Z1, 3).length);
    }

    @ParameterizedTest
    @ValueSource(ints = {FEXPA_S, FTSSEL_S})
    void fexpaAndFtsselAreUndefinedInStreamingModeWithoutFa64(int word) {
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = streamingCore(architecture);
        core.memory().write32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC=0: UNDEFINED");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        Aarch64Core core = core(256);
        core.memory().write32(0, INDEX_II_S);
        core.memory().write32(4, 0x0420bc22); // movprfx z2, z1
        core.memory().write32(8, ADD_S); // add z1.s, z2.s, z3.s
        Ir64Block block = new StandardIr64BlockLifter(SVE2).lift(core.memory(), 0, 3);
        new Ir64BlockExecutor(SVE2).executeBlock(core, block);
        assertEquals(12L, core.pc());
        long[] d = elements(core, Z1, 2);
        for (int i = 0; i < d.length; i++) {
            assertEquals(truncate(BigInteger.valueOf(i - 1), 2), d[i], "z1 = z2 (copiado do índice) + z3 (zero)");
        }
    }
}
