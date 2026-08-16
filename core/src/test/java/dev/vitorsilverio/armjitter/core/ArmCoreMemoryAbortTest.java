package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.mmu.Cp15VmsaCoprocessor;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException;
import dev.vitorsilverio.armjitter.memory.mmu.TranslatingAddressSpace;
import dev.vitorsilverio.armjitter.support.FaultingAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// B4.1.3 (RFC-SOFTMMU §3): captura de `MemoryTranslationException` no interpretador
/// (`ArmCore#executeSingleInstruction`/`IrBlockExecutor#execute`), convertida em entrada de
/// `DATA_ABORT`/`PREFETCH_ABORT` com FAR/FSR preenchidos via `MemoryAbortListener`. Page-walk e
/// permissão em si já são cobertos por `TranslatingAddressSpaceTest` (B4.1.1) — aqui o barramento
/// que lança a falta é o {@link FaultingAddressSpace}, um decorator de teste determinístico.
class ArmCoreMemoryAbortTest {
    /// `LDR r0, [r1]` (ARM): lê a memória apontada por r1 — usado para faltas de DADOS.
    private static final int LDR_R0_R1 = 0xE591_0000;

    private static Cp15VmsaCoprocessor attachCp15(ArmCore core) {
        TranslatingAddressSpace mmu = new TranslatingAddressSpace(new TestAddressSpace(4));
        Cp15VmsaCoprocessor cp15 = new Cp15VmsaCoprocessor(mmu, core);
        core.setMemoryAbortListener(cp15);
        return cp15;
    }

    @Test
    void dataAbortOnLoadEntersAbortVectorWithFarFsrAndRetryAddress() {
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0, LDR_R0_R1);
        FaultingAddressSpace memory = new FaultingAddressSpace(physical);
        memory.faultOn(0x1000, MemoryAccessType.DATA_READ, FaultStatus.SECTION_PERMISSION);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        Cp15VmsaCoprocessor cp15 = attachCp15(core);
        core.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
        core.setRegister(1, 0x1000);

        core.step();

        assertEquals(CpuMode.ABORT, core.mode());
        // DATA_ABORT: LR = endereço da instrução faltosa + 8 (ARM DDI 0100I) — a instrução está
        // em 0x0, então LR=8; "SUBS PC,LR,#8" no handler real refaria exatamente esta instrução.
        assertEquals(8, core.register(14));
        assertEquals(0x10, core.programCounter());
        assertEquals(0x9000, core.register(13));
        assertTrue(core.cpsr().irqDisabled());

        assertEquals(0x1000, cp15.read(15, 0, 6, 0, 0), "DFAR deveria ter o endereço faltoso");
        assertEquals(FaultStatus.SECTION_PERMISSION.code(), cp15.read(15, 0, 5, 0, 0), "DFSR deveria ter o FS[3:0]");
    }

    @Test
    void prefetchAbortOnInstructionFetchEntersAbortVectorWithFarFsr() {
        TestAddressSpace physical = new TestAddressSpace(128);
        FaultingAddressSpace memory = new FaultingAddressSpace(physical);
        memory.faultOn(0x2000, MemoryAccessType.INSTRUCTION_FETCH, FaultStatus.SECTION_TRANSLATION);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        Cp15VmsaCoprocessor cp15 = attachCp15(core);
        core.setBankedRegister(CpuMode.ABORT, 13, 0xA000);
        core.setProgramCounter(0x2000);

        core.step();

        assertEquals(CpuMode.ABORT, core.mode());
        // PREFETCH_ABORT: LR = endereço da instrução faltosa + 4.
        assertEquals(0x2004, core.register(14));
        assertEquals(0x0C, core.programCounter());
        assertEquals(0xA000, core.register(13));

        assertEquals(0x2000, cp15.read(15, 0, 6, 0, 2), "IFAR deveria ter o endereço faltoso");
        assertEquals(FaultStatus.SECTION_TRANSLATION.code(), cp15.read(15, 0, 5, 0, 1), "IFSR deveria ter o FS[3:0]");
    }

    @Test
    void subsPcLrAfterDataAbortRetriesFaultingInstruction() {
        // Idioma real do handler de abort do Linux: `SUBS PC, LR, #8` (retoma no endereço da
        // instrução que abortou — aqui simulado no interpretador, sem depender de um handler
        // real instalado no vetor).
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0x40, LDR_R0_R1);
        FaultingAddressSpace memory = new FaultingAddressSpace(physical);
        memory.faultOn(0x1000, MemoryAccessType.DATA_READ);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        attachCp15(core);
        core.setProgramCounter(0x40);
        core.setRegister(1, 0x1000);

        core.step();
        assertEquals(CpuMode.ABORT, core.mode());
        int lr = core.register(14);

        // SUBS PC, LR, #8 (subtrai 8 de LR e grava em PC; a forma com PC copia SPSR->CPSR — aqui
        // só o valor de PC importa para a retomada).
        core.setProgramCounter(lr - 8);

        assertEquals(0x40, core.programCounter(), "deveria retomar exatamente na instrução faltosa");
    }

    /// Regressão da lacuna de observabilidade achada na F3 (`virtual-arm-box`): sob
    /// `ArmCore#runBlocks`/`JitRuntime#execute`, nenhum evento `beforeInstruction`/
    /// `afterInstruction` dispara (blocos são executados atomicamente por desenho), o que
    /// impedia identificar o PC exato da instrução que causou uma falta de memória real dentro de
    /// um bloco — só o texto já impresso pelo guest denunciava o Oops, sem correlação com a
    /// instrução culpada. `ArmTraceListener#onMemoryAbort` fecha essa lacuna reportando o PC exato
    /// nos TRÊS caminhos (`step()`, bloco interpretado, bloco compilado/JIT), pois
    /// `ArmCore#enterMemoryAbort` é o único ponto de convergência dos três.
    @Test
    void onMemoryAbortFiresWithExactFaultingPcUnderStep() {
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0x40, LDR_R0_R1);
        FaultingAddressSpace memory = new FaultingAddressSpace(physical);
        memory.faultOn(0x1000, MemoryAccessType.DATA_READ, FaultStatus.SECTION_PERMISSION);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        attachCp15(core);
        core.setProgramCounter(0x40);
        core.setRegister(1, 0x1000);
        List<Integer> abortedAddresses = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void onMemoryAbort(ArmCore tracedCore, int instructionAddress, MemoryTranslationException fault) {
                abortedAddresses.add(instructionAddress);
            }
        });

        core.step();

        assertEquals(List.of(0x40), abortedAddresses);
    }

    @Test
    void onMemoryAbortFiresWithExactFaultingPcUnderInterpretedBlock() {
        TestAddressSpace physical = new TestAddressSpace(128);
        // Precede a instrução faltosa com uma que não falta, para provar que o PC reportado é o
        // da instrução culpada dentro do bloco (não o início do bloco).
        physical.put32(0x40, 0xE3A0_0001); // MOV r0, #1
        physical.put32(0x44, LDR_R0_R1);
        FaultingAddressSpace memory = new FaultingAddressSpace(physical);
        memory.faultOn(0x1000, MemoryAccessType.DATA_READ, FaultStatus.SECTION_PERMISSION);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        attachCp15(core);
        core.setProgramCounter(0x40);
        core.setRegister(1, 0x1000);
        List<Integer> abortedAddresses = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void onMemoryAbort(ArmCore tracedCore, int instructionAddress, MemoryTranslationException fault) {
                abortedAddresses.add(instructionAddress);
            }
        });
        JitRuntime interpreted = JitRuntimeFactory.interpretedArmThumb(16, 1);

        core.runBlock(interpreted);

        assertEquals(List.of(0x44), abortedAddresses);
    }

    @Test
    void onMemoryAbortFiresWithExactFaultingPcUnderCompiledJitBlock() {
        TestAddressSpace physical = new TestAddressSpace(128);
        physical.put32(0x40, 0xE3A0_0001); // MOV r0, #1
        physical.put32(0x44, LDR_R0_R1);
        FaultingAddressSpace memory = new FaultingAddressSpace(physical);
        memory.faultOn(0x1000, MemoryAccessType.DATA_READ, FaultStatus.SECTION_PERMISSION);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
        attachCp15(core);
        core.setProgramCounter(0x40);
        core.setRegister(1, 0x1000);
        List<Integer> abortedAddresses = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void onMemoryAbort(ArmCore tracedCore, int instructionAddress, MemoryTranslationException fault) {
                abortedAddresses.add(instructionAddress);
            }
        });
        // hotThreshold=1: compila de forma síncrona já na primeira execução deste bloco (caminho
        // clássico sem tiering) — garante que o bloco realmente rode via AsmBlockCompiler/bytecode,
        // não pelo fallback frio interpretado do JitRuntime.
        JitRuntime compiled = JitRuntimeFactory.armThumb(16, 1);

        core.runBlock(compiled);

        assertEquals(List.of(0x44), abortedAddresses);
    }
}
