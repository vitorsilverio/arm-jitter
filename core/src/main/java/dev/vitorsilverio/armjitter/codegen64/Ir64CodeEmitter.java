package dev.vitorsilverio.armjitter.codegen64;

import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;

/// Converte um {@link Ir64Block} num {@link CompiledBlock64} executável — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.CodeEmitter} (32 bits), introduzido na task B6.4.
@FunctionalInterface
public interface Ir64CodeEmitter {
    /// Emite um bloco executável a partir do IR.
    CompiledBlock64 emit(Ir64Block block);
}
