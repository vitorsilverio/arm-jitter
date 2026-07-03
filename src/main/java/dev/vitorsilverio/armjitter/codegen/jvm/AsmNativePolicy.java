package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.EnumSet;
import java.util.Set;

/// Decide se um bloco IR pode ser emitido nativamente pelo {@link dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter}.
///
/// Regra geral: as ops são suportadas para qualquer condição — o {@code AsmBlockCompiler} emite um
/// guard {@code evalCond} por op, espelhando o interpretador. As exceções abaixo são por motivos
/// NÃO-condicionais:
/// <ul>
///   <li>{@link IrOp.Swap} — raro, mantém fallback.</li>
///   <li>{@link IrOp.Alu} com {@code src2} ShiftedRegister — carry-out complexo.</li>
///   <li>{@link IrOp.Alu} com shifts ({@code LSL/LSR/ASR/ROR}) e {@code setFlags=true}.</li>
///   <li>{@link IrOp.Alu} com {@code dst=15} e {@code setFlags=true} — restaura SPSR.</li>
///   <li>{@link IrOp.Load}/{@link IrOp.Store} com offset {@link IrOperand.ShiftedRegister}.</li>
///   <li>BLX ({@link IrOp.BranchExchange} com {@code link}, {@link IrOp.ThumbBlSuffix} com {@code exchange}).</li>
///   <li>Formas ARMv5TE com escrita em PC (o comum — Saturating/DspMultiply/LDRD/STRD sem PC —
///       é emitido nativamente).</li>
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
        // Condição ≠ AL é suportada nativamente: o AsmBlockCompiler emite um guard `evalCond` por
        // op (espelhando o `if (!evalCond) return false;` do interpretador). As rejeições abaixo são
        // por motivos NÃO-CONDICIONAIS: operandos shiftados (carry-out complexo), BLX/interworking,
        // e as ops ARMv5TE ainda não emitidas nativamente (Saturating/DspMultiply/DoubleTransfer/Swap).
        return switch (op) {
            case IrOp.Alu alu -> supportsAlu(alu);
            case IrOp.Multiply ignored -> true;
            case IrOp.LongMultiply ignored -> true;
            // ARMv5TE emitidas nativamente (Mobiclip/SDK usam pesado). Só as formas com escrita
            // em PC (UNPREDICTABLE/troca de bloco) ficam no interpretado.
            case IrOp.Saturating s -> s.dst() != 15;
            case IrOp.DspMultiply d -> d.dst() != 15 && !(d.op2() == 2 && d.rn() == 15);
            case IrOp.DoubleTransfer d -> !(d.offset() instanceof IrOperand.ShiftedRegister)
                    && d.first() + 1 <= (d.load() ? 14 : 15);
            case IrOp.Load l -> !(l.offset() instanceof IrOperand.ShiftedRegister);
            case IrOp.Store s -> !(s.offset() instanceof IrOperand.ShiftedRegister);
            case IrOp.LoadLiteral ignored -> true;
            case IrOp.MultipleTransfer ignored -> true;
            case IrOp.Branch ignored -> true;
            case IrOp.BranchExchange b -> !b.link(); // BLX -> interpret
            case IrOp.ThumbBlPrefix ignored -> true;
            case IrOp.ThumbBlSuffix s -> !s.exchange(); // BLX -> interpret
            case IrOp.Push ignored -> true;
            case IrOp.Pop ignored -> true;
            case IrOp.PsrTransfer ignored -> true;
            case IrOp.Swi ignored -> true;
            case IrOp.Coprocessor ignored -> true;
            case IrOp.Undefined ignored -> true;
            case IrOp.Swap ignored -> false;
            case IrOp.Cycle ignored -> true;
            case IrOp.Fetch ignored -> true;
        };
    }

    private static boolean supportsAlu(IrOp.Alu alu) {
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
