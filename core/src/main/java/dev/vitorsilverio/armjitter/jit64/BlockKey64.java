package dev.vitorsilverio.armjitter.jit64;

/// Chave de cache para um bloco A64 compilado — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.jit.BlockKey} (32 bits), introduzido na task B6.4, mas
/// deliberadamente MAIS SIMPLES (ver `b6.4-aarch64-asm-backend.md`, decisão D5): sem
/// `instructionSet` (A64 não tem Thumb), sem `itState` (não existe IT block em A64), sem
/// `translationGeneration` (A64 ainda não tem MMU — isso é a task futura B6.6).
///
/// @param pc program counter inicial do bloco (endereço de 64 bits)
public record BlockKey64(long pc) {
}
