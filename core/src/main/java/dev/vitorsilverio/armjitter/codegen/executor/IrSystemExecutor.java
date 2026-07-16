package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Executa PSR, SWI, coprocessador e instruções indefinidas da IR interpretada.
public final class IrSystemExecutor {
    private final IrExecutionSupport support;

    IrSystemExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    public void executePsrTransfer(ArmCore core, IrOp.PsrTransfer transfer) {
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
    public boolean executeSwi(ArmCore core, IrOp.Swi swi, int sequentialPc) {
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
    public boolean executeCoprocessor(ArmCore core, IrOp.Coprocessor cp) {
        if (!core.cpsr().evalCond(cp.condition())) {
            return false;
        }
        CoprocessorBus bus = core.coprocessorBus();
        if (!bus.handles(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2())) {
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
    public boolean executeUndefined(ArmCore core, IrOp.Undefined undefined) {
        if (!core.cpsr().evalCond(undefined.condition())) {
            return false;
        }
        core.setProgramCounter(undefined.sequentialPc());
        core.requestException(ArmException.UNDEFINED);
        return true;
    }

    /// `CPS`/`CPSIE`/`CPSID` (ARMv6): reusa {@link ArmCore#setCpsr} — o mesmo caminho de troca de
    /// banco que `MSR`/entrada de exceção usam — para que a troca de modo (quando presente)
    /// rebanque os registradores corretamente. UNPREDICTABLE em modo User: tratado como NOP.
    public void executeChangeProcessorState(ArmCore core, IrOp.ChangeProcessorState cps) {
        if (!core.cpsr().evalCond(cps.condition())) {
            return;
        }
        if (core.mode() == CpuMode.USER) {
            return;
        }
        int value = core.cpsr().get();
        if (cps.changeFlags()) {
            int mask = (cps.changeA() ? CpsrRegister.ABORT_DISABLE_FLAG : 0)
                    | (cps.changeI() ? CpsrRegister.IRQ_DISABLE_FLAG : 0)
                    | (cps.changeF() ? CpsrRegister.FIQ_DISABLE_FLAG : 0);
            value = cps.enable() ? (value & ~mask) : (value | mask);
        }
        if (cps.changeMode()) {
            value = (value & ~0b11111) | cps.mode();
        }
        core.setCpsr(value);
    }

    /// `SETEND` (ARMv6): seta o bit E do CPSR. Acessos de dados subsequentes com E=1 lançam
    /// {@link UnsupportedOperationException} (ver {@code IrExecutionSupport}).
    public void executeSetEndianness(ArmCore core, IrOp.SetEndianness setend) {
        if (!core.cpsr().evalCond(setend.condition())) {
            return;
        }
        core.cpsr().setBigEndian(setend.bigEndian());
    }

    /// `WFI` (ARMv6K hint): coloca o core em HALT (acorda com {@code setInterruptLine(true)}).
    public void executeWaitForInterrupt(ArmCore core, IrOp.WaitForInterrupt wfi) {
        if (!core.cpsr().evalCond(wfi.condition())) {
            return;
        }
        core.halt();
    }

    /// `DMB`/`DSB`/`ISB` (ARMv7, Thumb-2 — B2.5): NOP observável neste core single-thread sem
    /// reordenação real de memória (premissa documentada em {@link IrOp.MemoryBarrier}). Ainda
    /// checa a condição por simetria com o resto do executor, mesmo não havendo efeito algum.
    public void executeMemoryBarrier(ArmCore core, IrOp.MemoryBarrier barrier) {
        if (!core.cpsr().evalCond(barrier.condition())) {
            return;
        }
        // Intencionalmente vazio: ver justificativa em IrOp.MemoryBarrier.
    }

    /// Grava o ITSTATE\[7:0\] do CPSR (Thumb-2 IT block, B2.4). Ver o javadoc de
    /// {@link IrOp.SetItState} para as duas origens (entrada do `IT`/avanço pós-instrução) e por
    /// que o avanço é sempre emitido com condição AL pelo lifter.
    public void executeSetItState(ArmCore core, IrOp.SetItState setItState) {
        if (!core.cpsr().evalCond(setItState.condition())) {
            return;
        }
        core.cpsr().setItState(setItState.itState());
    }
}
