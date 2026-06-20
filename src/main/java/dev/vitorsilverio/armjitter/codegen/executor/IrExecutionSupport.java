package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Utilitários compartilhados pelos executores de IR interpretada.
final class IrExecutionSupport {
    private final ArmArchitecture architecture;

    IrExecutionSupport(ArmArchitecture architecture) {
        this.architecture = architecture;
    }

    ArmArchitecture architecture() {
        return architecture;
    }

    int operand(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.value();
            case IrOperand.Register register -> registerValue(core, register.index(), register.valueOverride());
            case IrOperand.ShiftedRegister register -> {
                int value = shiftedRegisterOperand(core, register);
                yield register.negated() ? -value : value;
            }
        };
    }

    int registerValue(ArmCore core, int register, int valueOverride) {
        // -1 is the "no override" sentinel; a real PC override (address+8/12) is always word/half
        // aligned and so can never be -1. Comparing against -1 (not >= 0) is essential for high
        // addresses such as the ARM9 BIOS at 0xFFFF0000+, whose overrides are negative as ints.
        return valueOverride != -1 ? valueOverride : core.register(register);
    }

    boolean operandCarryOut(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.carryOutKnown()
                    ? immediate.carryOut()
                    : core.cpsr().carry();
            case IrOperand.Register ignored -> core.cpsr().carry();
            case IrOperand.ShiftedRegister register -> shiftedRegisterCarryOut(core, register);
        };
    }

    void writeLoadedRegister(ArmCore core, int register, int value) {
        if (register == 15) {
            loadToPc(core, value);
            return;
        }
        core.setRegister(register, value);
    }

    void loadToPc(ArmCore core, int value) {
        if (architecture.has(ArmFeature.LOAD_PC_INTERWORKING)) {
            core.cpsr().setThumbMode((value & 1) != 0);
            core.setProgramCounter(value & ~1);
        } else {
            alignAndSetPc(core, value);
        }
    }

    boolean writeAluDestination(ArmCore core, int register, int value, boolean restoreSpsr) {
        if (register != 15) {
            core.setRegister(register, value);
            return false;
        }
        if (restoreSpsr) {
            restoreCpsrFromCurrentSpsr(core);
        }
        alignAndSetPc(core, value);
        return true;
    }

    void alignAndSetPc(ArmCore core, int value) {
        int mask = core.cpsr().isThumbMode() ? ~1 : ~3;
        core.setProgramCounter(value & mask);
    }

    void restoreCpsrFromCurrentSpsr(ArmCore core) {
        CpuMode mode = core.mode();
        if (mode == CpuMode.USER || mode == CpuMode.SYSTEM) {
            return;
        }
        core.setCpsr(core.spsr(mode));
    }

    int read32Arm7(ArmCore core, int address) {
        int aligned = address & ~3;
        int value = core.memory().read32(aligned);
        core.addMemoryCycles(aligned, 4, MemoryAccessType.DATA_READ);
        return Integer.rotateRight(value, (address & 3) * 8);
    }

    int read8Arm7(ArmCore core, int address) {
        int value = core.memory().read8(address);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
        return value;
    }

    int read16Arm7(ArmCore core, int address, boolean signed) {
        if (signed && (address & 1) != 0) {
            int value = core.memory().read8(address);
            core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
            return (byte) value;
        }
        int aligned = address & ~1;
        int value = core.memory().read16(aligned);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_READ);
        return (address & 1) == 0 ? value : Integer.rotateRight(value, 8);
    }

    void write16Arm7(ArmCore core, int address, int value) {
        core.memory().write16(address, value);
        core.addMemoryCycles(address, 2, MemoryAccessType.DATA_WRITE);
    }

    void write8Arm7(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_WRITE);
    }

    void write32Arm7(ArmCore core, int address, int value) {
        core.memory().write32(address, value);
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
    }

    int effectiveRegisterMask(int mask, boolean emptyRegisterList) {
        if (!emptyRegisterList) {
            return mask;
        }
        // ARM7TDMI transfers R15 for an empty list; ARMv5 transfers nothing (just the ±0x40 base
        // writeback, which still uses the count of 16).
        return architecture.has(ArmFeature.EMPTY_RLIST_NO_TRANSFER) ? 0 : (1 << 15);
    }

    int effectiveRegisterCount(int mask, boolean emptyRegisterList) {
        return emptyRegisterList ? 16 : Integer.bitCount(mask);
    }

    boolean shouldWriteBackMultiple(boolean writeback, boolean load, int mask, int baseRegister) {
        if (!writeback) {
            return false;
        }
        if (load && (mask & (1 << baseRegister)) != 0) {
            if (!architecture.has(ArmFeature.LDM_WRITEBACK_BASE_IN_LIST)) {
                return false; // ARMv4: a base in the list always keeps the loaded value
            }
            // ARMv5: writeback still happens unless the base is the highest register of a
            // multi-register transfer, in which case the loaded value wins.
            boolean baseIsHighest = (mask >>> baseRegister) == 1;
            boolean multiple = Integer.bitCount(mask) > 1;
            return !(baseIsHighest && multiple);
        }
        return true;
    }

    int multipleStoreRegisterValue(
            ArmCore core,
            IrOp.MultipleTransfer transfer,
            int register,
            int firstRegister,
            int writebackAddress) {
        if (transfer.userMode()) {
            return core.bankedRegister(CpuMode.USER, register);
        }
        // ARM7TDMI quirk: STM of the base register, when it is not the first in the list, stores
        // the already-incremented (writeback) value. ARMv5 always stores the original base.
        if (!architecture.has(ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL)
                && transfer.writeback()
                && register == transfer.base()
                && register != firstRegister) {
            return writebackAddress;
        }
        return registerValue(core, register, register == 15 ? transfer.pcStoreValueOverride() : -1);
    }

    int mergePsr(int current, int value, int fieldMask) {
        int mask = 0;
        if ((fieldMask & 0x1) != 0) {
            mask |= 0x0000_00FF;
        }
        if ((fieldMask & 0x2) != 0) {
            mask |= 0x0000_FF00;
        }
        if ((fieldMask & 0x4) != 0) {
            mask |= 0x00FF_0000;
        }
        if ((fieldMask & 0x8) != 0) {
            mask |= 0xFF00_0000;
        }
        return (current & ~mask) | (value & mask);
    }

    int cpsrWriteFieldMask(ArmCore core, int fieldMask) {
        return core.mode() == CpuMode.USER ? fieldMask & 0x8 : fieldMask;
    }

    int signExtendIfNeeded(int value, int sizeBytes, boolean signed) {
        if (!signed) {
            return value;
        }
        return switch (sizeBytes) {
            case 1 -> (byte) value;
            case 2 -> (short) value;
            default -> value;
        };
    }

    void setLogicFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    void setLogicFlags(ArmCore core, int result, boolean carry) {
        core.cpsr().setNzcv(result < 0, result == 0, carry, core.cpsr().overflow());
    }

    void setAddFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(result, left) < 0;
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    void setAdcFlags(ArmCore core, int left, int right, int carryIn, int result) {
        long unsigned = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right) + carryIn;
        long signed = (long) left + (long) right + carryIn;
        boolean carry = (unsigned >>> 32) != 0;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    void setSbcFlags(ArmCore core, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    boolean shiftCarryOut(ArmCore core, int value, IrOpCode opcode, int amount, boolean immediateShift) {
        if (!immediateShift && amount == 0) {
            return core.cpsr().carry();
        }
        return switch (opcode) {
            case LSL -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.LSL, amount);
            case LSR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.LSR, amount);
            case ASR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.ASR, amount);
            case ROR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.ROR, amount);
            default -> throw new IllegalStateException("Unexpected shift opcode: " + opcode);
        };
    }

    private int shiftedRegisterOperand(ArmCore core, IrOperand.ShiftedRegister register) {
        int value = registerValue(core, register.index(), register.valueOverride());
        if (register.rrx()) {
            return (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
        }
        int amount = register.amountRegister() >= 0
                ? registerValue(core, register.amountRegister(), register.amountValueOverride()) & 0xFF
                : register.amount();
        if (register.amountRegister() >= 0 && amount == 0) {
            return value;
        }
        return applyShift(value, register.shiftType(), amount);
    }

    private boolean shiftedRegisterCarryOut(ArmCore core, IrOperand.ShiftedRegister register) {
        int value = registerValue(core, register.index(), register.valueOverride());
        if (register.rrx()) {
            return (value & 1) != 0;
        }
        int amount = register.amountRegister() >= 0
                ? registerValue(core, register.amountRegister(), register.amountValueOverride()) & 0xFF
                : register.amount();
        if (amount == 0) {
            return core.cpsr().carry();
        }
        return shiftCarryOut(value, register.shiftType(), amount);
    }

    private int applyShift(int value, ShiftType shiftType, int amount) {
        return switch (shiftType) {
            case LSL -> amount >= 32 ? 0 : value << amount;
            case LSR -> amount >= 32 ? 0 : value >>> amount;
            case ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            case ROR -> Integer.rotateRight(value, amount & 31);
        };
    }

    private boolean shiftCarryOut(int value, ShiftType shiftType, int amount) {
        if (amount <= 0) {
            return false;
        }
        return switch (shiftType) {
            case LSL -> {
                if (amount < 32) {
                    yield ((value >>> (32 - amount)) & 1) != 0;
                }
                yield amount == 32 && (value & 1) != 0;
            }
            case LSR -> {
                if (amount < 32) {
                    yield ((value >>> (amount - 1)) & 1) != 0;
                }
                yield amount == 32 && value < 0;
            }
            case ASR -> amount >= 32 ? value < 0 : ((value >>> (amount - 1)) & 1) != 0;
            case ROR -> {
                int effective = amount & 31;
                yield effective == 0 ? value < 0 : ((value >>> (effective - 1)) & 1) != 0;
            }
        };
    }
}
