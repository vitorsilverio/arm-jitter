package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopSuperblockDetectorTest {
    private static final int BLOCK_A = 0x0000_0000;
    private static final int BLOCK_B = 0x0000_0100;
    private static final int MOV_R0_1 = 0xE3A00001;
    /// `b 0x100` a partir de 0x4 (offset = (0x100 - (0x4+8)) / 4 = 61).
    private static final int B_TO_BLOCK_B = 0xEA00003D;
    /// `b 0x0` a partir de 0x104 (offset = (0x0 - (0x104+8)) / 4 = -67).
    private static final int B_TO_BLOCK_A = 0xEAFFFFBD;

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
    void detectsPingPongCycleThroughRuntime() {
        JitRuntime runtime = JitRuntimeFactory.interpretedArmThumb(64, 1);
        runtime.setChainCycleBudget(40);
        runtime.setLoopSuperblocks(true);
        ArmCore core = new ArmCore(pingPongMemory(), SwiDispatcher.empty());
        core.setProgramCounter(BLOCK_A);

        // 8 confirmações × amostragem 1/64 => ~512 runs de corrente; 700 dá folga
        // (as primeiras execuções compilam/populam o IC antes de encadear).
        for (int i = 0; i < 700; i++) {
            runtime.execute(core.programCounter(), core);
        }

        List<LoopSuperblockDetector.Candidate> candidates =
                runtime.superblockDetector().candidates();
        assertEquals(1, candidates.size(), runtime.superblockDetector().summary());
        LoopSuperblockDetector.Candidate candidate = candidates.getFirst();
        // As correntes amostradas começam em A ou em B conforme onde o budget parou —
        // ambos são o MESMO ciclo; aceitamos qualquer rotação dele.
        if (candidate.headPc() == BLOCK_A) {
            assertArrayEquals(new int[]{BLOCK_A, BLOCK_B}, candidate.memberPcs());
        } else {
            assertEquals(BLOCK_B, candidate.headPc());
            assertArrayEquals(new int[]{BLOCK_B, BLOCK_A}, candidate.memberPcs());
        }
        assertEquals(InstructionSet.ARM, candidate.instructionSet());
        assertTrue(runtime.superblockDetector().summary().contains("candidatos=1"));
    }

    @Test
    void disabledDetectorKeepsChainingIdentical() {
        JitRuntime detecting = JitRuntimeFactory.interpretedArmThumb(64, 1);
        JitRuntime plain = JitRuntimeFactory.interpretedArmThumb(64, 1);
        detecting.setChainCycleBudget(40);
        plain.setChainCycleBudget(40);
        detecting.setLoopSuperblocks(true);

        ArmCore coreA = new ArmCore(pingPongMemory(), SwiDispatcher.empty());
        ArmCore coreB = new ArmCore(pingPongMemory(), SwiDispatcher.empty());
        coreA.setProgramCounter(BLOCK_A);
        coreB.setProgramCounter(BLOCK_A);
        for (int i = 0; i < 200; i++) {
            detecting.execute(coreA.programCounter(), coreA);
            plain.execute(coreB.programCounter(), coreB);
        }
        assertEquals(coreB.programCounter(), coreA.programCounter());
        assertEquals(coreB.cycles(), coreA.cycles());
        assertEquals(plain.chainedBlocks, detecting.chainedBlocks);
    }

    @Test
    void unitConfirmationRules() {
        LoopSuperblockDetector detector = new LoopSuperblockDetector();
        // Amostra determinística: o run 0 de cada janela de 64 é amostrado.
        for (int round = 0; round < 8; round++) {
            assertTrue(detector.startRun(0x1000, InstructionSet.ARM));
            assertTrue(detector.observeHop(0x2000, InstructionSet.ARM) == false);
            assertTrue(detector.observeHop(0x1000, InstructionSet.ARM)); // fecha o ciclo
            for (int skip = 0; skip < 63; skip++) {
                detector.startRun(0x1000, InstructionSet.ARM);
            }
        }
        assertEquals(1, detector.candidates().size());
        assertArrayEquals(new int[]{0x1000, 0x2000}, detector.candidates().getFirst().memberPcs());

        // Ciclo misto ARM/THUMB é descartado sem promover.
        LoopSuperblockDetector mixed = new LoopSuperblockDetector();
        assertTrue(mixed.startRun(0x1000, InstructionSet.ARM));
        assertTrue(mixed.observeHop(0x2000, InstructionSet.THUMB));
        assertEquals(0, mixed.candidates().size());
    }
}
