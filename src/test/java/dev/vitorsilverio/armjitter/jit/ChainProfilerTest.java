package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainProfilerTest {
    private static final int BLOCK_A = 0x0000_0000;
    private static final int BLOCK_B = 0x0000_0100;
    private static final int MOV_R0_1 = 0xE3A00001;
    /// `b 0x100` a partir de 0x4 (offset = (0x100 - (0x4+8)) / 4).
    private static final int B_TO_BLOCK_B = 0xEA00003D;
    /// `b 0x0` a partir de 0x104 (offset = (0x0 - (0x104+8)) / 4 = -67).
    private static final int B_TO_BLOCK_A = 0xEAFFFFBD;

    /// ROM sintética: bloco A = MOV+B->B; bloco B = MOV+B->A (ping-pongue infinito).
    private static AddressSpace pingPongMemory() {
        int[] words = new int[0x200 / 4];
        words[BLOCK_A / 4] = MOV_R0_1;
        words[BLOCK_A / 4 + 1] = B_TO_BLOCK_B;
        words[BLOCK_B / 4] = MOV_R0_1;
        words[BLOCK_B / 4 + 1] = B_TO_BLOCK_A;
        return new AddressSpace() {
            @Override public int read8(int address) { return (read32(address & ~3) >>> ((address & 3) * 8)) & 0xFF; }
            @Override public int read16(int address) { return (read32(address & ~3) >>> ((address & 2) * 8)) & 0xFFFF; }
            @Override public int read32(int address) { return words[(address & 0x1FF) / 4]; }
            @Override public void write8(int address, int value) { }
            @Override public void write16(int address, int value) { }
            @Override public void write32(int address, int value) { }
            @Override public boolean providesAccessCycles() { return false; }
        };
    }

    @Test
    void chainLoopRecordsPairsRunsAndBudgetBreak() {
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(64, 1);
        runtime.setChainCycleBudget(40);
        ChainProfiler profiler = new ChainProfiler();
        runtime.setChainProfiler(profiler);
        ArmCore core = new ArmCore(pingPongMemory(), SwiDispatcher.empty());

        // Aquece: compila A e B e povoa o inline cache (threshold=1).
        core.setProgramCounter(BLOCK_A);
        for (int i = 0; i < 4; i++) {
            runtime.execute(core.programCounter(), core);
        }
        long hopsBeforeChainRun = profiler.totalHops();

        // Execução quente: entra pelo IC e encadeia A->B->A->... até o orçamento.
        runtime.execute(core.programCounter(), core);
        assertTrue(profiler.totalHops() > hopsBeforeChainRun, "corrente quente deve registrar saltos");

        String report = profiler.report(10);
        assertTrue(report.contains("00000000 -> 00000100"), report);
        assertTrue(report.contains("00000100 -> 00000000"), report);
        assertTrue(report.contains("BUDGET"), report);
        assertTrue(report.contains("LOOP fechado"), report);
        // Estabilidade 100%: cada bloco tem um único sucessor.
        assertTrue(report.contains("100.0%") || report.contains("100,0%"), report);
    }

    @Test
    void disabledProfilerKeepsChainingBehaviorIdentical() {
        JitRuntime withProfiler = JitRuntimeFactory.interpretedArmThumb(64, 1);
        JitRuntime without = JitRuntimeFactory.interpretedArmThumb(64, 1);
        withProfiler.setChainCycleBudget(40);
        without.setChainCycleBudget(40);
        withProfiler.setChainProfiler(new ChainProfiler());

        ArmCore coreA = new ArmCore(pingPongMemory(), SwiDispatcher.empty());
        ArmCore coreB = new ArmCore(pingPongMemory(), SwiDispatcher.empty());
        coreA.setProgramCounter(0);
        coreB.setProgramCounter(0);
        for (int i = 0; i < 6; i++) {
            withProfiler.execute(coreA.programCounter(), coreA);
            without.execute(coreB.programCounter(), coreB);
        }
        assertEquals(coreB.programCounter(), coreA.programCounter());
        assertEquals(coreB.cycles(), coreA.cycles());
        assertEquals(without.chainedBlocks, withProfiler.chainedBlocks);
    }

    @Test
    void reportHandlesEmptyProfiler() {
        String report = new ChainProfiler().report(5);
        assertTrue(report.contains("runs=0"));
    }
}
