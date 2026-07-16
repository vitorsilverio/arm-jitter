package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa branches e interworking da IR interpretada.
public final class IrBranchExecutor {
    private final IrExecutionSupport support;

    IrBranchExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeBranch(ArmCore core, IrOp.Branch branch) {
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
    public boolean executeBranchExchange(ArmCore core, IrOp.BranchExchange branch) {
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

    public void executeThumbBlPrefix(ArmCore core, IrOp.ThumbBlPrefix prefix) {
        if (!core.cpsr().evalCond(prefix.condition())) {
            return;
        }
        core.setRegister(14, prefix.address() + 4 + prefix.highOffset());
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeThumbBlSuffix(ArmCore core, IrOp.ThumbBlSuffix suffix) {
        if (!core.cpsr().evalCond(suffix.condition())) {
            return false;
        }
        int oldLink = core.register(14);
        core.setRegister(14, (suffix.address() + 2) | 1);
        int target = oldLink + suffix.lowOffset();
        if (suffix.exchange()) {
            core.cpsr().setThumbMode(false);   // BLX: back to ARM state
            core.setProgramCounter(target & ~3); // and word-align the destination
        } else {
            core.setProgramCounter(target);
        }
        return true;
    }

    /// `TBB`/`TBH` (B2.4): lê a tabela em memória delegando ao mesmo helper de leitura de
    /// byte/halfword que loads comuns já usam (G1 — sem duplicar cálculo de endereço/alinhamento),
    /// e desvia para `PC_da_instrução + 4 + 2*valor`. O valor lido nunca fica visível em nenhum
    /// registrador (ver a decisão D3 em `b2.4-thumb2-branches-it.md`).
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeTableBranch(ArmCore core, IrOp.TableBranch tableBranch) {
        if (!core.cpsr().evalCond(tableBranch.condition())) {
            return false;
        }
        int tableBase = support.registerValue(core, tableBranch.rn(), tableBranch.rnValueOverride());
        int index = support.registerValue(core, tableBranch.rm(), tableBranch.rmValueOverride());
        int tableEntry;
        if (tableBranch.halfword()) {
            int address = tableBase + (index << 1);
            tableEntry = support.read16Arm7(core, address, false) & 0xFFFF;
        } else {
            int address = tableBase + index;
            tableEntry = support.read8Arm7(core, address) & 0xFF;
        }
        core.setProgramCounter(tableBranch.pcBase() + (tableEntry << 1));
        return true;
    }

    /// `CBZ`/`CBNZ` (B2.4): desvia conforme `rn` seja zero/não-zero, sem tocar NZCV.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeCompareBranchZero(ArmCore core, IrOp.CompareBranchZero cbz) {
        if (!core.cpsr().evalCond(cbz.condition())) {
            return false;
        }
        boolean zero = core.register(cbz.rn()) == 0;
        boolean take = cbz.branchIfNonZero() != zero; // CBNZ: !zero; CBZ: zero
        if (!take) {
            return false;
        }
        core.setProgramCounter(cbz.target());
        return true;
    }
}
