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
        core.setRegister(ArmCore.LR, returnAddress);
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
            case SWI, UNDEFINED -> core.programCounter();
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
        };
    }
}
