package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.ir64.SveMemoryOp64;
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
import java.util.function.LongUnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.18 — store SVE (`ST1`/`STNT1`/`ST[234]`/`STR`/scatter/`ST1Q`). Palavras conferidas contra
/// `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1+f64mm`).
///
/// O oráculo NÃO passa pelas tabelas do decoder: o tamanho do acesso e o do elemento de cada palavra vêm do NOME do
/// mnemônico (`st1h {z0.s}` = acesso de 2 bytes, elemento de 4), e o resultado é comparado byte a byte com uma IMAGEM
/// esperada da memória inteira — o que pega, de graça, uma escrita em elemento inativo.
class Aarch64SveStoreTest {
    private static final int P1 = 1;
    private static final int Z0 = 0;
    private static final int Z3 = 3;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_UNKNOWN = 0L;
    private static final long MAPPED_LIMIT = 0x2000L;
    private static final long DATA = 0x1800L;
    private static final int INSTRUCTION_AREA = 0x100;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(
            Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2), "teste-SVE2p1", Aarch64Feature.SVE2_1);
    private static final int SMSTART_SM = 0xd503437f;

    /// Memória que só existe abaixo de `MAPPED_LIMIT`: acima, todo acesso levanta a falta de tradução. Registra o
    /// endereço de cada byte escrito.
    private static final class StoreMemory implements AddressSpace64 {
        final byte[] bytes = new byte[(int) MAPPED_LIMIT];
        final List<Long> written = new ArrayList<>();

        private void check(long address, int size, MemoryAccessType type) {
            if (address < 0 || address + size > MAPPED_LIMIT) {
                throw new MemoryTranslationException64(address, type, FaultStatus64.translationFault(3));
            }
        }

        void poke32(long address, int value) {
            for (int i = 0; i < 4; i++) {
                bytes[(int) address + i] = (byte) (value >>> (i * 8));
            }
        }

        @Override
        public int read8(long address) {
            check(address, 1, MemoryAccessType.DATA_READ);
            return bytes[(int) address] & 0xFF;
        }

        @Override
        public int read16(long address) {
            return read8(address) | read8(address + 1) << 8;
        }

        @Override
        public int read32(long address) {
            return read16(address) | read16(address + 2) << 16;
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

    private static Aarch64Core core(Aarch64Architecture architecture, int vl, StoreMemory memory) {
        Aarch64Core core = new Aarch64Core(memory, architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static StoreMemory randomMemory(long seed) {
        StoreMemory memory = new StoreMemory();
        new Random(seed).nextBytes(memory.bytes);
        return memory;
    }

    /// Escreve as palavras a partir de `0x10` (direto nos bytes: não conta como escrita de dados) e executa uma
    /// instrução por palavra.
    private static void run(Aarch64Architecture architecture, Aarch64Core core, StoreMemory memory, int... words) {
        for (int i = 0; i < words.length; i++) {
            memory.poke32(0x10 + i * 4L, words[i]);
        }
        core.setProgramCounter(0x10);
        memory.written.clear();
        Ir64BlockExecutor executor = new Ir64BlockExecutor(architecture);
        for (int i = 0; i < words.length; i++) {
            executor.step(core);
        }
    }

    /// Compara a memória inteira com a imagem esperada. A área das instruções (abaixo de `0x100`) é ignorada: o `run` só
    /// grava as palavras ali depois de a imagem esperada ter sido copiada.
    private static void assertMemory(byte[] expected, StoreMemory memory) {
        byte[] image = expected.clone();
        System.arraycopy(memory.bytes, 0, image, 0, INSTRUCTION_AREA);
        assertArrayEquals(image, memory.bytes);
    }

    // ── Utilidades de estado ─────────────────────────────────────────────────────────────────────

    private static byte[] zBytes(Aarch64Core core, int reg) {
        byte[] out = new byte[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (core.scalable().zWord(reg, i / 8) >>> ((i % 8) * 8));
        }
        return out;
    }

    private static void fillZRandom(Aarch64Core core, int reg, long seed) {
        Random random = new Random(seed);
        for (int w = 0; w < core.vectorLengthBytes() / 8; w++) {
            core.scalable().setZWord(reg, w, random.nextLong());
        }
    }

    /// Predicado com um bit por elemento ativo (na posição `e << esz`).
    private static void setPredicate(Aarch64Core core, int reg, int esz, boolean[] active) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
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
        Arrays.fill(active, true);
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

    private static int log2(int value) {
        return Integer.numberOfTrailingZeros(value);
    }

    /// Copia os `count` bytes baixos do elemento `element` (de `elementBytes` bytes) de `vector` para `image[address..]`.
    private static void putElement(byte[] image, long address, byte[] vector, int element, int elementBytes, int count) {
        for (int b = 0; b < count; b++) {
            image[(int) address + b] = vector[element * elementBytes + b];
        }
    }

    /// Ordem de `n` alvos DISTINTOS, embaralhada de forma determinística (offsets em ordem arbitrária).
    private static int[] permutation(int n, long seed) {
        Integer[] boxed = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        java.util.Collections.shuffle(Arrays.asList(boxed), new Random(seed));
        return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
    }

    private static void setElement(Aarch64Core core, int reg, int element, int elementBytes, long value) {
        int bitOffset = element * elementBytes * 8;
        int word = bitOffset / 64;
        int shift = bitOffset % 64;
        long current = core.scalable().zWord(reg, word);
        if (elementBytes == 8) {
            core.scalable().setZWord(reg, word, value);
            return;
        }
        long mask = ((1L << (elementBytes * 8)) - 1L) << shift;
        core.scalable().setZWord(reg, word, current & ~mask | (value << shift) & mask);
    }

    // ── ST1 / ST2 / ST3 / ST4 / STNT1 ────────────────────────────────────────────────────────────

    /// (palavra do `as`, registradores, bytes escritos por elemento, bytes do elemento do vetor, primeiro registrador,
    /// deslocamento do primeiro elemento em função de `VL/8`). `x2 = base`, `x3 = 5`.
    private static Stream<Object[]> structures() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> Stream.of(
                row(vl, 0xe4034440, 1, 1, 1, 0, vb -> 5L),        // st1b {z0.b}
                row(vl, 0xe4234440, 1, 1, 2, 0, vb -> 5L),        // st1b {z0.h}
                row(vl, 0xe4434440, 1, 1, 4, 0, vb -> 5L),        // st1b {z0.s}
                row(vl, 0xe4634440, 1, 1, 8, 0, vb -> 5L),        // st1b {z0.d}
                row(vl, 0xe4a34440, 1, 2, 2, 0, vb -> 10L),       // st1h {z0.h}
                row(vl, 0xe4c34440, 1, 2, 4, 0, vb -> 10L),       // st1h {z0.s}
                row(vl, 0xe4e34440, 1, 2, 8, 0, vb -> 10L),       // st1h {z0.d}
                row(vl, 0xe5434440, 1, 4, 4, 0, vb -> 20L),       // st1w {z0.s}
                row(vl, 0xe5634440, 1, 4, 8, 0, vb -> 20L),       // st1w {z0.d}
                row(vl, 0xe5e34440, 1, 8, 8, 0, vb -> 40L),       // st1d {z0.d}
                row(vl, 0xe5034440, 1, 4, 16, 0, vb -> 20L),      // st1w {z0.q}
                row(vl, 0xe5c34440, 1, 8, 16, 0, vb -> 40L),      // st1d {z0.q}
                row(vl, 0xe4036440, 1, 1, 1, 0, vb -> 5L),        // stnt1b (= st1b)
                row(vl, 0xe4836440, 1, 2, 2, 0, vb -> 10L),       // stnt1h
                row(vl, 0xe5036440, 1, 4, 4, 0, vb -> 20L),       // stnt1w
                row(vl, 0xe5836440, 1, 8, 8, 0, vb -> 40L),       // stnt1d
                row(vl, 0xe400e440, 1, 1, 1, 0, vb -> 0L),        // st1b {z0.b}, [x2]
                row(vl, 0xe421e440, 1, 1, 2, 0, vb -> vb / 2),    // st1b {z0.h}, #1, mul vl
                row(vl, 0xe448e440, 1, 1, 4, 0, vb -> -2 * vb),   // st1b {z0.s}, #-8, mul vl
                row(vl, 0xe467e440, 1, 1, 8, 0, vb -> 7 * vb / 8), // st1b {z0.d}, #7, mul vl
                row(vl, 0xe4a2e440, 1, 2, 2, 0, vb -> 2 * vb),    // st1h {z0.h}, #2, mul vl
                row(vl, 0xe4c2e440, 1, 2, 4, 0, vb -> vb),        // st1h {z0.s}, #2, mul vl
                row(vl, 0xe4e2e440, 1, 2, 8, 0, vb -> vb / 2),    // st1h {z0.d}, #2, mul vl
                row(vl, 0xe543e440, 1, 4, 4, 0, vb -> 3 * vb),    // st1w {z0.s}, #3, mul vl
                row(vl, 0xe563e440, 1, 4, 8, 0, vb -> 3 * vb / 2), // st1w {z0.d}, #3, mul vl
                row(vl, 0xe5ede440, 1, 8, 8, 0, vb -> -3 * vb),   // st1d {z0.d}, #-3, mul vl
                row(vl, 0xe548e440, 1, 4, 4, 0, vb -> -8 * vb),   // st1w {z0.s}, #-8, mul vl (mínimo)
                row(vl, 0xe547e440, 1, 4, 4, 0, vb -> 7 * vb),    // st1w {z0.s}, #7, mul vl (máximo)
                row(vl, 0xe502e440, 1, 4, 16, 0, vb -> vb / 2),   // st1w {z0.q}, #2, mul vl
                row(vl, 0xe5c2e440, 1, 8, 16, 0, vb -> vb),       // st1d {z0.q}, #2, mul vl
                row(vl, 0xe411e440, 1, 1, 1, 0, vb -> vb),        // stnt1b #1, mul vl
                row(vl, 0xe591e440, 1, 8, 8, 0, vb -> vb),        // stnt1d #1, mul vl
                row(vl, 0xe431e440, 2, 1, 1, 0, vb -> 2 * vb),    // st2b #2, mul vl
                row(vl, 0xe451e440, 3, 1, 1, 0, vb -> 3 * vb),    // st3b #3, mul vl
                row(vl, 0xe47ee440, 4, 1, 1, 0, vb -> -8 * vb),   // st4b #-8, mul vl
                row(vl, 0xe4b1e440, 2, 2, 2, 0, vb -> 2 * vb),    // st2h #2, mul vl
                row(vl, 0xe551e440, 3, 4, 4, 0, vb -> 3 * vb),    // st3w #3, mul vl
                row(vl, 0xe5f1e440, 4, 8, 8, 0, vb -> 4 * vb),    // st4d #4, mul vl
                row(vl, 0xe471e45f, 4, 1, 1, 31, vb -> 4 * vb),   // st4b {z31, z0, z1, z2}: dá a volta em Z31 → Z0
                row(vl, 0xe4236440, 2, 1, 1, 0, vb -> 5L),        // st2b, escalar+escalar
                row(vl, 0xe4c36440, 3, 2, 2, 0, vb -> 10L),       // st3h
                row(vl, 0xe5636440, 4, 4, 4, 0, vb -> 20L),       // st4w
                row(vl, 0xe5a3645e, 2, 8, 8, 30, vb -> 40L),      // st2d {z30, z31}
                row(vl, 0xe4410440, 2, 16, 16, 0, vb -> 2 * vb),  // st2q #2, mul vl
                row(vl, 0xe4810440, 3, 16, 16, 0, vb -> 3 * vb),  // st3q #3, mul vl
                row(vl, 0xe4cf0440, 4, 16, 16, 0, vb -> -4 * vb), // st4q #-4, mul vl
                row(vl, 0xe4630440, 2, 16, 16, 0, vb -> 5L * 16), // st2q, escalar+escalar
                row(vl, 0xe4a30440, 3, 16, 16, 0, vb -> 5L * 16), // st3q
                row(vl, 0xe4e30440, 4, 16, 16, 0, vb -> 5L * 16)  // st4q
        ));
    }

    private static Object[] row(int vl, int word, int registers, int memoryBytes, int elementBytes, int firstRegister,
            LongUnaryOperator offset) {
        return new Object[] {vl, word, registers, memoryBytes, elementBytes, firstRegister, offset};
    }

    @ParameterizedTest
    @MethodSource("structures")
    void storesInterleaveTheRightBytesAndLeaveEveryInactiveElementAndEveryOtherByteIntact(int vl, int word,
            int registers, int memoryBytes, int elementBytes, int firstRegister, LongUnaryOperator offset) {
        StoreMemory memory = randomMemory(word);
        Aarch64Core core = core(SVE2P1, vl, memory);
        int vectorBytes = core.vectorLengthBytes();
        int elements = vectorBytes / elementBytes;
        boolean[] active = randomActive(elements, word);
        setPredicate(core, P1, log2(elementBytes), active);
        byte[][] sources = new byte[registers][];
        for (int k = 0; k < registers; k++) {
            int register = (firstRegister + k) & 31;
            fillZRandom(core, register, word + 31L * k);
            sources[k] = zBytes(core, register);
        }
        core.setX(2, DATA);
        core.setX(3, 5);
        byte[] expected = memory.bytes.clone();
        long start = DATA + offset.applyAsLong(vectorBytes);
        for (int e = 0; e < elements; e++) {
            if (!active[e]) {
                continue;
            }
            for (int k = 0; k < registers; k++) {
                putElement(expected, start + ((long) e * registers + k) * memoryBytes, sources[k], e, elementBytes,
                        memoryBytes);
            }
        }
        run(SVE2P1, core, memory, word);
        assertMemory(expected, memory);
        for (int k = 0; k < registers; k++) {
            assertArrayEquals(sources[k], zBytes(core, (firstRegister + k) & 31), "o store não altera Z");
        }
        assertEquals(0x14L, core.pc());
    }

    @Test
    void inactiveElementsNeverTouchMemoryNotEvenTheUnmappedOnes() {
        StoreMemory memory = randomMemory(7);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[32];
        active[3] = true;
        setPredicate(core, P1, 0, active);
        core.setX(2, MAPPED_LIMIT - 4); // os elementos 4.. cairiam fora do mapa
        core.setX(3, 0);
        run(SVE, core, memory, 0xe4034440); // st1b {z0.b}, p1, [x2, x3]
        assertEquals(0x14L, core.pc(), "sem aborto: o elemento inativo fora do mapa não é escrito");
        assertEquals(List.of(MAPPED_LIMIT - 1), memory.written);
    }

    @Test
    void aStoreThatFaultsTakesTheDataAbort() {
        StoreMemory memory = randomMemory(8);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        core.setX(2, MAPPED_LIMIT - 16); // metade do vetor fora do mapa
        core.setX(3, 0);
        run(SVE, core, memory, 0xe4034440);
        assertEquals(HANDLER, core.pc(), "aborto real de dados");
    }

    @ParameterizedTest
    @ValueSource(ints = {0xe401e7e0, 0xe58047e0})
    void theStackPointerIsAValidBase(int word) {
        // st1b {z0.b}, p1, [sp, #1, mul vl] e str z0, [sp, #1, mul vl]: os dois escrevem um vetor em SP + VL.
        StoreMemory memory = randomMemory(9);
        Aarch64Core core = core(SVE, 256, memory);
        int vectorBytes = core.vectorLengthBytes();
        setPredicate(core, P1, 0, allActive(vectorBytes));
        fillZRandom(core, Z0, 5);
        core.setSp(DATA - vectorBytes);
        byte[] expected = memory.bytes.clone();
        System.arraycopy(zBytes(core, Z0), 0, expected, (int) DATA, vectorBytes);
        run(SVE, core, memory, word);
        assertEquals(0x14L, core.pc());
        assertMemory(expected, memory);
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void st1RoundTripsWithTheStructureLoadsOfB17_17(int vl) {
        int[][] pairs = { // ld, st, registradores, bytes do elemento
                {0xa423c440, 0xe4236440, 2, 1},  // ld2b / st2b
                {0xa4c3c440, 0xe4c36440, 3, 2},  // ld3h / st3h
                {0xa563c440, 0xe5636440, 4, 4}}; // ld4w / st4w
        for (int[] pair : pairs) {
            StoreMemory memory = randomMemory(pair[0]);
            Aarch64Core core = core(SVE, vl, memory);
            setPredicate(core, P1, log2(pair[3]), allActive(core.vectorLengthBytes() / pair[3]));
            core.setX(2, DATA);
            core.setX(3, 0);
            run(SVE, core, memory, pair[0]);
            int length = pair[2] * core.vectorLengthBytes();
            byte[] original = Arrays.copyOfRange(memory.bytes, (int) DATA, (int) DATA + length);
            core.setX(2, DATA + 0x400);
            run(SVE, core, memory, pair[1]);
            assertArrayEquals(original,
                    Arrays.copyOfRange(memory.bytes, (int) DATA + 0x400, (int) DATA + 0x400 + length));
        }
    }

    // ── STR ──────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void strWritesTheWholeVectorAtScaledOffsetsIncludingTheImmediateEnds(int vl) {
        // str z5, [x2, #-256, mul vl] / [x2, #255, mul vl] / [x2]
        int[] words = {0xe5a04045, 0xe59f5c45, 0xe5804045};
        long[] units = {-256L, 255L, 0L};
        for (int i = 0; i < words.length; i++) {
            StoreMemory memory = randomMemory(12);
            Aarch64Core core = core(SVE, vl, memory);
            int vectorBytes = core.vectorLengthBytes();
            fillZRandom(core, 5, 1);
            core.setX(2, DATA - units[i] * vectorBytes); // o alvo é DATA, mesmo com a base fora do mapa
            byte[] expected = memory.bytes.clone();
            System.arraycopy(zBytes(core, 5), 0, expected, (int) DATA, vectorBytes);
            run(SVE, core, memory, words[i]);
            assertMemory(expected, memory);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void strWritesTheWholePredicateAtAScaledOffset(int vl) {
        // str p5, [x2, #-3, mul vl]
        StoreMemory memory = randomMemory(13);
        Aarch64Core core = core(SVE, vl, memory);
        int predicateBytes = core.vectorLengthBytes() / 8;
        byte[] predicate = new byte[predicateBytes];
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setPWord(5, w, 0x0123456789abcdefL * (w + 3));
        }
        for (int i = 0; i < predicateBytes; i++) {
            predicate[i] = (byte) (core.scalable().pWord(5, i / 8) >>> ((i % 8) * 8));
        }
        core.setX(2, DATA + 3L * predicateBytes);
        byte[] expected = memory.bytes.clone();
        System.arraycopy(predicate, 0, expected, (int) DATA, predicateBytes);
        run(SVE, core, memory, 0xe5bf1445);
        assertMemory(expected, memory);
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void strRoundTripsWithLdr(int vl) {
        StoreMemory memory = randomMemory(14);
        Aarch64Core core = core(SVE, vl, memory);
        core.setX(2, DATA);
        run(SVE, core, memory, 0x85804045, 0x85800045); // ldr z5, [x2]; ldr p5, [x2]
        int vectorBytes = core.vectorLengthBytes();
        byte[] vector = Arrays.copyOfRange(memory.bytes, (int) DATA, (int) DATA + vectorBytes);
        byte[] predicate = Arrays.copyOfRange(memory.bytes, (int) DATA, (int) DATA + vectorBytes / 8);
        core.setX(2, DATA + 0x400);
        run(SVE, core, memory, 0xe5804045); // str z5, [x2]
        assertArrayEquals(vector, Arrays.copyOfRange(memory.bytes, (int) DATA + 0x400, (int) DATA + 0x400 + vectorBytes));
        core.setX(2, DATA + 0x600);
        run(SVE, core, memory, 0xe5800045); // str p5, [x2]
        assertArrayEquals(predicate,
                Arrays.copyOfRange(memory.bytes, (int) DATA + 0x600, (int) DATA + 0x600 + vectorBytes / 8));
    }

    // ── Scatter ──────────────────────────────────────────────────────────────────────────────────

    /// (palavra, bytes do elemento do vetor, bytes escritos por elemento, extensão do deslocamento, escalado).
    /// `x2 = base`, `Z3 = deslocamentos`, `Z0 = dado`.
    private static Stream<Object[]> scatters() {
        int u = SveMemoryOp64.Store.OFFSET_UXTW;
        int s = SveMemoryOp64.Store.OFFSET_SXTW;
        int d = SveMemoryOp64.Store.OFFSET_64;
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> Stream.of(
                scatter(vl, 0xe4438440, 4, 1, u, false), // st1b {z0.s}, [x2, z3.s, uxtw]
                scatter(vl, 0xe443c440, 4, 1, s, false), // st1b {z0.s}, [x2, z3.s, sxtw]
                scatter(vl, 0xe4c38440, 4, 2, u, false), // st1h {z0.s}, [x2, z3.s, uxtw]
                scatter(vl, 0xe4e3c440, 4, 2, s, true),  // st1h {z0.s}, [x2, z3.s, sxtw #1]
                scatter(vl, 0xe4e38440, 4, 2, u, true),  // st1h {z0.s}, [x2, z3.s, uxtw #1]
                scatter(vl, 0xe5438440, 4, 4, u, false), // st1w {z0.s}, [x2, z3.s, uxtw]
                scatter(vl, 0xe563c440, 4, 4, s, true),  // st1w {z0.s}, [x2, z3.s, sxtw #2]
                scatter(vl, 0xe5638440, 4, 4, u, true),  // st1w {z0.s}, [x2, z3.s, uxtw #2]
                scatter(vl, 0xe4038440, 8, 1, u, false), // st1b {z0.d}, [x2, z3.d, uxtw]
                scatter(vl, 0xe403c440, 8, 1, s, false), // st1b {z0.d}, [x2, z3.d, sxtw]
                scatter(vl, 0xe4a38440, 8, 2, u, true),  // st1h {z0.d}, [x2, z3.d, uxtw #1]
                scatter(vl, 0xe4a3c440, 8, 2, s, true),  // st1h {z0.d}, [x2, z3.d, sxtw #1]
                scatter(vl, 0xe5238440, 8, 4, u, true),  // st1w {z0.d}, [x2, z3.d, uxtw #2]
                scatter(vl, 0xe5a3c440, 8, 8, s, true),  // st1d {z0.d}, [x2, z3.d, sxtw #3]
                scatter(vl, 0xe5838440, 8, 8, u, false), // st1d {z0.d}, [x2, z3.d, uxtw]
                scatter(vl, 0xe403a440, 8, 1, d, false), // st1b {z0.d}, [x2, z3.d]
                scatter(vl, 0xe483a440, 8, 2, d, false), // st1h {z0.d}, [x2, z3.d]
                scatter(vl, 0xe4a3a440, 8, 2, d, true),  // st1h {z0.d}, [x2, z3.d, lsl #1]
                scatter(vl, 0xe503a440, 8, 4, d, false), // st1w {z0.d}, [x2, z3.d]
                scatter(vl, 0xe523a440, 8, 4, d, true),  // st1w {z0.d}, [x2, z3.d, lsl #2]
                scatter(vl, 0xe583a440, 8, 8, d, false), // st1d {z0.d}, [x2, z3.d]
                scatter(vl, 0xe5a3a440, 8, 8, d, true))); // st1d {z0.d}, [x2, z3.d, lsl #3]
    }

    private static Object[] scatter(int vl, int word, int elementBytes, int memoryBytes, int extend, boolean scaled) {
        return new Object[] {vl, word, elementBytes, memoryBytes, extend, scaled};
    }

    @ParameterizedTest
    @MethodSource("scatters")
    void scatterWritesEachActiveElementAtItsOwnAddressInAnyOrder(int vl, int word, int elementBytes, int memoryBytes,
            int extend, boolean scaled) {
        StoreMemory memory = randomMemory(word);
        Aarch64Core core = core(SVE, vl, memory);
        int elements = core.vectorLengthBytes() / elementBytes;
        boolean[] active = randomActive(elements, word);
        setPredicate(core, P1, log2(elementBytes), active);
        fillZRandom(core, Z0, word);
        byte[] source = zBytes(core, Z0);
        int shift = scaled ? log2(memoryBytes) : 0;
        int[] order = permutation(elements, word);
        Random garbage = new Random(word);
        long[] target = new long[elements];
        for (int e = 0; e < elements; e++) {
            target[e] = order[e] * 16L; // alvos distintos e sem sobreposição
            long offset = target[e] >> shift;
            // Com deslocamento de 32 bits em elemento de 64, os 32 bits ALTOS são lixo que não pode contar.
            long value = extend == SveMemoryOp64.Store.OFFSET_64 || elementBytes == 4 ? offset
                    : offset | (garbage.nextLong() << 32);
            setElement(core, Z3, e, elementBytes, value);
        }
        core.setX(2, DATA);
        byte[] expected = memory.bytes.clone();
        for (int e = 0; e < elements; e++) {
            if (active[e]) {
                putElement(expected, DATA + target[e], source, e, elementBytes, memoryBytes);
            }
        }
        run(SVE, core, memory, word);
        assertMemory(expected, memory);
        assertEquals(0x14L, core.pc());
    }

    @Test
    void sxtwAndUxtwDifferForANegative32BitOffset() {
        int sxtw = 0xe443c440; // st1b {z0.s}, p1, [x2, z3.s, sxtw]
        int uxtw = 0xe4438440; // st1b {z0.s}, p1, [x2, z3.s, uxtw]
        for (int word : new int[] {sxtw, uxtw}) {
            StoreMemory memory = randomMemory(21);
            Aarch64Core core = core(SVE, 256, memory);
            setPredicate(core, P1, 2, allActive(8));
            fillZRandom(core, Z0, 22);
            for (int e = 0; e < 8; e++) {
                setElement(core, Z3, e, 4, (-16L * (e + 1)) & 0xFFFFFFFFL);
            }
            core.setX(2, DATA + 0x400);
            byte[] source = zBytes(core, Z0);
            byte[] expected = memory.bytes.clone();
            if (word == sxtw) {
                for (int e = 0; e < 8; e++) {
                    expected[(int) (DATA + 0x400 - 16L * (e + 1))] = source[e * 4];
                }
            }
            run(SVE, core, memory, word);
            assertMemory(expected, memory);
            assertEquals(word == sxtw ? 0x14L : HANDLER, core.pc(),
                    word == sxtw ? "sxtw: a base menos o deslocamento" : "uxtw: 0xFFFFFFxx cai fora do mapa");
        }
    }

    @Test
    void whenTwoActiveElementsHitTheSameAddressTheHigherElementWins() {
        StoreMemory memory = randomMemory(23);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = {true, true, false, true}; // o elemento 3 é o último ativo
        setPredicate(core, P1, 3, active);
        fillZRandom(core, Z0, 24);
        for (int e = 0; e < 4; e++) {
            setElement(core, Z3, e, 8, 0);
        }
        core.setX(2, DATA);
        byte[] source = zBytes(core, Z0);
        byte[] expected = memory.bytes.clone();
        System.arraycopy(source, 3 * 8, expected, (int) DATA, 8);
        run(SVE, core, memory, 0xe583a440); // st1d {z0.d}, p1, [x2, z3.d]
        assertMemory(expected, memory);
    }

    @Test
    void inactiveScatterElementsNeverTouchMemoryEvenWithUnmappedOffsets() {
        StoreMemory memory = randomMemory(25);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[4];
        active[1] = true;
        setPredicate(core, P1, 3, active);
        for (int e = 0; e < 4; e++) {
            setElement(core, Z3, e, 8, e == 1 ? 0x20 : 0x7fffffffffffff00L);
        }
        core.setX(2, DATA);
        run(SVE, core, memory, 0xe583a440);
        assertEquals(0x14L, core.pc());
        assertEquals(8, memory.written.size());
        assertEquals(DATA + 0x20, memory.written.get(0));
    }

    /// (palavra, bytes do elemento, bytes escritos, imediato sem escala).
    private static Stream<Object[]> vectorPlusImmediate() {
        return IntStream.of(VECTOR_LENGTHS).boxed().flatMap(vl -> Stream.of(
                new Object[] {vl, 0xe47fa460, 4, 1, 31},    // st1b {z0.s}, [z3.s, #31]
                new Object[] {vl, 0xe4ffa460, 4, 2, 62},    // st1h {z0.s}, [z3.s, #62]
                new Object[] {vl, 0xe57fa460, 4, 4, 124},   // st1w {z0.s}, [z3.s, #124]
                new Object[] {vl, 0xe445a460, 8, 1, 5},     // st1b {z0.d}, [z3.d, #5]
                new Object[] {vl, 0xe4dfa460, 8, 2, 62},    // st1h {z0.d}, [z3.d, #62]
                new Object[] {vl, 0xe55fa460, 8, 4, 124},   // st1w {z0.d}, [z3.d, #124]
                new Object[] {vl, 0xe5dfa460, 8, 8, 248},   // st1d {z0.d}, [z3.d, #248]
                new Object[] {vl, 0xe5c0a460, 8, 8, 0}));   // st1d {z0.d}, [z3.d]
    }

    @ParameterizedTest
    @MethodSource("vectorPlusImmediate")
    void vectorPlusImmediateAddsTheScaledImmediateToEachElementOfTheBaseVector(int vl, int word, int elementBytes,
            int memoryBytes, int immediate) {
        StoreMemory memory = randomMemory(word);
        Aarch64Core core = core(SVE, vl, memory);
        int elements = core.vectorLengthBytes() / elementBytes;
        boolean[] active = randomActive(elements, word);
        setPredicate(core, P1, log2(elementBytes), active);
        fillZRandom(core, Z0, word);
        byte[] source = zBytes(core, Z0);
        int[] order = permutation(elements, word);
        byte[] expected = memory.bytes.clone();
        for (int e = 0; e < elements; e++) {
            long target = DATA + order[e] * 16L;
            setElement(core, Z3, e, elementBytes, target - immediate);
            if (active[e]) {
                putElement(expected, target, source, e, elementBytes, memoryBytes);
            }
        }
        run(SVE, core, memory, word);
        assertMemory(expected, memory);
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void st1qUsesAVectorBaseAndAScalarOffsetPerQuadword(int vl) {
        StoreMemory memory = randomMemory(31);
        Aarch64Core core = core(SVE2P1, vl, memory);
        int segments = core.vectorLengthBytes() / 16;
        boolean[] active = randomActive(segments, 31);
        setPredicate(core, P1, 4, active);
        fillZRandom(core, Z0, 32);
        byte[] source = zBytes(core, Z0);
        int[] order = permutation(segments, 33);
        byte[] expected = memory.bytes.clone();
        long offset = 0x40;
        for (int s = 0; s < segments; s++) {
            long target = DATA + order[s] * 32L;
            core.scalable().setZWord(Z3, 2 * s, target - offset);
            core.scalable().setZWord(Z3, 2 * s + 1, 0x7fffffffffffffffL); // a metade alta do segmento não é usada
            if (active[s]) {
                putElement(expected, target, source, s, 16, 16);
            }
        }
        core.setX(4, offset);
        run(SVE2P1, core, memory, 0xe4242460); // st1q {z0.q}, p1, [z3.d, x4]
        assertMemory(expected, memory);
    }

    @Test
    void st1qWithRm31UsesXzrAsTheOffset() {
        StoreMemory memory = randomMemory(34);
        Aarch64Core core = core(SVE2P1, 256, memory);
        setPredicate(core, P1, 4, allActive(2));
        fillZRandom(core, Z0, 35);
        core.scalable().setZWord(Z3, 0, DATA);
        core.scalable().setZWord(Z3, 2, DATA + 0x100);
        core.setX(4, 0x999); // não pode influenciar
        byte[] source = zBytes(core, Z0);
        byte[] expected = memory.bytes.clone();
        putElement(expected, DATA, source, 0, 16, 16);
        putElement(expected, DATA + 0x100, source, 1, 16, 16);
        run(SVE2P1, core, memory, 0xe43f2460); // st1q {z0.q}, p1, [z3.d]
        assertMemory(expected, memory);
    }

    // ── Decode: feature, recusas (G8), modo streaming, lifter, acesso ────────────────────────────

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        StoreMemory memory = new StoreMemory();
        memory.poke32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static boolean decodes(Aarch64Architecture architecture, int word) {
        return decodeOrNull(architecture, word) instanceof SveMemoryOp64.Store;
    }

    @Test
    void theSve2p1FormsNeedFeatSve2p1() {
        for (int word : new int[] {0xe5034440, 0xe5c34440, 0xe502e440, 0xe4410440, 0xe4630440, 0xe4242460}) {
            assertFalse(decodes(SVE, word), "sem SVE2p1: " + Integer.toHexString(word));
            assertTrue(decodes(SVE2P1, word), "com SVE2p1: " + Integer.toHexString(word));
        }
        assertTrue(decodes(SVE, 0xe4034440));
        assertTrue(decodes(SVE, 0xe4438440));
    }

    @Test
    void theHolesInTheDecodetreeAreRefused() {
        assertFalse(decodes(SVE2P1, 0xe4834440), "st1h com esz = 0 (msz > esz)");
        assertFalse(decodes(SVE2P1, 0xe480e440), "st1h com esz = 0, imediato (msz > esz)");
        assertFalse(decodes(SVE2P1, 0xe5204440), "msz = 2 com bits[22:21] = 01");
        assertFalse(decodes(SVE2P1, 0xe41f4440), "st1b com Rm = 31");
        assertFalse(decodes(SVE2P1, 0xe41f6440), "stnt1b com Rm = 31");
        assertFalse(decodes(SVE2P1, 0xe47f0440), "st2q com Rm = 31");
        assertFalse(decodes(SVE2P1, 0xe5bf1455), "str p com o bit 4 ligado");
        assertFalse(decodes(SVE2P1, 0xe5c00400), "opcode 000 com bits[24:22] = 111");
        assertFalse(decodes(SVE2P1, 0xe4000440), "opcode 000 com nreg = 0");
        assertFalse(decodes(SVE2P1, 0xe5000400), "opcode 000 com bits[24:23] = 10");
        assertFalse(decodes(SVE2P1, 0xe4510440), "st[234]q imediato com o bit 20 ligado");
        assertFalse(decodes(SVE2P1, 0xe4638440), "scatter de 32 bits com msz = 0 escalado");
        assertFalse(decodes(SVE2P1, 0xe5e38440), "scatter de 32 bits com msz = 3 > esz = 2");
        assertFalse(decodes(SVE2P1, 0xe5ffa460), "vetor + imediato de 32 bits com msz = 3 > esz = 2");
        assertFalse(decodes(SVE2P1, 0xe4602440), "opcode 001 com bits[22:21] = 11 (nem ST1Q nem STNT1)");
        assertFalse(decodes(SVE2P1, 0xe49f6440), "stnt1h com Rm = 31");
    }

    @ParameterizedTest
    @ValueSource(ints = {0xe4438440, 0xe443c440, 0xe403a440, 0xe47fa460, 0xe5034440, 0xe4242460})
    void scatterAndTheWideElementFormsAreUndefinedInStreamingMode(int word) {
        StoreMemory memory = randomMemory(41);
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Architecture withSve2p1 = Aarch64Architecture.extending(architecture, "teste-SVE2p1", Aarch64Feature.SVE2_1);
        Aarch64Core core = new Aarch64Core(memory, withSve2p1, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(withSve2p1, core, memory, SMSTART_SM);
        memory.poke32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(withSve2p1).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
    }

    @ParameterizedTest
    @ValueSource(ints = {0xe4034440, 0xe4236440, 0xe5804045, 0xe5800045})
    void contiguousStoresAndStrAreLegalInStreamingModeAtTheStreamingVectorLength(int word) {
        StoreMemory memory = randomMemory(42);
        Aarch64Architecture architecture = Aarch64Architecture.ARMV9_2_A;
        Aarch64Core core = new Aarch64Core(memory, architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, memory, SMSTART_SM);
        setPredicate(core, P1, 0, allActive(64));
        fillZRandom(core, Z0, 43);
        fillZRandom(core, 5, 44);
        core.setX(2, DATA);
        core.setX(3, 0);
        memory.poke32(0x10, word);
        core.setProgramCounter(0x10);
        memory.written.clear();
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(0x14L, core.pc());
        boolean predicateStore = word == 0xe5800045;
        assertEquals(predicateStore ? 8 : 64 * (word == 0xe4236440 ? 2 : 1), memory.written.size(),
                "SVL = 512: 64 bytes por vetor");
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        StoreMemory memory = randomMemory(45);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 0, allActive(32));
        fillZRandom(core, Z0, 46);
        core.setX(2, DATA);
        core.setX(3, 5);
        memory.poke32(0, 0xe4034440);
        byte[] expected = memory.bytes.clone();
        System.arraycopy(zBytes(core, Z0), 0, expected, (int) DATA + 5, 32);
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(memory, 0, 1);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        assertMemory(expected, memory);
        assertInstanceOf(SveMemoryOp64.Store.class, decodeOrNull(SVE, 0xe4034440));
    }

    /// `CPACR_EL1.ZEN` negando SVE: a exceção é tomada e NENHUM byte é escrito.
    @ParameterizedTest
    @ValueSource(ints = {0xe4034440, 0xe4438440, 0xe5804045, 0xe5800045, 0xe431e440, 0xe47fa460})
    void everyStoreTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        StoreMemory memory = randomMemory(47);
        Aarch64Core core = core(SVE, 256, memory);
        core.setSystemRegisterBus(new dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus() {
            @Override
            public boolean handles(dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId register) {
                return register == dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.CPACR_EL1;
            }

            @Override
            public long read(dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId register) {
                return 0L; // CPACR_EL1 = 0: ZEN = 00
            }

            @Override
            public void write(dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId register, long newValue) {
                throw new UnsupportedOperationException();
            }
        });
        setPredicate(core, P1, 0, allActive(32));
        core.setX(2, DATA);
        core.setX(3, 0);
        run(SVE, core, memory, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(0x19L, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC = acesso SVE");
        assertTrue(memory.written.isEmpty());
    }
}
