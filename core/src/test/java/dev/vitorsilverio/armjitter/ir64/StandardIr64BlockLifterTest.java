package dev.vitorsilverio.armjitter.ir64;

import dev.vitorsilverio.armjitter.arch64.Aarch64Architecture;
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

    /// B11.2: o construtor com arquitetura explícita ainda não gateia nenhum decode (isso é
    /// B11.4) — o bloco liftado deve ser IDÊNTICO ao do construtor sem argumento (zero-diff, G3).
    @Test
    void liftIsIdenticalRegardlessOfArchitecture() {
        AddressSpace64 memory = newMemory(16);
        putWord(memory, 0, MOVZ_X0_1);

        Ir64Block fromDefault = new StandardIr64BlockLifter().lift(memory, 0, 1);
        Ir64Block fromArmv9_5A = new StandardIr64BlockLifter(Aarch64Architecture.ARMV9_5_A)
                .lift(memory, 0, 1);

        assertEquals(fromDefault.operations(), fromArmv9_5A.operations());
    }

    @Test
    void constructorRejectsNullArchitecture() {
        assertThrows(NullPointerException.class, () -> new StandardIr64BlockLifter(null));
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

    // ── Fim de bloco em instruções que redirecionam o fluxo (F11, achado real) ─────────────────────

    private static final int ERET = 0xd69f03e0;
    private static final int HVC_0 = 0xd4000002;
    private static final int SMC_0 = 0xd4000003;
    private static final int BRK_1 = 0xd4200020;
    private static final int WFI = 0xd503207f;
    private static final int HLT = 0xd44acf00; // hlt #0x5678: decodifica como UndefinedInstructionTrap
    private static final int ISB = 0xd5033fdf;
    private static final int NOP = 0xd503201f;
    private static final int B_SELF = 0x14000000; // b .

    /// `movz; <instrução>; movz; movz; b .` — com uma instrução terminal o bloco acaba logo depois dela
    /// (`endPc == 8`); sem, vai até o `b .` do fim (`endPc == 20`).
    private Ir64Block liftAroundTerminal(int terminal) {
        AddressSpace64 memory = newMemory(64);
        putWord(memory, 0, MOVZ_X0_1);
        putWord(memory, 4, terminal);
        putWord(memory, 8, MOVZ_X0_1);
        putWord(memory, 12, MOVZ_X0_1);
        putWord(memory, 16, B_SELF);
        return lifter.lift(memory, 0, 10);
    }

    @Test
    void blockEndsRightAfterEret() {
        Ir64Block block = liftAroundTerminal(ERET);

        assertEquals(8, block.endPc(), "nada depois do eret pode entrar no bloco");
        assertEquals(Ir64Op.Kind.EXCEPTION_RETURN, lastOp(block).kind());
    }

    @Test
    void blockEndsRightAfterHvcAndSmc() {
        assertEquals(8, liftAroundTerminal(HVC_0).endPc());
        assertEquals(Ir64Op.Kind.PRIVILEGED_CALL, lastOp(liftAroundTerminal(HVC_0)).kind());
        assertEquals(8, liftAroundTerminal(SMC_0).endPc());
    }

    @Test
    void blockEndsRightAfterBrkAndUndefined() {
        assertEquals(8, liftAroundTerminal(BRK_1).endPc());
        assertEquals(Ir64Op.Kind.BREAKPOINT, lastOp(liftAroundTerminal(BRK_1)).kind());
        assertEquals(8, liftAroundTerminal(HLT).endPc());
        assertEquals(Ir64Op.Kind.UNDEFINED_INSTRUCTION_TRAP, lastOp(liftAroundTerminal(HLT)).kind());
    }

    @Test
    void blockEndsRightAfterWfiButNotAfterOtherSystemInstructions() {
        assertEquals(8, liftAroundTerminal(WFI).endPc());
        assertEquals(20, liftAroundTerminal(ISB).endPc(), "ISB não redireciona o fluxo");
        assertEquals(20, liftAroundTerminal(NOP).endPc(), "NOP não redireciona o fluxo");
    }

    private static Ir64Op lastOp(Ir64Block block) {
        return block.operations().get(block.operations().size() - 1);
    }
}
