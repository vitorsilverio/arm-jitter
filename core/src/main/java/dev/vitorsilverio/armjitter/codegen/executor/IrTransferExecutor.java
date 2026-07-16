package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa LDM/STM, PUSH e POP da IR interpretada.
public final class IrTransferExecutor {
    private final IrExecutionSupport support;

    IrTransferExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeMultipleTransfer(ArmCore core, IrOp.MultipleTransfer transfer) {
        if (!core.cpsr().evalCond(transfer.condition())) {
            return false;
        }
        int mask = support.effectiveRegisterMask(transfer.registerMask(), transfer.emptyRegisterList());
        int count = support.effectiveRegisterCount(transfer.registerMask(), transfer.emptyRegisterList());
        int base = core.register(transfer.base());
        int address = transfer.mode().startAddress(base, count) & ~3;
        boolean includesPc = (mask & (1 << 15)) != 0;
        boolean forceUser = transfer.userMode() && !includesPc;
        int loadedPc = 0;
        int writebackAddress = transfer.mode().writebackAddress(base, count);
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        for (int register = 0; register <= 15; register++) {
            if ((mask & (1 << register)) != 0) {
                if (transfer.load()) {
                    int value = support.read32Arm7(core, address);
                    if (transfer.userMode() && includesPc && register == 15) {
                        loadedPc = value;
                    } else if (forceUser) {
                        core.setBankedRegister(CpuMode.USER, register, value);
                    } else {
                        support.writeLoadedRegister(core, register, value);
                    }
                } else {
                    int value = support.multipleStoreRegisterValue(core, transfer, register, firstRegister, writebackAddress);
                    support.write32Arm7(core, address, value);
                }
                address += 4;
            }
        }
        if (support.shouldWriteBackMultiple(transfer.writeback(), transfer.load(), mask, transfer.base())) {
            core.setRegister(transfer.base(), writebackAddress);
        }
        if (transfer.load() && transfer.userMode() && includesPc) {
            support.restoreCpsrFromCurrentSpsr(core);
            support.alignAndSetPc(core, loadedPc);
        }
        return transfer.load() && includesPc;
    }

    public void executePush(ArmCore core, IrOp.Push push) {
        if (!core.cpsr().evalCond(push.condition())) {
            return;
        }
        int count = Integer.bitCount(push.registerMask()) + (push.includeLr() ? 1 : 0);
        int address = core.register(13) - count * 4;
        int current = address;
        for (int register = 0; register <= 7; register++) {
            if ((push.registerMask() & (1 << register)) != 0) {
                support.write32Arm7(core, current, core.register(register));
                current += 4;
            }
        }
        if (push.includeLr()) {
            support.write32Arm7(core, current, core.register(14));
        }
        core.setRegister(13, address);
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executePop(ArmCore core, IrOp.Pop pop) {
        if (!core.cpsr().evalCond(pop.condition())) {
            return false;
        }
        int current = core.register(13);
        for (int register = 0; register <= 7; register++) {
            if ((pop.registerMask() & (1 << register)) != 0) {
                core.setRegister(register, support.read32Arm7(core, current));
                current += 4;
            }
        }
        boolean pcChanged = false;
        if (pop.includePc()) {
            int value = support.read32Arm7(core, current);
            current += 4;
            support.loadToPc(core, value);
            pcChanged = true;
        }
        core.setRegister(13, current);
        return pcChanged;
    }

    /// `SRS` (ARMv6): empilha LR e SPSR ATUAIS (do modo em que a instrução executa) na pilha
    /// (`R13`) de um modo alvo. UNPREDICTABLE em modo User/System (sem SPSR/banco privilegiado
    /// próprio) — tratado como UNDEFINED.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeStoreReturnState(ArmCore core, IrOp.StoreReturnState srs) {
        if (!core.cpsr().evalCond(srs.condition())) {
            return false;
        }
        CpuMode current = core.mode();
        if (current == CpuMode.USER || current == CpuMode.SYSTEM) {
            core.setProgramCounter(srs.sequentialPc());
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        CpuMode target = CpuMode.fromBits(srs.targetMode());
        int targetSp = core.bankedRegister(target, 13);
        int address = srs.addressingMode().startAddress(targetSp, 2) & ~3;
        int writebackAddress = srs.addressingMode().writebackAddress(targetSp, 2);
        support.write32Arm7(core, address, core.register(14));
        support.write32Arm7(core, address + 4, core.spsr(current));
        if (srs.writeback()) {
            core.setBankedRegister(target, 13, writebackAddress);
        }
        return false;
    }

    /// `RFE` (ARMv6): carrega PC e CPSR da pilha apontada por `base` (Rn); reusa
    /// {@link ArmCore#setCpsr} para trocar modo/banco antes de alinhar o PC pelo bit T já
    /// restaurado (mesma ordem de {@code IrExecutionSupport#restoreCpsrFromCurrentSpsr} +
    /// {@code alignAndSetPc}). UNPREDICTABLE em modo User/System — tratado como UNDEFINED.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeReturnFromException(ArmCore core, IrOp.ReturnFromException rfe) {
        if (!core.cpsr().evalCond(rfe.condition())) {
            return false;
        }
        CpuMode current = core.mode();
        if (current == CpuMode.USER || current == CpuMode.SYSTEM) {
            core.setProgramCounter(rfe.sequentialPc());
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        int base = core.register(rfe.base());
        int address = rfe.addressingMode().startAddress(base, 2) & ~3;
        int writebackAddress = rfe.addressingMode().writebackAddress(base, 2);
        int newPc = support.read32Arm7(core, address);
        int newCpsr = support.read32Arm7(core, address + 4);
        if (rfe.writeback()) {
            core.setRegister(rfe.base(), writebackAddress);
        }
        core.setCpsr(newCpsr);
        support.alignAndSetPc(core, newPc);
        return true;
    }
}
