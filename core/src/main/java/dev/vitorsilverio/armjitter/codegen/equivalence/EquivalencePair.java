package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core.ArmCore;

/// Par de cores idênticos com memórias independentes para comparação de emissores.
public record EquivalencePair(ArmCore reference, ArmCore candidate) {
}
