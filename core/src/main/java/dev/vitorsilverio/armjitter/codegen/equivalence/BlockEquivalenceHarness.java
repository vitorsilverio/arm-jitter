package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;

import java.util.Objects;

/// Compara dois {@link CodeEmitter}s no mesmo bloco IR e estado inicial de CPU.
public final class BlockEquivalenceHarness {
    /// Resultado da execução de um bloco por um emissor.
    public record ExecutionResult(int internalCycles, CpuSnapshot snapshot) {
    }

    /// Executa o bloco com o emissor informado sobre o core fornecido.
    public ExecutionResult run(CodeEmitter emitter, IrBlock block, ArmCore core) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(core, "core");
        int internalCycles = emitter.emit(block).execute(core);
        return new ExecutionResult(internalCycles, CpuSnapshot.capture(core));
    }

    /// Exige que candidato e referência produzam o mesmo estado e ciclos internos.
    public void assertEquivalent(
            CodeEmitter reference,
            CodeEmitter candidate,
            IrBlock block,
            EquivalencePairFactory pairFactory) {
        EquivalencePair pair = pairFactory.create();
        ExecutionResult referenceResult = run(reference, block, pair.reference());
        ExecutionResult candidateResult = run(candidate, block, pair.candidate());

        referenceResult.snapshot().assertEqualTo(candidateResult.snapshot(), "CPU state");
        if (referenceResult.internalCycles() != candidateResult.internalCycles()) {
            throw EquivalenceMismatchException.of(
                    "internal cycles",
                    "IrOp.Cycle total",
                    Integer.toString(referenceResult.internalCycles()),
                    Integer.toString(candidateResult.internalCycles()));
        }
    }
}
