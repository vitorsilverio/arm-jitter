package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B17.25 — `LDNT1_zprz`/`STNT1_zprz` (gather/scatter non-temporal da SVE2). Palavras conferidas contra
/// `aarch64-none-elf-as` (devkitA64, `-march=armv9.4-a+sve2+sve2p1`).
///
/// "Non-temporal" é um hint de cache: sem modelo de cache, o acesso é o de um `LD1`/`ST1`. O que distingue estas
/// instruções das irmãs `LD1_zprz`/`ST1_zprz` é o ENDEREÇAMENTO: base VETORIAL (`Zn[e]`) mais deslocamento ESCALAR (`Xm`),
/// o inverso do escalar + vetor. O oráculo lê o tamanho do acesso, a extensão e o tamanho do elemento do NOME do mnemônico
/// (`ldnt1sh {z0.s}` = acesso de 2 bytes, com sinal, elemento de 4) e confere contra bytes lidos direto do array de teste.
class Aarch64SveNonTemporalTest {
    private static final int P1 = 1;
    private static final int Z0 = 0;
    private static final int Z3 = 3;
    private static final int OFFSET_REGISTER = 2;
    private static final long VBAR = 0x400L;
    private static final long HANDLER = VBAR + 0x400L;
    private static final long ESR_EC_UNKNOWN = 0L;
    private static final long MAPPED_LIMIT = 0x2000L;
    private static final long DATA = 0x1800L;
    private static final long DATA_REGION = 0x1000L;
    private static final long UNMAPPED_ADDRESS = 0x7FFF_0000L;
    private static final long OFFSET = 0x30L;
    private static final int SLOT_BYTES = 16;
    private static final long JUNK = 0x2222_2222_2222_2222L;
    private static final int[] VECTOR_LENGTHS = {256, 512};
    private static final Aarch64Architecture SVE = Aarch64Architecture.ARMV9_0_A;
    private static final Aarch64Architecture SVE2 = Aarch64Architecture.extending(SVE, "teste-SVE2", Aarch64Feature.SVE2);
    private static final int SMSTART_SM = 0xd503437f;
    private static final int INSTRUCTION_ADDRESS = 0x10;

    private static final int LDNT1D_OVERLAP = 0xc582c463;
    private static final int LDNT1D_XZR = 0xc59fc460;
    private static final int STNT1D_XZR = 0xe59f2460;

    /// Uma instrução do grupo: palavra, tamanho do acesso (`msz`), do elemento (`esz`) e extensão, lidos do mnemônico.
    private record Form(String name, int word, int msz, int esz, boolean signed, boolean store) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Form> FORMS = List.of(
            new Form("ldnt1b {z0.s}", 0x8402a460, 0, 2, false, false),
            new Form("ldnt1sb {z0.s}", 0x84028460, 0, 2, true, false),
            new Form("ldnt1h {z0.s}", 0x8482a460, 1, 2, false, false),
            new Form("ldnt1sh {z0.s}", 0x84828460, 1, 2, true, false),
            new Form("ldnt1w {z0.s}", 0x8502a460, 2, 2, false, false),
            new Form("ldnt1b {z0.d}", 0xc402c460, 0, 3, false, false),
            new Form("ldnt1sb {z0.d}", 0xc4028460, 0, 3, true, false),
            new Form("ldnt1h {z0.d}", 0xc482c460, 1, 3, false, false),
            new Form("ldnt1sh {z0.d}", 0xc4828460, 1, 3, true, false),
            new Form("ldnt1w {z0.d}", 0xc502c460, 2, 3, false, false),
            new Form("ldnt1sw {z0.d}", 0xc5028460, 2, 3, true, false),
            new Form("ldnt1d {z0.d}", 0xc582c460, 3, 3, false, false),
            new Form("stnt1b {z0.s}", 0xe4422460, 0, 2, false, true),
            new Form("stnt1h {z0.s}", 0xe4c22460, 1, 2, false, true),
            new Form("stnt1w {z0.s}", 0xe5422460, 2, 2, false, true),
            new Form("stnt1b {z0.d}", 0xe4022460, 0, 3, false, true),
            new Form("stnt1h {z0.d}", 0xe4822460, 1, 3, false, true),
            new Form("stnt1w {z0.d}", 0xe5022460, 2, 3, false, true),
            new Form("stnt1d {z0.d}", 0xe5822460, 3, 3, false, true));

    /// Memória que só existe abaixo de `MAPPED_LIMIT`: acima, todo acesso levanta a falta de tradução. Registra o endereço
    /// de cada leitura de dados (uma vez por acesso, inclusive o que falha) e de cada byte escrito.
    private static final class NtMemory implements AddressSpace64 {
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

    private static NtMemory randomMemory(long seed) {
        NtMemory memory = new NtMemory();
        new Random(seed).nextBytes(memory.bytes);
        return memory;
    }

    private static Aarch64Core core(Aarch64Architecture architecture, int vl, NtMemory memory) {
        Aarch64Core core = new Aarch64Core(memory, architecture, vl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    /// Escreve a palavra em `0x10` (fora da região de dados), executa UMA instrução e zera os registros de acesso.
    private static void run(Aarch64Architecture architecture, Aarch64Core core, NtMemory memory, int word) {
        memory.write32(INSTRUCTION_ADDRESS, word);
        core.setProgramCounter(0x10);
        memory.touched.clear();
        memory.written.clear();
        new Ir64BlockExecutor(architecture).step(core);
    }

    private static Stream<Object[]> everyFormAtEveryVectorLength() {
        return FORMS.stream().flatMap(form -> Arrays.stream(VECTOR_LENGTHS).mapToObj(vl -> new Object[] {form, vl}));
    }

    private static Stream<Integer> everyVectorLength() {
        return Arrays.stream(VECTOR_LENGTHS).boxed();
    }

    // ── Utilidades de estado ─────────────────────────────────────────────────────────────────────

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

    private static byte[] zBytes(Aarch64Core core, int reg) {
        byte[] out = new byte[core.vectorLengthBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (core.scalable().zWord(reg, i / 8) >>> ((i % 8) * 8));
        }
        return out;
    }

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

    private static int[] permutation(int n, long seed) {
        Integer[] boxed = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        Collections.shuffle(Arrays.asList(boxed), new Random(seed));
        return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
    }

    private static long signExtend(long value, int bytes) {
        int shift = 64 - bytes * 8;
        return value << shift >> shift;
    }

    /// Monta o cenário de um gather/scatter: `Zn[e] = slot(e) - OFFSET` para o elemento ativo (`UNMAPPED_ADDRESS` para o
    /// inativo, que se fosse acessado abortaria), `X2 = OFFSET`. Devolve o endereço efetivo de cada elemento.
    private static long[] prepareAddresses(Aarch64Core core, Form form, boolean[] active, long offset, long seed) {
        int elements = active.length;
        int[] order = permutation(elements, seed);
        long[] target = new long[elements];
        for (int e = 0; e < elements; e++) {
            target[e] = DATA + (long) SLOT_BYTES * order[e];
            setElement(core, Z3, e, 1 << form.esz(), active[e] ? target[e] - offset : UNMAPPED_ADDRESS);
        }
        core.setX(OFFSET_REGISTER, offset);
        return target;
    }

    // ── Execução ─────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} @ VL {1}")
    @MethodSource("everyFormAtEveryVectorLength")
    void everyFormAccessesZnPlusXmForEachActiveElementOnly(Form form, int vl) {
        NtMemory memory = randomMemory(1000 + form.word());
        Aarch64Core core = core(SVE2, vl, memory);
        int elements = vl / 8 >> form.esz();
        boolean[] active = randomActive(elements, 7);
        setPredicate(core, P1, form.esz(), active);
        long[] target = prepareAddresses(core, form, active, OFFSET, 11);
        if (form.store()) {
            checkStore(form, core, memory, active, target);
        } else {
            checkLoad(form, core, memory, active, target);
        }
    }

    private static void checkLoad(Form form, Aarch64Core core, NtMemory memory, boolean[] active, long[] target) {
        fillZ(core, Z0, -1L);
        run(SVE2, core, memory, form.word());
        assertEquals(0x14L, core.pc(), form.name());
        int elementBytes = 1 << form.esz();
        byte[] expected = new byte[core.vectorLengthBytes()];
        List<Long> touched = new ArrayList<>();
        for (int e = 0; e < active.length; e++) {
            if (!active[e]) {
                continue;
            }
            long value = memory.raw(target[e], 1 << form.msz());
            value = form.signed() ? signExtend(value, 1 << form.msz()) : value;
            for (int b = 0; b < elementBytes; b++) {
                expected[e * elementBytes + b] = (byte) (value >>> (8 * b));
            }
            touched.add(target[e]);
        }
        assertArrayEquals(expected, zBytes(core, Z0), form.name() + ": elemento inativo vira zero, ativo lê Zn[e] + Xm");
        assertEquals(touched, memory.touched, form.name() + ": só o elemento ativo acessa a memória, em ordem");
    }

    private static void checkStore(Form form, Aarch64Core core, NtMemory memory, boolean[] active, long[] target) {
        int elementBytes = 1 << form.esz();
        for (int e = 0; e < active.length; e++) {
            setElement(core, Z0, e, elementBytes, new Random(e * 31L + form.word()).nextLong());
        }
        memory.write32(INSTRUCTION_ADDRESS, form.word()); // a palavra entra na imagem esperada, não no diff
        byte[] expected = memory.bytes.clone();
        for (int e = 0; e < active.length; e++) {
            if (!active[e]) {
                continue;
            }
            long value = element(core, Z0, e, elementBytes);
            for (int b = 0; b < 1 << form.msz(); b++) {
                expected[(int) target[e] + b] = (byte) (value >>> (8 * b));
            }
        }
        run(SVE2, core, memory, form.word());
        assertEquals(0x14L, core.pc(), form.name());
        assertArrayEquals(expected, memory.bytes, form.name() + ": o elemento inativo não escreve");
    }

    @ParameterizedTest
    @MethodSource("everyVectorLength")
    void aZeroRegisterOperandMeansAZeroOffsetAndADestinationEqualToTheBaseVectorStillReadsEveryAddressFirst(int vl) {
        for (int word : new int[] {LDNT1D_XZR, LDNT1D_OVERLAP}) {
            NtMemory memory = randomMemory(2000 + word);
            Aarch64Core core = core(SVE2, vl, memory);
            int elements = vl / 64;
            boolean[] active = allActive(elements);
            setPredicate(core, P1, 3, active);
            Form form = FORMS.get(11);
            long offset = word == LDNT1D_XZR ? 0 : OFFSET;
            long[] target = prepareAddresses(core, form, active, offset, 13);
            fillZ(core, Z0, JUNK);
            run(SVE2, core, memory, word);
            int destination = word == LDNT1D_OVERLAP ? Z3 : Z0;
            for (int e = 0; e < elements; e++) {
                assertEquals(memory.raw(target[e], 8), element(core, destination, e, 8), "elemento " + e + " de " + word);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("everyVectorLength")
    void stnt1XzrStoresAtTheVectorAddressesUnchanged(int vl) {
        NtMemory memory = randomMemory(3000);
        Aarch64Core core = core(SVE2, vl, memory);
        int elements = vl / 64;
        setPredicate(core, P1, 3, allActive(elements));
        long[] target = prepareAddresses(core, FORMS.get(18), allActive(elements), 0, 17);
        for (int e = 0; e < elements; e++) {
            setElement(core, Z0, e, 8, 0x0101_0101_0101_0101L * (e + 1));
        }
        core.setX(OFFSET_REGISTER, 0x5555); // Rm = XZR: este valor não pode entrar na conta
        run(SVE2, core, memory, STNT1D_XZR);
        for (int e = 0; e < elements; e++) {
            assertEquals(0x0101_0101_0101_0101L * (e + 1), memory.raw(target[e], 8), "elemento " + e);
        }
    }

    /// O par escalar + vetor (`LD1_zprz`) usa `Xn` como base e `Zm[e]` como deslocamento; aqui os papéis se invertem. Um
    /// executor que confundisse os dois leria de `Xm + Zm[e]`: com `Z3` = endereços absolutos e `X2` = 0x30, o que o
    /// oráculo enxerga é uma leitura em `slot`, e não em `slot + 0x30`.
    @Test
    void theVectorIsTheBaseAndTheScalarIsTheOffsetNotTheOtherWayAround() {
        NtMemory memory = randomMemory(4000);
        Aarch64Core core = core(SVE2, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        for (int e = 0; e < 4; e++) {
            setElement(core, Z3, e, 8, DATA + 0x100L * e);
        }
        core.setX(OFFSET_REGISTER, OFFSET);
        run(SVE2, core, memory, FORMS.get(11).word());
        for (int e = 0; e < 4; e++) {
            assertEquals(memory.raw(DATA + 0x100L * e + OFFSET, 8), element(core, Z0, e, 8), "elemento " + e);
        }
        assertEquals(List.of(DATA + OFFSET, DATA + 0x100 + OFFSET, DATA + 0x200 + OFFSET, DATA + 0x300 + OFFSET),
                memory.touched);
    }

    /// Elemento de 32 bits: `Zn[e]` entra ZERO-estendido e a soma com `Xm` é de 64 bits. `Zn[e] = 0xFFFFF000` e
    /// `Xm = DATA - 0xFFFFF000` (módulo 2^64) somam `DATA`; com extensão de sinal cairiam fora da memória.
    @Test
    void aThirtyTwoBitElementIsZeroExtendedBeforeTheScalarIsAdded() {
        NtMemory memory = randomMemory(4001);
        Aarch64Core core = core(SVE2, 256, memory);
        setPredicate(core, P1, 2, allActive(8));
        long vectorValue = 0xFFFF_F000L;
        for (int e = 0; e < 8; e++) {
            setElement(core, Z3, e, 4, vectorValue);
        }
        core.setX(OFFSET_REGISTER, DATA - vectorValue);
        run(SVE2, core, memory, FORMS.get(4).word());
        assertEquals(0x14L, core.pc());
        for (int e = 0; e < 8; e++) {
            assertEquals(memory.raw(DATA, 4), element(core, Z0, e, 4), "elemento " + e);
        }
    }

    @Test
    void aLoadThatFaultsAbortsAtTheLowestFaultingElementWithoutTouchingTheDestination() {
        NtMemory memory = randomMemory(4002);
        Aarch64Core core = core(SVE2, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        setElement(core, Z3, 0, 8, DATA);
        setElement(core, Z3, 1, 8, UNMAPPED_ADDRESS);
        setElement(core, Z3, 2, 8, DATA);
        setElement(core, Z3, 3, 8, UNMAPPED_ADDRESS + 0x1000);
        core.setX(OFFSET_REGISTER, 0);
        fillZ(core, Z0, JUNK);
        run(SVE2, core, memory, FORMS.get(11).word());
        assertEquals(HANDLER, core.pc());
        assertEquals(UNMAPPED_ADDRESS, core.exceptionState().far(Aarch64ExceptionLevel.EL1));
        assertEquals(JUNK, core.scalable().zWord(Z0, 0), "aborto preciso: o destino não é escrito");
    }

    @Test
    void aStoreThatFaultsOnItsFirstActiveElementWritesNothing() {
        NtMemory memory = randomMemory(4003);
        Aarch64Core core = core(SVE2, 256, memory);
        setPredicate(core, P1, 3, allActive(4));
        setElement(core, Z3, 0, 8, UNMAPPED_ADDRESS);
        for (int e = 1; e < 4; e++) {
            setElement(core, Z3, e, 8, DATA);
        }
        core.setX(OFFSET_REGISTER, 0);
        memory.write32(INSTRUCTION_ADDRESS, FORMS.get(18).word());
        byte[] before = memory.bytes.clone();
        run(SVE2, core, memory, FORMS.get(18).word());
        assertEquals(HANDLER, core.pc());
        assertEquals(UNMAPPED_ADDRESS, core.exceptionState().far(Aarch64ExceptionLevel.EL1));
        assertArrayEquals(before, memory.bytes);
    }

    // ── Decoder ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyWordDecodesToTheOperationTheMnemonicNames() {
        for (Form form : FORMS) {
            Ir64Op op = decode(SVE2, form.word());
            if (form.store()) {
                SveMemoryOp64.Store store = assertInstanceOf(SveMemoryOp64.Store.class, op, form.name());
                assertEquals(SveMemoryOp64.Store.Op.SCATTER_VECTOR_PLUS_SCALAR, store.op(), form.name());
                assertEquals(form.msz(), store.msz(), form.name());
                assertEquals(form.esz(), store.esz(), form.name());
                assertEquals(Z0, store.rt(), form.name());
                assertEquals(Z3, store.rn(), form.name());
                assertEquals(OFFSET_REGISTER, store.rm(), form.name());
                assertEquals(P1, store.pg(), form.name());
                assertTrue(store.nonStreaming(), form.name());
            } else {
                SveMemoryOp64.Gather gather = assertInstanceOf(SveMemoryOp64.Gather.class, op, form.name());
                assertEquals(SveMemoryOp64.Gather.Op.VECTOR_PLUS_SCALAR, gather.op(), form.name());
                assertEquals(form.msz(), gather.msz(), form.name());
                assertEquals(form.esz(), gather.esz(), form.name());
                assertEquals(form.signed(), gather.signExtend(), form.name());
                assertFalse(gather.firstFault(), form.name() + ": não existe forma first-fault non-temporal");
                assertEquals(Z0, gather.rd(), form.name());
                assertEquals(Z3, gather.rn(), form.name());
                assertEquals(OFFSET_REGISTER, gather.rm(), form.name());
                assertEquals(P1, gather.pg(), form.name());
            }
        }
    }

    @Test
    void theNonTemporalFormsNeedSve2() {
        for (Form form : FORMS) {
            assertFalse(decodesAsGroup(SVE, form.word()), form.name() + " sem FEAT_SVE2");
            assertTrue(decodesAsGroup(SVE2, form.word()), form.name() + " com FEAT_SVE2");
        }
    }

    /// Combinações que o `trans_LDNT1_zprz`/`trans_STNT1_zprz` do QEMU e o `.decode` recusam (G8).
    @ParameterizedTest
    @ValueSource(ints = {
            0x85028460, // ldnt1sw com elemento de 32 bits: esz < msz + !u
            0x8582a460, // msz = 3 com elemento de 32 bits
            0x8582c460, // idem, bits[15:13] = 110
            0xc5828460, // "ldnt1sd": msz == esz sem u
            0x8402c460, // 32 bits com o bit 14 ligado (não é `10 u`)
            0xc402e470, // 64 bits com o bit 13 ligado (e rd[4] ligado: fora do prefetch)
            0xe5c22460, // stnt1 de 32 bits com msz = 3 > esz
            0xe4602460, // stnt1 com bits[22:21] = 11
            0xe4a02460}) // bits[22:21] = 01 com msz != 0 (só o ST1Q usa 01)
    void refusedCombinationsAreNotDecodedAsTheGroup(int word) {
        assertFalse(decodesAsGroup(SVE2, word), Integer.toHexString(word));
    }

    private static Ir64Op decode(Aarch64Architecture architecture, int word) {
        NtMemory memory = new NtMemory();
        memory.write32(0, word);
        try {
            return new Aarch64Decoder(architecture).decode(memory, 0);
        } catch (UnsupportedOperationException refused) {
            return null;
        }
    }

    private static boolean decodesAsGroup(Aarch64Architecture architecture, int word) {
        Ir64Op op = decode(architecture, word);
        return op instanceof SveMemoryOp64.Gather gather && gather.op() == SveMemoryOp64.Gather.Op.VECTOR_PLUS_SCALAR
                || op instanceof SveMemoryOp64.Store store && store.op() == SveMemoryOp64.Store.Op.SCATTER_VECTOR_PLUS_SCALAR;
    }

    // ── Modo streaming e acesso ──────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0x8402a460, 0xc582c460, 0xe4422460, 0xe5822460})
    void everyFormIsUndefinedInStreamingMode(int word) {
        NtMemory memory = randomMemory(5000);
        Aarch64Architecture architecture = Aarch64Architecture.extending(Aarch64Architecture.ARMV9_2_A, "teste-SVE2", Aarch64Feature.SVE2);
        Aarch64Core core = new Aarch64Core(memory, architecture, 256, 512);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        run(architecture, core, memory, SMSTART_SM);
        setPredicate(core, P1, 3, allActive(4));
        core.setX(OFFSET_REGISTER, DATA);
        run(architecture, core, memory, word);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
    }
}
