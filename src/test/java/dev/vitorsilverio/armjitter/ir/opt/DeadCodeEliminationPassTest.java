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
        // MOV r0, #1 followed by MOV r0, #2 — first write to r0 is dead
        IrBlock before = block(mov(0, 1), mov(0, 2));
        IrBlock after = pass.optimize(before);

        assertEquals(1, after.operations().size());
        assertEquals(2, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void readBeforeOverwritePreservesOp() {
        // ADD r1, r0, #5 reads r0, so MOV r0 before it must be kept
        IrBlock block = block(mov(0, 10), add(1, 0, 5));
        IrBlock after = pass.optimize(block);

        assertEquals(2, after.operations().size());
    }

    @Test
    void setFlagsOpNeverEliminated() {
        // Even if dst is overwritten later, setFlags must be preserved
        IrBlock block = block(movFlags(0, 1), mov(0, 2));
        IrBlock after = pass.optimize(block);

        assertEquals(2, after.operations().size());
    }

    @Test
    void dst15NeverEliminated() {
        IrOp.Alu writePc = new IrOp.Alu(IrOpCode.MOV, 15, 0, -1,
                new IrOperand.Immediate(0x100), false, Condition.AL);
        IrOp.Alu overwrite = new IrOp.Alu(IrOpCode.MOV, 15, 0, -1,
                new IrOperand.Immediate(0x200), false, Condition.AL);
        IrBlock block = block(writePc, overwrite);
        IrBlock after = pass.optimize(block);

        // both PC writes must be preserved (PC = 15 → never DCE'd)
        assertEquals(2, after.operations().size());
    }

    @Test
    void chainOfDeadWritesEliminated() {
        // MOV r0, #1 ; MOV r0, #2 ; MOV r0, #3 — first two are dead
        IrBlock block = block(mov(0, 1), mov(0, 2), mov(0, 3));
        IrBlock after = pass.optimize(block);

        assertEquals(1, after.operations().size());
        assertEquals(3, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void registerReadByLaterOpPreservesEarlierWrite() {
        // r0 written, then r1 = r0 + r2 reads r0
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
        // Store to r1 then LDM that overwrites r1: the MOV r1 is dead only if r1 is NOT in LDM load list...
        // Actually: if LDM loads r1, it writes r1 → earlier MOV r1 is dead
        // Simulate: MOV r1 then LDM that defines r1 (LDM IrOp.MultipleTransfer load=true, mask bit1=1)
        IrOp.MultipleTransfer ldm = new IrOp.MultipleTransfer(
                true, 0, 0b10 /* r1 */, false, -1, false,
                dev.vitorsilverio.armjitter.decoder.BlockTransferMode.IA, false, Condition.AL);
        IrBlock block = block(mov(1, 99), ldm);
        IrBlock after = pass.optimize(block);

        assertEquals(1, after.operations().size());
        assertInstanceOf(IrOp.MultipleTransfer.class, after.operations().getFirst());
    }
}
