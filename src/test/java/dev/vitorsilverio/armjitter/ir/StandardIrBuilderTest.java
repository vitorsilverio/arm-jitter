package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandardIrBuilderTest {
    @Test
    void liftsArmAddImmediateToAluOperationAndCycle() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE280_0005);
        DecodedInstruction instruction = new ArmDecoder().decode(memory, 0);
        IrBlock.Builder block = IrBlock.builder(0);

        new StandardIrBuilder().lift(instruction, block);
        IrBlock ir = block.sealed();

        assertEquals(4, ir.endPc());
        assertInstanceOf(IrOp.Alu.class, ir.operations().getFirst());
        IrOp.Alu alu = (IrOp.Alu) ir.operations().getFirst();
        assertEquals("ADD", alu.opcode());
        assertEquals(0, alu.dst());
        assertEquals(0, alu.src1());
        assertEquals(new IrOperand.Immediate(5), alu.src2());
        assertEquals(Condition.AL, alu.condition());
        assertInstanceOf(IrOp.Cycle.class, ir.operations().get(1));
        IrOp.Fetch fetch = assertInstanceOf(IrOp.Fetch.class, ir.operations().get(2));
        assertEquals(0, fetch.address());
        assertEquals(4, fetch.sizeBytes());
    }

    @Test
    void liftsBranchPreservingTargetConditionAndLink() {
        DecodedInstruction instruction = new DecodedInstruction(
                0x100,
                0x0B00_0001,
                InstructionSet.ARM,
                Condition.EQ,
                InstructionKind.BRANCH,
                -1,
                -1,
                -1,
                0x10C,
                true,
                false,
                true);
        IrBlock.Builder block = IrBlock.builder(0x100);

        new StandardIrBuilder().lift(instruction, block);
        IrOp.Branch branch = (IrOp.Branch) block.sealed().operations().getFirst();

        assertEquals(0x10C, branch.target());
        assertTrue(branch.link());
        assertEquals(Condition.EQ, branch.condition());
        assertEquals(InstructionSet.ARM, branch.targetSet());
    }

    @Test
    void liftsSwiPreservingImmediateAndCondition() {
        DecodedInstruction instruction = new DecodedInstruction(
                0,
                0xEF00_0008,
                InstructionSet.ARM,
                Condition.AL,
                InstructionKind.SWI,
                -1,
                -1,
                -1,
                0x08,
                true,
                false,
                false);
        IrBlock.Builder block = IrBlock.builder(0);

        new StandardIrBuilder().lift(instruction, block);
        IrOp.Swi swi = (IrOp.Swi) block.sealed().operations().getFirst();

        assertEquals(0x08, swi.immediate());
        assertEquals(Condition.AL, swi.condition());
    }

    @Test
    void liftsLoadAndStorePreservingMemoryShape() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(0, 0xE590_1004);
        memory.put32(4, 0xE580_1008);
        ArmDecoder decoder = new ArmDecoder();
        StandardIrBuilder builder = new StandardIrBuilder();

        IrBlock.Builder loadBlock = IrBlock.builder(0);
        builder.lift(decoder.decode(memory, 0), loadBlock);
        IrOp.Load load = (IrOp.Load) loadBlock.sealed().operations().getFirst();

        IrBlock.Builder storeBlock = IrBlock.builder(4);
        builder.lift(decoder.decode(memory, 4), storeBlock);
        IrOp.Store store = (IrOp.Store) storeBlock.sealed().operations().getFirst();

        assertEquals(1, load.dst());
        assertEquals(0, load.base());
        assertEquals(-1, load.baseValueOverride());
        assertEquals(new IrOperand.Immediate(4), load.offset());
        assertEquals(4, load.sizeBytes());

        assertEquals(1, store.src());
        assertEquals(0, store.base());
        assertEquals(-1, store.baseValueOverride());
        assertEquals(new IrOperand.Immediate(8), store.offset());
        assertEquals(4, store.sizeBytes());
    }

    @Test
    void liftsArmPcRelativeLoadWithPipelineBaseOverride() {
        TestAddressSpace memory = new TestAddressSpace(16);
        memory.put32(4, 0xE59F_1000);
        DecodedInstruction instruction = new ArmDecoder().decode(memory, 4);
        IrBlock.Builder block = IrBlock.builder(4);

        new StandardIrBuilder().lift(instruction, block);
        IrOp.Load load = (IrOp.Load) block.sealed().operations().getFirst();

        assertEquals(15, load.base());
        assertEquals(12, load.baseValueOverride());
    }

    @Test
    void liftsBranchExchange() {
        DecodedInstruction instruction = new DecodedInstruction(
                0,
                0xE12F_FF10,
                InstructionSet.ARM,
                Condition.AL,
                InstructionKind.BRANCH_EXCHANGE,
                -1,
                0,
                -1,
                0,
                false,
                false,
                false);
        IrBlock.Builder block = IrBlock.builder(0);

        new StandardIrBuilder().lift(instruction, block);
        IrOp.BranchExchange bx = (IrOp.BranchExchange) block.sealed().operations().getFirst();

        assertEquals(0, bx.sourceRegister());
        assertEquals(Condition.AL, bx.condition());
    }
}
