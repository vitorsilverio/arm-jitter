package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa LDM/STM, PUSH e POP da IR interpretada.
final class IrTransferExecutor {
    private final IrExecutionSupport support;

    IrTransferExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeMultipleTransfer(ArmCore core, IrOp.MultipleTransfer transfer) {
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

    void executePush(ArmCore core, IrOp.Push push) {
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
    boolean executePop(ArmCore core, IrOp.Pop pop) {
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
}
