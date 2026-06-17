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

        for (IrOp op : block.operations()) {
            switch (op) {
                case IrOp.Alu aluOp -> pcChanged |= alu.execute(core, aluOp);
                case IrOp.Multiply multiply -> alu.executeMultiply(core, multiply);
                case IrOp.LongMultiply longMultiply -> alu.executeLongMultiply(core, longMultiply);
                case IrOp.PsrTransfer psr -> system.executePsrTransfer(core, psr);
                case IrOp.LoadLiteral loadLiteral -> pcChanged |= memory.executeLoadLiteral(core, loadLiteral);
                case IrOp.Load load -> pcChanged |= memory.executeLoad(core, load);
                case IrOp.Store store -> memory.executeStore(core, store);
                case IrOp.Swap swap -> pcChanged |= memory.executeSwap(core, swap);
                case IrOp.MultipleTransfer multipleTransfer -> pcChanged |= transfer.executeMultipleTransfer(core, multipleTransfer);
                case IrOp.Branch branchOp -> pcChanged |= branch.executeBranch(core, branchOp);
                case IrOp.BranchExchange branchExchange -> pcChanged |= branch.executeBranchExchange(core, branchExchange);
                case IrOp.ThumbBlPrefix prefix -> branch.executeThumbBlPrefix(core, prefix);
                case IrOp.ThumbBlSuffix suffix -> pcChanged |= branch.executeThumbBlSuffix(core, suffix);
                case IrOp.Push push -> transfer.executePush(core, push);
                case IrOp.Pop pop -> pcChanged |= transfer.executePop(core, pop);
                case IrOp.Swi swi -> pcChanged |= system.executeSwi(core, swi, block.endPc());
                case IrOp.Coprocessor cp -> pcChanged |= system.executeCoprocessor(core, cp);
                case IrOp.Undefined undefined -> pcChanged |= system.executeUndefined(core, undefined);
                case IrOp.Cycle cycleOp -> cycles += cycle.executeCycle(cycleOp);
                case IrOp.Fetch fetch -> cycle.executeFetch(core, fetch);
            }
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }
}
