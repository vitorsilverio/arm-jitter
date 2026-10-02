package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.BranchOp;
import dev.vitorsilverio.armjitter.ir.IntegerOp;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.MemoryOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;
import dev.vitorsilverio.armjitter.memory.mpu.PmsaAccessException;
import dev.vitorsilverio.armjitter.memory.mpu.Pmsav8AccessException;

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
    private final IrNeonExecutor neon;

    /// Cria um executor para a arquitetura informada.
    public IrBlockExecutor(ArmArchitecture architecture) {
        IrExecutionSupport support = new IrExecutionSupport(architecture);
        this.alu = new IrAluExecutor(support);
        this.memory = new IrMemoryExecutor(support);
        this.branch = new IrBranchExecutor(support);
        this.transfer = new IrTransferExecutor(support);
        this.system = new IrSystemExecutor(support);
        this.cycle = new IrCycleExecutor();
        this.neon = new IrNeonExecutor(support);
        this.vfp = new IrVfpExecutor(support, neon);
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
                // Caso próprio só para `Cycle`/`Fetch` (2 de cada 3 ops; o laço soma os ciclos) e para os
                // 12 `Kind` que o gate de desempenho da E15.5 mediu como quentes (99,98% das ops em 5
                // jogos de GBA): ali `tableswitch` + chamada direta é inlinável, e a ponte megamórfica
                // custou até 15% de throughput.
                // Todo o resto é roteado pela própria op (`IrOp#execute`).
                IrOp op = ops[i];
                switch (kinds[i]) {
                case IrOp.Kind.CYCLE -> cycles += cycle.executeCycle((IrOp.Cycle) op);
                case IrOp.Kind.FETCH -> cycle.executeFetch(core, (IrOp.Fetch) op);
                case IrOp.Kind.ALU -> pcChanged |= alu.execute(core, (IntegerOp.Alu) op);
                case IrOp.Kind.LOAD -> pcChanged |= memory.executeLoad(core, (MemoryOp.Load) op);
                case IrOp.Kind.BRANCH -> pcChanged |= branch.executeBranch(core, (BranchOp.Branch) op);
                case IrOp.Kind.MULTIPLY -> alu.executeMultiply(core, (IntegerOp.Multiply) op);
                case IrOp.Kind.STORE -> memory.executeStore(core, (MemoryOp.Store) op);
                case IrOp.Kind.LOAD_LITERAL -> pcChanged |= memory.executeLoadLiteral(core, (MemoryOp.LoadLiteral) op);
                case IrOp.Kind.MULTIPLE_TRANSFER -> pcChanged |= transfer.executeMultipleTransfer(core, (MemoryOp.MultipleTransfer) op);
                case IrOp.Kind.POP -> pcChanged |= transfer.executePop(core, (MemoryOp.Pop) op);
                case IrOp.Kind.BRANCH_EXCHANGE -> pcChanged |= branch.executeBranchExchange(core, (BranchOp.BranchExchange) op);
                case IrOp.Kind.PUSH -> transfer.executePush(core, (MemoryOp.Push) op);
                case IrOp.Kind.THUMB_BL_PREFIX -> branch.executeThumbBlPrefix(core, (BranchOp.ThumbBlPrefix) op);
                case IrOp.Kind.THUMB_BL_SUFFIX -> pcChanged |= branch.executeThumbBlSuffix(core, (BranchOp.ThumbBlSuffix) op);
                // ADVANCE_VPT (B16.2)/ADVANCE_ECI (B16.5) são pulados quando a instrução MVE anterior no
                // MESMO bloco já mudou o PC (fault de ECI reservado) — ver Javadoc de
                // IrSystemExecutor#executeAdvanceVpt. `executeOp` não tem esse gate. A checagem fica
                // no `default` (e não em casos próprios) para o `switch` continuar denso (`tableswitch`).
                default -> {
                    if (!pcChanged || !isPredicationAdvance(kinds[i])) {
                        pcChanged |= op.execute(this, core, block.endPc());
                    }
                }
                }
            }
        } catch (MemoryTranslationException fault) {
            core.enterMemoryAbort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        } catch (PmsaAccessException fault) {
            // B20.3: mesmo tratamento acima, catch à parte (Armadilha 4 da B20.3 — classe irmã,
            // nunca subtipo de MemoryTranslationException).
            core.enterPmsaAbort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        } catch (Pmsav8AccessException fault) {
            // B20.7: mesmo tratamento acima, catch à parte (terceira classe irmã).
            core.enterPmsav8Abort(ownerInstructionAddress(ops, kinds, i), fault);
            return cycles;
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }

    /// `true` para os dois `Kind` que só avançam o estado de predicação/ECI de uma instrução MVE
    /// que terminou sem fault — os únicos que {@link #execute} pula depois de o PC ter mudado.
    private static boolean isPredicationAdvance(int kind) {
        return kind == IrOp.Kind.ADVANCE_VPT || kind == IrOp.Kind.ADVANCE_ECI;
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
    /// despachar DIRETO a cada método de categoria (ex. `IrAluExecutor#execute`) — ver
    /// `AluOpNode`/`MultiplyOpNode`. Desde a E15.5 é também por aqui que a ponte
    /// {@link IrOp#execute} de cada `record` alcança o executor da sua família.
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
    /// para executar ops não suportadas nativamente inline no bytecode JVM gerado. Equivale a
    /// {@link IrOp#execute} (task E15.5): não soma ciclos nem aplica o gate de `pcChanged` de
    /// `AdvanceVpt`/`AdvanceEci`, que são do laço de {@link #execute}.
    ///
    /// @param blockEndPc PC sequencial do fim do bloco (necessário para {@link SystemOp.Swi})
    public boolean executeOp(ArmCore core, IrOp op, int blockEndPc) {
        return op.execute(this, core, blockEndPc);
    }

    /// Executor de VFP (task B3.4): ver {@link #aluExecutor()}.
    public IrVfpExecutor vfpExecutor() {
        return vfp;
    }

    /// Executor de NEON (task E15.5): ver {@link #aluExecutor()}.
    public IrNeonExecutor neonExecutor() {
        return neon;
    }
}
