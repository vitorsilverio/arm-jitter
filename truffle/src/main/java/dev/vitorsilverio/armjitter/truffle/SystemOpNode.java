package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrSystemExecutor;
import dev.vitorsilverio.armjitter.codegen.executor.IrTransferExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Nó Truffle para a categoria de sistema (task A6): `PsrTransfer`, `Swi`, `Coprocessor`,
/// `Undefined`, `ChangeProcessorState`, `SetEndianness`, `StoreReturnState`,
/// `ReturnFromException`, `WaitForInterrupt`, `MemoryBarrier`, `SetItState` — a taxonomia da
/// especificação agrupa `StoreReturnState`/`ReturnFromException` aqui mesmo vivendo em
/// {@link IrTransferExecutor} no `core/`, por isso este nó guarda os DOIS executores.
///
/// <p>Só `Swi` (delega a `SwiDispatcher#dispatch`), `Coprocessor` (delega a
/// `CoprocessorBus#read/write`), `StoreReturnState` e `ReturnFromException` (ambos empilham/
/// restauram via `AddressSpace`) escapam para código do hospedeiro — só esses 4 casos ficam atrás
/// de {@link TruffleBoundary} (item 3 da especificação). `PsrTransfer`/`Undefined`/
/// `ChangeProcessorState`/`SetEndianness`/`WaitForInterrupt`/`MemoryBarrier`/`SetItState` só
/// leem/escrevem CPSR/SPSR/registradores — abertos ao partial evaluation.</p>
final class SystemOpNode extends IrOpNode {
    @CompilationFinal
    private final IrOp op;
    private final IrSystemExecutor systemExecutor;
    private final IrTransferExecutor transferExecutor;

    SystemOpNode(IrOp op, IrSystemExecutor systemExecutor, IrTransferExecutor transferExecutor) {
        super(op.condition());
        this.op = op;
        this.systemExecutor = systemExecutor;
        this.transferExecutor = transferExecutor;
    }

    @Override
    boolean doExecute(ArmCore core, int blockEndPc) {
        return switch (op) {
            case IrOp.PsrTransfer psrTransfer -> {
                systemExecutor.executePsrTransfer(core, psrTransfer);
                yield false;
            }
            case IrOp.Swi swi -> executeSwiAtBoundary(swi, core, blockEndPc);
            case IrOp.Coprocessor coprocessor -> executeCoprocessorAtBoundary(coprocessor, core);
            case IrOp.Undefined undefined -> systemExecutor.executeUndefined(core, undefined);
            case IrOp.ChangeProcessorState changeProcessorState -> {
                systemExecutor.executeChangeProcessorState(core, changeProcessorState);
                yield false;
            }
            case IrOp.SetEndianness setEndianness -> {
                systemExecutor.executeSetEndianness(core, setEndianness);
                yield false;
            }
            case IrOp.StoreReturnState storeReturnState -> executeStoreReturnStateAtBoundary(storeReturnState, core);
            case IrOp.ReturnFromException returnFromException ->
                    executeReturnFromExceptionAtBoundary(returnFromException, core);
            case IrOp.WaitForInterrupt waitForInterrupt -> {
                systemExecutor.executeWaitForInterrupt(core, waitForInterrupt);
                yield false;
            }
            case IrOp.MemoryBarrier memoryBarrier -> {
                systemExecutor.executeMemoryBarrier(core, memoryBarrier);
                yield false;
            }
            case IrOp.SetItState setItState -> {
                systemExecutor.executeSetItState(core, setItState);
                yield false;
            }
            default -> throw new IllegalStateException("SystemOpNode não cobre: " + op);
        };
    }

    @TruffleBoundary
    private boolean executeSwiAtBoundary(IrOp.Swi swi, ArmCore core, int blockEndPc) {
        return systemExecutor.executeSwi(core, swi, blockEndPc);
    }

    @TruffleBoundary
    private boolean executeCoprocessorAtBoundary(IrOp.Coprocessor coprocessor, ArmCore core) {
        return systemExecutor.executeCoprocessor(core, coprocessor);
    }

    @TruffleBoundary
    private boolean executeStoreReturnStateAtBoundary(IrOp.StoreReturnState storeReturnState, ArmCore core) {
        return transferExecutor.executeStoreReturnState(core, storeReturnState);
    }

    @TruffleBoundary
    private boolean executeReturnFromExceptionAtBoundary(IrOp.ReturnFromException returnFromException, ArmCore core) {
        return transferExecutor.executeReturnFromException(core, returnFromException);
    }
}
