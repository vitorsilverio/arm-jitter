package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle base para uma única `IrOp` (task A6). Cada subclasse cobre UMA categoria da
/// taxonomia da especificação (`AluOpNode`, `MultiplyOpNode`, `MemoryOpNode`, `TransferOpNode`,
/// `BranchOpNode`, `SystemOpNode`, `CycleFetchOpNode`) e delega o cálculo real ao método de
/// categoria correspondente em `codegen/executor` — a MESMA fonte de verdade usada pelo
/// interpretador e pelo fallback PER_OP do ASM (invariante G1). NENHUMA subclasse reimplementa
/// regra de flags/UNPREDICTABLE/`ArmFeature`.
///
/// <p>Substitui, na árvore montada por {@link TruffleCodeEmitter}, o `switch` exaustivo de 40
/// casos de {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor#executeOp} (a
/// causa raiz do bailout de partial evaluation documentado em `RELATORIO-A5.md`) por um `switch`
/// pequeno (no máximo 11 casos) POR CATEGORIA, isolado numa classe de nó própria: cada `execute`
/// de nó vira um call-site monomórfico separado, que o Graal consegue especializar/podar em vez
/// de precisar expandir um dispatcher gigante para QUALQUER bloco.</p>
///
/// <p>Guarda a condição da op para o profiling do item 4 da especificação: quando
/// {@link IrOp#condition() condition()} não é {@link Condition#AL} (a maioria dos blocos usa a
/// MESMA condição sempre — tipicamente AL), um {@link ConditionProfile} observa o resultado de
/// `evalCond` só para ajudar o Graal a especular o branch mais provável; o executor delegado
/// reavalia a MESMA condição de qualquer forma (G1 — este nó nunca decide sozinho se a op deve
/// rodar, só observa o resultado de uma chamada que já aconteceria).</p>
abstract sealed class IrOpNode extends Node
        permits AluOpNode, MultiplyOpNode, MemoryOpNode, TransferOpNode, BranchOpNode, SystemOpNode, CycleFetchOpNode {
    private final Condition condition;
    private final ConditionProfile conditionTakenProfile = ConditionProfile.create();

    IrOpNode(Condition condition) {
        this.condition = condition;
    }

    /// Executa a operação e devolve se o PC foi alterado.
    ///
    /// @param blockEndPc PC sequencial do fim do bloco (só usado por `IrOp.Swi`)
    final boolean executeOp(ArmCore core, int blockEndPc) {
        if (condition != Condition.AL) {
            conditionTakenProfile.profile(core.cpsr().evalCond(condition));
        }
        return doExecute(core, blockEndPc);
    }

    /// Ciclos internos (`IrOp.Cycle`) contribuídos por esta op; `0` para toda categoria exceto
    /// `CycleFetchOpNode` na sub-forma `Cycle`.
    int cycleDelta() {
        return 0;
    }

    abstract boolean doExecute(ArmCore core, int blockEndPc);
}
