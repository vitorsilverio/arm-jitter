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
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.armjitter.truffle.support.ByteArrayAddressSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A2 — TruffleCodeEmitter vs InterpretedCodeEmitter: bloco ALU puro (nativo), bloco com uma op
/// não suportada (cai no fallback WHOLE_BLOCK e continua equivalente) e bloco vazio. Espelha os
/// casos da suíte de equivalência do `AsmCodeEmitter` (fase 4, ver histórico do git).
class TruffleCodeEmitterEquivalenceTest {
    private final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    private final CodeEmitter referenceEmitter = new InterpretedCodeEmitter();

    /// Exige equivalência entre a referência interpretada e o emissor Truffle candidato —
    /// mesmo papel de `BlockEquivalenceTest` (test-scope do core, não publicado como
    /// test-jar e por isso não importável deste módulo).
    private void assertBlockEquivalent(CodeEmitter candidate, IrBlock block, EquivalencePairFactory pairFactory) {
        harness.assertEquivalent(referenceEmitter, candidate, block, pairFactory);
    }

    private static EquivalencePairFactory pairFactory() {
        return () -> {
            ArmCore reference = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(new ByteArrayAddressSpace(64), SwiDispatcher.empty());
            reference.setRegister(0, 10);
            reference.setRegister(1, 3);
            candidate.setRegister(0, 10);
            candidate.setRegister(1, 3);
            return new EquivalencePair(reference, candidate);
        };
    }

    @Test
    void pureAluBlockCompilesNativelyAndMatchesInterpreter() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
        IrBlock block = new IrBlock(0, 16, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.Alu(IrOpCode.SUB, 3, 2, -1, new IrOperand.Immediate(1), false, Condition.AL),
                new IrOp.Alu(IrOpCode.CMP, 3, 3, -1, new IrOperand.Register(0, -1), true, Condition.AL),
                new IrOp.Alu(IrOpCode.MOV, 4, -1, -1, new IrOperand.Immediate(0xCAFE), false, Condition.AL),
                new IrOp.Alu(IrOpCode.AND, 5, 4, -1, new IrOperand.Immediate(0xFF), false, Condition.AL),
                new IrOp.Cycle(3),
                new IrOp.Fetch(0, 4)));

        assertBlockEquivalent(emitter, block, pairFactory());
        assertEquals(1L, emitter.nativeBlockCount(), "bloco ALU puro deve compilar nativamente");
        assertEquals(0L, emitter.fallbackBlockCount());
    }

    @Test
    void blockWithUnsupportedOpFallsBackToInterpretedAndStaysEquivalent() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
        // ShiftedRegister como src2 não é suportado nesta task -> o bloco INTEIRO cai no
        // fallback, mesmo tendo ops "simples" antes dele.
        IrBlock block = new IrBlock(0, 8, List.of(
                new IrOp.Alu(IrOpCode.ADD, 2, 0, -1, new IrOperand.Register(1, -1), false, Condition.AL),
                new IrOp.Alu(IrOpCode.MOV, 3, -1, -1,
                        new IrOperand.ShiftedRegister(0, ShiftType.LSL, 2, -1, -1, -1, false, false),
                        false, Condition.AL),
                new IrOp.Cycle(2),
                new IrOp.Fetch(0, 4)));

        assertBlockEquivalent(emitter, block, pairFactory());
        assertEquals(0L, emitter.nativeBlockCount());
        assertEquals(1L, emitter.fallbackBlockCount(), "bloco com op nao suportada deve cair no fallback inteiro");
    }

    @Test
    void emptyBlockIsEquivalentAndCompilesNatively() {
        TruffleCodeEmitter emitter = new TruffleCodeEmitter(ArmArchitecture.ARMV4T);
        IrBlock block = new IrBlock(0, 0, List.of());

        assertBlockEquivalent(emitter, block, pairFactory());
        assertEquals(1L, emitter.nativeBlockCount(), "bloco vazio nao tem ops nao suportadas");
    }
}
