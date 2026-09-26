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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.19 — gather load SVE (`LD1_zprz`/`LD1_zpiz`/`LD1Q`, cada um em `LD1*` e first-fault `LDFF1*`). Palavras conferidas
/// contra `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1+f64mm`).
///
/// O oráculo NÃO passa pelas tabelas do decoder: o tamanho do acesso, a extensão, a forma do deslocamento e o `ff` de cada
/// palavra vêm do NOME do mnemônico (`ldff1sh {z0.s}, p1/z, [x2, z3.s, sxtw #1]`), e o resultado é comparado com bytes
/// lidos direto do array da memória de teste. A cobertura do decoder é exaustiva: as 16384 palavras dos dois prefixos são
/// decodificadas e o conjunto aceito é comparado com os padrões do `sve.decode` transcritos literalmente neste arquivo.
class Aarch64SveGatherTest {
    private static final int P1 = 1;
    private static final int Z0 = 0;
    private static final int Z3 = 3;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_UNKNOWN = 0L;
    private static final long MAPPED_LIMIT = 0x2000L;
    private static final long DATA = 0x1800L;
    private static final long DATA_REGION = 0x1000L;
    private static final long UNMAPPED_OFFSET = 0x7FFF_0000L;
    private static final int SLOT_BYTES = 16;
    private static final long JUNK = 0x2222_2222_2222_2222L;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2P1 = Aarch64Architecture.extending(
            Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2), "teste-SVE2p1", Aarch64Feature.SVE2_1);
    private static final int SMSTART_SM = 0xd503437f;

    private static final int LD1W_UXTW = 0x85034440;
    private static final int LD1W_SXTW = 0x85434440;
    private static final int LD1D_UXTW_SCALED = 0xc5a34440;
    private static final int LD1D_SXTW_SCALED = 0xc5e34440;
    private static final int LDFF1W_UXTW = 0x85036440;
    private static final int LDFF1D_64 = 0xc5c3e440;
    private static final int LD1D_64 = 0xc5c3c440;
    private static final int LDFF1SW_IMMEDIATE = 0xc521a460;
    private static final int LD1W_OVERLAP = 0x85234443;
    private static final int LD1D_IMMEDIATE_OVERLAP = 0xc5a1c463;
    private static final int LDFF1D_OVERLAP = 0xc5e3e443;
    private static final int LD1B_SP_BASE = 0x840347e0;
    private static final int LD1Q = 0xc402a460;
    private static final int LD1Q_XZR = 0xc41fa460;
    private static final int LD1Q_OVERLAP = 0xc402a463;

    // Formas de endereçamento de `Form`.
    private static final int UXTW = 0;
    private static final int SXTW = 1;
    private static final int OFFSET_64 = 2;
    private static final int VECTOR_IMMEDIATE = 3;

    /// Memória que só existe abaixo de `MAPPED_LIMIT`: acima, todo acesso levanta a falta de tradução (como uma MMU).
    /// Registra UMA vez o endereço de cada leitura de dados (`touched`), inclusive a que falha.
    private static final class GatherMemory implements AddressSpace64 {
        final byte[] bytes = new byte[(int) MAPPED_LIMIT];
        final List<Long> touched = new ArrayList<>();

        private void check(long address, int size) {
            if (address >= DATA_REGION) {
                touched.add(address);
            }
            if (address < 0 || address + size > MAPPED_LIMIT) {
                throw new MemoryTranslationException64(address, MemoryAccessType.DATA_READ,
                        FaultStatus64.translationFault(3));
            }
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
            check(address, 1);
            return (int) raw(address, 1);
        }

        @Override
        public int read16(long address) {
            check(address, 2);
            return (int) raw(address, 2);
        }

        @Override
        public int read32(long address) {
            check(address, 4);
            return (int) raw(address, 4);
        }

        @Override
        public long read64(long address) {
            check(address, 8);
            return raw(address, 8);
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

    private static Aarch64Core core(Aarch64Architecture architecture, int vl, GatherMemory memory) {
        Aarch64Core core = new Aarch64Core(memory, architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static GatherMemory randomMemory(long seed) {
        GatherMemory memory = new GatherMemory();
        new Random(seed).nextBytes(memory.bytes);
        return memory;
    }

    /// Escreve as palavras a partir de `0x10` e executa uma instrução por palavra. `touched` é zerado antes: só o acesso
    /// aos dados (`0x1000`+) aparece nele. Um load nunca escreve na memória.
    private static void run(Aarch64Architecture architecture, Aarch64Core core, GatherMemory memory, int... words) {
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
        for (int i = (int) DATA_REGION; i < saved.length; i++) {
            assertEquals(saved[i], memory.bytes[i], "um load não escreve na memória");
        }
    }

    // ── Utilidades de estado ─────────────────────────────────────────────────────────────────────

    private static byte[] zBytes(Aarch64Core core, int reg) {
        byte[] out = new byte[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (core.scalable().zWord(reg, i / 8) >>> ((i % 8) * 8));
        }
        return out;
    }

    private static void fillZ(Aarch64Core core, int reg, long value) {
        for (int w = 0; w < core.vectorLengthBytes() / 8; w++) {
            core.scalable().setZWord(reg, w, value);
        }
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

    private static void setFfr(Aarch64Core core, long value) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            core.scalable().setFfrWord(w, value);
        }
    }

    private static void assertFfr(long expected, Aarch64Core core, String message) {
        for (int w = 0; w < core.scalable().wordsPerPredicate(); w++) {
            assertEquals(expected, core.scalable().ffrWord(w), message + " (palavra " + w + ")");
        }
    }

    private static int[] permutation(int n, long seed) {
        Integer[] boxed = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        Collections.shuffle(Arrays.asList(boxed), new Random(seed));
        return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
    }

    /// Valor de `size` bytes na memória, com a extensão pedida.
    private static long load(GatherMemory memory, long address, int size, boolean signed) {
        long value = memory.raw(address, size);
        if (!signed || size == 8) {
            return value;
        }
        int shift = 64 - size * 8;
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

    // ── A tabela: cada palavra nomeada pelo mnemônico ────────────────────────────────────────────

    /// Uma forma de gather: (palavra, log2 do acesso, log2 do elemento, com sinal, first-fault, forma do endereço, com
    /// escala, `imm5`). Forma do endereço: `UXTW`, `SXTW`, `OFFSET_64` ou `VECTOR_IMMEDIATE`.
    private record Form(int word, int msz, int esz, boolean signed, boolean firstFault, int addressing, boolean scaled,
            int imm5) {
    }

    private static Form row(int word, int msz, int esz, boolean signed, boolean firstFault, int addressing,
            boolean scaled, int imm5) {
        return new Form(word, msz, esz, signed, firstFault, addressing, scaled, imm5);
    }

    private static final Form[] FORMS = {
                row(0x84034440, 0, 2, false, false, 0, false, 0), // ld1b {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84434440, 0, 2, false, false, 1, false, 0), // ld1b {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x8420c460, 0, 2, false, false, 3, false, 0), // ld1b {z0.s}, p1/z, [z3.s]
                row(0x8425c460, 0, 2, false, false, 3, false, 5), // ld1b {z0.s}, p1/z, [z3.s, #5]
                row(0x843fc460, 0, 2, false, false, 3, false, 31), // ld1b {z0.s}, p1/z, [z3.s, #31]
                row(0x84030440, 0, 2, true, false, 0, false, 0), // ld1sb {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84430440, 0, 2, true, false, 1, false, 0), // ld1sb {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x84208460, 0, 2, true, false, 3, false, 0), // ld1sb {z0.s}, p1/z, [z3.s]
                row(0x84258460, 0, 2, true, false, 3, false, 5), // ld1sb {z0.s}, p1/z, [z3.s, #5]
                row(0x843f8460, 0, 2, true, false, 3, false, 31), // ld1sb {z0.s}, p1/z, [z3.s, #31]
                row(0x84834440, 1, 2, false, false, 0, false, 0), // ld1h {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84a34440, 1, 2, false, false, 0, true, 0), // ld1h {z0.s}, p1/z, [x2, z3.s, uxtw #1]
                row(0x84c34440, 1, 2, false, false, 1, false, 0), // ld1h {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x84e34440, 1, 2, false, false, 1, true, 0), // ld1h {z0.s}, p1/z, [x2, z3.s, sxtw #1]
                row(0x84a0c460, 1, 2, false, false, 3, false, 0), // ld1h {z0.s}, p1/z, [z3.s]
                row(0x84a5c460, 1, 2, false, false, 3, false, 5), // ld1h {z0.s}, p1/z, [z3.s, #10]
                row(0x84bfc460, 1, 2, false, false, 3, false, 31), // ld1h {z0.s}, p1/z, [z3.s, #62]
                row(0x84830440, 1, 2, true, false, 0, false, 0), // ld1sh {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84a30440, 1, 2, true, false, 0, true, 0), // ld1sh {z0.s}, p1/z, [x2, z3.s, uxtw #1]
                row(0x84c30440, 1, 2, true, false, 1, false, 0), // ld1sh {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x84e30440, 1, 2, true, false, 1, true, 0), // ld1sh {z0.s}, p1/z, [x2, z3.s, sxtw #1]
                row(0x84a08460, 1, 2, true, false, 3, false, 0), // ld1sh {z0.s}, p1/z, [z3.s]
                row(0x84a58460, 1, 2, true, false, 3, false, 5), // ld1sh {z0.s}, p1/z, [z3.s, #10]
                row(0x84bf8460, 1, 2, true, false, 3, false, 31), // ld1sh {z0.s}, p1/z, [z3.s, #62]
                row(0x85034440, 2, 2, false, false, 0, false, 0), // ld1w {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x85234440, 2, 2, false, false, 0, true, 0), // ld1w {z0.s}, p1/z, [x2, z3.s, uxtw #2]
                row(0x85434440, 2, 2, false, false, 1, false, 0), // ld1w {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x85634440, 2, 2, false, false, 1, true, 0), // ld1w {z0.s}, p1/z, [x2, z3.s, sxtw #2]
                row(0x8520c460, 2, 2, false, false, 3, false, 0), // ld1w {z0.s}, p1/z, [z3.s]
                row(0x8525c460, 2, 2, false, false, 3, false, 5), // ld1w {z0.s}, p1/z, [z3.s, #20]
                row(0x853fc460, 2, 2, false, false, 3, false, 31), // ld1w {z0.s}, p1/z, [z3.s, #124]
                row(0xc4034440, 0, 3, false, false, 0, false, 0), // ld1b {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4434440, 0, 3, false, false, 1, false, 0), // ld1b {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc443c440, 0, 3, false, false, 2, false, 0), // ld1b {z0.d}, p1/z, [x2, z3.d]
                row(0xc420c460, 0, 3, false, false, 3, false, 0), // ld1b {z0.d}, p1/z, [z3.d]
                row(0xc425c460, 0, 3, false, false, 3, false, 5), // ld1b {z0.d}, p1/z, [z3.d, #5]
                row(0xc43fc460, 0, 3, false, false, 3, false, 31), // ld1b {z0.d}, p1/z, [z3.d, #31]
                row(0xc4030440, 0, 3, true, false, 0, false, 0), // ld1sb {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4430440, 0, 3, true, false, 1, false, 0), // ld1sb {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc4438440, 0, 3, true, false, 2, false, 0), // ld1sb {z0.d}, p1/z, [x2, z3.d]
                row(0xc4208460, 0, 3, true, false, 3, false, 0), // ld1sb {z0.d}, p1/z, [z3.d]
                row(0xc4258460, 0, 3, true, false, 3, false, 5), // ld1sb {z0.d}, p1/z, [z3.d, #5]
                row(0xc43f8460, 0, 3, true, false, 3, false, 31), // ld1sb {z0.d}, p1/z, [z3.d, #31]
                row(0xc4834440, 1, 3, false, false, 0, false, 0), // ld1h {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4a34440, 1, 3, false, false, 0, true, 0), // ld1h {z0.d}, p1/z, [x2, z3.d, uxtw #1]
                row(0xc4c34440, 1, 3, false, false, 1, false, 0), // ld1h {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc4e34440, 1, 3, false, false, 1, true, 0), // ld1h {z0.d}, p1/z, [x2, z3.d, sxtw #1]
                row(0xc4c3c440, 1, 3, false, false, 2, false, 0), // ld1h {z0.d}, p1/z, [x2, z3.d]
                row(0xc4e3c440, 1, 3, false, false, 2, true, 0), // ld1h {z0.d}, p1/z, [x2, z3.d, lsl #1]
                row(0xc4a0c460, 1, 3, false, false, 3, false, 0), // ld1h {z0.d}, p1/z, [z3.d]
                row(0xc4a5c460, 1, 3, false, false, 3, false, 5), // ld1h {z0.d}, p1/z, [z3.d, #10]
                row(0xc4bfc460, 1, 3, false, false, 3, false, 31), // ld1h {z0.d}, p1/z, [z3.d, #62]
                row(0xc4830440, 1, 3, true, false, 0, false, 0), // ld1sh {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4a30440, 1, 3, true, false, 0, true, 0), // ld1sh {z0.d}, p1/z, [x2, z3.d, uxtw #1]
                row(0xc4c30440, 1, 3, true, false, 1, false, 0), // ld1sh {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc4e30440, 1, 3, true, false, 1, true, 0), // ld1sh {z0.d}, p1/z, [x2, z3.d, sxtw #1]
                row(0xc4c38440, 1, 3, true, false, 2, false, 0), // ld1sh {z0.d}, p1/z, [x2, z3.d]
                row(0xc4e38440, 1, 3, true, false, 2, true, 0), // ld1sh {z0.d}, p1/z, [x2, z3.d, lsl #1]
                row(0xc4a08460, 1, 3, true, false, 3, false, 0), // ld1sh {z0.d}, p1/z, [z3.d]
                row(0xc4a58460, 1, 3, true, false, 3, false, 5), // ld1sh {z0.d}, p1/z, [z3.d, #10]
                row(0xc4bf8460, 1, 3, true, false, 3, false, 31), // ld1sh {z0.d}, p1/z, [z3.d, #62]
                row(0xc5034440, 2, 3, false, false, 0, false, 0), // ld1w {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc5234440, 2, 3, false, false, 0, true, 0), // ld1w {z0.d}, p1/z, [x2, z3.d, uxtw #2]
                row(0xc5434440, 2, 3, false, false, 1, false, 0), // ld1w {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc5634440, 2, 3, false, false, 1, true, 0), // ld1w {z0.d}, p1/z, [x2, z3.d, sxtw #2]
                row(0xc543c440, 2, 3, false, false, 2, false, 0), // ld1w {z0.d}, p1/z, [x2, z3.d]
                row(0xc563c440, 2, 3, false, false, 2, true, 0), // ld1w {z0.d}, p1/z, [x2, z3.d, lsl #2]
                row(0xc520c460, 2, 3, false, false, 3, false, 0), // ld1w {z0.d}, p1/z, [z3.d]
                row(0xc525c460, 2, 3, false, false, 3, false, 5), // ld1w {z0.d}, p1/z, [z3.d, #20]
                row(0xc53fc460, 2, 3, false, false, 3, false, 31), // ld1w {z0.d}, p1/z, [z3.d, #124]
                row(0xc5030440, 2, 3, true, false, 0, false, 0), // ld1sw {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc5230440, 2, 3, true, false, 0, true, 0), // ld1sw {z0.d}, p1/z, [x2, z3.d, uxtw #2]
                row(0xc5430440, 2, 3, true, false, 1, false, 0), // ld1sw {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc5630440, 2, 3, true, false, 1, true, 0), // ld1sw {z0.d}, p1/z, [x2, z3.d, sxtw #2]
                row(0xc5438440, 2, 3, true, false, 2, false, 0), // ld1sw {z0.d}, p1/z, [x2, z3.d]
                row(0xc5638440, 2, 3, true, false, 2, true, 0), // ld1sw {z0.d}, p1/z, [x2, z3.d, lsl #2]
                row(0xc5208460, 2, 3, true, false, 3, false, 0), // ld1sw {z0.d}, p1/z, [z3.d]
                row(0xc5258460, 2, 3, true, false, 3, false, 5), // ld1sw {z0.d}, p1/z, [z3.d, #20]
                row(0xc53f8460, 2, 3, true, false, 3, false, 31), // ld1sw {z0.d}, p1/z, [z3.d, #124]
                row(0xc5834440, 3, 3, false, false, 0, false, 0), // ld1d {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc5a34440, 3, 3, false, false, 0, true, 0), // ld1d {z0.d}, p1/z, [x2, z3.d, uxtw #3]
                row(0xc5c34440, 3, 3, false, false, 1, false, 0), // ld1d {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc5e34440, 3, 3, false, false, 1, true, 0), // ld1d {z0.d}, p1/z, [x2, z3.d, sxtw #3]
                row(0xc5c3c440, 3, 3, false, false, 2, false, 0), // ld1d {z0.d}, p1/z, [x2, z3.d]
                row(0xc5e3c440, 3, 3, false, false, 2, true, 0), // ld1d {z0.d}, p1/z, [x2, z3.d, lsl #3]
                row(0xc5a0c460, 3, 3, false, false, 3, false, 0), // ld1d {z0.d}, p1/z, [z3.d]
                row(0xc5a5c460, 3, 3, false, false, 3, false, 5), // ld1d {z0.d}, p1/z, [z3.d, #40]
                row(0xc5bfc460, 3, 3, false, false, 3, false, 31), // ld1d {z0.d}, p1/z, [z3.d, #248]
                row(0x84036440, 0, 2, false, true, 0, false, 0), // ldff1b {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84436440, 0, 2, false, true, 1, false, 0), // ldff1b {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x8420e460, 0, 2, false, true, 3, false, 0), // ldff1b {z0.s}, p1/z, [z3.s]
                row(0x8425e460, 0, 2, false, true, 3, false, 5), // ldff1b {z0.s}, p1/z, [z3.s, #5]
                row(0x843fe460, 0, 2, false, true, 3, false, 31), // ldff1b {z0.s}, p1/z, [z3.s, #31]
                row(0x84032440, 0, 2, true, true, 0, false, 0), // ldff1sb {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84432440, 0, 2, true, true, 1, false, 0), // ldff1sb {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x8420a460, 0, 2, true, true, 3, false, 0), // ldff1sb {z0.s}, p1/z, [z3.s]
                row(0x8425a460, 0, 2, true, true, 3, false, 5), // ldff1sb {z0.s}, p1/z, [z3.s, #5]
                row(0x843fa460, 0, 2, true, true, 3, false, 31), // ldff1sb {z0.s}, p1/z, [z3.s, #31]
                row(0x84836440, 1, 2, false, true, 0, false, 0), // ldff1h {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84a36440, 1, 2, false, true, 0, true, 0), // ldff1h {z0.s}, p1/z, [x2, z3.s, uxtw #1]
                row(0x84c36440, 1, 2, false, true, 1, false, 0), // ldff1h {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x84e36440, 1, 2, false, true, 1, true, 0), // ldff1h {z0.s}, p1/z, [x2, z3.s, sxtw #1]
                row(0x84a0e460, 1, 2, false, true, 3, false, 0), // ldff1h {z0.s}, p1/z, [z3.s]
                row(0x84a5e460, 1, 2, false, true, 3, false, 5), // ldff1h {z0.s}, p1/z, [z3.s, #10]
                row(0x84bfe460, 1, 2, false, true, 3, false, 31), // ldff1h {z0.s}, p1/z, [z3.s, #62]
                row(0x84832440, 1, 2, true, true, 0, false, 0), // ldff1sh {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x84a32440, 1, 2, true, true, 0, true, 0), // ldff1sh {z0.s}, p1/z, [x2, z3.s, uxtw #1]
                row(0x84c32440, 1, 2, true, true, 1, false, 0), // ldff1sh {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x84e32440, 1, 2, true, true, 1, true, 0), // ldff1sh {z0.s}, p1/z, [x2, z3.s, sxtw #1]
                row(0x84a0a460, 1, 2, true, true, 3, false, 0), // ldff1sh {z0.s}, p1/z, [z3.s]
                row(0x84a5a460, 1, 2, true, true, 3, false, 5), // ldff1sh {z0.s}, p1/z, [z3.s, #10]
                row(0x84bfa460, 1, 2, true, true, 3, false, 31), // ldff1sh {z0.s}, p1/z, [z3.s, #62]
                row(0x85036440, 2, 2, false, true, 0, false, 0), // ldff1w {z0.s}, p1/z, [x2, z3.s, uxtw]
                row(0x85236440, 2, 2, false, true, 0, true, 0), // ldff1w {z0.s}, p1/z, [x2, z3.s, uxtw #2]
                row(0x85436440, 2, 2, false, true, 1, false, 0), // ldff1w {z0.s}, p1/z, [x2, z3.s, sxtw]
                row(0x85636440, 2, 2, false, true, 1, true, 0), // ldff1w {z0.s}, p1/z, [x2, z3.s, sxtw #2]
                row(0x8520e460, 2, 2, false, true, 3, false, 0), // ldff1w {z0.s}, p1/z, [z3.s]
                row(0x8525e460, 2, 2, false, true, 3, false, 5), // ldff1w {z0.s}, p1/z, [z3.s, #20]
                row(0x853fe460, 2, 2, false, true, 3, false, 31), // ldff1w {z0.s}, p1/z, [z3.s, #124]
                row(0xc4036440, 0, 3, false, true, 0, false, 0), // ldff1b {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4436440, 0, 3, false, true, 1, false, 0), // ldff1b {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc443e440, 0, 3, false, true, 2, false, 0), // ldff1b {z0.d}, p1/z, [x2, z3.d]
                row(0xc420e460, 0, 3, false, true, 3, false, 0), // ldff1b {z0.d}, p1/z, [z3.d]
                row(0xc425e460, 0, 3, false, true, 3, false, 5), // ldff1b {z0.d}, p1/z, [z3.d, #5]
                row(0xc43fe460, 0, 3, false, true, 3, false, 31), // ldff1b {z0.d}, p1/z, [z3.d, #31]
                row(0xc4032440, 0, 3, true, true, 0, false, 0), // ldff1sb {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4432440, 0, 3, true, true, 1, false, 0), // ldff1sb {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc443a440, 0, 3, true, true, 2, false, 0), // ldff1sb {z0.d}, p1/z, [x2, z3.d]
                row(0xc420a460, 0, 3, true, true, 3, false, 0), // ldff1sb {z0.d}, p1/z, [z3.d]
                row(0xc425a460, 0, 3, true, true, 3, false, 5), // ldff1sb {z0.d}, p1/z, [z3.d, #5]
                row(0xc43fa460, 0, 3, true, true, 3, false, 31), // ldff1sb {z0.d}, p1/z, [z3.d, #31]
                row(0xc4836440, 1, 3, false, true, 0, false, 0), // ldff1h {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4a36440, 1, 3, false, true, 0, true, 0), // ldff1h {z0.d}, p1/z, [x2, z3.d, uxtw #1]
                row(0xc4c36440, 1, 3, false, true, 1, false, 0), // ldff1h {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc4e36440, 1, 3, false, true, 1, true, 0), // ldff1h {z0.d}, p1/z, [x2, z3.d, sxtw #1]
                row(0xc4c3e440, 1, 3, false, true, 2, false, 0), // ldff1h {z0.d}, p1/z, [x2, z3.d]
                row(0xc4e3e440, 1, 3, false, true, 2, true, 0), // ldff1h {z0.d}, p1/z, [x2, z3.d, lsl #1]
                row(0xc4a0e460, 1, 3, false, true, 3, false, 0), // ldff1h {z0.d}, p1/z, [z3.d]
                row(0xc4a5e460, 1, 3, false, true, 3, false, 5), // ldff1h {z0.d}, p1/z, [z3.d, #10]
                row(0xc4bfe460, 1, 3, false, true, 3, false, 31), // ldff1h {z0.d}, p1/z, [z3.d, #62]
                row(0xc4832440, 1, 3, true, true, 0, false, 0), // ldff1sh {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc4a32440, 1, 3, true, true, 0, true, 0), // ldff1sh {z0.d}, p1/z, [x2, z3.d, uxtw #1]
                row(0xc4c32440, 1, 3, true, true, 1, false, 0), // ldff1sh {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc4e32440, 1, 3, true, true, 1, true, 0), // ldff1sh {z0.d}, p1/z, [x2, z3.d, sxtw #1]
                row(0xc4c3a440, 1, 3, true, true, 2, false, 0), // ldff1sh {z0.d}, p1/z, [x2, z3.d]
                row(0xc4e3a440, 1, 3, true, true, 2, true, 0), // ldff1sh {z0.d}, p1/z, [x2, z3.d, lsl #1]
                row(0xc4a0a460, 1, 3, true, true, 3, false, 0), // ldff1sh {z0.d}, p1/z, [z3.d]
                row(0xc4a5a460, 1, 3, true, true, 3, false, 5), // ldff1sh {z0.d}, p1/z, [z3.d, #10]
                row(0xc4bfa460, 1, 3, true, true, 3, false, 31), // ldff1sh {z0.d}, p1/z, [z3.d, #62]
                row(0xc5036440, 2, 3, false, true, 0, false, 0), // ldff1w {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc5236440, 2, 3, false, true, 0, true, 0), // ldff1w {z0.d}, p1/z, [x2, z3.d, uxtw #2]
                row(0xc5436440, 2, 3, false, true, 1, false, 0), // ldff1w {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc5636440, 2, 3, false, true, 1, true, 0), // ldff1w {z0.d}, p1/z, [x2, z3.d, sxtw #2]
                row(0xc543e440, 2, 3, false, true, 2, false, 0), // ldff1w {z0.d}, p1/z, [x2, z3.d]
                row(0xc563e440, 2, 3, false, true, 2, true, 0), // ldff1w {z0.d}, p1/z, [x2, z3.d, lsl #2]
                row(0xc520e460, 2, 3, false, true, 3, false, 0), // ldff1w {z0.d}, p1/z, [z3.d]
                row(0xc525e460, 2, 3, false, true, 3, false, 5), // ldff1w {z0.d}, p1/z, [z3.d, #20]
                row(0xc53fe460, 2, 3, false, true, 3, false, 31), // ldff1w {z0.d}, p1/z, [z3.d, #124]
                row(0xc5032440, 2, 3, true, true, 0, false, 0), // ldff1sw {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc5232440, 2, 3, true, true, 0, true, 0), // ldff1sw {z0.d}, p1/z, [x2, z3.d, uxtw #2]
                row(0xc5432440, 2, 3, true, true, 1, false, 0), // ldff1sw {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc5632440, 2, 3, true, true, 1, true, 0), // ldff1sw {z0.d}, p1/z, [x2, z3.d, sxtw #2]
                row(0xc543a440, 2, 3, true, true, 2, false, 0), // ldff1sw {z0.d}, p1/z, [x2, z3.d]
                row(0xc563a440, 2, 3, true, true, 2, true, 0), // ldff1sw {z0.d}, p1/z, [x2, z3.d, lsl #2]
                row(0xc520a460, 2, 3, true, true, 3, false, 0), // ldff1sw {z0.d}, p1/z, [z3.d]
                row(0xc525a460, 2, 3, true, true, 3, false, 5), // ldff1sw {z0.d}, p1/z, [z3.d, #20]
                row(0xc53fa460, 2, 3, true, true, 3, false, 31), // ldff1sw {z0.d}, p1/z, [z3.d, #124]
                row(0xc5836440, 3, 3, false, true, 0, false, 0), // ldff1d {z0.d}, p1/z, [x2, z3.d, uxtw]
                row(0xc5a36440, 3, 3, false, true, 0, true, 0), // ldff1d {z0.d}, p1/z, [x2, z3.d, uxtw #3]
                row(0xc5c36440, 3, 3, false, true, 1, false, 0), // ldff1d {z0.d}, p1/z, [x2, z3.d, sxtw]
                row(0xc5e36440, 3, 3, false, true, 1, true, 0), // ldff1d {z0.d}, p1/z, [x2, z3.d, sxtw #3]
                row(0xc5c3e440, 3, 3, false, true, 2, false, 0), // ldff1d {z0.d}, p1/z, [x2, z3.d]
                row(0xc5e3e440, 3, 3, false, true, 2, true, 0), // ldff1d {z0.d}, p1/z, [x2, z3.d, lsl #3]
                row(0xc5a0e460, 3, 3, false, true, 3, false, 0), // ldff1d {z0.d}, p1/z, [z3.d]
                row(0xc5a5e460, 3, 3, false, true, 3, false, 5), // ldff1d {z0.d}, p1/z, [z3.d, #40]
                row(0xc5bfe460, 3, 3, false, true, 3, false, 31), // ldff1d {z0.d}, p1/z, [z3.d, #248]
    };

    private static Stream<Object[]> everyForm() {
        return IntStream.of(VECTOR_LENGTHS).boxed()
                .flatMap(vl -> Arrays.stream(FORMS).map(form -> new Object[] {form, vl}));
    }

    private static Stream<Integer> everyVectorLength() {
        return IntStream.of(VECTOR_LENGTHS).boxed();
    }

    /// Endereço de cada elemento: alvos DISTINTOS em ordem embaralhada (offsets fora de ordem) e alguns repetidos.
    private static long[] targets(int elements, long seed) {
        int[] order = permutation(elements, seed);
        long[] target = new long[elements];
        for (int e = 0; e < elements; e++) {
            target[e] = DATA + (long) SLOT_BYTES * order[e];
            if (e >= 3 && e % 5 == 4) {
                target[e] = target[e - 3];
            }
        }
        return target;
    }

    /// Escreve em `Z3` o operando vetorial que faz o elemento `e` apontar para `target[e]` (o elemento inativo aponta
    /// para fora do mapa: se algum acesso lhe fosse feito, haveria aborto).
    private static void setAddressVector(Aarch64Core core, Form form, long[] target, boolean[] active) {
        int elementBytes = 1 << form.esz();
        long immediate = (long) form.imm5() << form.msz();
        int shift = form.scaled() ? form.msz() : 0;
        for (int e = 0; e < target.length; e++) {
            long value;
            if (!active[e]) {
                value = UNMAPPED_OFFSET;
            } else if (form.addressing() == VECTOR_IMMEDIATE) {
                value = target[e] - immediate;
            } else {
                value = (target[e] - DATA) >> shift;
                if (form.esz() == 3 && form.addressing() != OFFSET_64) {
                    value |= 0xDEAD_BEEFL << 32; // só os 32 bits baixos contam em uxtw/sxtw
                }
            }
            setElement(core, Z3, e, elementBytes, value);
        }
    }

    @ParameterizedTest(name = "{0} @ VL {1}")
    @MethodSource("everyForm")
    void everyFormLoadsTheRightElementsInOrderAndTouchesNothingElse(Form form, int vl) {
        checkForm(form, vl, Z0);
    }

    /// O destino é o MESMO registrador do vetor de endereços: o resultado só pode ser gravado depois do último acesso.
    @ParameterizedTest
    @MethodSource("everyVectorLength")
    void aDestinationEqualToTheAddressVectorStillReadsEveryAddressBeforeWriting(int vl) {
        checkForm(row(LD1W_OVERLAP, 2, 2, false, false, UXTW, true, 0), vl, Z3);
        checkForm(row(LD1D_IMMEDIATE_OVERLAP, 3, 3, false, false, VECTOR_IMMEDIATE, false, 1), vl, Z3);
        checkForm(row(LDFF1D_OVERLAP, 3, 3, false, true, OFFSET_64, true, 0), vl, Z3);
    }

    private static void checkForm(Form form, int vl, int destination) {
        GatherMemory memory = randomMemory(form.word() ^ vl);
        Aarch64Core core = core(SVE, vl, memory);
        int elementBytes = 1 << form.esz();
        int elements = vl / 8 / elementBytes;
        boolean[] active = randomActive(elements, form.word());
        setPredicate(core, P1, form.esz(), active);
        long[] target = targets(elements, form.word());
        setAddressVector(core, form, target, active);
        core.setX(2, DATA);
        setFfr(core, ~0L);
        if (destination != Z3) {
            fillZ(core, destination, -1L);
        }
        Long[] values = new Long[elements];
        List<Long> expectedTouched = new ArrayList<>();
        for (int e = 0; e < elements; e++) {
            if (active[e]) {
                values[e] = load(memory, target[e], 1 << form.msz(), form.signed());
                expectedTouched.add(target[e]);
            }
        }
        run(SVE, core, memory, form.word());
        assertEquals(0x14L, core.pc(), "nenhuma exceção");
        assertArrayEquals(expectedVector(vl / 8, elementBytes, values), zBytes(core, destination));
        assertEquals(expectedTouched, memory.touched, "um acesso por elemento ativo, em ordem crescente, e mais nada");
        assertFfr(~0L, core, "sem falta o FFR não muda");
    }

    @ParameterizedTest
    @MethodSource("everyVectorLength")
    void theStackPointerIsTheBaseWhenRnIs31(int vl) {
        GatherMemory memory = randomMemory(50);
        Aarch64Core core = core(SVE, vl, memory);
        int elements = vl / 8 / 4;
        setPredicate(core, P1, 2, allActive(elements));
        for (int e = 0; e < elements; e++) {
            setElement(core, Z3, e, 4, (long) SLOT_BYTES * e);
        }
        core.setSp(DATA);
        core.setX(2, 0x100); // não é a base
        run(SVE, core, memory, LD1B_SP_BASE); // ld1b {z0.s}, p1/z, [sp, z3.s, uxtw]
        Long[] values = new Long[elements];
        for (int e = 0; e < elements; e++) {
            values[e] = load(memory, DATA + (long) SLOT_BYTES * e, 1, false);
        }
        assertArrayEquals(expectedVector(vl / 8, 4, values), zBytes(core, Z0));
    }

    // ── xs: sxtw × uxtw ──────────────────────────────────────────────────────────────────────────

    @Test
    void sxtwSignExtendsTheThirtyTwoBitOffsetAndUxtwDoesNot() {
        GatherMemory memory = randomMemory(51);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 2, allActive(8));
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, e, 4, -SLOT_BYTES * (e + 1L)); // 0xFFFFFFF0, 0xFFFFFFE0, ...
        }
        core.setX(2, DATA + 0x100);
        run(SVE, core, memory, LD1W_SXTW);
        Long[] values = new Long[8];
        for (int e = 0; e < 8; e++) {
            values[e] = load(memory, DATA + 0x100 - SLOT_BYTES * (e + 1L), 4, false);
        }
        assertArrayEquals(expectedVector(32, 4, values), zBytes(core, Z0));

        Aarch64Core aborting = core(SVE, 256, memory);
        setPredicate(aborting, P1, 2, allActive(8));
        for (int e = 0; e < 8; e++) {
            setElement(aborting, Z3, e, 4, -SLOT_BYTES * (e + 1L));
        }
        aborting.setX(2, DATA + 0x100);
        fillZ(aborting, Z0, JUNK);
        run(SVE, aborting, memory, LD1W_UXTW);
        assertEquals(HANDLER, aborting.pc(), "uxtw lê 0xFFFFFFF0 como positivo: fora do mapa");
        assertEquals(JUNK, aborting.scalable().zWord(Z0, 0));
        assertEquals(DATA + 0x100 + 0xFFFF_FFF0L, aborting.exceptionState().far(Aarch64ExceptionLevel.EL1));
    }

    @Test
    void theSixtyFourBitElementFormsUseOnlyTheLowThirtyTwoBitsWithSxtwAndUxtw() {
        GatherMemory memory = randomMemory(52);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        for (int e = 0; e < 4; e++) {
            long offset = -2L * (e + 1) & 0xFFFF_FFFFL; // -2, -4, ... como 32 bits, escalado por 8 => -16, -32, ...
            setElement(core, Z3, e, 8, 0x1234_5678L << 32 | offset);
        }
        core.setX(2, DATA + 0x100);
        run(SVE, core, memory, LD1D_SXTW_SCALED);
        Long[] values = new Long[4];
        for (int e = 0; e < 4; e++) {
            values[e] = load(memory, DATA + 0x100 - SLOT_BYTES * (e + 1L), 8, false);
        }
        assertArrayEquals(expectedVector(32, 8, values), zBytes(core, Z0));

        Aarch64Core aborting = core(SVE, 256, memory);
        setPredicate(aborting, P1, 3, allActive(4));
        setElement(aborting, Z3, 0, 8, 0x1234_5678L << 32 | (-2L & 0xFFFF_FFFFL));
        aborting.setX(2, DATA + 0x100);
        run(SVE, aborting, memory, LD1D_UXTW_SCALED);
        assertEquals(HANDLER, aborting.pc());
    }

    // ── Falta ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anElementInTheMiddleThatFaultsMakesLdff1LoadTheEarlierOnesClearTheFfrFromThereAndRaiseNothing() {
        GatherMemory memory = randomMemory(53);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 2, allActive(8));
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, e, 4, e == 3 ? UNMAPPED_OFFSET : SLOT_BYTES * (long) e);
        }
        core.setX(2, DATA);
        setFfr(core, ~0L);
        fillZ(core, Z0, -1L);
        run(SVE, core, memory, LDFF1W_UXTW); // ldff1w {z0.s}, p1/z, [x2, z3.s, uxtw]
        assertEquals(0x14L, core.pc(), "nenhuma exceção");
        Long[] values = new Long[8];
        for (int e = 0; e < 3; e++) {
            values[e] = load(memory, DATA + SLOT_BYTES * (long) e, 4, false);
        }
        assertArrayEquals(expectedVector(32, 4, values), zBytes(core, Z0),
                "do elemento que falhou em diante fica zero, mesmo os que teriam endereço válido");
        assertEquals(0xFFFL, core.scalable().ffrWord(0), "elemento 3 × 4 bytes = bit 12: de lá em diante, zero");
        assertEquals(List.of(DATA, DATA + 16, DATA + 32, DATA + UNMAPPED_OFFSET), memory.touched,
                "depois da falta, nenhum outro elemento é tentado");
    }

    @Test
    void whenTheFirstActiveElementFaultsLdff1AbortsForRealEvenIfAnInactiveOneBeforeItWouldFault() {
        GatherMemory memory = randomMemory(54);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[8];
        active[2] = true;
        active[4] = true;
        setPredicate(core, P1, 2, active);
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, e, 4, e == 4 ? SLOT_BYTES : UNMAPPED_OFFSET + e);
        }
        core.setX(2, DATA);
        setFfr(core, ~0L);
        fillZ(core, Z0, JUNK);
        run(SVE, core, memory, LDFF1W_UXTW);
        assertEquals(HANDLER, core.pc());
        assertEquals(DATA + UNMAPPED_OFFSET + 2, core.exceptionState().far(Aarch64ExceptionLevel.EL1));
        assertEquals(JUNK, core.scalable().zWord(Z0, 0), "Z0 intacto no aborto");
        assertFfr(~0L, core, "FFR intacto no aborto");
    }

    @Test
    void aPlainLd1ReportsTheFaultOfTheLowestFaultingElementAndLeavesTheDestinationUntouched() {
        GatherMemory memory = randomMemory(55);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 2, allActive(8));
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, e, 4, e == 3 ? UNMAPPED_OFFSET + 3 : e == 5 ? UNMAPPED_OFFSET + 5 : SLOT_BYTES * (long) e);
        }
        core.setX(2, DATA);
        setFfr(core, ~0L);
        fillZ(core, Z0, JUNK);
        run(SVE, core, memory, LD1W_UXTW);
        assertEquals(HANDLER, core.pc());
        assertEquals(DATA + UNMAPPED_OFFSET + 3, core.exceptionState().far(Aarch64ExceptionLevel.EL1),
                "o aborto reportado é o do elemento de MENOR índice");
        assertEquals(JUNK, core.scalable().zWord(Z0, 0), "aborto preciso: nenhum elemento foi gravado");
        assertFfr(~0L, core, "LD1 não toca o FFR");
    }

    @Test
    void ldff1NeverSetsAnFfrBitBackAndKeepsTheBitsBelowTheFaultAsTheyWere() {
        GatherMemory memory = randomMemory(56);
        Aarch64Core core = core(SVE, 256, memory);
        boolean[] active = new boolean[4];
        active[0] = true;
        active[2] = true;
        active[3] = true;
        setPredicate(core, P1, 3, active);
        setElement(core, Z3, 0, 8, SLOT_BYTES);
        setElement(core, Z3, 2, 8, UNMAPPED_OFFSET);
        setElement(core, Z3, 3, 8, SLOT_BYTES * 2L);
        core.setX(2, DATA);
        core.scalable().setFfrWord(0, 0xFFFF_FFFF_FFFF_FF0FL);
        run(SVE, core, memory, LDFF1D_64); // ldff1d {z0.d}, p1/z, [x2, z3.d]
        assertEquals(0x14L, core.pc());
        assertEquals(0x0000_0000_0000_FF0FL, core.scalable().ffrWord(0), "o elemento 2 tem o bit 16");
        assertArrayEquals(expectedVector(32, 8, new Long[] {load(memory, DATA + SLOT_BYTES, 8, false), null, null, null}),
                zBytes(core, Z0));
    }

    @ParameterizedTest
    @ValueSource(ints = {LD1D_64, LDFF1D_64, LD1W_UXTW, LDFF1W_UXTW, LDFF1SW_IMMEDIATE})
    void anAllInactivePredicateZeroesTheVectorTouchesNothingAndLeavesTheFfrAlone(int word) {
        GatherMemory memory = randomMemory(57);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 3, new boolean[4]);
        fillZ(core, Z3, UNMAPPED_OFFSET);
        fillZ(core, Z0, -1L);
        core.setX(2, DATA);
        setFfr(core, 0x1234L);
        run(SVE, core, memory, word);
        assertEquals(0x14L, core.pc(), "nenhum aborto");
        assertEquals(0L, core.scalable().zWord(Z0, 0));
        assertEquals(0x1234L, core.scalable().ffrWord(0));
        assertTrue(memory.touched.isEmpty());
    }

    @Test
    void ldff1WithAnImmediateSignExtendsTheLoadedWordsAndStopsAtTheFault() {
        GatherMemory memory = randomMemory(58);
        memory.bytes[(int) DATA + 0] = (byte) 0xff;
        memory.bytes[(int) DATA + 1] = (byte) 0xff;
        memory.bytes[(int) DATA + 2] = (byte) 0xff;
        memory.bytes[(int) DATA + 3] = (byte) 0x80; // 0x80FFFFFF: negativo como palavra
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        setElement(core, Z3, 0, 8, DATA - 4); // [z3.d, #4] => DATA
        setElement(core, Z3, 1, 8, DATA + SLOT_BYTES - 4);
        setElement(core, Z3, 2, 8, UNMAPPED_OFFSET);
        setElement(core, Z3, 3, 8, DATA + 2L * SLOT_BYTES - 4);
        setFfr(core, ~0L);
        run(SVE, core, memory, LDFF1SW_IMMEDIATE); // ldff1sw {z0.d}, p1/z, [z3.d, #4]
        assertEquals(0x14L, core.pc());
        assertArrayEquals(expectedVector(32, 8, new Long[] {
                load(memory, DATA, 4, true), load(memory, DATA + SLOT_BYTES, 4, true), null, null}), zBytes(core, Z0));
        assertEquals(0xFFFFL, core.scalable().ffrWord(0));
    }

    // ── LD1Q ─────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("everyVectorLength")
    void ld1qLoadsOneQuadwordPerActiveSegmentFromTheVectorBasePlusTheScalarOffset(int vl) {
        for (int word : new int[] {LD1Q, LD1Q_XZR, LD1Q_OVERLAP}) {
            GatherMemory memory = randomMemory(59 + word);
            Aarch64Core core = core(SVE2P1, vl, memory);
            int segments = vl / 128;
            boolean[] active = randomActive(segments, 60);
            active[segments - 1] = false;
            setPredicate(core, P1, 4, active);
            long offset = word == LD1Q_XZR ? 0 : 0x30;
            long[] target = new long[segments];
            int[] order = permutation(segments, 61);
            int destination = word == LD1Q_OVERLAP ? Z3 : Z0;
            for (int s = 0; s < segments; s++) {
                target[s] = DATA + (long) SLOT_BYTES * order[s];
                core.scalable().setZWord(Z3, 2 * s, active[s] ? target[s] - offset : UNMAPPED_OFFSET);
                core.scalable().setZWord(Z3, 2 * s + 1, JUNK); // a metade alta do segmento não conta
            }
            core.setX(2, offset);
            if (destination == Z0) {
                fillZ(core, Z0, -1L);
            }
            run(SVE2P1, core, memory, word);
            assertEquals(0x14L, core.pc());
            byte[] expected = new byte[vl / 8];
            List<Long> touched = new ArrayList<>();
            for (int s = 0; s < segments; s++) {
                if (active[s]) {
                    System.arraycopy(memory.bytes, (int) target[s], expected, s * 16, 16);
                    touched.add(target[s]);
                    touched.add(target[s] + 8);
                }
            }
            assertArrayEquals(expected, zBytes(core, destination), "segmento inativo = zero");
            assertEquals(touched, memory.touched);
        }
    }

    @Test
    void ld1qAbortsWithoutWritingTheDestinationWhenAnActiveSegmentFaults() {
        GatherMemory memory = randomMemory(62);
        Aarch64Core core = core(SVE2P1, 256, memory);
        setPredicate(core, P1, 4, allActive(2));
        core.scalable().setZWord(Z3, 0, DATA);
        core.scalable().setZWord(Z3, 2, UNMAPPED_OFFSET);
        core.setX(2, 0);
        fillZ(core, Z0, JUNK);
        run(SVE2P1, core, memory, LD1Q);
        assertEquals(HANDLER, core.pc());
        assertEquals(UNMAPPED_OFFSET, core.exceptionState().far(Aarch64ExceptionLevel.EL1));
        assertEquals(JUNK, core.scalable().zWord(Z0, 0), "aborto preciso");
    }

    @Test
    void ld1qNeedsSve2p1() {
        assertFalse(decodes(SVE, LD1Q));
        assertTrue(decodes(SVE2P1, LD1Q));
    }

    // ── Prefetch, streaming, acesso ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0xc4638440, 0xc463a440, 0xc4234440, 0xc581e46b, 0xc4636440})
    void theGatherPrefetchesAreNoOpsThatDecodeAsThePrfOperation(int word) {
        GatherMemory memory = randomMemory(63);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        fillZ(core, Z3, UNMAPPED_OFFSET);
        fillZ(core, Z0, JUNK);
        run(SVE, core, memory, word);
        assertEquals(0x14L, core.pc());
        assertEquals(JUNK, core.scalable().zWord(Z0, 0));
        assertTrue(memory.touched.isEmpty());
        Ir64Op op = decodeOrNull(SVE, word);
        assertEquals(Ir64Op.SveLoad.Op.PRF, assertInstanceOf(Ir64Op.SveLoad.class, op).op());
    }

    @ParameterizedTest
    @ValueSource(ints = {LD1W_UXTW, LDFF1D_64, 0xc5a1c463, LD1Q, 0xc4638440, 0x84230440})
    void everyGatherIsUndefinedInStreamingMode(int word) {
        GatherMemory memory = randomMemory(64);
        Aarch64Architecture architecture = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-SVE2p1",
                Aarch64Feature.SVE2_1);
        Aarch64Core core = new Aarch64Core(memory, architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, memory, SMSTART_SM);
        setPredicate(core, P1, 3, allActive(8));
        core.setX(2, DATA);
        memory.write32(0x10, word);
        core.setProgramCounter(0x10);
        new Ir64BlockExecutor(architecture).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
    }

    /// `CPACR_EL1.ZEN` negando SVE: a exceção é tomada e NENHUM estado (nem `FFR`, nem memória) é tocado.
    @ParameterizedTest
    @ValueSource(ints = {LD1W_UXTW, LD1D_64, LDFF1D_64, LD1Q, 0xc4638440, 0xc5a1c463})
    void everyGatherTrapsWithTheSveAccessExceptionWhenCpacrDeniesIt(int word) {
        GatherMemory memory = randomMemory(65);
        Aarch64Core core = core(SVE2P1, 256, memory);
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
        setPredicate(core, P1, 3, allActive(4));
        setFfr(core, 0x77L);
        fillZ(core, Z0, 0x3333333333333333L);
        core.setX(2, DATA);
        run(SVE2P1, core, memory, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(0x19L, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26, "EC = acesso SVE");
        assertEquals(0x3333333333333333L, core.scalable().zWord(Z0, 0));
        assertEquals(0x77L, core.scalable().ffrWord(0));
        assertTrue(memory.touched.isEmpty());
    }

    @Test
    void theSameBlockRunsThroughTheLifterAndTheBlockExecutor() {
        GatherMemory memory = randomMemory(66);
        Aarch64Core core = core(SVE, 256, memory);
        setPredicate(core, P1, 2, allActive(8));
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, e, 4, SLOT_BYTES * (long) e);
        }
        core.setX(2, DATA);
        memory.write32(0, LD1W_UXTW);
        Ir64Block block = new StandardIr64BlockLifter(SVE).lift(memory, 0, 1);
        new Ir64BlockExecutor(SVE).executeBlock(core, block);
        Long[] values = new Long[8];
        for (int e = 0; e < 8; e++) {
            values[e] = load(memory, DATA + SLOT_BYTES * (long) e, 4, false);
        }
        assertArrayEquals(expectedVector(32, 4, values), zBytes(core, Z0));
    }

    // ── O decoder, exaustivo ─────────────────────────────────────────────────────────────────────

    private static final int ZPRZ_32 = 0;
    private static final int ZPRZ_64_UNPACKED = 1;
    private static final int ZPRZ_64 = 2;
    private static final int ZPIZ = 3;
    private static final int LD1Q_KIND = 4;
    private static final int PRF_KIND = 5;
    private static final int LDNT1_KIND = 6;
    private static final int FIELD = -1;

    /// Uma linha do `sve.decode` transcrita literalmente (com `.`/`-` = livre) mais os atributos que o `.decode` fixa
    /// (`esz=`, `msz=`, `scale=`, `u=`); `FIELD` = vem dos bits da própria palavra.
    private record Pattern(String text, int kind, int esz, int msz, int scale, int unsigned) {
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
    }

    private static final List<Pattern> PATTERNS = List.of(
            // SVE 32-bit gather load (scalar plus 32-bit unscaled/scaled offsets)
            new Pattern("1000010 00 .0 ..... 0.. ... ..... .....", ZPRZ_32, 2, 0, 0, FIELD),
            new Pattern("1000010 01 .. ..... 0.. ... ..... .....", ZPRZ_32, 2, 1, FIELD, FIELD),
            new Pattern("1000010 10 .. ..... 01. ... ..... .....", ZPRZ_32, 2, 2, FIELD, 1),
            // SVE 32-bit gather load (vector plus immediate)
            new Pattern("1000010 .. 01 ..... 1.. ... ..... .....", ZPIZ, 2, FIELD, FIELD, FIELD),
            // SVE 64-bit gather load (scalar plus 32-bit unpacked unscaled/scaled offsets)
            new Pattern("1100010 00 .0 ..... 0.. ... ..... .....", ZPRZ_64_UNPACKED, 3, 0, 0, FIELD),
            new Pattern("1100010 01 .. ..... 0.. ... ..... .....", ZPRZ_64_UNPACKED, 3, 1, FIELD, FIELD),
            new Pattern("1100010 10 .. ..... 0.. ... ..... .....", ZPRZ_64_UNPACKED, 3, 2, FIELD, FIELD),
            new Pattern("1100010 11 .. ..... 01. ... ..... .....", ZPRZ_64_UNPACKED, 3, 3, FIELD, 1),
            // SVE 64-bit gather load (scalar plus 64-bit unscaled/scaled offsets)
            new Pattern("1100010 00 10 ..... 1.. ... ..... .....", ZPRZ_64, 3, 0, 0, FIELD),
            new Pattern("1100010 01 1. ..... 1.. ... ..... .....", ZPRZ_64, 3, 1, FIELD, FIELD),
            new Pattern("1100010 10 1. ..... 1.. ... ..... .....", ZPRZ_64, 3, 2, FIELD, FIELD),
            new Pattern("1100010 11 1. ..... 11. ... ..... .....", ZPRZ_64, 3, 3, FIELD, 1),
            // LD1Q: vector + scalar
            new Pattern("1100 0100 000 ..... 101 ... ..... .....", LD1Q_KIND, 4, 4, 0, 0),
            // SVE 64-bit gather load (vector plus immediate)
            new Pattern("1100010 .. 01 ..... 1.. ... ..... .....", ZPIZ, 3, FIELD, FIELD, FIELD),
            // SVE 64-bit gather prefetch
            new Pattern("1100010 00 11 ----- 1-- --- ----- 0 ----", PRF_KIND, 0, 0, 0, 0),
            new Pattern("1100010 00 -1 ----- 0-- --- ----- 0 ----", PRF_KIND, 0, 0, 0, 0),
            new Pattern("1100010 -- 00 ----- 111 --- ----- 0 ----", PRF_KIND, 0, 0, 0, 0),
            // SVE2 non-temporal gather (vector plus scalar): o `u` é o bit 13 na forma de 32 bits e o 14 na de 64
            new Pattern("1100010 .. 00 ..... 1.0 ... ..... .....", LDNT1_KIND, 3, FIELD, 0, FIELD),
            new Pattern("1000010 .. 00 ..... 10. ... ..... .....", LDNT1_KIND, 2, FIELD, 0, FIELD));

    private static boolean bit(int word, int index) {
        return ((word >>> index) & 1) != 0;
    }

    @Test
    void theAcceptedSetOfTheSixteenThousandWordsIsExactlyTheOneTheDecodeFileEnumerates() {
        Aarch64Decoder decoder = new Aarch64Decoder(SVE2P1);
        GatherMemory memory = new GatherMemory();
        int[] acceptedPerKind = new int[LDNT1_KIND + 1];
        for (int prefix : new int[] {0b1000010, 0b1100010}) {
            for (int field = 0; field < 1 << 12; field++) {
                for (int rdLow : new int[] {0, 16}) {
                    int word = prefix << 25 | field << 13 | 1 << 10 | Z3 << 5 | rdLow;
                    memory.write32(0, word);
                    Ir64Op decoded;
                    try {
                        decoded = decoder.decode(memory, 0);
                    } catch (UnsupportedOperationException refused) {
                        decoded = null;
                    }
                    Pattern matched = null;
                    for (Pattern pattern : PATTERNS) {
                        if ((word & pattern.mask()) == pattern.value()) {
                            assertNull(matched, "o .decode transcrito tem duas linhas para " + Integer.toHexString(word));
                            matched = pattern;
                        }
                    }
                    String hex = Integer.toHexString(word);
                    if (matched == null || !expectedValid(matched, word)) {
                        if (prefix == 0b1100010) {
                            assertNull(decoded, "recusada: " + hex);
                        } else {
                            assertFalse(decoded instanceof Ir64Op.SveGather, "recusada: " + hex);
                        }
                        continue;
                    }
                    acceptedPerKind[matched.kind()]++;
                    assertExpected(matched, word, decoded, hex);
                }
            }
        }
        for (int kind = 0; kind <= LDNT1_KIND; kind++) {
            assertTrue(acceptedPerKind[kind] > 0, "nenhuma palavra aceita da forma " + kind);
        }
    }

    /// Posição do `u` do `LDNT1_zprz`: `10 u` (bit 13) no elemento de 32 bits, `1 u 0` (bit 14) no de 64.
    private static int ntUnsignedBit(Pattern pattern) {
        return pattern.esz() == 2 ? 13 : 14;
    }

    /// `trans_LD1_zpiz`: `esz < msz || (esz == msz && !u)` é indefinido. As linhas de `LD1_zprz` já são enumeradas.
    private static boolean expectedValid(Pattern pattern, int word) {
        if (pattern.kind() != ZPIZ && pattern.kind() != LDNT1_KIND) {
            return true;
        }
        int msz = (word >>> 23) & 3;
        boolean unsigned = bit(word, pattern.kind() == LDNT1_KIND ? ntUnsignedBit(pattern) : 14);
        return !(pattern.esz() < msz || pattern.esz() == msz && !unsigned);
    }

    private static void assertExpected(Pattern pattern, int word, Ir64Op decoded, String hex) {
        if (pattern.kind() == PRF_KIND) {
            Ir64Op.SveLoad prefetch = assertInstanceOf(Ir64Op.SveLoad.class, decoded, hex);
            assertEquals(Ir64Op.SveLoad.Op.PRF, prefetch.op(), hex);
            assertTrue(prefetch.nonStreaming(), hex);
            return;
        }
        Ir64Op.SveGather gather = assertInstanceOf(Ir64Op.SveGather.class, decoded, hex);
        assertEquals(pattern.esz(), gather.esz(), hex);
        assertEquals(word & 31, gather.rd(), hex);
        assertEquals((word >>> 5) & 31, gather.rn(), hex);
        assertEquals((word >>> 10) & 7, gather.pg(), hex);
        if (pattern.kind() == LDNT1_KIND) {
            assertEquals(Ir64Op.SveGather.Op.VECTOR_PLUS_SCALAR, gather.op(), hex);
            assertEquals((word >>> 23) & 3, gather.msz(), hex);
            assertEquals((word >>> 16) & 31, gather.rm(), hex);
            assertEquals(!bit(word, ntUnsignedBit(pattern)), gather.signExtend(), hex);
            assertFalse(gather.firstFault(), hex);
            return;
        }
        if (pattern.kind() == LD1Q_KIND) {
            assertEquals(Ir64Op.SveGather.Op.LD1Q, gather.op(), hex);
            assertEquals(4, gather.msz(), hex);
            assertEquals((word >>> 16) & 31, gather.rm(), hex);
            assertFalse(gather.firstFault(), hex);
            return;
        }
        assertEquals(pattern.msz() == FIELD ? (word >>> 23) & 3 : pattern.msz(), gather.msz(), hex);
        boolean unsigned = pattern.unsigned() == 1 || bit(word, 14);
        assertEquals(!unsigned, gather.signExtend(), hex);
        assertEquals(bit(word, 13), gather.firstFault(), "o bit ff dobra cada linha: " + hex);
        if (pattern.kind() == ZPIZ) {
            assertEquals(Ir64Op.SveGather.Op.VECTOR_PLUS_IMMEDIATE, gather.op(), hex);
            assertEquals((word >>> 16) & 31, gather.immediate(), hex);
            return;
        }
        assertEquals(Ir64Op.SveGather.Op.SCALAR_PLUS_VECTOR, gather.op(), hex);
        assertEquals((word >>> 16) & 31, gather.rm(), hex);
        assertEquals(pattern.scale() == FIELD ? bit(word, 21) : pattern.scale() == 1, gather.scaled(), hex);
        int extend = pattern.kind() == ZPRZ_64 ? Ir64Op.SveGather.OFFSET_64
                : bit(word, 22) ? Ir64Op.SveGather.OFFSET_SXTW : Ir64Op.SveGather.OFFSET_UXTW;
        assertEquals(extend, gather.offsetExtend(), hex);
    }

    private static Ir64Op decodeOrNull(Aarch64Architecture architecture, int word) {
        GatherMemory memory = new GatherMemory();
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static boolean decodes(Aarch64Architecture architecture, int word) {
        return decodeOrNull(architecture, word) instanceof Ir64Op.SveGather;
    }
}
