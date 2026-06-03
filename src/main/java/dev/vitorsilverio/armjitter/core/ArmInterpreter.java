package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.decoder.ThumbDecoder;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
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
        core.addMemoryCycles(pc, core.cpsr().isThumbMode() ? 2 : 4, MemoryAccessType.INSTRUCTION_FETCH);
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
            case SUB, RSB, CMP, SBC, RSC, NEG -> executeSub(core, instruction, sequentialPc);
            case CMN -> executeCmn(core, instruction, sequentialPc);
            case AND, EOR, ORR, BIC, TST, TEQ -> executeLogic(core, instruction, sequentialPc);
            case MVN -> executeMvn(core, instruction, sequentialPc);
            case MRS -> executeMrs(core, instruction, sequentialPc);
            case MSR -> executeMsr(core, instruction, sequentialPc);
            case CLZ -> executeClz(core, instruction, sequentialPc);
            case LSL, LSR, ASR, ROR -> executeShift(core, instruction, sequentialPc);
            case MUL, MLA -> executeMul(core, instruction, sequentialPc);
            case UMULL, UMLAL, SMULL, SMLAL -> executeLongMul(core, instruction, sequentialPc);
            case LOAD_LITERAL -> executeLoadLiteral(core, instruction, sequentialPc);
            case LOAD -> executeLoad(core, instruction, sequentialPc);
            case STORE -> executeStore(core, instruction, sequentialPc);
            case SWAP -> executeSwap(core, instruction, sequentialPc);
            case LOAD_MULTIPLE -> executeLoadMultiple(core, instruction, sequentialPc);
            case STORE_MULTIPLE -> executeStoreMultiple(core, instruction, sequentialPc);
            case BRANCH -> executeBranch(core, instruction);
            case BRANCH_EXCHANGE -> executeBranchExchange(core, instruction);
            case LONG_BRANCH_PREFIX -> executeLongBranchPrefix(core, instruction, sequentialPc);
            case LONG_BRANCH_SUFFIX -> executeLongBranchSuffix(core, instruction);
            case PUSH -> executePush(core, instruction, sequentialPc);
            case POP -> executePop(core, instruction, sequentialPc);
            case SWI -> executeSwi(core, instruction, sequentialPc);
            case UNIMPLEMENTED -> executeUndefined(core, sequentialPc);
        }
    }

    private void executeMove(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int result = operand2(core, instruction);
        if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
            return;
        }
        if (instruction.setFlags()) {
            setLogicFlags(core, result, operandCarryOut(core, instruction));
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeAdd(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = readSourceRegister(core, instruction.sourceRegister(), instruction);
        int right = operand2(core, instruction);
        int result = left + right;
        if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
            return;
        }
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
        if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
            return;
        }
        if (instruction.setFlags()) {
            setAdcFlags(core, left, right, carry, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeSub(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int source = instruction.kind() == InstructionKind.NEG
                ? 0
                : readSourceRegister(core, instruction.sourceRegister(), instruction);
        int operand = operand2(core, instruction);
        boolean reverse = instruction.kind() == InstructionKind.RSB || instruction.kind() == InstructionKind.RSC;
        int left = reverse ? operand : source;
        int right = reverse ? source : operand;
        int borrow = (instruction.kind() == InstructionKind.SBC || instruction.kind() == InstructionKind.RSC)
                && !core.cpsr().carry() ? 1 : 0;
        int subtrahend = right + borrow;
        int result = left - subtrahend;
        if (instruction.kind() != InstructionKind.CMP) {
            if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
                return;
            }
        }
        if (instruction.setFlags()) {
            setSbcFlags(core, left, right, borrow, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeCmn(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = readSourceRegister(core, instruction.sourceRegister(), instruction);
        int right = operand2(core, instruction);
        int result = left + right;
        setAddFlags(core, left, right, result);
        core.setProgramCounter(sequentialPc);
    }

    private void executeLogic(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int left = readSourceRegister(core, instruction.sourceRegister(), instruction);
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
            if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
                return;
            }
        }
        if (instruction.setFlags()) {
            setLogicFlags(core, result, operandCarryOut(core, instruction));
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeMvn(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int result = ~operand2(core, instruction);
        if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
            return;
        }
        if (instruction.setFlags()) {
            setLogicFlags(core, result, operandCarryOut(core, instruction));
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeClz(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int value = readSourceRegister(core, instruction.sourceRegister(), instruction);
        core.setRegister(instruction.destinationRegister(), Integer.numberOfLeadingZeros(value));
        core.setProgramCounter(sequentialPc);
    }

    private void executeMrs(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int value = instruction.immediate() == 0 ? core.cpsr().get() : core.spsr(core.mode());
        core.setRegister(instruction.destinationRegister(), value);
        core.setProgramCounter(sequentialPc);
    }

    private void executeMsr(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int control = instruction.immediateOperand() ? instruction.destinationRegister() : instruction.immediate();
        boolean spsr = (control & 0x10) != 0;
        int fieldMask = control & 0xF;
        int value = instruction.immediateOperand()
                ? instruction.immediate()
                : readSourceRegister(core, instruction.sourceRegister(), instruction);
        if (spsr) {
            core.setSpsr(core.mode(), mergePsr(core.spsr(core.mode()), value, fieldMask));
        } else {
            core.setCpsr(mergePsr(core.cpsr().get(), value, cpsrWriteFieldMask(core, fieldMask)));
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeShift(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int value = readSourceRegister(core, instruction.sourceRegister(), instruction);
        int amount = instruction.immediateOperand()
                ? instruction.immediate()
                : (readSourceRegister(core, instruction.secondSourceRegister(), instruction) & 0xFF);
        int normalizedAmount = normalizedInstructionShiftAmount(instruction.kind(), amount, instruction);
        int result = switch (instruction.kind()) {
            case LSL -> normalizedAmount >= 32 ? 0 : value << normalizedAmount;
            case LSR -> normalizedAmount == 0 ? value : (normalizedAmount >= 32 ? 0 : value >>> normalizedAmount);
            case ASR -> normalizedAmount == 0 ? value : (normalizedAmount >= 32 ? (value < 0 ? -1 : 0) : value >> normalizedAmount);
            case ROR -> normalizedAmount == 0 ? value : Integer.rotateRight(value, normalizedAmount & 31);
            default -> throw new IllegalStateException("Unexpected shift op: " + instruction.kind());
        };
        if (writeAluDestination(core, instruction.destinationRegister(), result, instruction.setFlags())) {
            return;
        }
        if (instruction.setFlags()) {
            setLogicFlags(core, result, shiftCarryOut(core, value, instruction.kind(), normalizedAmount,
                    instruction.immediateOperand()));
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeMul(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int result = readSourceRegister(core, instruction.sourceRegister(), instruction)
                * readSourceRegister(core, instruction.secondSourceRegister(), instruction);
        if (instruction.kind() == InstructionKind.MLA) {
            result += readSourceRegister(core, instruction.immediate(), instruction);
        }
        core.setRegister(instruction.destinationRegister(), result);
        if (instruction.setFlags()) {
            setLogicFlags(core, result);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeLongMul(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        boolean signed = instruction.kind() == InstructionKind.SMULL || instruction.kind() == InstructionKind.SMLAL;
        boolean accumulate = instruction.kind() == InstructionKind.UMLAL || instruction.kind() == InstructionKind.SMLAL;
        long result;
        if (signed) {
            result = (long) readSourceRegister(core, instruction.sourceRegister(), instruction)
                    * (long) readSourceRegister(core, instruction.secondSourceRegister(), instruction);
        } else {
            result = Integer.toUnsignedLong(readSourceRegister(core, instruction.sourceRegister(), instruction))
                    * Integer.toUnsignedLong(readSourceRegister(core, instruction.secondSourceRegister(), instruction));
        }
        if (accumulate) {
            long current = (Integer.toUnsignedLong(readSourceRegister(core, instruction.immediate(), instruction)) << 32)
                    | Integer.toUnsignedLong(readSourceRegister(core, instruction.destinationRegister(), instruction));
            result += current;
        }
        core.setRegister(instruction.destinationRegister(), (int) result);
        core.setRegister(instruction.immediate(), (int) (result >>> 32));
        if (instruction.setFlags()) {
            core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
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
        int target = readSourceRegister(core, instruction.sourceRegister(), instruction);
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
        int offset = memoryOffset(core, instruction);
        int base = readMemoryBaseRegister(core, instruction);
        int address = instruction.postIndexed() ? base : base + offset;
        int value = switch (instruction.accessSizeBytes()) {
            case 1 -> read8Arm7(core, address);
            case 2 -> read16Arm7(core, address, instruction.signedAccess());
            case 4 -> read32Arm7(core, address);
            default -> throw new UnsupportedOperationException("Unsupported load size: " + instruction.accessSizeBytes());
        };
        value = signExtendIfNeeded(value, instruction.accessSizeBytes(), instruction.signedAccess());
        writeLoadedRegister(core, instruction.destinationRegister(), value);
        if (instruction.writeback()) {
            core.setRegister(instruction.sourceRegister(), base + offset);
        }
        if (instruction.destinationRegister() != 15) {
            core.setProgramCounter(sequentialPc);
        }
    }

    private void executeLoadLiteral(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        writeLoadedRegister(core, instruction.destinationRegister(), read32Arm7(core, instruction.immediate()));
        if (instruction.destinationRegister() != 15) {
            core.setProgramCounter(sequentialPc);
        }
    }

    private void executeStore(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int offset = memoryOffset(core, instruction);
        int base = readMemoryBaseRegister(core, instruction);
        int address = instruction.postIndexed() ? base : base + offset;
        int value = readMemoryStoreRegister(core, instruction.destinationRegister(), instruction);
        switch (instruction.accessSizeBytes()) {
            case 1 -> write8Arm7(core, address, value);
            case 2 -> write16Arm7(core, address, value);
            case 4 -> write32Arm7(core, address, value);
            default -> throw new UnsupportedOperationException("Unsupported store size: " + instruction.accessSizeBytes());
        }
        if (instruction.writeback()) {
            core.setRegister(instruction.sourceRegister(), base + offset);
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executeSwap(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int address = readMemoryBaseRegister(core, instruction);
        int memoryValue = switch (instruction.accessSizeBytes()) {
            case 1 -> read8Arm7(core, address);
            case 4 -> read32Arm7(core, address);
            default -> throw new UnsupportedOperationException("Unsupported swap size: " + instruction.accessSizeBytes());
        };
        int registerValue = readMemoryStoreRegister(core, instruction.secondSourceRegister(), instruction);
        switch (instruction.accessSizeBytes()) {
            case 1 -> write8Arm7(core, address, registerValue);
            case 4 -> write32Arm7(core, address, registerValue);
            default -> throw new UnsupportedOperationException("Unsupported swap size: " + instruction.accessSizeBytes());
        }
        writeLoadedRegister(core, instruction.destinationRegister(), memoryValue);
        if (instruction.destinationRegister() != 15) {
            core.setProgramCounter(sequentialPc);
        }
    }

    private void executeLoadMultiple(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int mask = effectiveRegisterMask(instruction.immediate(), instruction.emptyRegisterList());
        int count = effectiveRegisterCount(instruction.immediate(), instruction.emptyRegisterList());
        int base = core.register(instruction.sourceRegister());
        int address = instruction.blockTransferMode().startAddress(base, count);
        boolean includesPc = (mask & (1 << 15)) != 0;
        boolean forceUser = instruction.link() && !includesPc;
        int loadedPc = 0;
        for (int register = 0; register <= 15; register++) {
            if ((mask & (1 << register)) != 0) {
                int value = read32Arm7(core, address);
                if (instruction.link() && includesPc && register == 15) {
                    loadedPc = value;
                } else if (forceUser) {
                    core.setBankedRegister(CpuMode.USER, register, value);
                } else {
                    writeLoadedRegister(core, register, value);
                }
                address += 4;
            }
        }
        if (shouldWriteBackMultiple(instruction.writeback(), true, mask, instruction.sourceRegister())) {
            core.setRegister(instruction.sourceRegister(),
                    instruction.blockTransferMode().writebackAddress(base, count));
        }
        if (instruction.link() && includesPc) {
            restoreCpsrFromCurrentSpsr(core);
            alignAndSetPc(core, loadedPc);
        }
        if (!includesPc) {
            core.setProgramCounter(sequentialPc);
        }
    }

    private void executeStoreMultiple(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int mask = effectiveRegisterMask(instruction.immediate(), instruction.emptyRegisterList());
        int count = effectiveRegisterCount(instruction.immediate(), instruction.emptyRegisterList());
        int base = core.register(instruction.sourceRegister());
        int address = instruction.blockTransferMode().startAddress(base, count);
        boolean forceUser = instruction.link();
        int writebackAddress = instruction.blockTransferMode().writebackAddress(base, count);
        int firstRegister = Integer.numberOfTrailingZeros(mask);
        for (int register = 0; register <= 15; register++) {
            if ((mask & (1 << register)) != 0) {
                int value = multipleStoreRegisterValue(
                        core,
                        instruction,
                        register,
                        forceUser,
                        firstRegister,
                        writebackAddress);
                address += 4;
                write32Arm7(core, address - 4, value);
            }
        }
        if (instruction.writeback()) {
            core.setRegister(instruction.sourceRegister(),
                    instruction.blockTransferMode().writebackAddress(base, count));
        }
        core.setProgramCounter(sequentialPc);
    }

    private void executePush(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int count = Integer.bitCount(instruction.immediate()) + (instruction.link() ? 1 : 0);
        int address = core.register(13) - count * 4;
        int current = address;
        for (int register = 0; register <= 7; register++) {
            if ((instruction.immediate() & (1 << register)) != 0) {
                write32Arm7(core, current, core.register(register));
                current += 4;
            }
        }
        if (instruction.link()) {
            write32Arm7(core, current, core.register(14));
        }
        core.setRegister(13, address);
        core.setProgramCounter(sequentialPc);
    }

    private void executePop(ArmCore core, DecodedInstruction instruction, int sequentialPc) {
        int current = core.register(13);
        for (int register = 0; register <= 7; register++) {
            if ((instruction.immediate() & (1 << register)) != 0) {
                core.setRegister(register, read32Arm7(core, current));
                current += 4;
            }
        }
        if (instruction.link()) {
            int value = read32Arm7(core, current);
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
        if (core.swiDispatcher().canDispatch(instruction.immediate())) {
            CpuState next = core.swiDispatcher().dispatch(instruction.immediate(), core.toCpuState());
            core.apply(next);
            return;
        }
        core.requestException(ArmException.SWI);
    }

    private void executeUndefined(ArmCore core, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        core.requestException(ArmException.UNDEFINED);
    }

    private int operand2(ArmCore core, DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return instruction.immediate();
        }
        if (instruction.instructionSet() == InstructionSet.ARM) {
            return armShiftedRegisterOperand(core, instruction);
        }
        return readSourceRegister(core, instruction.secondSourceRegister(), instruction);
    }

    private int memoryOffset(ArmCore core, DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return instruction.immediate();
        }
        int value = core.register(instruction.secondSourceRegister());
        if (isArmSingleDataTransfer(instruction.raw())) {
            int amount = (instruction.raw() >>> 7) & 0x1F;
            int shiftBits = (instruction.raw() >>> 5) & 0x3;
            if (amount == 0 && shiftBits == 3) {
                value = (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
            } else {
                value = applyShift(value, shiftType(shiftBits), normalizedShiftAmount(shiftBits, amount));
            }
        }
        return instruction.immediate() < 0 ? -value : value;
    }

    private boolean isArmSingleDataTransfer(int raw) {
        return (raw & 0x0C00_0000) == 0x0400_0000;
    }

    private int armShiftedRegisterOperand(ArmCore core, DecodedInstruction instruction) {
        int value = readSourceRegister(core, instruction.secondSourceRegister(), instruction);
        boolean registerShift = (instruction.raw() & (1 << 4)) != 0;
        if (registerShift) {
            int shiftRegister = (instruction.raw() >>> 8) & 0xF;
            int amount = core.register(shiftRegister) & 0xFF;
            if (amount == 0) {
                return value;
            }
            int shiftBits = (instruction.raw() >>> 5) & 0x3;
            return applyRegisterShift(value, shiftType(shiftBits), amount);
        }
        int amount = (instruction.raw() >>> 7) & 0x1F;
        int shiftBits = (instruction.raw() >>> 5) & 0x3;
        if (amount == 0 && shiftBits == 0) {
            return value;
        }
        if (amount == 0 && shiftBits == 3) {
            return (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
        }
        return applyShift(value, shiftType(shiftBits), normalizedShiftAmount(shiftBits, amount));
    }

    private boolean operandCarryOut(ArmCore core, DecodedInstruction instruction) {
        if (instruction.instructionSet() != InstructionSet.ARM) {
            return core.cpsr().carry();
        }
        if (instruction.immediateOperand()) {
            int rotate = ((instruction.raw() >>> 8) & 0xF) * 2;
            return rotate == 0 ? core.cpsr().carry() : instruction.immediate() < 0;
        }
        int value = readSourceRegister(core, instruction.secondSourceRegister(), instruction);
        boolean registerShift = (instruction.raw() & (1 << 4)) != 0;
        int shiftBits = (instruction.raw() >>> 5) & 0x3;
        if (registerShift) {
            int amount = core.register((instruction.raw() >>> 8) & 0xF) & 0xFF;
            return amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, shiftType(shiftBits), amount);
        }
        int amount = (instruction.raw() >>> 7) & 0x1F;
        if (amount == 0 && shiftBits == 0) {
            return core.cpsr().carry();
        }
        if (amount == 0 && shiftBits == 3) {
            return (value & 1) != 0;
        }
        return shiftCarryOut(value, shiftType(shiftBits), normalizedShiftAmount(shiftBits, amount));
    }

    private int normalizedInstructionShiftAmount(
            InstructionKind kind,
            int amount,
            DecodedInstruction instruction) {
        if (instruction.instructionSet() == InstructionSet.THUMB
                && instruction.immediateOperand()
                && amount == 0
                && (kind == InstructionKind.LSR || kind == InstructionKind.ASR)) {
            return 32;
        }
        return amount;
    }

    private boolean shiftCarryOut(
            ArmCore core,
            int value,
            InstructionKind kind,
            int amount,
            boolean immediateShift) {
        if (!immediateShift && amount == 0) {
            return core.cpsr().carry();
        }
        return switch (kind) {
            case LSL -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.LSL, amount);
            case LSR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.LSR, amount);
            case ASR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.ASR, amount);
            case ROR -> amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, ShiftType.ROR, amount);
            default -> throw new IllegalStateException("Unexpected shift op: " + kind);
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

    private ShiftType shiftType(int bits) {
        return switch (bits) {
            case 0 -> ShiftType.LSL;
            case 1 -> ShiftType.LSR;
            case 2 -> ShiftType.ASR;
            case 3 -> ShiftType.ROR;
            default -> throw new IllegalArgumentException("Invalid ARM shift type: " + bits);
        };
    }

    private int normalizedShiftAmount(int shiftBits, int amount) {
        if (amount == 0 && (shiftBits == 1 || shiftBits == 2)) {
            return 32;
        }
        return amount;
    }

    private int applyShift(int value, ShiftType shiftType, int amount) {
        return switch (shiftType) {
            case LSL -> amount >= 32 ? 0 : value << amount;
            case LSR -> amount >= 32 ? 0 : value >>> amount;
            case ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            case ROR -> Integer.rotateRight(value, amount & 31);
        };
    }

    private int applyRegisterShift(int value, ShiftType shiftType, int amount) {
        return switch (shiftType) {
            case LSL -> amount >= 32 ? 0 : value << amount;
            case LSR -> amount >= 32 ? 0 : value >>> amount;
            case ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            case ROR -> Integer.rotateRight(value, amount & 31);
        };
    }

    private int readSourceRegister(ArmCore core, int register, DecodedInstruction instruction) {
        if (register == 15) {
            if (instruction.instructionSet() == dev.vitorsilverio.armjitter.decoder.InstructionSet.THUMB) {
                return (instruction.address() + 4);
            }
            return instruction.address() + 8;
        }
        return core.register(register);
    }

    private int readMemoryStoreRegister(ArmCore core, int register, DecodedInstruction instruction) {
        if (register == 15) {
            if (instruction.instructionSet() == InstructionSet.THUMB) {
                return instruction.address() + 4;
            }
            return instruction.address() + 8;
        }
        return core.register(register);
    }

    private int readMultipleStoreRegister(ArmCore core, int register, DecodedInstruction instruction) {
        if (register == 15 && instruction.instructionSet() == InstructionSet.ARM) {
            return instruction.address() + 12;
        }
        return readMemoryStoreRegister(core, register, instruction);
    }

    private int readMemoryBaseRegister(ArmCore core, DecodedInstruction instruction) {
        if (instruction.sourceRegister() == 15) {
            return readSourceRegister(core, 15, instruction);
        }
        return core.register(instruction.sourceRegister());
    }

    private int read32Arm7(ArmCore core, int address) {
        int aligned = address & ~3;
        int value = core.memory().read32(aligned);
        core.addMemoryCycles(aligned, 4, MemoryAccessType.DATA_READ);
        return Integer.rotateRight(value, (address & 3) * 8);
    }

    private int read8Arm7(ArmCore core, int address) {
        int value = core.memory().read8(address);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
        return value;
    }

    private int read16Arm7(ArmCore core, int address, boolean signed) {
        if (signed && (address & 1) != 0) {
            int value = core.memory().read8(address);
            core.addMemoryCycles(address, 1, MemoryAccessType.DATA_READ);
            return (byte) value;
        }
        int aligned = address & ~1;
        int value = core.memory().read16(aligned);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_READ);
        return value;
    }

    private void write16Arm7(ArmCore core, int address, int value) {
        int aligned = address & ~1;
        core.memory().write16(aligned, value);
        core.addMemoryCycles(aligned, 2, MemoryAccessType.DATA_WRITE);
    }

    private void write8Arm7(ArmCore core, int address, int value) {
        core.memory().write8(address, value);
        core.addMemoryCycles(address, 1, MemoryAccessType.DATA_WRITE);
    }

    private void write32Arm7(ArmCore core, int address, int value) {
        core.memory().write32(address, value);
        core.addMemoryCycles(address, 4, MemoryAccessType.DATA_WRITE);
    }

    private int effectiveRegisterMask(int mask, boolean emptyRegisterList) {
        return emptyRegisterList ? 1 << 15 : mask;
    }

    private int effectiveRegisterCount(int mask, boolean emptyRegisterList) {
        return emptyRegisterList ? 16 : Integer.bitCount(mask);
    }

    private void writeLoadedRegister(ArmCore core, int register, int value) {
        if (register == 15) {
            core.cpsr().setThumbMode((value & 1) != 0);
            core.setProgramCounter(value & ~1);
            return;
        }
        core.setRegister(register, value);
    }

    private void restoreCpsrFromCurrentSpsr(ArmCore core) {
        CpuMode mode = core.mode();
        core.setCpsr(core.spsr(mode));
    }

    private boolean writeAluDestination(ArmCore core, int register, int value, boolean restoreSpsr) {
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

    private void alignAndSetPc(ArmCore core, int value) {
        int mask = core.cpsr().isThumbMode() ? ~1 : ~3;
        core.setProgramCounter(value & mask);
    }

    private int mergePsr(int current, int value, int fieldMask) {
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

    private int cpsrWriteFieldMask(ArmCore core, int fieldMask) {
        return core.mode() == CpuMode.USER ? fieldMask & 0x8 : fieldMask;
    }

    private boolean shouldWriteBackMultiple(boolean writeback, boolean load, int mask, int baseRegister) {
        return writeback && !(load && (mask & (1 << baseRegister)) != 0);
    }

    private int multipleStoreRegisterValue(
            ArmCore core,
            DecodedInstruction instruction,
            int register,
            boolean forceUser,
            int firstRegister,
            int writebackAddress) {
        if (forceUser) {
            return core.bankedRegister(CpuMode.USER, register);
        }
        if (instruction.writeback()
                && register == instruction.sourceRegister()
                && register != firstRegister) {
            return writebackAddress;
        }
        return readMultipleStoreRegister(core, register, instruction);
    }

    private void setLogicFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    private void setLogicFlags(ArmCore core, int result, boolean carry) {
        core.cpsr().setNzcv(result < 0, result == 0, carry, core.cpsr().overflow());
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
