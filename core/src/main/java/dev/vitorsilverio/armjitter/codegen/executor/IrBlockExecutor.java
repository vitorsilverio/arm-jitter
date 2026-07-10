package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Orquestra a execução interpretada de um bloco IR.
///
/// Esta é a única implementação da semântica das instruções: tanto o caminho JIT
/// (blocos compilados) quanto o interpretador frio ({@link dev.vitorsilverio.armjitter.core.ArmInterpreter})
/// a utilizam, de modo que correções de comportamento vivem em um único lugar.
public final class IrBlockExecutor {
    private final IrAluExecutor alu;
    private final IrMemoryExecutor memory;
    private final IrBranchExecutor branch;
    private final IrTransferExecutor transfer;
    private final IrSystemExecutor system;
    private final IrCycleExecutor cycle;

    /// Cria um executor para a arquitetura informada.
    public IrBlockExecutor(ArmArchitecture architecture) {
        IrExecutionSupport support = new IrExecutionSupport(architecture);
        this.alu = new IrAluExecutor(support);
        this.memory = new IrMemoryExecutor(support);
        this.branch = new IrBranchExecutor(support);
        this.transfer = new IrTransferExecutor(support);
        this.system = new IrSystemExecutor(support);
        this.cycle = new IrCycleExecutor();
    }

    /// Interpreta um bloco IR e devolve os ciclos internos (`IrOp.Cycle`) consumidos.
    public int execute(IrBlock block, ArmCore core) {
        int cycles = 0;
        boolean pcChanged = false;

        // Itera o array cacheado por índice (sem alocar Iterator nem checkIndex por op):
        // este loop roda milhões de vezes, é o frame mais quente do interpretador.
        IrOp[] ops = block.operationsArray();
        for (int i = 0, n = ops.length; i < n; i++) {
            // Dispatch O(1) por discriminador inteiro (tableswitch), em vez do `switch` por
            // padrão de tipo, cuja varredura linear de `instanceof` era o 2º frame mais quente.
            // O cast em cada case é garantido por IrOp.kind() (ver IrOp.Kind).
            IrOp op = ops[i];
            switch (op.kind()) {
                case IrOp.Kind.ALU -> pcChanged |= alu.execute(core, (IrOp.Alu) op);
                case IrOp.Kind.MULTIPLY -> alu.executeMultiply(core, (IrOp.Multiply) op);
                case IrOp.Kind.LONG_MULTIPLY -> alu.executeLongMultiply(core, (IrOp.LongMultiply) op);
                case IrOp.Kind.SATURATING -> alu.executeSaturating(core, (IrOp.Saturating) op);
                case IrOp.Kind.DSP_MULTIPLY -> alu.executeDspMultiply(core, (IrOp.DspMultiply) op);
                case IrOp.Kind.PARALLEL_ALU -> alu.executeParallelAlu(core, (IrOp.ParallelAlu) op);
                case IrOp.Kind.SEL -> alu.executeSel(core, (IrOp.Sel) op);
                case IrOp.Kind.SATURATE -> alu.executeSaturate(core, (IrOp.Saturate) op);
                case IrOp.Kind.ABS_DIFF_SUM -> alu.executeAbsDiffSum(core, (IrOp.AbsDiffSum) op);
                case IrOp.Kind.PSR_TRANSFER -> system.executePsrTransfer(core, (IrOp.PsrTransfer) op);
                case IrOp.Kind.LOAD -> pcChanged |= memory.executeLoad(core, (IrOp.Load) op);
                case IrOp.Kind.STORE -> memory.executeStore(core, (IrOp.Store) op);
                case IrOp.Kind.DOUBLE_TRANSFER -> pcChanged |= memory.executeDoubleTransfer(core, (IrOp.DoubleTransfer) op);
                case IrOp.Kind.SWAP -> pcChanged |= memory.executeSwap(core, (IrOp.Swap) op);
                case IrOp.Kind.LOAD_LITERAL -> pcChanged |= memory.executeLoadLiteral(core, (IrOp.LoadLiteral) op);
                case IrOp.Kind.MULTIPLE_TRANSFER -> pcChanged |= transfer.executeMultipleTransfer(core, (IrOp.MultipleTransfer) op);
                case IrOp.Kind.BRANCH -> pcChanged |= branch.executeBranch(core, (IrOp.Branch) op);
                case IrOp.Kind.BRANCH_EXCHANGE -> pcChanged |= branch.executeBranchExchange(core, (IrOp.BranchExchange) op);
                case IrOp.Kind.THUMB_BL_PREFIX -> branch.executeThumbBlPrefix(core, (IrOp.ThumbBlPrefix) op);
                case IrOp.Kind.THUMB_BL_SUFFIX -> pcChanged |= branch.executeThumbBlSuffix(core, (IrOp.ThumbBlSuffix) op);
                case IrOp.Kind.PUSH -> transfer.executePush(core, (IrOp.Push) op);
                case IrOp.Kind.POP -> pcChanged |= transfer.executePop(core, (IrOp.Pop) op);
                case IrOp.Kind.SWI -> pcChanged |= system.executeSwi(core, (IrOp.Swi) op, block.endPc());
                case IrOp.Kind.COPROCESSOR -> pcChanged |= system.executeCoprocessor(core, (IrOp.Coprocessor) op);
                case IrOp.Kind.UNDEFINED -> pcChanged |= system.executeUndefined(core, (IrOp.Undefined) op);
                case IrOp.Kind.CYCLE -> cycles += cycle.executeCycle((IrOp.Cycle) op);
                case IrOp.Kind.FETCH -> cycle.executeFetch(core, (IrOp.Fetch) op);
                default -> throw new IllegalStateException("IrOp kind desconhecido: " + op.kind());
            }
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }

    /// Executa uma única {@link IrOp} sem o ajuste final de PC, e devolve se o PC foi alterado.
    ///
    /// Usado pela infraestrutura {@link dev.vitorsilverio.armjitter.codegen.AsmFallbackPolicy#PER_OP}
    /// para executar ops não suportadas nativamente inline no bytecode JVM gerado.
    ///
    /// @param blockEndPc PC sequencial do fim do bloco (necessário para {@link IrOp.Swi})
    public boolean executeOp(ArmCore core, IrOp op, int blockEndPc) {
        return switch (op) {
            case IrOp.Alu aluOp -> alu.execute(core, aluOp);
            case IrOp.Multiply multiply -> { alu.executeMultiply(core, multiply); yield false; }
            case IrOp.LongMultiply lm -> { alu.executeLongMultiply(core, lm); yield false; }
            case IrOp.Saturating sat -> { alu.executeSaturating(core, sat); yield false; }
            case IrOp.DspMultiply dsp -> { alu.executeDspMultiply(core, dsp); yield false; }
            case IrOp.ParallelAlu parallel -> { alu.executeParallelAlu(core, parallel); yield false; }
            case IrOp.Sel sel -> { alu.executeSel(core, sel); yield false; }
            case IrOp.Saturate saturate -> { alu.executeSaturate(core, saturate); yield false; }
            case IrOp.AbsDiffSum usad -> { alu.executeAbsDiffSum(core, usad); yield false; }
            case IrOp.PsrTransfer psr -> { system.executePsrTransfer(core, psr); yield false; }
            case IrOp.LoadLiteral ll -> memory.executeLoadLiteral(core, ll);
            case IrOp.Load load -> memory.executeLoad(core, load);
            case IrOp.Store store -> { memory.executeStore(core, store); yield false; }
            case IrOp.DoubleTransfer dt -> memory.executeDoubleTransfer(core, dt);
            case IrOp.Swap swap -> memory.executeSwap(core, swap);
            case IrOp.MultipleTransfer mt -> transfer.executeMultipleTransfer(core, mt);
            case IrOp.Branch b -> branch.executeBranch(core, b);
            case IrOp.BranchExchange bx -> branch.executeBranchExchange(core, bx);
            case IrOp.ThumbBlPrefix prefix -> { branch.executeThumbBlPrefix(core, prefix); yield false; }
            case IrOp.ThumbBlSuffix suffix -> branch.executeThumbBlSuffix(core, suffix);
            case IrOp.Push push -> { transfer.executePush(core, push); yield false; }
            case IrOp.Pop pop -> transfer.executePop(core, pop);
            case IrOp.Swi swi -> system.executeSwi(core, swi, blockEndPc);
            case IrOp.Coprocessor cp -> system.executeCoprocessor(core, cp);
            case IrOp.Undefined undef -> system.executeUndefined(core, undef);
            case IrOp.Cycle cycleOp -> { cycle.executeCycle(cycleOp); yield false; }
            case IrOp.Fetch fetch -> { cycle.executeFetch(core, fetch); yield false; }
        };
    }
}
