package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.17 — load contíguo SVE (`LD1`/`LD[234]`/`LDNT1`/`LD1R*`/`LD1RQ`/`LD1RO`/`LDR`/`PRF`) e a classe própria
/// `LDFF1`/`LDNF1` com o `FFR`. Palavras conferidas contra `aarch64-none-elf-as` (devkitA64,
/// `-march=armv9.4-a+sve2+sve2p1+f64mm`).
///
/// O oráculo lê os bytes direto do array da memória de teste (nunca passa pelo `AddressSpace64` nem pelas tabelas do
/// decoder): o tamanho, a largura do elemento e a extensão de cada `dtype` vêm do NOME do mnemônico (`ld1sh {z.s}`).
class Aarch64SveLoadTest {
    private static final int P1 = 1;
    private static final int Z0 = 0;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_UNKNOWN = 0L;
    private static final long MAPPED_LIMIT = 0x2000L;
    private static final long DATA = 0x1800L;
    private static final long DATA_REGION = 0x1000L;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(
            Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2), "teste-SVE2p1", Aarch64Feature.SVE2_1);
    private static final Aarch64Architecture FULL = Aarch64Architecture.extending(SVE2P1, "teste-F64MM",
            Aarch64Feature.F64MM);
    private static final int SMSTART_SM = 0xd503437f;

    /// Memória que só existe abaixo de `MAPPED_LIMIT`: acima, todo acesso levanta a falta de tradução (como uma MMU).
    private static final class FaultingMemory implements AddressSpace64 {
        final byte[] bytes = new byte[(int) MAPPED_LIMIT];
        final java.util.List<Long> touched = new java.util.ArrayList<>();

        private void check(long address, int size) {
            if (address >= DATA_REGION) {
                touched.add(address);
            }
            if (address < 0 || address + size > MAPPED_LIMIT) {
                throw new MemoryTranslationException64(address, MemoryAccessType.DATA_READ,
                        FaultStatus64.translationFault(3));
            }
        }

        @Override
        public int read8(long address) {
            check(address, 1);
            return bytes[(int) address] & 0xFF;
        }

        @Override
        public int read16(long address) {
            check(address, 2);
            return (bytes[(int) address] & 0xFF) | (bytes[(int) address + 1] & 0xFF) << 8;
        }

        @Override
        public int read32(long address) {
            check(address, 4);
            int a = (int) address;
            return (bytes[a] & 0xFF) | (bytes[a + 1] & 0xFF) << 8 | (bytes[a + 2] & 0xFF) << 16
                    | (bytes[a + 3] & 0xFF) << 24;
        }

        @Override
        public void write8(long address, int value) {
            bytes[(int) address] = (byte) value;
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

    private static Aarch64Core core(Aarch64Architecture architecture, int vl, FaultingMemory memory) {
        Aarch64Core core = new Aarch64Core(memory, architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static FaultingMemory randomMemory(long seed) {
        FaultingMemory memory = new FaultingMemory();
        new Random(seed).nextBytes(memory.bytes);
        return memory;
    }

    /// Escreve as palavras a partir de `0x10` e executa uma instrução por palavra. As instruções ficam fora da área de
    /// dados (`0x1000`+) e `touched` é zerado antes, para que só o acesso aos dados apareça nele.
    private static void run(Aarch64Architecture architecture, Aarch64Core core, FaultingMemory memory, int... words) {
        byte[] saved = memory.bytes.clone();
        for (int i = 0; i < words.length; i++) {
            memory.write32(0x10 + i * 4L, words[i]);
        }
        core.setProgramCounter(0x10);
        memory.touched.clear();
        Ir64BlockExecutor executor = new Ir64BlockExecutor(architecture);
        for (int i = 0; i < words.length; i++) {
            executor.step(core);
        }
        // Só o que cabe abaixo de 0x10 + words*4 foi mexido por este método; a área de dados não.
        for (int i = 0x1000; i < saved.length; i++) {
            assertEquals(saved[i], memory.bytes[i], "um load não escreve na memória");
        }
    }

    // ── Utilidades de estado ─────────────────────────────────────────────────────────────────────

    private static byte[] zBytes(Aarch64Core core, int reg) {
        int bytes = core.vectorLengthBytes();
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (core.scalable().zWord(reg, i / 8) >>> ((i % 8) * 8));
        }
        return out;
    }

    private static void fillZ(Aarch64Core core, int reg, long value) {
        for (int w = 0; w < core.vectorLengthBytes() / 8; w++) {
            core.scalable().setZWord(reg, w, value);
        }
    }

    /// Predicado com um bit por elemento ativo (na posição `e << esz`).
    private static void setPredicate(Aarch64Core core, int reg, int esz, boolean[] active) {
        int words = core.scalable().wordsPerPredicate();
        for (int w = 0; w < words; w++) {
            core.scalable().setPWord(reg, w, 0);
        }
        for (int e = 0; e < active.length; e++) {
            if (active[e]) {
                int bit = e << esz;
                core.scalable().setPWord(reg, bit / 64, core.scalable().pWord(reg, bit / 64) | 1L << (bit % 64));
            }
        }
    }

    private static boolean[] allActive(int elements) {
        boolean[] active = new boolean[elements];
        java.util.Arrays.fill(active, true);
        return active;
    }

    private static boolean[] randomActive(int elements, long seed) {
        Random random = new Random(seed);
        boolean[] active = new boolean[elements];
        for (int e = 0; e < elements; e++) {
            active[e] = random.nextInt(3) != 0;
        }
        active[0] = true;
        return active;
    }

    private static void setFfr(Aarch64Core core, long value) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setFfrWord(w, value);
        }
    }

    private static long ffrBit(Aarch64Core core, int bit) {
        return (core.scalable().ffrWord(bit / 64) >>> (bit % 64)) & 1L;
    }

    /// Valor de `size` bytes na memória, sem sinal.
    private static long unsigned(FaultingMemory memory, long address, int size) {
        long value = 0;
        for (int i = size - 1; i >= 0; i--) {
            value = value << 8 | (memory.bytes[(int) address + i] & 0xFF);
        }
        return value;
    }

    private static long extend(long value, int bytes, boolean signed) {
        if (!signed || bytes == 8) {
            return value;
        }
        int shift = 64 - bytes * 8;
        return (value << shift) >> shift;
    }

    /// Bytes esperados de um vetor de elementos de `elementBytes`, com `values[e]` (nulo = inativo = zero).
    private static byte[] expectedVector(int vectorBytes, int elementBytes, Long[] values) {
        byte[] out = new byte[vectorBytes];
        for (int e = 0; e < values.length; e++) {
            if (values[e] == null) {
                continue;
            }
            for (int b = 0; b < elementBytes && b < 8; b++) {
                out[e * elementBytes + b] = (byte) (values[e] >>> (b * 8));
            }
        }
        return out;
    }

    // ── LD1: a tabela dtype, nomeada pelo mnemônico ──────────────────────────────────────────────

    /// (palavra do `as`, tamanho do acesso em bytes, tamanho do elemento em bytes, com sinal). `x2 = base`, `x3 = índice`.
    private static Stream<int[]> dtypes() {
        return Stream.of(
                new int[] {0xa4034440, 1, 1, 0}, // ld1b {z0.b}
                new int[] {0xa4234440, 1, 2, 0}, // ld1b {z0.h}
                new int[] {0xa4434440, 1, 4, 0}, // ld1b {z0.s}
                new int[] {0xa4634440, 1, 8, 0}, // ld1b {z0.d}
                new int[] {0xa4834440, 4, 8, 1}, // ld1sw {z0.d}
                new int[] {0xa4a34440, 2, 2, 0}, // ld1h {z0.h}
                new int[] {0xa4c34440, 2, 4, 0}, // ld1h {z0.s}
                new int[] {0xa4e34440, 2, 8, 0}, // ld1h {z0.d}
                new int[] {0xa5034440, 2, 8, 1}, // ld1sh {z0.d}
                new int[] {0xa5234440, 2, 4, 1}, // ld1sh {z0.s}
                new int[] {0xa5434440, 4, 4, 0}, // ld1w {z0.s}
                new int[] {0xa5634440, 4, 8, 0}, // ld1w {z0.d}
                new int[] {0xa5834440, 1, 8, 1}, // ld1sb {z0.d}
                new int[] {0xa5a34440, 1, 4, 1}, // ld1sb {z0.s}
                new int[] {0xa5c34440, 1, 2, 1}, // ld1sb {z0.h}
                new int[] {0xa5e34440, 8, 8, 0}); // ld1d {z0.d}
    }

    private static Stream<Object[]> dtypesAtEveryVectorLength() {
        return dtypes().flatMap(d -> IntStream.of(VECTOR_LENGTHS).mapToObj(vl -> new Object[] {d, vl}));
    }

    @ParameterizedTest
    @MethodSource("dtypesAtEveryVectorLength")
    void ld1LoadsTheRightSizeWithTheRightExtensionAndZeroesInactiveElements(int[] dtype, int vl) {
        int msz = dtype[1];
        int elementBytes = dtype[2];
        boolean signed = dtype[3] != 0;
        FaultingMemory memory = randomMemory(dtype[0]);
        Aarch64Core core = core(SVE, vl, memory);
        int elements = core.vectorLengthBytes() / elementBytes;
        boolean[] active = randomActive(elements, dtype[0]);
        setPredicate(core, P1, Integer.numberOfTrailingZeros(elementBytes), active);
        fillZ(core, Z0, 0xA5A5A5A5A5A5A5A5L);
        core.setX(2, DATA);
        core.setX(3, 5);
        run(SVE, core, memory, dtype[0]);
        long start = DATA + 5L * msz;
        Long[] values = new Long[elements];
        for (int e = 0; e < elements; e++) {
            if (active[e]) {
                values[e] = extend(unsigned(memory, start + (long) e * msz, msz), msz, signed);
            }
        }
        assertArrayEquals(expectedVector(core.vectorLengthBytes(), elementBytes, values), zBytes(core, Z0));
    }

    @Test
    void inactiveElementsNeverTouchMemory() {
        FaultingMemory memory = randomMemory(7);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[32];
        active[3] = true;
        setPredicate(core, P1, 0, active);
        core.setX(2, MAPPED_LIMIT - 4); // os elementos 4.. estariam fora do mapa
        core.setX(3, 0);
        run(SVE, core, memory, 0xa4034440); // ld1b {z0.b}, p1/z, [x2, x3]
        assertEquals(0x10L + 4, core.pc(), "sem aborto: o elemento inativo fora do mapa não é lido");
        assertEquals(1, memory.touched.size());
        assertEquals(MAPPED_LIMIT - 4 + 3, memory.touched.get(0));
    }

    @Test
    void ld1AbortsPreciselyLeavingTheRegisterUntouched() {
        FaultingMemory memory = randomMemory(8);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        fillZ(core, Z0, 0x1111111111111111L);
        core.setX(2, MAPPED_LIMIT - 16); // metade do vetor fora do mapa
        core.setX(3, 0);
        run(SVE, core, memory, 0xa4034440);
        assertEquals(HANDLER, core.pc(), "aborto real de dados");
        assertEquals(0x1111111111111111L, core.scalable().zWord(Z0, 0), "Z0 não foi gravado");
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ld1WithAScaledImmediateAndAllTheImmediateRangeEnds(int vl) {
        FaultingMemory memory = randomMemory(9);
        Aarch64Core core = core(SVE, vl, memory);
        int bytes = core.vectorLengthBytes();
        setPredicate(core, P1, 0, allActive(bytes));
        core.setX(2, DATA);
        run(SVE, core, memory, 0xa401a440); // ld1b {z0.b}, p1/z, [x2, #1, mul vl]
        Long[] b = new Long[bytes];
        for (int e = 0; e < bytes; e++) {
            b[e] = unsigned(memory, DATA + bytes + e, 1);
        }
        assertArrayEquals(expectedVector(bytes, 1, b), zBytes(core, Z0));
        // ld1d {z0.d}, p1/z, [x2, #-8, mul vl]: imm = -8 (o mínimo), escala = VL/8 elementos × 8 bytes = VL.
        setPredicate(core, P1, 3, allActive(bytes / 8));
        run(SVE, core, memory, 0xa5e8a440);
        Long[] d = new Long[bytes / 8];
        for (int e = 0; e < d.length; e++) {
            d[e] = unsigned(memory, DATA - 8L * bytes + 8L * e, 8);
        }
        assertArrayEquals(expectedVector(bytes, 8, d), zBytes(core, Z0));
    }

    // ── Elemento de 128 bits (LD1W/LD1D .Q) ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ld1wAndLd1dWithA128BitElementZeroExtendIntoEachQuadword(int vl) {
        FaultingMemory memory = randomMemory(10);
        Aarch64Core core = core(SVE2P1, vl, memory);
        int bytes = core.vectorLengthBytes();
        int elements = bytes / 16;
        setPredicate(core, P1, 4, allActive(elements));
        core.setX(2, DATA);
        core.setX(3, 2);
        run(SVE2P1, core, memory, 0xa5038440); // ld1w {z0.q}, p1/z, [x2, x3, lsl #2]
        Long[] words = new Long[elements];
        long start = DATA + 2L * 4;
        for (int e = 0; e < elements; e++) {
            words[e] = unsigned(memory, start + 4L * e, 4);
        }
        assertArrayEquals(quadwords(bytes, words, null), zBytes(core, Z0));
        run(SVE2P1, core, memory, 0xa5838440); // ld1d {z0.q}, p1/z, [x2, x3, lsl #3]
        long dstart = DATA + 2L * 8;
        for (int e = 0; e < elements; e++) {
            words[e] = unsigned(memory, dstart + 8L * e, 8);
        }
        assertArrayEquals(quadwords(bytes, words, null), zBytes(core, Z0));
        run(SVE2P1, core, memory, 0xa5932440); // ld1d {z0.q}, p1/z, [x2, #3, mul vl]
        for (int e = 0; e < elements; e++) {
            words[e] = unsigned(memory, DATA + 3L * bytes / 16 * 8 + 8L * e, 8);
        }
        assertArrayEquals(quadwords(bytes, words, null), zBytes(core, Z0));
    }

    /// Vetor de elementos de 128 bits: `low[e]` nos 8 bytes baixos, `high[e]` (se houver) nos 8 altos.
    private static byte[] quadwords(int bytes, Long[] low, Long[] high) {
        byte[] out = new byte[bytes];
        for (int e = 0; e < low.length; e++) {
            for (int b = 0; b < 8; b++) {
                out[e * 16 + b] = (byte) (low[e] >>> (b * 8));
                if (high != null) {
                    out[e * 16 + 8 + b] = (byte) (high[e] >>> (b * 8));
                }
            }
        }
        return out;
    }

    @Test
    void the128BitElementFormsNeedSve2p1() {
        assertFalse(decodes(SVE, 0xa5038440));
        assertFalse(decodes(SVE, 0xa5932440));
        assertFalse(decodes(SVE, 0xa4a38440)); // ld2q
        assertTrue(decodes(SVE2P1, 0xa5038440));
        assertTrue(decodes(SVE2P1, 0xa4a38440));
    }

    // ── LD2 / LD3 / LD4 / LDNT1 ──────────────────────────────────────────────────────────────────

    /// (palavra, número de registradores, tamanho do elemento em bytes, primeiro registrador, deslocamento em bytes do
    /// primeiro elemento a partir de `x2`; `x3 = 5` só nas formas com registrador).
    private static Stream<Object[]> structures() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> {
            int vb = vl / 8;
            return Stream.of(
                    new Object[] {vl, 0xa423c440, 2, 1, 0, 5L},            // ld2b
                    new Object[] {vl, 0xa4c3c440, 3, 2, 0, 5L * 2},         // ld3h
                    new Object[] {vl, 0xa563c45e, 4, 4, 30, 5L * 4},        // ld4w z30..z1 (dá a volta em Z31 → Z0)
                    new Object[] {vl, 0xa4a38440, 2, 16, 0, 5L * 16},       // ld2q
                    new Object[] {vl, 0xa5238440, 3, 16, 0, 5L * 16},       // ld3q
                    new Object[] {vl, 0xa5a38440, 4, 16, 0, 5L * 16},       // ld4q
                    new Object[] {vl, 0xa403c440, 1, 1, 0, 5L},            // ldnt1b (= ld1b)
                    new Object[] {vl, 0xa583c440, 1, 8, 0, 5L * 8},         // ldnt1d
                    new Object[] {vl, 0xa401e440, 1, 1, 0, (long) vb},      // ldnt1b #1, mul vl
                    new Object[] {vl, 0xa58fe440, 1, 8, 0, -(long) vb},     // ldnt1d #-1, mul vl
                    new Object[] {vl, 0xa421e440, 2, 1, 0, 2L * vb},        // ld2b #2, mul vl
                    new Object[] {vl, 0xa5cfe440, 3, 8, 0, -3L * vb},       // ld3d #-3, mul vl
                    new Object[] {vl, 0xa4e1e440, 4, 2, 0, 4L * vb},        // ld4h #4, mul vl
                    new Object[] {vl, 0xa491e440, 2, 16, 0, 2L * vb},       // ld2q #2, mul vl
                    new Object[] {vl, 0xa51fe440, 3, 16, 0, -3L * vb},      // ld3q #-3, mul vl
                    new Object[] {vl, 0xa591e440, 4, 16, 0, 4L * vb});      // ld4q #4, mul vl
        });
    }

    @ParameterizedTest
    @MethodSource("structures")
    void structureLoadsDeinterleaveIntoConsecutiveRegistersModulo32(int vl, int word, int registers, int elementBytes,
            int firstRegister, long offset) {
        FaultingMemory memory = randomMemory(word);
        Aarch64Core core = core(SVE2P1, vl, memory);
        int bytes = core.vectorLengthBytes();
        int elements = bytes / elementBytes;
        boolean[] active = randomActive(elements, word);
        setPredicate(core, P1, Integer.numberOfTrailingZeros(elementBytes), active);
        core.setX(2, DATA);
        core.setX(3, 5);
        run(SVE2P1, core, memory, word);
        for (int k = 0; k < registers; k++) {
            byte[] expected = new byte[bytes];
            for (int e = 0; e < elements; e++) {
                if (!active[e]) {
                    continue;
                }
                long address = DATA + offset + ((long) e * registers + k) * elementBytes;
                for (int b = 0; b < elementBytes; b++) {
                    expected[e * elementBytes + b] = memory.bytes[(int) address + b];
                }
            }
            assertArrayEquals(expected, zBytes(core, (firstRegister + k) & 31), "registrador " + k);
        }
    }

    // ── LDFF1 / LDNF1 e o FFR ────────────────────────────────────────────────────────────────────

    @Test
    void ldff1WithTheSecondPageUnmappedLoadsTheFirstPageAndClearsTheFfrWithoutAbort() {
        FaultingMemory memory = randomMemory(11);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        setFfr(core, ~0L);
        fillZ(core, Z0, -1L);
        core.setX(2, MAPPED_LIMIT - 16);
        core.setX(3, 0);
        run(SVE, core, memory, 0xa4036440); // ldff1b {z0.b}, p1/z, [x2, x3]
        assertEquals(0x14L, core.pc(), "nenhuma exceção");
        Long[] values = new Long[32];
        for (int e = 0; e < 16; e++) {
            values[e] = unsigned(memory, MAPPED_LIMIT - 16 + e, 1);
        }
        assertArrayEquals(expectedVector(32, 1, values), zBytes(core, Z0), "elementos do lado do erro ficam zero");
        for (int bit = 0; bit < 32; bit++) {
            assertEquals(bit < 16 ? 1L : 0L, ffrBit(core, bit), "FFR bit " + bit);
        }
    }

    @Test
    void ldff1AbortsForRealWhenTheFirstActiveElementFaults() {
        FaultingMemory memory = randomMemory(12);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[32];
        active[20] = true; // o primeiro ATIVO é o 20, e ele está fora do mapa
        setPredicate(core, P1, 0, active);
        setFfr(core, ~0L);
        fillZ(core, Z0, 0x2222222222222222L);
        core.setX(2, MAPPED_LIMIT - 16);
        core.setX(3, 0);
        run(SVE, core, memory, 0xa4036440);
        assertEquals(HANDLER, core.pc());
        assertEquals(0x2222222222222222L, core.scalable().zWord(Z0, 0), "Z0 intacto no aborto");
        assertEquals(~0L, core.scalable().ffrWord(0), "FFR intacto no aborto");
    }

    @Test
    void ldff1ClearsFromTheFaultingElementOnAndNeverSetsBitsBack() {
        FaultingMemory memory = randomMemory(13);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[4]; // ldff1d: 4 doublewords, os bits do FFR são de 8 em 8
        active[0] = true;
        active[2] = true; // fora do mapa
        active[3] = true;
        setPredicate(core, P1, 3, active);
        core.scalable().setFfrWord(0, 0xFFFF_FFFF_FFFF_FF0FL); // o elemento 0 (bits 0-7) já parcialmente limpo
        core.setX(2, MAPPED_LIMIT - 16);
        core.setX(3, 0);
        run(SVE, core, memory, 0xa5e36440); // ldff1d {z0.d}, p1/z, [x2, x3, lsl #3]
        assertEquals(0x14L, core.pc());
        // o elemento 2 tem o bit 16; nada de 16 em diante sobrevive, e os bits 0-15 ficam como estavam
        assertEquals(0x0000_0000_0000_FF0FL, core.scalable().ffrWord(0));
        assertArrayEquals(expectedVector(32, 8, new Long[] {unsigned(memory, MAPPED_LIMIT - 16, 8), null, null, null}),
                zBytes(core, Z0), "o elemento 3, depois da falha, não é carregado");
    }

    @Test
    void ldnf1NeverAbortsNotEvenOnTheFirstElement() {
        FaultingMemory memory = randomMemory(14);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        setFfr(core, ~0L);
        fillZ(core, Z0, -1L);
        core.setX(2, MAPPED_LIMIT); // primeira página fora do mapa
        run(SVE, core, memory, 0xa410a440); // ldnf1b {z0.b}, p1/z, [x2, #0, mul vl]
        assertEquals(0x14L, core.pc(), "sem exceção");
        assertEquals(0L, core.scalable().zWord(Z0, 0));
        assertEquals(0L, core.scalable().ffrWord(0), "FFR zerado desde o elemento 0");
    }

    @Test
    void ldnf1LoadsWhatIsMappedAndRecordsTheFirstFault() {
        FaultingMemory memory = randomMemory(15);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        setFfr(core, ~0L);
        core.setX(2, MAPPED_LIMIT - 8);
        run(SVE, core, memory, 0xa410a440);
        Long[] values = new Long[32];
        for (int e = 0; e < 8; e++) {
            values[e] = unsigned(memory, MAPPED_LIMIT - 8 + e, 1);
        }
        assertArrayEquals(expectedVector(32, 1, values), zBytes(core, Z0));
        assertEquals(0xFFL, core.scalable().ffrWord(0) & 0xFFFF_FFFFL);
    }

    @Test
    void ldnf1WithAScaledImmediateAndSignExtension() {
        FaultingMemory memory = randomMemory(16);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        setFfr(core, ~0L);
        core.setX(2, DATA);
        run(SVE, core, memory, 0xa49ea440); // ldnf1sw {z0.d}, p1/z, [x2, #-2, mul vl]
        Long[] values = new Long[4];
        for (int e = 0; e < 4; e++) {
            values[e] = extend(unsigned(memory, DATA - 2L * 32 / 8 * 4 + 4L * e, 4), 4, true);
        }
        assertArrayEquals(expectedVector(32, 8, values), zBytes(core, Z0));
        assertEquals(~0L, core.scalable().ffrWord(0), "sem falha, o FFR não muda");
    }

    @Test
    void anAllInactivePredicateZeroesTheVectorAndLeavesTheFfrAlone() {
        FaultingMemory memory = randomMemory(17);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, new boolean[32]);
        setFfr(core, 0x1234L);
        fillZ(core, Z0, -1L);
        core.setX(2, MAPPED_LIMIT + 0x100);
        core.setX(3, 0);
        run(SVE, core, memory, 0xa4036440, 0xa410a440);
        assertEquals(0x18L, core.pc(), "nenhum aborto");
        assertEquals(0L, core.scalable().zWord(Z0, 0));
        assertEquals(0x1234L, core.scalable().ffrWord(0));
        assertTrue(memory.touched.isEmpty());
    }

    @Test
    void ldff1AcceptsXzrAsTheIndexRegister() {
        FaultingMemory memory = randomMemory(18);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        setFfr(core, ~0L);
        core.setX(2, DATA);
        run(SVE, core, memory, 0xa5ff6440); // ldff1d {z0.d}, p1/z, [x2, xzr, lsl #3] (= [x2])
        Long[] values = new Long[4];
        for (int e = 0; e < 4; e++) {
            values[e] = unsigned(memory, DATA + 8L * e, 8);
        }
        assertArrayEquals(expectedVector(32, 8, values), zBytes(core, Z0));
        assertFalse(decodes(SVE, 0xa41f4440), "ld1b com Rm = 31 não é alocado");
    }

    // ── LD1R* ────────────────────────────────────────────────────────────────────────────────────

    /// (palavra, tamanho do acesso, tamanho do elemento, com sinal, imediato já em bytes).
    private static Stream<int[]> broadcasts() {
        return Stream.of(
                new int[] {0x847f8440, 1, 1, 0, 63},   // ld1rb {z0.b}, [x2, #63]
                new int[] {0x8445a440, 1, 2, 0, 5},    // ld1rb {z0.h}
                new int[] {0x8445c440, 1, 4, 0, 5},    // ld1rb {z0.s}
                new int[] {0x8445e440, 1, 8, 0, 5},    // ld1rb {z0.d}
                new int[] {0x84ff8440, 4, 8, 1, 252},  // ld1rsw {z0.d}
                new int[] {0x84ffa440, 2, 2, 0, 126},  // ld1rh {z0.h}
                new int[] {0x85418440, 2, 8, 1, 2},    // ld1rsh {z0.d}
                new int[] {0x8541a440, 2, 4, 1, 2},    // ld1rsh {z0.s}
                new int[] {0x8541c440, 4, 4, 0, 4},    // ld1rw {z0.s}
                new int[] {0x8541e440, 4, 8, 0, 4},    // ld1rw {z0.d}
                new int[] {0x85c18440, 1, 8, 1, 1},    // ld1rsb {z0.d}
                new int[] {0x85c1a440, 1, 4, 1, 1},    // ld1rsb {z0.s}
                new int[] {0x85c1c440, 1, 2, 1, 1},    // ld1rsb {z0.h}
                new int[] {0x84c1c440, 2, 4, 0, 2},    // ld1rh {z0.s}
                new int[] {0x84c1e440, 2, 8, 0, 2},    // ld1rh {z0.d}
                new int[] {0x85c1e440, 8, 8, 0, 8});   // ld1rd {z0.d}
    }

    @ParameterizedTest
    @MethodSource("broadcasts")
    void ld1rReplicatesOneElementIntoTheActiveElementsOnly(int[] form) {
        int msz = form[1];
        int elementBytes = form[2];
        FaultingMemory memory = randomMemory(form[0]);
        Aarch64Core core = core(SVE, 256, memory);
        int elements = 32 / elementBytes;
        boolean[] active = randomActive(elements, form[0]);
        setPredicate(core, P1, Integer.numberOfTrailingZeros(elementBytes), active);
        core.setX(2, DATA);
        run(SVE, core, memory, form[0]);
        long value = extend(unsigned(memory, DATA + form[4], msz), msz, form[3] != 0);
        Long[] values = new Long[elements];
        for (int e = 0; e < elements; e++) {
            values[e] = active[e] ? value : null;
        }
        assertArrayEquals(expectedVector(32, elementBytes, values), zBytes(core, Z0));
    }

    @Test
    void ld1rWithNoActiveElementDoesNotTouchMemory() {
        FaultingMemory memory = randomMemory(19);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, new boolean[32]);
        fillZ(core, Z0, -1L);
        core.setX(2, MAPPED_LIMIT + 0x40);
        run(SVE, core, memory, 0x847f8440);
        assertEquals(0x14L, core.pc());
        assertEquals(0L, core.scalable().zWord(Z0, 0));
        assertTrue(memory.touched.isEmpty());
    }

    // ── LD1RQ / LD1RO ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ld1rqReplicatesTheFirstQuadwordUnderThePredicateOfTheFirstSegment(int vl) {
        FaultingMemory memory = randomMemory(20);
        Aarch64Core core = core(FULL, vl, memory);
        int bytes = core.vectorLengthBytes();
        boolean[] active = randomActive(bytes / 4, 20);
        setPredicate(core, P1, 2, active);
        core.setX(2, DATA);
        core.setX(3, 3);
        run(FULL, core, memory, 0xa5030440); // ld1rqw {z0.s}, p1/z, [x2, x3, lsl #2]
        long start = DATA + 3L * 4;
        byte[] expected = new byte[bytes];
        for (int e = 0; e < 4; e++) {
            for (int b = 0; b < 4; b++) {
                byte value = active[e] ? memory.bytes[(int) start + e * 4 + b] : 0;
                for (int copy = 0; copy < bytes / 16; copy++) {
                    expected[copy * 16 + e * 4 + b] = value;
                }
            }
        }
        assertArrayEquals(expected, zBytes(core, Z0), "só os 4 primeiros elementos do predicado contam");
        setPredicate(core, P1, 3, allActive(bytes / 8)); // ld1rqd {z0.d}, p1/z, [x2, #-128]
        run(FULL, core, memory, 0xa5882440);
        byte[] second = new byte[bytes];
        for (int copy = 0; copy < bytes / 16; copy++) {
            System.arraycopy(memory.bytes, (int) DATA - 128, second, copy * 16, 16);
        }
        assertArrayEquals(second, zBytes(core, Z0));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ld1roReplicatesTheFirstOctawordAndIsUndefinedBelow256Bits(int vl) {
        FaultingMemory memory = randomMemory(21);
        Aarch64Core core = core(FULL, vl, memory);
        int bytes = core.vectorLengthBytes();
        setPredicate(core, P1, 3, allActive(bytes / 8));
        core.setX(2, DATA);
        run(FULL, core, memory, 0xa5a82440); // ld1rod {z0.d}, p1/z, [x2, #-256]
        byte[] expected = new byte[bytes];
        for (int copy = 0; copy < bytes / 32; copy++) {
            System.arraycopy(memory.bytes, (int) DATA - 256, expected, copy * 32, 32);
        }
        assertArrayEquals(expected, zBytes(core, Z0));
    }

    @Test
    void ld1roIsUndefinedAt128BitsAndNeedsF64mm() {
        FaultingMemory memory = randomMemory(22);
        Aarch64Core core = core(FULL, 128, memory);
        setPredicate(core, P1, 0, allActive(16));
        core.setX(2, DATA);
        run(FULL, core, memory, 0xa4202440); // ld1rob {z0.b}, p1/z, [x2, #0]
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertFalse(decodes(SVE2P1, 0xa4230440), "ld1rob sem FEAT_F64MM");
        assertTrue(decodes(FULL, 0xa4230440));
        assertTrue(decodes(SVE2P1, 0xa4030440), "ld1rqb só precisa de SVE");
    }

    // ── LDR ──────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void ldrLoadsAWholeVectorAndAWholePredicateFromScaledOffsets(int vl) {
        FaultingMemory memory = randomMemory(23);
        Aarch64Core core = core(SVE, vl, memory);
        int bytes = core.vectorLengthBytes();
        core.setX(2, DATA);
        run(SVE, core, memory, 0x85804c41); // ldr z1, [x2, #3, mul vl]
        byte[] vector = new byte[bytes];
        System.arraycopy(memory.bytes, (int) DATA + 3 * bytes, vector, 0, bytes);
        assertArrayEquals(vector, zBytes(core, 1));
        core.setX(2, DATA);
        run(SVE, core, memory, 0x85800c41); // ldr p1, [x2, #3, mul vl]
        int predicateBytes = bytes / 8;
        for (int i = 0; i < predicateBytes; i++) {
            long got = (core.scalable().pWord(1, i / 8) >>> ((i % 8) * 8)) & 0xFF;
            assertEquals(memory.bytes[(int) DATA + 3 * predicateBytes + i] & 0xFF, got, "byte " + i);
        }
    }

    @Test
    void ldrWithTheMostNegativeImmediateAndFromTheStackPointer() {
        FaultingMemory memory = randomMemory(24);
        Aarch64Core core = core(SVE, 256, memory);
        core.setSp(0x1F00);
        core.setX(2, 0x1F00);
        run(SVE, core, memory, 0x85a04041); // ldr z1, [x2, #-256, mul vl]
        // -256 × 32 bytes = -8192: fora do mapa, portanto é um aborto
        assertEquals(HANDLER, core.pc());
        FaultingMemory smaller = randomMemory(25);
        Aarch64Core c2 = core(SVE, 256, smaller);
        c2.setX(2, 0x1F00);
        c2.setSp(0x1F00);
        run(SVE, c2, smaller, 0x85bf5c41); // ldr z1, [x2, #-1, mul vl]
        assertEquals(0x14L, c2.pc());
        byte[] expected = new byte[32];
        System.arraycopy(smaller.bytes, 0x1F00 - 32, expected, 0, 32);
        assertArrayEquals(expected, zBytes(c2, 1));
        run(SVE, c2, smaller, 0x85bf13ef); // ldr p15, [sp, #-4, mul vl]: PL = 4 bytes
        for (int i = 0; i < 4; i++) {
            long got = (c2.scalable().pWord(15, 0) >>> (i * 8)) & 0xFF;
            assertEquals(smaller.bytes[0x1F00 - 16 + i] & 0xFF, got, "byte " + i);
        }
    }

    // ── PRF ──────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0x8403c440, 0x85c10440, 0x8503c44b, 0x85fe6444, 0x84230440, 0x8405e460})
    void prefetchesAreNoOpsThatNeverAbortEvenOnUnmappedAddresses(int word) {
        FaultingMemory memory = randomMemory(26);
        Aarch64Core core = core(SVE, 256, memory);
        core.setX(2, MAPPED_LIMIT + 0x1000);
        core.setX(3, 0x100);
        core.scalable().setPWord(P1, 0, 0x55L);
        long before = core.scalable().zWord(0, 0);
        run(SVE, core, memory, word);
        assertEquals(0x14L, core.pc());
        assertEquals(0x55L, core.scalable().pWord(P1, 0));
        assertEquals(before, core.scalable().zWord(0, 0));
        assertTrue(memory.touched.isEmpty());
    }

    // ── Modo streaming, decode e lifter ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0xa4036440, 0xa411a440, 0x8405e460})
    void firstFaultNonFaultAndTheNonStreamingPrefetchAreUndefinedInStreamingMode(int word) {
        FaultingMemory memory = randomMemory(27);
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(memory, architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, memory, SMSTART_SM);
        memory.write32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
    }

    @Test
    void ld1IsLegalInStreamingModeAtTheStreamingVectorLength() {
        FaultingMemory memory = randomMemory(28);
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(memory, architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, memory, SMSTART_SM);
        setPredicate(core, P1, 0, allActive(64));
        core.setX(2, DATA);
        core.setX(3, 0);
        memory.write32(0x10, 0xa4034440);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc());
        byte[] expected = new byte[64];
        System.arraycopy(memory.bytes, (int) DATA, expected, 0, 64);
        assertArrayEquals(expected, zBytes(core, Z0), "SVL = 512: 64 bytes");
    }

    @Test
    void theHolesInTheDecodetreeAreRefused() {
        assertFalse(decodes(FULL, 0xa4430440), "LD1RQ/LD1RO com bits[22:21] = 10");
        assertFalse(decodes(FULL, 0xa4630440), "LD1RQ/LD1RO com bits[22:21] = 11");
        assertFalse(decodes(FULL, 0xa4412440), "LD1RQ/LD1RO imediato com bits[22:21] = 10");
        assertFalse(decodes(FULL, 0xa4112440), "001 com o bit 20 ligado só existe para as formas .Q");
        assertFalse(decodes(FULL, 0xa4b1e440), "LD[234]Q imediato exige bits[22:21] = 00");
        assertFalse(decodes(FULL, 0xa411e440), "LD[234]Q imediato com nreg = 0");
        assertFalse(decodes(FULL, 0xa4238440), "100 com msz = 0 e sel = 01 não é alocado nesta task");
        assertFalse(decodes(FULL, 0xa41f4440), "LD1B com Rm = 31");
        assertFalse(decodes(FULL, 0x841fc440), "PRF_rr com Rm = 31");
        assertTrue(decodes(FULL, 0xa5ff6440), "LDFF1 com Rm = 31 (XZR)");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        FaultingMemory memory = randomMemory(29);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        core.setX(2, DATA);
        core.setX(3, 5);
        memory.write32(0, 0xa4034440);
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(memory, 0, 1);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        byte[] expected = new byte[32];
        System.arraycopy(memory.bytes, (int) DATA + 5, expected, 0, 32);
        assertArrayEquals(expected, zBytes(core, Z0));
    }

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        FaultingMemory memory = new FaultingMemory();
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static boolean decodes(Aarch64Architecture architecture, int word) {
        return decodeOrNull(architecture, word) instanceof Ir64Op.SveLoad;
    }
}
