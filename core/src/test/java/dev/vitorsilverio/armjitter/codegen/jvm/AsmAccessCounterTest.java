package dev.vitorsilverio.armjitter.codegen.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.support.IrOpSamples;
import org.junit.jupiter.api.Test;

import java.util.Map;

/// E15.7 — os contadores de acesso ao register cache (antes um `switch` em
/// `AsmBlockCompiler#countAccesses`, agora `countXxx` na família de cada emissor). Uma asserção por
/// caminho; o vetor esperado é escrito à mão, por registrador `r0..r14`.
class AsmAccessCounterTest {
    private static final int NO_OVERRIDE = -1;
    private static final int OVERRIDE = 0x100;

    @Test
    void rangeGuardsIgnoreThePcAndNegativeRegisters() {
        AsmAccessCounter counter = new AsmAccessCounter();
        counter.read(-1);
        counter.read(15);
        counter.write(-1);
        counter.write(15);
        assertArrayEquals(new int[15], counter.accesses);
        assertFalse(new AsmRegCache().cached(-1));
        assertFalse(new AsmRegCache().cached(15));
    }

    @Test
    void operandReadsOnlyRegistersWithoutOverride() {
        AsmAccessCounter counter = new AsmAccessCounter();
        counter.operand(new IrOperand.Register(1, OVERRIDE));
        counter.operand(new IrOperand.Register(2, NO_OVERRIDE));
        counter.operand(new IrOperand.ShiftedRegister(3, ShiftType.LSL, 0, 4, NO_OVERRIDE, NO_OVERRIDE, false, false));
        counter.operand(new IrOperand.ShiftedRegister(5, ShiftType.LSL, 0, 6, OVERRIDE, OVERRIDE, false, false));
        counter.operand(new IrOperand.ShiftedRegister(7, ShiftType.LSL, 1, NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, false, false));
        counter.operand(new IrOperand.Immediate(8));
        assertArrayEquals(accesses(2, 1, 3, 1, 4, 1, 7, 1), counter.accesses);
    }

    @Test
    void load() {
        assertCounts(sample(MemoryOp.Load.class, Map.of("dst", 3, "base", 1, "baseValueOverride", NO_OVERRIDE,
                "offset", new IrOperand.Register(2, NO_OVERRIDE), "writeback", true)), accesses(1, 2, 2, 1, 3, 1), 1, 3);
        // base == dst: o writeback não conta de novo.
        assertCounts(sample(MemoryOp.Load.class, Map.of("dst", 4, "base", 4, "baseValueOverride", NO_OVERRIDE,
                "offset", new IrOperand.Immediate(0), "writeback", true)), accesses(4, 2), 4);
        // base com override (PC) e sem writeback: só o destino.
        assertCounts(sample(MemoryOp.Load.class, Map.of("dst", 5, "base", 6, "baseValueOverride", OVERRIDE,
                "offset", new IrOperand.Immediate(0))), accesses(5, 1), 5);
    }

    @Test
    void multipleTransfer() {
        assertCounts(sample(MemoryOp.MultipleTransfer.class, Map.of("load", true, "base", 0, "registerMask", 0b110,
                "writeback", true)), accesses(0, 2, 1, 1, 2, 1), 0, 1, 2);
        assertCounts(sample(MemoryOp.MultipleTransfer.class, Map.of("base", 0, "registerMask", 0b110)),
                accesses(0, 1, 1, 1, 2, 1));
        // Forma rara (user-mode) vai pelo helper: não conta.
        assertCounts(sample(MemoryOp.MultipleTransfer.class, Map.of("base", 0, "registerMask", 0b110,
                "userMode", true)), accesses());
    }

    @Test
    void pushAndPop() {
        assertCounts(sample(MemoryOp.Push.class, Map.of("registerMask", 0b1, "includeLr", true)),
                accesses(0, 1, 13, 2, 14, 1), 13);
        assertCounts(sample(MemoryOp.Push.class, Map.of("registerMask", 0b10)), accesses(1, 1, 13, 2), 13);
        assertCounts(sample(MemoryOp.Pop.class, Map.of("registerMask", 0b10)), accesses(1, 1, 13, 2), 1, 13);
    }

    @Test
    void doubleTransfer() {
        assertCounts(sample(MemoryOp.DoubleTransfer.class, Map.of("first", 4, "second", 5, "base", 2,
                "baseValueOverride", NO_OVERRIDE, "offset", new IrOperand.Immediate(0), "writeback", true)),
                accesses(2, 2, 4, 1, 5, 1), 2);
        assertCounts(sample(MemoryOp.DoubleTransfer.class, Map.of("load", true, "first", 4, "second", 5, "base", 2,
                "baseValueOverride", OVERRIDE, "offset", new IrOperand.Immediate(0))), accesses(4, 1, 5, 1), 4, 5);
    }

    @Test
    void multiply() {
        assertCounts(sample(IntegerOp.Multiply.class, Map.of("dst", 0, "rm", 1, "rmValueOverride", NO_OVERRIDE,
                "rs", 2, "rsValueOverride", NO_OVERRIDE, "rn", 3, "rnValueOverride", NO_OVERRIDE, "accumulate", true)),
                accesses(0, 1, 1, 1, 2, 1, 3, 1), 0);
        assertCounts(sample(IntegerOp.Multiply.class, Map.of("dst", 0, "rm", 1, "rmValueOverride", OVERRIDE,
                "rs", 2, "rsValueOverride", OVERRIDE, "rn", 3, "rnValueOverride", NO_OVERRIDE)), accesses(0, 1), 0);
        assertCounts(sample(IntegerOp.Multiply.class, Map.of("dst", 0, "rm", 1, "rmValueOverride", OVERRIDE,
                "rs", 2, "rsValueOverride", OVERRIDE, "rn", 3, "rnValueOverride", OVERRIDE, "accumulate", true)),
                accesses(0, 1), 0);
    }

    @Test
    void longMultiply() {
        assertCounts(sample(IntegerOp.LongMultiply.class, Map.of("dstLow", 0, "dstHigh", 1, "rm", 2,
                "rmValueOverride", NO_OVERRIDE, "rs", 3, "rsValueOverride", NO_OVERRIDE, "dstLowValueOverride",
                NO_OVERRIDE, "dstHighValueOverride", NO_OVERRIDE, "accumulateDouble", true)),
                accesses(0, 2, 1, 2, 2, 1, 3, 1), 0, 1);
        assertCounts(sample(IntegerOp.LongMultiply.class, Map.of("dstLow", 0, "dstHigh", 1, "rm", 2,
                "rmValueOverride", OVERRIDE, "rs", 3, "rsValueOverride", OVERRIDE, "dstLowValueOverride",
                OVERRIDE, "dstHighValueOverride", OVERRIDE, "accumulate", true)), accesses(0, 1, 1, 1), 0, 1);
    }

    @Test
    void dspMultiply() {
        // SMLAxy (op2=0) lê Rn; SMLAWy (op2=1, x=0) também; SMULWy (op2=1, x=1) e SMULxy (op2=3) não.
        assertCounts(dsp(0, 0), accesses(0, 1, 1, 1, 2, 1, 3, 1), 0);
        assertCounts(dsp(1, 0), accesses(0, 1, 1, 1, 2, 1, 3, 1), 0);
        assertCounts(dsp(1, 1), accesses(0, 1, 2, 1, 3, 1), 0);
        assertCounts(dsp(3, 0), accesses(0, 1, 2, 1, 3, 1), 0);
        // SMLALxy (op2=2): lê e escreve o par RdLo(rn)/RdHi(dst).
        assertCounts(dsp(2, 0), accesses(0, 2, 1, 2, 2, 1, 3, 1), 0, 1);
    }

    @Test
    void bitFieldClearReadsOnlyTheDestination() {
        assertCounts(sample(IntegerOp.BitFieldInsert.class, Map.of("dst", 2, "src", -1)), accesses(2, 2), 2);
        assertCounts(sample(IntegerOp.BitFieldInsert.class, Map.of("dst", 2, "src", 3)), accesses(2, 2, 3, 1), 2);
    }

    private static IrOp dsp(int op2, int x) {
        return sample(IntegerOp.DspMultiply.class, Map.of("dst", 0, "rn", 1, "rm", 2, "rs", 3, "op2", op2, "x", x));
    }

    private static IrOp sample(Class<? extends IrOp> recordClass, Map<String, Object> fields) {
        return IrOpSamples.sample(recordClass, fields);
    }

    /// Pares (registrador, acessos) → vetor `r0..r14`.
    private static int[] accesses(int... registerCountPairs) {
        int[] expected = new int[15];
        for (int i = 0; i < registerCountPairs.length; i += 2) {
            expected[registerCountPairs[i]] = registerCountPairs[i + 1];
        }
        return expected;
    }

    private static void assertCounts(IrOp op, int[] expectedAccesses, int... writtenRegisters) {
        AsmAccessCounter counter = new AsmAccessCounter();
        AsmEmitterRegistry.lookup(op).countAccesses(op, counter);
        assertArrayEquals(expectedAccesses, counter.accesses, op::toString);
        boolean[] expectedWrites = new boolean[15];
        for (int reg : writtenRegisters) {
            expectedWrites[reg] = true;
        }
        assertArrayEquals(expectedWrites, counter.writes, op::toString);
    }
}
