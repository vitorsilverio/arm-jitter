package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ir64NativePolicyTest {
    @Test
    void supportsPr1OpSet() {
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.Alu64(Ir64AluOp.ADD, 0, 1, 5, true, false, false, false)));
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.PcRelative(0, 0x1000L, 0x10L, false)));
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.Branch64(Ir64BranchForm.IMMEDIATE, 0x1000L, 0x1010L, -1, false, Ir64Condition.AL)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.CompareBranch64(
                Ir64CompareBranchForm.CBZ_CBNZ, 0, true, -1, false, 0x1010L)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Cycle(1)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Fetch(0x1000L, 4)));
    }

    @Test
    void rejectsOpsOutsidePr1Scope() {
        assertFalse(Ir64NativePolicy.supports(new Ir64Op.Svc(0)));
        assertFalse(Ir64NativePolicy.supports(new Ir64Op.Load64(
                0, 31, Ir64MemSize.DOUBLEWORD, false, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0)));
    }
}
