package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuSnapshotTest {
    @Test
    void snapshotsDivergeWhenOnlyOneVfpSingleRegisterDiffers() {
        ArmCore a = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        ArmCore b = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        b.vfp().setS(9, 0x1);

        CpuSnapshot snapshotA = CpuSnapshot.capture(a);
        CpuSnapshot snapshotB = CpuSnapshot.capture(b);

        assertThrows(EquivalenceMismatchException.class,
                () -> snapshotA.assertEqualTo(snapshotB, "vfp single register"));
    }

    @Test
    void snapshotsDivergeWhenFpscrDiffers() {
        ArmCore a = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        ArmCore b = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        b.fpscr().setValue(dev.vitorsilverio.armjitter.core.FpscrRegister.NEGATIVE_FLAG);

        CpuSnapshot snapshotA = CpuSnapshot.capture(a);
        CpuSnapshot snapshotB = CpuSnapshot.capture(b);

        assertThrows(EquivalenceMismatchException.class,
                () -> snapshotA.assertEqualTo(snapshotB, "fpscr"));
    }

    @Test
    void identicalCoresProduceEqualSnapshots() {
        ArmCore a = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        ArmCore b = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());

        CpuSnapshot.capture(a).assertEqualTo(CpuSnapshot.capture(b), "identical cores");
    }
}
