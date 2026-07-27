package dev.vitorsilverio.armjitter.codegen64;

import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Ir64Block;
import dev.vitorsilverio.armjitter.jit64.CompiledBlock64;

/// Emissor oráculo A64 — espelho estrutural de
/// {@link dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter} (32 bits), introduzido na
/// task B6.4. `emit` fecha uma closure sobre um {@link Ir64BlockExecutor} compartilhado (stateless
/// além de um decoder interno não usado por {@link Ir64BlockExecutor#executeBlock}) que despacha
/// {@link Ir64BlockExecutor#executeBlock} — nunca reimplementa a semântica das operações (G1).
public final class InterpretedIr64CodeEmitter implements Ir64CodeEmitter {
    private final Ir64BlockExecutor executor = new Ir64BlockExecutor();

    @Override
    public CompiledBlock64 emit(Ir64Block block) {
        return core -> executor.executeBlock(core, block);
    }
}
