package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.RootCallTarget;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/// Emissor Truffle (task A2 mínima + A3 cobertura completa + **A6 especialização de nós**):
/// {@link TruffleBlockRootNode} agora monta, na EMISSÃO, uma árvore `@Children` de
/// {@link IrOpNode} — um nó especializado por CATEGORIA de `IrOp` (ver a taxonomia da
/// especificação da A6: `AluOpNode`/`MultiplyOpNode`/`MemoryOpNode`/`TransferOpNode`/
/// `BranchOpNode`/`SystemOpNode`/`CycleFetchOpNode`), escolhida por {@link IrOpNodeFactory}, em
/// vez do array de DADOS `IrOp[]` percorrido pelo `switch` exaustivo de 40 casos de
/// {@link IrBlockExecutor#executeOp} (A2/A3) — esse dispatcher único era a causa raiz do bailout
/// de partial evaluation documentado em `RELATORIO-A5.md` (0 blocos ARM reais compilados). Cada
/// `IrOpNode` continua delegando ao MESMO executor de categoria que o interpretador e o fallback
/// PER_OP do ASM usam (invariante G1) — a mudança é estrutural (como o Graal enxerga a árvore),
/// nenhuma semântica nova.
///
/// <p>{@link #fallback} e {@link InterpretedCodeEmitter} continuam retidos por compatibilidade de
/// API (G3): {@link IrOpNodeFactory} cobre exaustivamente as mesmas 40 categorias que
/// `executeOp` cobria, então {@link #supports} continua sempre `true` e o fallback continua
/// inatingível na prática — igual à A3.</p>
public final class TruffleCodeEmitter implements CodeEmitter {
    private final IrBlockExecutor executor;
    private final CodeEmitter fallback;
    private final AtomicLong nativeBlockCount = new AtomicLong();
    private final AtomicLong fallbackBlockCount = new AtomicLong();

    /// Emissor ligado a uma arquitetura: o executor interpretado reusado por
    /// {@link IrBlockExecutor#executeOp} consulta os mesmos forks de comportamento que o
    /// interpretador puro e o backend ASM (ex.: interworking de load->PC).
    public TruffleCodeEmitter(ArmArchitecture architecture) {
        Objects.requireNonNull(architecture, "architecture");
        this.executor = new IrBlockExecutor(architecture);
        this.fallback = new InterpretedCodeEmitter(architecture);
    }

    @Override
    public CompiledBlock emit(IrBlock block) {
        if (!supports(block)) {
            fallbackBlockCount.incrementAndGet();
            return fallback.emit(block);
        }
        nativeBlockCount.incrementAndGet();
        IrOp[] ops = block.operationsArray();
        IrOpNode[] nodes = new IrOpNode[ops.length];
        for (int i = 0; i < ops.length; i++) {
            nodes[i] = IrOpNodeFactory.create(ops[i], executor);
        }
        RootCallTarget callTarget = new TruffleBlockRootNode(nodes, block.endPc()).getCallTarget();
        return core -> (int) callTarget.call(core);
    }

    @Override
    public CodegenBackend backend() {
        return CodegenBackend.TRUFFLE;
    }

    /// Quantidade de blocos compilados nativamente pelo nó Truffle.
    public long nativeBlockCount() {
        return nativeBlockCount.get();
    }

    /// Quantidade de blocos delegados por inteiro ao {@link InterpretedCodeEmitter}.
    public long fallbackBlockCount() {
        return fallbackBlockCount.get();
    }

    /// Sempre `true` (task A3, mantido pela A6): {@link IrOpNodeFactory} cobre exaustivamente
    /// todo `IrOp.Kind` — não há mais categoria que force o fallback `WHOLE_BLOCK`.
    private static boolean supports(IrBlock block) {
        return true;
    }
}
