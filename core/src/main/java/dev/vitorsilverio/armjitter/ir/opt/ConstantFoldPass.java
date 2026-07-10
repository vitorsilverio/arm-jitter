package dev.vitorsilverio.armjitter.ir.opt;

import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;

import java.util.ArrayList;
import java.util.List;

/// Substitui operações ALU cujos dois operandos são conhecidos em tempo de compilação
/// por um {@code MOV dst, #resultado}.
///
/// Condições para dobramento:
/// <ul>
///   <li>Condição {@code AL}.</li>
///   <li>{@code setFlags = false} — dobrar flags exigiria simular o barrel-shifter completo.</li>
///   <li>{@code dst != 15} — modificar o PC tem semântica especial.</li>
///   <li>{@code src2} é {@link IrOperand.Immediate}.</li>
///   <li>Opcode não precisa de carry (ADC/SBC/RSC) nem é de teste puro (CMP/CMN/TST/TEQ).</li>
///   <li>Para opcodes que usam src1, {@code src1ValueOverride >= 0}.</li>
/// </ul>
public final class ConstantFoldPass implements IrOptimizer {

    @Override
    public IrBlock optimize(IrBlock block) {
        List<IrOp> result = new ArrayList<>(block.operations().size());
        boolean changed = false;
        for (IrOp op : block.operations()) {
            if (op instanceof IrOp.Alu alu && canFold(alu)) {
                result.add(foldedMov(alu));
                changed = true;
            } else {
                result.add(op);
            }
        }
        return changed ? new IrBlock(block.startPc(), block.endPc(), result) : block;
    }

    private static boolean canFold(IrOp.Alu alu) {
        if (alu.condition() != Condition.AL) return false;
        if (alu.setFlags()) return false;
        if (alu.dst() == 15) return false;
        if (!(alu.src2() instanceof IrOperand.Immediate)) return false;
        return switch (alu.opcode()) {
            case MOV, MVN, NEG -> true;
            case ADC, SBC, RSC -> false;           // precisam do carry
            case CMP, CMN, TST, TEQ -> false;      // sem escrita em registrador
            // ARMv6 (B1.2): sem dobra por ora — compute() não as conhece, e o REV com Rm=PC
            // teria src1ValueOverride >= 0 e cairia no default indevidamente.
            case SXTB, SXTH, SXTB16, UXTB, UXTH, UXTB16, REV, REV16, REVSH -> false;
            default -> alu.src1ValueOverride() >= 0;
        };
    }

    private static IrOp.Alu foldedMov(IrOp.Alu alu) {
        int value = compute(alu);
        return new IrOp.Alu(
                IrOpCode.MOV, alu.dst(), 0, -1,
                new IrOperand.Immediate(value), false, Condition.AL);
    }

    private static int compute(IrOp.Alu alu) {
        int imm = ((IrOperand.Immediate) alu.src2()).value();
        int s1 = alu.src1ValueOverride();
        return switch (alu.opcode()) {
            case MOV -> imm;
            case MVN -> ~imm;
            case NEG -> -imm;
            case ADD -> s1 + imm;
            case SUB -> s1 - imm;
            case RSB -> imm - s1;
            case AND -> s1 & imm;
            case EOR -> s1 ^ imm;
            case ORR -> s1 | imm;
            case BIC -> s1 & ~imm;
            case CLZ -> Integer.numberOfLeadingZeros(s1);
            case LSL -> { int a = imm & 0xFF; yield a >= 32 ? 0 : s1 << a; }
            case LSR -> { int a = imm & 0xFF; yield a == 0 ? s1 : (a >= 32 ? 0 : s1 >>> a); }
            case ASR -> { int a = imm & 0xFF; yield a == 0 ? s1 : (a >= 32 ? (s1 < 0 ? -1 : 0) : s1 >> a); }
            case ROR -> { int a = imm & 0xFF; yield a == 0 ? s1 : Integer.rotateRight(s1, a & 31); }
            default -> throw new IllegalStateException("ConstantFoldPass: opcode inesperado " + alu.opcode());
        };
    }
}
