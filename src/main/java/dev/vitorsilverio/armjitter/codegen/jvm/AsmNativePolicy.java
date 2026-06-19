package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
///
/// Regra geral: todas as ops com condição {@code AL} são suportadas, exceto:
/// <ul>
///   <li>{@link IrOp.Swap} — raro, mantém fallback.</li>
///   <li>{@link IrOp.Alu} com {@code src2} ShiftedRegister — carry-out complexo.</li>
///   <li>{@link IrOp.Alu} com shifts ({@code LSL/LSR/ASR/ROR}) e {@code setFlags=true}.</li>
///   <li>{@link IrOp.Alu} com {@code dst=15} e {@code setFlags=true} — restaura SPSR.</li>
///   <li>{@link IrOp.Load}/{@link IrOp.Store} com offset {@link IrOperand.ShiftedRegister}.</li>
/// </ul>
public final class AsmNativePolicy {
    private static final Set<IrOpCode> SHIFT_OPCODES = EnumSet.of(
            IrOpCode.LSL, IrOpCode.LSR, IrOpCode.ASR, IrOpCode.ROR);

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

    public static boolean supports(IrOp op) {
        return switch (op) {
            case IrOp.Alu alu -> supportsAlu(alu);
            case IrOp.Multiply m -> m.condition() == Condition.AL;
            case IrOp.LongMultiply m -> m.condition() == Condition.AL;
            case IrOp.Load l -> l.condition() == Condition.AL && !(l.offset() instanceof IrOperand.ShiftedRegister);
            case IrOp.Store s -> s.condition() == Condition.AL && !(s.offset() instanceof IrOperand.ShiftedRegister);
            case IrOp.LoadLiteral l -> l.condition() == Condition.AL;
            case IrOp.MultipleTransfer t -> t.condition() == Condition.AL;
            case IrOp.Branch b -> b.condition() == Condition.AL;
            case IrOp.BranchExchange b -> b.condition() == Condition.AL && !b.link(); // BLX -> interpret
            case IrOp.ThumbBlPrefix p -> p.condition() == Condition.AL;
            case IrOp.ThumbBlSuffix s -> s.condition() == Condition.AL && !s.exchange(); // BLX -> interpret
            case IrOp.Push p -> p.condition() == Condition.AL;
            case IrOp.Pop p -> p.condition() == Condition.AL;
            case IrOp.PsrTransfer t -> t.condition() == Condition.AL;
            case IrOp.Swi s -> s.condition() == Condition.AL;
            case IrOp.Coprocessor c -> c.condition() == Condition.AL;
            case IrOp.Undefined u -> u.condition() == Condition.AL;
            case IrOp.Swap ignored -> false;
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
        };
    }

    private static boolean supportsAlu(IrOp.Alu alu) {
        if (alu.condition() != Condition.AL) return false;
        if (alu.src2() instanceof IrOperand.ShiftedRegister) return false;
        // Shift+setFlags: carry-out computation is complex, defer to interpreted.
        if (SHIFT_OPCODES.contains(alu.opcode()) && alu.setFlags()) return false;
        // dst=15 + setFlags: restores CPSR from SPSR, defer to interpreted.
        if (alu.dst() == 15 && alu.setFlags()) return false;
        return true;
    }

    /// Opcodes ALU actualmente emitidos nativamente (pode incluir todos exceto os filtrados acima).
    public static Set<IrOpCode> supportedAluOpcodes() {
        return EnumSet.allOf(IrOpCode.class);
    }
}
