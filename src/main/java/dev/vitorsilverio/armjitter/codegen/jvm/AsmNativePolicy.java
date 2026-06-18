package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link AsmCodeEmitter} (Fase 4).
public final class AsmNativePolicy {
    private static final Set<IrOpCode> SUPPORTED_ALU = EnumSet.of(
            IrOpCode.MOV,
            IrOpCode.ADD,
            IrOpCode.SUB,
            IrOpCode.AND,
            IrOpCode.CMP);

    private AsmNativePolicy() {
    }

    public static boolean supports(IrBlock block) {
        for (IrOp op : block.operations()) {
            if (!supports(op)) {
                return false;
            }
        }
        return true;
    }

    static boolean supports(IrOp op) {
        return switch (op) {
            case IrOp.Alu alu -> supportsAlu(alu);
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
            default -> false;
        };
    }

    private static boolean supportsAlu(IrOp.Alu alu) {
        if (alu.condition() != Condition.AL) {
            return false;
        }
        if (!SUPPORTED_ALU.contains(alu.opcode())) {
            return false;
        }
        if (alu.setFlags() && alu.opcode() != IrOpCode.CMP) {
            return false;
        }
        return switch (alu.src2()) {
            case IrOperand.Immediate ignored -> true;
            case IrOperand.Register ignored -> true;
            case IrOperand.ShiftedRegister ignored -> false;
        };
    }

    public static Set<IrOpCode> supportedAluOpcodes() {
        return Set.copyOf(SUPPORTED_ALU);
    }
}
