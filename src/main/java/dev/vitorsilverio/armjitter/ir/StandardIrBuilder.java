package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

/// Builder padrao que converte o subconjunto decodificado atual em IR.
public final class StandardIrBuilder implements IrBuilder {
    /// Eleva uma instrucao decodificada para uma ou mais operacoes IR.
    @Override
    public void lift(DecodedInstruction instruction, IrBlock.Builder block) {
        switch (instruction.kind()) {
            case MOV -> liftAlu("MOV", instruction, block);
            case ADD -> liftAlu("ADD", instruction, block);
            case ADC -> liftAlu("ADC", instruction, block);
            case SUB -> liftAlu("SUB", instruction, block);
            case RSB -> liftAlu("RSB", instruction, block);
            case SBC -> liftAlu("SBC", instruction, block);
            case RSC -> liftAlu("RSC", instruction, block);
            case NEG -> liftAlu("NEG", instruction, block);
            case AND -> liftAlu("AND", instruction, block);
            case EOR -> liftAlu("EOR", instruction, block);
            case ORR -> liftAlu("ORR", instruction, block);
            case BIC -> liftAlu("BIC", instruction, block);
            case MVN -> liftAlu("MVN", instruction, block);
            case MRS -> block.add(new IrOp.PsrTransfer(
                    true,
                    instruction.immediate() != 0,
                    instruction.destinationRegister(),
                    -1,
                    0,
                    false,
                    0,
                    instruction.condition()));
            case MSR -> block.add(new IrOp.PsrTransfer(
                    false,
                    ((instruction.immediateOperand() ? instruction.destinationRegister() : instruction.immediate()) & 0x10) != 0,
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.immediateOperand() ? instruction.immediate() : 0,
                    instruction.immediateOperand(),
                    (instruction.immediateOperand() ? instruction.destinationRegister() : instruction.immediate()) & 0xF,
                    instruction.condition()));
            case TST -> liftAlu("TST", instruction, block);
            case TEQ -> liftAlu("TEQ", instruction, block);
            case LSL -> liftAlu("LSL", instruction, block);
            case LSR -> liftAlu("LSR", instruction, block);
            case ASR -> liftAlu("ASR", instruction, block);
            case ROR -> liftAlu("ROR", instruction, block);
            case MUL, MLA -> block.add(new IrOp.Multiply(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    instruction.kind() == InstructionKind.MLA ? instruction.immediate() : -1,
                    instruction.kind() == InstructionKind.MLA
                            ? registerValueOverride(instruction, instruction.immediate())
                            : -1,
                    instruction.kind() == InstructionKind.MLA,
                    instruction.setFlags(),
                    instruction.condition()));
            case UMULL, UMLAL, SMULL, SMLAL -> block.add(new IrOp.LongMultiply(
                    instruction.destinationRegister(),
                    instruction.immediate(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    registerValueOverride(instruction, instruction.immediate()),
                    registerValueOverride(instruction, instruction.destinationRegister()),
                    instruction.kind() == InstructionKind.SMULL || instruction.kind() == InstructionKind.SMLAL,
                    instruction.kind() == InstructionKind.UMLAL || instruction.kind() == InstructionKind.SMLAL,
                    instruction.setFlags(),
                    instruction.condition()));
            case CLZ -> block.add(new IrOp.Alu(
                    "CLZ",
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    new IrOperand.Immediate(0),
                    false,
                    instruction.condition()));
            case CMP -> liftAlu("CMP", instruction, block);
            case CMN -> liftAlu("CMN", instruction, block);
            case LOAD_LITERAL -> block.add(new IrOp.LoadLiteral(
                    instruction.destinationRegister(),
                    instruction.immediate(),
                    instruction.condition()));
            case LOAD -> block.add(new IrOp.Load(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    offset(instruction),
                    instruction.accessSizeBytes(),
                    instruction.signedAccess(),
                    instruction.writeback(),
                    instruction.postIndexed(),
                    instruction.condition()));
            case STORE -> block.add(new IrOp.Store(
                    instruction.destinationRegister(),
                    registerValueOverride(instruction, instruction.destinationRegister()),
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    offset(instruction),
                    instruction.accessSizeBytes(),
                    instruction.writeback(),
                    instruction.postIndexed(),
                    instruction.condition()));
            case SWAP -> block.add(new IrOp.Swap(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    baseValueOverride(instruction),
                    instruction.secondSourceRegister(),
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    instruction.accessSizeBytes(),
                    instruction.condition()));
            case LOAD_MULTIPLE -> block.add(new IrOp.MultipleTransfer(
                    true,
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.writeback(),
                    pcStoreValueOverride(instruction),
                    instruction.link(),
                    instruction.blockTransferMode(),
                    instruction.emptyRegisterList(),
                    instruction.condition()));
            case STORE_MULTIPLE -> block.add(new IrOp.MultipleTransfer(
                    false,
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.writeback(),
                    pcStoreValueOverride(instruction),
                    instruction.link(),
                    instruction.blockTransferMode(),
                    instruction.emptyRegisterList(),
                    instruction.condition()));
            case BRANCH -> block.add(new IrOp.Branch(
                    instruction.immediate(),
                    instruction.address() + instructionWidth(instruction),
                    instruction.link(),
                    instruction.condition(),
                    instruction.instructionSet()));
            case BRANCH_EXCHANGE -> block.add(new IrOp.BranchExchange(
                    instruction.sourceRegister(),
                    registerValueOverride(instruction, instruction.sourceRegister()),
                    instruction.condition()));
            case LONG_BRANCH_PREFIX -> block.add(new IrOp.ThumbBlPrefix(
                    instruction.immediate(),
                    instruction.address(),
                    instruction.condition()));
            case LONG_BRANCH_SUFFIX -> block.add(new IrOp.ThumbBlSuffix(
                    instruction.immediate(),
                    instruction.address(),
                    instruction.condition()));
            case PUSH -> block.add(new IrOp.Push(
                    instruction.immediate(),
                    instruction.link(),
                    instruction.condition()));
            case POP -> block.add(new IrOp.Pop(
                    instruction.immediate(),
                    instruction.link(),
                    instruction.condition()));
            case SWI -> block.add(new IrOp.Swi(instruction.immediate(), instruction.condition()));
            case UNIMPLEMENTED -> block.add(new IrOp.Undefined(
                    instruction.address() + instructionWidth(instruction),
                    instruction.condition()));
        }

        block.add(new IrOp.Cycle(1));
        block.add(new IrOp.Fetch(instruction.address(), instructionWidth(instruction)));
        block.endPc(instruction.address() + instructionWidth(instruction));
    }

    private void liftAlu(String opcode, DecodedInstruction instruction, IrBlock.Builder block) {
        block.add(new IrOp.Alu(
                opcode,
                instruction.destinationRegister(),
                instruction.sourceRegister(),
                registerValueOverride(instruction, instruction.sourceRegister()),
                operand(instruction),
                instruction.setFlags() || instruction.kind() == InstructionKind.CMP || instruction.kind() == InstructionKind.CMN,
                instruction.condition()));
    }

    private IrOperand operand(DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            if (instruction.instructionSet() == InstructionSet.ARM) {
                int rotate = ((instruction.raw() >>> 8) & 0xF) * 2;
                return new IrOperand.Immediate(instruction.immediate(), rotate != 0, instruction.immediate() < 0);
            }
            if (instruction.instructionSet() == InstructionSet.THUMB
                    && instruction.immediate() == 0
                    && (instruction.kind() == InstructionKind.LSR || instruction.kind() == InstructionKind.ASR)) {
                return new IrOperand.Immediate(32);
            }
            return new IrOperand.Immediate(instruction.immediate());
        }
        if (instruction.instructionSet() == InstructionSet.ARM) {
            if ((instruction.raw() & (1 << 4)) != 0) {
                int shiftBits = (instruction.raw() >>> 5) & 0x3;
                int amountRegister = (instruction.raw() >>> 8) & 0xF;
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        shiftType(shiftBits),
                        0,
                        amountRegister,
                        registerValueOverride(instruction, instruction.secondSourceRegister()),
                        registerValueOverride(instruction, amountRegister),
                        false,
                        false);
            }
            int amount = (instruction.raw() >>> 7) & 0x1F;
            int shiftBits = (instruction.raw() >>> 5) & 0x3;
            if (amount != 0 || shiftBits != 0) {
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        shiftType(shiftBits),
                        normalizedShiftAmount(shiftBits, amount),
                        -1,
                        registerValueOverride(instruction, instruction.secondSourceRegister()),
                        -1,
                        amount == 0 && shiftBits == 3,
                        false);
            }
        }
        return new IrOperand.Register(
                instruction.secondSourceRegister(),
                registerValueOverride(instruction, instruction.secondSourceRegister()));
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

    private IrOperand offset(DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return new IrOperand.Immediate(instruction.immediate());
        }
        if (instruction.instructionSet() == InstructionSet.ARM) {
            boolean negated = instruction.immediate() < 0;
            if (isArmSingleDataTransfer(instruction.raw())) {
                int amount = (instruction.raw() >>> 7) & 0x1F;
                int shiftBits = (instruction.raw() >>> 5) & 0x3;
                return new IrOperand.ShiftedRegister(
                        instruction.secondSourceRegister(),
                        shiftType(shiftBits),
                        normalizedShiftAmount(shiftBits, amount),
                        -1,
                        registerValueOverride(instruction, instruction.secondSourceRegister()),
                        -1,
                        amount == 0 && shiftBits == 3,
                        negated);
            }
            return new IrOperand.ShiftedRegister(
                    instruction.secondSourceRegister(),
                    ShiftType.LSL,
                    0,
                    -1,
                    registerValueOverride(instruction, instruction.secondSourceRegister()),
                    -1,
                    false,
                    negated);
        }
        return new IrOperand.Register(
                instruction.secondSourceRegister(),
                registerValueOverride(instruction, instruction.secondSourceRegister()));
    }

    private int baseValueOverride(DecodedInstruction instruction) {
        if (instruction.sourceRegister() != 15) {
            return -1;
        }
        return registerValueOverride(instruction, 15);
    }

    private int registerValueOverride(DecodedInstruction instruction, int register) {
        if (register != 15) {
            return -1;
        }
        return switch (instruction.instructionSet()) {
            case ARM -> instruction.address() + 8;
            case THUMB -> (instruction.address() + 4);
        };
    }

    private int pcStoreValueOverride(DecodedInstruction instruction) {
        return switch (instruction.instructionSet()) {
            case ARM -> instruction.address() + 12;
            case THUMB -> instruction.address() + 4;
        };
    }

    private boolean isArmSingleDataTransfer(int raw) {
        return (raw & 0x0C00_0000) == 0x0400_0000;
    }

    private int instructionWidth(DecodedInstruction instruction) {
        return switch (instruction.instructionSet()) {
            case ARM -> 4;
            case THUMB -> 2;
        };
    }
}
