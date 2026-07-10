package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.codegen.jvm.SuperblockContext;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.List;

/// Emite um bloco executavel a partir de IR otimizada.
public interface CodeEmitter {
    /// Compila o bloco IR recebido para uma unidade executavel pelo runtime.
    CompiledBlock emit(IrBlock block);

    /// Retorna o backend de execução deste emissor.
    CodegenBackend backend();

    /// Compõe um LOOP-SUPERBLOCO (task C0.3) a partir de blocos já emitidos por ESTE
    /// emissor: um `CompiledBlock` que executa o ciclo `members` como um loop interno
    /// com os guards do chain loop (via `context`). `memberStartPcs[i]` é o PC inicial
    /// do bloco `members.get(i)`; o índice 0 é o head, sempre executado primeiro.
    ///
    /// Retorna `null` quando o backend não suporta composição (default) ou quando algum
    /// membro não foi produzido por este emissor — o chamador segue sem superbloco.
    default CompiledBlock emitLoopSuperblock(
            List<CompiledBlock> members, int[] memberStartPcs, SuperblockContext context) {
        return null;
    }
}
