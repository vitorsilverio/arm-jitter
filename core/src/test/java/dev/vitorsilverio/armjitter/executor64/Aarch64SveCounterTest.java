package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SvePredicateOp64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.28 — predicado-como-contador do SVE2.1 (`PN8`-`PN15`): `PTRUE`, `CNTP`, `PEXT`, `WHILE*` com contador e os 16
/// `LD1`/`ST1` multi-vetor. Palavras conferidas contra `aarch64-none-elf-as` (devkitA64, `-march=armv9.2-a+sme+sme2+sve2p1`).
///
/// O ORÁCULO do contador é escrito de outro modo que o executor: a definição do manual, por EXPANSÃO em granulos de byte
/// (o bit do byte `b` está ligado se `b` é múltiplo do tamanho do elemento do contador e `b / esize < contagem`, e `invert`
/// nega isso), lida no elemento da instrução; o executor usa o atalho do QEMU (contagem ajustada mais passo). Os dois só
/// concordam se o atalho estiver certo, inclusive com o contador MENOR ou MAIOR que o elemento da instrução.
class Aarch64SveCounterTest {
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final int STREAMING_VL = 512;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_SME = 0x1DL;
    private static final int SMSTART_SM = 0xd503437f;
    private static final int INSTRUCTION_ADDRESS = 0x10;
    private static final long MAPPED_LIMIT = 0x4000L;
    private static final long DATA = 0x2000L;
    private static final long DATA_REGION = 0x1000L;
    private static final int BASE_REGISTER = 3;
    private static final int OFFSET_REGISTER = 4;
    private static final int COUNTER_BASE = 8;
    private static final long JUNK = 0x3333_3333_3333_3333L;

    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-SVE2", Aarch64Feature.SVE2);
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-SVE2p1", Aarch64Feature.SVE2, Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture SME2 = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A,
            "teste-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
    private static final Aarch64Architecture SVE2P1_SME2 = Aarch64Architecture.extending(SVE2P1, "teste-SVE2p1-SME2",
            Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);

    // ── Memória de teste ─────────────────────────────────────────────────────────────────────────

    /// Só existe abaixo de `MAPPED_LIMIT`. Registra o endereço de cada leitura de dados (a partir de `DATA_REGION`, para
    /// não contar a busca de instrução) e de cada byte escrito.
    private static final class CounterMemory implements AddressSpace64 {
        final byte[] bytes = new byte[(int) MAPPED_LIMIT];
        final List<Long> touched = new ArrayList<>();
        final List<Long> written = new ArrayList<>();

        private void check(long address, int size, MemoryAccessType type) {
            if (address < 0 || address + size > MAPPED_LIMIT) {
                throw new MemoryTranslationException64(address, type, FaultStatus64.translationFault(3));
            }
        }

        private void read(long address, int size) {
            if (address >= DATA_REGION) {
                touched.add(address);
            }
            check(address, size, MemoryAccessType.DATA_READ);
        }

        long raw(long address, int size) {
            long value = 0;
            for (int i = size - 1; i >= 0; i--) {
                value = value << 8 | (bytes[(int) address + i] & 0xFF);
            }
            return value;
        }

        @Override
        public int read8(long address) {
            read(address, 1);
            return (int) raw(address, 1);
        }

        @Override
        public int read16(long address) {
            read(address, 2);
            return (int) raw(address, 2);
        }

        @Override
        public int read32(long address) {
            read(address, 4);
            return (int) raw(address, 4);
        }

        @Override
        public long read64(long address) {
            read(address, 8);
            return raw(address, 8);
        }

        @Override
        public void write8(long address, int value) {
            check(address, 1, MemoryAccessType.DATA_WRITE);
            bytes[(int) address] = (byte) value;
            written.add(address);
        }

        @Override
        public void write16(long address, int value) {
            write8(address, value);
            write8(address + 1, value >>> 8);
        }

        @Override
        public void write32(long address, int value) {
            write16(address, value);
            write16(address + 2, value >>> 16);
        }
    }

    private static CounterMemory randomMemory(long seed) {
        CounterMemory memory = new CounterMemory();
        new Random(seed).nextBytes(memory.bytes);
        return memory;
    }

    private static Aarch64Core core(Aarch64Architecture architecture, int vl, CounterMemory memory) {
        Aarch64Core core = new Aarch64Core(memory, architecture, vl, STREAMING_VL);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    /// Escreve a palavra em `0x10`, executa UMA instrução e zera os registros de acesso.
    private static void run(Aarch64Architecture architecture, Aarch64Core core, CounterMemory memory, int word) {
        memory.write32(INSTRUCTION_ADDRESS, word);
        core.setProgramCounter(INSTRUCTION_ADDRESS);
        memory.touched.clear();
        memory.written.clear();
        new Ir64BlockExecutor(architecture).step(core);
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        CounterMemory memory = new CounterMemory();
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    // ── Codificadores (a partir do layout do `sve.decode`, independentes do decoder) ─────────────

    private static int ptrueCounter(int esz, int pn) {
        return 0x25207810 | esz << 22 | pn - COUNTER_BASE;
    }

    private static int cntpCounter(int esz, int lg2Vectors, int pn, int rd) {
        return 0x25208300 | esz << 22 | (lg2Vectors - 1) << 10 | pn - COUNTER_BASE << 5 | rd;
    }

    private static int pext1(int esz, int imm, int pn, int pd) {
        return 0x25207010 | esz << 22 | imm << 8 | pn - COUNTER_BASE << 5 | pd;
    }

    private static int pext2(int esz, int imm, int pn, int pd) {
        return 0x25207410 | esz << 22 | imm << 8 | pn - COUNTER_BASE << 5 | pd;
    }

    /// `WHILE*` com contador. `less` = `WHILELT`/`WHILELO`/`WHILELE`/`WHILELS`; `vectors` = 2 ou 4.
    private static int whileCounter(boolean less, int vectors, int esz, int rm, int rn, boolean unsigned, boolean eqBit, int pn) {
        int base = (vectors == 2 ? 0x25204010 : 0x25206010) | (less ? 1 << 10 : 0);
        return base | esz << 22 | rm << 16 | (unsigned ? 1 << 11 : 0) | rn << 5 | (eqBit ? 1 << 3 : 0) | pn - COUNTER_BASE;
    }

    private static int multiVector(boolean store, boolean strided, boolean immediate, int registers, int esz, int pg, int rn,
            int rmOrImm, int rd) {
        int word = strided ? 0xA1000000 : 0xA0000000;
        word |= immediate ? (store ? 0x600000 : 0x400000) : (store ? 0x200000 : 0);
        word |= (immediate ? rmOrImm & 0xF : rmOrImm) << 16;
        word |= (registers == 4 ? 1 << 15 : 0) | esz << 13 | pg - COUNTER_BASE << 10 | rn << 5 | rd;
        return word;
    }

    // ── O que o montador emite (devkitA64) ───────────────────────────────────────────────────────

    private static final int PTRUE_PN8_B = 0x25207810;
    private static final int PTRUE_PN15_D = 0x25e07817;
    private static final int CNTP_PN8_B_VLX2 = 0x25208300;
    private static final int CNTP_PN15_D_VLX4 = 0x25e087e1;
    private static final int CNTP_PN9_H_VLX2 = 0x25608322;
    private static final int WHILELT_PN8_B_VLX2 = 0x25214410;
    private static final int WHILELT_PN9_H_VLX4 = 0x25636451;
    private static final int WHILELO_PN10_S_VLX2 = 0x25a54c92;
    private static final int WHILELS_PN15_D_VLX4 = 0x25e56c9f;
    private static final int WHILEGE_PN8_B_VLX2 = 0x25214010;
    private static final int WHILEGT_PN9_B_VLX4 = 0x25216019;
    private static final int WHILEHI_PN10_S_VLX2 = 0x25a5489a;
    private static final int WHILEHS_PN11_S_VLX4 = 0x25a56893;
    private static final int PEXT_P0_B_PN8_0 = 0x25207010;
    private static final int PEXT_P1_H_PN9_3 = 0x25607331;
    private static final int PEXT_P2P3_S_PN10_1 = 0x25a07552;
    private static final int PEXT_P14P15_D_PN15_0 = 0x25e074fe;
    private static final int LD1B_Z0Z1_PN8_X0_X1 = 0xa0010000;
    private static final int LD1W_Z4Z7_PN9_X2_X3 = 0xa003c444;
    // O montador escreve o deslocamento em VLs (`#2, mul vl` com 2 registradores = campo `imm4 = 1`).
    private static final int LD1H_Z2Z3_PN10_X0_IMM2 = 0xa0412802;
    private static final int LD1D_Z8Z11_PN15_SP_IMMM8 = 0xa04effe8;
    private static final int ST1B_Z0Z1_PN8_X0_X1 = 0xa0210000;
    private static final int ST1W_Z4Z7_PN9_X2_X3 = 0xa023c444;
    private static final int ST1H_Z2Z3_PN10_X0_IMM2 = 0xa0612802;
    private static final int ST1D_Z8Z11_PN15_SP_IMMM8 = 0xa06effe8;
    private static final int LD1B_Z0Z8_PN8_X0_X1 = 0xa1010000;
    private static final int LD1B_Z16Z24_PN9_X0_X1 = 0xa1010410;
    private static final int LD1W_Z1Z9_PN8_X0_X1 = 0xa1014001;
    private static final int LD1W_Z0Z4Z8Z12_PN8_X0_X1 = 0xa101c000;
    private static final int LD1W_Z17Z21Z25Z29_PN8_X0_X1 = 0xa101c011;
    private static final int LD1H_Z0Z8_PN8_X0_IMMM2 = 0xa14f2000;
    private static final int LD1B_Z0Z4Z8Z12_PN8_X0_IMM4 = 0xa1418000;
    private static final int ST1B_Z0Z8_PN8_X0_X1 = 0xa1210000;
    private static final int ST1W_Z0Z4Z8Z12_PN8_X0_X1 = 0xa121c000;
    private static final int ST1H_Z0Z8_PN8_X0_IMMM2 = 0xa16f2000;
    private static final int ST1B_Z0Z4Z8Z12_PN8_X0_IMM4 = 0xa1618000;
    private static final int LDNT1B_Z0Z8_PN8_X0_X1 = 0xa1010008;
    private static final int STNT1W_Z0Z4Z8Z12_PN8_X0_X1 = 0xa121c008;

    private static final int[] SCALAR_WORDS = {
        PTRUE_PN8_B, PTRUE_PN15_D, CNTP_PN8_B_VLX2, CNTP_PN15_D_VLX4, CNTP_PN9_H_VLX2, WHILELT_PN8_B_VLX2,
        WHILELT_PN9_H_VLX4, WHILELO_PN10_S_VLX2, WHILELS_PN15_D_VLX4, WHILEGE_PN8_B_VLX2, WHILEGT_PN9_B_VLX4,
        WHILEHI_PN10_S_VLX2, WHILEHS_PN11_S_VLX4, PEXT_P0_B_PN8_0, PEXT_P1_H_PN9_3, PEXT_P2P3_S_PN10_1,
        PEXT_P14P15_D_PN15_0};
    private static final int[] CONTIGUOUS_WORDS = {
        LD1B_Z0Z1_PN8_X0_X1, LD1W_Z4Z7_PN9_X2_X3, LD1H_Z2Z3_PN10_X0_IMM2, LD1D_Z8Z11_PN15_SP_IMMM8,
        ST1B_Z0Z1_PN8_X0_X1, ST1W_Z4Z7_PN9_X2_X3, ST1H_Z2Z3_PN10_X0_IMM2, ST1D_Z8Z11_PN15_SP_IMMM8};
    private static final int[] STRIDED_WORDS = {
        LD1B_Z0Z8_PN8_X0_X1, LD1B_Z16Z24_PN9_X0_X1, LD1W_Z1Z9_PN8_X0_X1, LD1W_Z0Z4Z8Z12_PN8_X0_X1,
        LD1W_Z17Z21Z25Z29_PN8_X0_X1, LD1H_Z0Z8_PN8_X0_IMMM2, LD1B_Z0Z4Z8Z12_PN8_X0_IMM4, ST1B_Z0Z8_PN8_X0_X1,
        ST1W_Z0Z4Z8Z12_PN8_X0_X1, ST1H_Z0Z8_PN8_X0_IMMM2, ST1B_Z0Z4Z8Z12_PN8_X0_IMM4, LDNT1B_Z0Z8_PN8_X0_X1,
        STNT1W_Z0Z4Z8Z12_PN8_X0_X1};

    // ── Decodificação ────────────────────────────────────────────────────────────────────────────

    @Test
    void ptrueAndCntpAndPextCarryTheirCounterRegisterAndFields() {
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.PTRUE, 0, 8, 0, 0, 0, false, 0),
                decode(SVE2P1, PTRUE_PN8_B));
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.PTRUE, 3, 15, 0, 0, 0, false, 0),
                decode(SVE2P1, PTRUE_PN15_D));
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.CNTP, 3, 0, 15, 1, 2, false, 0),
                decode(SVE2P1, CNTP_PN15_D_VLX4));
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.CNTP, 1, 0, 9, 2, 1, false, 0),
                decode(SVE2P1, CNTP_PN9_H_VLX2));
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.PEXT_1, 1, 1, 9, 0, 3, false, 0),
                decode(SVE2P1, PEXT_P1_H_PN9_3));
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.PEXT_2, 2, 2, 10, 0, 1, false, 0),
                decode(SVE2P1, PEXT_P2P3_S_PN10_1));
        assertEquals(new SvePredicateOp64.CounterPredicate(SvePredicateOp64.CounterPredicate.Op.PEXT_2, 3, 14, 15, 0, 0, false, 0),
                decode(SVE2P1, PEXT_P14P15_D_PN15_0));
    }

    @Test
    void whileWithACounterAlsoDecodesUnderSme2Alone() {
        assertNotNull(decode(SME2, WHILELT_PN8_B_VLX2));
        assertNull(decode(SVE2, WHILELT_PN8_B_VLX2), "SVE2 sem SVE2p1 nem SME2");
    }

    @Test
    void whileWithACounterDecodesTheFourOperationsWithTheEqAndUnsignedBits() {
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_LT_CNT2, 0, 8, 0, 1, true, false, false, 0),
                decode(SVE2P1, WHILELT_PN8_B_VLX2));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_LT_CNT4, 1, 9, 2, 3, true, false, false, 0),
                decode(SVE2P1, WHILELT_PN9_H_VLX4));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_LT_CNT2, 2, 10, 4, 5, true, true, false, 0),
                decode(SVE2P1, WHILELO_PN10_S_VLX2));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_LT_CNT4, 3, 15, 4, 5, true, true, true, 0),
                decode(SVE2P1, WHILELS_PN15_D_VLX4));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_GT_CNT2, 0, 8, 0, 1, true, false, false, 0),
                decode(SVE2P1, WHILEGE_PN8_B_VLX2));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_GT_CNT4, 0, 9, 0, 1, true, false, true, 0),
                decode(SVE2P1, WHILEGT_PN9_B_VLX4));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_GT_CNT2, 2, 10, 4, 5, true, true, true, 0),
                decode(SVE2P1, WHILEHI_PN10_S_VLX2));
        assertEquals(new SvePredicateOp64.ScalarCompare(SvePredicateOp64.ScalarCompare.Op.WHILE_GT_CNT4, 2, 11, 4, 5, true, true, false, 0),
                decode(SVE2P1, WHILEHS_PN11_S_VLX4));
    }

    private static SveMemoryOp64.MultiVectorMemory multi(Aarch64Architecture architecture, int word) {
        return assertInstanceOf(SveMemoryOp64.MultiVectorMemory.class, decode(architecture, word), Integer.toHexString(word));
    }

    @Test
    void contiguousMultiVectorFormsCarryConsecutiveRegistersAndTheCounterGovernor() {
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 0, 2, 0, 1, 8, 0, 1, true, 0, false, 0),
                multi(SVE2P1, LD1B_Z0Z1_PN8_X0_X1));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 2, 4, 4, 1, 9, 2, 3, true, 0, false, 0),
                multi(SVE2P1, LD1W_Z4Z7_PN9_X2_X3));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 1, 2, 2, 1, 10, 0, 0, false, 1, false, 0),
                multi(SVE2P1, LD1H_Z2Z3_PN10_X0_IMM2));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 3, 4, 8, 1, 15, 31, 0, false, -2, false, 0),
                multi(SVE2P1, LD1D_Z8Z11_PN15_SP_IMMM8));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(true, 3, 4, 8, 1, 15, 31, 0, false, -2, false, 0),
                multi(SVE2P1, ST1D_Z8Z11_PN15_SP_IMMM8));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(true, 1, 2, 2, 1, 10, 0, 0, false, 1, false, 0),
                multi(SVE2P1, ST1H_Z2Z3_PN10_X0_IMM2));
    }

    /// As duas formas de 2 registradores com o MESMO `rd` cru diferem no destino: o consecutivo usa `rd:4 × 2` (bits 4:1) e o
    /// `_stride` usa `rd:5` com o bit 3 como hint não temporal — e o registrador seguinte está a 8, não a 1.
    @Test
    void stridedFormsUnscrambleTheRegisterFieldAndReadTheStrideFromTheRegisterCount() {
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 0, 2, 0, 8, 8, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, LD1B_Z0Z8_PN8_X0_X1));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 0, 2, 16, 8, 9, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, LD1B_Z16Z24_PN9_X0_X1));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 2, 2, 1, 8, 8, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, LD1W_Z1Z9_PN8_X0_X1));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 2, 4, 0, 4, 8, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, LD1W_Z0Z4Z8Z12_PN8_X0_X1));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 2, 4, 17, 4, 8, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, LD1W_Z17Z21Z25Z29_PN8_X0_X1));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 0, 2, 0, 8, 8, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, LDNT1B_Z0Z8_PN8_X0_X1)); // o bit 3 (não temporal) não escolhe o registrador
        assertEquals(new SveMemoryOp64.MultiVectorMemory(false, 1, 2, 0, 8, 8, 0, 0, false, -1, true, 0),
                multi(SVE2P1_SME2, LD1H_Z0Z8_PN8_X0_IMMM2));
        assertEquals(new SveMemoryOp64.MultiVectorMemory(true, 2, 4, 0, 4, 8, 0, 1, true, 0, true, 0),
                multi(SVE2P1_SME2, STNT1W_Z0Z4Z8Z12_PN8_X0_X1));
    }

    @Test
    void theSameRegisterFieldMeansDifferentDestinationsInTheTwoTwoRegisterForms() {
        int consecutive = multiVector(false, false, false, 2, 0, 8, 0, 1, 0b01000);
        int strided = multiVector(false, true, false, 2, 0, 8, 0, 1, 0b00000);
        assertEquals(8, multi(SVE2P1_SME2, consecutive).rt());
        assertEquals(1, multi(SVE2P1_SME2, consecutive).registerStride());
        assertEquals(0, multi(SVE2P1_SME2, strided).rt());
        assertEquals(8, multi(SVE2P1_SME2, strided).registerStride());
    }

    @Test
    void featureGatesFollowTheQemuPredicates() {
        for (int word : concat(SCALAR_WORDS, CONTIGUOUS_WORDS, STRIDED_WORDS)) {
            assertNull(decode(SVE, word), "sem SVE2p1/SME2: " + Integer.toHexString(word));
        }
        for (int word : concat(SCALAR_WORDS, CONTIGUOUS_WORDS)) {
            assertNotNull(decode(SVE2P1, word), "SVE2p1: " + Integer.toHexString(word));
        }
        for (int word : STRIDED_WORDS) {
            assertNull(decode(SVE2P1, word), "as _stride são SME2 puras: " + Integer.toHexString(word));
            assertNotNull(decode(SVE2P1_SME2, word), "SVE2p1+SME2: " + Integer.toHexString(word));
        }
        for (int word : CONTIGUOUS_WORDS) {
            assertTrue(multi(SVE2P1_SME2, word).streamingOnly() == false, "SVE2p1 presente: fora do streaming também");
            assertTrue(multi(SME2, word).streamingOnly(), "só SME2: exige streaming");
        }
    }

    /// `CNTP_c` com o bit 8 zerado: o campo `rn:4` do `sve.decode` admitiria `P0`-`P7`, mas `PNn` só existe de 8 a 15.
    @Test
    void cntpRefusesAnOrdinaryPredicateRegisterInsteadOfReadingItAsACounter() {
        for (int pn = 0; pn < COUNTER_BASE; pn++) {
            int word = CNTP_PN8_B_VLX2 & ~(0b1000 << 5) | pn << 5;
            assertNull(decode(SVE2P1, word), "P" + pn);
        }
    }

    @Test
    void aFourRegisterStridedFormWithAMisalignedRegisterIsRefusedAndTheContiguousFourRegisterFormHasNoRoomForBitOne() {
        assertNull(decode(SVE2P1_SME2, LD1W_Z0Z4Z8Z12_PN8_X0_X1 | 0b00100));
        assertNull(decode(SVE2P1, LD1W_Z4Z7_PN9_X2_X3 | 0b10));
        assertNotNull(decode(SVE2P1, LD1W_Z4Z7_PN9_X2_X3 | 0b1)); // o bit 0 é o LDNT1
    }

    private static int[] concat(int[]... arrays) {
        return Arrays.stream(arrays).flatMapToInt(Arrays::stream).toArray();
    }

    // ── Varredura do decoder contra as linhas do `sve.decode` ────────────────────────────────────

    private enum Group { SCALAR, WHILE_COUNTER, CONTIGUOUS, STRIDED }

    /// Uma linha do `sve.decode` transcrita literalmente (`.`/`-` livres). `extraFree` são bits que o decoder recusa por
    /// regra do manual mesmo que o `.decode` os deixe livres (documentado no decoder).
    private record Line(String text, Group group) {
        int mask() {
            return bits(true);
        }

        int value() {
            return bits(false);
        }

        private int bits(boolean mask) {
            int result = 0;
            for (char c : text.replace(" ", "").toCharArray()) {
                boolean fixed = c == '0' || c == '1';
                result = result << 1 | (mask ? (fixed ? 1 : 0) : (c == '1' ? 1 : 0));
            }
            return result;
        }

        boolean matches(int word) {
            return (word & mask()) == value();
        }
    }

    private static final List<Line> LINES = List.of(
            new Line("00100101 .. 1000000111100000010 ...", Group.SCALAR),
            new Line("00100101 .. 100 000 10 000 . 1 .... .....", Group.SCALAR),
            new Line("00100101 .. 1 00000 0111 00 .. ... 1 ....", Group.SCALAR),
            new Line("00100101 .. 1 00000 0111 010 . ... 1 ....", Group.SCALAR),
            new Line("00100101 .. 1 ..... 0100 . 1 ..... 1 . ...", Group.WHILE_COUNTER),
            new Line("00100101 .. 1 ..... 0110 . 1 ..... 1 . ...", Group.WHILE_COUNTER),
            new Line("00100101 .. 1 ..... 0100 . 0 ..... 1 . ...", Group.WHILE_COUNTER),
            new Line("00100101 .. 1 ..... 0110 . 0 ..... 1 . ...", Group.WHILE_COUNTER),
            new Line("10100000000 ..... 0 .. ... ..... .... -", Group.CONTIGUOUS),
            new Line("10100000000 ..... 1 .. ... ..... ... 0 -", Group.CONTIGUOUS),
            new Line("10100000001 ..... 0 .. ... ..... .... -", Group.CONTIGUOUS),
            new Line("10100000001 ..... 1 .. ... ..... ... 0 -", Group.CONTIGUOUS),
            new Line("101000000100 .... 0 .. ... ..... .... -", Group.CONTIGUOUS),
            new Line("101000000100 .... 1 .. ... ..... ... 0 -", Group.CONTIGUOUS),
            new Line("101000000110 .... 0 .. ... ..... .... -", Group.CONTIGUOUS),
            new Line("101000000110 .... 1 .. ... ..... ... 0 -", Group.CONTIGUOUS),
            new Line("10100001000 ..... 0 .. ... ..... .....", Group.STRIDED),
            new Line("10100001000 ..... 1 .. ... ..... .....", Group.STRIDED),
            new Line("10100001001 ..... 0 .. ... ..... .....", Group.STRIDED),
            new Line("10100001001 ..... 1 .. ... ..... .....", Group.STRIDED),
            new Line("101000010100 .... 0 .. ... ..... .....", Group.STRIDED),
            new Line("101000010100 .... 1 .. ... ..... .....", Group.STRIDED),
            new Line("101000010110 .... 0 .. ... ..... .....", Group.STRIDED),
            new Line("101000010110 .... 1 .. ... ..... .....", Group.STRIDED));

    private static boolean isCounterOp(Ir64Op op) {
        return op instanceof SvePredicateOp64.CounterPredicate || op instanceof SveMemoryOp64.MultiVectorMemory
                || op instanceof SvePredicateOp64.ScalarCompare compare && switch (compare.op()) {
                    case WHILE_LT_CNT2, WHILE_LT_CNT4, WHILE_GT_CNT2, WHILE_GT_CNT4 -> true;
                    default -> false;
                };
    }

    /// Regras do manual que o decoder aplica além do `.decode` (ver o Javadoc de `Aarch64SveCounterDecoder`).
    private static boolean refusedByTheManual(Line line, int word) {
        if (line.text().startsWith("00100101 .. 100 000 10 000")) {
            return (word & 0x100) == 0; // PNn tem bits[9:8] = 11
        }
        return line.group() == Group.STRIDED && (word & 1 << 15) != 0 && (word & 0b100) != 0;
    }

    @Test
    void theSweepOfOneMillionWordsMatchesTheDecodeFileLineByLine() {
        Random random = new Random(0x28);
        for (int sample = 0; sample < 1_000_000; sample++) {
            int word;
            if (sample % 2 == 0) {
                int prefix = switch (sample / 2 % 3) {
                    case 0 -> 0x25000000;
                    case 1 -> 0xA0000000;
                    default -> 0xA1000000;
                };
                word = prefix | random.nextInt() & 0x00FFFFFF;
            } else {
                // Uma linha do grupo com 1 a 3 bits FIXOS invertidos: o que sobra fica perto do grupo, onde um decoder frouxo erraria.
                Line line = LINES.get(random.nextInt(LINES.size()));
                word = line.value() | random.nextInt() & ~line.mask();
                for (int flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    word ^= 1 << random.nextInt(Integer.SIZE);
                }
            }
            Line matched = null;
            for (Line line : LINES) {
                if (line.matches(word)) {
                    assertNull(matched, "o .decode transcrito tem duas linhas para " + Integer.toHexString(word));
                    matched = line;
                }
            }
            Ir64Op decoded = decode(SVE2P1_SME2, word);
            String hex = Integer.toHexString(word);
            if (matched == null || refusedByTheManual(matched, word)) {
                assertFalse(isCounterOp(decoded), "não é linha do grupo: " + hex);
            } else {
                assertTrue(isCounterOp(decoded), "linha do grupo recusada: " + hex);
            }
        }
    }

    @Test
    void everyVariableBitOfEveryLineIsExhaustivelyAcceptedOrRefusedAsTheDecodeFileSays() {
        for (Line line : LINES) {
            int free = ~line.mask();
            // Enumera TODAS as combinações dos bits livres, se couberem em 2^17; senão, uma amostra de 2^17.
            int freeBits = Integer.bitCount(free);
            Random random = new Random(line.text().hashCode());
            int total = 1 << Math.min(freeBits, 17);
            for (int i = 0; i < total; i++) {
                int bits = freeBits <= 17 ? deposit(i, free) : random.nextInt() & free;
                int word = line.value() | bits;
                String hex = Integer.toHexString(word);
                Ir64Op decoded = decode(SVE2P1_SME2, word);
                if (refusedByTheManual(line, word)) {
                    assertFalse(isCounterOp(decoded), hex);
                } else {
                    assertTrue(isCounterOp(decoded), line.text() + ": " + hex);
                }
            }
        }
    }

    /// Espalha os bits de `value` pelas posições ligadas em `mask` (o inverso de `Integer.compress`).
    private static int deposit(int value, int mask) {
        return Integer.expand(value, mask);
    }

    // ── O modelo do contador (oráculo por expansão em bytes) ─────────────────────────────────────

    /// O contador de tamanho `counterEsz` com `count` elementos, `invert` ou não, escrito à mão em bits — `((count << 1) | 1)
    /// << counterEsz`, o bit 15 como inversão.
    private static long counterBits(int counterEsz, int count, boolean invert) {
        return (((long) count << 1 | 1L) << counterEsz) | (invert ? 1L << 15 : 0L);
    }

    /// `true` se o elemento `element` (tamanho `vectorEsz`) do trecho de vetores está ativo, pela definição do manual.
    private static boolean expandedActive(int counterEsz, int count, boolean invert, int vectorEsz, int element) {
        long byteIndex = (long) element << vectorEsz;
        int granule = Math.max(counterEsz, vectorEsz);
        boolean aligned = byteIndex % (1L << granule) == 0;
        if (!aligned) {
            return false;
        }
        boolean inside = byteIndex < ((long) count << counterEsz);
        return inside != invert;
    }

    /// `count` máximo que cabe no campo do contador para `vl` (bytes): `4 × VL >> counterEsz`, menos um.
    private static int maxCount(int vl, int counterEsz) {
        return (4 * vl >> counterEsz) - 1;
    }

    private static Stream<Object[]> everyCounterShape() {
        List<Object[]> shapes = new ArrayList<>();
        for (int vl : new int[] {32, 64}) {
            for (int counterEsz = 0; counterEsz < 4; counterEsz++) {
                int max = maxCount(vl, counterEsz);
                for (int count : new int[] {0, 1, 2, 3, 5, 7, max / 4, max / 2, max / 2 + 1, max - 1, max}) {
                    if (count < 0 || count > max) {
                        continue;
                    }
                    for (boolean invert : new boolean[] {false, true}) {
                        shapes.add(new Object[] {vl, counterEsz, count, invert});
                    }
                }
            }
        }
        return shapes.stream();
    }

    @ParameterizedTest(name = "VL {0}B, contador {1}, contagem {2}, invert {3}")
    @MethodSource("everyCounterShape")
    void decodingACounterAgreesWithTheByteExpansionForEveryElementSize(int vl, int counterEsz, int count, boolean invert) {
        long bits = counterBits(counterEsz, count, invert);
        for (int vectorEsz = 0; vectorEsz < 4; vectorEsz++) {
            SveCounterOps.Counter counter = SveCounterOps.Counter.decode(bits, vl, vectorEsz);
            for (int element = 0; element < 4 * vl >> vectorEsz; element++) {
                assertEquals(expandedActive(counterEsz, count, invert, vectorEsz, element), counter.active(element),
                        "esz do vetor " + vectorEsz + ", elemento " + element);
            }
        }
    }

    @Test
    void aCounterWithNoElementSizeBitIsAnEmptyPredicateEvenWithInvertSet() {
        SveCounterOps.Counter counter = SveCounterOps.Counter.decode(0x8000L | 0x10, 32, 0);
        for (int element = 0; element < 128; element++) {
            assertFalse(counter.active(element));
        }
    }

    @Test
    void theCountFieldIsCutAtFourVectorsOfBits() {
        // VL = 32 bytes: a máscara do contador tem 8 bits (pow2ceil(32) << 3 = 256). Um bit acima disso é ignorado.
        long polluted = counterBits(0, 5, false) | 1L << 9;
        SveCounterOps.Counter counter = SveCounterOps.Counter.decode(polluted, 32, 0);
        assertEquals(5, counter.count());
    }

    @Test
    void encodePredCountUsesTheCanonicalFullFormAndZeroForNone() {
        assertEquals(0L, SveCounterOps.encode(32, 0, 0, false));
        assertEquals(0L, SveCounterOps.encode(32, 0, 2, true));
        assertEquals(1L << 15 | 1L, SveCounterOps.encode(32, 32, 0, false), "cheio = invert com contagem 0");
        assertEquals(1L << 15 | 1L << 2, SveCounterOps.encode(8, 8, 2, false));
        assertEquals((5L << 1 | 1L) << 1, SveCounterOps.encode(32, 5, 1, false));
        assertEquals(1L << 15 | (27L << 1 | 1L), SveCounterOps.encode(32, 5, 0, true), "invert guarda elementos - contagem");
        assertEquals(1L << 15 | 1L << 3, SveCounterOps.encode(4, 4, 3, true), "invert de tudo é a contagem 0");
    }

    // ── Execução ─────────────────────────────────────────────────────────────────────────────────

    private static Stream<Integer> everyVectorLength() {
        return Arrays.stream(VECTOR_LENGTHS).boxed();
    }

    private static Stream<Object[]> vectorLengthAndElementSize() {
        return everyVectorLength().flatMap(vl -> IntStream.range(0, 4).mapToObj(esz -> new Object[] {vl, esz}));
    }

    private static void setCounter(Aarch64Core core, int pn, long bits) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(pn, w, w == 0 ? bits : JUNK);
        }
    }

    private static long counterWord(Aarch64Core core, int pn) {
        return core.scalable().pWord(pn, 0);
    }

    private static boolean predicateBit(Aarch64Core core, int reg, int bit) {
        return (core.scalable().pWord(reg, bit / 64) >>> (bit % 64) & 1L) != 0;
    }

    @ParameterizedTest
    @MethodSource("vectorLengthAndElementSize")
    void ptrueWritesTheCanonicalAllTrueCounterAndClearsTheRestOfTheRegister(int vl, int esz) {
        for (int pn = COUNTER_BASE; pn < 16; pn++) {
            CounterMemory memory = randomMemory(1);
            Aarch64Core core = core(SVE2P1, vl, memory);
            setCounter(core, pn, 0x1234);
            run(SVE2P1, core, memory, ptrueCounter(esz, pn));
            assertEquals(0x8000L | 1L << esz, counterWord(core, pn));
            for (int w = 1; w < core.scalable().wordsPerPredicate(); w++) {
                assertEquals(0L, core.scalable().pWord(pn, w));
            }
            assertEquals(0x14L, core.pc());
        }
    }

    @ParameterizedTest
    @MethodSource("everyCounterShape")
    void cntpCountsTheActiveElementsOfTwoOrFourVectors(int vlBytes, int counterEsz, int count, boolean invert) {
        int vl = vlBytes * 8;
        if (vl != 256 && vl != 512) {
            return;
        }
        for (int vectorEsz = 0; vectorEsz < 4; vectorEsz++) {
            for (int lg2Vectors = 1; lg2Vectors <= 2; lg2Vectors++) {
                CounterMemory memory = randomMemory(2);
                Aarch64Core core = core(SVE2P1, vl, memory);
                setCounter(core, 9, counterBits(counterEsz, count, invert));
                core.setX(2, JUNK);
                run(SVE2P1, core, memory, cntpCounter(vectorEsz, lg2Vectors, 9, 2));
                long expected = 0;
                for (int e = 0; e < (vlBytes << lg2Vectors) >> vectorEsz; e++) {
                    if (expandedActive(counterEsz, count, invert, vectorEsz, e)) {
                        expected++;
                    }
                }
                assertEquals(expected, core.x(2), "esz " + vectorEsz + " vlx" + (1 << lg2Vectors));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("everyCounterShape")
    void pextExtractsTheSegmentsOfTheCounterAsPlainMasks(int vlBytes, int counterEsz, int count, boolean invert) {
        int vl = vlBytes * 8;
        if (vl != 256 && vl != 512) {
            return;
        }
        for (int vectorEsz = 0; vectorEsz < 4; vectorEsz++) {
            int granule = vectorEsz + Math.max(0, counterEsz - vectorEsz);
            for (int imm = 0; imm < 4; imm++) {
                CounterMemory memory = randomMemory(3);
                Aarch64Core core = core(SVE2P1, vl, memory);
                setCounter(core, 10, counterBits(counterEsz, count, invert));
                for (int p = 0; p < 16; p++) {
                    if (p != 10) {
                        setCounter(core, p, JUNK);
                    }
                }
                run(SVE2P1, core, memory, pext1(vectorEsz, imm, 10, 0));
                for (int bit = 0; bit < vlBytes; bit++) {
                    int element = ((imm * vlBytes + bit)) >> vectorEsz;
                    boolean aligned = bit % (1 << granule) == 0 && ((imm * vlBytes + bit) & ((1 << vectorEsz) - 1)) == 0;
                    boolean expected = aligned && expandedActive(counterEsz, count, invert, vectorEsz, element);
                    assertEquals(expected, predicateBit(core, 0, bit),
                            "segmento " + imm + " esz " + vectorEsz + " bit " + bit);
                }
                assertEquals(JUNK, counterWord(core, 1), "PEXT de um registrador não mexe no vizinho");
            }
            for (int imm = 0; imm < 2; imm++) {
                CounterMemory memory = randomMemory(4);
                Aarch64Core core = core(SVE2P1, vl, memory);
                setCounter(core, 10, counterBits(counterEsz, count, invert));
                run(SVE2P1, core, memory, pext2(vectorEsz, imm, 10, 14));
                for (int half = 0; half < 2; half++) {
                    int segment = imm * 2 + half;
                    int register = (14 + half) % 16;
                    for (int bit = 0; bit < vlBytes; bit++) {
                        int byteIndex = segment * vlBytes + bit;
                        boolean aligned = bit % (1 << granule) == 0 && (byteIndex & ((1 << vectorEsz) - 1)) == 0;
                        boolean expected = aligned
                                && expandedActive(counterEsz, count, invert, vectorEsz, byteIndex >> vectorEsz);
                        assertEquals(expected, predicateBit(core, register, bit),
                                "par, segmento " + segment + " esz " + vectorEsz + " bit " + bit);
                    }
                }
            }
        }
    }

    @Test
    void pext2PairWrapsAroundTheSixteenPredicateRegisters() {
        CounterMemory memory = randomMemory(5);
        Aarch64Core core = core(SVE2P1, 256, memory);
        setCounter(core, 8, counterBits(0, 1, true)); // invert com contagem 1: tudo menos o primeiro byte
        run(SVE2P1, core, memory, pext2(0, 0, 8, 15));
        assertFalse(predicateBit(core, 15, 0));
        assertTrue(predicateBit(core, 15, 1));
        assertTrue(predicateBit(core, 0, 0), "o segundo registrador do par de P15 é P0");
    }

    // ── WHILE com contador ───────────────────────────────────────────────────────────────────────

    private static final long[] EDGE_VALUES = {
        0L, 1L, 2L, 3L, 7L, 8L, 9L, 100L, -1L, -2L, -3L, -100L, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE,
        Long.MIN_VALUE + 1, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFFL};

    /// Quantos elementos consecutivos, a partir do primeiro, satisfazem a condição do `WHILE`: o laço do manual incrementa (ou
    /// decrementa) um registrador de 64 bits que DÁ A VOLTA — por isso `MAX <= MAX` continua verdadeiro no elemento seguinte
    /// (`MAX + 1` vira `MIN`) e o predicado sai cheio.
    private static int whileTrueElements(boolean less, boolean orEqual, boolean unsigned, long op0, long op1, int maxElements) {
        int count = 0;
        long current = op0;
        for (int i = 0; i < maxElements; i++) {
            int order = unsigned ? Long.compareUnsigned(current, op1) : Long.compare(current, op1);
            boolean holds = less ? (orEqual ? order <= 0 : order < 0) : (orEqual ? order >= 0 : order > 0);
            if (!holds) {
                break;
            }
            count++;
            current += less ? 1 : -1;
        }
        return count;
    }

    /// `PredCountTest` do manual.
    private static int[] predCountTest(int elements, int count, boolean invert) {
        if (count == 0) {
            return new int[] {0, 1, 1, 0};
        }
        if (!invert) {
            return new int[] {1, 0, count != elements ? 1 : 0, 0};
        }
        return new int[] {count == elements ? 1 : 0, 0, 0, 0};
    }

    private static Stream<Object[]> whileCases() {
        List<Object[]> cases = new ArrayList<>();
        for (int vl : VECTOR_LENGTHS) {
            for (int vectors : new int[] {2, 4}) {
                for (int esz = 0; esz < 4; esz++) {
                    for (boolean less : new boolean[] {true, false}) {
                        for (boolean unsigned : new boolean[] {false, true}) {
                            for (boolean eqBit : new boolean[] {false, true}) {
                                cases.add(new Object[] {vl, vectors, esz, less, unsigned, eqBit});
                            }
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "VL {0} vlx{1} esz {2} lt={3} unsigned={4} eqBit={5}")
    @MethodSource("whileCases")
    void whileWritesTheEncodedCountAndTheCountTestFlagsForEdgeOperands(int vl, int vectors, int esz, boolean less,
            boolean unsigned, boolean eqBit) {
        // No `WHILE_gt`, o bit `eq = 0` é `GE`/`HS`; no `WHILE_lt`, `eq = 1` é `LE`/`LS` (`eq = (a->eq == lt)`).
        boolean orEqual = eqBit == less;
        int maxElements = (vl / 8 * vectors) >> esz;
        for (long op0 : EDGE_VALUES) {
            for (long op1 : EDGE_VALUES) {
                CounterMemory memory = randomMemory(6);
                Aarch64Core core = core(SVE2P1, vl, memory);
                core.setX(1, op0);
                core.setX(2, op1);
                int word = whileCounter(less, vectors, esz, 2, 1, unsigned, eqBit, 12);
                run(SVE2P1, core, memory, word);
                int count = whileTrueElements(less, orEqual, unsigned, op0, op1, maxElements);
                assertEquals(SveCounterOps.encode(maxElements, count, esz, !less), counterWord(core, 12),
                        Long.toHexString(op0) + " ? " + Long.toHexString(op1));
                int[] flags = predCountTest(maxElements, count, !less);
                var pstate = core.pstate();
                assertEquals(flags[0] == 1, pstate.negative(), "N");
                assertEquals(flags[1] == 1, pstate.zero(), "Z");
                assertEquals(flags[2] == 1, pstate.carry(), "C");
                assertFalse(pstate.overflow(), "V");
                for (int w = 1; w < core.scalable().wordsPerPredicate(); w++) {
                    assertEquals(0L, core.scalable().pWord(12, w));
                }
            }
        }
    }

    /// Fixa números concretos em vez do oráculo: `WHILELT PN8.B, X0, X1, VLX2` com `X0 = 3`, `X1 = 40` em `VL = 256` cobre 64
    /// bytes: 37 elementos ficam acima do limite, então o contador sai CHEIO (contagem 0, `invert`).
    @Test
    void aWhileThatFillsTheTwoVectorsIsTheCanonicalFullCounter() {
        CounterMemory memory = randomMemory(7);
        Aarch64Core core = core(SVE2P1, 256, memory);
        core.setX(0, 3);
        core.setX(1, 100);
        run(SVE2P1, core, memory, WHILELT_PN8_B_VLX2);
        assertEquals(0x8001L, counterWord(core, 8));
        core.setX(1, 10); // 7 elementos
        run(SVE2P1, core, memory, WHILELT_PN8_B_VLX2);
        assertEquals((7L << 1 | 1L), counterWord(core, 8));
        core.setX(1, 3); // nenhum
        run(SVE2P1, core, memory, WHILELT_PN8_B_VLX2);
        assertEquals(0L, counterWord(core, 8));
        assertTrue(core.pstate().zero());
    }

    // ── LD1 / ST1 multi-vetor ────────────────────────────────────────────────────────────────────

    private record MultiForm(boolean store, boolean strided, boolean immediate, int registers, int esz) {
        int stride() {
            return strided ? (registers == 2 ? 8 : 4) : 1;
        }

        @Override
        public String toString() {
            return (store ? "st1" : "ld1") + (strided ? "-stride" : "") + (immediate ? "-imm" : "-reg") + " x" + registers
                    + " esz" + esz;
        }
    }

    private static Stream<Object[]> everyMultiForm() {
        List<Object[]> forms = new ArrayList<>();
        for (int vl : VECTOR_LENGTHS) {
            for (boolean store : new boolean[] {false, true}) {
                for (boolean strided : new boolean[] {false, true}) {
                    for (boolean immediate : new boolean[] {false, true}) {
                        for (int registers : new int[] {2, 4}) {
                            for (int esz = 0; esz < 4; esz++) {
                                forms.add(new Object[] {new MultiForm(store, strided, immediate, registers, esz), vl});
                            }
                        }
                    }
                }
            }
        }
        return forms.stream();
    }

    private static int firstRegister(MultiForm form, int seed) {
        // Registrador inicial válido: no consecutivo, múltiplo do número de registradores; no `_stride`, os blocos
        // 0-7 ou 16-23 (2 registradores) e 0-3 ou 16-19 (4).
        if (!form.strided()) {
            return (seed % (32 / form.registers())) * form.registers();
        }
        return form.registers() == 2 ? (seed % 2 == 0 ? seed % 8 : 16 + seed % 8) : (seed % 2 == 0 ? seed % 4 : 16 + seed % 4);
    }

    private static Aarch64Architecture architectureFor(MultiForm form) {
        return form.strided() ? SVE2P1_SME2 : SVE2P1;
    }

    /// Roda uma instrução multi-vetor em modo streaming quando ela o exige (`_stride`), preparando o `Z`/`P` DEPOIS de
    /// entrar no modo (entrar/sair zera o estado).
    private static Aarch64Core preparedCore(MultiForm form, int vl, CounterMemory memory) {
        Aarch64Core core = core(architectureFor(form), vl, memory);
        if (form.strided()) {
            run(architectureFor(form), core, memory, SMSTART_SM);
        }
        return core;
    }

    private static int effectiveVl(MultiForm form, int vl) {
        return form.strided() ? STREAMING_VL : vl;
    }

    @ParameterizedTest(name = "{0} @ VL {1}")
    @MethodSource("everyMultiForm")
    void everyMultiVectorFormMovesExactlyTheElementsTheCounterActivates(MultiForm form, int vl) {
        int effective = effectiveVl(form, vl);
        int vlBytes = effective / 8;
        int perVector = vlBytes >> form.esz();
        Random random = new Random(form.toString().hashCode() + vl);
        int[] counterEszs = {0, 1, 2, 3};
        for (int trial = 0; trial < 24; trial++) {
            int counterEsz = counterEszs[trial % 4];
            boolean invert = trial % 3 == 2;
            int count = random.nextInt(maxCount(vlBytes, counterEsz) + 1);
            int zt = firstRegister(form, random.nextInt(8));
            int stride = form.stride();
            CounterMemory memory = randomMemory(trial);
            Aarch64Core core = preparedCore(form, vl, memory);
            setCounter(core, 8 + trial % 8, counterBits(counterEsz, count, invert));
            long base = DATA + 0x100;
            core.setX(BASE_REGISTER, base);
            core.setX(OFFSET_REGISTER, 5);
            int immediate = trial % 16 - 8;
            long start = form.immediate()
                    ? base + (long) immediate * form.registers() * vlBytes
                    : base + (5L << form.esz());
            int word = multiVector(form.store(), form.strided(), form.immediate(), form.registers(), form.esz(),
                    8 + trial % 8, BASE_REGISTER, form.immediate() ? immediate : OFFSET_REGISTER, zt);
            if (form.immediate() && start < DATA_REGION || start + (long) form.registers() * vlBytes > MAPPED_LIMIT) {
                continue;
            }
            memory.write32(INSTRUCTION_ADDRESS, word); // a palavra entra na imagem esperada, não no diff
            byte[] original = memory.bytes.clone();
            long[][] sources = new long[form.registers()][perVector];
            if (form.store()) {
                for (int k = 0; k < form.registers(); k++) {
                    for (int e = 0; e < perVector; e++) {
                        sources[k][e] = random.nextLong() & mask(form.esz());
                        setElement(core, zt + k * stride, e, 1 << form.esz(), sources[k][e]);
                    }
                }
            } else {
                for (int k = 0; k < form.registers(); k++) {
                    fillZ(core, zt + k * stride, JUNK);
                }
            }
            run(architectureFor(form), core, memory, word);
            assertEquals(0x14L, core.pc(), form + " trial " + trial);
            List<Long> expectedAddresses = new ArrayList<>();
            byte[] expectedMemory = original.clone();
            for (int k = 0; k < form.registers(); k++) {
                for (int e = 0; e < perVector; e++) {
                    int global = k * perVector + e;
                    boolean active = expandedActive(counterEsz, count, invert, form.esz(), global);
                    long address = start + ((long) global << form.esz());
                    if (form.store()) {
                        if (active) {
                            for (int b = 0; b < 1 << form.esz(); b++) {
                                expectedMemory[(int) address + b] = (byte) (sources[k][e] >>> (8 * b));
                            }
                        }
                    } else {
                        long expected = active ? memory.raw(address, 1 << form.esz()) : 0L;
                        assertEquals(expected, element(core, zt + k * stride, e, 1 << form.esz()),
                                form + " trial " + trial + " reg " + k + " elem " + e);
                        if (active) {
                            expectedAddresses.add(address);
                        }
                    }
                }
            }
            if (form.store()) {
                assertArrayEquals(expectedMemory, memory.bytes, form + " trial " + trial + ": só o elemento ativo escreve");
            } else {
                assertEquals(expectedAddresses, memory.touched, form + " trial " + trial + ": só o ativo lê, em ordem");
            }
        }
    }

    private static long mask(int esz) {
        return esz == 3 ? -1L : (1L << (8 << esz)) - 1L;
    }

    private static void setElement(Aarch64Core core, int reg, int element, int elementBytes, long value) {
        int bitOffset = element * elementBytes * 8;
        int word = bitOffset / 64;
        int shift = bitOffset % 64;
        if (elementBytes == 8) {
            core.scalable().setZWord(reg, word, value);
            return;
        }
        long mask = ((1L << (elementBytes * 8)) - 1L) << shift;
        core.scalable().setZWord(reg, word, core.scalable().zWord(reg, word) & ~mask | (value << shift) & mask);
    }

    private static long element(Aarch64Core core, int reg, int element, int elementBytes) {
        int bitOffset = element * elementBytes * 8;
        long word = core.scalable().zWord(reg, bitOffset / 64) >>> (bitOffset % 64);
        return elementBytes == 8 ? word : word & ((1L << (elementBytes * 8)) - 1L);
    }

    private static void fillZ(Aarch64Core core, int reg, long value) {
        for (int w = 0; w < core.vectorLengthBytes() / 8; w++) {
            core.scalable().setZWord(reg, w, value);
        }
    }

    /// O `_stride` e o consecutivo com os mesmos operandos escolhem registradores DIFERENTES: o teste distingue as duas
    /// formas pelos `Z` que o load escreve (`Z0` e `Z1` contra `Z0` e `Z8`).
    @Test
    void theStridedAndTheConsecutiveTwoRegisterLoadsWriteDifferentRegisterPairs() {
        CounterMemory consecutiveMemory = randomMemory(8);
        Aarch64Core consecutive = core(SVE2P1_SME2, 256, consecutiveMemory);
        setCounter(consecutive, 8, 0x8001L); // todos os elementos, esz 0
        consecutive.setX(BASE_REGISTER, DATA);
        for (int reg : new int[] {0, 1, 8}) {
            fillZ(consecutive, reg, JUNK);
        }
        run(SVE2P1_SME2, consecutive, consecutiveMemory, multiVector(false, false, false, 2, 0, 8, BASE_REGISTER, 31, 0));
        assertEquals(consecutiveMemory.raw(DATA, 8), consecutive.scalable().zWord(0, 0));
        assertEquals(consecutiveMemory.raw(DATA + 32, 8), consecutive.scalable().zWord(1, 0), "o segundo vetor é Z1");
        assertEquals(JUNK, consecutive.scalable().zWord(8, 0), "Z8 intacto");

        CounterMemory stridedMemory = randomMemory(9);
        Aarch64Core strided = core(SVE2P1_SME2, 256, stridedMemory);
        run(SVE2P1_SME2, strided, stridedMemory, SMSTART_SM);
        setCounter(strided, 8, 0x8001L);
        strided.setX(BASE_REGISTER, DATA);
        for (int reg : new int[] {0, 1, 8}) {
            fillZ(strided, reg, JUNK);
        }
        run(SVE2P1_SME2, strided, stridedMemory, multiVector(false, true, false, 2, 0, 8, BASE_REGISTER, 31, 0));
        assertEquals(stridedMemory.raw(DATA, 8), strided.scalable().zWord(0, 0));
        assertEquals(stridedMemory.raw(DATA + 64, 8), strided.scalable().zWord(8, 0), "o segundo vetor é Z8 (VL de streaming 512)");
        assertEquals(JUNK, strided.scalable().zWord(1, 0), "Z1 intacto");
    }

    /// Com o contador zerado nenhum elemento acessa a memória: nem o aborto de um endereço não mapeado acontece, e o
    /// destino do load vira zero (não fica com o valor antigo).
    @ParameterizedTest
    @MethodSource("everyVectorLength")
    void anEmptyCounterTouchesNothingAndZeroesTheLoadDestinations(int vl) {
        CounterMemory memory = randomMemory(10);
        Aarch64Core core = core(SVE2P1, vl, memory);
        setCounter(core, 8, 0L);
        core.setX(BASE_REGISTER, 0x7FFF_0000L); // não mapeado
        for (int reg = 0; reg < 4; reg++) {
            fillZ(core, reg, JUNK);
        }
        run(SVE2P1, core, memory, multiVector(false, false, false, 4, 0, 8, BASE_REGISTER, 31, 0));
        assertEquals(0x14L, core.pc());
        for (int reg = 0; reg < 4; reg++) {
            assertEquals(0L, core.scalable().zWord(reg, 0), "Z" + reg);
        }
        assertTrue(memory.touched.isEmpty());
    }

    /// Um elemento ativo num endereço não mapeado aborta o load INTEIRO, sem gravar nenhum registrador (aborto preciso).
    @Test
    void anAbortInTheMiddleOfALoadLeavesEveryDestinationRegisterUntouched() {
        CounterMemory memory = randomMemory(11);
        Aarch64Core core = core(SVE2P1, 256, memory);
        setCounter(core, 8, 0x8001L); // tudo ativo: 64 bytes a partir da base
        core.setX(BASE_REGISTER, MAPPED_LIMIT - 40); // o segundo vetor cruza o limite
        fillZ(core, 0, JUNK);
        fillZ(core, 1, JUNK);
        run(SVE2P1, core, memory, multiVector(false, false, false, 2, 0, 8, BASE_REGISTER, 31, 0));
        assertEquals(HANDLER, core.pc(), "o aborto de dados entra no vetor de exceções");
        assertEquals(JUNK, core.scalable().zWord(0, 0));
        assertEquals(JUNK, core.scalable().zWord(1, 0));
    }

    @Test
    void theStackPointerIsTheBaseWhenRnIs31AndXzrIsTheIndexWhenRmIs31() {
        CounterMemory memory = randomMemory(12);
        Aarch64Core core = core(SVE2P1, 256, memory);
        core.setSp(DATA);
        core.setX(OFFSET_REGISTER, JUNK);
        setCounter(core, 8, 0x8001L);
        run(SVE2P1, core, memory, multiVector(false, false, false, 2, 3, 8, 31, 31, 0));
        assertEquals(memory.raw(DATA, 8), core.scalable().zWord(0, 0), "Xm = XZR: deslocamento zero");
    }

    // ── Acesso e modo streaming ──────────────────────────────────────────────────────────────────

    @Test
    void cntpWithoutSve2p1NeedsStreamingModeAndTrapsOutsideIt() {
        CounterMemory memory = randomMemory(13);
        Aarch64Core core = core(SME2, 256, memory);
        setCounter(core, 8, 0x8001L);
        core.setX(0, JUNK);
        run(SME2, core, memory, CNTP_PN8_B_VLX2);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(JUNK, core.x(0));

        CounterMemory streamingMemory = randomMemory(14);
        Aarch64Core streaming = core(SME2, 256, streamingMemory);
        run(SME2, streaming, streamingMemory, SMSTART_SM);
        setCounter(streaming, 8, 0x8001L);
        run(SME2, streaming, streamingMemory, CNTP_PN8_B_VLX2);
        assertEquals(2L * STREAMING_VL / 8, streaming.x(0), "VL de streaming, dois vetores de bytes");
    }

    @ParameterizedTest
    @ValueSource(ints = {LD1B_Z0Z8_PN8_X0_X1, ST1W_Z0Z4Z8Z12_PN8_X0_X1, LD1H_Z0Z8_PN8_X0_IMMM2, ST1B_Z0Z4Z8Z12_PN8_X0_IMM4})
    void theStridedFormsTrapOutsideStreamingModeEvenWhenSve2p1IsPresent(int word) {
        CounterMemory memory = randomMemory(15);
        Aarch64Core core = core(SVE2P1_SME2, 256, memory);
        setCounter(core, 8, 0x8001L);
        core.setX(BASE_REGISTER, DATA);
        run(SVE2P1_SME2, core, memory, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertTrue(memory.touched.isEmpty());
        assertTrue(memory.written.isEmpty());
    }

    @Test
    void theContiguousFormsRunInStreamingModeToo() {
        CounterMemory memory = randomMemory(16);
        Aarch64Core core = core(SVE2P1_SME2, 256, memory);
        run(SVE2P1_SME2, core, memory, SMSTART_SM);
        setCounter(core, 8, 0x8001L);
        core.setX(BASE_REGISTER, DATA);
        run(SVE2P1_SME2, core, memory, multiVector(false, false, false, 2, 3, 8, BASE_REGISTER, 31, 0));
        assertEquals(0x14L, core.pc());
        assertEquals(memory.raw(DATA + STREAMING_VL / 8, 8), core.scalable().zWord(1, 0), "o segundo vetor está a SVL bytes");
    }

    @Test
    void ptrueIsAnOrdinarySveAccessAndIsRefusedWithoutTheFeature() {
        CounterMemory memory = randomMemory(17);
        Aarch64Core core = core(SME2, 256, memory);
        run(SME2, core, memory, PTRUE_PN8_B);
        assertEquals(0x8001L, counterWord(core, 8));
        assertThrowsUnsupportedAtDecodeWithoutTheFeature();
    }

    private static void assertThrowsUnsupportedAtDecodeWithoutTheFeature() {
        CounterMemory memory = new CounterMemory();
        memory.write32(0, PTRUE_PN8_B);
        assertThrows(UnsupportedOperationException.class, () -> new Aarch64Decoder(SVE).decode(memory, 0));
    }
}
