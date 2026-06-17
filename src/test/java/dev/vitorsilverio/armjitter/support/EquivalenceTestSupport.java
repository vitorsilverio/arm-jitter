package dev.vitorsilverio.armjitter.support;

import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePair;
import dev.vitorsilverio.armjitter.codegen.equivalence.EquivalencePairFactory;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/// Utilitários para montar pares de CPU idênticos em testes de equivalência.
public final class EquivalenceTestSupport {
    private EquivalenceTestSupport() {
    }

    /// Clona o estado serializado de um core (memória compartilhada).
    public static ArmCore forkState(ArmCore template) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            template.saveState(new DataOutputStream(buffer));
            ArmCore fork = new ArmCore(template.memory(), template.swiDispatcher());
            fork.loadState(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));
            return fork;
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to fork ArmCore state", failure);
        }
    }

    /// Cria pares com memórias independentes e o mesmo conteúdo inicial.
    public static EquivalencePairFactory independentPair(TestAddressSpace memory, Consumer<ArmCore> init) {
        return () -> {
            TestAddressSpace referenceMemory = memory.copy();
            TestAddressSpace candidateMemory = memory.copy();
            ArmCore reference = new ArmCore(referenceMemory, SwiDispatcher.empty());
            ArmCore candidate = new ArmCore(candidateMemory, SwiDispatcher.empty());
            init.accept(reference);
            init.accept(candidate);
            return new EquivalencePair(reference, candidate);
        };
    }
}
