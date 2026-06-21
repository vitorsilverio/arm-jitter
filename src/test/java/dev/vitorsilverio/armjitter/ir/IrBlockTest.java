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

    @Test
    void cachedArrayMirrorsOperationsSnapshotInOrder() {
        ArrayList<IrOp> ops = new ArrayList<>();
        IrOp.Cycle first = new IrOp.Cycle(1);
        IrOp.Cycle second = new IrOp.Cycle(2);
        ops.add(first);
        ops.add(second);

        IrBlock block = new IrBlock(0x200, 0x208, ops);
        ops.add(new IrOp.Cycle(3)); // mutação posterior não afeta o snapshot

        IrOp[] array = block.operationsArray();
        assertEquals(2, array.length);
        assertSame(first, array[0]);
        assertSame(second, array[1]);
        // mesma sequência que a lista pública
        assertEquals(block.operations(), java.util.List.of(array));
    }
}
