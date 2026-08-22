package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;

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
    private final IrVfpExecutor vfp;

    /// Cria um executor para a arquitetura informada.
    public IrBlockExecutor(ArmArchitecture architecture) {
        IrExecutionSupport support = new IrExecutionSupport(architecture);
        this.alu = new IrAluExecutor(support);
        this.memory = new IrMemoryExecutor(support);
        this.branch = new IrBranchExecutor(support);
        this.transfer = new IrTransferExecutor(support);
        this.system = new IrSystemExecutor(support);
        this.cycle = new IrCycleExecutor();
        this.vfp = new IrVfpExecutor(support);
    }

    /// Interpreta um bloco IR e devolve os ciclos internos (`IrOp.Cycle`) consumidos.
    ///
    /// B4.1.3 (RFC-SOFTMMU §3): uma {@link MemoryTranslationException} lançada por um `AddressSpace`
    /// traduzido (`TranslatingAddressSpace`) no meio do laço é capturada aqui — o `try` cerca o laço
    /// inteiro sem custo no caminho quente (uma região `try` sem lançamento não paga nada na JVM; só
    /// o `throw` em si tem custo, e faltas de tradução já são raras por natureza). No `catch`, o
    /// endereço da instrução faltosa é o do PRÓXIMO `IrOp.Fetch` a partir do índice corrente — cada
    /// instrução termina SEMPRE com `Cycle`+`Fetch` (G4), então o Fetch que ainda não rodou é
    /// exatamente o desta instrução (ver {@link #ownerInstructionAddress}).
    public int execute(IrBlock block, ArmCore core) {
        int cycles = 0;
        boolean pcChanged = false;

        // Itera o array cacheado por índice (sem alocar Iterator nem checkIndex por op):
        // este loop roda milhões de vezes, é o frame mais quente do interpretador.
        IrOp[] ops = block.operationsArray();
        // Discriminador pré-resolvido na construção do bloco (task C8, candidato #1): evita a
        // chamada virtual megamórfica de `op.kind()` por op a cada execução — o bloco é imutável
        // pós-lift, então o dispatch já é conhecido e só precisa ser indexado aqui.
        int[] kinds = block.kindsArray();
        int n = ops.length;
        int i = 0;
        try {
            for (; i < n; i++) {
                // Dispatch O(1) por discriminador inteiro (tableswitch), em vez do `switch` por
                // padrão de tipo, cuja varredura linear de `instanceof` era o 2º frame mais quente.
                // O cast em cada case é garantido por IrOp.kind() (ver IrOp.Kind).
                IrOp op = ops[i];
                switch (kinds[i]) {
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
                case IrOp.Kind.LOAD_EXCLUSIVE -> memory.executeLoadExclusive(core, (IrOp.LoadExclusive) op);
                case IrOp.Kind.STORE_EXCLUSIVE -> memory.executeStoreExclusive(core, (IrOp.StoreExclusive) op);
                case IrOp.Kind.CLEAR_EXCLUSIVE -> memory.executeClearExclusive(core, (IrOp.ClearExclusive) op);
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
                case IrOp.Kind.COPROCESSOR_DOUBLE -> pcChanged |= system.executeCoprocessorDouble(core, (IrOp.CoprocessorDouble) op);
                case IrOp.Kind.UNDEFINED -> pcChanged |= system.executeUndefined(core, (IrOp.Undefined) op);
                case IrOp.Kind.CYCLE -> cycles += cycle.executeCycle((IrOp.Cycle) op);
                case IrOp.Kind.FETCH -> cycle.executeFetch(core, (IrOp.Fetch) op);
                case IrOp.Kind.CHANGE_PROCESSOR_STATE -> system.executeChangeProcessorState(core, (IrOp.ChangeProcessorState) op);
                case IrOp.Kind.SET_ENDIANNESS -> system.executeSetEndianness(core, (IrOp.SetEndianness) op);
                case IrOp.Kind.STORE_RETURN_STATE -> pcChanged |= transfer.executeStoreReturnState(core, (IrOp.StoreReturnState) op);
                case IrOp.Kind.RETURN_FROM_EXCEPTION -> pcChanged |= transfer.executeReturnFromException(core, (IrOp.ReturnFromException) op);
                case IrOp.Kind.WAIT_FOR_INTERRUPT -> system.executeWaitForInterrupt(core, (IrOp.WaitForInterrupt) op);
                case IrOp.Kind.MOVE_TOP -> alu.executeMoveTop(core, (IrOp.MoveTop) op);
                case IrOp.Kind.MEMORY_BARRIER -> system.executeMemoryBarrier(core, (IrOp.MemoryBarrier) op);
                case IrOp.Kind.SET_IT_STATE -> system.executeSetItState(core, (IrOp.SetItState) op);
                case IrOp.Kind.TABLE_BRANCH -> pcChanged |= branch.executeTableBranch(core, (IrOp.TableBranch) op);
                case IrOp.Kind.COMPARE_BRANCH_ZERO -> pcChanged |= branch.executeCompareBranchZero(core, (IrOp.CompareBranchZero) op);
                case IrOp.Kind.BIT_FIELD_EXTRACT -> alu.executeBitFieldExtract(core, (IrOp.BitFieldExtract) op);
                case IrOp.Kind.BIT_FIELD_INSERT -> alu.executeBitFieldInsert(core, (IrOp.BitFieldInsert) op);
                case IrOp.Kind.BIT_REVERSE -> alu.executeBitReverse(core, (IrOp.BitReverse) op);
                case IrOp.Kind.DIVIDE -> alu.executeDivide(core, (IrOp.Divide) op);
                case IrOp.Kind.VFP_ALU -> vfp.executeVfpAlu(core, (IrOp.VfpAlu) op);
                case IrOp.Kind.VFP_MOVE_IMMEDIATE -> vfp.executeVfpMoveImmediate(core, (IrOp.VfpMoveImmediate) op);
                case IrOp.Kind.VFP_COMPARE -> vfp.executeVfpCompare(core, (IrOp.VfpCompare) op);
                case IrOp.Kind.VFP_CONVERT -> vfp.executeVfpConvert(core, (IrOp.VfpConvert) op);
                case IrOp.Kind.VFP_LOAD -> vfp.executeVfpLoad(core, (IrOp.VfpLoad) op);
                case IrOp.Kind.VFP_STORE -> vfp.executeVfpStore(core, (IrOp.VfpStore) op);
                case IrOp.Kind.VFP_MULTIPLE_TRANSFER -> vfp.executeVfpMultipleTransfer(core, (IrOp.VfpMultipleTransfer) op);
                case IrOp.Kind.VFP_CORE_TRANSFER -> vfp.executeVfpCoreTransfer(core, (IrOp.VfpCoreTransfer) op);
                case IrOp.Kind.VFP_CORE_PAIR_TRANSFER -> vfp.executeVfpCorePairTransfer(core, (IrOp.VfpCorePairTransfer) op);
                case IrOp.Kind.VFP_SYSTEM_TRANSFER -> vfp.executeVfpSystemTransfer(core, (IrOp.VfpSystemTransfer) op);
                case IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE -> vfp.executeVfpCorePairTransferSingle(core, (IrOp.VfpCorePairTransferSingle) op);
                case IrOp.Kind.VFP_CONVERT_FIXED -> vfp.executeVfpConvertFixed(core, (IrOp.VfpConvertFixed) op);
                case IrOp.Kind.M_PROFILE_SYSTEM_REGISTER -> system.executeMProfileSystemRegister(core, (IrOp.MProfileSystemRegister) op);
                case IrOp.Kind.BREAKPOINT -> pcChanged |= system.executeBreakpoint(core, (IrOp.Breakpoint) op, block.endPc());
                    default -> throw new IllegalStateException("IrOp kind desconhecido: " + op.kind());
                }
            }
        } catch (MemoryTranslationException fault) {
            core.enterMemoryAbort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }

    /// Endereço da instrução dona da op no índice `faultIndex` (B4.1.3): cada instrução termina
    /// SEMPRE com `Cycle`+`Fetch` (G4, ver `StandardIrBuilder`), então o primeiro `Fetch` a partir
    /// de `faultIndex` (inclusive) é o desta instrução — só roda no caminho raro de exceção, sem
    /// custo no laço quente de {@link #execute}.
    private static int ownerInstructionAddress(IrOp[] ops, int[] kinds, int faultIndex) {
        for (int j = faultIndex; j < ops.length; j++) {
            if (kinds[j] == IrOp.Kind.FETCH) {
                return ((IrOp.Fetch) ops[j]).address();
            }
        }
        throw new IllegalStateException("bloco sem IrOp.Fetch após o índice " + faultIndex);
    }

    /// Executor de ALU/multiplicação (task A6): exposto para que o módulo `truffle/` possa
    /// despachar DIRETO a cada método de categoria (ex. `IrAluExecutor#execute`), sem passar pelo
    /// `switch` exaustivo de {@link #executeOp} — ver `AluOpNode`/`MultiplyOpNode`.
    public IrAluExecutor aluExecutor() {
        return alu;
    }

    /// Executor de memória (task A6): ver {@link #aluExecutor()}.
    public IrMemoryExecutor memoryExecutor() {
        return memory;
    }

    /// Executor de branch (task A6): ver {@link #aluExecutor()}.
    public IrBranchExecutor branchExecutor() {
        return branch;
    }

    /// Executor de LDM/STM/PUSH/POP/SRS/RFE (task A6): ver {@link #aluExecutor()}.
    public IrTransferExecutor transferExecutor() {
        return transfer;
    }

    /// Executor de PSR/SWI/coprocessador/sistema (task A6): ver {@link #aluExecutor()}.
    public IrSystemExecutor systemExecutor() {
        return system;
    }

    /// Executor de ciclo/fetch (task A6): ver {@link #aluExecutor()}.
    public IrCycleExecutor cycleExecutor() {
        return cycle;
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
            case IrOp.LoadExclusive lex -> { memory.executeLoadExclusive(core, lex); yield false; }
            case IrOp.StoreExclusive sex -> { memory.executeStoreExclusive(core, sex); yield false; }
            case IrOp.ClearExclusive clrex -> { memory.executeClearExclusive(core, clrex); yield false; }
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
            case IrOp.CoprocessorDouble cp -> system.executeCoprocessorDouble(core, cp);
            case IrOp.Undefined undef -> system.executeUndefined(core, undef);
            case IrOp.Cycle cycleOp -> { cycle.executeCycle(cycleOp); yield false; }
            case IrOp.Fetch fetch -> { cycle.executeFetch(core, fetch); yield false; }
            case IrOp.ChangeProcessorState cps -> { system.executeChangeProcessorState(core, cps); yield false; }
            case IrOp.SetEndianness setend -> { system.executeSetEndianness(core, setend); yield false; }
            case IrOp.StoreReturnState srs -> transfer.executeStoreReturnState(core, srs);
            case IrOp.ReturnFromException rfe -> transfer.executeReturnFromException(core, rfe);
            case IrOp.WaitForInterrupt wfi -> { system.executeWaitForInterrupt(core, wfi); yield false; }
            case IrOp.MoveTop moveTop -> { alu.executeMoveTop(core, moveTop); yield false; }
            case IrOp.MemoryBarrier barrier -> { system.executeMemoryBarrier(core, barrier); yield false; }
            case IrOp.SetItState setIt -> { system.executeSetItState(core, setIt); yield false; }
            case IrOp.TableBranch tb -> branch.executeTableBranch(core, tb);
            case IrOp.CompareBranchZero cbz -> branch.executeCompareBranchZero(core, cbz);
            case IrOp.BitFieldExtract bfx -> { alu.executeBitFieldExtract(core, bfx); yield false; }
            case IrOp.BitFieldInsert bfi -> { alu.executeBitFieldInsert(core, bfi); yield false; }
            case IrOp.BitReverse rbit -> { alu.executeBitReverse(core, rbit); yield false; }
            case IrOp.Divide div -> { alu.executeDivide(core, div); yield false; }
            case IrOp.VfpAlu vfpAlu -> { vfp.executeVfpAlu(core, vfpAlu); yield false; }
            case IrOp.VfpMoveImmediate vfpMovImm -> { vfp.executeVfpMoveImmediate(core, vfpMovImm); yield false; }
            case IrOp.VfpCompare vfpCmp -> { vfp.executeVfpCompare(core, vfpCmp); yield false; }
            case IrOp.VfpConvert vfpCvt -> { vfp.executeVfpConvert(core, vfpCvt); yield false; }
            case IrOp.VfpLoad vfpLoad -> { vfp.executeVfpLoad(core, vfpLoad); yield false; }
            case IrOp.VfpStore vfpStore -> { vfp.executeVfpStore(core, vfpStore); yield false; }
            case IrOp.VfpMultipleTransfer vfpMt -> { vfp.executeVfpMultipleTransfer(core, vfpMt); yield false; }
            case IrOp.VfpCoreTransfer vfpCore -> { vfp.executeVfpCoreTransfer(core, vfpCore); yield false; }
            case IrOp.VfpCorePairTransfer vfpCorePair -> { vfp.executeVfpCorePairTransfer(core, vfpCorePair); yield false; }
            case IrOp.VfpSystemTransfer vfpSys -> { vfp.executeVfpSystemTransfer(core, vfpSys); yield false; }
            case IrOp.VfpCorePairTransferSingle vfpCorePairSingle -> { vfp.executeVfpCorePairTransferSingle(core, vfpCorePairSingle); yield false; }
            case IrOp.VfpConvertFixed vfpCvtFixed -> { vfp.executeVfpConvertFixed(core, vfpCvtFixed); yield false; }
            case IrOp.MProfileSystemRegister m -> { system.executeMProfileSystemRegister(core, m); yield false; }
            case IrOp.Breakpoint bkpt -> system.executeBreakpoint(core, bkpt, blockEndPc);
        };
    }

    /// Executor de VFP (task B3.4): ver {@link #aluExecutor()}.
    public IrVfpExecutor vfpExecutor() {
        return vfp;
    }
}
