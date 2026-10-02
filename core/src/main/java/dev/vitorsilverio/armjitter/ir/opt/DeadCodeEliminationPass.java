package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;

import java.util.ArrayList;
import java.util.List;

/// Remove operações {@link IntegerOp.Alu} cujos resultados nunca são lidos.
///
/// Usa análise de vivência de registradores para trás (backward liveness), com os conjuntos
/// {@link IrOp#regUse()}/{@link IrOp#regDef()} que cada operação declara (task E15.6).
/// Somente {@code IntegerOp.Alu} com {@code setFlags=false} e {@code dst≠15} são candidatas;
/// todos os outros ops são preservados por terem efeitos colaterais (memória, CPSR, PC, exceções).
///
/// <p>Fórmula: {@code live_in(i) = use(i) | (live_out(i) & ~def(i))}
/// <br>Saída do bloco: todos os registradores são considerados vivos (conservador).
public final class DeadCodeEliminationPass implements IrOptimizer {
    /// Todos os registradores `r0..r15`.
    private static final int ALL_REGISTERS = 0xFFFF;
    /// Índice do `PC` (`R15`): escrita nele é desvio, nunca código morto.
    private static final int PC = 15;

    @Override
    public IrBlock optimize(IrBlock block) {
        List<IrOp> ops = block.operations();
        int n = ops.size();

        // live[i] = bitmask de registradores vivos ANTES de ops[i]; live[n] = saída do bloco
        int[] live = new int[n + 1];
        live[n] = ALL_REGISTERS;  // conservador: todos vivos ao sair do bloco

        for (int i = n - 1; i >= 0; i--) {
            // live_in(i) = use(i) | (live_out(i) & ~def(i))
            IrOp op = ops.get(i);
            live[i] = op.regUse() | (live[i + 1] & ~mustDef(op));
        }

        List<IrOp> result = new ArrayList<>(n);
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (isDead(ops.get(i), live[i + 1])) {
                changed = true;
            } else {
                result.add(ops.get(i));
            }
        }
        return changed ? new IrBlock(block.startPc(), block.endPc(), result) : block;
    }

    // ── auxiliares de vivência (liveness) ──────────────────────────────────────

    private static boolean isDead(IrOp op, int liveOut) {
        return op instanceof IntegerOp.Alu alu
                && !alu.setFlags()
                && alu.dst() != PC
                && (liveOut & (1 << alu.dst())) == 0;
    }

    /// Uma op predicada (executada condicionalmente) NÃO é um must-def: ela pode não rodar, então
    /// não pode matar a vivência de uma escrita anterior no mesmo registrador (ex.: o par clássico
    /// `ADDEQ r,..` / `ADDNE r,..` if-then-else). Trata seu conjunto def como vazio para que a
    /// vivência backward permaneça conservadora e a DCE não apague a escrita complementar.
    private static int mustDef(IrOp op) {
        return op.condition() == Condition.AL ? op.regDef() : 0;
    }
}
