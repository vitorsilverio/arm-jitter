package dev.vitorsilverio.armjitter.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.vitorsilverio.armjitter.codegen.executor.IrSystemExecutor;
import dev.vitorsilverio.armjitter.codegen.executor.IrTransferExecutor;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.SystemOp;

/// Nó Truffle para a categoria de sistema (task A6, + `Hvc`/`Smc`/`Eret`/`MrsBank`/`MsrBank`/
/// `Breakpoint` desde a A10.5): `PsrTransfer`, `Swi`, `Coprocessor`, `Undefined`,
/// `ChangeProcessorState`, `SetEndianness`, `StoreReturnState`, `ReturnFromException`,
/// `WaitForInterrupt`, `MemoryBarrier`, `SetItState`, `Hvc`, `Smc`, `Eret`, `MrsBank`, `MsrBank`,
/// `Breakpoint` — a taxonomia da especificação agrupa `StoreReturnState`/`ReturnFromException`
/// aqui mesmo vivendo em {@link IrTransferExecutor} no `core/`, por isso este nó guarda os DOIS
/// executores.
///
/// <p>Só `Swi` (delega a `SwiDispatcher#dispatch`), `Coprocessor` (delega a
/// `CoprocessorBus#read/write`), `StoreReturnState`, `ReturnFromException` (ambos empilham/
/// restauram via `AddressSpace`) e `Breakpoint` (delega a `BkptDispatcher#dispatch`, A10.5)
/// escapam para código do hospedeiro — só esses 5 casos ficam atrás de {@link TruffleBoundary}
/// (item 3 da especificação). `PsrTransfer`/`Undefined`/`ChangeProcessorState`/`SetEndianness`/
/// `WaitForInterrupt`/`MemoryBarrier`/`SetItState`/`Hvc`/`Smc`/`Eret`/`MrsBank`/`MsrBank` só
/// leem/escrevem CPSR/SPSR/registradores/banco de outro modo (semântica pura do core, sem
/// colaborador externo — `core.requestException` é só uma mudança de estado, não um `throw`) —
/// abertos ao partial evaluation. `Hvc`/`Smc`/`Breakpoint` podem entrar em exceção de guest no
/// meio de um bloco (mesma classe de cuidado documentada pela E7 para o backend ASM) e `Eret`
/// muda PC/modo — ambos terminam o `IrBlock` POR CONSTRUÇÃO (`StandardIrBlockLifter`, os 6 `Kind`
/// desta task estão na lista de kinds que encerram o bloco), então o nó seguinte deste array
/// nunca pertence à mesma instrução: não há necessidade de lógica extra aqui para "parar".</p>
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
            case SystemOp.PsrTransfer psrTransfer -> {
                systemExecutor.executePsrTransfer(core, psrTransfer);
                yield false;
            }
            case SystemOp.Swi swi -> executeSwiAtBoundary(swi, core, blockEndPc);
            case SystemOp.Coprocessor coprocessor -> executeCoprocessorAtBoundary(coprocessor, core);
            case SystemOp.Undefined undefined -> systemExecutor.executeUndefined(core, undefined);
            case SystemOp.ChangeProcessorState changeProcessorState -> {
                systemExecutor.executeChangeProcessorState(core, changeProcessorState);
                yield false;
            }
            case SystemOp.SetEndianness setEndianness -> {
                systemExecutor.executeSetEndianness(core, setEndianness);
                yield false;
            }
            case SystemOp.StoreReturnState storeReturnState -> executeStoreReturnStateAtBoundary(storeReturnState, core);
            case SystemOp.ReturnFromException returnFromException ->
                    executeReturnFromExceptionAtBoundary(returnFromException, core);
            case SystemOp.WaitForInterrupt waitForInterrupt -> {
                systemExecutor.executeWaitForInterrupt(core, waitForInterrupt);
                yield false;
            }
            case SystemOp.MemoryBarrier memoryBarrier -> {
                systemExecutor.executeMemoryBarrier(core, memoryBarrier);
                yield false;
            }
            case SystemOp.SetItState setItState -> {
                systemExecutor.executeSetItState(core, setItState);
                yield false;
            }
            case SystemOp.Hvc hvc -> systemExecutor.executeHvc(core, hvc, blockEndPc);
            case SystemOp.Smc smc -> systemExecutor.executeSmc(core, smc, blockEndPc);
            case SystemOp.Eret eret -> systemExecutor.executeEret(core, eret, blockEndPc);
            case SystemOp.MrsBank mrsBank -> systemExecutor.executeMrsBank(core, mrsBank, blockEndPc);
            case SystemOp.MsrBank msrBank -> systemExecutor.executeMsrBank(core, msrBank, blockEndPc);
            case SystemOp.Breakpoint breakpoint -> executeBreakpointAtBoundary(breakpoint, core, blockEndPc);
            default -> throw new IllegalStateException("SystemOpNode não cobre: " + op);
        };
    }

    @TruffleBoundary
    private boolean executeSwiAtBoundary(SystemOp.Swi swi, ArmCore core, int blockEndPc) {
        return systemExecutor.executeSwi(core, swi, blockEndPc);
    }

    @TruffleBoundary
    private boolean executeCoprocessorAtBoundary(SystemOp.Coprocessor coprocessor, ArmCore core) {
        return systemExecutor.executeCoprocessor(core, coprocessor);
    }

    @TruffleBoundary
    private boolean executeStoreReturnStateAtBoundary(SystemOp.StoreReturnState storeReturnState, ArmCore core) {
        return transferExecutor.executeStoreReturnState(core, storeReturnState);
    }

    @TruffleBoundary
    private boolean executeReturnFromExceptionAtBoundary(SystemOp.ReturnFromException returnFromException, ArmCore core) {
        return transferExecutor.executeReturnFromException(core, returnFromException);
    }

    @TruffleBoundary
    private boolean executeBreakpointAtBoundary(SystemOp.Breakpoint breakpoint, ArmCore core, int blockEndPc) {
        return systemExecutor.executeBreakpoint(core, breakpoint, blockEndPc);
    }
}
