package dev.vitorsilverio.armjitter.truffle;

import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Escolhe, na EMISSÃO do bloco (não em tempo de execução), a subclasse concreta de
/// {@link IrOpNode} para cada `IrOp` (task A6, item 1 da especificação: "escolher a subclasse de
/// nó NA EMISSÃO... de modo que em runtime não haja switch nenhum" no nível da árvore — dentro de
/// cada nó ainda há um `switch` pequeno sobre os poucos records daquela categoria, decisão
/// explícita da especificação).
///
/// <p>O `switch` de 40 casos aqui é INÓCUO para o partial evaluation: roda uma única vez por op,
/// em tempo de montagem da árvore (chamado por {@link TruffleCodeEmitter#emit}), nunca dentro de
/// `executeBlock`/`doExecute` — o Graal nunca vê este método.</p>
final class IrOpNodeFactory {
    private IrOpNodeFactory() {
    }

    static IrOpNode create(IrOp op, IrBlockExecutor executor) {
        return switch (op.kind()) {
            case IrOp.Kind.ALU, IrOp.Kind.MOVE_TOP, IrOp.Kind.SEL, IrOp.Kind.SATURATE,
                    IrOp.Kind.ABS_DIFF_SUM, IrOp.Kind.SATURATING ->
                    new AluOpNode(op, executor.aluExecutor());
            case IrOp.Kind.MULTIPLY, IrOp.Kind.LONG_MULTIPLY, IrOp.Kind.DSP_MULTIPLY, IrOp.Kind.PARALLEL_ALU ->
                    new MultiplyOpNode(op, executor.aluExecutor());
            case IrOp.Kind.LOAD, IrOp.Kind.STORE, IrOp.Kind.LOAD_LITERAL, IrOp.Kind.DOUBLE_TRANSFER,
                    IrOp.Kind.SWAP, IrOp.Kind.LOAD_EXCLUSIVE, IrOp.Kind.STORE_EXCLUSIVE, IrOp.Kind.CLEAR_EXCLUSIVE ->
                    new MemoryOpNode(op, executor.memoryExecutor());
            case IrOp.Kind.MULTIPLE_TRANSFER, IrOp.Kind.PUSH, IrOp.Kind.POP ->
                    new TransferOpNode(op, executor.transferExecutor());
            case IrOp.Kind.BRANCH, IrOp.Kind.BRANCH_EXCHANGE, IrOp.Kind.THUMB_BL_PREFIX,
                    IrOp.Kind.THUMB_BL_SUFFIX, IrOp.Kind.TABLE_BRANCH, IrOp.Kind.COMPARE_BRANCH_ZERO ->
                    new BranchOpNode(op, executor.branchExecutor());
            case IrOp.Kind.PSR_TRANSFER, IrOp.Kind.SWI, IrOp.Kind.COPROCESSOR, IrOp.Kind.UNDEFINED,
                    IrOp.Kind.CHANGE_PROCESSOR_STATE, IrOp.Kind.SET_ENDIANNESS, IrOp.Kind.STORE_RETURN_STATE,
                    IrOp.Kind.RETURN_FROM_EXCEPTION, IrOp.Kind.WAIT_FOR_INTERRUPT, IrOp.Kind.MEMORY_BARRIER,
                    IrOp.Kind.SET_IT_STATE ->
                    new SystemOpNode(op, executor.systemExecutor(), executor.transferExecutor());
            case IrOp.Kind.CYCLE, IrOp.Kind.FETCH -> new CycleFetchOpNode(op, executor.cycleExecutor());
            default -> throw new IllegalStateException("IrOp kind desconhecido: " + op.kind());
        };
    }
}
