package dev.vitorsilverio.armjitter.core;

/// {@link ExceptionModel} do perfil ARM clássico (A/R): vetores fixos em `0x00`-`0x1C` (ou
/// `0xFFFF0000`+ com {@link ArmCore#highVectors()}), troca de modo bancada, SPSR do modo alvo e
/// máscara de IRQ/FIQ. Extraído de {@code ArmCore#handleException} na B7.1 (refactor zero-diff) —
/// é o modelo default de todo {@link ArmCore}, sem nenhuma mudança de comportamento observável.
public final class AProfileExceptionModel implements ExceptionModel {

    @Override
    public void enterException(ArmCore core, ArmException exception) {
        int returnAddress = exceptionReturnAddress(core, exception);
        // Qualquer entrada de exceção (SWI/IRQ/undefined/aborts) abre o monitor de
        // exclusividade: um STREX após o retorno deve falhar e refazer o par LDREX/STREX.
        core.clearExclusiveMonitor();
        CpuMode targetMode = exceptionMode(exception);
        int vector = exceptionVector(exception);
        int oldCpsr = core.cpsr().get();
        core.switchMode(targetMode);
        core.setSpsr(targetMode, oldCpsr);
        // Hyp mode não banca LR (é o LR_usr/LR_sys compartilhado, ver B9.8.1) — o registrador de
        // retorno real do modo é ELR_hyp, à parte da numeração R0-R15 (ARM DDI 0406C, mesmo achado
        // real que o `take_aarch32_exception` do QEMU confirma: só grava env->regs[14] quando
        // new_mode != HYP).
        if (targetMode == CpuMode.HYP) {
            core.setElrHyp(returnAddress);
        } else {
            core.setRegister(ArmCore.LR, returnAddress);
        }
        core.cpsr().setThumbMode(false);
        // ITSTATE (B2.4, Thumb-2 IT block): a entrada de exceção sempre limpa o ITSTATE do CPSR
        // NOVO (a `oldCpsr` completa, incl. o ITSTATE de origem, já foi capturada acima em
        // `setSpsr` — só a cópia ATIVA some) — mesma regra do ARM ARM para qualquer exceção
        // (SWI/IRQ/FIQ/aborts/UNDEFINED), independente de em qual instrução do IT block ela
        // ocorreu. Sem isto, um `IrOp.SetItState` de avanço emitido pelo lifter LOGO DEPOIS da
        // instrução que disparou a exceção (mesmo bloco IR, ainda vai executar) deixaria um
        // ITSTATE não-zero "vazando" para o handler por coincidência de timing.
        core.cpsr().setItState(0);
        // CPSR.E na entrada de exceção (task F3, achado real): hardware real reprograma E para
        // SCTLR.EE aqui, independente do que o código interrompido tinha configurado via SETEND
        // — sem isto, um handler de exceção (ex. o vector_stub de IRQ) pode herdar E=1 de um
        // trecho interrompido em plena SETEND BE e ler sua própria tabela de branch invertida em
        // bytes. NONE por padrão (nenhuma mudança de comportamento em cores sem CP15/SCTLR.EE).
        core.applyExceptionEndiannessPolicy();
        core.cpsr().setIrqDisabled(true);
        if (exception == ArmException.RESET || exception == ArmException.FIQ) {
            core.cpsr().setFiqDisabled(true);
        }
        core.setProgramCounter((core.highVectors() ? 0xFFFF0000 : 0) + vector);
    }

    private static int exceptionReturnAddress(ArmCore core, ArmException exception) {
        return switch (exception) {
            // HVC: mesma convenção de SWI — o executor (`IrSystemExecutor#executeHvc`) já grava
            // o PC sequencial (endereço da PRÓXIMA instrução) antes de pedir a exceção, mesmo
            // achado real conferido no `gen_hvc` do QEMU (`gen_update_pc(s, curr_insn_len(s))`
            // ANTES de levantar `EXCP_HVC`).
            case SWI, UNDEFINED, HVC -> core.programCounter();
            case IRQ, FIQ -> core.programCounter() + 4;
            case PREFETCH_ABORT -> core.programCounter() + 4;
            case DATA_ABORT -> core.programCounter() + 8;
            case RESET -> 0;
        };
    }

    private static CpuMode exceptionMode(ArmException exception) {
        return switch (exception) {
            case RESET, SWI -> CpuMode.SUPERVISOR;
            case UNDEFINED -> CpuMode.UNDEFINED;
            case PREFETCH_ABORT, DATA_ABORT -> CpuMode.ABORT;
            case IRQ -> CpuMode.IRQ;
            case FIQ -> CpuMode.FIQ;
            case HVC -> CpuMode.HYP;
        };
    }

    private static int exceptionVector(ArmException exception) {
        return switch (exception) {
            case RESET -> 0x00;
            case UNDEFINED -> 0x04;
            case SWI -> 0x08;
            case PREFETCH_ABORT -> 0x0C;
            case DATA_ABORT -> 0x10;
            case IRQ -> 0x18;
            case FIQ -> 0x1C;
            // HVC (B9.8.2): vetor "Hyp Trap"/genérico, simplificação deliberada desta escada — sem
            // `HVBAR` real modelado ainda (nenhum consumidor reprograma vetores de Hyp hoje), reusa
            // o MESMO esquema fixo (`highVectors`) que todo o resto do perfil A/R já usa. QEMU real
            // (`arm_cpu_do_interrupt_aarch32_hyp`) usa este MESMO offset 0x14 para HVC executado a
            // partir de qualquer modo que não seja o próprio Hyp mode — o caso "Hyp chamando HVC de
            // si mesmo" (que usaria 0x08 no hardware real) não é distinguido aqui, mesma
            // simplificação documentada no plano mestre `b9.8-plano-hyp-monitor-32bit.md`.
            case HVC -> 0x14;
        };
    }
}
