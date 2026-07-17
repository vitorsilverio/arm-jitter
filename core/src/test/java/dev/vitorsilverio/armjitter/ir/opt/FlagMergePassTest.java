package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagMergePassTest {
    private final FlagMergePass pass = new FlagMergePass();

    private static IrOp.Alu aluF(IrOpCode op, int dst, int src1Reg, int immVal) {
        // setFlags = true
        return new IrOp.Alu(op, dst, src1Reg, -1,
                new IrOperand.Immediate(immVal), true, Condition.AL);
    }

    private static IrOp.Alu alu(IrOpCode op, int dst, int src1Reg, int immVal) {
        // setFlags = false
        return new IrOp.Alu(op, dst, src1Reg, -1,
                new IrOperand.Immediate(immVal), false, Condition.AL);
    }

    private static IrBlock block(IrOp... ops) {
        IrBlock.Builder b = IrBlock.builder(0).endPc(ops.length * 4);
        for (IrOp op : ops) b.add(op);
        return b.sealed();
    }

    @Test
    void singleSetFlagsAtEndIsKept() {
        // Flags na saída do bloco são conservadoramente vivas
        IrBlock block = block(aluF(IrOpCode.ADD, 0, 1, 1));
        IrBlock after = pass.optimize(block);

        assertSame(block, after);
    }

    @Test
    void twoSetFlagsInSequenceFirstDropped() {
        // ADD setFlags, depois CMP setFlags — as primeiras flags são sobrescritas antes da saída
        IrBlock block = block(
                aluF(IrOpCode.ADD, 0, 1, 1),
                aluF(IrOpCode.ADD, 2, 3, 4));
        IrBlock after = pass.optimize(block);

        IrOp.Alu first = (IrOp.Alu) after.operations().get(0);
        IrOp.Alu second = (IrOp.Alu) after.operations().get(1);

        assertFalse(first.setFlags(), "first setFlags should be removed");
        assertTrue(second.setFlags(), "last setFlags must be kept");
    }

    @Test
    void threeSetFlagsOnlyLastKept() {
        IrBlock block = block(
                aluF(IrOpCode.ADD, 0, 1, 1),
                aluF(IrOpCode.ADD, 2, 3, 2),
                aluF(IrOpCode.SUB, 4, 5, 3));
        IrBlock after = pass.optimize(block);

        assertFalse(((IrOp.Alu) after.operations().get(0)).setFlags());
        assertFalse(((IrOp.Alu) after.operations().get(1)).setFlags());
        assertTrue(((IrOp.Alu) after.operations().get(2)).setFlags());
    }

    @Test
    void adcBetweenSetFlagsPreservesFirst() {
        // ADD setFlags, ADC (lê carry), ADD setFlags — as primeiras flags precisam ser mantidas porque ADC lê o carry
        IrOp.Alu adc = new IrOp.Alu(IrOpCode.ADC, 2, 3, -1,
                new IrOperand.Immediate(0), false, Condition.AL);
        IrBlock block = block(
                aluF(IrOpCode.ADD, 0, 1, 1),
                adc,
                aluF(IrOpCode.ADD, 4, 5, 2));
        IrBlock after = pass.optimize(block);

        assertTrue(((IrOp.Alu) after.operations().get(0)).setFlags(),
                "first setFlags must be kept because ADC reads carry");
    }

    @Test
    void sbcPreservesCarryFlagSource() {
        IrOp.Alu sbc = new IrOp.Alu(IrOpCode.SBC, 2, 3, -1,
                new IrOperand.Immediate(0), false, Condition.AL);
        IrBlock block = block(aluF(IrOpCode.SUB, 0, 1, 5), sbc);
        IrBlock after = pass.optimize(block);

        assertTrue(((IrOp.Alu) after.operations().get(0)).setFlags(),
                "setFlags kept because SBC reads carry");
    }

    @Test
    void nonFlagOpBetweenTwoSetFlagsDoesNotPreserveFirst() {
        // MOV (sem setFlags) entre dois setFlags → o primeiro continua morto
        IrBlock block = block(
                aluF(IrOpCode.ADD, 0, 1, 1),
                alu(IrOpCode.MOV, 3, 0, 0),
                aluF(IrOpCode.SUB, 4, 5, 1));
        IrBlock after = pass.optimize(block);

        assertFalse(((IrOp.Alu) after.operations().get(0)).setFlags());
        assertTrue(((IrOp.Alu) after.operations().get(2)).setFlags());
    }

    @Test
    void noChangeReturnsSameInstance() {
        // Só um setFlags no final
        IrBlock block = block(aluF(IrOpCode.CMP, 0, 1, 0));
        assertSame(block, pass.optimize(block));
    }

    @Test
    void preservesStartAndEndPc() {
        IrBlock block = IrBlock.builder(0x400).endPc(0x410)
                .add(aluF(IrOpCode.ADD, 0, 1, 1))
                .add(aluF(IrOpCode.ADD, 2, 3, 2))
                .sealed();
        IrBlock after = pass.optimize(block);

        assertEquals(0x400, after.startPc());
        assertEquals(0x410, after.endPc());
    }
}
