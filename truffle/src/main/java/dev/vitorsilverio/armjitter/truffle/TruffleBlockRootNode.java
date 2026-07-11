package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// `RootNode` Truffle que executa um `IrBlock` fixo (array de ops `@CompilationFinal`, loop
/// `@ExplodeLoop`) delegando a semântica de cada op para {@link IrBlockExecutor#executeOp} — a
/// MESMA fonte de verdade usada pelo interpretador e pelo fallback PER_OP do backend ASM
/// (invariante G1). Este nó não reimplementa NADA de ALU/flags/memória/branch: só monta a árvore
/// que o Graal especializa por partial evaluation, análogo ao que `AsmBlockCompiler` faz emitindo
/// bytecode — mas delegando (em vez de reimplementar) a semântica de CADA categoria de `IrOp`.
///
/// `IrOp.Cycle` é tratado à parte (soma direta de `count()`) porque `executeOp` descarta esse
/// valor — o mesmo contrato do loop principal em `IrBlockExecutor#execute`, cujo `pcChanged`
/// (task A3) este nó também reproduz: o fixup final de PC só roda quando NENHUMA op do bloco
/// alterou o PC (branch, load→PC, SWI, etc.) — exatamente como o interpretador.
final class TruffleBlockRootNode extends RootNode {
    @CompilationFinal(dimensions = 1)
    private final IrOp[] ops;
    private final int endPc;
    private final IrBlockExecutor executor;

    TruffleBlockRootNode(IrOp[] ops, int endPc, IrBlockExecutor executor) {
        super(null);
        this.ops = ops;
        this.endPc = endPc;
        this.executor = executor;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        ArmCore core = (ArmCore) frame.getArguments()[0];
        return executeBlock(core);
    }

    @ExplodeLoop
    private int executeBlock(ArmCore core) {
        int cycles = 0;
        boolean pcChanged = false;
        for (IrOp op : ops) {
            if (op.kind() == IrOp.Kind.CYCLE) {
                cycles += ((IrOp.Cycle) op).count();
            } else {
                pcChanged |= executor.executeOp(core, op, endPc);
            }
        }
        if (!pcChanged) {
            core.setProgramCounter(endPc);
        }
        return cycles;
    }

    @Override
    public String getName() {
        return "arm-jitter-truffle-block";
    }
}
