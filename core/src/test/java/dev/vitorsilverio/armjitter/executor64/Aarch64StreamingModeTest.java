package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
import dev.vitorsilverio.armjitter.arch64.Aarch64Feature;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64ScalableRegisters;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B18.2 — modo streaming com efeito real: `SMSTART`/`SMSTOP` (`MSR SVCRSM/SVCRZA/SVCRSMZA, #imm`), o zeramento
/// destrutivo de `Z`/`P`/`FFR`/`ZA`, o `VL` efetivo = `SVL`, o trap de acesso SME e as instruções ilegais
/// em streaming. Palavras conferidas contra `aarch64-none-elf-as` (devkitA64). Roda em `VL ≠ SVL`.
class Aarch64StreamingModeTest {
    private static final int SMSTART_SM = 0xd503437f;
    private static final int SMSTOP_SM = 0xd503427f;
    private static final int SMSTART_ZA = 0xd503457f;
    private static final int SMSTOP_ZA = 0xd503447f;
    private static final int SMSTART_BOTH = 0xd503477f;
    private static final int SMSTOP_BOTH = 0xd503467f;
    private static final int ADD_VECTOR = 0x4ea28420; // add v0.4s, v1.4s, v2.4s
    private static final int LD1_STRUCTURE = 0x4c407800; // ld1 {v0.4s}, [x0]
    private static final int FADD_SCALAR = 0x1e222820; // fadd s0, s1, s2
    private static final int UMOV_LANE0 = 0x0e043c00; // umov w0, v0.s[0]
    private static final int MOV_X0_1 = 0xd2800020;
    private static final int BRK_0 = 0xd4200000;
    private static final int ERET = 0xd69f03e0;

    private static final long PATTERN = 0xA5A5_5A5A_DEAD_BEEFL;
    private static final long FPSR_AFTER_STREAMING_CHANGE = 0x0800009FL;
    private static final long SVCR_SM = 1L;
    private static final long SVCR_ZA = 2L;
    private static final long SMCR_FA64 = 1L << 31;
    private static final long ESR_EC_SME = 0x1DL;
    private static final long ESR_EC_UNKNOWN = 0x00L;
    private static final long CPACR_SMEN_SHIFT = 24;
    private static final long SMTC_ACCESS_TRAP = 0L;
    private static final long VBAR = 0x400L;
    /// Exceção síncrona de EL0 (AArch64) entra em `VBAR + 0x400`.
    private static final long HANDLER = VBAR + 0x400L;

    private static final Aarch64Architecture WITH_FA64 = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV9_2_A, "teste-FA64", Aarch64Feature.SME_FA64);

    private static Aarch64Core core(Aarch64Architecture architecture, int vl, int svl) {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)), architecture,
                vl, svl);
        core.exceptionState().setVbar(Aarch64ExceptionLevel.EL1, VBAR);
        return core;
    }

    private static Aarch64Core core(int vl, int svl) {
        return core(Aarch64Architecture.ARMV9_2_A, vl, svl);
    }

    /// Escreve `words` instruções a partir do endereço 0 e executa-as uma a uma.
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

    private static void run(Aarch64Core core, int... words) {
        run(Aarch64Architecture.ARMV9_2_A, core, words);
    }

    /// Enche o banco INTEIRO (largura do banco, não só o `VL` efetivo) de `Z`, `P` e `FFR` com um padrão.
    private static void dirtyScalableBank(Aarch64Core core) {
        Aarch64ScalableRegisters bank = core.scalable();
        for (int reg = 0; reg < Aarch64ScalableRegisters.Z_REGISTER_COUNT; reg++) {
            for (int word = 0; word < bank.wordsPerVector(); word++) {
                bank.setZWord(reg, word, PATTERN + reg + word);
            }
        }
        for (int reg = 0; reg < Aarch64ScalableRegisters.P_REGISTER_COUNT; reg++) {
            for (int word = 0; word < bank.wordsPerPredicate(); word++) {
                bank.setPWord(reg, word, PATTERN ^ reg);
            }
        }
        for (int word = 0; word < bank.wordsPerPredicate(); word++) {
            bank.setFfrWord(word, PATTERN);
        }
    }

    private static boolean scalableBankIsAllZero(Aarch64Core core) {
        for (long word : core.scalable().snapshot()) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    // ── Efeito de SMSTART/SMSTOP ─────────────────────────────────────────────────────────────

    @Test
    void smstartSmAndSmstopSmToggleOnlyStreamingMode() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_SM);
        assertTrue(core.streamingModeEnabled());
        assertFalse(core.zaEnabled(), "SM e ZA são eixos independentes");
        assertEquals(4L, core.pc(), "SMSTART não é desvio: o PC avança");
        run(core, SMSTOP_SM);
        assertFalse(core.streamingModeEnabled());
    }

    @Test
    void smstartZaAndSmstopZaToggleOnlyZa() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_ZA);
        assertTrue(core.zaEnabled());
        assertFalse(core.streamingModeEnabled());
        assertTrue(core.matrix().zaAllocated());
        run(core, SMSTOP_ZA);
        assertFalse(core.zaEnabled());
        assertFalse(core.matrix().zaAllocated(), "desligar ZA libera o armazenamento");
    }

    @Test
    void smstartAndSmstopWithoutOperandAffectBothBits() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_BOTH);
        assertEquals(SVCR_SM | SVCR_ZA, core.svcr());
        run(core, SMSTOP_BOTH);
        assertEquals(0L, core.svcr());
    }

    @Test
    void anAliasLeavesTheBitItDoesNotNameUntouched() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_ZA, SMSTART_SM, SMSTOP_SM);
        assertTrue(core.zaEnabled(), "SMSTOP SM não desliga ZA");
        assertFalse(core.streamingModeEnabled());
    }

    // ── Zeramento destrutivo (a parte que software real DEPENDE) ─────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {SMSTART_SM, SMSTART_BOTH})
    void crossingIntoStreamingModeZeroesZPAndFfr(int word) {
        Aarch64Core core = core(256, 512);
        dirtyScalableBank(core);
        assertFalse(scalableBankIsAllZero(core));
        run(core, word);
        assertTrue(scalableBankIsAllZero(core), "Z/P/FFR zerados na entrada");
    }

    @ParameterizedTest
    @ValueSource(ints = {SMSTOP_SM, SMSTOP_BOTH})
    void crossingOutOfStreamingModeZeroesZPAndFfr(int word) {
        Aarch64Core core = core(256, 512);
        run(core, SMSTART_BOTH);
        dirtyScalableBank(core);
        run(core, word);
        assertTrue(scalableBankIsAllZero(core), "Z/P/FFR zerados na saída");
    }

    @Test
    void streamingModeChangeResetsFpsrAndFpmr() {
        Aarch64Core core = core(256, 256);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0x1234L);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPMR, 0x7L);
        run(core, SMSTART_SM);
        assertEquals(FPSR_AFTER_STREAMING_CHANGE, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR));
        assertEquals(0L, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPMR));
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0x99L);
        run(core, SMSTOP_SM);
        assertEquals(FPSR_AFTER_STREAMING_CHANGE, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR),
                "também na saída");
    }

    @Test
    void writingTheSameStreamingModeAgainChangesNothing() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_SM);
        dirtyScalableBank(core);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR, 0x1234L);
        run(core, SMSTART_SM);
        assertFalse(scalableBankIsAllZero(core), "SM 1→1 não é uma travessia de fronteira");
        assertEquals(0x1234L, core.readIntrinsicSystemRegister(Aarch64SystemRegisterId.FPSR));
    }

    @Test
    void streamingModeChangeDoesNotTouchZa() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_ZA);
        core.matrix().setZaWord(3, PATTERN);
        run(core, SMSTART_SM, SMSTOP_SM);
        assertEquals(PATTERN, core.matrix().zaWord(3), "ZA só é zerado pela mudança de PSTATE.ZA");
    }

    @Test
    void enablingZaZeroesItEvenAfterItWasAllocatedAndWrittenBefore() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_ZA);
        core.matrix().setZaWord(0, PATTERN);
        core.matrix().setZaWord(7, PATTERN);
        run(core, SMSTOP_ZA, SMSTART_ZA);
        assertEquals(0L, core.matrix().zaWord(0));
        assertEquals(0L, core.matrix().zaWord(7));
    }

    @Test
    void enablingZaZeroesZt0UnderSme2() {
        Aarch64Architecture sme2 = Aarch64Architecture.extending(
                Aarch64Architecture.ARMV9_2_A, "teste-SME2", Aarch64Feature.SCALABLE_MATRIX_EXTENSION_2);
        Aarch64Core core = core(sme2, 256, 256);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0xFL | (1L << 30));
        run(sme2, core, SMSTART_ZA);
        core.matrix().setZt0Word(2, PATTERN);
        run(sme2, core, SMSTOP_ZA, SMSTART_ZA);
        assertEquals(0L, core.matrix().zt0Word(2));
    }

    // ── VL efetivo = SVL em streaming (VL ≠ SVL, o único cenário em que um erro é observável) ──

    @ParameterizedTest
    @ValueSource(ints = {256, 512})
    void effectiveVectorLengthIsSvlOnlyWhileStreaming(int vl) {
        int svl = vl == 256 ? 512 : 256;
        Aarch64Core core = core(vl, svl);
        assertEquals(vl, core.vectorLengthBits());
        assertEquals(vl / 8, core.vectorLengthBytes());
        run(core, SMSTART_SM);
        assertEquals(svl, core.vectorLengthBits(), "em streaming o VL efetivo é o SVL");
        assertEquals(svl / 8, core.vectorLengthBytes());
        assertEquals(svl / 64, core.predicateLengthBytes());
        assertEquals(vl, core.implementedVectorLengthBits(), "o VL implementado (não streaming) não muda");
        run(core, SMSTOP_SM);
        assertEquals(vl, core.vectorLengthBits());
    }

    @Test
    void theBankHoldsTheLargerOfVlAndSvl() {
        Aarch64Core core = core(256, 512);
        assertEquals(512, core.scalable().vectorLengthBits(), "banco dimensionado por max(VL, SVL)");
        run(core, SMSTART_SM);
        int lastWord = core.scalable().wordsPerVector() - 1;
        core.scalable().setZWord(31, lastWord, PATTERN);
        assertEquals(PATTERN, core.scalable().zWord(31, lastWord), "Z31 cabe inteiro em SVL = 512");
    }

    @Test
    void smcrLenNarrowsTheStateOnlyWhileStreaming() {
        Aarch64Core core = core(256, 512);
        run(core, SMSTART_SM);
        dirtyScalableBank(core);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0L); // LEN=0 → SVL efetivo 128
        assertEquals(128, core.vectorLengthBits());
        assertNotEquals(0L, core.scalable().zWord(0, 0), "os 128 bits baixos ficam");
        assertEquals(0L, core.scalable().zWord(0, 2), "acima de SVL/128 é zerado");
        assertEquals(0L, core.scalable().zWord(31, core.scalable().wordsPerVector() - 1));
    }

    @Test
    void smcrLenDoesNotTouchTheNonStreamingState() {
        Aarch64Core core = core(256, 512);
        dirtyScalableBank(core);
        long before = core.scalable().zWord(0, 2);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0L);
        assertEquals(256, core.vectorLengthBits(), "fora de streaming SMCR não afeta o VL");
        assertEquals(before, core.scalable().zWord(0, 2));
    }

    @Test
    void zcrLenIsIgnoredWhileStreaming() {
        Aarch64Core core = core(512, 512);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.ZCR_EL1, 0L);
        assertEquals(128, core.vectorLengthBits());
        run(core, SMSTART_SM);
        assertEquals(512, core.vectorLengthBits(), "em streaming quem manda é SMCR, não ZCR");
    }

    // ── Terminal de bloco (Armadilha 3) ──────────────────────────────────────────────────────

    @Test
    void smstartEndsTheLiftedBlock() {
        Aarch64Core core = core(256, 512);
        core.memory().write32(0, MOV_X0_1);
        core.memory().write32(4, SMSTART_SM);
        core.memory().write32(8, MOV_X0_1);
        Ir64Block block = new StandardIr64BlockLifter(Aarch64Architecture.ARMV9_2_A).lift(core.memory(), 0, 8);
        int groupSize = 3; // Fetch, Cycle, op (G4)
        assertEquals(2 * groupSize, block.operationsArray().length,
                "o bloco fecha logo após SMSTART: o VL das seguintes muda");
    }

    @Test
    void smstartInsideABlockTakesEffectBeforeTheNextInstruction() {
        Aarch64Core core = core(256, 512);
        core.memory().write32(0, SMSTART_SM);
        core.memory().write32(4, MOV_X0_1);
        Ir64Block first = new StandardIr64BlockLifter(Aarch64Architecture.ARMV9_2_A).lift(core.memory(), 0, 8);
        new Ir64BlockExecutor(Aarch64Architecture.ARMV9_2_A).executeBlock(core, first);
        assertTrue(core.streamingModeEnabled());
        assertEquals(4L, core.pc(), "o bloco terminou depois do SMSTART, com o PC na instrução seguinte");
    }

    // ── Trap de acesso SME ───────────────────────────────────────────────────────────────────

    private static final class Cpacr implements Aarch64SystemRegisterBus {
        long value;

        @Override
        public boolean handles(Aarch64SystemRegisterId register) {
            return register == Aarch64SystemRegisterId.CPACR_EL1;
        }

        @Override
        public long read(Aarch64SystemRegisterId register) {
            return value;
        }

        @Override
        public void write(Aarch64SystemRegisterId register, long value) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void smstartWithoutSmeAccessTrapsInsteadOfExecuting() {
        Aarch64Core core = core(256, 256);
        Cpacr cpacr = new Cpacr();
        cpacr.value = 0b00L << CPACR_SMEN_SHIFT;
        core.setSystemRegisterBus(cpacr);
        run(core, SMSTART_SM);
        assertFalse(core.streamingModeEnabled(), "a instrução trapou, não executou");
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_SME, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26);
        assertEquals(SMTC_ACCESS_TRAP, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) & 0x7L);
        assertEquals(0L, core.exceptionState().elr(Aarch64ExceptionLevel.EL1), "ELR = a própria instrução");
    }

    @Test
    void smstartWithSmeAccessGrantedExecutes() {
        Aarch64Core core = core(256, 256);
        Cpacr cpacr = new Cpacr();
        cpacr.value = 0b11L << CPACR_SMEN_SHIFT;
        core.setSystemRegisterBus(cpacr);
        run(core, SMSTART_SM);
        assertTrue(core.streamingModeEnabled());
    }

    // ── Exceção e ERET não tocam SM/ZA ───────────────────────────────────────────────────────

    @Test
    void exceptionEntryAndEretPreserveStreamingModeAndZa() {
        Aarch64Core core = core(256, 512);
        run(core, SMSTART_BOTH);
        core.memory().write32(0x10, BRK_0);
        core.memory().write32(HANDLER, ERET);
        core.setProgramCounter(0x10);
        Ir64BlockExecutor executor = new Ir64BlockExecutor(Aarch64Architecture.ARMV9_2_A);
        executor.step(core); // brk → handler EL1
        assertEquals(HANDLER, core.pc());
        assertTrue(core.streamingModeEnabled(), "a entrada em EL1 não sai do modo streaming");
        assertTrue(core.zaEnabled());
        assertEquals(512, core.vectorLengthBits());
        executor.step(core); // eret
        assertTrue(core.streamingModeEnabled());
        assertTrue(core.zaEnabled());
    }

    // ── Instruções ilegais em streaming ──────────────────────────────────────────────────────

    private static void seedVectors(Aarch64Core core) {
        core.fp().setQ(1, 0x0000_0001_0000_0001L, 0x0000_0001_0000_0001L);
        core.fp().setQ(2, 0x0000_0002_0000_0002L, 0x0000_0002_0000_0002L);
    }

    @Test
    void advSimdVectorAddExecutesOutsideStreamingMode() {
        Aarch64Core core = core(256, 256);
        seedVectors(core);
        run(core, ADD_VECTOR);
        assertEquals(0x0000_0003_0000_0003L, core.fp().low64(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {ADD_VECTOR, LD1_STRUCTURE})
    void illegalInstructionInStreamingModeRaisesUndefinedInsteadOfExecuting(int word) {
        Aarch64Core core = core(256, 256);
        seedVectors(core);
        run(core, SMSTART_SM);
        core.setX(0, 0x200);
        core.memory().write32(0x4, word);
        core.setProgramCounter(0x4);
        new Ir64BlockExecutor(Aarch64Architecture.ARMV9_2_A).step(core);
        assertEquals(HANDLER, core.pc());
        assertEquals(ESR_EC_UNKNOWN, core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> 26,
                "EC=0: UNDEFINED, não o trap de SME");
        assertEquals(0x4L, core.exceptionState().elr(Aarch64ExceptionLevel.EL1));
        assertEquals(0L, core.fp().low64(0), "a instrução não executou");
    }

    @Test
    void legalInstructionsStillRunInStreamingMode() {
        Aarch64Core core = core(256, 256);
        run(core, SMSTART_SM);
        core.fp().setSFloat(1, 1.5f); // o SMSTART zerou V: semeia DEPOIS
        core.fp().setSFloat(2, 2.0f);
        run(core, FADD_SCALAR, UMOV_LANE0, MOV_X0_1);
        assertEquals(3.5f, core.fp().sFloat(0), "FADD escalar é permitido em streaming");
        assertEquals(1L, core.x(0));
        assertNotEquals(HANDLER, core.pc());
    }

    @Test
    void sameBlockRunsInBothModesBecauseTheDecisionIsMadeAtExecutionTime() {
        Aarch64Core core = core(256, 256);
        seedVectors(core);
        core.memory().write32(0, ADD_VECTOR);
        Ir64BlockExecutor executor = new Ir64BlockExecutor(Aarch64Architecture.ARMV9_2_A);
        Ir64Block block = new StandardIr64BlockLifter(Aarch64Architecture.ARMV9_2_A).lift(core.memory(), 0, 1);
        executor.executeBlock(core, block); // SM = 0: executa
        assertEquals(0x0000_0003_0000_0003L, core.fp().low64(0));
        core.setSvcr(SVCR_SM);
        core.setProgramCounter(0);
        core.fp().setQ(0, 0L, 0L);
        executor.executeBlock(core, block); // o MESMO bloco com SM = 1: UNDEFINED
        assertEquals(HANDLER, core.pc());
        assertEquals(0L, core.fp().low64(0));
    }

    @Test
    void fa64FeatureAndSmcrBitTogetherLiftTheRestriction() {
        Aarch64Core core = core(WITH_FA64, 256, 256);
        assertFalse(core.fa64Enabled(), "a feature sozinha não basta: SMCR_EL1.FA64 reseta em 0");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0xFL | SMCR_FA64);
        assertTrue(core.fa64Enabled());
        run(WITH_FA64, core, SMSTART_SM);
        seedVectors(core); // o SMSTART zerou V: semeia DEPOIS
        run(WITH_FA64, core, ADD_VECTOR);
        assertEquals(0x0000_0003_0000_0003L, core.fp().low64(0), "com FA64 o AdvSIMD roda em streaming");
    }

    @Test
    void fa64FeatureWithoutTheSmcrBitStillRestricts() {
        Aarch64Core core = core(WITH_FA64, 256, 256);
        seedVectors(core);
        run(WITH_FA64, core, SMSTART_SM, ADD_VECTOR);
        assertEquals(HANDLER, core.pc());
        assertTrue(core.streamingRestrictionApplies());
    }

    @Test
    void smcrFa64BitWithoutTheFeatureDoesNothing() {
        Aarch64Core core = core(256, 256);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0xFL | SMCR_FA64);
        assertFalse(core.fa64Enabled(), "FEAT_SME_FA64 ausente: o bit é armazenado mas não libera nada");
        run(core, SMSTART_SM);
        assertTrue(core.streamingRestrictionApplies());
    }

    @Test
    void fa64RequiresTheHigherLevelsThatAreModeled() {
        Aarch64Core core = core(WITH_FA64, 256, 256);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0xFL | SMCR_FA64);
        Aarch64SystemRegisterBus el2AndEl3 = new Aarch64SystemRegisterBus() {
            @Override
            public boolean handles(Aarch64SystemRegisterId register) {
                return register == Aarch64SystemRegisterId.CPTR_EL2 || register == Aarch64SystemRegisterId.CPTR_EL3;
            }

            @Override
            public long read(Aarch64SystemRegisterId register) {
                return 0L;
            }

            @Override
            public void write(Aarch64SystemRegisterId register, long value) {
                throw new UnsupportedOperationException();
            }
        };
        core.setSystemRegisterBus(el2AndEl3);
        assertFalse(core.fa64Enabled(), "SMCR_EL2.FA64 = 0 veta");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, 0xFL | SMCR_FA64);
        assertFalse(core.fa64Enabled(), "SMCR_EL3.FA64 = 0 veta");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, 0xFL | SMCR_FA64);
        assertTrue(core.fa64Enabled());
    }

    @Test
    void fa64OnlyConsultsTheLevelsAtOrAboveTheCurrentOne() {
        Aarch64Core core = core(WITH_FA64, 256, 256);
        Aarch64SystemRegisterBus el2AndEl3 = new Aarch64SystemRegisterBus() {
            @Override
            public boolean handles(Aarch64SystemRegisterId register) {
                return register == Aarch64SystemRegisterId.CPTR_EL2 || register == Aarch64SystemRegisterId.CPTR_EL3;
            }

            @Override
            public long read(Aarch64SystemRegisterId register) {
                return 0L;
            }

            @Override
            public void write(Aarch64SystemRegisterId register, long value) {
                throw new UnsupportedOperationException();
            }
        };
        core.setSystemRegisterBus(el2AndEl3);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, 0xFL | SMCR_FA64);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, 0xFL | SMCR_FA64);
        // SMCR_EL1.FA64 = 0, mas em EL2/EL3 o SMCR_EL1 não se aplica.
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        assertTrue(core.fa64Enabled(), "EL2 sofre SMCR_EL2 e SMCR_EL3, não SMCR_EL1");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL2, 0xFL);
        assertFalse(core.fa64Enabled(), "SMCR_EL2.FA64 = 0 veta em EL2");
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        assertTrue(core.fa64Enabled(), "EL3 só sofre SMCR_EL3");
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL3, 0xFL);
        assertFalse(core.fa64Enabled());
    }

    // ── SME sem SVE (o banco existe só por causa do streaming) ───────────────────────────────

    private static final Aarch64Architecture SME_WITHOUT_SVE = Aarch64Architecture.extending(
            Aarch64Architecture.ARMV8_0_A, "teste-SME-sem-SVE", Aarch64Feature.SCALABLE_MATRIX_EXTENSION);

    @Test
    void smeWithoutSveStillGetsABankWithPredicatesSizedBySvl() {
        Aarch64Core core = core(SME_WITHOUT_SVE, 256, 512);
        assertFalse(core.hasSve());
        assertTrue(core.hasSme());
        assertTrue(core.scalable().hasPredicates(), "Z/P/FFR existem para o modo streaming");
        assertEquals(512, core.scalable().vectorLengthBits());
        assertEquals(128, core.implementedVectorLengthBits(), "sem SVE o VL não streaming é o de V<n>");
        run(SME_WITHOUT_SVE, core, SMSTART_SM);
        assertEquals(512, core.vectorLengthBits());
        dirtyScalableBank(core);
        core.writeIntrinsicSystemRegister(Aarch64SystemRegisterId.SMCR_EL1, 0L);
        assertEquals(128, core.vectorLengthBits());
        assertEquals(0L, core.scalable().zWord(0, 2), "o estreitamento também vale sem SVE");
        run(SME_WITHOUT_SVE, core, SMSTOP_SM);
        assertTrue(scalableBankIsAllZero(core));
    }

    @Test
    void presetsWithoutSmeAreNeverAffected() {
        Aarch64Core core = core(Aarch64Architecture.ARMV9_0_A, 256, 256);
        seedVectors(core);
        assertFalse(core.streamingRestrictionApplies());
        run(Aarch64Architecture.ARMV9_0_A, core, ADD_VECTOR);
        assertEquals(0x0000_0003_0000_0003L, core.fp().low64(0));
    }
}
