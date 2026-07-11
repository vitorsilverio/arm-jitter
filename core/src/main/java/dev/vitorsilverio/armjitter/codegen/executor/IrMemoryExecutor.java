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
        int base = load.baseValueOverride() != -1 ? load.baseValueOverride() : core.register(load.base());
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
        int base = store.baseValueOverride() != -1 ? store.baseValueOverride() : core.register(store.base());
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

    /// `LDREX{,B,H,D}`: lê a memória e marca o monitor de exclusividade do core com o
    /// endereço (sem sinal, em `long` — §5 da RFC IR-64) e o tamanho do acesso.
    void executeLoadExclusive(ArmCore core, IrOp.LoadExclusive load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return;
        }
        int address = core.register(load.base());
        core.markExclusive(Integer.toUnsignedLong(address), load.sizeBytes());
        switch (load.sizeBytes()) {
            case 1 -> core.setRegister(load.dst(), support.read8Arm7(core, address));
            case 2 -> core.setRegister(load.dst(), support.read16Arm7(core, address, false));
            case 4 -> core.setRegister(load.dst(), support.read32Arm7(core, address));
            case 8 -> {
                core.setRegister(load.dst(), support.read32Arm7(core, address));
                core.setRegister(load.dst() + 1, support.read32Arm7(core, address + 4));
            }
            default -> throw new UnsupportedOperationException(
                    "Unsupported exclusive load size: " + load.sizeBytes());
        }
    }

    /// `STREX{,B,H,D}`: consulta o monitor ANTES de qualquer escrita — um STREX que falha não
    /// pode ter efeito colateral de memória. Sucesso escreve, devolve 0 em `dst` e consome o
    /// monitor; falha devolve 1 com a memória intacta.
    void executeStoreExclusive(ArmCore core, IrOp.StoreExclusive store) {
        if (!core.cpsr().evalCond(store.condition())) {
            return;
        }
        int address = core.register(store.base());
        if (!core.exclusiveMonitorCovers(Integer.toUnsignedLong(address), store.sizeBytes())) {
            core.setRegister(store.dst(), 1);
            return;
        }
        int value = core.register(store.src());
        switch (store.sizeBytes()) {
            case 1 -> support.write8Arm7(core, address, value);
            case 2 -> support.write16Arm7(core, address, value);
            case 4 -> support.write32Arm7(core, address, value);
            case 8 -> {
                support.write32Arm7(core, address, value);
                support.write32Arm7(core, address + 4, core.register(store.src() + 1));
            }
            default -> throw new UnsupportedOperationException(
                    "Unsupported exclusive store size: " + store.sizeBytes());
        }
        core.setRegister(store.dst(), 0);
        core.clearExclusiveMonitor();
    }

    /// `CLREX`: abre o monitor de exclusividade.
    void executeClearExclusive(ArmCore core, IrOp.ClearExclusive clear) {
        if (!core.cpsr().evalCond(clear.condition())) {
            return;
        }
        core.clearExclusiveMonitor();
    }

    /// LDRD/STRD: two consecutive 32-bit accesses to `first` and `first+1` sharing one address.
    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeDoubleTransfer(ArmCore core, IrOp.DoubleTransfer dt) {
        if (!core.cpsr().evalCond(dt.condition())) {
            return false;
        }
        int offset = support.operand(core, dt.offset());
        int base = dt.baseValueOverride() != -1 ? dt.baseValueOverride() : core.register(dt.base());
        int address = dt.postIndexed() ? base : base + offset;
        int second = dt.first() + 1;
        if (dt.load()) {
            int low = support.read32Arm7(core, address);
            int high = support.read32Arm7(core, address + 4);
            support.writeLoadedRegister(core, dt.first(), low);
            support.writeLoadedRegister(core, second, high);
        } else {
            support.write32Arm7(core, address, core.register(dt.first()));
            support.write32Arm7(core, address + 4, core.register(second));
        }
        // Writeback (skip when a load would clobber the base it still needs — UNPREDICTABLE).
        if (dt.writeback() && (!dt.load() || (dt.base() != dt.first() && dt.base() != second))) {
            core.setRegister(dt.base(), base + offset);
        }
        return dt.load() && (dt.first() == 15 || second == 15);
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
