package dev.vitorsilverio.armjitter.codegen64.jvm64;

import dev.vitorsilverio.armjitter.ir64.AdvSimdMoveOp64;
import dev.vitorsilverio.armjitter.ir64.BranchOp64;
import dev.vitorsilverio.armjitter.ir64.FpOp64;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64MoveWideOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ir64NativePolicyTest {
    @Test
    void supportsPr1OpSet() {
        assertTrue(Ir64NativePolicy.supports(
                new IntegerOp64.Alu64(Ir64AluOp.ADD, 0, 1, 5, true, false, false, false)));
        assertTrue(Ir64NativePolicy.supports(
                new IntegerOp64.MoveWide(Ir64MoveWideOp.MOVZ, 0, 1, 0, true)));
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.PcRelative(0, 0x1000L, 0x10L, false)));
        assertTrue(Ir64NativePolicy.supports(
                new BranchOp64.Branch64(Ir64BranchForm.IMMEDIATE, 0x1000L, 0x1010L, -1, false, Ir64Condition.AL)));
        assertTrue(Ir64NativePolicy.supports(new BranchOp64.CompareBranch64(
                Ir64CompareBranchForm.CBZ_CBNZ, 0, true, -1, false, 0x1010L)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Cycle(1)));
        assertTrue(Ir64NativePolicy.supports(new Ir64Op.Fetch(0x1000L, 4)));
    }

    @Test
    void supportsPr2OpSet() {
        assertTrue(Ir64NativePolicy.supports(new SystemOp64.Svc(0)));
        assertTrue(Ir64NativePolicy.supports(new MemoryOp64.Load64(
                0, 31, Ir64MemSize.DOUBLEWORD, false, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0)));
        assertTrue(Ir64NativePolicy.supports(new MemoryOp64.Store64(
                0, 31, Ir64MemSize.WORD, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, -1, null, 0)));
        assertTrue(Ir64NativePolicy.supports(new MemoryOp64.LoadStorePair(
                true, 0, 1, 31, true,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0, false)));
        assertTrue(Ir64NativePolicy.supports(new MemoryOp64.LoadLiteral64(0, 0x1000L, true, false)));
    }

    @Test
    void supportsPr3OpSet() {
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.AluShiftedRegister(
                Ir64AluOp.ADD, 0, 1, 2, dev.vitorsilverio.armjitter.ir64.Ir64ShiftType.LSL, 0, true, false)));
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.AluExtendedRegister(
                Ir64AluOp.ADD, 0, 31, 2, dev.vitorsilverio.armjitter.ir64.Ir64AluExtendType.UXTX, 0,
                true, false, false)));
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.ConditionalSelect(
                dev.vitorsilverio.armjitter.ir64.Ir64ConditionalSelectOp.CSEL, 0, 1, 2, true, Ir64Condition.EQ)));
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.Bitfield(
                dev.vitorsilverio.armjitter.ir64.Ir64BitfieldOp.UBFM, 0, 1, 0, 7, true)));
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.MultiplyAccumulate(false, 0, 1, 2, 3, true)));
        assertTrue(Ir64NativePolicy.supports(new IntegerOp64.Divide(true, 0, 1, 2, true)));
        assertTrue(Ir64NativePolicy.supports(new MemoryOp64.LoadExclusive(0, 31, Ir64MemSize.WORD, false)));
        assertTrue(Ir64NativePolicy.supports(new MemoryOp64.StoreExclusive(0, 1, 31, Ir64MemSize.WORD, false)));
    }

    @Test
    void supportsB654FpOpSet() {
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.Alu(FpOp64.Fp64Operation.ADD, true, 0, 1, 2)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.MoveImmediate(false, 0, 0x3F800000L)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.Compare(true, false, false, 0, 1)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.Convert(FpOp64.Fp64Conversion.F32_TO_F64, 0, 1)));
    }

    @Test
    void supportsC124FpOpSet() {
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.MultiplyAdd(true, false, false, 0, 1, 2, 3)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.ConditionalSelect(true, 0, 1, 2, Ir64Condition.EQ)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.ConditionalCompare(true, false, 0, 1, Ir64Condition.EQ, 0)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.Round(FpOp64.Fp64RoundingDirection.NEAREST_TIES_EVEN, true, 0, 1)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.IntegerConvert(
                        false, true, FpOp64.Fp64RoundingDirection.TOWARD_ZERO, true, true, 0, 0, 1)));
        assertTrue(Ir64NativePolicy.supports(
                new FpOp64.GeneralRegisterMove(false, true, 0, 1)));
    }

    @Test
    void supportsC125LoadStoreFpSimdOpSet() {
        assertTrue(Ir64NativePolicy.supports(new FpOp64.Load64(
                0, 1, dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.DOUBLE,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0L, -1, null, 0)));
        assertTrue(Ir64NativePolicy.supports(new FpOp64.Store64(
                0, 1, dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.DOUBLE,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0L, -1, null, 0)));
        assertTrue(Ir64NativePolicy.supports(new FpOp64.LoadStorePair(
                true, 0, 1, 2, dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.DOUBLE,
                dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode.OFFSET, 0L)));
        assertTrue(Ir64NativePolicy.supports(new FpOp64.LoadLiteral64(
                0, 0x1000L, dev.vitorsilverio.armjitter.ir64.Ir64FpMemSize.QUAD)));
        assertTrue(Ir64NativePolicy.supports(new AdvSimdMoveOp64.LoadStoreMultiple(
                true, 0, 1, -1, true, false, 3, 1, 1)));
        assertTrue(Ir64NativePolicy.supports(new AdvSimdMoveOp64.LoadStoreSingle(
                true, 0, 1, -1, false, 3, 1, 0)));
        assertTrue(Ir64NativePolicy.supports(new AdvSimdMoveOp64.LoadSingleReplicate(
                0, 1, -1, true, false, 3, 1)));
    }
}
