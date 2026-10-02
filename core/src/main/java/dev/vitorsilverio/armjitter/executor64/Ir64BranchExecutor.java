package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.decoder64.Aarch64Decoder;
import dev.vitorsilverio.armjitter.ir64.BranchOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;

/// Semântica dos desvios A64 ({@link BranchOp64}): `B`/`BL`/`BR`/`RET`, `B.cond`, `CBZ`/`TBZ` e as
/// formas compare-and-branch.
///
/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}
/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.
public final class Ir64BranchExecutor {
    /// Deslocamento em bytes do registrador X30 (link register) usado por `BL`/`BLR`.
    private static final int LINK_REGISTER = 30;

    private Ir64BranchExecutor() {
    }

    /// Executa {@link BranchOp64.Branch64}.
    public static boolean executeBranch(Aarch64Core core, BranchOp64.Branch64 op) {
        if (!core.pstate().evalCond(op.condition())) {
            return false;
        }
        long target = switch (op.form()) {
            case IMMEDIATE -> op.target();
            case REGISTER -> core.x(op.registerOperand());
        };
        if (op.link()) {
            core.setX(LINK_REGISTER, op.instructionAddress() + Aarch64Decoder.instructionSizeBytes());
        }
        core.setProgramCounter(target);
        return true;
    }

    /// Executa {@link BranchOp64.CompareBranch64}.
    public static boolean executeCompareBranch(Aarch64Core core, BranchOp64.CompareBranch64 op) {
        boolean conditionMet = switch (op.form()) {
            case CBZ_CBNZ -> {
                long value = core.xForWidth(op.rn(), op.wide());
                yield op.branchIfNonZero() ? value != 0 : value == 0;
            }
            case TBZ_TBNZ -> {
                long bit = (core.x(op.rn()) >>> op.bitPosition()) & 1L;
                yield op.branchIfNonZero() ? bit != 0 : bit == 0;
            }
        };
        if (!conditionMet) {
            return false;
        }
        core.setProgramCounter(op.target());
        return true;
    }

    /// `CB_cond` (`FEAT_CMPBR`, B19.22) — compara `Rt`/`Rm` sem tocar `NZCV`. Reusa
    /// {@link Ir64MemoryExecutor#signExtendFromSize}/{@link Ir64MemoryExecutor#zeroTruncateToSize} (que já são identidade para
    /// {@link Ir64MemSize#DOUBLEWORD}, dispensando um caso especial de 64 bits aqui) para estender
    /// os operandos conforme a assinatura da condição — só `GT`/`GE` são com sinal
    /// ({@link Ir64CompareBranchCondition#isSigned}).
    public static boolean executeCompareAndBranchRegister(Aarch64Core core, BranchOp64.CompareAndBranchRegister op) {
        long rawT = core.x(op.rt());
        long rawM = core.x(op.rm());
        long t = op.condition().isSigned()
                ? Ir64MemoryExecutor.signExtendFromSize(rawT, op.size()) : Ir64MemoryExecutor.zeroTruncateToSize(rawT, op.size());
        long m = op.condition().isSigned()
                ? Ir64MemoryExecutor.signExtendFromSize(rawM, op.size()) : Ir64MemoryExecutor.zeroTruncateToSize(rawM, op.size());
        if (!evaluateCompareBranchCondition(op.condition(), t, m)) {
            return false;
        }
        core.setProgramCounter(op.target());
        return true;
    }

    /// `CB_cond_imm` (`FEAT_CMPBR`, B19.22) — compara `Rt` contra o imediato `UInt(imm6)` sem tocar
    /// `NZCV`.
    public static boolean executeCompareAndBranchImmediate(Aarch64Core core, BranchOp64.CompareAndBranchImmediate op) {
        long rawT = core.x(op.rt());
        long t = op.wide()
                ? rawT
                : (op.condition().isSigned() ? (long) (int) rawT : rawT & 0xFFFF_FFFFL);
        if (!evaluateCompareBranchCondition(op.condition(), t, (long) op.immediate())) {
            return false;
        }
        core.setProgramCounter(op.target());
        return true;
    }

    private static boolean evaluateCompareBranchCondition(
            Ir64CompareBranchCondition condition, long a, long b) {
        return switch (condition) {
            case GREATER_THAN -> a > b;
            case GREATER_OR_EQUAL -> a >= b;
            case GREATER_THAN_UNSIGNED -> Long.compareUnsigned(a, b) > 0;
            case GREATER_OR_EQUAL_UNSIGNED -> Long.compareUnsigned(a, b) >= 0;
            case LESS_THAN -> a < b;
            case LESS_THAN_UNSIGNED -> Long.compareUnsigned(a, b) < 0;
            case EQUAL -> a == b;
            case NOT_EQUAL -> a != b;
        };
    }
}
