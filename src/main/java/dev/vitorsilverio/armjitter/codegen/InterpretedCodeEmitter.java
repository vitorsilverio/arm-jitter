package dev.vitorsilverio.armjitter.codegen;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Emissor que transforma IR em um bloco executavel por interpretacao de IR.
public final class InterpretedCodeEmitter implements CodeEmitter {
    /// Emite um bloco executavel que interpreta as operacoes IR em ordem.
    @Override
    public CompiledBlock emit(IrBlock block) {
        return core -> execute(block, core);
    }

    private int execute(IrBlock block, ArmCore core) {
        int cycles = 0;
        boolean pcChanged = false;

        for (IrOp op : block.operations()) {
            switch (op) {
                case IrOp.Alu alu -> executeAlu(core, alu);
                case IrOp.LoadLiteral load -> executeLoadLiteral(core, load);
                case IrOp.Load load -> executeLoad(core, load);
                case IrOp.Store store -> executeStore(core, store);
                case IrOp.MultipleTransfer transfer -> pcChanged |= executeMultipleTransfer(core, transfer);
                case IrOp.Branch branch -> pcChanged |= executeBranch(core, branch);
                case IrOp.BranchExchange branch -> pcChanged |= executeBranchExchange(core, branch);
                case IrOp.ThumbBlPrefix prefix -> executeThumbBlPrefix(core, prefix);
                case IrOp.ThumbBlSuffix suffix -> pcChanged |= executeThumbBlSuffix(core, suffix);
                case IrOp.Push push -> executePush(core, push);
                case IrOp.Pop pop -> pcChanged |= executePop(core, pop);
                case IrOp.Swi swi -> pcChanged |= executeSwi(core, swi, block.endPc());
                case IrOp.Cycle cycle -> cycles += cycle.count();
            }
        }

        if (!pcChanged) {
            core.setProgramCounter(block.endPc());
        }
        return cycles;
    }

    private void executeAlu(ArmCore core, IrOp.Alu alu) {
        if (!core.cpsr().evalCond(alu.condition())) {
            return;
        }

        int right = operand(core, alu.src2());
        switch (alu.opcode()) {
            case "MOV" -> {
                core.setRegister(alu.dst(), right);
                if (alu.setFlags()) {
                    setLogicFlags(core, right);
                }
            }
            case "ADD" -> {
                int left = core.register(alu.src1());
                int result = left + right;
                core.setRegister(alu.dst(), result);
                if (alu.setFlags()) {
                    setAddFlags(core, left, right, result);
                }
            }
            case "ADC" -> {
                int left = core.register(alu.src1());
                int carry = core.cpsr().carry() ? 1 : 0;
                int result = left + right + carry;
                core.setRegister(alu.dst(), result);
                if (alu.setFlags()) {
                    setAdcFlags(core, left, right, carry, result);
                }
            }
            case "SUB", "CMP", "SBC", "NEG" -> {
                int left = "NEG".equals(alu.opcode()) ? 0 : core.register(alu.src1());
                int borrow = "SBC".equals(alu.opcode()) && !core.cpsr().carry() ? 1 : 0;
                int subtrahend = right + borrow;
                int result = left - subtrahend;
                if (!"CMP".equals(alu.opcode())) {
                    core.setRegister(alu.dst(), result);
                }
                if (alu.setFlags()) {
                    setSbcFlags(core, left, right, borrow, result);
                }
            }
            case "CMN" -> {
                int left = core.register(alu.src1());
                int result = left + right;
                if (alu.setFlags()) {
                    setAddFlags(core, left, right, result);
                }
            }
            case "AND", "EOR", "ORR", "BIC", "TST", "TEQ" -> {
                int left = core.register(alu.src1());
                int result = switch (alu.opcode()) {
                    case "AND" -> left & right;
                    case "EOR" -> left ^ right;
                    case "ORR" -> left | right;
                    case "BIC" -> left & ~right;
                    case "TST" -> left & right;
                    case "TEQ" -> left ^ right;
                    default -> throw new IllegalStateException("Unexpected logic opcode: " + alu.opcode());
                };
                if (!"TST".equals(alu.opcode()) && !"TEQ".equals(alu.opcode())) {
                    core.setRegister(alu.dst(), result);
                }
                if (alu.setFlags()) {
                    setLogicFlags(core, result);
                }
            }
            case "MVN" -> {
                int result = ~right;
                core.setRegister(alu.dst(), result);
                if (alu.setFlags()) {
                    setLogicFlags(core, result);
                }
            }
            case "LSL", "LSR", "ASR", "ROR" -> {
                int value = core.register(alu.src1());
                int amount = right & 0xFF;
                int result = switch (alu.opcode()) {
                    case "LSL" -> amount >= 32 ? 0 : value << amount;
                    case "LSR" -> amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
                    case "ASR" -> amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
                    case "ROR" -> amount == 0 ? value : Integer.rotateRight(value, amount & 31);
                    default -> throw new IllegalStateException("Unexpected shift opcode: " + alu.opcode());
                };
                core.setRegister(alu.dst(), result);
                if (alu.setFlags()) {
                    setLogicFlags(core, result);
                }
            }
            case "MUL" -> {
                int left = core.register(alu.src1());
                int result = left * right;
                core.setRegister(alu.dst(), result);
                if (alu.setFlags()) {
                    setLogicFlags(core, result);
                }
            }
            default -> throw new UnsupportedOperationException("Unknown IR ALU opcode: " + alu.opcode());
        }
    }

    private boolean executeBranch(ArmCore core, IrOp.Branch branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        if (branch.link()) {
            core.setRegister(14, branch.returnAddress());
        }
        core.setProgramCounter(branch.target());
        return true;
    }

    private boolean executeBranchExchange(ArmCore core, IrOp.BranchExchange branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        int target = core.register(branch.sourceRegister());
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
        return true;
    }

    private void executeThumbBlPrefix(ArmCore core, IrOp.ThumbBlPrefix prefix) {
        if (!core.cpsr().evalCond(prefix.condition())) {
            return;
        }
        core.setRegister(14, prefix.address() + 4 + prefix.highOffset());
    }

    private boolean executeThumbBlSuffix(ArmCore core, IrOp.ThumbBlSuffix suffix) {
        if (!core.cpsr().evalCond(suffix.condition())) {
            return false;
        }
        int oldLink = core.register(14);
        core.setRegister(14, (suffix.address() + 2) | 1);
        core.setProgramCounter(oldLink + suffix.lowOffset());
        return true;
    }

    private void executeLoad(ArmCore core, IrOp.Load load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return;
        }
        int address = core.register(load.base()) + operand(core, load.offset());
        int value = switch (load.sizeBytes()) {
            case 1 -> core.memory().read8(address);
            case 2 -> core.memory().read16(address);
            case 4 -> core.memory().read32(address);
            default -> throw new UnsupportedOperationException("Unsupported IR load size: " + load.sizeBytes());
        };
        value = signExtendIfNeeded(value, load.sizeBytes(), load.signed());
        core.setRegister(load.dst(), value);
    }

    private void executeLoadLiteral(ArmCore core, IrOp.LoadLiteral load) {
        if (!core.cpsr().evalCond(load.condition())) {
            return;
        }
        core.setRegister(load.dst(), core.memory().read32(load.address()));
    }

    private void executeStore(ArmCore core, IrOp.Store store) {
        if (!core.cpsr().evalCond(store.condition())) {
            return;
        }
        int address = core.register(store.base()) + operand(core, store.offset());
        int value = core.register(store.src());
        switch (store.sizeBytes()) {
            case 1 -> core.memory().write8(address, value);
            case 2 -> core.memory().write16(address, value);
            case 4 -> core.memory().write32(address, value);
            default -> throw new UnsupportedOperationException("Unsupported IR store size: " + store.sizeBytes());
        }
    }

    private boolean executeMultipleTransfer(ArmCore core, IrOp.MultipleTransfer transfer) {
        if (!core.cpsr().evalCond(transfer.condition())) {
            return false;
        }
        int address = core.register(transfer.base());
        for (int register = 0; register <= 15; register++) {
            if ((transfer.registerMask() & (1 << register)) != 0) {
                if (transfer.load()) {
                    core.setRegister(register, core.memory().read32(address));
                } else {
                    core.memory().write32(address, core.register(register));
                }
                address += 4;
            }
        }
        if (transfer.writeback()) {
            core.setRegister(transfer.base(), address);
        }
        return transfer.load() && (transfer.registerMask() & (1 << 15)) != 0;
    }

    private void executePush(ArmCore core, IrOp.Push push) {
        if (!core.cpsr().evalCond(push.condition())) {
            return;
        }
        int count = Integer.bitCount(push.registerMask()) + (push.includeLr() ? 1 : 0);
        int address = core.register(13) - count * 4;
        int current = address;
        for (int register = 0; register <= 7; register++) {
            if ((push.registerMask() & (1 << register)) != 0) {
                core.memory().write32(current, core.register(register));
                current += 4;
            }
        }
        if (push.includeLr()) {
            core.memory().write32(current, core.register(14));
        }
        core.setRegister(13, address);
    }

    private boolean executePop(ArmCore core, IrOp.Pop pop) {
        if (!core.cpsr().evalCond(pop.condition())) {
            return false;
        }
        int current = core.register(13);
        for (int register = 0; register <= 7; register++) {
            if ((pop.registerMask() & (1 << register)) != 0) {
                core.setRegister(register, core.memory().read32(current));
                current += 4;
            }
        }
        boolean pcChanged = false;
        if (pop.includePc()) {
            int value = core.memory().read32(current);
            current += 4;
            core.cpsr().setThumbMode((value & 1) != 0);
            core.setProgramCounter(value & ~1);
            pcChanged = true;
        }
        core.setRegister(13, current);
        return pcChanged;
    }

    private boolean executeSwi(ArmCore core, IrOp.Swi swi, int sequentialPc) {
        if (!core.cpsr().evalCond(swi.condition())) {
            return false;
        }
        core.setProgramCounter(sequentialPc);
        CpuState next = core.swiDispatcher().dispatch(swi.immediate(), core.toCpuState());
        core.apply(next);
        return true;
    }

    private int operand(ArmCore core, IrOperand operand) {
        return switch (operand) {
            case IrOperand.Immediate immediate -> immediate.value();
            case IrOperand.Register register -> core.register(register.index());
        };
    }

    private void setLogicFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    private void setAddFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(result, left) < 0;
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private void setAdcFlags(ArmCore core, int left, int right, int carryIn, int result) {
        long unsigned = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right) + carryIn;
        long signed = (long) left + (long) right + carryIn;
        boolean carry = (unsigned >>> 32) != 0;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private void setSubFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(left, right) >= 0;
        boolean overflow = ((left ^ right) & (left ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private void setSbcFlags(ArmCore core, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    private int signExtendIfNeeded(int value, int sizeBytes, boolean signed) {
        if (!signed) {
            return value;
        }
        return switch (sizeBytes) {
            case 1 -> (byte) value;
            case 2 -> (short) value;
            default -> value;
        };
    }
}
