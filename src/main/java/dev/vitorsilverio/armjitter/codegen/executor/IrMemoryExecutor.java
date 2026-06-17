package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa loads, stores e swap da IR interpretada.
final class IrMemoryExecutor {
    private final IrExecutionSupport support;

    IrMemoryExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeLoad(ArmCore core, IrOp.Load load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return false;
        }
        int offset = support.operand(core, load.offset());
        int base = load.baseValueOverride() >= 0 ? load.baseValueOverride() : core.register(load.base());
        int address = load.postIndexed() ? base : base + offset;
        int value = switch (load.sizeBytes()) {
            case 1 -> support.read8Arm7(core, address);
            case 2 -> support.read16Arm7(core, address, load.signed());
            case 4 -> support.read32Arm7(core, address);
            default -> throw new UnsupportedOperationException("Unsupported IR load size: " + load.sizeBytes());
        };
        value = support.signExtendIfNeeded(value, load.sizeBytes(), load.signed());
        support.writeLoadedRegister(core, load.dst(), value);
        if (load.writeback() && load.base() != load.dst()) {
            core.setRegister(load.base(), base + offset);
        }
        return load.dst() == 15;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeLoadLiteral(ArmCore core, IrOp.LoadLiteral load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return false;
        }
        support.writeLoadedRegister(core, load.dst(), support.read32Arm7(core, load.address()));
        return load.dst() == 15;
    }

    void executeStore(ArmCore core, IrOp.Store store) {
        if (!core.cpsr().evalCond(store.condition())) {
            return;
        }
        int offset = support.operand(core, store.offset());
        int base = store.baseValueOverride() >= 0 ? store.baseValueOverride() : core.register(store.base());
        int address = store.postIndexed() ? base : base + offset;
        int value = support.registerValue(core, store.src(), store.srcValueOverride());
        switch (store.sizeBytes()) {
            case 1 -> support.write8Arm7(core, address, value);
            case 2 -> support.write16Arm7(core, address, value);
            case 4 -> support.write32Arm7(core, address, value);
            default -> throw new UnsupportedOperationException("Unsupported IR store size: " + store.sizeBytes());
        }
        if (store.writeback()) {
            core.setRegister(store.base(), base + offset);
        }
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeSwap(ArmCore core, IrOp.Swap swap) {
        if (!core.cpsr().evalCond(swap.condition())) {
            return false;
        }
        int address = support.registerValue(core, swap.base(), swap.baseValueOverride());
        int memoryValue = switch (swap.sizeBytes()) {
            case 1 -> support.read8Arm7(core, address);
            case 4 -> support.read32Arm7(core, address);
            default -> throw new UnsupportedOperationException("Unsupported IR swap size: " + swap.sizeBytes());
        };
        int registerValue = support.registerValue(core, swap.src(), swap.srcValueOverride());
        switch (swap.sizeBytes()) {
            case 1 -> support.write8Arm7(core, address, registerValue);
            case 4 -> support.write32Arm7(core, address, registerValue);
            default -> throw new UnsupportedOperationException("Unsupported IR swap size: " + swap.sizeBytes());
        }
        support.writeLoadedRegister(core, swap.dst(), memoryValue);
        return swap.dst() == 15;
    }
}
