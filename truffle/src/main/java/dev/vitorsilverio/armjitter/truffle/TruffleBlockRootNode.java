package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.ConditionProfile;
import dev.vitorsilverio.armjitter.core.ArmCore;

/// `RootNode` Truffle que executa um `IrBlock` fixo (task A6): a árvore é um array
/// `@Children` de {@link IrOpNode} — um nó ESPECIALIZADO por categoria de `IrOp` (ver a taxonomia
/// da especificação), montado uma única vez na emissão por {@link IrOpNodeFactory}
/// ({@link TruffleCodeEmitter#emit}) — em vez do array de DADOS `IrOp[]` que a A2/A3 percorriam
/// com um `switch` exaustivo de 40 casos (`IrBlockExecutor#executeOp`). Este nó não reimplementa
/// NADA de ALU/flags/memória/branch: cada `IrOpNode` delega ao mesmo executor que o interpretador
/// usa (invariante G1) — só a FORMA da árvore mudou, para que o Graal consiga "ver através" dela
/// em partial evaluation (causa raiz do bailout documentado em `RELATORIO-A5.md`).
///
/// `@ExplodeLoop` continua no nível do bloco (item 2 da especificação, decisão CONFIRMADA depois
/// da mudança para nós especializados — não redundante: é o padrão canônico Truffle de "loop
/// explodido sobre filhos homogêneos em quantidade, heterogêneos em tipo", e é exatamente o que
/// o PE sabe podar quando cada filho já é um call-site monomórfico separado).
///
/// A contabilidade de `pcChanged`/ciclos por op (A3) é preservada: o fixup final de PC só roda
/// quando NENHUM filho alterou o PC — o mesmo contrato de {@code IrBlockExecutor#execute}. O
/// resultado (`!pcChanged`) passa por um {@link ConditionProfile} (item 4b da especificação): a
/// maioria dos blocos executa até o fim sem branch/exceção, então este profile ajuda o Graal a
/// especular o caminho comum sem mudar o resultado.
final class TruffleBlockRootNode extends RootNode {
    @Children
    private final IrOpNode[] ops;
    private final int endPc;
    private final ConditionProfile pcUnchangedProfile = ConditionProfile.create();

    TruffleBlockRootNode(IrOpNode[] ops, int endPc) {
        super(null);
        this.ops = insert(ops);
        this.endPc = endPc;
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
        for (IrOpNode op : ops) {
            pcChanged |= op.executeOp(core, endPc);
            cycles += op.cycleDelta();
        }
        if (pcUnchangedProfile.profile(!pcChanged)) {
            core.setProgramCounter(endPc);
        }
        return cycles;
    }

    @Override
    public String getName() {
        return "arm-jitter-truffle-block";
    }
}
