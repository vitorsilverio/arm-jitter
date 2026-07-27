package dev.vitorsilverio.armjitter.codegen.equivalence;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;

/// Par de cores A64 idênticos com memórias independentes para comparação de emissores — sibling
/// de {@link EquivalencePair} (32 bits), introduzido na task B6.4.
public record EquivalencePair64(Aarch64Core reference, Aarch64Core candidate) {
}
