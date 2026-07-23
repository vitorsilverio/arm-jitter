package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Testes mínimos da B7.3: VTOR, NVIC (habilitação/pendência/prioridade/preempção/NMI),
/// PRIMASK, PendSV via ICSR, SysTick e uma integração "mini kernel" — ver "Testes mínimos" em
/// `tasks/trilha-b-arquiteturas/b7.3-scs-nvic-systick.md`.
///
/// A entrada/saída de exceção em si (empilhamento, `EXC_RETURN`, equivalência interpretado×ASM)
/// já tem cobertura de G1 na B7.2 (`MProfileExceptionModelTest`) — o mecanismo é o mesmo
/// {@link MProfileExceptionModel#enterException(ArmCore, int)} usado ali, backend-agnóstico.
/// Esta suíte foca no que é novo: pendência/prioridade/NVIC/SysTick, exercitados via
/// {@link ArmCore#step()} (interpretador frio, único caminho que dispara
/// {@code servicePendingIrq} instrução a instrução).
class MProfileSystemControlTest {
    private static final int MEMORY_SIZE = 0x8000;
    /// `b .` (Thumb): branch para si mesmo — usado como corpo inerte de handler/loop.
    private static final short BRANCH_TO_SELF = (short) 0xE7FE;

    private static final int NVIC_ISER0_OFFSET = 0x100;
    private static final int NVIC_ISPR0_OFFSET = 0x200;
    private static final int NVIC_ICPR0_OFFSET = 0x280;
    private static final int NVIC_IPR0_OFFSET = 0x400;
    private static final int ICSR_OFFSET = 0xD04;
    private static final int VTOR_OFFSET = 0xD08;
    private static final int SYST_CSR_OFFSET = 0x010;
    private static final int SYST_RVR_OFFSET = 0x014;
    private static final int SYST_CVR_OFFSET = 0x018;
    private static final int ICSR_PENDSVSET_BIT = 1 << 28;
    private static final int SYST_CSR_ENABLE_BIT = 1;
    private static final int SYST_CSR_TICKINT_BIT = 1 << 1;
    private static final int SYST_CSR_COUNTFLAG_BIT = 1 << 16;

    private static TestAddressSpace newMemory() {
        return new TestAddressSpace(MEMORY_SIZE);
    }

    private static ArmCore newCore(TestAddressSpace memory) {
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        core.cpsr().setThumbMode(true);
        return core;
    }

    private static void writeVector(TestAddressSpace memory, int exceptionNumber, int handlerAddress) {
        memory.put32(4 * exceptionNumber, handlerAddress | 1);
        memory.put16(handlerAddress, BRANCH_TO_SELF);
    }

    /// Teste mínimo #1: VTOR — escrever, disparar exceção, vetor buscado da base nova.
    @Test
    void vtorRelocatesVectorTable() {
        TestAddressSpace memory = newMemory();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        MProfileSystemControl scs = new MProfileSystemControl(model, 0, () -> {});

        int newBase = 0x1000;
        int handler = 0x2000;
        memory.put32(newBase + 4 * MProfileException.SVCALL.number(), handler | 1);
        memory.put16(handler, BRANCH_TO_SELF);
        core.setRegister(13, 0x7000);

        scs.write32(VTOR_OFFSET, newBase);
        assertEquals(newBase, scs.read32(VTOR_OFFSET));

        model.enterException(core, MProfileException.SVCALL);
        assertEquals(handler, core.programCounter());
    }

    /// Teste mínimo #2: NVIC — ISER habilita, ISPR pende, IRQ16+n entra com o número certo no
    /// IPSR; ICPR despende antes de entrar → não entra.
    @Test
    void nvicEnablePendAndClear() {
        TestAddressSpace memory = newMemory();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        MProfileSystemControl scs = new MProfileSystemControl(model, 4, () -> {});
        core.setRegister(13, 0x7000);
        core.setProgramCounter(0x1000);
        memory.put16(0x1000, BRANCH_TO_SELF);

        int irq2Number = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + 2;
        writeVector(memory, irq2Number, 0x2000);

        scs.write32(NVIC_ISER0_OFFSET, 1 << 2);
        scs.write32(NVIC_ISPR0_OFFSET, 1 << 2);
        core.step();

        assertEquals(irq2Number, model.currentException(), "IPSR deveria ser o número da IRQ2 (16+2)");
        assertEquals(0x2000, core.programCounter());

        // Cenário 2: habilitar+pender e limpar ANTES de dar a chance de entrar.
        TestAddressSpace memory2 = newMemory();
        ArmCore core2 = newCore(memory2);
        MProfileExceptionModel model2 = new MProfileExceptionModel();
        core2.setExceptionModel(model2);
        MProfileSystemControl scs2 = new MProfileSystemControl(model2, 4, () -> {});
        core2.setRegister(13, 0x7000);
        core2.setProgramCounter(0x1000);
        memory2.put16(0x1000, BRANCH_TO_SELF);
        writeVector(memory2, irq2Number, 0x2000);

        scs2.write32(NVIC_ISER0_OFFSET, 1 << 2);
        scs2.write32(NVIC_ISPR0_OFFSET, 1 << 2);
        scs2.write32(NVIC_ICPR0_OFFSET, 1 << 2);
        core2.step();

        assertEquals(0, model2.currentException(), "ICPR limpou a pendência antes de entrar");
        assertEquals(0x1000, core2.programCounter(), "sem exceção, o loop principal continua");
    }

    /// Teste mínimo #3: prioridade — A(0x40) ativa; B(0x80) pende → NÃO preempta; C(0x00) pende
    /// → preempta (entrada aninhada); NMI preempta qualquer um.
    @Test
    void priorityPreemption() {
        TestAddressSpace memory = newMemory();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        MProfileSystemControl scs = new MProfileSystemControl(model, 3, () -> {});
        core.setRegister(13, 0x7000);
        core.setProgramCounter(0x1000);
        memory.put16(0x1000, BRANCH_TO_SELF);

        int irqA = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER;     // 16
        int irqB = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + 1; // 17
        int irqC = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER + 2; // 18
        writeVector(memory, irqA, 0x2000);
        writeVector(memory, irqB, 0x3000);
        writeVector(memory, irqC, 0x4000);
        writeVector(memory, MProfileException.NMI.number(), 0x5000);

        scs.write32(NVIC_ISER0_OFFSET, 0b111);
        scs.write32(NVIC_IPR0_OFFSET, 0x40 | (0x80 << 8) | (0x00 << 16));

        scs.write32(NVIC_ISPR0_OFFSET, 1); // pende A
        core.step();
        assertEquals(irqA, model.currentException(), "A deveria entrar (Thread mode preempta sempre)");

        scs.write32(NVIC_ISPR0_OFFSET, 1 << 1); // pende B (0x80, menos urgente que A/0x40)
        core.step(); // executa o `b .` do handler de A; não deveria preemptar
        assertEquals(irqA, model.currentException(), "B não deveria preemptar A (menos urgente)");
        assertTrue(model.pending(irqB), "B continua pendente, só não ativou ainda");

        scs.write32(NVIC_ISPR0_OFFSET, 1 << 2); // pende C (0x00, mais urgente que A/0x40)
        core.step();
        assertEquals(irqC, model.currentException(), "C deveria preemptar A (mais urgente)");
        assertTrue(model.active(irqA), "A continua ativa, aninhada abaixo de C");

        model.pendException(MProfileException.NMI.number());
        core.step();
        assertEquals(MProfileException.NMI.number(), model.currentException(), "NMI preempta qualquer um");
    }

    /// Teste mínimo #4: PRIMASK=1 segura IRQ mas não NMI.
    @Test
    void primaskMasksConfigurableButNotNmi() {
        TestAddressSpace memory = newMemory();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        MProfileSystemControl scs = new MProfileSystemControl(model, 1, () -> {});
        core.setRegister(13, 0x7000);
        core.setProgramCounter(0x1000);
        memory.put16(0x1000, BRANCH_TO_SELF);

        int irqA = MProfileExceptionModel.FIRST_EXTERNAL_EXCEPTION_NUMBER;
        writeVector(memory, irqA, 0x2000);
        writeVector(memory, MProfileException.NMI.number(), 0x5000);

        model.setPrimask(1);
        scs.write32(NVIC_ISER0_OFFSET, 1);
        scs.write32(NVIC_ISPR0_OFFSET, 1);
        core.step();
        assertEquals(0, model.currentException(), "PRIMASK=1 segura exceção de prioridade configurável");

        model.pendException(MProfileException.NMI.number());
        core.step();
        assertEquals(MProfileException.NMI.number(), model.currentException(), "PRIMASK não mascara NMI");
    }

    /// Teste mínimo #5: PendSV via ICSR.PENDSVSET entra ao final do handler ativo (retorno → entra).
    @Test
    void pendSvActivatesAfterActiveHandlerReturns() {
        TestAddressSpace memory = newMemory();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        MProfileSystemControl scs = new MProfileSystemControl(model, 0, () -> {});
        core.setRegister(13, 0x7000);

        int svcHandler = 0x2000;
        int pendSvHandler = 0x3000;
        writeVector(memory, MProfileException.SVCALL.number(), svcHandler);
        writeVector(memory, MProfileException.PENDSV.number(), pendSvHandler);
        memory.put16(svcHandler, (short) 0x4770); // bx lr

        model.enterException(core, MProfileException.SVCALL);
        assertEquals(svcHandler, core.programCounter());

        scs.write32(ICSR_OFFSET, ICSR_PENDSVSET_BIT);
        core.step(); // executa "bx lr": retorna a Thread mode, ainda não checou pendência de novo
        assertEquals(0, model.currentException(), "handler do SVCALL retornou para Thread mode");

        core.setProgramCounter(0x1000);
        memory.put16(0x1000, BRANCH_TO_SELF);
        core.step(); // agora sim, checa pendência antes de decodificar
        assertEquals(MProfileException.PENDSV.number(), model.currentException(), "PendSV entra após a Thread liberar");
        assertEquals(pendSvHandler, core.programCounter());
    }

    /// Teste mínimo #6: SysTick — RVR=100, ENABLE+TICKINT; `tick(250)` gera 2 recargas
    /// (250 = 100+100+50), COUNTFLAG lido-e-limpo conforme B3.3. Também prova que o evento de
    /// recarga dispara mesmo alimentando `tick()` aos poucos (1 ciclo por chamada), não só
    /// quando um único `tick()` consome o período inteiro.
    @Test
    void sysTickReloadsAndPendsTwice() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        MProfileSystemControl scs = new MProfileSystemControl(model, 0, () -> {});

        scs.write32(SYST_RVR_OFFSET, 100);
        scs.write32(SYST_CSR_OFFSET, SYST_CSR_ENABLE_BIT | SYST_CSR_TICKINT_BIT);
        scs.tick(250);

        assertEquals(50, scs.read32(SYST_CVR_OFFSET), "2 recargas de 100 ciclos consomem 200 de 250, sobram 50");
        assertTrue(model.pending(MProfileException.SYSTICK.number()), "TICKINT pendeu a SYSTICK");

        int csr = scs.read32(SYST_CSR_OFFSET);
        assertEquals(SYST_CSR_COUNTFLAG_BIT, csr & SYST_CSR_COUNTFLAG_BIT, "COUNTFLAG setado após underflow");
        int csrAfterRead = scs.read32(SYST_CSR_OFFSET);
        assertEquals(0, csrAfterRead & SYST_CSR_COUNTFLAG_BIT, "leitura limpa COUNTFLAG");

        scs.tick(1);
        assertTrue(scs.read32(SYST_CVR_OFFSET) < 50);
        // Escrever QUALQUER valor em CVR zera E limpa COUNTFLAG (armadilha da B3.3).
        scs.write32(SYST_CVR_OFFSET, 0xDEAD);
        assertEquals(0, scs.read32(SYST_CVR_OFFSET));

        model.clearPending(MProfileException.SYSTICK.number());
        MProfileSystemControl fedOneCycleAtATime = new MProfileSystemControl(model, 0, () -> {});
        fedOneCycleAtATime.write32(SYST_RVR_OFFSET, 100);
        fedOneCycleAtATime.write32(SYST_CSR_OFFSET, SYST_CSR_ENABLE_BIT | SYST_CSR_TICKINT_BIT);
        for (int i = 0; i < 100; i++) {
            fedOneCycleAtATime.tick(1);
        }
        assertTrue(model.pending(MProfileException.SYSTICK.number()),
                "a recarga tem que disparar mesmo alimentando 1 ciclo por chamada");
    }

    /// Teste de integração: mini "kernel" — loop principal + handler de SysTick que incrementa
    /// um contador em RAM. Roda N ciclos; o contador bate com o número de recargas esperado.
    @Test
    void miniKernelCountsSysTickTicks() {
        TestAddressSpace memory = newMemory();
        ArmCore core = newCore(memory);
        MProfileExceptionModel model = new MProfileExceptionModel();
        core.setExceptionModel(model);
        MProfileSystemControl scs = new MProfileSystemControl(model, 0, () -> {});

        int loopAddress = 0x1000;
        int handlerAddress = 0x2000;
        int counterAddress = 0x800;
        int counterPointerRegister = 1;
        memory.put16(loopAddress, BRANCH_TO_SELF);
        // ldr r0,[r1] ; adds r0,#1 ; str r0,[r1] ; bx lr
        memory.put16(handlerAddress, (short) 0x6808);
        memory.put16(handlerAddress + 2, (short) 0x3001);
        memory.put16(handlerAddress + 4, (short) 0x6008);
        memory.put16(handlerAddress + 6, (short) 0x4770);
        writeVectorWithoutBody(memory, MProfileException.SYSTICK.number(), handlerAddress);

        core.setRegister(13, 0x7000);
        core.setRegister(counterPointerRegister, counterAddress);
        core.setProgramCounter(loopAddress);

        int reloadValue = 100;
        scs.write32(SYST_RVR_OFFSET, reloadValue);
        scs.write32(SYST_CSR_OFFSET, SYST_CSR_ENABLE_BIT | SYST_CSR_TICKINT_BIT);

        int expectedTicks = 5;
        // O interpretador frio não custeia ciclos de fetch/branch simples neste barramento de
        // teste (`TestAddressSpace.providesAccessCycles()==false`) — o "ciclo consumido" que o
        // hospedeiro informa ao `tick()` é uma convenção dele, não algo que `core.cycles()`
        // reflita instrução a instrução aqui. Fixamos 1 ciclo/step, o suficiente para exercitar
        // reload+pend+entrada+handler de ponta a ponta.
        int cyclesPerStep = 1;
        // Cada recarga consome reloadValue ciclos; folga generosa para o handler
        // (LDR/ADDS/STR/BX) rodar entre elas. Para assim que o contador bate o esperado, para
        // não arriscar disparar uma 6ª recarga por causa da folga.
        int maxSteps = expectedTicks * reloadValue * 3;
        for (int i = 0; i < maxSteps; i++) {
            core.step();
            scs.tick(cyclesPerStep);
            if (memory.read32(counterAddress) >= expectedTicks) {
                break;
            }
        }

        assertEquals(expectedTicks, memory.read32(counterAddress), "handler do SysTick rodou exatamente expectedTicks vezes");
    }

    private static void writeVectorWithoutBody(TestAddressSpace memory, int exceptionNumber, int handlerAddress) {
        memory.put32(4 * exceptionNumber, handlerAddress | 1);
    }
}
