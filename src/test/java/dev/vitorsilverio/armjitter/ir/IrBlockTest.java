package dev.vitorsilverio.armjitter.ir;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class IrBlockTest {
    @Test
    void sealsOperationsAsImmutableSnapshot() {
        ArrayList<IrOp> ops = new ArrayList<>();
        ops.add(new IrOp.Cycle(1));

        IrBlock block = new IrBlock(0x100, 0x104, ops);
        ops.add(new IrOp.Cycle(2));

        assertEquals(1, block.operations().size());
        assertThrows(UnsupportedOperationException.class, () -> block.operations().add(new IrOp.Cycle(3)));
    }
}
