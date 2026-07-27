package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen64.Ir64CodeEmitter;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;

import java.util.Objects;

/// Compara dois {@link Ir64CodeEmitter}s no mesmo bloco IR e estado inicial de CPU — sibling de
/// {@link BlockEquivalenceHarness} (32 bits), introduzido na task B6.4 (aceite do PR1: prova que
/// {@code InterpretedIr64CodeEmitter} e {@code Asm64CodeEmitter} concordam).
public final class BlockEquivalenceHarness64 {
    /// Resultado da execução de um bloco por um emissor.
    public record ExecutionResult(int internalCycles, Aarch64CpuSnapshot snapshot) {
    }

    /// Executa o bloco com o emissor informado sobre o core fornecido.
    public ExecutionResult run(Ir64CodeEmitter emitter, Ir64Block block, Aarch64Core core) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(core, "core");
        int internalCycles = emitter.emit(block).execute(core);
        return new ExecutionResult(internalCycles, Aarch64CpuSnapshot.capture(core));
    }

    /// Exige que candidato e referência produzam o mesmo estado e ciclos internos.
    public void assertEquivalent(
            Ir64CodeEmitter reference,
            Ir64CodeEmitter candidate,
            Ir64Block block,
            EquivalencePairFactory64 pairFactory) {
        EquivalencePair64 pair = pairFactory.create();
        ExecutionResult referenceResult = run(reference, block, pair.reference());
        ExecutionResult candidateResult = run(candidate, block, pair.candidate());

        referenceResult.snapshot().assertEqualTo(candidateResult.snapshot(), "CPU state");
        if (referenceResult.internalCycles() != candidateResult.internalCycles()) {
            throw EquivalenceMismatchException.of(
                    "internal cycles",
                    "Ir64Op.Cycle total",
                    Integer.toString(referenceResult.internalCycles()),
                    Integer.toString(candidateResult.internalCycles()));
        }
    }
}
