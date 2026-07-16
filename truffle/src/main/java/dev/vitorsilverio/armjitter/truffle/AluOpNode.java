package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.vitorsilverio.armjitter.codegen.executor.IrAluExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria ALU escalar (task A6, taxonomia da especificação): `Alu`,
/// `MoveTop`, `Sel`, `Saturate`, `AbsDiffSum`, `Saturating`. Delega cada caso DIRETO ao método de
/// {@link IrAluExecutor} correspondente — nenhuma regra de flags/saturação reimplementada (G1).
final class AluOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrAluExecutor executor;

    AluOpNode(IrOp op, IrAluExecutor executor) {
        super(op.condition());
        this.op = op;
        this.executor = executor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        return switch (op) {
            case IrOp.Alu alu -> executor.execute(core, alu);
            case IrOp.MoveTop moveTop -> {
                executor.executeMoveTop(core, moveTop);
                yield false;
            }
            case IrOp.Sel sel -> {
                executor.executeSel(core, sel);
                yield false;
            }
            case IrOp.Saturate saturate -> {
                executor.executeSaturate(core, saturate);
                yield false;
            }
            case IrOp.AbsDiffSum absDiffSum -> {
                executor.executeAbsDiffSum(core, absDiffSum);
                yield false;
            }
            case IrOp.Saturating saturating -> {
                executor.executeSaturating(core, saturating);
                yield false;
            }
            default -> throw new IllegalStateException("AluOpNode não cobre: " + op);
        };
    }
}
