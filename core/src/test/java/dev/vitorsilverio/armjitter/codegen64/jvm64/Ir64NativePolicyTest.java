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
    void supportsPr2OpSet() {
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Svc(0)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Load64(
                0, 31, Ir64MemSize.DOUBLEWORD, false, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Store64(
                0, 31, Ir64MemSize.WORD, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.LoadStorePair(
                true, 0, 1, 31, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, false)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.LoadLiteral64(0, 0x1000L, true, false)));
    }

    @Test
    void supportsPr3OpSet() {
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.AluShiftedRegister(
                Ir64AluOp.ADD, 0, 1, 2, dev.vitorsilverio.armjitter.ir64.Ir64ShiftType.LSL, 0, true, false)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.AluExtendedRegister(
                Ir64AluOp.ADD, 0, 31, 2, dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType.UXTX, 0,
                true, false, false)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.ConditionalSelect(
                dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp.CSEL, 0, 1, 2, true, Ir64Condition.EQ)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Bitfield(
                dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp.UBFM, 0, 1, 0, 7, true)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.MultiplyAccumulate(false, 0, 1, 2, 3, true)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Divide(true, 0, 1, 2, true)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.LoadExclusive(0, 31, Ir64MemSize.WORD, false)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.StoreExclusive(0, 1, 31, Ir64MemSize.WORD, false)));
    }

    @Test
    void supportsB654FpOpSet() {
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.Fp64Alu(Ir64Op.Fp64Operation.ADD, true, 0, 1, 2)));
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.Fp64MoveImmediate(false, 0, 0x3F800000L)));
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.Fp64Compare(true, false, false, 0, 1)));
        assertTrue(Ir64NativePolicy.supports(
                new Ir64Op.Fp64Convert(Ir64Op.Fp64Conversion.F32_TO_F64, 0, 1)));
    }
}
