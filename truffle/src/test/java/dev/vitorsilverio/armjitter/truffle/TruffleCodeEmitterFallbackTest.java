package dev.vitorsilverio.armjitter.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
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
/// especializado (aqui `NEON_THREE_SAME`, ainda sem nó Truffle — ver A10.7) é delegado por
/// inteiro ao {@link InterpretedCodeEmitter} e produz estado de CPU idêntico ao interpretador
/// (G1). Antes da A10.1, {@code emit} desse bloco quebrava com {@link IllegalStateException}.
/// **A10.5**: usava `BKPT` como exemplo de `Kind` descoberto — trocado para `NEON_THREE_SAME`
/// porque a A10.5 deu nó Truffle a `Breakpoint`.
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
        // VADD.I32 d0, d0, d1 — Kind NEON_THREE_SAME, sem nó Truffle.
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 0, 0, 1, 2),
                new IrOp.Cycle(1),
                new IrOp.Fetch(0, 4)));

        harness.assertEquivalent(referenceEmitter, emitter, block, pairFactory());

        assertEquals(0L, emitter.nativeBlockCount(), "bloco com NEON_THREE_SAME não pode compilar nativamente");
        assertEquals(1L, emitter.fallbackBlockCount(), "bloco com NEON_THREE_SAME deve cair no fallback interpretado");
    }

    @Test
    void mixedBlockWithOneUncoveredOpFallsBackWholeBlock() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV7A);
        // ADD r2, r0, r1  (coberto)  +  VADD.I32 d0, d0, d1  (NÃO coberto) -> bloco inteiro no fallback.
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.NeonThreeSame(AdvSimdThreeSameOp.ADD, false, 0, 0, 1, 2),
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
