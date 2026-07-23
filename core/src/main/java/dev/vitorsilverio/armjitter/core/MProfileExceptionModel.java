package dev.vitorsilverio.armjitter.core;

import java.util.ArrayDeque;
import java.util.Deque;

/// {@link ExceptionModel} do perfil ARM Cortex-M (B7.2): MSP/PSP, `xPSR`, empilhamento
/// automático na entrada de exceção e `EXC_RETURN` na saída. Extraído a partir do
/// ARMv7-M ARM (DDI 0403E) §B1.5, seguindo a ordem de operações do `v7m_push_stack`/
/// `do_v7m_exception_exit` do QEMU (`target/arm/tcg/m_helper.c`).
///
/// B7.3 acrescenta pendência/prioridade/preempção (consultadas por
/// {@link MProfileSystemControl}, o SCS memory-mapped que o hospedeiro pendura em `0xE000E000`):
/// as exceções continuam podendo chegar por chamada direta de
/// {@link #enterException(ArmCore, MProfileException)} (testes, sempre incondicional) ou por
/// `SVC` ({@link #handlesSupervisorCall()}), mas agora também por
/// {@link #hasPendingException()}/{@link #enterPendingException(ArmCore)} — o mesmo ponto onde
/// {@link ArmCore} consulta a linha de IRQ do perfil A.
public final class MProfileExceptionModel implements ExceptionModel {
    /// Bit SPSEL do CONTROL (1 = SP ativo em Thread mode é o PSP; ignorado em Handler mode, que
    /// sempre usa o MSP). Público: reusado por {@link #writeControl} e por quem monta um
    /// CONTROL inicial em teste (MRS/MSR CONTROL de verdade só chegam na B7.4).
    public static final int CONTROL_SPSEL_BIT = 1 << 1;
    /// Bit nPRIV do CONTROL (privilégio de Thread mode; sem consumidor nesta task).
    private static final int CONTROL_NPRIV_BIT = 1 << 0;
    /// Bit PRIMASK (mascaramento simples de exceções configuráveis; BASEPRI/FAULTMASK ficam
    /// para a B7.4, que decide como expô-los).
    private static final int PRIMASK_BIT = 1 << 0;
    /// Valor do IPSR/`currentException` em Thread mode (nenhuma exceção ativa).
    private static final int THREAD_MODE_IPSR = 0;

    /// Tamanho do frame de exceção empilhado (8 words: R0-R3, R12, LR, ReturnAddress, xPSR).
    /// Sem extensão de ponto flutuante (frame de 0x68 com FP context) — fora de escopo da B7.2.
    /// Público para quem precisar calcular o deslocamento de SP esperado em teste.
    public static final int EXCEPTION_FRAME_SIZE_BYTES = 32;
    private static final int R0_OFFSET = 0;
    private static final int R1_OFFSET = 4;
    private static final int R2_OFFSET = 8;
    private static final int R3_OFFSET = 12;
    private static final int R12_OFFSET = 16;
    private static final int LR_OFFSET = 20;
    private static final int RETURN_ADDRESS_OFFSET = 24;
    private static final int XPSR_OFFSET = 28;
    /// Bit 2 do SP: quando setado antes do empilhamento, o frame realinha a 8 bytes (mais um
    /// word de ajuste) e o realinhamento fica registrado no bit 9 do `xPSR` empilhado
    /// ({@link #XPSR_STACK_ALIGN_BIT}) para o retorno desfazer.
    private static final int STACK_ALIGN_CHECK_BIT = 0x4;
    private static final int STACK_ALIGN_ADJUSTMENT_BYTES = 4;

    /// Bit T do `xPSR` (bit 24 — posição DIFERENTE do bit T do CPSR clássico, bit 5;
    /// {@link CpsrRegister#THUMB_FLAG} não se aplica aqui). Perfil M nunca sai do estado Thumb,
    /// então este bit empilhado é sempre 1; um valor restaurado com este bit 0 é `INVSTATE`.
    private static final int XPSR_THUMB_BIT = 1 << 24;
    /// Bit STKALIGN do `xPSR` empilhado (bit 9): registra se o empilhamento realinhou o SP.
    private static final int XPSR_STACK_ALIGN_BIT = 1 << 9;
    /// Máscara do IPSR dentro do `xPSR` (bits 8:0) — mesmo número de {@link MProfileException#number()}.
    private static final int XPSR_IPSR_MASK = 0x1FF;
    /// Bits do CPSR clássico preservados 1:1 no `xPSR` (NZCVQ, GE\[3:0\] e ITSTATE ocupam
    /// exatamente as mesmas posições em ambos os registradores — só T e IPSR divergem de layout).
    private static final int XPSR_PRESERVED_CPSR_BITS_MASK =
            CpsrRegister.NEGATIVE_FLAG | CpsrRegister.ZERO_FLAG | CpsrRegister.CARRY_FLAG
                    | CpsrRegister.OVERFLOW_FLAG | CpsrRegister.SATURATION_FLAG
                    | CpsrRegister.GE_FLAGS_MASK | CpsrRegister.IT_LOW_MASK | CpsrRegister.IT_HIGH_MASK;

    /// `EXC_RETURN`: handler→handler, MSP (nested, sem troca de stack). Público: os 3 valores
    /// válidos são o vocabulário de retorno de exceção M-profile (`BX`/`POP {pc}` com este valor),
    /// usado por teste e por quem gerar código para handlers.
    public static final int EXC_RETURN_HANDLER_TO_HANDLER_MSP = 0xFFFFFFF1;
    /// `EXC_RETURN`: thread→handler, retorna usando MSP (SPSEL era 0 na entrada).
    public static final int EXC_RETURN_THREAD_TO_HANDLER_MSP = 0xFFFFFFF9;
    /// `EXC_RETURN`: thread→handler, retorna usando PSP (SPSEL era 1 na entrada).
    public static final int EXC_RETURN_THREAD_TO_HANDLER_PSP = 0xFFFFFFFD;
    /// Prefixo comum a todo `EXC_RETURN` (nibble alto todo 1) — usado por {@link #interceptsBranch}
    /// para reconhecer um candidato antes de validar contra os 3 valores válidos acima.
    private static final int EXC_RETURN_PREFIX_MASK = 0xFFFFFFF0;
    private static final int EXC_RETURN_PREFIX = 0xFFFFFFF0;

    private static final int VECTOR_ENTRY_SIZE_BYTES = 4;
    /// Bit 0 do endereço lido da tabela de vetores: deve ser 1 (Thumb). Mesmo bit de
    /// {@link #XPSR_THUMB_BIT} conceitualmente, mas em posição de ENDEREÇO, não de `xPSR`.
    private static final int VECTOR_THUMB_BIT = 1;

    /// Primeiro número de exceção externa (IRQ0 do NVIC — B7.3, ARMv7-M ARM tabela B1-4).
    static final int FIRST_EXTERNAL_EXCEPTION_NUMBER = 16;
    /// Máximo de IRQs externas suportadas nesta fase (limite do construtor de
    /// {@link MProfileSystemControl}, um word de ISER/ICER/ISPR/ICPR/IABR).
    static final int MAX_EXTERNAL_IRQS = 32;
    private static final int EXCEPTION_TABLE_SIZE = FIRST_EXTERNAL_EXCEPTION_NUMBER + MAX_EXTERNAL_IRQS;
    /// Prioridade efetiva do Thread mode (nada ativo): mais baixa possível, qualquer exceção
    /// habilitada e pendente preempta.
    private static final int THREAD_MODE_PRIORITY = Integer.MAX_VALUE;
    /// Prioridade fixa (impl-defined negativa, ARMv7-M ARM B1.5.4) da NMI — sempre preempta,
    /// nunca mascarada por PRIMASK.
    private static final int NMI_PRIORITY = -2;
    /// Prioridade fixa do HardFault — preempta tudo exceto NMI, nunca mascarada por PRIMASK.
    private static final int HARD_FAULT_PRIORITY = -1;
    /// Máscara dos 8 bits de prioridade implementados nesta fase (armadilha da B7.3: a
    /// arquitetura permite implementar menos bits, mas fixamos 8 e documentamos).
    private static final int PRIORITY_FIELD_MASK = 0xFF;

    /// Pendência por número de exceção (índice = {@link MProfileException#number()} para as
    /// fixas, {@code FIRST_EXTERNAL_EXCEPTION_NUMBER + irq} para as externas do NVIC).
    private final boolean[] pending = new boolean[EXCEPTION_TABLE_SIZE];
    /// Prioridade configurável de cada exceção (SHPR1-3/IPR do SCS); NMI/HardFault ignoram este
    /// array — a prioridade delas é fixa ({@link #NMI_PRIORITY}/{@link #HARD_FAULT_PRIORITY}).
    private final int[] priority = new int[EXCEPTION_TABLE_SIZE];
    /// Habilitação por IRQ externa (ISER/ICER do NVIC); exceções de sistema (2-15) não têm gate
    /// de habilitação nesta fase (SHCSR é armazenamento inerte, ver "Não inclui" da B7.3).
    private final boolean[] externalIrqEnabled = new boolean[MAX_EXTERNAL_IRQS];
    /// Pilha de exceções ativas (número no topo = {@link #currentException}) — espelha a mesma
    /// informação de {@link #currentException} mas preserva o histórico completo de aninhamento
    /// para {@link #currentPriority()} (preempção) e o IABR do {@link MProfileSystemControl}.
    private final Deque<Integer> activeExceptions = new ArrayDeque<>();

    /// Sombra do SP INATIVO (o ativo mora em {@code ArmCore.registers[13]}, ver
    /// {@link ArmCore#SP}) — MSP quando o ativo é o PSP, e vice-versa. Sincronizados apenas em
    /// entrada/retorno de exceção e em {@link #writeControl} (troca de SPSEL) — nunca fora disso.
    private int mainStackPointer;
    private int processStackPointer;
    private int control;
    private int currentException = THREAD_MODE_IPSR;
    private int primask;
    private int vectorTableOffset;

    /// Retorna o MSP. Quando o MSP é o SP ativo (Handler mode, ou Thread com SPSEL=0), este
    /// valor está desatualizado — leia {@code core.register(13)} nesse caso.
    public int mainStackPointer() {
        return mainStackPointer;
    }

    /// Ajusta o MSP diretamente (setup de teste/boot; não sincroniza o SP ativo).
    public void setMainStackPointer(int mainStackPointer) {
        this.mainStackPointer = mainStackPointer;
    }

    /// Retorna o PSP. Quando o PSP é o SP ativo (Thread com SPSEL=1), este valor está
    /// desatualizado — leia {@code core.register(13)} nesse caso.
    public int processStackPointer() {
        return processStackPointer;
    }

    /// Ajusta o PSP diretamente (setup de teste/boot; não sincroniza o SP ativo).
    public void setProcessStackPointer(int processStackPointer) {
        this.processStackPointer = processStackPointer;
    }

    /// Retorna o CONTROL (bits SPSEL/nPRIV).
    public int control() {
        return control;
    }

    /// Retorna {@code true} quando SPSEL=1 (SP ativo em Thread mode é o PSP).
    public boolean spsel() {
        return (control & CONTROL_SPSEL_BIT) != 0;
    }

    /// Escreve o CONTROL, trocando o SP ativo com a sombra correta quando o bit SPSEL muda
    /// (mesma troca que a entrada/retorno de exceção faz — MRS/MSR CONTROL chegam na B7.4, mas
    /// já plugam neste método). Sem efeito em Handler mode (SPSEL só é lido/gravado em Thread,
    /// arquiteturalmente).
    public void writeControl(ArmCore core, int value) {
        boolean wasPsp = spsel();
        control = value;
        boolean nowPsp = spsel();
        if (currentException != THREAD_MODE_IPSR || wasPsp == nowPsp) {
            return;
        }
        if (wasPsp) {
            processStackPointer = core.register(ArmCore.SP);
            core.setRegister(ArmCore.SP, mainStackPointer);
        } else {
            mainStackPointer = core.register(ArmCore.SP);
            core.setRegister(ArmCore.SP, processStackPointer);
        }
    }

    /// Retorna o IPSR atual (0 = Thread mode; caso contrário, o número da exceção ativa).
    public int currentException() {
        return currentException;
    }

    /// Retorna `true` quando uma exceção está ativa (Handler mode).
    public boolean handlerModeActive() {
        return currentException != THREAD_MODE_IPSR;
    }

    /// Retorna o PRIMASK (bit 0; BASEPRI/FAULTMASK ficam para a B7.4).
    public int primask() {
        return primask;
    }

    /// Ajusta o PRIMASK.
    public void setPrimask(int primask) {
        this.primask = primask & PRIMASK_BIT;
    }

    /// Retorna o offset da tabela de vetores (VTOR). `0` por padrão; memory-mapped na B7.3.
    public int vectorTableOffset() {
        return vectorTableOffset;
    }

    /// Ajusta o VTOR (setup de teste; memory-mapped de verdade na B7.3).
    public void setVectorTableOffset(int vectorTableOffset) {
        this.vectorTableOffset = vectorTableOffset;
    }

    /// Marca a exceção `number` como pendente (ISPR/PENDSVSET/PENDSTSET do
    /// {@link MProfileSystemControl}, ou o hospedeiro sinalizando uma IRQ externa diretamente).
    public void pendException(int number) {
        pending[number] = true;
    }

    /// Limpa a pendência da exceção `number` (ICPR/PENDSVCLR/PENDSTCLR).
    public void clearPending(int number) {
        pending[number] = false;
    }

    /// Retorna `true` quando a exceção `number` está pendente.
    public boolean pending(int number) {
        return pending[number];
    }

    /// Habilita/desabilita a IRQ externa `irqIndex` (0-based, ISER/ICER do NVIC). Exceções de
    /// sistema (2-15) não passam por aqui — sem gate de habilitação nesta fase.
    public void setExternalIrqEnabled(int irqIndex, boolean enabled) {
        externalIrqEnabled[irqIndex] = enabled;
    }

    /// Retorna `true` quando a IRQ externa `irqIndex` está habilitada.
    public boolean externalIrqEnabled(int irqIndex) {
        return externalIrqEnabled[irqIndex];
    }

    /// Ajusta a prioridade configurável (8 bits, {@link #PRIORITY_FIELD_MASK}) da exceção
    /// `number` (SHPR1-3 para exceções de sistema, IPR para IRQs externas). Sem efeito em
    /// NMI/HardFault — prioridade fixa, ver {@link #effectivePriority}.
    public void setPriority(int number, int priorityValue) {
        priority[number] = priorityValue & PRIORITY_FIELD_MASK;
    }

    /// Retorna a prioridade configurada (crua, 0 para NMI/HardFault mesmo não sendo usada).
    public int priority(int number) {
        return priority[number];
    }

    /// Retorna `true` quando a exceção `number` está no topo ou aninhada na pilha de exceções
    /// ativas (IABR do NVIC — só tem sentido para número >= {@link #FIRST_EXTERNAL_EXCEPTION_NUMBER}).
    public boolean active(int number) {
        return activeExceptions.contains(number);
    }

    /// Prioridade efetiva (ARMv7-M ARM B1.5.4): NMI/HardFault são fixas e nunca mascaradas por
    /// PRIMASK; as demais usam o registrador configurável.
    private int effectivePriority(int number) {
        if (number == MProfileException.NMI.number()) {
            return NMI_PRIORITY;
        }
        if (number == MProfileException.HARD_FAULT.number()) {
            return HARD_FAULT_PRIORITY;
        }
        return priority[number];
    }

    /// Prioridade efetiva da exceção ativa no topo da pilha, ou {@link #THREAD_MODE_PRIORITY}
    /// (a mais baixa possível) quando nenhuma está ativa — qualquer pendência habilitada
    /// preempta o Thread mode.
    private int currentPriority() {
        return activeExceptions.isEmpty() ? THREAD_MODE_PRIORITY : effectivePriority(activeExceptions.peek());
    }

    /// Número da exceção pendente mais urgente que pode entrar agora (habilitada, prioridade
    /// efetiva menor — mais urgente — que {@link #currentPriority()}, não mascarada por
    /// PRIMASK), ou `-1` se nenhuma. PRIMASK=1 mascara toda exceção de prioridade configurável
    /// (>= 0); NMI/HardFault (negativas) nunca são mascaradas por ele.
    private int highestPriorityPendingCandidate() {
        int currentPriority = currentPriority();
        int bestNumber = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int number = MProfileException.NMI.number(); number < pending.length; number++) {
            if (!pending[number]) {
                continue;
            }
            if (number >= FIRST_EXTERNAL_EXCEPTION_NUMBER
                    && !externalIrqEnabled[number - FIRST_EXTERNAL_EXCEPTION_NUMBER]) {
                continue;
            }
            int candidatePriority = effectivePriority(number);
            if (primask != 0 && candidatePriority >= 0) {
                continue;
            }
            if (candidatePriority >= currentPriority) {
                continue;
            }
            if (candidatePriority < bestPriority) {
                bestPriority = candidatePriority;
                bestNumber = number;
            }
        }
        return bestNumber;
    }

    @Override
    public boolean hasPendingException() {
        return highestPriorityPendingCandidate() != -1;
    }

    @Override
    public void enterPendingException(ArmCore core) {
        int number = highestPriorityPendingCandidate();
        if (number == -1) {
            throw new IllegalStateException("enterPendingException chamado sem candidato pendente");
        }
        pending[number] = false;
        enterException(core, number);
    }

    @Override
    public boolean handlesSupervisorCall() {
        return true;
    }

    @Override
    public void enterException(ArmCore core, ArmException exception) {
        switch (exception) {
            case SWI -> enterException(core, MProfileException.SVCALL);
            case UNDEFINED -> enterException(core, MProfileException.USAGE_FAULT);
            default -> throw new UnsupportedOperationException(
                    "Exceção " + exception + " ainda não modelada no perfil M (sem NVIC/reset "
                            + "vector até B7.3/B7.5)");
        }
    }

    /// Entrada de exceção M-profile (ARMv7-M ARM B1.5.6): empilha R0-R3/R12/LR/ReturnAddress/xPSR
    /// no SP ativo (realinhando a 8 bytes se preciso), monta `EXC_RETURN` em LR, troca para
    /// Handler mode com SP ativo = MSP, e busca o vetor. Entrada incondicional (chamador decide
    /// se a exceção "deveria" preemptar — testes chamam direto; {@link #enterPendingException}
    /// já valida prioridade antes de chegar aqui).
    public void enterException(ArmCore core, MProfileException exception) {
        enterException(core, exception.number());
    }

    /// Variante de {@link #enterException(ArmCore, MProfileException)} por número bruto — único
    /// jeito de entrar numa IRQ externa (16+), que não tem constante em {@link MProfileException}.
    void enterException(ArmCore core, int exceptionNumber) {
        core.clearExclusiveMonitor();
        int originalSp = core.register(ArmCore.SP);
        boolean framePtrAlign = (originalSp & STACK_ALIGN_CHECK_BIT) != 0;
        int frame = originalSp - EXCEPTION_FRAME_SIZE_BYTES;
        if (framePtrAlign) {
            frame -= STACK_ALIGN_ADJUSTMENT_BYTES;
        }
        core.memory().write32(frame + R0_OFFSET, core.register(0));
        core.memory().write32(frame + R1_OFFSET, core.register(1));
        core.memory().write32(frame + R2_OFFSET, core.register(2));
        core.memory().write32(frame + R3_OFFSET, core.register(3));
        core.memory().write32(frame + R12_OFFSET, core.register(12));
        core.memory().write32(frame + LR_OFFSET, core.register(ArmCore.LR));
        core.memory().write32(frame + RETURN_ADDRESS_OFFSET, core.programCounter());
        int stackedXpsr = (core.cpsr().get() & XPSR_PRESERVED_CPSR_BITS_MASK)
                | XPSR_THUMB_BIT
                | (currentException & XPSR_IPSR_MASK)
                | (framePtrAlign ? XPSR_STACK_ALIGN_BIT : 0);
        core.memory().write32(frame + XPSR_OFFSET, stackedXpsr);

        boolean returnsToPsp = currentException == THREAD_MODE_IPSR && spsel();
        int excReturn = handlerModeActive()
                ? EXC_RETURN_HANDLER_TO_HANDLER_MSP
                : (returnsToPsp ? EXC_RETURN_THREAD_TO_HANDLER_PSP : EXC_RETURN_THREAD_TO_HANDLER_MSP);
        if (returnsToPsp) {
            processStackPointer = frame;
        } else {
            mainStackPointer = frame;
        }
        core.setRegister(ArmCore.LR, excReturn);
        currentException = exceptionNumber;
        activeExceptions.push(exceptionNumber);
        core.setRegister(ArmCore.SP, mainStackPointer);
        core.cpsr().setItState(0);

        int vector = core.memory().read32(vectorTableOffset + VECTOR_ENTRY_SIZE_BYTES * exceptionNumber);
        if ((vector & VECTOR_THUMB_BIT) == 0) {
            // ARMv7-M ARM B1.5.6: vetor com bit 0 = 0 é HardFault(INVSTATE). Modelagem completa
            // de HardFault/fault status fica para B7.3+; aqui falha alto e documentado em vez de
            // continuar com um PC errado.
            throw new IllegalStateException(
                    "Vetor de exceção M-profile com bit 0 = 0 (HardFault/INVSTATE não modelado "
                            + "nesta fase, B7.2): número " + exceptionNumber);
        }
        core.setProgramCounter(vector & ~VECTOR_THUMB_BIT);
    }

    @Override
    public boolean interceptsBranch(int target) {
        return isExcReturnCandidate(target) || (target & VECTOR_THUMB_BIT) == 0;
    }

    @Override
    public void branchIntercepted(ArmCore core, int target) {
        if (isExcReturnCandidate(target)) {
            exceptionReturn(core, target);
            return;
        }
        // BX/BLX para bit 0 = 0: perfil M não tem estado ARM (só Thumb/Thumb-2) — tentativa
        // controlada como UsageFault (INVSTATE), nunca troca de fato para ARM.
        enterException(core, MProfileException.USAGE_FAULT);
    }

    private static boolean isExcReturnCandidate(int target) {
        return (target & EXC_RETURN_PREFIX_MASK) == EXC_RETURN_PREFIX;
    }

    /// Retorno de exceção (ARMv7-M ARM B1.5.8): valida o `EXC_RETURN`, desempilha o frame na
    /// ordem inversa do empilhamento, restaura `xPSR`/IPSR/SP e sai de Handler mode.
    private void exceptionReturn(ArmCore core, int excReturn) {
        boolean toHandler = excReturn == EXC_RETURN_HANDLER_TO_HANDLER_MSP;
        boolean fromPsp = excReturn == EXC_RETURN_THREAD_TO_HANDLER_PSP;
        if (!toHandler && excReturn != EXC_RETURN_THREAD_TO_HANDLER_MSP && !fromPsp) {
            enterException(core, MProfileException.USAGE_FAULT);
            return;
        }
        int frame = fromPsp ? processStackPointer : mainStackPointer;
        core.setRegister(0, core.memory().read32(frame + R0_OFFSET));
        core.setRegister(1, core.memory().read32(frame + R1_OFFSET));
        core.setRegister(2, core.memory().read32(frame + R2_OFFSET));
        core.setRegister(3, core.memory().read32(frame + R3_OFFSET));
        core.setRegister(12, core.memory().read32(frame + R12_OFFSET));
        core.setRegister(ArmCore.LR, core.memory().read32(frame + LR_OFFSET));
        int returnAddress = core.memory().read32(frame + RETURN_ADDRESS_OFFSET);
        int stackedXpsr = core.memory().read32(frame + XPSR_OFFSET);
        if ((stackedXpsr & XPSR_THUMB_BIT) == 0) {
            // T=0 restaurado: perfil M não tem estado ARM — UsageFault (INVSTATE) controlado.
            enterException(core, MProfileException.USAGE_FAULT);
            return;
        }
        int cpsr = (core.cpsr().get() & ~XPSR_PRESERVED_CPSR_BITS_MASK)
                | (stackedXpsr & XPSR_PRESERVED_CPSR_BITS_MASK);
        core.cpsr().set(cpsr);
        if (!activeExceptions.isEmpty()) {
            activeExceptions.pop();
        }
        currentException = stackedXpsr & XPSR_IPSR_MASK;
        core.setProgramCounter(returnAddress);

        int newSp = frame + EXCEPTION_FRAME_SIZE_BYTES
                + ((stackedXpsr & XPSR_STACK_ALIGN_BIT) != 0 ? STACK_ALIGN_ADJUSTMENT_BYTES : 0);
        if (toHandler) {
            mainStackPointer = newSp;
            core.setRegister(ArmCore.SP, mainStackPointer);
            return;
        }
        if (fromPsp) {
            processStackPointer = newSp;
            control |= CONTROL_SPSEL_BIT;
            core.setRegister(ArmCore.SP, processStackPointer);
        } else {
            mainStackPointer = newSp;
            control &= ~CONTROL_SPSEL_BIT;
            core.setRegister(ArmCore.SP, mainStackPointer);
        }
    }
}
