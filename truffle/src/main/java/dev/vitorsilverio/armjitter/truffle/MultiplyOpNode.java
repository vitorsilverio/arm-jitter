package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.vitorsilverio.armjitter.codegen.executor.IrAluExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria de multiplicação (task A6): `Multiply`, `LongMultiply`,
/// `DspMultiply`, `ParallelAlu`, e desde a A10.6 `DspDualMultiply`/`DspTopWordMultiply`. Todas
/// vivem em {@link IrAluExecutor} (mesma classe de `AluOpNode`), mas formam um nó Truffle
/// SEPARADO — a taxonomia da especificação as separa por perfil de custo/uso (multiplicação é
/// rara em blocos ALU simples), não pela classe do executor. Nenhuma dessas ops muda o PC.
final class MultiplyOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrAluExecutor executor;

    MultiplyOpNode(IrOp op, IrAluExecutor executor) {
        super(op.condition());
        this.op = op;
        this.executor = executor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        switch (op) {
            case IntegerOp.Multiply multiply -> executor.executeMultiply(core, multiply);
            case IntegerOp.LongMultiply longMultiply -> executor.executeLongMultiply(core, longMultiply);
            case IntegerOp.DspMultiply dspMultiply -> executor.executeDspMultiply(core, dspMultiply);
            case IntegerOp.ParallelAlu parallelAlu -> executor.executeParallelAlu(core, parallelAlu);
            case IntegerOp.DspDualMultiply dspDualMultiply -> executor.executeDspDualMultiply(core, dspDualMultiply);
            case IntegerOp.DspTopWordMultiply dspTopWordMultiply -> executor.executeDspTopWordMultiply(core, dspTopWordMultiply);
            default -> throw new IllegalStateException("MultiplyOpNode não cobre: " + op);
        }
        return false;
    }
}
