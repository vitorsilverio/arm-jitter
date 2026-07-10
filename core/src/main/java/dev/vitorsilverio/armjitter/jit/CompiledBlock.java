package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.core.ArmCore;

/// Bloco pronto para execução pelo runtime de blocos.
///
/// Pode ser uma closure que interpreta IR ({@link dev.vitorsilverio.armjitter.codegen.CodegenBackend#INTERPRETED_IR})
/// ou um método JVM gerado via ASM ({@link dev.vitorsilverio.armjitter.codegen.CodegenBackend#JVM_BYTECODE}).
/// Em ambos os casos o retorno de {@link #execute} segue o mesmo contrato: ciclos internos
/// (`IrOp.Cycle`) do bloco; fetch e waitstates de memória vão para {@link ArmCore#cycles()}.
@FunctionalInterface
public interface CompiledBlock {
    /// Executa o bloco contra o core e retorna os ciclos consumidos.
    int execute(ArmCore core);
}
