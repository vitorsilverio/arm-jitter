package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.ir.IrOp;

/// Executa branches e interworking da IR interpretada.
public final class IrBranchExecutor {
    /// `LR`/`R14` reusado como contador de loop pelo Low Overhead Branch Extension (B15.6,
    /// ARMv8.1-M) — não há registrador oculto, ver Javadoc de `IrOp.LoopStart`/`IrOp.LoopEnd`.
    private static final int LOOP_COUNTER_REGISTER = 14;

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
            core.setRegister(14, branch.returnAddress()); // BLX: captura o retorno antes de trocar de estado
        }
        if (core.exceptionModel().interceptsBranch(target)) {
            core.exceptionModel().branchIntercepted(core, target);
            return true;
        }
        core.cpsr().setThumbMode((target & 1) != 0);
        core.setProgramCounter(target & ~1);
        return true;
    }

    /// `BXNS`/`BLXNS` (perfil M, B15.4): só produzida sob `ArmFeature#M_PROFILE_SECURITY`
    /// (exclusivo do perfil M) — cast direto, mesmo padrão de `IrSystemExecutor#executeNocp`.
    /// `MProfileExceptionModel` decide sozinho o destino de `LR`/`SP`/PC (troca de estado,
    /// `EXC_RETURN`/`FNC_RETURN`) — ver Javadoc de `MProfileExceptionModel#secureBranchExchange`.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeSecureBranchExchange(ArmCore core, IrOp.SecureBranchExchange branch) {
        if (!core.cpsr().evalCond(branch.condition())) {
            return false;
        }
        int target = support.registerValue(core, branch.sourceRegister(), branch.sourceValueOverride());
        ((dev.vitorsilverio.armjitter.core.MProfileExceptionModel) core.exceptionModel())
                .secureBranchExchange(core, target, branch.link(), branch.returnAddress());
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
            core.cpsr().setThumbMode(false);   // BLX: volta ao estado ARM
            core.setProgramCounter(target & ~3); // e alinha o destino à palavra
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

    /// `DLS`/`WLS`/`DLSTP`/`WLSTP` (perfil M, B15.6/B16.15) — `trans_DLS`/`trans_WLS` do QEMU. `WLS*`
    /// (`hasSkipBranch`) com `rn==0` só desvia para `target`: NÃO grava `LR` nem `LTPSIZE` (o loop
    /// nunca começa). Nos demais casos `LR = rn` (lido ANTES de sobrescrever `LR` — cobre o caso
    /// raro/válido de `rn==LR`, ver Armadilha 4 da task) e, nas formas `*TP`, `LTPSIZE = size`.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeLoopStart(ArmCore core, IrOp.LoopStart loopStart) {
        if (!core.cpsr().evalCond(loopStart.condition())) {
            return false;
        }
        int count = core.register(loopStart.rn());
        if (loopStart.hasSkipBranch() && count == 0) {
            core.setProgramCounter(loopStart.target());
            return true;
        }
        core.setRegister(LOOP_COUNTER_REGISTER, count);
        if (loopStart.ltpsize() != IrOp.LoopStart.NO_LTPSIZE) {
            core.fpscr().setLtpsize(loopStart.ltpsize());
        }
        return false;
    }

    /// `LE`/`LETP` (perfil M, B15.6/B16.15) — `trans_LE` do QEMU: `forever` (`f=1`) desvia
    /// incondicionalmente sem tocar `LR`; senão `LR` (não-assinado) `<=` decremento sai SEM
    /// decrementar (checagem ANTES do decremento) e, no `LETP`, restaura `LTPSIZE = 4`; senão
    /// `LR -= decremento` e desvia de volta. Decremento = `1` (`LE`) ou `1 << (4 - LTPSIZE)` (`LETP`).
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeLoopEnd(ArmCore core, IrOp.LoopEnd loopEnd) {
        if (!core.cpsr().evalCond(loopEnd.condition())) {
            return false;
        }
        if (loopEnd.forever()) {
            core.setProgramCounter(loopEnd.target());
            return true;
        }
        int decrement = loopEnd.tailPredicated()
                ? 1 << (FpscrRegister.LTPSIZE_NONE - core.fpscr().ltpsize())
                : 1;
        int counter = core.register(LOOP_COUNTER_REGISTER);
        if (Integer.compareUnsigned(counter, decrement) <= 0) {
            if (loopEnd.tailPredicated()) {
                core.fpscr().setLtpsize(FpscrRegister.LTPSIZE_NONE);
            }
            return false;
        }
        core.setRegister(LOOP_COUNTER_REGISTER, counter - decrement);
        core.setProgramCounter(loopEnd.target());
        return true;
    }
}
