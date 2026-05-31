package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;

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
            case SBC -> liftAlu("SBC", instruction, block);
            case NEG -> liftAlu("NEG", instruction, block);
            case AND -> liftAlu("AND", instruction, block);
            case EOR -> liftAlu("EOR", instruction, block);
            case ORR -> liftAlu("ORR", instruction, block);
            case BIC -> liftAlu("BIC", instruction, block);
            case MVN -> liftAlu("MVN", instruction, block);
            case TST -> liftAlu("TST", instruction, block);
            case TEQ -> liftAlu("TEQ", instruction, block);
            case LSL -> liftAlu("LSL", instruction, block);
            case LSR -> liftAlu("LSR", instruction, block);
            case ASR -> liftAlu("ASR", instruction, block);
            case ROR -> liftAlu("ROR", instruction, block);
            case MUL -> liftAlu("MUL", instruction, block);
            case CMP -> liftAlu("CMP", instruction, block);
            case CMN -> liftAlu("CMN", instruction, block);
            case LOAD_LITERAL -> block.add(new IrOp.LoadLiteral(
                    instruction.destinationRegister(),
                    instruction.immediate(),
                    instruction.condition()));
            case LOAD -> block.add(new IrOp.Load(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    offset(instruction),
                    instruction.accessSizeBytes(),
                    instruction.signedAccess(),
                    false,
                    instruction.condition()));
            case STORE -> block.add(new IrOp.Store(
                    instruction.destinationRegister(),
                    instruction.sourceRegister(),
                    offset(instruction),
                    instruction.accessSizeBytes(),
                    false,
                    instruction.condition()));
            case LOAD_MULTIPLE -> block.add(new IrOp.MultipleTransfer(
                    true,
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.writeback(),
                    instruction.condition()));
            case STORE_MULTIPLE -> block.add(new IrOp.MultipleTransfer(
                    false,
                    instruction.sourceRegister(),
                    instruction.immediate(),
                    instruction.writeback(),
                    instruction.condition()));
            case BRANCH -> block.add(new IrOp.Branch(
                    instruction.immediate(),
                    instruction.address() + instructionWidth(instruction),
                    instruction.link(),
                    instruction.condition(),
                    instruction.instructionSet()));
            case BRANCH_EXCHANGE -> block.add(new IrOp.BranchExchange(
                    instruction.sourceRegister(),
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
            case UNIMPLEMENTED -> throw new UnsupportedOperationException(
                    "Cannot lift unimplemented instruction: 0x" + Integer.toHexString(instruction.raw()));
        }

        block.add(new IrOp.Cycle(1));
        block.endPc(instruction.address() + instructionWidth(instruction));
    }

    private void liftAlu(String opcode, DecodedInstruction instruction, IrBlock.Builder block) {
        block.add(new IrOp.Alu(
                opcode,
                instruction.destinationRegister(),
                instruction.sourceRegister(),
                operand(instruction),
                instruction.setFlags() || instruction.kind() == InstructionKind.CMP || instruction.kind() == InstructionKind.CMN,
                instruction.condition()));
    }

    private IrOperand operand(DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return new IrOperand.Immediate(instruction.immediate());
        }
        return new IrOperand.Register(instruction.secondSourceRegister());
    }

    private IrOperand offset(DecodedInstruction instruction) {
        if (instruction.immediateOperand()) {
            return new IrOperand.Immediate(instruction.immediate());
        }
        return new IrOperand.Register(instruction.secondSourceRegister());
    }

    private int instructionWidth(DecodedInstruction instruction) {
        return switch (instruction.instructionSet()) {
            case ARM -> 4;
            case THUMB -> 2;
        };
    }
}
