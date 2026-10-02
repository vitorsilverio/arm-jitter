package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.vitorsilverio.armjitter.codegen.executor.IrAluExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria ALU escalar (task A6, taxonomia da especificação): `Alu`,
/// `MoveTop`, `Sel`, `Saturate`, `AbsDiffSum`, `Saturating`, e desde a A10.4 `BitFieldExtract`,
/// `BitFieldInsert`, `BitReverse`, `Divide`. Delega cada caso DIRETO ao método de
/// {@link IrAluExecutor} correspondente — nenhuma regra de flags/saturação/divisão reimplementada
/// (G1); em particular `Divide` NÃO reimplementa a semântica ARM de divisão por zero (resultado
/// `0`) nem de `INT_MIN / -1` (satura em `INT_MIN`, não lança), que já vivem no executor.
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
            case IntegerOp.Alu alu -> executor.execute(core, alu);
            case IntegerOp.MoveTop moveTop -> {
                executor.executeMoveTop(core, moveTop);
                yield false;
            }
            case IntegerOp.Sel sel -> {
                executor.executeSel(core, sel);
                yield false;
            }
            case IntegerOp.Saturate saturate -> {
                executor.executeSaturate(core, saturate);
                yield false;
            }
            case IntegerOp.AbsDiffSum absDiffSum -> {
                executor.executeAbsDiffSum(core, absDiffSum);
                yield false;
            }
            case IntegerOp.Saturating saturating -> {
                executor.executeSaturating(core, saturating);
                yield false;
            }
            case IntegerOp.BitFieldExtract bitFieldExtract -> {
                executor.executeBitFieldExtract(core, bitFieldExtract);
                yield false;
            }
            case IntegerOp.BitFieldInsert bitFieldInsert -> {
                executor.executeBitFieldInsert(core, bitFieldInsert);
                yield false;
            }
            case IntegerOp.BitReverse bitReverse -> {
                executor.executeBitReverse(core, bitReverse);
                yield false;
            }
            case IntegerOp.Divide divide -> {
                executor.executeDivide(core, divide);
                yield false;
            }
            default -> throw new IllegalStateException("AluOpNode não cobre: " + op);
        };
    }
}
