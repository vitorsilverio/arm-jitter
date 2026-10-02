package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64BreakpointException;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState;
import dev.vitorsilverio.armjitter.core64.Aarch64HypervisorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64SecureMonitorCallException;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.core64.Aarch64UndefinedInstructionException;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.SystemOp64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;

/// Semântica das operações de sistema A64 ({@link SystemOp64}): `SVC`/`HVC`/`SMC`, `MSR`/`MRS`,
/// instruções de sistema, `ERET`, `BRK` e controle do modo streaming.
///
/// Métodos estáticos sem estado, alcançados pela ponte {@link dev.vitorsilverio.armjitter.ir64.Ir64Op#execute}
/// de cada record (task E15.4); os corpos vieram de {@link Ir64BlockExecutor} sem alteração.
public final class Ir64SystemExecutor {
    /// Bit `I` dentro do `imm4` de `DAIFSet`/`DAIFClr` (`ARM DDI 0487`, ordem `D:A:I:F` — `I` é a
    /// posição `1`, MESMA convenção de `PstateRegister#irqDisabled` para máscara de IRQ). Único
    /// bit deste grupo com efeito observável neste emulador (B8.3, ver
    /// {@link dev.vitorsilverio.armjitter.ir64.SystemOp64.InterruptMask}).
    private static final int DAIF_MASK_BIT_I = 1 << 1;

    private Ir64SystemExecutor() {
    }

    /// Executa {@link SystemOp64.Svc}.
    public static boolean executeSvc(Aarch64Core core, SystemOp64.Svc op) {
        core.svcHandler().handle(core, op.immediate());
        return false;
    }

    /// `MRS`/`MSR (register)` (B6.6.1) — delega ao {@link Aarch64SystemRegisterBus} instalado
    /// (D2 da task); registrador sem hospedeiro que o {@link Aarch64SystemRegisterBus#handles}
    /// devolve `false` lança {@link UnsupportedOperationException} aqui mesmo (mesmo padrão de
    /// "sem hospedeiro" de {@link Aarch64SystemRegisterBus#none()} — checado explicitamente antes
    /// de chamar `read`/`write`, não só confiando na exceção default deles). Não existe forma
    /// `W` (ver {@link SystemOp64.SystemRegister} javadoc): `MRS` sempre grava `X` completo via
    /// {@link Aarch64Core#setX}; `MSR` sempre lê `X` completo via {@link Aarch64Core#x} (que já
    /// devolve `0` para `rt == 31`, `XZR`).
    public static boolean executeSystemRegister(Aarch64Core core, SystemOp64.SystemRegister op) {
        // B6.6.7: identidades da CPU (CurrentEL/MPIDR_EL1/MIDR_EL1/ID_AA64*/TPIDR_EL1) são
        // resolvidas DIRETO pelo core, sem passar pelo `Aarch64SystemRegisterBus` — checado
        // primeiro, mesmo quando um hospedeiro real está instalado (essas identidades nunca são
        // responsabilidade do hospedeiro, ver javadoc de `Aarch64SystemRegisterId`).
        if (core.handlesSystemRegisterIntrinsically(op.register())) {
            if (op.read()) {
                core.setX(op.rt(), core.readIntrinsicSystemRegister(op.register()));
            } else {
                core.writeIntrinsicSystemRegister(op.register(), core.x(op.rt()));
            }
            return false;
        }
        Aarch64SystemRegisterBus bus = core.systemRegisterBus();
        if (!bus.handles(op.register())) {
            throw new UnsupportedOperationException(
                    "AArch64: registrador de sistema sem hospedeiro instalado: " + op.register());
        }
        if (op.read()) {
            core.setX(op.rt(), bus.read(op.register()));
        } else {
            bus.write(op.register(), core.x(op.rt()));
        }
        return false;
    }

    /// `AT S1E1R`/`S1E1W`/`S1E0R`/`S1E0W` (B10.6) — delega inteiramente ao
    /// {@link Aarch64SystemRegisterBus} instalado; um barramento sem MMU (default, sem
    /// {@link Aarch64SystemRegisterBus#handles} — o método nem checa isso, ao contrário de
    /// {@link #executeSystemRegister}) lança {@link UnsupportedOperationException} direto do default
    /// de {@link Aarch64SystemRegisterBus#addressTranslate}. `rt=31` (`XZR`) lê `0` via
    /// {@link Aarch64Core#x}, mesma convenção de {@link SystemOp64.SystemRegister}. Sem escrita em
    /// registrador geral: o resultado vai só para `PAR_EL1`, dentro do bus.
    public static boolean executeAddressTranslate(Aarch64Core core, SystemOp64.AddressTranslate op) {
        long va = core.x(op.rt());
        core.systemRegisterBus().addressTranslate(op.form(), va);
        return false;
    }

    /// `TLBI VMALLE1`/`TLBI VMALLE1IS`/`DSB`/`ISB`/`DMB` (B6.6.3): a barreira é sempre NOP; `TLBI`
    /// delega em {@link Aarch64SystemRegisterBus#invalidateTlbAll()} — sem checar
    /// {@link Aarch64SystemRegisterBus#handles} (diferente de {@link #executeSystemRegister}),
    /// já que o método tem default NOP no barramento vazio (mesma disciplina "sem hospedeiro =
    /// sem TLB para invalidar", não uma falta arquitetural).
    public static boolean executeSystemInstruction(Aarch64Core core, SystemOp64.SystemInstruction op) {
        switch (op.opcode()) {
            case TLBI_ALL -> core.systemRegisterBus().invalidateTlbAll();
            case BARRIER, NOP_HINT, CACHE_MAINTENANCE_NOP, PSTATE_FIELD_NOP, MAINTENANCE_UNMODELED_NOP -> {
                /* NOP observável — sem cache/pipeline/event-stream/campo de PSTATE modelado. */
            }
            case INSTRUCTION_CACHE_INVALIDATE_ALL -> core.invalidateInstructionCacheAll();
            case INSTRUCTION_CACHE_INVALIDATE_BY_VA ->
                    core.invalidateInstructionCacheByVirtualAddress(core.x(op.rt()));
            case WFI -> core.setSleepState(CpuSleepState.HALTED);
            case CLEAR_EXCLUSIVE -> core.clearExclusiveMonitor();
        }
        return false;
    }

    /// `MSR (immediate) DAIFSet`/`DAIFClr` (B8.3) — só o bit `I` de `DAIF` tem efeito neste
    /// emulador (`D`/`A`/`F` ignorados, ver javadoc de {@link SystemOp64.InterruptMask}).
    public static boolean executeInterruptMask(Aarch64Core core, SystemOp64.InterruptMask op) {
        if ((op.mask() & DAIF_MASK_BIT_I) != 0) {
            core.pstate().setIrqDisabled(op.set());
        }
        return false;
    }

    /// `SMSTART`/`SMSTOP` (`MSR SVCRSM/SVCRZA/SVCRSMZA, #imm`, B18.2). `CheckSMEAccess` primeiro: sem
    /// permissão a instrução trapa (`EC=0x1D`, `SMTC=0`) em vez de executar, e o PC já é o do vetor.
    /// Depois liga/desliga os bits pedidos por {@link Aarch64Core#setSvcr}, o único ponto que aplica
    /// os efeitos destrutivos. O bit não citado pelo alias (ex.: `ZA` em `SVCRSM`) fica como está.
    public static boolean executeStreamingModeControl(Aarch64Core core, SystemOp64.StreamingModeControl op) {
        if (!core.smeEnabledCheck(op.instructionAddress())) {
            return true;
        }
        long selected = (op.streamingMode() ? Aarch64Core.SVCR_SM_BIT : 0L)
                | (op.za() ? Aarch64Core.SVCR_ZA_BIT : 0L);
        core.setSvcr(op.enable() ? core.svcr() | selected : core.svcr() & ~selected);
        return false;
    }

    /// {@link Ir64Op.StreamingRestricted} (B18.2): `UNDEFINED` quando a restrição do modo streaming se
    /// aplica (`PSTATE.SM = 1` sem `FEAT_SME_FA64` efetivo); senão executa a operação embrulhada.
    public static boolean executeStreamingRestricted(Aarch64Core core, Ir64Op.StreamingRestricted op) {
        if (core.streamingRestrictionApplies()) {
            throw new Aarch64UndefinedInstructionException();
        }
        return op.inner().execute(core);
    }

    /// `BRK` (B8.3) — sempre lança, capturado por {@link Ir64BlockExecutor#step}/{@link Ir64BlockExecutor#executeBlock} no MESMO
    /// ponto que {@link MemoryTranslationException64} (ver os `catch` ali).
    public static boolean executeBreakpoint(SystemOp64.Breakpoint op) {
        throw new Aarch64BreakpointException(op.immediate());
    }

    /// `HLT` sem estado de debug (B8.3) — mesmo contrato de {@link #executeBreakpoint}.
    public static boolean executeUndefinedInstructionTrap() {
        throw new Aarch64UndefinedInstructionException();
    }

    /// `HVC`/`SMC` (B10.4/B10.5): sempre lança, capturada por {@link Ir64BlockExecutor#step}/{@link Ir64BlockExecutor#executeBlock}
    /// no MESMO ponto que {@link Aarch64BreakpointException}/{@link Aarch64UndefinedInstructionException}
    /// (precisa do endereço da própria instrução, só disponível ali — mesmo motivo de
    /// {@link #executeBreakpoint}). O antigo stub `PSCI_RET_NOT_SUPPORTED` de `SMC` (pré-B10.5)
    /// foi REMOVIDO — `SMC` agora entra em EL3 de verdade via
    /// {@link Aarch64Core#enterSecureMonitorCall}, mesmo mecanismo de `HVC`/
    /// {@link Aarch64Core#enterHypervisorCall}.
    public static boolean executePrivilegedCall(SystemOp64.PrivilegedCall op) {
        if (op.isHvc()) {
            throw new Aarch64HypervisorCallException();
        }
        throw new Aarch64SecureMonitorCallException();
    }

    /// `ERET` (B6.6.4, e B6.6.7 para o bit `I`; generalizado em B10.1 para os 4 níveis): `PC←
    /// ELR_ELx`, `PSTATE.{N,Z,C,V,I}←SPSR_ELx`, sai para o nível codificado em `SPSR_ELx.M[3:0]`
    /// (`x` é o nível ATUAL — `ERET` sempre lê o PRÓPRIO banco de quem o executa, nunca o de um
    /// nível fixo; o nível de DESTINO não é sempre "um abaixo do atual", ver
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel#fromSpsrValue}) — mesma
    /// ordem do precedente 32-bit (`SUBS PC,LR,#8` equivalente, mas automático aqui: A64 não
    /// precisa de subtração porque `ELR_ELx` já é o endereço exato de retomada, sem o viés `+4`/
    /// `+8` do LR bancado do ARM32).
    public static boolean executeExceptionReturn(Aarch64Core core, SystemOp64.ExceptionReturn op) {
        Aarch64ExceptionState exceptionState = core.exceptionState();
        Aarch64ExceptionLevel source = exceptionState.currentEl();
        long returnAddress = exceptionState.elr(source);
        long rawSpsr = exceptionState.spsr(source);
        core.pstate().setFromSpsrFormat(rawSpsr);
        exceptionState.setCurrentEl(Aarch64ExceptionLevel.fromSpsrValue(rawSpsr));
        core.narrowScalableStateToCurrentVectorLength();
        core.setProgramCounter(returnAddress);
        return true;
    }
}
