package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeadCodeEliminationPassTest {
    private final DeadCodeEliminationPass pass = new DeadCodeEliminationPass();

    private static IrOp.Alu mov(int dst, int imm) {
        return new IrOp.Alu(IrOpCode.MOV, dst, 0, -1,
                new IrOperand.Immediate(imm), false, Condition.AL);
    }

    private static IrOp.Alu movFlags(int dst, int imm) {
        return new IrOp.Alu(IrOpCode.MOV, dst, 0, -1,
                new IrOperand.Immediate(imm), true, Condition.AL);
    }

    private static IrOp.Alu add(int dst, int src1, int imm) {
        return new IrOp.Alu(IrOpCode.ADD, dst, src1, -1,
                new IrOperand.Immediate(imm), false, Condition.AL);
    }

    private static IrOp.Alu addRead(int dst, int src1Reg, int src2Reg) {
        return new IrOp.Alu(IrOpCode.ADD, dst, src1Reg, -1,
                new IrOperand.Register(src2Reg), false, Condition.AL);
    }

    private static IrBlock block(IrOp... ops) {
        IrBlock.Builder b = IrBlock.builder(0).endPc(ops.length * 4);
        for (IrOp op : ops) b.add(op);
        return b.sealed();
    }

    @Test
    void overwrittenBeforeReadIsEliminated() {
        // MOV r0, #1 seguido de MOV r0, #2 — a primeira escrita em r0 é morta
        IrBlock before = block(mov(0, 1), mov(0, 2));
        IrBlock after = pass.optimize(before);

        assertEquals(1, after.operations().size());
        assertEquals(2, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void readBeforeOverwritePreservesOp() {
        // ADD r1, r0, #5 lê r0, então o MOV r0 antes dele precisa ser mantido
        IrBlock block = block(mov(0, 10), add(1, 0, 5));
        IrBlock after = pass.optimize(block);

        assertEquals(2, after.operations().size());
    }

    @Test
    void setFlagsOpNeverEliminated() {
        // Mesmo que dst seja sobrescrito depois, setFlags precisa ser preservado
        IrBlock block = block(movFlags(0, 1), mov(0, 2));
        IrBlock after = pass.optimize(block);

        assertEquals(2, after.operations().size());
    }

    @Test
    void parallelAluOperandsStayLive() {
        // MOV r1 é lido pela SADD16 antes de r1 ser reescrito — não pode ser eliminado
        // (mesma classe do bug de offset shiftado da B1.2: op nova sem case em regUse).
        IrOp.ParallelAlu sadd16 = new IrOp.ParallelAlu(
                dev.vitorsilverio.armjitter.ir.ParallelAluOp.ADD16,
                dev.vitorsilverio.armjitter.ir.ParallelAluVariant.SIGNED,
                2, 1, 1, Condition.AL);
        IrBlock block = block(mov(1, 5), sadd16, mov(1, 7));
        IrBlock after = pass.optimize(block);

        assertEquals(3, after.operations().size());
    }

    @Test
    void dst15NeverEliminated() {
        IrOp.Alu writePc = new IrOp.Alu(IrOpCode.MOV, 15, 0, -1,
                new IrOperand.Immediate(0x100), false, Condition.AL);
        IrOp.Alu overwrite = new IrOp.Alu(IrOpCode.MOV, 15, 0, -1,
                new IrOperand.Immediate(0x200), false, Condition.AL);
        IrBlock block = block(writePc, overwrite);
        IrBlock after = pass.optimize(block);

        // as duas escritas em PC precisam ser preservadas (PC = 15 → nunca sofre DCE)
        assertEquals(2, after.operations().size());
    }

    @Test
    void chainOfDeadWritesEliminated() {
        // MOV r0, #1 ; MOV r0, #2 ; MOV r0, #3 — as duas primeiras são mortas
        IrBlock block = block(mov(0, 1), mov(0, 2), mov(0, 3));
        IrBlock after = pass.optimize(block);

        assertEquals(1, after.operations().size());
        assertEquals(3, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void registerReadByLaterOpPreservesEarlierWrite() {
        // r0 escrito, depois r1 = r0 + r2 lê r0
        IrBlock block = block(mov(0, 42), addRead(1, 0, 2));
        IrBlock after = pass.optimize(block);

        assertEquals(2, after.operations().size());
    }

    @Test
    void noChangeReturnsSameInstance() {
        IrBlock block = block(mov(0, 1), addRead(1, 0, 2));
        assertSame(block, pass.optimize(block));
    }

    @Test
    void emptyBlockReturnsSameInstance() {
        IrBlock block = IrBlock.builder(0).endPc(0).sealed();
        assertSame(block, pass.optimize(block));
    }

    @Test
    void preservesStartAndEndPc() {
        IrBlock block = IrBlock.builder(0x200).endPc(0x210)
                .add(mov(0, 1)).add(mov(0, 2)).sealed();
        IrBlock after = pass.optimize(block);

        assertEquals(0x200, after.startPc());
        assertEquals(0x210, after.endPc());
    }

    @Test
    void deadWriteBeforeMultipleTransferIsEliminated() {
        // Store em r1 seguido de LDM que sobrescreve r1: o MOV r1 só é morto se r1 NÃO estiver na lista de load do LDM...
        // Na verdade: se o LDM carrega r1, ele escreve r1 → o MOV r1 anterior é morto
        // Simula: MOV r1 seguido de LDM que define r1 (LDM IrOp.MultipleTransfer load=true, mask bit1=1)
        IrOp.MultipleTransfer ldm = new IrOp.MultipleTransfer(
                true, 0, 0b10 /* r1 */, false, -1, false,
                dev.vitorsilverio.armjitter.decoder.BlockTransferMode.IA, false, Condition.AL);
        IrBlock block = block(mov(1, 99), ldm);
        IrBlock after = pass.optimize(block);

        assertEquals(1, after.operations().size());
        assertInstanceOf(IrOp.MultipleTransfer.class, after.operations().getFirst());
    }
}
