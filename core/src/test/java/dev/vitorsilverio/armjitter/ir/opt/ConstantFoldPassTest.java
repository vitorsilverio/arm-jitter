package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstantFoldPassTest {
    private final ConstantFoldPass pass = new ConstantFoldPass();

    private static IrBlock block(IrOp... ops) {
        IrBlock.Builder b = IrBlock.builder(0).endPc(4);
        for (IrOp op : ops) b.add(op);
        return b.sealed();
    }

    private static IrOp.Alu alu(IrOpCode op, int dst, int src1Override, int imm) {
        return new IrOp.Alu(op, dst, 0, src1Override, new IrOperand.Immediate(imm), false, Condition.AL);
    }

    private static IrOp.Alu aluFlags(IrOpCode op, int dst, int src1Override, int imm) {
        return new IrOp.Alu(op, dst, 0, src1Override, new IrOperand.Immediate(imm), true, Condition.AL);
    }

    @Test
    void movWithImmediateIsReplaced() {
        IrBlock before = block(alu(IrOpCode.MOV, 0, -1, 42));
        IrBlock after = pass.optimize(before);

        assertEquals(1, after.operations().size());
        IrOp.Alu folded = (IrOp.Alu) after.operations().getFirst();
        assertEquals(IrOpCode.MOV, folded.opcode());
        assertEquals(42, ((IrOperand.Immediate) folded.src2()).value());
    }

    @Test
    void addWithBothKnownFoldsToMov() {
        IrBlock block = block(alu(IrOpCode.ADD, 1, 10, 5));
        IrBlock after = pass.optimize(block);

        IrOp.Alu folded = (IrOp.Alu) after.operations().getFirst();
        assertEquals(IrOpCode.MOV, folded.opcode());
        assertEquals(15, ((IrOperand.Immediate) folded.src2()).value());
    }

    @Test
    void subWithBothKnownFoldsToMov() {
        IrBlock block = block(alu(IrOpCode.SUB, 2, 20, 7));
        IrBlock after = pass.optimize(block);

        assertEquals(13, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void andWithBothKnownFolds() {
        IrBlock block = block(alu(IrOpCode.AND, 3, 0xFF, 0x0F));
        IrBlock after = pass.optimize(block);

        assertEquals(0x0F, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void mvnFolds() {
        IrBlock block = block(alu(IrOpCode.MVN, 0, -1, 0));
        IrBlock after = pass.optimize(block);

        assertEquals(-1, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void negFolds() {
        IrBlock block = block(alu(IrOpCode.NEG, 0, -1, 5));
        IrBlock after = pass.optimize(block);

        assertEquals(-5, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void lslWithKnownOperandsFolds() {
        IrBlock block = block(alu(IrOpCode.LSL, 0, 1, 3));  // 1 << 3 = 8
        IrBlock after = pass.optimize(block);

        assertEquals(8, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().getFirst()).src2()).value());
    }

    @Test
    void setFlagsPreventsConstantFold() {
        IrBlock block = block(aluFlags(IrOpCode.ADD, 0, 5, 3));
        IrBlock after = pass.optimize(block);

        assertSame(block, after);
    }

    @Test
    void dst15PreventsConstantFold() {
        IrBlock block = block(alu(IrOpCode.ADD, 15, 5, 3));
        IrBlock after = pass.optimize(block);

        assertSame(block, after);
    }

    @Test
    void unknownSrc1PreventsConstantFold() {
        // src1ValueOverride = -1 (unknown), opcode that needs src1
        IrBlock block = block(new IrOp.Alu(IrOpCode.ADD, 0, 1, -1,
                new IrOperand.Immediate(5), false, Condition.AL));
        IrBlock after = pass.optimize(block);

        assertSame(block, after);
    }

    @Test
    void adcSkippedDueToCarryDependence() {
        IrBlock block = block(alu(IrOpCode.ADC, 0, 5, 3));
        IrBlock after = pass.optimize(block);

        assertSame(block, after);
    }

    @Test
    void cmpNotFoldedNoRegisterWrite() {
        IrBlock block = block(new IrOp.Alu(IrOpCode.CMP, 0, 0, 5,
                new IrOperand.Immediate(5), true, Condition.AL));
        IrBlock after = pass.optimize(block);

        assertSame(block, after);
    }

    @Test
    void identityOnBlockWithNoFoldableOps() {
        IrOp.Alu unfoldable = new IrOp.Alu(IrOpCode.ADD, 0, 1, -1,
                new IrOperand.Immediate(1), false, Condition.AL);
        IrBlock block = block(unfoldable);

        assertSame(block, pass.optimize(block));
    }

    @Test
    void multipleOpsPartiallyFolded() {
        IrOp foldable = alu(IrOpCode.ADD, 0, 3, 2);
        IrOp unfoldable = new IrOp.Alu(IrOpCode.SUB, 1, 2, -1,
                new IrOperand.Immediate(1), false, Condition.AL);
        IrBlock block = block(foldable, unfoldable);
        IrBlock after = pass.optimize(block);

        assertEquals(2, after.operations().size());
        assertEquals(IrOpCode.MOV, ((IrOp.Alu) after.operations().get(0)).opcode());
        assertEquals(5, ((IrOperand.Immediate) ((IrOp.Alu) after.operations().get(0)).src2()).value());
        assertEquals(IrOpCode.SUB, ((IrOp.Alu) after.operations().get(1)).opcode());
    }

    @Test
    void foldedOpIsNativeSupported() {
        // After folding, the result should be a MOV with Immediate → always native-supported
        IrBlock block = block(alu(IrOpCode.ADD, 0, 10, 20));
        IrBlock after = pass.optimize(block);

        IrOp.Alu folded = (IrOp.Alu) after.operations().getFirst();
        assertInstanceOf(IrOperand.Immediate.class, folded.src2());
        assertEquals(IrOpCode.MOV, folded.opcode());
    }

    @Test
    void preservesStartAndEndPc() {
        IrBlock block = IrBlock.builder(0x100).endPc(0x108).add(alu(IrOpCode.MOV, 0, -1, 7)).sealed();
        IrBlock after = pass.optimize(block);

        assertEquals(0x100, after.startPc());
        assertEquals(0x108, after.endPc());
    }
}
