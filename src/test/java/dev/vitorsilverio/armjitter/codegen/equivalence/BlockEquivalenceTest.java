package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.ir.IrBlock;

/// Base para testes que comparam um emissor candidato com {@link InterpretedCodeEmitter}.
public abstract class BlockEquivalenceTest {
    protected final BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
    protected final CodeEmitter referenceEmitter = new InterpretedCodeEmitter();

    /// Exige equivalência entre referência interpretada e o emissor candidato.
    protected void assertBlockEquivalent(
            CodeEmitter candidate,
            IrBlock block,
            EquivalencePairFactory pairFactory) {
        harness.assertEquivalent(referenceEmitter, candidate, block, pairFactory);
    }
}
