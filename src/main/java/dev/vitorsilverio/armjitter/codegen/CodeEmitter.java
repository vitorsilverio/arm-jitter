package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

/// Emite um bloco executavel a partir de IR otimizada.
public interface CodeEmitter {
    /// Compila o bloco IR recebido para uma unidade executavel pelo runtime.
    CompiledBlock emit(IrBlock block);

    /// Retorna o backend de execução deste emissor.
    CodegenBackend backend();
}
