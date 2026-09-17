package dev.vitorsilverio.armjitter.codegen.executor;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmException;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.CpuMode;
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
}
