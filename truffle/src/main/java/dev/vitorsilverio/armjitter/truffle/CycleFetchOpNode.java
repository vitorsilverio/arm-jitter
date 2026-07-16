package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrCycleExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria de ciclo/fetch (task A6): `Cycle`, `Fetch`. Delega DIRETO a
/// {@link IrCycleExecutor}. G4: nunca recebem guard condicional — `IrOp#condition()` sempre
/// devolve `AL` para os dois (ver o default de `IrOp`), então `IrOpNode#executeOp` nunca chega a
/// consultar o profile de condição para este nó.
///
/// <p>`Cycle` é puro (só soma `count()`, sem tocar o hospedeiro) e fica aberto ao partial
/// evaluation. `Fetch` chama `ArmCore#addMemoryCycles`, que delega a `memory.accessCycles` — uma
/// implementação do hospedeiro (o barramento de memória, ex. wait-states de GBA/NDS), opaca ao
/// Graal — por isso só o caso `Fetch` fica atrás de {@link TruffleBoundary} (item 3 da
/// especificação).</p>
final class CycleFetchOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrCycleExecutor executor;

    CycleFetchOpNode(IrOp op, IrCycleExecutor executor) {
        super(op.condition());
        this.op = op;
        this.executor = executor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        if (op instanceof IrOp.Fetch fetch) {
            executeFetchAtBoundary(fetch, core);
        }
        // IrOp.Cycle não tem efeito de execução: sua contribuição inteira é o cycleDelta() abaixo,
        // exatamente como o `IrBlockExecutor#execute` original tratava Kind.CYCLE à parte.
        return false;
    }

    @Override
    int cycleDelta() {
        return op instanceof IrOp.Cycle cycle ? executor.executeCycle(cycle) : 0;
    }

    @TruffleBoundary
    private void executeFetchAtBoundary(IrOp.Fetch fetch, ArmCore core) {
        executor.executeFetch(core, fetch);
    }
}
