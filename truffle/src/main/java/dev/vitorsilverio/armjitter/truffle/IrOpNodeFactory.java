package dev.vitorsilverio.armjitter.truffle;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Escolhe, na EMISSÃO do bloco (não em tempo de execução), a subclasse concreta de
/// {@link IrOpNode} para cada `IrOp` (task A6, item 1 da especificação: "escolher a subclasse de
/// nó NA EMISSÃO... de modo que em runtime não haja switch nenhum" no nível da árvore — dentro de
/// cada nó ainda há um `switch` pequeno sobre os poucos records daquela categoria, decisão
/// explícita da especificação).
///
/// <p>Este mapeamento **NÃO é exaustivo** sobre `IrOp.Kind`: cobre 66 dos 84 `Kind` — as 7
/// categorias da taxonomia da A6 (ALU escalar, multiplicação, memória, transferência múltipla,
/// branch, sistema, ciclo/fetch) mais VFP (A10.3); a multiplicação inclui
/// `DspDualMultiply`/`DspTopWordMultiply` desde a A10.6, a ALU inclui bitfield/`RBIT`/`SDIV`/
/// `UDIV` desde a A10.4, e o sistema inclui `Hvc`/`Smc`/`Eret`/`MrsBank`/`MsrBank`/`Breakpoint`
/// desde a A10.5. Os 18 `Kind` restantes (todo NEON) foram acrescentados por B13 DEPOIS da A6 e
/// ainda não têm nó Truffle especializado — fechado por task própria (A10.7). Enquanto isso,
/// {@link TruffleCodeEmitter} desvia os blocos que contêm um desses `Kind` para o
/// {@link dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter} (via {@link #supports}), em
/// vez de quebrar.</p>
///
/// <p>A lista de `Kind` suportados vive num ÚNICO ponto — {@link #category(IrOp)} — consultado
/// tanto por {@link #create} quanto por {@link #supports}, para que as duas nunca divirjam (o
/// `TruffleCodeEmitterSupportsCoherenceTest` trava esse contrato). O `default -> null` de
/// `category` só é alcançável, via `create`, por bug: {@link #supports} filtra o bloco antes.</p>
///
/// <p>O `switch` de {@link #category(IrOp)} é INÓCUO para o partial evaluation: roda uma única vez
/// por op, em tempo de montagem da árvore (chamado por {@link TruffleCodeEmitter#emit}), nunca
/// dentro de `executeBlock`/`doExecute` — o Graal nunca vê este método.</p>
final class IrOpNodeFactory {
    private IrOpNodeFactory() {
    }

    /// Categoria de nó da taxonomia da A6. Fonte única de verdade compartilhada por
    /// {@link #create} e {@link #supports}.
    private enum Category {
        ALU, MULTIPLY, MEMORY, TRANSFER, BRANCH, SYSTEM, CYCLE_FETCH, VFP
    }

    static IrOpNode create(IrOp op, IrBlockExecutor executor) {
        Category category = category(op);
        if (category == null) {
            throw new IllegalStateException("IrOp kind desconhecido: " + op.kind());
        }
        return switch (category) {
            case ALU -> new AluOpNode(op, executor.aluExecutor());
            case MULTIPLY -> new MultiplyOpNode(op, executor.aluExecutor());
            case MEMORY -> new MemoryOpNode(op, executor.memoryExecutor());
            case TRANSFER -> new TransferOpNode(op, executor.transferExecutor());
            case BRANCH -> new BranchOpNode(op, executor.branchExecutor());
            case SYSTEM -> new SystemOpNode(op, executor.systemExecutor(), executor.transferExecutor());
            case CYCLE_FETCH -> new CycleFetchOpNode(op, executor.cycleExecutor());
            case VFP -> new VfpOpNode(op, executor.vfpExecutor(), executor.systemExecutor());
        };
    }

    /// `true` exatamente para os `Kind` que {@link #create} sabe construir um nó especializado —
    /// os demais são delegados ao interpretador por {@link TruffleCodeEmitter}. Deriva da MESMA
    /// {@link #category(IrOp)} que `create`, então nunca diverge dela.
    static boolean supports(IrOp op) {
        return category(op) != null;
    }

    private static Category category(IrOp op) {
        return switch (op.kind()) {
            case IrOp.Kind.ALU, IrOp.Kind.MOVE_TOP, IrOp.Kind.SEL, IrOp.Kind.SATURATE,
                    IrOp.Kind.ABS_DIFF_SUM, IrOp.Kind.SATURATING, IrOp.Kind.BIT_FIELD_EXTRACT,
                    IrOp.Kind.BIT_FIELD_INSERT, IrOp.Kind.BIT_REVERSE, IrOp.Kind.DIVIDE ->
                    Category.ALU;
            case IrOp.Kind.MULTIPLY, IrOp.Kind.LONG_MULTIPLY, IrOp.Kind.DSP_MULTIPLY, IrOp.Kind.PARALLEL_ALU,
                    IrOp.Kind.DSP_DUAL_MULTIPLY, IrOp.Kind.DSP_TOP_WORD_MULTIPLY ->
                    Category.MULTIPLY;
            case IrOp.Kind.LOAD, IrOp.Kind.STORE, IrOp.Kind.LOAD_LITERAL, IrOp.Kind.DOUBLE_TRANSFER,
                    IrOp.Kind.SWAP, IrOp.Kind.LOAD_EXCLUSIVE, IrOp.Kind.STORE_EXCLUSIVE, IrOp.Kind.CLEAR_EXCLUSIVE ->
                    Category.MEMORY;
            case IrOp.Kind.MULTIPLE_TRANSFER, IrOp.Kind.PUSH, IrOp.Kind.POP ->
                    Category.TRANSFER;
            case IrOp.Kind.BRANCH, IrOp.Kind.BRANCH_EXCHANGE, IrOp.Kind.THUMB_BL_PREFIX,
                    IrOp.Kind.THUMB_BL_SUFFIX, IrOp.Kind.TABLE_BRANCH, IrOp.Kind.COMPARE_BRANCH_ZERO ->
                    Category.BRANCH;
            case IrOp.Kind.PSR_TRANSFER, IrOp.Kind.SWI, IrOp.Kind.COPROCESSOR, IrOp.Kind.UNDEFINED,
                    IrOp.Kind.CHANGE_PROCESSOR_STATE, IrOp.Kind.SET_ENDIANNESS, IrOp.Kind.STORE_RETURN_STATE,
                    IrOp.Kind.RETURN_FROM_EXCEPTION, IrOp.Kind.WAIT_FOR_INTERRUPT, IrOp.Kind.MEMORY_BARRIER,
                    IrOp.Kind.SET_IT_STATE,
                    // A10.5: Hyp/Monitor de 32 bits (B9.8) + Virtualization Extensions (B22.5).
                    IrOp.Kind.HVC, IrOp.Kind.SMC, IrOp.Kind.ERET, IrOp.Kind.MRS_BANK, IrOp.Kind.MSR_BANK,
                    IrOp.Kind.BREAKPOINT ->
                    Category.SYSTEM;
            case IrOp.Kind.CYCLE, IrOp.Kind.FETCH -> Category.CYCLE_FETCH;
            case IrOp.Kind.VFP_ALU, IrOp.Kind.VFP_MOVE_IMMEDIATE, IrOp.Kind.VFP_COMPARE,
                    IrOp.Kind.VFP_CONVERT, IrOp.Kind.VFP_LOAD, IrOp.Kind.VFP_STORE,
                    IrOp.Kind.VFP_MULTIPLE_TRANSFER, IrOp.Kind.VFP_CORE_TRANSFER,
                    IrOp.Kind.VFP_CORE_PAIR_TRANSFER, IrOp.Kind.VFP_SYSTEM_TRANSFER,
                    IrOp.Kind.M_PROFILE_SYSTEM_REGISTER, IrOp.Kind.COPROCESSOR_DOUBLE,
                    IrOp.Kind.VFP_CORE_PAIR_TRANSFER_SINGLE, IrOp.Kind.VFP_CONVERT_FIXED ->
                    Category.VFP;
            default -> null;
        };
    }
}
