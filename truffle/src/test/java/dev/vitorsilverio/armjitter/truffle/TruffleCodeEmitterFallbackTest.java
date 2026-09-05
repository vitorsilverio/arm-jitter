package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePairFactory;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A10.1 — o backend Truffle NUNCA lança por `Kind` não coberto: um bloco com uma op sem nó
/// especializado (aqui `BKPT`, sem nó Truffle mesmo após a A10.3 cobrir VFP) é delegado por
/// inteiro ao {@link InterpretedCodeEmitter} e produz estado de CPU idêntico ao interpretador
/// (G1). Antes desta task, {@code emit} desse bloco quebrava com {@link IllegalStateException}.
class TruffleCodeEmitterFallbackTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    private final CodeEmitter referenceEmitter = new InterpretedCodeEmitter(ArmArchitecture.ARMV7A);

    private static EquivalencePairFactory pairFactory() {
        return () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(256), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(256), SwiDispatcher.empty());
            return new EquivalencePair(reference, candidate);
        };
    }

    @Test
    void blockWithUncoveredOpFallsBackAndMatchesInterpreter() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV7A);
        // BKPT #0 — Kind BREAKPOINT, sem nó Truffle.
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.Breakpoint(0),
                new IrOp.Cycle(1),
                new IrOp.Fetch(0, 4)));

        harness.assertEquivalent(referenceEmitter, emitter, block, pairFactory());

        assertEquals(0L, emitter.nativeBlockCount(), "bloco com BKPT não pode compilar nativamente");
        assertEquals(1L, emitter.fallbackBlockCount(), "bloco com BKPT deve cair no fallback interpretado");
    }

    @Test
    void mixedBlockWithOneUncoveredOpFallsBackWholeBlock() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV7A);
        // ADD r2, r0, r1  (coberto)  +  BKPT #0  (NÃO coberto) -> bloco inteiro no fallback.
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.Breakpoint(0),
                new IrOp.Cycle(1),
                new IrOp.Fetch(0, 4)));

        harness.assertEquivalent(referenceEmitter, emitter, block, pairFactory());

        assertEquals(0L, emitter.nativeBlockCount());
        assertEquals(1L, emitter.fallbackBlockCount());
    }

    @Test
    void pureIntegerBlockStillCompilesNatively() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV7A);
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.Cycle(1),
                new IrOp.Fetch(0, 4)));

        harness.assertEquivalent(referenceEmitter, emitter, block, pairFactory());

        assertEquals(1L, emitter.nativeBlockCount(), "bloco puramente inteiro deve compilar nativamente");
        assertEquals(0L, emitter.fallbackBlockCount());
    }
}
