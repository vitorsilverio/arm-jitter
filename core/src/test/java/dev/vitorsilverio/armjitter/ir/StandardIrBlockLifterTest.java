package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandardIrBlockLifterTest {
    @Test
    void liftsLinearBlockUntilBranch() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        memory.put32(8, 0xEA00_0000);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(memory, 0, 8);

        assertEquals(12, block.endPc());
        assertEquals(9, block.operations().size());
        assertInstanceOf(IrOp.Alu.class, block.operations().get(0));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(1));
        assertFetch(block.operations().get(2), 0, 4);
        assertInstanceOf(IrOp.Alu.class, block.operations().get(3));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(4));
        assertFetch(block.operations().get(5), 4, 4);
        assertInstanceOf(IrOp.Branch.class, block.operations().get(6));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(7));
        assertFetch(block.operations().get(8), 8, 4);
    }

    @Test
    void respectsMaxInstructionLimit() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0_0001);
        memory.put32(4, 0xE280_0002);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(memory, 0, 1);

        assertEquals(4, block.endPc());
        assertEquals(3, block.operations().size());
    }

    @Test
    void stopsBlockAtLoadIntoPc() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE590_F000);
        memory.put32(4, 0xE3A0_0001);
        StandardIrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());

        IrBlock block = lifter.lift(memory, 0, 4);

        assertEquals(4, block.endPc());
        assertEquals(3, block.operations().size());
        assertInstanceOf(IrOp.Load.class, block.operations().get(0));
        assertInstanceOf(IrOp.Cycle.class, block.operations().get(1));
        assertFetch(block.operations().get(2), 0, 4);
    }

    private void assertFetch(IrOp op, int address, int sizeBytes) {
        IrOp.Fetch fetch = assertInstanceOf(IrOp.Fetch.class, op);
        assertEquals(address, fetch.address());
        assertEquals(sizeBytes, fetch.sizeBytes());
    }
}
