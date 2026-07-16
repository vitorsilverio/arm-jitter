package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrTransferExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria LDM/STM/PUSH/POP (task A6): `MultipleTransfer`, `Push`, `Pop`.
/// Delega DIRETO a {@link IrTransferExecutor}. Toda op desta categoria acessa memória (uma
/// sequência de words) — mesma justificativa de {@link MemoryOpNode} para o `@TruffleBoundary`
/// cobrir o dispatch inteiro em vez de só o `read`/`write` individual (limite de módulo G3/A1).
final class TransferOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrTransferExecutor executor;

    TransferOpNode(IrOp op, IrTransferExecutor executor) {
        super(op.condition());
        this.op = op;
        this.executor = executor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        return executeAtBoundary(core);
    }

    @TruffleBoundary
    private boolean executeAtBoundary(ArmCore core) {
        return switch (op) {
            case IrOp.MultipleTransfer multipleTransfer -> executor.executeMultipleTransfer(core, multipleTransfer);
            case IrOp.Push push -> {
                executor.executePush(core, push);
                yield false;
            }
            case IrOp.Pop pop -> executor.executePop(core, pop);
            default -> throw new IllegalStateException("TransferOpNode não cobre: " + op);
        };
    }
}
