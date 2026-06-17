package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Executa PSR, SWI, coprocessador e instruções indefinidas da IR interpretada.
final class IrSystemExecutor {
    private final IrExecutionSupport support;

    IrSystemExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    void executePsrTransfer(ArmCore core, IrOp.PsrTransfer transfer) {
        if (!core.cpsr().evalCond(transfer.condition())) {
            return;
        }
        CpuMode psrMode = core.mode();
        boolean hasSPSR = psrMode != CpuMode.USER && psrMode != CpuMode.SYSTEM;
        if (transfer.read()) {
            int value = (transfer.spsr() && hasSPSR) ? core.spsr(psrMode) : core.cpsr().get();
            core.setRegister(transfer.register(), value);
            return;
        }
        int value = transfer.immediateOperand()
                ? transfer.immediate()
                : support.registerValue(core, transfer.register(), transfer.registerValueOverride());
        if (transfer.spsr()) {
            if (hasSPSR) {
                core.setSpsr(psrMode, support.mergePsr(core.spsr(psrMode), value, transfer.fieldMask()));
            }
        } else {
            core.setCpsr(support.mergePsr(core.cpsr().get(), value, support.cpsrWriteFieldMask(core, transfer.fieldMask())));
        }
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeSwi(ArmCore core, IrOp.Swi swi, int sequentialPc) {
        if (!core.cpsr().evalCond(swi.condition())) {
            return false;
        }
        core.setProgramCounter(sequentialPc);
        if (core.swiDispatcher().canDispatch(swi.immediate())) {
            CpuState next = core.swiDispatcher().dispatch(swi.immediate(), core.toCpuState());
            core.apply(next);
            return true;
        }
        core.requestException(ArmException.SWI);
        return true;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeCoprocessor(ArmCore core, IrOp.Coprocessor cp) {
        if (!core.cpsr().evalCond(cp.condition())) {
            return false;
        }
        CoprocessorBus bus = core.coprocessorBus();
        if (!bus.handles(cp.coprocessor())) {
            core.setProgramCounter(cp.sequentialPc());
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (cp.load()) {
            int value = bus.read(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2());
            if (cp.register() == 15) {
                core.cpsr().setNzcv((value & 0x8000_0000) != 0, (value & 0x4000_0000) != 0,
                        (value & 0x2000_0000) != 0, (value & 0x1000_0000) != 0);
            } else {
                core.setRegister(cp.register(), value);
            }
        } else {
            bus.write(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2(),
                    core.register(cp.register()));
        }
        return false;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeUndefined(ArmCore core, IrOp.Undefined undefined) {
        if (!core.cpsr().evalCond(undefined.condition())) {
            return false;
        }
        core.setProgramCounter(undefined.sequentialPc());
        core.requestException(ArmException.UNDEFINED);
        return true;
    }
}
