package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.RootCallTarget;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/// Emissor Truffle (task A2 mínima + A3 cobertura completa): {@link TruffleBlockRootNode} delega
/// CADA `IrOp` do bloco a {@link IrBlockExecutor#executeOp} — a mesma fonte de verdade usada pelo
/// interpretador e pelo fallback PER_OP do backend ASM (invariante G1). Como `executeOp` já cobre
/// exaustivamente todo `IrOp` (inclui condição != AL, ShiftedRegister, branches, memória,
/// LDM/STM, PSR/SWI/coprocessador e as ops ARMv6/v6K/ARMv5TE), TODO bloco compila nativamente —
/// {@link #fallback} e {@link InterpretedCodeEmitter} ficam retidos por compatibilidade de API
/// (G3) mas o caminho de fallback é inatingível na prática.
///
/// <p>Isto NÃO especializa nós Truffle por categoria de op (isso seria emissão bytecode-like,
/// como o `AsmBlockCompiler` faz) — é otimização de partial evaluation, fora do escopo de
/// correção da A3; ver a task A4 (factory + bench) para medir o ganho real de JIT.</p>
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
        RootCallTarget callTarget =
                new TruffleBlockRootNode(block.operationsArray(), block.endPc(), executor).getCallTarget();
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

    /// Sempre `true` (task A3): {@link IrBlockExecutor#executeOp} cobre exaustivamente todo
    /// `IrOp.Kind` — não há mais categoria que force o fallback `WHOLE_BLOCK`.
    private static boolean supports(IrBlock block) {
        return true;
    }
}
