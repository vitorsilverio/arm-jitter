package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceTest;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.StandardIrOptimizer;
import dev.vitorsilverio.armjitter.support.EquivalenceTestSupport;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsmFallbackPolicyTest extends BlockEquivalenceTest {

    private static IrBlock liftBlock(TestAddressSpace memory, int count) {
        return new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder())
                .lift(memory, 0, count);
    }

    /// Bloco com uma op não suportada nativamente por motivo NÃO-condicional (flags LÓGICOS que
    /// dependem do carry-out do barrel shifter) — exercita o fallback WHOLE_BLOCK/PER_OP/FAIL_FAST.
    /// (Condição ≠ AL e ShiftedRegister sem flags agora SÃO emitidos nativamente, então não servem
    /// mais para este teste.)
    /// 0xE3A00064 = MOV r0, #100          (AL — nativo)
    /// 0xE1B01312 = MOVS r1, r2, LSL r3   (flags lógicos + shifter carry — não nativo)
    private static TestAddressSpace buildMixedBlock() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A00064);  // MOV r0, #100        (AL — native)
        memory.put32(4, 0xE1B01312);  // MOVS r1, r2, LSL r3 (logic flags + shifter carry — not native)
        return memory;
    }

    // ── WHOLE_BLOCK ───────────────────────────────────────────────────────────

    @Test
    void wholBlockNativeBlockIncrementedForSupportedBlock() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.WHOLE_BLOCK);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0000A);  // MOV r0, #10 (fully native)
        IrBlock block = liftBlock(memory, 1);

        emitter.emit(block);

        assertEquals(1, emitter.nativeBlockCount());
        assertEquals(0, emitter.fallbackBlockCount());
        assertEquals(0, emitter.perOpFallbackOpCount());
    }

    @Test
    void wholeBlockFallbackBlockIncrementedForUnsupportedBlock() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.WHOLE_BLOCK);
        TestAddressSpace memory = buildMixedBlock();
        IrBlock block = liftBlock(memory, 2);

        assertFalse(emitter.isNativeSupported(block));
        emitter.emit(block);

        assertEquals(0, emitter.nativeBlockCount());
        assertEquals(1, emitter.fallbackBlockCount());
    }

    @Test
    void wholeBlockFallbackGivesSameResultAsInterpreted() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.WHOLE_BLOCK);
        TestAddressSpace memory = buildMixedBlock();
        IrBlock block = liftBlock(memory, 2);

        assertBlockEquivalent(emitter, block, EquivalenceTestSupport.independentPair(memory, c -> { }));
    }

    @Test
    void resetCountersClearsAll() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.WHOLE_BLOCK);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0000A);
        emitter.emit(liftBlock(memory, 1));
        emitter.resetCounters();

        assertEquals(0, emitter.nativeBlockCount());
        assertEquals(0, emitter.fallbackBlockCount());
        assertEquals(0, emitter.perOpFallbackOpCount());
    }

    // ── PER_OP ────────────────────────────────────────────────────────────────

    @Test
    void perOpBlockWithUnsupportedOpMatchesInterpreted() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.PER_OP);
        TestAddressSpace memory = buildMixedBlock();
        IrBlock block = liftBlock(memory, 2);

        // Block is not "wholly native" but PER_OP can still compile it
        assertFalse(emitter.isNativeSupported(block));
        assertBlockEquivalent(emitter, block, EquivalenceTestSupport.independentPair(memory, c -> { }));
    }

    @Test
    void perOpCountsFallbackOps() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.PER_OP);
        TestAddressSpace memory = buildMixedBlock();
        IrBlock block = liftBlock(memory, 2);

        emitter.emit(block);

        // 1 non-native op (MOV r1, r2, LSL r3 — ShiftedRegister) must be counted
        assertTrue(emitter.perOpFallbackOpCount() >= 1,
                "Expected at least 1 per-op fallback; got " + emitter.perOpFallbackOpCount());
        assertEquals(1, emitter.nativeBlockCount());
        assertEquals(0, emitter.fallbackBlockCount());
    }

    @Test
    void perOpFullyNativeBlockHasZeroFallbackOps() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.PER_OP);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0000A);  // MOV r0, #10 — native
        IrBlock block = liftBlock(memory, 1);

        emitter.emit(block);

        assertEquals(0, emitter.perOpFallbackOpCount());
        assertEquals(1, emitter.nativeBlockCount());
    }

    // ── FAIL_FAST ─────────────────────────────────────────────────────────────

    @Test
    void failFastThrowsOnUnsupportedBlock() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.FAIL_FAST);
        TestAddressSpace memory = buildMixedBlock();
        IrBlock block = liftBlock(memory, 2);

        assertThrows(UnsupportedOperationException.class, () -> emitter.emit(block));
    }

    @Test
    void failFastNativeBlockCompilesNormally() {
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.FAIL_FAST);
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0000A);  // MOV r0, #10 (native)
        IrBlock block = liftBlock(memory, 1);

        assertDoesNotThrow(() -> emitter.emit(block));
        assertEquals(1, emitter.nativeBlockCount());
    }

    // ── supportedOps ─────────────────────────────────────────────────────────

    @Test
    void supportedOpsContainsExpectedTypes() {
        var ops = AsmCodeEmitter.supportedOps();

        assertTrue(ops.contains(IrOp.Alu.class));
        assertTrue(ops.contains(IrOp.Multiply.class));
        assertTrue(ops.contains(IrOp.Branch.class));
        assertTrue(ops.contains(IrOp.Load.class));
        assertTrue(ops.contains(IrOp.Cycle.class));
        assertTrue(ops.contains(IrOp.Fetch.class));
        assertFalse(ops.contains(IrOp.Swap.class), "Swap must NOT be in supportedOps");
    }

    @Test
    void supportedOpsHas19Types() {
        assertEquals(19, AsmCodeEmitter.supportedOps().size());
    }

    // ── IrOptimizer wiring ────────────────────────────────────────────────────

    @Test
    void optimizerIsAppliedBeforeEmission() {
        // The optimizer (gba) should constant-fold MOV r0, #10 + ADD r1, r0 (src1Override=10), #5
        // into two MOVs; the result must still match the interpreter.
        AsmCodeEmitter emitter = new AsmCodeEmitter(AsmFallbackPolicy.WHOLE_BLOCK, StandardIrOptimizer.gba());
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(0, 0xE3A0000A);  // MOV r0, #10
        memory.put32(4, 0xE2801005);  // ADD r1, r0, #5  (interpreted r0 may not be 10 at runtime)
        IrBlock block = liftBlock(memory, 2);

        assertBlockEquivalent(emitter, block, EquivalenceTestSupport.independentPair(memory, c -> { }));
    }

    // ── policy() accessor ─────────────────────────────────────────────────────

    @Test
    void policyAccessorReturnsConfiguredPolicy() {
        assertEquals(AsmFallbackPolicy.PER_OP, new AsmCodeEmitter(AsmFallbackPolicy.PER_OP).policy());
        assertEquals(AsmFallbackPolicy.FAIL_FAST, new AsmCodeEmitter(AsmFallbackPolicy.FAIL_FAST).policy());
        assertEquals(AsmFallbackPolicy.WHOLE_BLOCK, new AsmCodeEmitter().policy());
    }
}
