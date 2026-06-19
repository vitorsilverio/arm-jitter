package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOpCode;
import dev.vitorsilverio.armjitter.ir.IrOperand;

/// Executa operações ALU e multiplicação da IR interpretada.
final class IrAluExecutor {
    private final IrExecutionSupport support;

    IrAluExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean execute(ArmCore core, IrOp.Alu alu) {
        if (!core.cpsr().evalCond(alu.condition())) {
            return false;
        }

        int right = support.operand(core, alu.src2());
        switch (alu.opcode()) {
            case IrOpCode.MOV -> {
                boolean carry = alu.setFlags() && support.operandCarryOut(core, alu.src2());
                if (support.writeAluDestination(core, alu.dst(), right, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setLogicFlags(core, right, carry);
                }
            }
            case IrOpCode.ADD -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = left + right;
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setAddFlags(core, left, right, result);
                }
            }
            case IrOpCode.ADC -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int carry = core.cpsr().carry() ? 1 : 0;
                int result = left + right + carry;
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setAdcFlags(core, left, right, carry, result);
                }
            }
            case IrOpCode.SUB, IrOpCode.RSB, IrOpCode.CMP, IrOpCode.SBC, IrOpCode.RSC, IrOpCode.NEG -> {
                int source = IrOpCode.NEG.equals(alu.opcode()) ? 0 : support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                boolean reverse = IrOpCode.RSB.equals(alu.opcode()) || IrOpCode.RSC.equals(alu.opcode());
                int left = reverse ? right : source;
                int subRight = reverse ? source : right;
                int borrow = (IrOpCode.SBC.equals(alu.opcode()) || IrOpCode.RSC.equals(alu.opcode()))
                        && !core.cpsr().carry() ? 1 : 0;
                int subtrahend = subRight + borrow;
                int result = left - subtrahend;
                if (!IrOpCode.CMP.equals(alu.opcode())) {
                    if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                        return true;
                    }
                }
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        support.restoreCpsrFromCurrentSpsr(core);
                    } else {
                        support.setSbcFlags(core, left, subRight, borrow, result);
                    }
                }
            }
            case IrOpCode.CMN -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = left + right;
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        support.restoreCpsrFromCurrentSpsr(core);
                    } else {
                        support.setAddFlags(core, left, right, result);
                    }
                }
            }
            case IrOpCode.AND, IrOpCode.EOR, IrOpCode.ORR, IrOpCode.BIC, IrOpCode.TST, IrOpCode.TEQ -> {
                int left = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int result = switch (alu.opcode()) {
                    case IrOpCode.AND -> left & right;
                    case IrOpCode.EOR -> left ^ right;
                    case IrOpCode.ORR -> left | right;
                    case IrOpCode.BIC -> left & ~right;
                    case IrOpCode.TST -> left & right;
                    case IrOpCode.TEQ -> left ^ right;
                    default -> throw new IllegalStateException("Unexpected logic opcode: " + alu.opcode());
                };
                boolean carry = alu.setFlags() && support.operandCarryOut(core, alu.src2());
                if (!IrOpCode.TST.equals(alu.opcode()) && !IrOpCode.TEQ.equals(alu.opcode())) {
                    if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                        return true;
                    }
                }
                if (alu.setFlags()) {
                    if (alu.dst() == 15) {
                        support.restoreCpsrFromCurrentSpsr(core);
                    } else {
                        support.setLogicFlags(core, result, carry);
                    }
                }
            }
            case IrOpCode.MVN -> {
                int result = ~right;
                boolean carry = alu.setFlags() && support.operandCarryOut(core, alu.src2());
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setLogicFlags(core, result, carry);
                }
            }
            case IrOpCode.CLZ -> core.setRegister(alu.dst(), Integer.numberOfLeadingZeros(
                    support.registerValue(core, alu.src1(), alu.src1ValueOverride())));
            case IrOpCode.LSL, IrOpCode.LSR, IrOpCode.ASR, IrOpCode.ROR -> {
                int value = support.registerValue(core, alu.src1(), alu.src1ValueOverride());
                int amount = right & 0xFF;
                int result = switch (alu.opcode()) {
                    case IrOpCode.LSL -> amount >= 32 ? 0 : value << amount;
                    case IrOpCode.LSR -> amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
                    case IrOpCode.ASR -> amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
                    case IrOpCode.ROR -> amount == 0 ? value : Integer.rotateRight(value, amount & 31);
                    default -> throw new IllegalStateException("Unexpected shift opcode: " + alu.opcode());
                };
                if (support.writeAluDestination(core, alu.dst(), result, alu.setFlags())) {
                    return true;
                }
                if (alu.setFlags()) {
                    support.setLogicFlags(core, result, support.shiftCarryOut(core, value, alu.opcode(), amount,
                            alu.src2() instanceof IrOperand.Immediate));
                }
            }
            default -> throw new UnsupportedOperationException("Unknown IR ALU opcode: " + alu.opcode());
        }
        return false;
    }

    void executeMultiply(ArmCore core, IrOp.Multiply multiply) {
        if (!core.cpsr().evalCond(multiply.condition())) {
            return;
        }
        int result = support.registerValue(core, multiply.rm(), multiply.rmValueOverride())
                * support.registerValue(core, multiply.rs(), multiply.rsValueOverride());
        if (multiply.accumulate()) {
            result += support.registerValue(core, multiply.rn(), multiply.rnValueOverride());
        }
        core.setRegister(multiply.dst(), result);
        if (multiply.setFlags()) {
            support.setLogicFlags(core, result);
        }
    }

    void executeLongMultiply(ArmCore core, IrOp.LongMultiply multiply) {
        if (!core.cpsr().evalCond(multiply.condition())) {
            return;
        }
        long result;
        if (multiply.signed()) {
            result = (long) support.registerValue(core, multiply.rm(), multiply.rmValueOverride())
                    * (long) support.registerValue(core, multiply.rs(), multiply.rsValueOverride());
        } else {
            result = Integer.toUnsignedLong(support.registerValue(core, multiply.rm(), multiply.rmValueOverride()))
                    * Integer.toUnsignedLong(support.registerValue(core, multiply.rs(), multiply.rsValueOverride()));
        }
        if (multiply.accumulate()) {
            long current = (Integer.toUnsignedLong(support.registerValue(core, multiply.dstHigh(), multiply.dstHighValueOverride())) << 32)
                    | Integer.toUnsignedLong(support.registerValue(core, multiply.dstLow(), multiply.dstLowValueOverride()));
            result += current;
        }
        core.setRegister(multiply.dstLow(), (int) result);
        core.setRegister(multiply.dstHigh(), (int) (result >>> 32));
        if (multiply.setFlags()) {
            core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
        }
    }

    /// ARMv5TE saturating add/subtract (QADD/QSUB/QDADD/QDSUB), all clamped to signed 32 bits and
    /// setting the sticky Q flag on any saturation.
    void executeSaturating(ArmCore core, IrOp.Saturating op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        int rm = core.register(op.rm());
        int rn = core.register(op.rn());
        boolean[] q = {false};
        int result = switch (op.op()) {
            case 0 -> clamp((long) rm + rn, q);                 // QADD:  sat(Rm + Rn)
            case 1 -> clamp((long) rm - rn, q);                 // QSUB:  sat(Rm - Rn)
            case 2 -> clamp((long) rm + clamp(2L * rn, q), q);  // QDADD: sat(Rm + sat(2*Rn))
            default -> clamp((long) rm - clamp(2L * rn, q), q); // QDSUB: sat(Rm - sat(2*Rn))
        };
        core.setRegister(op.dst(), result);
        if (q[0]) {
            core.cpsr().setSaturation(true); // sticky: only ever set here
        }
    }

    private static int clamp(long value, boolean[] saturated) {
        if (value > Integer.MAX_VALUE) {
            saturated[0] = true;
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            saturated[0] = true;
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
