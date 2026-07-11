package dev.vitorsilverio.armjitter.ir;

import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Objects;

/// Lifter linear de blocos usando um decoder e um builder de IR.
public final class StandardIrBlockLifter implements IrBlockLifter {
    private final InstructionDecoder decoder;
    private final IrBuilder builder;

    /// Cria um lifter com o decoder e builder informados.
    public StandardIrBlockLifter(InstructionDecoder decoder, IrBuilder builder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /// Decodifica e eleva um bloco linear a partir de `startPc`.
    @Override
    public IrBlock lift(AddressSpace memory, int startPc, int maxInstructions) {
        if (maxInstructions <= 0) {
            throw new IllegalArgumentException("maxInstructions must be positive");
        }

        IrBlock.Builder block = IrBlock.builder(startPc);
        int pc = startPc;
        for (int i = 0; i < maxInstructions; i++) {
            DecodedInstruction instruction;
            try {
                instruction = decoder.decode(memory, pc);
            } catch (IndexOutOfBoundsException exception) {
                if (!block.isEmpty()) {
                    break;
                }
                throw exception;
            }
            if (instruction.kind() == InstructionKind.UNIMPLEMENTED && !block.isEmpty()) {
                break;
            }
            builder.lift(instruction, block);
            if (isTerminal(instruction)) {
                break;
            }
            pc += instructionWidth(instruction.instructionSet());
        }
        return block.sealed();
    }

    private boolean isTerminal(DecodedInstruction instruction) {
        if (instruction.kind() == InstructionKind.LOAD_MULTIPLE
                && (instruction.emptyRegisterList() || (instruction.immediate() & (1 << 15)) != 0)) {
            return true;
        }
        if ((instruction.kind() == InstructionKind.LOAD || instruction.kind() == InstructionKind.LOAD_LITERAL)
                && instruction.destinationRegister() == 15) {
            return true;
        }
        if (isAluTerminal(instruction)) {
            return true;
        }
        return switch (instruction.kind()) {
            // COPROCESSOR ends the block: a CP15 MCR can change CPU/memory state (TCM/MMU/high
            // vectors) and, critically, the ARM9 wait-for-interrupt (MCR p15,0,Rd,c7,c0,4) must end
            // the block so the run loop re-checks the interrupt line while IME is still set — the
            // libnds swiIntrWait loop toggles IME=1/halt/IME=0 within one block, so a non-terminal
            // coprocessor op would hide the only IME=1 window and the awaited IRQ would never fire.
            // RFE sempre troca o PC (como POP/LOAD com dst=PC) e WFI para a CPU (mesmo motivo do
            // MCR de wait-for-interrupt do CP15 acima): ambas terminam o bloco.
            case BRANCH, BRANCH_EXCHANGE, LONG_BRANCH_SUFFIX, POP, SWI, UNIMPLEMENTED, COPROCESSOR,
                    RETURN_FROM_EXCEPTION, WAIT_FOR_INTERRUPT -> true;
            case MOV, ADD, ADC, SUB, RSB, SBC, RSC, NEG, AND, EOR, ORR, LSL, LSR, ASR, ROR, MUL, MLA, UMULL, UMLAL, SMULL, SMLAL, CLZ, SATURATING, DSP_MULTIPLY, EXTEND, BYTE_REVERSE, UMAAL, PARALLEL_ALU, SEL, PKH, SATURATE, USAD8, LOAD_EXCLUSIVE, STORE_EXCLUSIVE, CLEAR_EXCLUSIVE, BIC, MVN, MRS, MSR, TST, TEQ, CMP, CMN, LOAD_LITERAL, LOAD, STORE, DOUBLE_TRANSFER, SWAP, LOAD_MULTIPLE, STORE_MULTIPLE, LONG_BRANCH_PREFIX, PUSH,
                    CPS, SETEND, STORE_RETURN_STATE -> false;
        };
    }

    private boolean isAluTerminal(DecodedInstruction instruction) {
        return instruction.destinationRegister() == 15
                && switch (instruction.kind()) {
                    case MOV, ADD, ADC, SUB, RSB, SBC, RSC, AND, EOR, ORR, LSL, LSR, ASR, ROR, BIC, MVN -> true;
                    default -> false;
                };
    }

    private int instructionWidth(InstructionSet instructionSet) {
        return switch (instructionSet) {
            case ARM -> 4;
            case THUMB -> 2;
        };
    }
}
