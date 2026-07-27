package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// `executeBlock`/`executeOp` (B6.4, PR1, decisão D2): provam que executar um {@link Ir64Block}
/// inteiro de uma vez produz o MESMO estado final que chamar {@link Ir64BlockExecutor#step} a
/// mesma quantidade de vezes — a garantia estrutural da qual o backend ASM (D-ASM) depende.
class Ir64BlockExecutorBlockTest {
    private static final int MOVZ_X0_1 = 0xd2800020;      // movz x0, #1
    private static final int MOVK_X0_2_16 = 0xf2a00040;   // movk x0, #2, lsl #16
    private static final int ADD_X4_X5_IMM = 0x91048ca4;  // add x4, x5, #0x123
    private static final int B_UNCONDITIONAL = 0x1400000f; // b (offset +0x3c)

    private static Aarch64Core newCore(int memorySizeBytes) {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(memorySizeBytes)));
    }

    private static void putWord(Aarch64Core core, long address, int word) {
        core.memory().write32(address, word);
    }

    @Test
    void executeBlockMatchesStepStepStepOnStraightLine() {
        Aarch64Core stepCore = newCore(64);
        putWord(stepCore, 0x00, MOVZ_X0_1);
        putWord(stepCore, 0x04, MOVK_X0_2_16);
        putWord(stepCore, 0x08, ADD_X4_X5_IMM);
        stepCore.setX(5, 10L);
        Ir64BlockExecutor stepExecutor = new Ir64BlockExecutor();
        int stepCycles = 0;
        stepCycles += stepExecutor.step(stepCore);
        stepCycles += stepExecutor.step(stepCore);
        stepCycles += stepExecutor.step(stepCore);

        Aarch64Core blockCore = newCore(64);
        putWord(blockCore, 0x00, MOVZ_X0_1);
        putWord(blockCore, 0x04, MOVK_X0_2_16);
        putWord(blockCore, 0x08, ADD_X4_X5_IMM);
        blockCore.setX(5, 10L);
        Ir64Block block = new StandardIr64BlockLifter().lift(blockCore.memory(), 0, 3);
        int blockCycles = new Ir64BlockExecutor().executeBlock(blockCore, block);

        assertEquals(stepCore.x(0), blockCore.x(0));
        assertEquals(stepCore.x(4), blockCore.x(4));
        assertEquals(stepCore.pc(), blockCore.pc());
        assertEquals(stepCycles, blockCycles);
    }

    @Test
    void executeBlockAdvancesPcCorrectlyEvenWithoutBranch() {
        Aarch64Core core = newCore(64);
        putWord(core, 0x00, MOVZ_X0_1);
        putWord(core, 0x04, MOVK_X0_2_16);
        Ir64Block block = new StandardIr64BlockLifter().lift(core.memory(), 0, 2);

        new Ir64BlockExecutor().executeBlock(core, block);

        assertEquals(8L, core.pc());
        assertEquals(0x0002_0001L, core.x(0));
    }

    @Test
    void executeBlockStopsAdvancingAtTakenBranch() {
        Aarch64Core core = newCore(0x100);
        putWord(core, 0x08, B_UNCONDITIONAL); // b +0x3c -> 0x44
        Ir64Block block = new StandardIr64BlockLifter().lift(core.memory(), 0x08, 64);

        int cycles = new Ir64BlockExecutor().executeBlock(core, block);

        assertEquals(0x08L + 0x3cL, core.pc());
        assertEquals(1, cycles);
    }
}
