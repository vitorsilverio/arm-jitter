package dev.vitorsilverio.armjitter.memory.mpu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.AsmCodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmTraceListener;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.jit.CompiledBlock;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B20.3: captura de {@link PmsaAccessException} nos motores (`ArmCore#executeSingleInstruction`,
/// `IrBlockExecutor#execute`, `AsmBlockCompiler` nativo), convertida em entrada de
/// `DATA_ABORT`/`PREFETCH_ABORT` com `DFAR`/`DFSR`/`IFAR`/`IFSR` preenchidos via
/// {@link Pmsav7MpuCoprocessor} (`MemoryAbortListener`) — espelha
/// `dev.vitorsilverio.armjitter.core.ArmCoreMemoryAbortTest` (B4.1.3), trocando VMSA por PMSA.
class ArmCorePmsaAbortTest {
    private static final int LDR_R0_R1 = 0xE591_0000;
    private static final int STR_R0_R1 = 0xE581_0000;
    private static final int DFSR_WNR_BIT = 1 << 11;
    /// Endereço fora de qualquer região programada nos testes: `BACKGROUND` fault garantido.
    private static final int UNMAPPED_ADDRESS = 0x9000_0000;

    private static ArmCore coreWithRegionCoveringLowMemory(TestAddressSpace physical) {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        // Região 0: 64KiB a partir de 0, RWX plena — cobre código e pilha dos testes; o endereço de
        // falta fica bem fora dela (background, sem SCTLR.BR).
        mpu.setRgnr(0);
        mpu.setDrbar(0);
        mpu.setDrsr(0b1 | (15 << 1)); // EN=1, RSIZE=15 -> 2^16 bytes
        mpu.setDracr(0b011 << 8); // AP=3 (RWX)
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        ArmCore core = new ArmCore(space, SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        // Pmsav7MpuCoprocessor#<init> desliga a MPU (reset real de hardware) — habilitar DEPOIS
        // dele, senão o construtor pisa o `true` de volta para `false`.
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, core, ArmArchitecture.ARMV7R);
        mpu.setMpuEnabled(true);
        core.setCoprocessorBus(cp15);
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(space);
        return core;
    }

    private record Wired(ArmCore core, Pmsav7MpuCoprocessor cp15) {
    }

    private static Wired wiredCoreWithRegionCoveringLowMemory(TestAddressSpace physical) {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setRgnr(0);
        mpu.setDrbar(0);
        mpu.setDrsr(0b1 | (15 << 1)); // EN=1, RSIZE=15 -> 2^16 bytes
        mpu.setDracr(0b011 << 8); // AP=3 (RWX)
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        ArmCore core = new ArmCore(space, SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, core, ArmArchitecture.ARMV7R);
        mpu.setMpuEnabled(true);
        core.setCoprocessorBus(cp15);
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(space);
        return new Wired(core, cp15);
    }

    @Test
    void dataAbortOnLoadEntersAbortVectorWithFarFsrAndRetryAddress() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        physical.put32(0, LDR_R0_R1);
        ArmCore core = coreWithRegionCoveringLowMemory(physical);
        core.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
        core.setRegister(1, UNMAPPED_ADDRESS);

        core.step();

        assertEquals(CpuMode.ABORT, core.mode());
        assertEquals(8, core.register(14));
        assertEquals(0x10, core.programCounter());
        assertEquals(0x9000, core.register(13));
        assertTrue(core.cpsr().irqDisabled());
    }

    @Test
    void dataAbortOnStoreSetsWnrBitInDfsr() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        physical.put32(0, STR_R0_R1);
        Wired wired = wiredCoreWithRegionCoveringLowMemory(physical);
        ArmCore core = wired.core();
        Pmsav7MpuCoprocessor cp15 = wired.cp15();
        core.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
        core.setRegister(1, UNMAPPED_ADDRESS);

        core.step();

        int dfsr = cp15.read(15, 0, 5, 0, 0);
        assertEquals(PmsaFaultStatus.BACKGROUND.code(), dfsr & ~DFSR_WNR_BIT, "FS deveria continuar intacto");
        assertNotEquals(0, dfsr & DFSR_WNR_BIT, "WnR deveria estar ligado numa falta de ESCRITA");
        assertEquals(UNMAPPED_ADDRESS, cp15.read(15, 0, 6, 0, 0), "DFAR deveria ter o endereço faltoso");
    }

    @Test
    void dataAbortOnLoadLeavesWnrBitClearInDfsr() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        physical.put32(0, LDR_R0_R1);
        Wired wired = wiredCoreWithRegionCoveringLowMemory(physical);
        ArmCore core = wired.core();
        Pmsav7MpuCoprocessor cp15 = wired.cp15();
        core.setBankedRegister(CpuMode.ABORT, 13, 0x9000);
        core.setRegister(1, UNMAPPED_ADDRESS);

        core.step();

        int dfsr = cp15.read(15, 0, 5, 0, 0);
        assertEquals(0, dfsr & DFSR_WNR_BIT, "WnR deveria continuar desligado numa falta de LEITURA");
    }

    @Test
    void prefetchAbortOnInstructionFetchEntersAbortVectorWithFarFsr() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        Wired wired = wiredCoreWithRegionCoveringLowMemory(physical);
        ArmCore core = wired.core();
        Pmsav7MpuCoprocessor cp15 = wired.cp15();
        core.setBankedRegister(CpuMode.ABORT, 13, 0xA000);
        core.setProgramCounter(UNMAPPED_ADDRESS);

        core.step();

        assertEquals(CpuMode.ABORT, core.mode());
        assertEquals(UNMAPPED_ADDRESS + 4, core.register(14));
        assertEquals(0x0C, core.programCounter());
        assertEquals(0xA000, core.register(13));
        assertEquals(UNMAPPED_ADDRESS, cp15.read(15, 0, 6, 0, 2), "IFAR deveria ter o endereço faltoso");
        assertEquals(PmsaFaultStatus.BACKGROUND.code(), cp15.read(15, 0, 5, 0, 1), "IFSR deveria ter o FS");
    }

    @Test
    void onMemoryAbortFiresWithExactFaultingPcUnderStep() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        physical.put32(0x40, LDR_R0_R1);
        ArmCore core = coreWithRegionCoveringLowMemory(physical);
        core.setProgramCounter(0x40);
        core.setRegister(1, UNMAPPED_ADDRESS);
        List<Integer> abortedAddresses = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void onMemoryAbort(ArmCore tracedCore, int instructionAddress, PmsaAccessException fault) {
                abortedAddresses.add(instructionAddress);
            }
        });

        core.step();

        assertEquals(List.of(0x40), abortedAddresses);
    }

    @Test
    void onMemoryAbortFiresWithExactFaultingPcUnderInterpretedBlock() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        physical.put32(0x40, 0xE3A0_0001); // MOV r0, #1
        physical.put32(0x44, LDR_R0_R1);
        ArmCore core = coreWithRegionCoveringLowMemory(physical);
        core.setProgramCounter(0x40);
        core.setRegister(1, UNMAPPED_ADDRESS);
        List<Integer> abortedAddresses = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void onMemoryAbort(ArmCore tracedCore, int instructionAddress, PmsaAccessException fault) {
                abortedAddresses.add(instructionAddress);
            }
        });
        JitRuntime interpreted = JitRuntimeFactory.interpretedArmThumb(16, 1);

        core.runBlock(interpreted);

        assertEquals(List.of(0x44), abortedAddresses);
    }

    /// Compila o bloco DIRETAMENTE via {@link AsmCodeEmitter} (mesmo padrão do
    /// `MemoryAbortEquivalenceTest`, B4.1.3) em vez de passar pelo `JitRuntime` tiered — evita
    /// depender da promoção assíncrona a quente para exercitar o handler nativo de
    /// `PmsaAccessException` do {@link dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler}
    /// de forma determinística.
    @Test
    void onMemoryAbortFiresWithExactFaultingPcUnderCompiledJitBlock() {
        TestAddressSpace physical = new TestAddressSpace(0x1_0000);
        physical.put32(0x40, 0xE3A0_0001); // MOV r0, #1
        physical.put32(0x44, LDR_R0_R1);
        ArmCore core = coreWithRegionCoveringLowMemory(physical);
        core.setProgramCounter(0x40);
        core.setRegister(1, UNMAPPED_ADDRESS);
        List<Integer> abortedAddresses = new ArrayList<>();
        core.setTraceListener(new ArmTraceListener() {
            @Override
            public void onMemoryAbort(ArmCore tracedCore, int instructionAddress, PmsaAccessException fault) {
                abortedAddresses.add(instructionAddress);
            }
        });
        AsmCodeEmitter asmEmitter = new AsmCodeEmitter(ArmArchitecture.ARMV7R);
        IrBlock block = new StandardIrBlockLifter(new ArmDecoder(ArmArchitecture.ARMV7R), new StandardIrBuilder())
                .lift(core.memory(), 0x40, 2, 0);
        assertTrue(asmEmitter.isNativeSupported(block));
        CompiledBlock compiled = asmEmitter.emit(block);

        compiled.execute(core);

        assertEquals(List.of(0x44), abortedAddresses);
    }
}
