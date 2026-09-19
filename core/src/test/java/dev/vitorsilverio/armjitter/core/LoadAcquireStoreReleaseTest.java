package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// `LDA`/`LDAB`/`LDAH`/`LDAEX*`/`STL`/`STLB`/`STLH`/`STLEX*` (A32 + T32, ARMv8-A, B14.2) — 28
/// linhas de decodetree que generalizam o mesmo bloco exclusivo de `ArmDecoder`/
/// `Thumb2LoadStoreDecoder` que `LDREX`/`STREX` já usam (nibble `bits[11:8]`/`op4` novo).
/// `LDAEX*`/`STLEX*` reusam {@link InstructionKind#LOAD_EXCLUSIVE}/{@link InstructionKind#STORE_EXCLUSIVE}
/// (mesmo monitor de B1.4/B5.1, zero IrOp novo); `LDA*`/`STL*` reusam
/// {@link InstructionKind#LOAD}/{@link InstructionKind#STORE} (carga/escrita simples em `[Rn]`, sem
/// tocar o monitor). Ordenação acquire/release é NOP observável neste interpretador single-thread
/// (mesma decisão do lado A64).
class LoadAcquireStoreReleaseTest {
    // ── Encoders A32 (mesmo padrão de ArmV6ExclusiveAccessTest, nibble bits[11:8] generalizado) ─

    private static final int DISC_ACQUIRE_EXCLUSIVE = 0b1110;
    private static final int DISC_ACQUIRE_PLAIN = 0b1100;
    private static final int DISC_RESERVED_MIDDLE = 0b1101; // valor "do meio" sem instrução real

    private static int exclusiveFamilyA32(int discriminator, boolean load, int sz, int rt, int rn, int rmOrMarker) {
        int cond = 0xE000_0000;
        int fixed = 0x0180_0090; // bits27:24=0001, bit23=1, bits7:4=1001
        return cond | fixed | (sz << 21) | ((load ? 1 : 0) << 20) | (rn << 16) | (rt << 12)
                | (discriminator << 8) | rmOrMarker;
    }

    /// `LDAEX{,B,H,D} rd,[rn]`. `sz`: 00=word, 01=doubleword, 10=byte, 11=halfword.
    private static int ldaex(int sz, int rd, int rn) {
        return exclusiveFamilyA32(DISC_ACQUIRE_EXCLUSIVE, true, sz, rd, rn, 0xF);
    }

    /// `STLEX{,B,H,D} rd,rm,[rn]`.
    private static int stlex(int sz, int rd, int rn, int rm) {
        return exclusiveFamilyA32(DISC_ACQUIRE_EXCLUSIVE, false, sz, rd, rn, rm);
    }

    /// `LDA{,B,H} rt,[rn]`. `sz`: 00=word, 10=byte, 11=halfword (sem forma doubleword).
    private static int lda(int sz, int rt, int rn) {
        return exclusiveFamilyA32(DISC_ACQUIRE_PLAIN, true, sz, rt, rn, 0xF);
    }

    /// `STL{,B,H} rt,[rn]` — o "Rd" na posição 15:12 é sempre o marcador fixo `1111` (sem
    /// registrador de status: não é exclusivo); o dado transferido (`rt`) vai em bits 3:0.
    private static int stl(int sz, int rt, int rn) {
        return exclusiveFamilyA32(DISC_ACQUIRE_PLAIN, false, sz, 0xF, rn, rt);
    }

    /// Encoding com o nibble `bits[11:8]` num valor "do meio" (`1101`) que não corresponde a
    /// nenhuma instrução real — deve continuar `UNIMPLEMENTED` (Armadilha 7 da task, G8).
    private static int reservedMiddleDiscriminant(int rn, int rt) {
        return exclusiveFamilyA32(DISC_RESERVED_MIDDLE, true, 0b00, rt, rn, 0xF);
    }

    /// `LDREX{,B,H,D} rd,[rn]` — exclusivo clássico, para confirmar que a generalização do nibble
    /// não regrediu o caminho `1111` (discriminador) já existente.
    private static int ldrex(int sz, int rd, int rn) {
        return exclusiveFamilyA32(0b1111, true, sz, rd, rn, 0xF);
    }

    private static int strex(int sz, int rd, int rn, int rm) {
        return exclusiveFamilyA32(0b1111, false, sz, rd, rn, rm);
    }

    // ── Encoders T32 (mesmo padrão de Thumb2LoadStoreDecoderTest, op4 novo) ─────────────────────

    private static final int OP4_PLAIN_BYTE = 0b1000;
    private static final int OP4_PLAIN_HALF = 0b1001;
    private static final int OP4_PLAIN_WORD = 0b1010;
    private static final int OP4_RESERVED = 0b1011; // único valor sem instrução real no espaço novo
    private static final int OP4_EXCLUSIVE_BYTE = 0b1100;
    private static final int OP4_EXCLUSIVE_HALF = 0b1101;
    private static final int OP4_EXCLUSIVE_WORD = 0b1110;
    private static final int OP4_EXCLUSIVE_DOUBLE = 0b1111;

    /// `LDAEX{,B,H} rt,[rn]` T32 (op4 ∈ {1100,1101,1110}, forma sized sem offset).
    private static int ldaexT(int op4, int rn, int rt) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (op4 << 4) | 0xF;
    }

    /// `STLEX{,B,H} rd,rt,[rn]` T32.
    private static int stlexT(int op4, int rn, int rt, int rd) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (op4 << 4) | rd;
    }

    /// `LDAEXD rt,rt2,[rn]` T32 (`Rt2` campo independente, `Rt2==Rt+1` obrigatório).
    private static int ldaexdT(int rn, int rt, int rt2) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12)
                | (rt2 << 8) | (OP4_EXCLUSIVE_DOUBLE << 4) | 0xF;
    }

    /// `STLEXD rd,rt,rt2,[rn]` T32.
    private static int stlexdT(int rn, int rt, int rt2, int rd) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12)
                | (rt2 << 8) | (OP4_EXCLUSIVE_DOUBLE << 4) | rd;
    }

    /// `LDA{,B,H} rt,[rn]` T32 (op4 ∈ {1000,1001,1010}): `rt` (15:12) é o MESMO campo tanto para
    /// load quanto para store — bits 11:8 e 3:0 são SEMPRE marcadores fixos `1111`.
    private static int ldaT(int op4, int rn, int rt) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b101 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (op4 << 4) | 0xF;
    }

    /// `STL{,B,H} rt,[rn]` T32.
    private static int stlT(int op4, int rn, int rt) {
        return (0b1110100 << 25) | (1 << 23) | (1 << 22) | (0b100 << 20) | (rn << 16) | (rt << 12)
                | (0xF << 8) | (op4 << 4) | 0xF;
    }

    private static int reservedOp4T(int rn, int rt) {
        return ldaT(OP4_RESERVED, rn, rt);
    }

    // ── Runners ──────────────────────────────────────────────────────────────────────────────

    private static ArmCore newArmCore() {
        return new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV8A_32);
    }

    private static ArmCore newThumb2Core() {
        ArmCore core = new ArmCore(new TestAddressSpace(512), SwiDispatcher.empty(), ArmArchitecture.ARMV8A_32);
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void runArm(ArmCore core, int word) {
        core.memory().write32(core.programCounter(), word);
        core.step();
    }

    private static void runThumb2(ArmCore core, int hi, int lo) {
        TestAddressSpace memory = (TestAddressSpace) core.memory();
        int base = core.programCounter();
        memory.put16(base, hi);
        memory.put16(base + 2, lo);
        core.step();
    }

    // ── Gating: só decodifica sob ARMV8A_32 (G8) ────────────────────────────────────────────────

    @Test
    void ldaDecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, lda(0b00, 1, 0));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.LOAD, new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void stlDecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, stl(0b00, 1, 0));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.STORE, new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void ldaexDecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, ldaex(0b00, 1, 0));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.LOAD_EXCLUSIVE,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void stlexDecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, stlex(0b00, 1, 0, 2));
        assertEquals(InstructionKind.UNIMPLEMENTED, new ArmDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.STORE_EXCLUSIVE,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void thumb2PlainAcquireReleaseFormDecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = putThumb32(new TestAddressSpace(8), ldaT(OP4_PLAIN_WORD, 0, 1));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.LOAD, new ThumbDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void thumb2ExclusiveAcquireReleaseFormDecodesOnlyOnArmv8a32() {
        TestAddressSpace memory = putThumb32(new TestAddressSpace(8), ldaexT(OP4_EXCLUSIVE_WORD, 0, 1));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV7A).decode(memory, 0).kind());
        assertEquals(InstructionKind.LOAD_EXCLUSIVE,
                new ThumbDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    // ── G8: valores reservados do espaço de bits continuam UNDEFINED ───────────────────────────

    @Test
    void middleDiscriminantStaysUnimplementedOnArmv8a32() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, reservedMiddleDiscriminant(0, 1));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void thumb2ReservedOp4StaysUnimplementedOnArmv8a32() {
        TestAddressSpace memory = putThumb32(new TestAddressSpace(8), reservedOp4T(0, 1));
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ThumbDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    @Test
    void ldaDoubleWordFormDoesNotExist() {
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, lda(0b01, 1, 0)); // sz=01 (doubleword) na família não-exclusiva
        assertEquals(InstructionKind.UNIMPLEMENTED,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
    }

    // ── Execução: LDA lê, STL escreve ────────────────────────────────────────────────────────

    @Test
    void ldaLoadsTheCurrentMemoryValue() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 0x12345678);
        runArm(core, lda(0b00, 1, 0));
        assertEquals(0x12345678, core.register(1));
    }

    @Test
    void stlWritesTheRegisterToMemory() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        runArm(core, stl(0b00, 2, 0));
        assertEquals(0xCAFEBABE, core.memory().read32(0x10));
    }

    @Test
    void ldaThumb2LoadsTheCurrentMemoryValue() {
        ArmCore core = newThumb2Core();
        core.setRegister(1, 0x10);
        core.memory().write32(0x10, 0x12345678);
        runThumb2(core, hi(ldaT(OP4_PLAIN_WORD, 1, 2)), lo(ldaT(OP4_PLAIN_WORD, 1, 2)));
        assertEquals(0x12345678, core.register(2));
    }

    @Test
    void stlThumb2WritesTheRegisterToMemory() {
        ArmCore core = newThumb2Core();
        core.setRegister(1, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        runThumb2(core, hi(stlT(OP4_PLAIN_WORD, 1, 2)), lo(stlT(OP4_PLAIN_WORD, 1, 2)));
        assertEquals(0xCAFEBABE, core.memory().read32(0x10));
    }

    // ── Execução: LDAEX+STLEX fecham um par de exclusividade real ──────────────────────────────

    @Test
    void ldaexThenStlexToSameAddressSucceeds() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        runArm(core, ldaex(0b00, 1, 0));
        runArm(core, stlex(0b00, 3, 0, 2));
        assertEquals(0, core.register(3), "status deve ser 0 (sucesso)");
        assertEquals(0xCAFEBABE, core.memory().read32(0x10));
    }

    @Test
    void stlexWithoutPriorLdaexFailsAndLeavesMemoryIntact() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xDEADBEEF);
        core.memory().write32(0x10, 0x11111111);
        runArm(core, stlex(0b00, 3, 0, 2));
        assertEquals(1, core.register(3), "status deve ser 1 (falha)");
        assertEquals(0x11111111, core.memory().read32(0x10));
    }

    @Test
    void ldaexdLoadsThePairAndStlexdStoresBoth() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 0x11111111);
        core.memory().write32(0x14, 0x22222222);
        runArm(core, ldaex(0b01, 2, 0)); // LDAEXD r2:r3,[r0]
        assertEquals(0x11111111, core.register(2));
        assertEquals(0x22222222, core.register(3));

        core.setRegister(4, 0xAAAAAAAA);
        core.setRegister(5, 0xBBBBBBBB);
        runArm(core, stlex(0b01, 6, 0, 4)); // STLEXD r6,r4:r5,[r0]
        assertEquals(0, core.register(6));
        assertEquals(0xAAAAAAAA, core.memory().read32(0x10));
        assertEquals(0xBBBBBBBB, core.memory().read32(0x14));
    }

    // ── Armadilha 1: LDA NÃO marca o monitor — STREX/STLEX depois deve FALHAR ──────────────────

    @Test
    void ldaDoesNotMarkTheExclusiveMonitorSoSubsequentStrexFails() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        runArm(core, lda(0b00, 1, 0)); // LDA r1,[r0] — carga simples, monitor intocado
        runArm(core, strex(0b00, 3, 0, 2)); // STREX deve falhar: nenhum LDREX/LDAEX efetivo antes
        assertEquals(1, core.register(3), "STREX apos LDA (nao-exclusivo) deve falhar");
        assertEquals(0x11111111, core.memory().read32(0x10), "memoria nao pode ter sido tocada");
    }

    @Test
    void ldaexMarksTheSameMonitorAsClassicLdrexSoStrexSucceeds() {
        // LDAEX e LDREX marcam o MESMO monitor (B1.4/B5.1 compartilhado) — um STREX clássico
        // depois de um LDAEX deve suceder normalmente.
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        runArm(core, ldaex(0b00, 1, 0));
        runArm(core, strex(0b00, 3, 0, 2));
        assertEquals(0, core.register(3), "STREX apos LDAEX deve suceder (mesmo monitor)");
        assertEquals(0xCAFEBABE, core.memory().read32(0x10));
    }

    // ── T32: mesma armadilha 1, mesma cobertura de exclusividade ────────────────────────────────

    @Test
    void ldaThumb2DoesNotMarkTheExclusiveMonitor() {
        ArmCore core = newThumb2Core();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        runThumb2(core, hi(ldaT(OP4_PLAIN_WORD, 0, 1)), lo(ldaT(OP4_PLAIN_WORD, 0, 1)));
        runThumb2(core, hi(stlexT(OP4_EXCLUSIVE_WORD, 0, 2, 3)), lo(stlexT(OP4_EXCLUSIVE_WORD, 0, 2, 3)));
        assertEquals(1, core.register(3), "STLEX apos LDA (nao-exclusivo) deve falhar");
        assertEquals(0x11111111, core.memory().read32(0x10));
    }

    @Test
    void ldaexThenStlexToSameAddressSucceedsThumb2() {
        ArmCore core = newThumb2Core();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        runThumb2(core, hi(ldaexT(OP4_EXCLUSIVE_WORD, 0, 1)), lo(ldaexT(OP4_EXCLUSIVE_WORD, 0, 1)));
        runThumb2(core, hi(stlexT(OP4_EXCLUSIVE_WORD, 0, 2, 3)), lo(stlexT(OP4_EXCLUSIVE_WORD, 0, 2, 3)));
        assertEquals(0, core.register(3));
        assertEquals(0xCAFEBABE, core.memory().read32(0x10));
    }

    @Test
    void ldaexdThumb2LoadsThePairAndStlexdStoresBoth() {
        ArmCore core = newThumb2Core();
        core.setRegister(0, 0x10);
        core.memory().write32(0x10, 0x11111111);
        core.memory().write32(0x14, 0x22222222);
        runThumb2(core, hi(ldaexdT(0, 2, 3)), lo(ldaexdT(0, 2, 3)));
        assertEquals(0x11111111, core.register(2));
        assertEquals(0x22222222, core.register(3));

        core.setRegister(4, 0xAAAAAAAA);
        core.setRegister(5, 0xBBBBBBBB);
        runThumb2(core, hi(stlexdT(0, 4, 5, 6)), lo(stlexdT(0, 4, 5, 6)));
        assertEquals(0, core.register(6));
        assertEquals(0xAAAAAAAA, core.memory().read32(0x10));
        assertEquals(0xBBBBBBBB, core.memory().read32(0x14));
    }

    // ── UNPREDICTABLE (Rn/Rt=PC) vira UNDEFINED, não aceito silenciosamente ────────────────────

    @Test
    void ldaWithProgramCounterBaseOrDestinationIsUndefined() {
        int[] encodings = {lda(0b00, 1, 15), lda(0b00, 15, 0)};
        for (int encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
        }
    }

    @Test
    void stlWithProgramCounterBaseOrSourceIsUndefined() {
        int[] encodings = {stl(0b00, 1, 15), stl(0b00, 15, 0)};
        for (int encoding : encodings) {
            TestAddressSpace memory = new TestAddressSpace(8);
            memory.put32(0, encoding);
            assertEquals(InstructionKind.UNIMPLEMENTED,
                    new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
        }
    }

    // ── Regressão: LDREX/STREX clássicos não mudam sob o nibble generalizado ───────────────────

    @Test
    void classicExclusiveStillDecodesAndExecutesUnderArmv8a32() {
        ArmCore core = newArmCore();
        core.setRegister(0, 0x10);
        core.setRegister(2, 0xCAFEBABE);
        core.memory().write32(0x10, 0x11111111);
        TestAddressSpace memory = new TestAddressSpace(8);
        memory.put32(0, ldrex(0b00, 1, 0));
        assertEquals(InstructionKind.LOAD_EXCLUSIVE,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(memory, 0).kind());
        runArm(core, ldrex(0b00, 1, 0));
        runArm(core, strex(0b00, 3, 0, 2));
        assertEquals(0, core.register(3));
        assertEquals(0xCAFEBABE, core.memory().read32(0x10));
    }

    private static int hi(int combined) {
        return combined >>> 16;
    }

    private static int lo(int combined) {
        return combined & 0xFFFF;
    }

    /// Escreve os dois halfwords de uma instrução Thumb-2 de 32 bits em `address` (primeiro
    /// halfword no endereço mais baixo, igual a {@link #runThumb2}) — ao contrário de
    /// `memory.put32`, que grava os 4 bytes como uma palavra little-endian só e embaralharia a
    /// ordem hi/lo que o decoder espera.
    private static TestAddressSpace putThumb32(TestAddressSpace memory, int combined) {
        memory.put16(0, hi(combined));
        memory.put16(2, lo(combined));
        return memory;
    }
}
