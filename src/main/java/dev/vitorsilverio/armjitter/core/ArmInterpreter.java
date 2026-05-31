package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Interpretador frio usado para debug, step-by-step e como oraculo do JIT.
public final class ArmInterpreter {
    private final InstructionDecoder armDecoder;
    private final InstructionDecoder thumbDecoder;

    /// Cria um interpretador com decoders ARM e THUMB padrao.
    public ArmInterpreter() {
        this(new ArmDecoder(), new ThumbDecoder());
    }

    /// Cria um interpretador com decoders customizados.
    public ArmInterpreter(InstructionDecoder armDecoder, InstructionDecoder thumbDecoder) {
        this.armDecoder = armDecoder;
        this.thumbDecoder = thumbDecoder;
    }

    /// Executa exatamente uma instrucao e retorna a instrucao decodificada.
    public DecodedInstruction step(ArmCore core) {
        int pc = core.programCounter();
        DecodedInstruction instruction = decoderFor(core).decode(core.memory(), pc);
        int sequentialPc = pc + (core.cpsr().isThumbMode() ? 2 : 4);

        if (!core.cpsr().evalCond(instruction.condition())) {
            core.setProgramCounter(sequentialPc);
            core.addCycles(1);
            return instruction;
        }

        execute(core, instruction, sequentialPc);
        core.addCycles(1);
        return instruction;
    }

    private InstructionDecoder decoderFor(ArmCore core) {
        return core.cpsr().isThumbMode() ? thumbDecoder : armDecoder;
    }

    private void execute(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        switch (instruction.kind()) {
            case MOV -> executeMove(core, instruction, sequentialPc);
            case ADD -> executeAdd(core, instruction, sequentialPc);
            case ADC -> executeAdc(core, instruction, sequentialPc);
            case SUB, CMP, SBC, NEG -> executeSub(core, instruction, sequentialPc);
            case CMN -> executeCmn(core, instruction, sequentialPc);
            case AND, EOR, ORR, BIC, TST, TEQ -> executeLogic(core, instruction, sequentialPc);
            case MVN -> executeMvn(core, instruction, sequentialPc);
            case LSL, LSR, ASR, ROR -> executeShift(core, instruction, sequentialPc);
            case MUL -> executeMul(core, instruction, sequentialPc);
            case LOAD_LITERAL -> executeLoadLiteral(core, instruction, sequentialPc);
            case LOAD -> executeLoad(core, instruction, sequentialPc);
            case STORE -> executeStore(core, instruction, sequentialPc);
            case LOAD_MULTIPLE -> executeLoadMultiple(core, instruction, sequentialPc);
            case STORE_MULTIPLE -> executeStoreMultiple(core, instruction, sequentialPc);
            case BRANCH -> executeBranch(core, instruction);
            case BRANCH_EXCHANGE -> executeBranchExchange(core, instruction);
            case LONG_BRANCH_PREFIX -> executeLongBranchPrefix(core, instruction, sequentialPc);
            case LONG_BRANCH_SUFFIX -> executeLongBranchSuffix(core, instruction);
            case PUSH -> executePush(core, instruction, sequentialPc);
            case POP -> executePop(core, instruction, sequentialPc);
            case SWI -> executeSwi(core, instruction, sequentialPc);
            case UNIMPLEMENTED -> throw new UnsupportedOperationException(
                    "Instruction not implemented: 0x" + Integer.toHexString(instruction.raw()));
        }
    }

    private void executeMove(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int result = operand2(core, instruction);
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setLogicFlags(core, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeAdd(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = readSourceRegister(core, instruction.sourceRegister(), instruction);
        int right = operand2(core, instruction);
        int result = left + right;
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setAddFlags(core, left, right, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeAdc(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = readSourceRegister(core, instruction.sourceRegister(), instruction);
        int right = operand2(core, instruction);
        int carry = core.cpsr().carry() ? 1 : 0;
        int result = left + right + carry;
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setAdcFlags(core, left, right, carry, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeSub(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = instruction.kind() == InstructionKind.NEG ? 0 : core.register(instruction.sourceRegister());
        int right = operand2(core, instruction);
        int borrow = instruction.kind() == InstructionKind.SBC && !core.cpsr().carry() ? 1 : 0;
        int subtrahend = right + borrow;
        int result = left - subtrahend;
        if (instruction.kind() != InstructionKind.CMP) {
            core.setRegister(instruction.destinationRegister(), result);
        }
        if (instruction.setFlags()) {
            setSbcFlags(core, left, right, borrow, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeCmn(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = core.register(instruction.sourceRegister());
        int right = operand2(core, instruction);
        int result = left + right;
        setAddFlags(core, left, right, result);
        core.setProgramCounter(sequentialPc);
    }

    private void executeLogic(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = core.register(instruction.sourceRegister());
        int right = operand2(core, instruction);
        int result = switch (instruction.kind()) {
            case AND -> left & right;
            case EOR -> left ^ right;
            case ORR -> left | right;
            case BIC -> left & ~right;
            case TST -> left & right;
            case TEQ -> left ^ right;
            default -> throw new IllegalStateException("Unexpected logic op: " + instruction.kind());
        };
        if (instruction.kind() != InstructionKind.TST && instruction.kind() != InstructionKind.TEQ) {
            core.setRegister(instruction.destinationRegister(), result);
        }
        if (instruction.setFlags()) {
            setLogicFlags(core, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeMvn(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int result = ~operand2(core, instruction);
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setLogicFlags(core, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeShift(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int value = core.register(instruction.sourceRegister());
        int amount = instruction.immediateOperand()
                ? instruction.immediate()
                : (core.register(instruction.secondSourceRegister()) & 0xFF);
        int result = switch (instruction.kind()) {
            case LSL -> amount >= 32 ? 0 : value << amount;
            case LSR -> amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
            case ASR -> amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
            case ROR -> amount == 0 ? value : Integer.rotateRight(value, amount & 31);
            default -> throw new IllegalStateException("Unexpected shift op: " + instruction.kind());
        };
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setLogicFlags(core, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeMul(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int result = core.register(instruction.sourceRegister()) * core.register(instruction.secondSourceRegister());
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setLogicFlags(core, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeBranch(ArmCore core, DecodedInstruction instruction) {
        if (instruction.link()) {
            int link = instruction.address() + (core.cpsr().isThumbMode() ? 3 : 4);
            core.setRegister(14, link);
        }
        core.setProgramCounter(instruction.immediate());
    }

    private void executeBranchExchange(ArmCore core, DecodedInstruction instruction) {
        int target = core.register(instruction.sourceRegister());
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
    }

    private void executeLongBranchPrefix(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        core.setRegister(14, instruction.address() + 4 + instruction.immediate());
        core.setProgramCounter(sequentialPc);
    }

    private void executeLongBranchSuffix(ArmCore core, DecodedInstruction instruction) {
        int oldLink = core.register(14);
        int returnAddress = instruction.address() + 2;
        core.setRegister(14, returnAddress | 1);
        core.setProgramCounter(oldLink + instruction.immediate());
    }

    private void executeLoad(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int offset = instruction.immediateOperand() ? instruction.immediate() : core.register(instruction.secondSourceRegister());
        int address = core.register(instruction.sourceRegister()) + offset;
        int value = switch (instruction.accessSizeBytes()) {
            case 1 -> core.memory().read8(address);
            case 2 -> core.memory().read16(address);
            case 4 -> core.memory().read32(address);
            default -> throw new UnsupportedOperationException("Unsupported load size: " + instruction.accessSizeBytes());
        };
        value = signExtendIfNeeded(value, instruction.accessSizeBytes(), instruction.signedAccess());
        core.setRegister(instruction.destinationRegister(), value);
        core.setProgramCounter(sequentialPc);
    }

    private void executeLoadLiteral(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        core.setRegister(instruction.destinationRegister(), core.memory().read32(instruction.immediate()));
        core.setProgramCounter(sequentialPc);
    }

    private void executeStore(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int offset = instruction.immediateOperand() ? instruction.immediate() : core.register(instruction.secondSourceRegister());
        int address = core.register(instruction.sourceRegister()) + offset;
        int value = core.register(instruction.destinationRegister());
        switch (instruction.accessSizeBytes()) {
            case 1 -> core.memory().write8(address, value);
            case 2 -> core.memory().write16(address, value);
            case 4 -> core.memory().write32(address, value);
            default -> throw new UnsupportedOperationException("Unsupported store size: " + instruction.accessSizeBytes());
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeLoadMultiple(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int address = core.register(instruction.sourceRegister());
        for (int register = 0; register <= 15; register++) {
            if ((instruction.immediate() & (1 << register)) != 0) {
                int value = core.memory().read32(address);
                core.setRegister(register, value);
                address += 4;
            }
        }
        if (instruction.writeback()) {
            core.setRegister(instruction.sourceRegister(), address);
        }
        if ((instruction.immediate() & (1 << 15)) == 0) {
            core.setProgramCounter(sequentialPc);
        }
    }

    private void executeStoreMultiple(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int address = core.register(instruction.sourceRegister());
        for (int register = 0; register <= 15; register++) {
            if ((instruction.immediate() & (1 << register)) != 0) {
                core.memory().write32(address, core.register(register));
                address += 4;
            }
        }
        if (instruction.writeback()) {
            core.setRegister(instruction.sourceRegister(), address);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executePush(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int count = Integer.bitCount(instruction.immediate()) + (instruction.link() ? 1 : 0);
        int address = core.register(13) - count * 4;
        int current = address;
        for (int register = 0; register <= 7; register++) {
            if ((instruction.immediate() & (1 << register)) != 0) {
                core.memory().write32(current, core.register(register));
                current += 4;
            }
        }
        if (instruction.link()) {
            core.memory().write32(current, core.register(14));
        }
        core.setRegister(13, address);
        core.setProgramCounter(sequentialPc);
    }

    private void executePop(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int current = core.register(13);
        for (int register = 0; register <= 7; register++) {
            if ((instruction.immediate() & (1 << register)) != 0) {
                core.setRegister(register, core.memory().read32(current));
                current += 4;
            }
        }
        if (instruction.link()) {
            int value = core.memory().read32(current);
            current += 4;
            core.cpsr().setThumbMode((value & 1) != 0);
            core.setProgramCounter(value & ~1);
        } else {
            core.setProgramCounter(sequentialPc);
        }
        core.setRegister(13, current);
    }

    private void executeSwi(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        CpuState next = core.swiDispatcher().dispatch(instruction.immediate(), core.toCpuState());
        core.apply(next);
    }

    private int operand2(ArmCore core, DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return instruction.immediate();
        }
        return core.register(instruction.secondSourceRegister());
    }

    private int readSourceRegister(ArmCore core, int register, DecodedInstruction instruction) {
        if (register == 15 && instruction.instructionSet() == dev.vitorsilverio.armjitter.decoder.InstructionSet.THUMB) {
            return (instruction.address() + 4) & ~3;
        }
        return core.register(register);
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
