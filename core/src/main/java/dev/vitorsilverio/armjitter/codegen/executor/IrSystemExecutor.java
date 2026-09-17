package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdLanes;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.FpscrRegister;
import dev.vitorsilverio.armjitter.core.MProfileException;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.core.MveVptState;
import dev.vitorsilverio.armjitter.core.VfpRegisters;
import dev.vitorsilverio.armjitter.core.VprRegister;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.swi.CpuState;

/// Executa PSR, SWI, coprocessador e instruções indefinidas da IR interpretada.
public final class IrSystemExecutor {
    private final IrExecutionSupport support;

    IrSystemExecutor(IrExecutionSupport support) {
        this.support = support;
    }

    public void executePsrTransfer(ArmCore core, IrOp.PsrTransfer transfer) {
        if (!core.cpsr().evalCond(transfer.condition())) {
            return;
        }
        CpuMode psrMode = core.mode();
        boolean hasSPSR = psrMode != CpuMode.USER && psrMode != CpuMode.SYSTEM;
        if (transfer.read()) {
            int value = (transfer.spsr() && hasSPSR) ? core.spsr(psrMode) : core.cpsr().get();
            core.setRegister(transfer.register(), value);
            return;
        }
        int value = transfer.immediateOperand()
                ? transfer.immediate()
                : support.registerValue(core, transfer.register(), transfer.registerValueOverride());
        if (transfer.spsr()) {
            if (hasSPSR) {
                core.setSpsr(psrMode, support.mergePsr(core.spsr(psrMode), value, transfer.fieldMask()));
            }
        } else {
            core.setCpsr(support.mergePsr(core.cpsr().get(), value, support.cpsrWriteFieldMask(core, transfer.fieldMask())));
        }
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeSwi(ArmCore core, IrOp.Swi swi, int sequentialPc) {
        if (!core.cpsr().evalCond(swi.condition())) {
            return false;
        }
        core.setProgramCounter(sequentialPc);
        if (!core.exceptionModel().handlesSupervisorCall() && core.swiDispatcher().canDispatch(swi.immediate())) {
            CpuState next = core.swiDispatcher().dispatch(swi.immediate(), core.toCpuState());
            core.apply(next);
            return true;
        }
        core.requestException(ArmException.SWI);
        return true;
    }

    /// `HVC` (B9.8.2): entra em Hyp mode, exceto em modo `USER` (`UNDEFINED` — checagem em tempo
    /// de EXECUÇÃO, já que o decode em si é independente de modo). Mesma convenção de PC que
    /// {@link #executeSwi}: `sequentialPc` (endereço da PRÓXIMA instrução) é gravado ANTES de
    /// pedir a exceção — {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} usa o PC
    /// corrente como base de retorno tanto para `SWI` quanto para `HVC`.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeHvc(ArmCore core, IrOp.Hvc hvc, int sequentialPc) {
        if (!core.cpsr().evalCond(hvc.condition())) {
            return false;
        }
        core.setProgramCounter(sequentialPc);
        if (core.mode() == CpuMode.USER) {
            core.requestException(ArmException.UNDEFINED);
        } else {
            core.requestException(ArmException.HVC);
        }
        return true;
    }

    /// `SMC` (B9.8.3): entra em Monitor mode, exceto em modo `USER` (`UNDEFINED` — mesma checagem
    /// em tempo de EXECUÇÃO de {@link #executeHvc}). Mesma convenção de PC sequencial.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeSmc(ArmCore core, IrOp.Smc smc, int sequentialPc) {
        if (!core.cpsr().evalCond(smc.condition())) {
            return false;
        }
        core.setProgramCounter(sequentialPc);
        if (core.mode() == CpuMode.USER) {
            core.requestException(ArmException.UNDEFINED);
        } else {
            core.requestException(ArmException.SMC);
        }
        return true;
    }

    /// `ERET` (B9.8.4, A32): retorna de exceção, exceto em modo `USER` (`UNDEFINED` — mesma
    /// checagem em tempo de EXECUÇÃO de {@link #executeHvc}/{@link #executeSmc}). Ao contrário de
    /// `HVC`/`SMC`, não entra em exceção nova — é uma instrução de RETORNO pura (mesma categoria de
    /// `RFE`, {@code IrTransferExecutor#executeReturnFromException}): `PC`←`ELR_hyp` (Hyp mode) ou
    /// `LR` do banco ativo (qualquer outro modo), `CPSR`←SPSR do modo ativo. O registrador de
    /// retorno é lido ANTES de restaurar o CPSR — {@link IrExecutionSupport#restoreCpsrFromCurrentSpsr}
    /// troca de modo/banco, então ler depois corromperia a leitura do banco de ORIGEM.
    ///
    /// @return {@code true} (sempre altera o PC — via `UNDEFINED` ou via retorno real)
    public boolean executeEret(ArmCore core, IrOp.Eret eret, int sequentialPc) {
        if (!core.cpsr().evalCond(eret.condition())) {
            return false;
        }
        if (core.mode() == CpuMode.USER) {
            core.setProgramCounter(sequentialPc);
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        int returnAddress = core.mode() == CpuMode.HYP ? core.elrHyp() : core.register(14);
        support.restoreCpsrFromCurrentSpsr(core);
        support.alignAndSetPc(core, returnAddress);
        return true;
    }

    /// `MRS` bancado (B9.8.5): entra em `UNDEFINED` em modo `USER` (mesma checagem em tempo de
    /// EXECUÇÃO de {@link #executeHvc}/{@link #executeSmc}/{@link #executeEret}); senão lê o valor
    /// do modo ALVO (não o ativo) via `SPSR`/`ELR_hyp`/registrador bancado, conforme os campos já
    /// resolvidos pelo decoder. Sem checagem de Secure/Monitor state (simplificação da escada
    /// B9.8, ver `b9.8-plano-hyp-monitor-32bit.md`).
    ///
    /// @return {@code true} quando o PC foi alterado pela operação (só no caso `UNDEFINED`)
    public boolean executeMrsBank(ArmCore core, IrOp.MrsBank mrs, int sequentialPc) {
        if (!core.cpsr().evalCond(mrs.condition())) {
            return false;
        }
        if (core.mode() == CpuMode.USER) {
            core.setProgramCounter(sequentialPc);
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        int value = mrs.spsr()
                ? core.spsr(mrs.targetMode())
                : mrs.elrHyp() ? core.elrHyp() : core.bankedRegister(mrs.targetMode(), mrs.bankedRegister());
        core.setRegister(mrs.armRegister(), value);
        return false;
    }

    /// `MSR` bancado (B9.8.5): mesma checagem de modo `USER` de {@link #executeMrsBank}; senão
    /// escreve o registrador geral de origem no `SPSR`/`ELR_hyp`/registrador bancado do modo ALVO.
    ///
    /// @return {@code true} quando o PC foi alterado pela operação (só no caso `UNDEFINED`)
    public boolean executeMsrBank(ArmCore core, IrOp.MsrBank msr, int sequentialPc) {
        if (!core.cpsr().evalCond(msr.condition())) {
            return false;
        }
        if (core.mode() == CpuMode.USER) {
            core.setProgramCounter(sequentialPc);
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        int value = core.register(msr.armRegister());
        if (msr.spsr()) {
            core.setSpsr(msr.targetMode(), value);
        } else if (msr.elrHyp()) {
            core.setElrHyp(value);
        } else {
            core.setBankedRegister(msr.targetMode(), msr.bankedRegister(), value);
        }
        return false;
    }

    /// @return {@code true} quando o PC foi alterado pela operação (sempre — `BKPT` é
    /// incondicional, sem campo de condição em nenhum dos dois modos)
    public boolean executeBreakpoint(ArmCore core, IrOp.Breakpoint bkpt, int sequentialPc) {
        core.setProgramCounter(sequentialPc);
        if (core.bkptDispatcher().canDispatch(bkpt.immediate())) {
            CpuState next = core.bkptDispatcher().dispatch(bkpt.immediate(), core.toCpuState());
            core.apply(next);
        } else {
            core.requestException(ArmException.UNDEFINED);
        }
        return true;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeCoprocessor(ArmCore core, IrOp.Coprocessor cp) {
        if (!core.cpsr().evalCond(cp.condition())) {
            return false;
        }
        CoprocessorBus bus = core.coprocessorBus();
        if (!bus.handles(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2())) {
            core.setProgramCounter(cp.sequentialPc());
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (cp.load()) {
            int value = bus.read(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2());
            if (cp.register() == 15) {
                core.cpsr().setNzcv((value & 0x8000_0000) != 0, (value & 0x4000_0000) != 0,
                        (value & 0x2000_0000) != 0, (value & 0x1000_0000) != 0);
            } else {
                core.setRegister(cp.register(), value);
            }
        } else {
            bus.write(cp.coprocessor(), cp.opcode1(), cp.crn(), cp.crm(), cp.opcode2(),
                    core.register(cp.register()));
        }
        return false;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeCoprocessorDouble(ArmCore core, IrOp.CoprocessorDouble cp) {
        if (!core.cpsr().evalCond(cp.condition())) {
            return false;
        }
        CoprocessorBus bus = core.coprocessorBus();
        if (!bus.handlesDouble(cp.coprocessor(), cp.opcode1(), cp.crm())) {
            core.setProgramCounter(cp.sequentialPc());
            core.requestException(ArmException.UNDEFINED);
            return true;
        }
        if (cp.load()) {
            long value = bus.readDouble(cp.coprocessor(), cp.opcode1(), cp.crm());
            core.setRegister(cp.rt(), (int) value);
            core.setRegister(cp.rt2(), (int) (value >>> 32));
        } else {
            bus.writeDouble(cp.coprocessor(), cp.opcode1(), cp.crm(), core.register(cp.rt()), core.register(cp.rt2()));
        }
        return false;
    }

    /// @return {@code true} quando o PC foi alterado pela operação
    public boolean executeUndefined(ArmCore core, IrOp.Undefined undefined) {
        if (!core.cpsr().evalCond(undefined.condition())) {
            return false;
        }
        core.setProgramCounter(undefined.sequentialPc());
        core.requestException(ArmException.UNDEFINED);
        return true;
    }

    /// `CPS`/`CPSIE`/`CPSID` (ARMv6): reusa {@link ArmCore#setCpsr} — o mesmo caminho de troca de
    /// banco que `MSR`/entrada de exceção usam — para que a troca de modo (quando presente)
    /// rebanque os registradores corretamente. UNPREDICTABLE em modo User: tratado como NOP.
    public void executeChangeProcessorState(ArmCore core, IrOp.ChangeProcessorState cps) {
        if (!core.cpsr().evalCond(cps.condition())) {
            return;
        }
        // Perfil M (B7.4): `CPSIE i`/`CPSID i` (16-bit Thumb) tocam SÓ PRIMASK; `CPSIE f`/`CPSID f`
        // tocam SÓ FAULTMASK (o decoder só deixa `f` chegar aqui com M_FAULT_MASKING presente). O
        // perfil M não tem o modo User da forma A-profile, nem os bits A/mode — ignorados. Este
        // ramo precede o resto (A-profile) e o preserva bit a bit.
        if (core.exceptionModel() instanceof MProfileExceptionModel model) {
            if (cps.changeI()) {
                model.setPrimask(cps.enable() ? 0 : 1);
            }
            if (cps.changeF()) {
                model.setFaultmask(cps.enable() ? 0 : 1);
            }
            return;
        }
        if (core.mode() == CpuMode.USER) {
            return;
        }
        int value = core.cpsr().get();
        if (cps.changeFlags()) {
            int mask = (cps.changeA() ? CpsrRegister.ABORT_DISABLE_FLAG : 0)
                    | (cps.changeI() ? CpsrRegister.IRQ_DISABLE_FLAG : 0)
                    | (cps.changeF() ? CpsrRegister.FIQ_DISABLE_FLAG : 0);
            value = cps.enable() ? (value & ~mask) : (value | mask);
        }
        if (cps.changeMode()) {
            value = (value & ~0b11111) | cps.mode();
        }
        core.setCpsr(value);
    }

    /// `MRS`/`MSR` na forma SYSm do perfil M (B7.4): delega leitura/escrita do registrador especial
    /// ao {@link MProfileExceptionModel} (dono de MSP/PSP/PRIMASK/BASEPRI/FAULTMASK/CONTROL). O cast
    /// é seguro: esta op só é produzida pelo decoder sob {@code ArmFeature.M_PROFILE}, e todo core
    /// com essa feature roda com um {@code MProfileExceptionModel} instalado.
    public void executeMProfileSystemRegister(ArmCore core, IrOp.MProfileSystemRegister op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        MProfileExceptionModel model = (MProfileExceptionModel) core.exceptionModel();
        if (op.read()) {
            core.setRegister(op.armRegister(), model.readSystemRegister(core, op.sysm()));
        } else {
            model.writeSystemRegister(core, op.sysm(), core.register(op.armRegister()));
        }
    }

    /// `NOCP`/`NOCP_8_1` (perfil M, B15.2): seta `UFSR.NOCP` e entra em `USAGE_FAULT` direto no
    /// {@link MProfileExceptionModel} — sem passar por `ArmException` (que é compartilhado com o
    /// perfil A/R, onde `NOCP` não faz sentido nenhum, ver Armadilha 2 da task). O cast é seguro
    /// pelo mesmo motivo de {@link #executeMProfileSystemRegister}: esta op só é produzida sob
    /// {@code ArmFeature.M_PROFILE}.
    ///
    /// @return sempre {@code true} — `enterException` sempre muda o PC para o vetor do handler.
    public boolean executeNocp(ArmCore core, IrOp.Nocp op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        MProfileExceptionModel model = (MProfileExceptionModel) core.exceptionModel();
        model.setUsageFaultNocp();
        model.enterException(core, MProfileException.USAGE_FAULT);
        return true;
    }

    /// `VLLDM`/`VLSTM` (perfil M, B15.5): sem FPU real, sempre `UNDEFINED` — seta `UFSR.UNDEFINSTR`
    /// (diferente do `UFSR.NOCP` de {@link #executeNocp}: a arquitetura real prioriza estas 2 formas
    /// ANTES do `NOCP` genérico, ver Javadoc de {@code IrOp.VlldmVlstm}) e entra em `USAGE_FAULT`
    /// direto no {@link MProfileExceptionModel}, mesmo cast seguro de {@link #executeNocp}.
    ///
    /// @return sempre {@code true} — `enterException` sempre muda o PC para o vetor do handler.
    public boolean executeVlldmVlstm(ArmCore core, IrOp.VlldmVlstm op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        MProfileExceptionModel model = (MProfileExceptionModel) core.exceptionModel();
        model.setUsageFaultUndefinstr();
        model.enterException(core, MProfileException.USAGE_FAULT);
        return true;
    }

    /// `SG` (B15.4): só produzida sob `ArmFeature#M_PROFILE_SECURITY` (exclusivo do perfil M),
    /// mesmo cast direto de {@link #executeNocp} acima.
    public void executeSecureGateway(ArmCore core, IrOp.SecureGateway op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        ((MProfileExceptionModel) core.exceptionModel()).secureGateway(core);
    }

    /// `SETEND` (ARMv6): seta o bit E do CPSR. Acessos de dados subsequentes com E=1 passam a
    /// usar BE8 (task B1.8, ver {@code IrExecutionSupport#applyDataEndiannessWord}); a busca de
    /// instrução nunca é afetada.
    public void executeSetEndianness(ArmCore core, IrOp.SetEndianness setend) {
        if (!core.cpsr().evalCond(setend.condition())) {
            return;
        }
        core.cpsr().setBigEndian(setend.bigEndian());
    }

    /// `WFI` (ARMv6K hint): coloca o core em HALT (acorda com {@code setInterruptLine(true)}).
    public void executeWaitForInterrupt(ArmCore core, IrOp.WaitForInterrupt wfi) {
        if (!core.cpsr().evalCond(wfi.condition())) {
            return;
        }
        core.halt();
    }

    /// `DMB`/`DSB`/`ISB` (ARMv7, Thumb-2 — B2.5): NOP observável neste core single-thread sem
    /// reordenação real de memória (premissa documentada em {@link IrOp.MemoryBarrier}). Ainda
    /// checa a condição por simetria com o resto do executor, mesmo não havendo efeito algum.
    public void executeMemoryBarrier(ArmCore core, IrOp.MemoryBarrier barrier) {
        if (!core.cpsr().evalCond(barrier.condition())) {
            return;
        }
        // Intencionalmente vazio: ver justificativa em IrOp.MemoryBarrier.
    }

    /// Grava o ITSTATE\[7:0\] do CPSR (Thumb-2 IT block, B2.4). Ver o javadoc de
    /// {@link IrOp.SetItState} para as duas origens (entrada do `IT`/avanço pós-instrução) e por
    /// que o avanço é sempre emitido com condição AL pelo lifter.
    public void executeSetItState(ArmCore core, IrOp.SetItState setItState) {
        if (!core.cpsr().evalCond(setItState.condition())) {
            return;
        }
        core.cpsr().setItState(setItState.itState());
    }

    /// `LTPSIZE` neutro (B16.2 "Não inclui": tail predication/`LOW_OVERHEAD_BRANCH` não modela
    /// este estado ainda) — passado a {@link MveVptState#elementMask} para desligar a etapa de
    /// tail predication (`ltpsize < 4` nunca é verdadeiro).
    private static final int NO_TAIL_PREDICATION_LTPSIZE = 4;

    /// Entra em `USAGE_FAULT` com `UFSR.INVSTATE` (`mve_eci_check` real: `ECI` reservado numa
    /// instrução MVE beatwise) — mesmo cast direto de {@link #executeNocp}.
    ///
    /// @return sempre `true` — `enterException` sempre muda o PC para o vetor do handler.
    private static boolean faultInvstate(ArmCore core) {
        MProfileExceptionModel model = (MProfileExceptionModel) core.exceptionModel();
        model.setUsageFaultInvstate();
        model.enterException(core, MProfileException.USAGE_FAULT);
        return true;
    }

    /// `VPST` (perfil M, B16.2, MVE/Helium): grava `VPR.MASK01`/`MASK23` via
    /// {@link MveVptState#vpstMask} — antes, valida `ECI` (`mve_eci_check` real, roda para TODA
    /// instrução MVE beatwise): reservado faulta em vez de gravar (ver {@link #faultInvstate}).
    ///
    /// @return `true` quando faultou (PC mudou) — {@link
    ///         dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} usa isto para pular o
    ///         {@link IrOp.AdvanceVpt} seguinte no mesmo bloco: o QEMU real nunca chega a
    ///         `mve_update_and_store_eci`/`mve_advance_vpt` quando `mve_eci_check` falha —
    ///         translation-time short-circuit, não comportamento de pipeline.
    public boolean executeVpst(ArmCore core, IrOp.Vpst op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        core.vpr().setValue(MveVptState.vpstMask(core.vpr().value(), eci, op.mask()));
        return false;
    }

    /// `VPNOT` (perfil M, B16.2, MVE/Helium): inverte `VPR.P0` nas lanes correspondentes aos
    /// beats já executados — `vpr ^= eciMask` afeta só os 16 bits baixos (campo `P0`, deslocamento
    /// `0`), o MESMO idioma que {@link MveVptState#advance} usa para o `invMask`. **Não pôde ser
    /// confirmado byte a byte contra `HELPER(mve_vpnot)` do QEMU real nesta rodada de spec** (não
    /// encontrado via `WebFetch`/`WebSearch` dentro do orçamento da sessão) — derivado do idioma
    /// idêntico já usado por `mve_advance_vpt` no MESMO arquivo; ver `## Resultado` da task B16.2.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeVpnot(ArmCore core, IrOp.Vpnot op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int itState = core.cpsr().itState();
        core.vpr().setValue(core.vpr().value() ^ MveVptState.eciMask(itState));
        return false;
    }

    /// `VPSEL` (perfil M, B16.2, MVE/Helium): seleciona lane a lane (byte a byte — `@2op_nosz`
    /// não tem campo `size`) entre `Qn` e `Qm` conforme `VPR.P0`, escrevendo em `Qd` só nos bytes
    /// que {@link MveVptState#elementMask} marca como ativos (`LTPSIZE` neutro, ver
    /// {@link #NO_TAIL_PREDICATION_LTPSIZE}). **Semântica derivada, não confirmada byte a byte
    /// contra `HELPER(mve_vpsel)` do QEMU real** (mesma limitação de {@link #executeVpnot}) — a
    /// derivação segue o pseudocódigo arquitetural (`Armv8-M ARM` B4.24): seleção sempre por byte,
    /// write-enable pelo `elementMask` corrente. Ver `## Resultado` da task B16.2.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeVpsel(ArmCore core, IrOp.Vpsel op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int p0 = (vpr & VprRegister.P0_MASK) >>> VprRegister.P0_SHIFT;
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        for (int byteIndex = 0; byteIndex < 16; byteIndex++) {
            if (((mask >>> byteIndex) & 1) == 0) {
                continue;
            }
            boolean selectN = ((p0 >>> byteIndex) & 1) != 0;
            long value = vfp.element(selectN ? op.qn() : op.qm(), byteIndex, 0);
            vfp.setElement(op.qd(), byteIndex, 0, value);
        }
        return false;
    }

    /// Avanço pós-instrução do `VPR`/`ECI` (perfil M, B16.2, MVE/Helium) — {@link
    /// MveVptState#advance}. Emitido incondicionalmente (G4) depois de toda instrução MVE
    /// beatwise; {@link dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} pula esta
    /// chamada quando a instrução MVE anterior no MESMO bloco já mudou o PC (fault de `ECI`
    /// reservado — ver {@link #executeVpst}), nunca quando ela só zerou o `elementMask` inteiro
    /// (predicação total, que AINDA avança, ver Javadoc de {@link IrOp.AdvanceVpt}).
    public void executeAdvanceVpt(ArmCore core, IrOp.AdvanceVpt op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        MveVptState.MveVptAdvance result = MveVptState.advance(core.vpr().value(), core.cpsr().itState());
        core.vpr().setValue(result.vpr());
        core.cpsr().setItState(result.itState());
    }

    /// `VMSR`/`VMRS` com `reg=12` (perfil M, B16.2, MVE/Helium): transfere o `VPR` bruto de/para
    /// `armRegister` — armazenamento puro, sem aliasing (ver Javadoc de {@link IrOp.VprTransfer}).
    /// NÃO avança `VPR`/`ECI` ({@link #executeAdvanceVpt}): `VMSR_VMRS` nunca é beatwise.
    public void executeVprTransfer(ArmCore core, IrOp.VprTransfer op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        if (op.read()) {
            core.setRegister(op.armRegister(), core.vpr().value());
        } else {
            core.vpr().setValue(core.register(op.armRegister()));
        }
    }

    /// `VLDR_VSTR` (perfil M, B16.3, MVE/Helium): move os 128 bits de `Qd` de/para memória, BYTE a
    /// BYTE (Armadilha 5 da task — predicação por byte de memória, nunca um único acesso de 128
    /// bits quando a máscara não é cheia), usando {@link #NO_TAIL_PREDICATION_LTPSIZE} (mesma
    /// convenção de {@link #executeVpsel}: tail predication ainda não modelada, B15.6/B16.7+).
    /// Mesma checagem de `ECI` reservado que toda instrução MVE beatwise faz ({@code
    /// mve_eci_check} real) ANTES de tocar memória/registrador — ver {@link #executeVpst}.
    ///
    /// Writeback de `Rn` é SEMPRE incondicional (G4, Armadilha 6 da task): roda mesmo quando a
    /// instrução inteira está mascarada (`elementMask == 0`) — o QEMU real trata o cálculo de
    /// endereço/writeback como efeito ESCALAR da instrução, fora do laço "por beat" que aplica a
    /// máscara.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}) — {@link
    ///         dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor} usa isto para pular o
    ///         {@link IrOp.AdvanceVpt} seguinte no mesmo bloco.
    public boolean executeMveLoadStore(ArmCore core, IrOp.MveLoadStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int base = core.register(op.rn());
        int accessAddress = op.postIndexed() ? base : base + op.offset();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        VfpRegisters vfp = core.vfp();
        for (int byteIndex = 0; byteIndex < 16; byteIndex++) {
            if (((mask >>> byteIndex) & 1) == 0) {
                continue;
            }
            int byteAddress = accessAddress + byteIndex;
            if (op.load()) {
                int value = support.read8Arm7(core, byteAddress);
                vfp.setElement(op.qd(), byteIndex, 0, value);
            } else {
                long value = vfp.element(op.qd(), byteIndex, 0);
                support.write8Arm7(core, byteAddress, (int) value);
            }
        }
        if (op.writeback()) {
            core.setRegister(op.rn(), base + op.offset());
        }
        return false;
    }

    /// `VLDSTB_H`/`VLDSTB_W`/`VLDSTH_W` (perfil M, B16.4, MVE/Helium): load que alarga (extensão de
    /// sinal/zero) ou store que estreita (truncamento), predicados por ELEMENTO (não por byte —
    /// diferente de {@link #executeMveLoadStore}, ver Javadoc de {@link IrOp.MveWideningLoadStore}).
    /// Usa {@link IrExecutionSupport#readVectorElement}/{@link IrExecutionSupport#writeVectorElement}
    /// (sem o quirk de rotação de acesso desalinhado do ARM7 — `LDR`/`LDRH`, não aplicável a
    /// elemento vetorial, mesmo precedente NEON da B13.3) em vez de {@code read8Arm7}, já que aqui o
    /// elemento tem largura real (`1`/`2` bytes), não sempre `1` como em {@link #executeMveLoadStore}.
    ///
    /// No load, distingue duas máscaras (verbatim de `DO_VLDR`, `target/arm/tcg/mve_helper.c`):
    /// {@link MveVptState#eciMask} sozinho decide se a lane é tocada (beat abandonado = não
    /// tocada, comportamento UNKNOWN implementado como preservar); dentro disso,
    /// {@link MveVptState#elementMask} (que já inclui `eciMask`) decide entre carregar de verdade
    /// ou gravar ZERO (predicado `VPT` falhou, mas o beat está ativo). No store, só
    /// {@link MveVptState#elementMask} importa (mesmo padrão de {@link #executeMveLoadStore}).
    ///
    /// Writeback de `Rn` é SEMPRE incondicional (G4), mesma regra de {@link #executeMveLoadStore}.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveWideningLoadStore(ArmCore core, IrOp.MveWideningLoadStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int base = core.register(op.rn());
        int accessAddress = op.postIndexed() ? base : base + op.offset();
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int fullMask = MveVptState.elementMask(vpr, itState, NO_TAIL_PREDICATION_LTPSIZE, 0);
        int eciMask = MveVptState.eciMask(itState);
        VfpRegisters vfp = core.vfp();
        int registerSizeLog2 = op.registerSizeLog2();
        int memorySizeLog2 = op.memorySizeLog2();
        int memoryElementBytes = 1 << memorySizeLog2;
        int elementCount = 16 >>> registerSizeLog2;
        int address = accessAddress;
        for (int e = 0; e < elementCount; e++) {
            int b = e << registerSizeLog2;
            if (op.load()) {
                if (((eciMask >>> b) & 1) != 0) {
                    long value;
                    if (((fullMask >>> b) & 1) != 0) {
                        long raw = support.readVectorElement(core, address, memorySizeLog2);
                        value = op.signed() ? AdvSimdLanes.signExtend(raw, memorySizeLog2) : raw;
                        value = AdvSimdLanes.truncate(value, registerSizeLog2);
                    } else {
                        value = 0;
                    }
                    vfp.setElement(op.qd(), e, registerSizeLog2, value);
                }
            } else if (((fullMask >>> b) & 1) != 0) {
                long narrowed = AdvSimdLanes.truncate(vfp.element(op.qd(), e, registerSizeLog2), memorySizeLog2);
                support.writeVectorElement(core, address, memorySizeLog2, narrowed);
                core.notifyOrdinaryWrite(address, memoryElementBytes);
            }
            address += memoryElementBytes;
        }
        if (op.writeback()) {
            core.setRegister(op.rn(), base + op.offset());
        }
        return false;
    }

    /// Log2 do tamanho de um registrador `Q` inteiro em palavras de 32 bits (`16 / 4`) — usado só
    /// pelo laço de 4 iterações de {@link #executeMveGatherScatterOffset} no ramo `registerSizeLog2
    /// == 3` (`DO_VLDR64_SG`/`DO_VSTR64_SG` reais SEMPRE iteram em passos de 4 bytes, mesmo movendo
    /// dados de 64 bits — ver Javadoc de {@link IrOp.MveGatherScatterOffset#registerSizeLog2}).
    private static final int DOUBLEWORD_LOG2 = 3;
    private static final int WORD_LOG2 = 2;

    /// `VLDR_S_sg`/`VLDR_U_sg`/`VSTR_sg` (perfil M, B16.5, MVE/Helium): gather/scatter por vetor de
    /// offsets. Dois ramos, verbatim do QEMU real (`target/arm/tcg/mve_helper.c`): `registerSizeLog2
    /// < 3` usa `DO_VLDR_SG`/`DO_VSTR_SG` (offset lido de `qm` na MESMA largura do registrador,
    /// endereço por lane independente); `registerSizeLog2 == 3` usa `DO_VLDR64_SG`/`DO_VSTR64_SG`
    /// (par de acessos de 32 bits, offset lido só das lanes PARES de 32 bits de `qm` — Javadoc do
    /// `IrOp` explica o porquê). Mesma disciplina de duas máscaras de {@link
    /// #executeMveWideningLoadStore}: `eciMask` sozinho decide se a lane é tocada; `elementMask`
    /// decide entre carregar de verdade ou gravar ZERO (load) — no store só `elementMask` importa.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveGatherScatterOffset(ArmCore core, IrOp.MveGatherScatterOffset op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int base = core.register(op.rn());
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int fullMask = MveVptState.elementMask(vpr, itState, NO_TAIL_PREDICATION_LTPSIZE, 0);
        int eciMask = MveVptState.eciMask(itState);
        VfpRegisters vfp = core.vfp();
        int memorySizeLog2 = op.memorySizeLog2();
        int registerSizeLog2 = op.registerSizeLog2();
        if (registerSizeLog2 == DOUBLEWORD_LOG2) {
            for (int e = 0; e < 4; e++) {
                int b = e << WORD_LOG2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                int offsetLane = e & ~1;
                long offsetValue = vfp.element(op.qm(), offsetLane, WORD_LOG2);
                int addr = base + (op.offsetScaled()
                        ? (int) (offsetValue << memorySizeLog2) : (int) offsetValue);
                addr += 4 * (e & 1);
                if (op.load()) {
                    long value = ((fullMask >>> b) & 1) != 0
                            ? support.readVectorElement(core, addr, WORD_LOG2) : 0;
                    vfp.setElement(op.qd(), e, WORD_LOG2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long value = vfp.element(op.qd(), e, WORD_LOG2);
                    support.writeVectorElement(core, addr, WORD_LOG2, value);
                    core.notifyOrdinaryWrite(addr, 1 << WORD_LOG2);
                }
            }
        } else {
            int elementCount = 16 >>> registerSizeLog2;
            for (int e = 0; e < elementCount; e++) {
                int b = e << registerSizeLog2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                long offsetValue = vfp.element(op.qm(), e, registerSizeLog2);
                int addr = base + (op.offsetScaled()
                        ? (int) (offsetValue << memorySizeLog2) : (int) offsetValue);
                if (op.load()) {
                    long value;
                    if (((fullMask >>> b) & 1) != 0) {
                        long raw = support.readVectorElement(core, addr, memorySizeLog2);
                        value = op.signedLoad() ? AdvSimdLanes.signExtend(raw, memorySizeLog2) : raw;
                        value = AdvSimdLanes.truncate(value, registerSizeLog2);
                    } else {
                        value = 0;
                    }
                    vfp.setElement(op.qd(), e, registerSizeLog2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long narrowed = AdvSimdLanes.truncate(
                            vfp.element(op.qd(), e, registerSizeLog2), memorySizeLog2);
                    support.writeVectorElement(core, addr, memorySizeLog2, narrowed);
                    core.notifyOrdinaryWrite(addr, 1 << memorySizeLog2);
                }
            }
        }
        return false;
    }

    /// `VLDRW_sg_imm`/`VLDRD_sg_imm`/`VSTRW_sg_imm`/`VSTRD_sg_imm` (perfil M, B16.5, MVE/Helium):
    /// gather/scatter com base vetorial `qm` (cada lane já é um ENDEREÇO) mais {@code op.offset()}
    /// escalar somado a todas as lanes — mesma fórmula de endereço de {@link
    /// #executeMveGatherScatterOffset} com os papéis de "base"/"offset" trocados (`do_ldst_sg_imm`
    /// real). Writeback é POR LANE, gated só por `eciMask` (roda mesmo quando `elementMask` zera a
    /// lane) — diferente do writeback escalar único de {@link #executeMveLoadStore}.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveGatherScatterImmediate(ArmCore core, IrOp.MveGatherScatterImmediate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int vpr = core.vpr().value();
        int itState = core.cpsr().itState();
        int fullMask = MveVptState.elementMask(vpr, itState, NO_TAIL_PREDICATION_LTPSIZE, 0);
        int eciMask = MveVptState.eciMask(itState);
        VfpRegisters vfp = core.vfp();
        int sizeLog2 = op.sizeLog2();
        if (sizeLog2 == DOUBLEWORD_LOG2) {
            for (int e = 0; e < 4; e++) {
                int b = e << WORD_LOG2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                int offsetLane = e & ~1;
                int laneBase = (int) vfp.element(op.qm(), offsetLane, WORD_LOG2);
                int addr = laneBase + op.offset() + (4 * (e & 1));
                if (op.load()) {
                    long value = ((fullMask >>> b) & 1) != 0
                            ? support.readVectorElement(core, addr, WORD_LOG2) : 0;
                    vfp.setElement(op.qd(), e, WORD_LOG2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long value = vfp.element(op.qd(), e, WORD_LOG2);
                    support.writeVectorElement(core, addr, WORD_LOG2, value);
                    core.notifyOrdinaryWrite(addr, 1 << WORD_LOG2);
                }
                if (op.writeback() && (e & 1) != 0) {
                    vfp.setElement(op.qm(), offsetLane, WORD_LOG2, Integer.toUnsignedLong(addr - 4));
                }
            }
        } else {
            int elementCount = 16 >>> sizeLog2;
            for (int e = 0; e < elementCount; e++) {
                int b = e << sizeLog2;
                if (((eciMask >>> b) & 1) == 0) {
                    continue;
                }
                int laneBase = (int) vfp.element(op.qm(), e, sizeLog2);
                int addr = laneBase + op.offset();
                if (op.load()) {
                    long value = ((fullMask >>> b) & 1) != 0
                            ? support.readVectorElement(core, addr, sizeLog2) : 0;
                    vfp.setElement(op.qd(), e, sizeLog2, value);
                } else if (((fullMask >>> b) & 1) != 0) {
                    long value = vfp.element(op.qd(), e, sizeLog2);
                    support.writeVectorElement(core, addr, sizeLog2, value);
                    core.notifyOrdinaryWrite(addr, 1 << sizeLog2);
                }
                if (op.writeback()) {
                    vfp.setElement(op.qm(), e, sizeLog2, Integer.toUnsignedLong(addr));
                }
            }
        }
        return false;
    }

    /// Tabelas `off[]` de {@link #executeMveInterleavedLoadStore}, transcritas verbatim de
    /// `DO_VLD2B`/`DO_VLD4B`/`DO_VLD2H`/`DO_VLD4H`/`DO_VLD2W`/`DO_VLD4W` (idênticas para as formas
    /// `VST*`, `target/arm/tcg/mve_helper.c`) — cada linha é um `pat` (`0`-`3`; grupo `2` só usa
    /// `pat` `0`/`1`).
    private static final int[][] INTERLEAVE_OFF_BYTE_4 = {
            {0, 1, 10, 11}, {2, 3, 12, 13}, {4, 5, 14, 15}, {6, 7, 8, 9}
    };
    private static final int[][] INTERLEAVE_OFF_BYTE_2 = {
            {0, 2, 12, 14}, {4, 6, 8, 10}
    };
    /// `DO_VLD4H(op, O1, O2)` monta `off[4] = {O1, O1, O2, O2}` a partir de só 2 valores por `pat`.
    private static final int[][] INTERLEAVE_HALFWORD_PAIR_4 = {
            {0, 5}, {1, 6}, {2, 7}, {3, 4}
    };
    private static final int[][] INTERLEAVE_OFF_HALFWORD_2 = {
            {0, 1, 6, 7}, {2, 3, 4, 5}
    };
    private static final int[][] INTERLEAVE_OFF_WORD_2 = {
            {0, 4, 24, 28}, {8, 12, 16, 20}
    };

    private static int[] interleaveOffsetTable(int groupSize, int sizeLog2, int pat) {
        return switch (sizeLog2) {
            case 0 -> groupSize == 4 ? INTERLEAVE_OFF_BYTE_4[pat] : INTERLEAVE_OFF_BYTE_2[pat];
            case 1 -> groupSize == 4
                    ? expandHalfwordPair(INTERLEAVE_HALFWORD_PAIR_4[pat])
                    : INTERLEAVE_OFF_HALFWORD_2[pat];
            case 2 -> groupSize == 4 ? INTERLEAVE_OFF_BYTE_4[pat] : INTERLEAVE_OFF_WORD_2[pat];
            default -> throw new IllegalArgumentException("sizeLog2 inválido para VLD2/VLD4: " + sizeLog2);
        };
    }

    private static int[] expandHalfwordPair(int[] pair) {
        return new int[] {pair[0], pair[0], pair[1], pair[1]};
    }

    /// `VLD2`/`VLD4`/`VST2`/`VST4` (perfil M, B16.5, MVE/Helium): desentrelaçamento/entrelaçamento
    /// em 4 beats de 32 bits, transcrito verbatim de `DO_VLD2*`/`DO_VLD4*`/`DO_VST2*`/`DO_VST4*`
    /// (`target/arm/tcg/mve_helper.c`) — três formas de endereço/empacotamento por
    /// {@link IrOp.MveInterleavedLoadStore#sizeLog2} (byte/halfword/word), cada uma com sua própria
    /// aritmética de deslocamento de bits (ver os comentários por `case`, fiéis ao C original).
    /// **Só `eciMask` gate cada beat** (nenhum `elementMask`/`VPT` — comentário literal do QEMU
    /// real: "beatwise but not predicated"). Writeback incondicional (G4), soma
    /// `groupSize * 16` bytes.
    ///
    /// @return `true` quando faultou (`ECI` reservado) — ver {@link #executeMveLoadStore}.
    public boolean executeMveInterleavedLoadStore(ArmCore core, IrOp.MveInterleavedLoadStore op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int base = core.register(op.rn());
        int eciMask = MveVptState.eciMask(core.cpsr().itState());
        int[] off = interleaveOffsetTable(op.groupSize(), op.sizeLog2(), op.pat());
        VfpRegisters vfp = core.vfp();
        int qnidx = op.qd();
        int o1 = off[0];
        switch (op.sizeLog2()) {
            case 0 -> { // DO_VLD4B/DO_VLD2B/DO_VST4B/DO_VST2B: uma word por beat, 4 bytes empacotados
                int byteScale = op.groupSize() == 4 ? 4 : 2;
                for (int beat = 0; beat < 4; beat++) {
                    if (((eciMask >>> (beat << 2)) & 1) == 0) {
                        continue;
                    }
                    int addr = base + off[beat] * byteScale;
                    if (op.load()) {
                        long data = support.readVectorElement(core, addr, WORD_LOG2);
                        for (int e = 0; e < 4; e++, data >>>= 8) {
                            int qd = op.groupSize() == 4 ? qnidx + e : qnidx + (e & 1);
                            int lane = op.groupSize() == 4 ? off[beat] : off[beat] + (e >> 1);
                            vfp.setElement(qd, lane, 0, data & 0xFF);
                        }
                    } else {
                        long data = 0;
                        for (int e = 3; e >= 0; e--) {
                            int qd = op.groupSize() == 4 ? qnidx + e : qnidx + (e & 1);
                            int lane = op.groupSize() == 4 ? off[beat] : off[beat] + (e >> 1);
                            data = (data << 8) | vfp.element(qd, lane, 0);
                        }
                        support.writeVectorElement(core, addr, WORD_LOG2, data);
                        core.notifyOrdinaryWrite(addr, 4);
                    }
                }
            }
            case 1 -> { // DO_VLD4H/DO_VLD2H/DO_VST4H/DO_VST2H
                int y = 0;
                for (int beat = 0; beat < 4; beat++) {
                    if (((eciMask >>> (beat << 2)) & 1) == 0) {
                        y ^= op.groupSize() == 4 ? 2 : 0;
                        continue;
                    }
                    if (op.groupSize() == 4) {
                        int addr = base + off[beat] * 8 + (beat & 1) * 4;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qnidx + y, off[beat], 1, data & 0xFFFF);
                            vfp.setElement(qnidx + y + 1, off[beat], 1, (data >>> 16) & 0xFFFF);
                        } else {
                            long data = vfp.element(qnidx + y, off[beat], 1)
                                    | (vfp.element(qnidx + y + 1, off[beat], 1) << 16);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                        y ^= 2;
                    } else {
                        int addr = base + off[beat] * 4;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qnidx, off[beat], 1, data & 0xFFFF);
                            vfp.setElement(qnidx + 1, off[beat], 1, (data >>> 16) & 0xFFFF);
                        } else {
                            long data = vfp.element(qnidx, off[beat], 1)
                                    | (vfp.element(qnidx + 1, off[beat], 1) << 16);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                    }
                }
            }
            case 2 -> { // DO_VLD4W/DO_VLD2W/DO_VST4W/DO_VST2W
                for (int beat = 0; beat < 4; beat++) {
                    if (((eciMask >>> (beat << 2)) & 1) == 0) {
                        continue;
                    }
                    if (op.groupSize() == 4) {
                        int addr = base + off[beat] * 4;
                        int y = (beat + (o1 & 2)) & 3;
                        int lane = off[beat] >>> 2;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qnidx + y, lane, WORD_LOG2, data);
                        } else {
                            long data = vfp.element(qnidx + y, lane, WORD_LOG2);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                    } else {
                        int addr = base + off[beat];
                        int qd = qnidx + (beat & 1);
                        int lane = off[beat] >>> 3;
                        if (op.load()) {
                            long data = support.readVectorElement(core, addr, WORD_LOG2);
                            vfp.setElement(qd, lane, WORD_LOG2, data);
                        } else {
                            long data = vfp.element(qd, lane, WORD_LOG2);
                            support.writeVectorElement(core, addr, WORD_LOG2, data);
                            core.notifyOrdinaryWrite(addr, 4);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("sizeLog2 inválido para VLD2/VLD4: " + op.sizeLog2());
        }
        if (op.writeback()) {
            core.setRegister(op.rn(), base + op.groupSize() * 16);
        }
        return false;
    }

    /// `VIDUP`/`VDDUP` (perfil M, B16.5, MVE/Helium): verbatim de `DO_VIDUP` — grava `Rn` (truncado
    /// ao elemento) em cada lane sucessiva de `Qd`, mascarado por `elementMask` (`mergemask`, lane
    /// mascarada preserva o valor atual), e acumula `Rn += imm` livremente (SEM truncar o valor
    /// escalar) a cada lane, gravando o resultado final de volta em `Rn`.
    public boolean executeMveIncrementDup(ArmCore core, IrOp.MveIncrementDup op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        VfpRegisters vfp = core.vfp();
        int sizeLog2 = op.sizeLog2();
        int offset = core.register(op.rn());
        int elementCount = 16 >>> sizeLog2;
        for (int e = 0; e < elementCount; e++) {
            int b = e << sizeLog2;
            if (((mask >>> b) & 1) != 0) {
                vfp.setElement(op.qd(), e, sizeLog2, AdvSimdLanes.truncate(offset, sizeLog2));
            }
            offset += op.imm();
        }
        core.setRegister(op.rn(), offset);
        return false;
    }

    /// `VIWDUP`/`VDWDUP` (perfil M, B16.5, MVE/Helium): como {@link #executeMveIncrementDup}, mas
    /// o passo envolve (wrap) contra `Rm` — `do_add_wrap`/`do_sub_wrap` verbatim.
    public boolean executeMveWrappingIncrementDup(ArmCore core, IrOp.MveWrappingIncrementDup op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        VfpRegisters vfp = core.vfp();
        int sizeLog2 = op.sizeLog2();
        int offset = core.register(op.rn());
        int wrap = core.register(op.rm());
        int elementCount = 16 >>> sizeLog2;
        for (int e = 0; e < elementCount; e++) {
            int b = e << sizeLog2;
            if (((mask >>> b) & 1) != 0) {
                vfp.setElement(op.qd(), e, sizeLog2, AdvSimdLanes.truncate(offset, sizeLog2));
            }
            if (op.decrement()) {
                if (offset == 0) {
                    offset = wrap;
                }
                offset -= op.imm();
            } else {
                offset += op.imm();
                if (offset == wrap) {
                    offset = 0;
                }
            }
        }
        core.setRegister(op.rn(), offset);
        return false;
    }

    /// Avanço pós-instrução SÓ do `ECI` (B16.5) — {@link MveVptState#advanceEciOnly}. Usado só por
    /// {@link IrOp.MveInterleavedLoadStore} (`VLD2`/`VLD4`/`VST2`/`VST4`), mesmo gate de
    /// `pcChanged` de {@link #executeAdvanceVpt} no chamador.
    public void executeAdvanceEci(ArmCore core, IrOp.AdvanceEci op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return;
        }
        core.cpsr().setItState(MveVptState.advanceEciOnly(core.cpsr().itState()));
    }

    /// Vector 2-op inteiro (perfil M, B16.6, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#threeSameMasked}) — a MESMA função que `IrNeonExecutor`/executor A64
    /// chamam via {@link AdvSimdLanes#threeSame} para o caminho NÃO predicado (RFC B13.2 D1). As 12
    /// formas saturantes setam `FPSCR.QC` só quando alguma lane ATIVA saturou de verdade (ver
    /// Javadoc de {@link dev.vitorsilverio.armjitter.core.FpscrRegister#QC_FLAG}).
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVector2Op(ArmCore core, IrOp.MveVector2Op op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        boolean saturated = AdvSimdLanes.threeSameMasked(vfp, op.op(), esz, lanes, baseRd, baseRn, baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VMULLP_B`/`VMULLP_T`/`VMULL_BS`/`VMULL_BU`/`VMULL_TS`/`VMULL_TU` (perfil M, B16.6,
    /// MVE/Helium): delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#wideningInterleavedMasked}).
    /// Nenhuma destas seis satura (não estão entre os 10 `op` saturantes de {@link
    /// AdvSimdLanes#threeSameMasked} — `SMULL`/`UMULL`/`PMULL` nunca saturam por construção), sem
    /// `FPSCR.QC`.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVector2OpWidening(ArmCore core, IrOp.MveVector2OpWidening op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.wideningInterleavedMasked(vfp, op.op(), esz, outputElements, op.top(), baseRd, baseRn, baseRm,
                mask);
        return false;
    }

    /// `VADC`/`VADCI`/`VSBC`/`VSBCI` (perfil M, B16.6, MVE/Helium): soma/subtração com carry
    /// encadeado por `FPSCR.C` através dos 4 elementos de 32 bits de `Qn`/`Qm` — verbatim de
    /// `do_vadc` (`target/arm/tcg/mve_helper.c`, ver Javadoc de {@link IrOp.MveVectorCarry}). ESIZE
    /// fixo em 4 bytes (não usa {@link AdvSimdLanes#threeSameMasked}: o carry ENCADEADO entre
    /// lanes, que só avança em lanes ATIVAS, não é uma operação "three same" independente por lane).
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorCarry(ArmCore core, IrOp.MveVectorCarry op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        final int esz = 2; // ESIZE fixo em 4 bytes (word) — `size` do encoding é decorativo aqui.
        final int elementBytes = 1 << esz;
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        FpscrRegister fpscr = core.fpscr();
        boolean carry = op.immediateCarry() ? !op.add() : fpscr.c();
        boolean anyActive = false;
        int invert = op.add() ? 0 : -1;
        for (int i = 0; i < 4; i++) {
            int laneMask = (mask >>> (i * elementBytes)) & 0xF;
            long n = AdvSimdLanes.element(vfp, baseRn, i, esz);
            long m = AdvSimdLanes.element(vfp, baseRm, i, esz);
            long sum = (carry ? 1L : 0L) + (n & 0xFFFF_FFFFL) + ((m ^ invert) & 0xFFFF_FFFFL);
            if (laneMask != 0) {
                anyActive = true;
                carry = ((sum >>> 32) & 1) != 0;
            }
            long current = AdvSimdLanes.element(vfp, baseRd, i, esz);
            long merged = mergeMveCarryLane(current, sum, laneMask, elementBytes);
            AdvSimdLanes.setElement(vfp, baseRd, i, esz, merged);
        }
        if (anyActive) {
            fpscr.setNzcv(carry ? FpscrRegister.CARRY_FLAG : 0);
        }
        return false;
    }

    /// Mescla `result` (32 bits crus, só os 4 bytes baixos usados) em `current` byte a byte —
    /// mesma convenção de `mergemask`/{@link AdvSimdLanes#threeSameMasked} usada por
    /// {@link #executeMveVectorCarry} (duplicado aqui em vez de reusar `mergeLaneBytes`, privado ao
    /// núcleo — G6, sem número mágico: `0xFFL << (byteIndex*8)` é a mesma fórmula documentada lá).
    private static long mergeMveCarryLane(long current, long result, int laneMask, int elementBytes) {
        long merged = current;
        for (int byteIndex = 0; byteIndex < elementBytes; byteIndex++) {
            if (((laneMask >>> byteIndex) & 1) != 0) {
                long byteMask = 0xFFL << (byteIndex * 8);
                merged = (merged & ~byteMask) | (result & byteMask);
            }
        }
        return merged;
    }

    /// `VHCADD90`/`VHCADD270`/`VCADD90`/`VCADD270` (perfil M, B16.6, MVE/Helium): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#complexAddMasked}). Nunca satura, sem `FPSCR.QC`.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorComplexAdd(ArmCore core, IrOp.MveVectorComplexAdd op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRn = op.qn() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.complexAddMasked(vfp, op.rotate90(), op.halving(), esz, lanes, baseRd, baseRn, baseRm, mask);
        return false;
    }

    /// `VMAXA`/`VMINA` (perfil M, B16.7, MVE/Helium): delega ao núcleo COMPARTILHADO
    /// ({@link AdvSimdLanes#absAccumulateMasked}). Nunca satura, sem `FPSCR.QC`.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorAbsAccumulate(ArmCore core, IrOp.MveVectorAbsAccumulate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.absAccumulateMasked(vfp, op.max(), esz, lanes, baseRd, baseRm, mask);
        return false;
    }

    /// `VMAXNMA`/`VMINNMA` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`): delega ao núcleo
    /// COMPARTILHADO ({@link AdvSimdLanes#fpAbsAccumulateMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorFpAbsAccumulate(ArmCore core, IrOp.MveVectorFpAbsAccumulate op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int lanes = 16 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.fpAbsAccumulateMasked(vfp, op.max(), esz, lanes, baseRd, baseRm, mask);
        return false;
    }

    /// `VSHLL_BS`/`VSHLL_BU`/`VSHLL_TS`/`VSHLL_TU` forma T2 (perfil M, B16.7, MVE/Helium): delega ao
    /// núcleo COMPARTILHADO ({@link AdvSimdLanes#shiftWidenInterleavedMasked}). `shift` é sempre
    /// `8 << esz` (T2 — "shift == esize"). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorShiftWidenInterleaved(ArmCore core, IrOp.MveVectorShiftWidenInterleaved op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int shift = 8 << esz;
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        AdvSimdLanes.shiftWidenInterleavedMasked(vfp, op.signed(), esz, shift, outputElements, op.top(), baseRd,
                baseRm, mask);
        return false;
    }

    /// `VMOVNB`/`VMOVNT`/`VQMOVN_B*`/`VQMOVN_T*`/`VQMOVUNB`/`VQMOVUNT` (perfil M, B16.7, MVE/Helium):
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#narrowInterleavedMasked}). `FPSCR.QC` só
    /// para as 3 formas saturantes ({@link dev.vitorsilverio.armjitter.advsimd.AdvSimdNarrowUnaryOp#XTN}
    /// nunca satura).
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorNarrowInterleaved(ArmCore core, IrOp.MveVectorNarrowInterleaved op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int esz = op.esz();
        int outputElements = 8 >> esz;
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        boolean saturated = AdvSimdLanes.narrowInterleavedMasked(vfp, op.op(), esz, outputElements, op.top(), baseRd,
                baseRm, mask);
        if (saturated) {
            core.fpscr().orQc();
        }
        return false;
    }

    /// `VCVTB_SH`/`VCVTT_SH`/`VCVTB_HS`/`VCVTT_HS` (perfil M, B16.7, MVE/Helium, `FEAT_MVE_FP`):
    /// delega ao núcleo COMPARTILHADO ({@link AdvSimdLanes#fpNarrowPrecisionInterleavedMasked}/
    /// {@link AdvSimdLanes#fpWidenPrecisionInterleavedMasked}). Nunca satura.
    ///
    /// @return `true` quando faultou (ver {@link #executeVpst}).
    public boolean executeMveVectorFpConvertPrecision(ArmCore core, IrOp.MveVectorFpConvertPrecision op) {
        if (!core.cpsr().evalCond(op.condition())) {
            return false;
        }
        int eci = core.cpsr().eci();
        if (MveVptState.isReservedEci(eci)) {
            return faultInvstate(core);
        }
        VfpRegisters vfp = core.vfp();
        int vpr = core.vpr().value();
        int mask = MveVptState.elementMask(vpr, core.cpsr().itState(), NO_TAIL_PREDICATION_LTPSIZE, 0);
        int baseRd = op.qd() * VfpRegisters.WORDS_PER_QUAD;
        int baseRm = op.qm() * VfpRegisters.WORDS_PER_QUAD;
        if (op.widen()) {
            AdvSimdLanes.fpWidenPrecisionInterleavedMasked(vfp, op.top(), baseRd, baseRm, mask);
        } else {
            AdvSimdLanes.fpNarrowPrecisionInterleavedMasked(vfp, op.top(), baseRd, baseRm, mask);
        }
        return false;
    }
}
