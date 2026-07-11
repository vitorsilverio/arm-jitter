package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.RootCallTarget;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.CodeEmitter;
import dev.vitorsilverio.armjitter.codegen.CodegenBackend;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/// Emissor Truffle mínimo (task A2): compila nativamente blocos com apenas ALU simples
/// (MOV/ADD/SUB/AND/CMP, condição AL, operandos registrador/imediato, destino != PC),
/// `Cycle` e `Fetch`; qualquer outra op no bloco delega o bloco INTEIRO para
/// {@link InterpretedCodeEmitter} (fallback `WHOLE_BLOCK`, como o `AsmCodeEmitter` fazia antes
/// do PER_OP). Cobertura completa de `IrOp` é a A3; emissão nativa de ShiftedRegister/condições
/// != AL/memória/branches fica para tasks futuras da trilha A.
public final class TruffleCodeEmitter implements CodeEmitter {
    /// Opcodes ALU cobertos nativamente por esta task.
    private static final Set<IrOpCode> SUPPORTED_ALU_OPCODES =
            EnumSet.of(IrOpCode.MOV, IrOpCode.ADD, IrOpCode.SUB, IrOpCode.AND, IrOpCode.CMP);

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

    private static boolean supports(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    private static boolean supports(IrOp op) {
        return switch (op) {
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
            case IrOp.Alu alu -> alu.condition() == Condition.AL
                    && alu.dst() != 15
                    && SUPPORTED_ALU_OPCODES.contains(alu.opcode())
                    && !(alu.src2() instanceof IrOperand.ShiftedRegister);
            default -> false;
        };
    }
}
