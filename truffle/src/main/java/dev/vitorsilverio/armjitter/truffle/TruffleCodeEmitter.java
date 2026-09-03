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
/// <p>{@link IrOpNodeFactory} cobre um SUBCONJUNTO de `IrOp.Kind` — as 40 `Kind` das 7 categorias
/// da taxonomia da A6. Os 33 `Kind` acrescentados por B1/B3/B7/B9/B13 DEPOIS da A6 (todo VFP,
/// todo NEON, DSP dual/top-word, `HVC`/`SMC`/`ERET`/`MRS_bank`/`MSR_bank`, bitfield/`RBIT`/
/// `SDIV`/`UDIV`, `BKPT`, sysreg do perfil M) ainda não têm nó Truffle — cada categoria é fechada
/// por uma task própria (A10.3 VFP · A10.4 bitfield/divide · A10.5 sistema · A10.6 DSP · A10.7
/// NEON). Enquanto isso, {@link #supports} devolve `false` para qualquer bloco que contenha um
/// desses `Kind`, e {@link #emit} o delega por inteiro ao {@link #fallback}
/// ({@link InterpretedCodeEmitter}), contabilizando em {@link #fallbackBlockCount()} — o caminho
/// real, não mais "inatingível na prática" como era na A3/A6. Antes desta correção (A10.1),
/// `supports` era `return true` incondicional e um bloco com `Kind` não coberto **quebrava** com
/// `IllegalStateException` em {@link IrOpNodeFactory#create} em vez de degradar.</p>
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

    /// `true` só quando TODA op do bloco tem um nó especializado em {@link IrOpNodeFactory}
    /// (consulta {@link IrOpNodeFactory#supports} por op — espelha
    /// {@code AsmNativePolicy#supports(IrBlock)}). Um único `Kind` não coberto (VFP, NEON, DSP
    /// dual/top-word, sistema de B9, bitfield/divide, `BKPT`, sysreg M) manda o bloco inteiro
    /// para o {@link #fallback} — ver o Javadoc da classe e as tasks A10.3-A10.7.
    private static boolean supports(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (!IrOpNodeFactory.supports(op)) {
                return false;
            }
        }
        return true;
    }
}
