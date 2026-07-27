package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Vetores para o lifter novo da task B6.4 (PR1). Palavras reais reaproveitadas do corpus
/// assemblado por `aarch64-none-elf-as`/`objdump` (mesmas de B6.1/`Aarch64DecoderCorpusTest`/
/// `Ir64BlockExecutorTest`) — nenhum encoding é inventado à mão.
class StandardIr64BlockLifterTest {
    private final StandardIr64BlockLifter lifter = new StandardIr64BlockLifter();

    // movz x0, #1 — nunca terminal, usada para preencher instruções "retas".
    private static final int MOVZ_X0_1 = 0xd2800020;
    // add x4, x5, #0x123 — imediato, nunca terminal.
    private static final int ADD_X4_X5_IMM = 0x91048ca4;
    // b <label> (incondicional) — corpus real, offsets 0x54->0x90.
    private static final int B_UNCONDITIONAL = 0x1400000f;
    // cbz x0, <label> — corpus real, offsets 0x68->0x90.
    private static final int CBZ_X0 = 0xb4000140;

    private static AddressSpace64 newMemory(int sizeBytes) {
        return AddressSpace64.wrapping(new TestAddressSpace(sizeBytes));
    }

    private static void putWord(AddressSpace64 memory, long address, int word) {
        memory.write32(address, word);
    }

    @Test
    void straightLineBlockStopsAtMaxInstructions() {
        AddressSpace64 memory = newMemory(64);
        for (int i = 0; i < 8; i++) {
            putWord(memory, i * 4L, MOVZ_X0_1);
        }

        Ir64Block block = lifter.lift(memory, 0, 3);

        assertEquals(0L, block.startPc());
        assertEquals(12L, block.endPc());
        // 3 instruções * 3 ops (Fetch, Cycle, op) = 9.
        assertEquals(9, block.operations().size());
        int[] kinds = block.kindsArray();
        for (int i = 0; i < 3; i++) {
            assertEquals(Ir64Op.Kind.FETCH, kinds[i * 3]);
            assertEquals(Ir64Op.Kind.CYCLE, kinds[i * 3 + 1]);
            assertEquals(Ir64Op.Kind.MOVE_WIDE, kinds[i * 3 + 2]);
        }
    }

    @Test
    void blockTerminatesAtUnconditionalBranch() {
        AddressSpace64 memory = newMemory(64);
        putWord(memory, 0x00, MOVZ_X0_1);
        putWord(memory, 0x04, ADD_X4_X5_IMM);
        putWord(memory, 0x08, B_UNCONDITIONAL);
        putWord(memory, 0x0c, MOVZ_X0_1); // não deveria entrar no bloco

        Ir64Block block = lifter.lift(memory, 0, 64);

        assertEquals(0x0cL, block.endPc());
        assertEquals(9, block.operations().size()); // 3 instruções, o branch é a última
        assertEquals(Ir64Op.Kind.BRANCH64, block.kindsArray()[8]);
    }

    @Test
    void blockTerminatesAtCompareBranch() {
        AddressSpace64 memory = newMemory(64);
        putWord(memory, 0x00, CBZ_X0);
        putWord(memory, 0x04, MOVZ_X0_1); // não deveria entrar no bloco

        Ir64Block block = lifter.lift(memory, 0, 64);

        assertEquals(0x04L, block.endPc());
        assertEquals(3, block.operations().size());
        assertEquals(Ir64Op.Kind.COMPARE_BRANCH64, block.kindsArray()[2]);
    }

    @Test
    void singleInstructionBlockStartsAndEndsCorrectly() {
        AddressSpace64 memory = newMemory(16);
        putWord(memory, 0x00, MOVZ_X0_1);

        Ir64Block block = lifter.lift(memory, 0, 1);

        assertEquals(0L, block.startPc());
        assertEquals(4L, block.endPc());
        assertEquals(3, block.operations().size());
    }
}
