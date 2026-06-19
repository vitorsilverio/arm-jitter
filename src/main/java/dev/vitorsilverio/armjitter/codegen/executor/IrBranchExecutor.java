package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa branches e interworking da IR interpretada.
final class IrBranchExecutor {
    private final IrExecutionSupport support;

    IrBranchExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeBranch(ArmCore core, IrOp.Branch branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        if (branch.link()) {
            core.setRegister(14, branch.returnAddress());
        }
        core.setProgramCounter(branch.target());
        return true;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeBranchExchange(ArmCore core, IrOp.BranchExchange branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        int target = support.registerValue(core, branch.sourceRegister(), branch.sourceValueOverride());
        if (branch.link()) {
            core.setRegister(14, branch.returnAddress()); // BLX: capture return before exchanging
        }
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
        return true;
    }

    void executeThumbBlPrefix(ArmCore core, IrOp.ThumbBlPrefix prefix) {
        if (!core.cpsr().evalCond(prefix.condition())) {
            return;
        }
        core.setRegister(14, prefix.address() + 4 + prefix.highOffset());
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    boolean executeThumbBlSuffix(ArmCore core, IrOp.ThumbBlSuffix suffix) {
        if (!core.cpsr().evalCond(suffix.condition())) {
            return false;
        }
        int oldLink = core.register(14);
        core.setRegister(14, (suffix.address() + 2) | 1);
        core.setProgramCounter(oldLink + suffix.lowOffset());
        return true;
    }
}
