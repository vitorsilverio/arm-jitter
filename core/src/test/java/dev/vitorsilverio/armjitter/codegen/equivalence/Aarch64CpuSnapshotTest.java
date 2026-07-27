package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Aarch64CpuSnapshotTest {
    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(16)));
    }

    @Test
    void snapshotsDivergeWhenOnlyOneVRegisterDiffers() {
        Aarch64Core a = newCore();
        Aarch64Core b = newCore();
        b.fp().setD(9, 0x1);

        Aarch64CpuSnapshot snapshotA = Aarch64CpuSnapshot.capture(a);
        Aarch64CpuSnapshot snapshotB = Aarch64CpuSnapshot.capture(b);

        assertThrows(EquivalenceMismatchException.class,
                () -> snapshotA.assertEqualTo(snapshotB, "v register"));
    }

    @Test
    void identicalCoresProduceEqualSnapshots() {
        Aarch64Core a = newCore();
        Aarch64Core b = newCore();

        Aarch64CpuSnapshot.capture(a).assertEqualTo(Aarch64CpuSnapshot.capture(b), "identical cores");
    }
}
