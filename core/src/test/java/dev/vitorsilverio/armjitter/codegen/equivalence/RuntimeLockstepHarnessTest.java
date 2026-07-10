package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePairFactory;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLockstepHarnessTest {
    private static final int BLOCK_A = 0x0000_0000;
    private static final int BLOCK_B = 0x0000_0100;
    private static final int MOV_R0_1 = 0xE3A00001;
    /// `add r1, r1, #1` — contador de voltas observável nos snapshots.
    private static final int ADD_R1_R1_1 = 0xE2811001;
    /// `b 0x100` a partir de 0x8 (offset = (0x100 - (0x8+8)) / 4 = 60).
    private static final int B_TO_BLOCK_B = 0xEA00003C;
    /// `b 0x0` a partir de 0x108 (offset = (0x0 - (0x108+8)) / 4 = -68).
    private static final int B_TO_BLOCK_A = 0xEAFFFFBC;
    private static final int CHAIN_BUDGET = 40;
    private static final int LOCKSTEP_CALLS = 50;

    /// Ping-pongue A<->B com contador em r1 (mesma base do ChainProfilerTest, sem SMC).
    private static TestAddressSpace pingPongMemory() {
        TestAddressSpace memory = new TestAddressSpace(0x200);
        memory.put32(BLOCK_A, MOV_R0_1);
        memory.put32(BLOCK_A + 4, ADD_R1_R1_1);
        memory.put32(BLOCK_A + 8, B_TO_BLOCK_B);
        memory.put32(BLOCK_B, MOV_R0_1);
        memory.put32(BLOCK_B + 4, ADD_R1_R1_1);
        memory.put32(BLOCK_B + 8, B_TO_BLOCK_A);
        return memory;
    }

    private static EquivalencePairFactory pairFactory() {
        return EquivalenceTestSupport.independentPair(
                pingPongMemory(), core -> core.setProgramCounter(BLOCK_A));
    }

    /// Runtimes CLÁSSICOS (síncronos): o tiered (`armThumb`) compila em background e o
    /// momento em que o chaining liga é não-determinístico — ver javadoc do harness.
    @SuppressWarnings("deprecation") // jvmArmThumb: único factory ASM síncrono, ideal p/ lockstep
    private static JitRuntime runtime(boolean jit, int chainBudget) {
        JitRuntime runtime = jit
                ? JitRuntimeFactory.jvmArmThumb(64, 1)
                : JitRuntimeFactory.interpretedArmThumb(64, 1);
        runtime.setChainCycleBudget(chainBudget);
        return runtime;
    }

    @Test
    void identicalRuntimesStayInLockstep() {
        assertDoesNotThrow(() -> new RuntimeLockstepHarness().assertLockstep(
                runtime(true, CHAIN_BUDGET), runtime(true, CHAIN_BUDGET),
                pairFactory(), LOCKSTEP_CALLS));
    }

    @Test
    void jitAndInterpreterStayInLockstep() {
        assertDoesNotThrow(() -> new RuntimeLockstepHarness().assertLockstep(
                runtime(true, CHAIN_BUDGET), runtime(false, CHAIN_BUDGET),
                pairFactory(), LOCKSTEP_CALLS));
    }

    @Test
    void chainingOffAndOnAlsoLockstep() {
        // Budget 0 dos DOIS lados: sem chaining, um bloco por chamada — ainda idêntico.
        assertDoesNotThrow(() -> new RuntimeLockstepHarness().assertLockstep(
                runtime(true, 0), runtime(false, 0), pairFactory(), LOCKSTEP_CALLS));
    }

    @Test
    void detectsGranularityDivergence() {
        // O ponto do harness: budgets diferentes mudam ONDE cada execute() para —
        // exatamente a classe de bug que o loop-superbloco (C0.3) poderia introduzir.
        EquivalenceMismatchException mismatch = assertThrows(EquivalenceMismatchException.class,
                () -> new RuntimeLockstepHarness().assertLockstep(
                        runtime(false, CHAIN_BUDGET), runtime(false, 0),
                        pairFactory(), LOCKSTEP_CALLS));
        assertTrue(mismatch.getMessage().contains("lockstep call"), mismatch.getMessage());
    }
}
