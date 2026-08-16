package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// `CPSR.E=1` / BE8 (task B1.8): acesso de dados "byte-invariant big-endian" (ARM DDI 0406C
/// A2.9) real, substituindo a `UnsupportedOperationException` da B1.5 MVP. Cobre a fork
/// alinhada (`read32Arm7`/`read16Arm7`/`write32Arm7`/`write16Arm7`, ARMv6K sem
/// `UNALIGNED_ACCESS` fora do escopo desta suíte — usamos `ARM11_MPCORE`, que TEM a feature,
/// para exercitar o caminho atravessado também) e a invariância de byte único e de fetch.
class ArmV6Be8DataEndiannessTest {
    /// `SETEND <endianness>` (cond=1111, forçado AL).
    private static int setend(boolean bigEndian) {
        return 0xF101_0000 | (bigEndian ? 1 << 9 : 0);
    }

    /// `LDR Rd,[Rn]` (offset imediato 0, cond AL).
    private static int ldr(int rn, int rd) {
        return 0xE590_0000 | (rn << 16) | (rd << 12);
    }

    /// `STR Rd,[Rn]` (offset imediato 0, cond AL).
    private static int str(int rn, int rd) {
        return 0xE580_0000 | (rn << 16) | (rd << 12);
    }

    /// `LDRH Rd,[Rn]` (offset imediato 0, unsigned, cond AL).
    private static int ldrh(int rn, int rd) {
        return 0xE1D0_00B0 | (rn << 16) | (rd << 12);
    }

    /// `STRH Rd,[Rn]` (offset imediato 0, cond AL).
    private static int strh(int rn, int rd) {
        return 0xE1C0_00B0 | (rn << 16) | (rd << 12);
    }

    /// `LDRB Rd,[Rn]` (offset imediato 0, cond AL).
    private static int ldrb(int rn, int rd) {
        return 0xE5D0_0000 | (rn << 16) | (rd << 12);
    }

    /// `STRB Rd,[Rn]` (offset imediato 0, cond AL).
    private static int strb(int rn, int rd) {
        return 0xE5C0_0000 | (rn << 16) | (rd << 12);
    }

    /// `LDMIA Rn!, {reglist}` (cond AL).
    private static int ldmia(int rn, int reglist) {
        return 0xE8B0_0000 | (rn << 16) | reglist;
    }

    /// `STMIA Rn!, {reglist}` (cond AL).
    private static int stmia(int rn, int reglist) {
        return 0xE8A0_0000 | (rn << 16) | reglist;
    }

    private static ArmCore newCore(TestAddressSpace memory) {
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    private static ArmCore run(ArmCore core, int... instructions) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        for (int i = 0; i < instructions.length; i++) {
            memory.put32(base + i * 4, instructions[i]);
        }
        for (int i = 0; i < instructions.length; i++) {
            core.step();
        }
        return core;
    }

    private static void writeBytes(TestAddressSpace memory, int base, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            memory.write8(base + i, bytes[i]);
        }
    }

    // ── LOAD word/halfword: bytes trocados com E=1, comparado a E=0 ──────────────

    @Test
    void wordLoadHasBytesSwappedWithBigEndianVsLittleEndian() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0x10, 0x11, 0x22, 0x33, 0x44);
        memory.put32(0, ldr(0, 1));
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x10);

        core.step();
        assertEquals(0x4433_2211, core.register(1), "E=0: composição little-endian normal");

        core.setProgramCounter(0);
        run(core, setend(true), ldr(0, 1));
        // BE8: o byte no endereço mais baixo (0x11) vira o mais significativo.
        assertEquals(0x1122_3344, core.register(1), "E=1: BE8 troca a ordem dos 4 bytes");
    }

    @Test
    void halfwordLoadHasBytesSwappedWithBigEndianVsLittleEndian() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0x10, 0x11, 0x22);
        memory.put32(0, ldrh(0, 1));
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x10);

        core.step();
        assertEquals(0x2211, core.register(1), "E=0: composição little-endian normal");

        core.setProgramCounter(0);
        run(core, setend(true), ldrh(0, 1));
        assertEquals(0x1122, core.register(1), "E=1: BE8 troca a ordem dos 2 bytes");
    }

    // ── STORE word/halfword: escrita em ordem trocada, ida-e-volta ───────────────

    @Test
    void wordStoreWithBigEndianRoundTripsThroughWordLoadWithBigEndian() {
        TestAddressSpace memory = new TestAddressSpace(32);
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x10);
        core.setRegister(1, 0x1122_3344);

        run(core, setend(true), str(0, 1));
        // BE8 STR: os bytes na memória ficam na ordem "big-endian" (MSB no endereço mais baixo).
        assertEquals(0x11, memory.read8(0x10) & 0xFF);
        assertEquals(0x22, memory.read8(0x11) & 0xFF);
        assertEquals(0x33, memory.read8(0x12) & 0xFF);
        assertEquals(0x44, memory.read8(0x13) & 0xFF);

        core.setRegister(2, 0);
        run(core, ldr(0, 2)); // ainda E=1
        assertEquals(0x1122_3344, core.register(2), "ida-e-volta: LDR com E=1 recompõe o valor original");
    }

    @Test
    void halfwordStoreWithBigEndianRoundTripsThroughHalfwordLoadWithBigEndian() {
        TestAddressSpace memory = new TestAddressSpace(32);
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x10);
        core.setRegister(1, 0x1122);

        run(core, setend(true), strh(0, 1));
        assertEquals(0x11, memory.read8(0x10) & 0xFF);
        assertEquals(0x22, memory.read8(0x11) & 0xFF);

        core.setRegister(2, 0);
        run(core, ldrh(0, 2));
        assertEquals(0x1122, core.register(2));
    }

    // ── Byte único: invariante a E (teste negativo explícito) ────────────────────

    @Test
    void byteLoadIsUnaffectedByBigEndian() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0x10, 0xAB, 0xCD);
        memory.put32(0, ldrb(0, 1));
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x10);

        core.step();
        int littleEndianResult = core.register(1);

        core.setProgramCounter(0);
        run(core, setend(true), ldrb(0, 1));

        assertEquals(littleEndianResult, core.register(1), "LDRB é invariante a CPSR.E");
        assertEquals(0xAB, core.register(1));
    }

    @Test
    void byteStoreIsUnaffectedByBigEndian() {
        TestAddressSpace memoryLe = new TestAddressSpace(32);
        memoryLe.put32(0, strb(0, 1));
        ArmCore coreLe = newCore(memoryLe);
        coreLe.setRegister(0, 0x10);
        coreLe.setRegister(1, 0xEF);
        coreLe.step();

        TestAddressSpace memoryBe = new TestAddressSpace(32);
        ArmCore coreBe = newCore(memoryBe);
        coreBe.setRegister(0, 0x10);
        coreBe.setRegister(1, 0xEF);
        run(coreBe, setend(true), strb(0, 1));

        assertEquals(memoryLe.read8(0x10), memoryBe.read8(0x10), "STRB é invariante a CPSR.E");
    }

    // ── Fetch de instrução: sempre little-endian, mesmo com E=1 ligado ───────────

    @Test
    void instructionFetchStaysLittleEndianEvenWithBigEndianDataSet() {
        // NOP (MOV r0,r0) decodifica igual antes e depois de SETEND BE — a busca da PRÓXIMA
        // instrução nunca troca de endianness, só os acessos de DADO feitos por ela (se algum).
        TestAddressSpace memory = new TestAddressSpace(32);
        int mov = 0xE1A0_0000; // MOV r0, r0 (NOP funcional)
        memory.put32(0, setend(true));
        memory.put32(4, mov);
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x1234_5678);

        run(core, setend(true), mov);

        assertEquals(0x1234_5678, core.register(0), "MOV r0,r0 decodificado/executado normalmente após SETEND BE");
        assertEquals(8, core.programCounter());
    }

    // ── LDM/STM: cobertos "de graça" pelo primitivo compartilhado — provar, não assumir ──

    @Test
    void multipleTransferWordsAreEachIndependentlyByteSwappedWithBigEndian() {
        TestAddressSpace memory = new TestAddressSpace(64);
        writeBytes(memory, 0x20, 0x11, 0x22, 0x33, 0x44);
        writeBytes(memory, 0x24, 0x55, 0x66, 0x77, 0x88);
        memory.put32(0, ldmia(0, 0b0000_0000_0000_0110)); // LDMIA r0!, {r1,r2}
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x20);

        run(core, setend(true), ldmia(0, 0b0000_0000_0000_0110));

        assertEquals(0x1122_3344, core.register(1), "1a word transferida, BE8 aplicado individualmente");
        assertEquals(0x5566_7788, core.register(2), "2a word transferida, BE8 aplicado individualmente");
        assertEquals(0x28, core.register(0), "writeback normal, endianness não afeta endereçamento");
    }

    @Test
    void multipleTransferStoreWordsRoundTripThroughLoadWithBigEndian() {
        TestAddressSpace memory = new TestAddressSpace(64);
        ArmCore core = newCore(memory);
        core.setRegister(0, 0x20); // base do STM
        core.setRegister(1, 0x1122_3344);
        core.setRegister(2, 0x5566_7788);

        run(core, setend(true), stmia(0, 0b0000_0000_0000_0110)); // STMIA r0!, {r1,r2}

        core.setRegister(3, 0x20);
        core.setRegister(4, 0);
        core.setRegister(5, 0);
        run(core, ldmia(3, 0b0000_0000_0011_0000)); // LDMIA r3!, {r4,r5} — ainda E=1

        assertEquals(0x1122_3344, core.register(4));
        assertEquals(0x5566_7788, core.register(5));
    }

    // ── Regressão G3: E=0 permanece bit a bit idêntico ao comportamento pré-B1.8 ─

    @Test
    void unalignedWordLoadWithLittleEndianStaysPinnedToLegacyBehavior() {
        // Mesmo vetor/resultado de ArmV6UnalignedAccessTest#unalignedWordLoadIsCrossedAtEveryOffsetWithTheFeature
        // (exemplo da task B1.7) — prova que a mudança desta task é a identidade quando E=0.
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88);
        memory.put32(16, ldr(0, 1));
        ArmCore core = newCore(memory);
        core.setRegister(0, 1);
        core.setProgramCounter(16);

        core.step();

        assertEquals(0x5544_3322, core.register(1), "offset+1 atravessado, E=0 idêntico ao pré-B1.8");
    }
}
