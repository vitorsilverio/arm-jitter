package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrBranchExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria de branch (task A6): `Branch`, `BranchExchange`, `ThumbBlPrefix`,
/// `ThumbBlSuffix`, `TableBranch`, `CompareBranchZero`. Delega DIRETO a {@link IrBranchExecutor}.
///
/// <p>Só `TableBranch` (`TBB`/`TBH`) toca memória (lê a tabela) — é o ÚNICO caso desta categoria
/// atrás de {@link TruffleBoundary} (item 3 da especificação); os demais só leem/escrevem
/// registradores e o PC, algo que o Graal PRECISA ver através para especializar o bloco, e por
/// isso ficam abertos ao partial evaluation sem fronteira nenhuma.</p>
final class BranchOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrBranchExecutor executor;

    BranchOpNode(IrOp op, IrBranchExecutor executor) {
        super(op.condition());
        this.op = op;
        this.executor = executor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        return switch (op) {
            case IrOp.Branch branch -> executor.executeBranch(core, branch);
            case IrOp.BranchExchange branchExchange -> executor.executeBranchExchange(core, branchExchange);
            case IrOp.ThumbBlPrefix prefix -> {
                executor.executeThumbBlPrefix(core, prefix);
                yield false;
            }
            case IrOp.ThumbBlSuffix suffix -> executor.executeThumbBlSuffix(core, suffix);
            case IrOp.TableBranch tableBranch -> executeTableBranchAtBoundary(tableBranch, core);
            case IrOp.CompareBranchZero compareBranchZero -> executor.executeCompareBranchZero(core, compareBranchZero);
            default -> throw new IllegalStateException("BranchOpNode não cobre: " + op);
        };
    }

    @TruffleBoundary
    private boolean executeTableBranchAtBoundary(IrOp.TableBranch tableBranch, ArmCore core) {
        return executor.executeTableBranch(core, tableBranch);
    }
}
