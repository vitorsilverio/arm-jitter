package dev.vitorsilverio.armjitter.jit;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.memory.mpu.PmsaAddressSpace;
import dev.vitorsilverio.armjitter.memory.mpu.Pmsav7MpuCoprocessor;
import dev.vitorsilverio.armjitter.memory.mpu.Pmsav7MpuRegisters;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// B20.3: `PmsaAccessException` levantada DENTRO do próprio `lift()` (busca adiantada da PRIMEIRA
/// instrução de um bloco nunca-antes-visto, bloco ainda vazio — `StandardIrBlockLifter` propaga em
/// vez de cortar) — caminho DIFERENTE do coberto por `ArmCorePmsaAbortTest` (que falha durante a
/// EXECUÇÃO de um Load, não durante o fetch da instrução em si). Espelha o mesmo gap de cobertura
/// que já existia (não coberto) para `MemoryTranslationException` nestes dois `catch` do
/// `JitRuntime` — ver `## Resultado` da B20.3 para a nota completa.
class JitRuntimePmsaLiftFaultTest {
    /// Endereço fora de qualquer região programada: `PREFETCH_ABORT`/`BACKGROUND` no fetch.
    private static final int UNMAPPED_FETCH_ADDRESS = 0x9000_0000;

    private static ArmCore pmsaCoreWithNoRegions(TestAddressSpace physical) {
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1); // região 0 nunca habilitada: tudo é background
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        ArmCore core = new ArmCore(space, SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, core, ArmArchitecture.ARMV7R);
        mpu.setMpuEnabled(true);
        core.setCoprocessorBus(cp15);
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(space);
        return core;
    }

    /// Caminho CLÁSSICO (`coldEmitter == null`, `JitRuntimeFactory#interpretedArmThumb`):
    /// `hotThreshold=0` faz o PRIMEIRO `execute()` já cair no `lift()` sem passar pelo interpretado
    /// frio de `ArmCore#stepReturningInternalCycles` — a falta acontece ali, bloco ainda vazio.
    @Test
    void classicPathLiftFaultEntersAbortAndReportsSingleCycle() {
        TestAddressSpace physical = new TestAddressSpace(64);
        ArmCore core = pmsaCoreWithNoRegions(physical);
        core.setBankedRegister(CpuMode.ABORT, 13, 0xA000);
        JitRuntime classic = JitRuntimeFactory.interpretedArmThumb(16, 0);

        int cycles = classic.execute(UNMAPPED_FETCH_ADDRESS, core);

        assertEquals(1, cycles, "LIFT_FAULT_CYCLES");
        assertEquals(CpuMode.ABORT, core.mode());
    }

    /// Caminho TIERED (`coldEmitter != null`, `JitRuntimeFactory#armThumb`): a PRIMEIRA visão de um
    /// PC (`entry == null` em `executeTiered`) chama `lift()` incondicionalmente, antes de qualquer
    /// checagem de threshold.
    @Test
    void tieredPathFirstSightLiftFaultEntersAbortAndReportsSingleCycle() {
        TestAddressSpace physical = new TestAddressSpace(64);
        ArmCore core = pmsaCoreWithNoRegions(physical);
        core.setBankedRegister(CpuMode.ABORT, 13, 0xA000);
        JitRuntime tiered = JitRuntimeFactory.armThumb(16, 1, ArmArchitecture.ARMV7R);

        int cycles = tiered.execute(UNMAPPED_FETCH_ADDRESS, core);

        assertEquals(1, cycles, "LIFT_FAULT_CYCLES");
        assertEquals(CpuMode.ABORT, core.mode());
    }

    /// Caminho de PROMOÇÃO (tier frio -> quente): `lift()` é chamado uma SEGUNDA vez, síncrono na
    /// própria chamada de `execute()` (argumento de `submitCompile`, avaliado antes de qualquer
    /// submissão ao pool de background) — se a MPU for reprogramada entre a 1ª execução (que
    /// populou o tier frio com sucesso) e a 2ª (que tenta promover), a falta é DESCARTADA
    /// (`compilingColdBlocks.remove`), e o bloco frio já cacheado continua rodando normalmente
    /// depois — mesmo comentário do `JitRuntime` real. Aqui a memória fica desmapeada de vez, então
    /// a 2ª chamada aborta tanto na promoção quanto na execução do bloco frio em si; o que este
    /// teste prova é que a falta de PROMOÇÃO não escapa como `RuntimeException` não tratada.
    @Test
    void promotionRelistFaultIsSwallowedAndDoesNotEscapeExecute() {
        TestAddressSpace physical = new TestAddressSpace(64);
        physical.put32(0, 0xE320_F000); // NOP: primeira execução não deve abortar
        Pmsav7MpuRegisters mpu = new Pmsav7MpuRegisters(1);
        mpu.setRgnr(0);
        mpu.setDrbar(0);
        mpu.setDrsr(0b1 | (11 << 1)); // EN=1, RSIZE=11 -> 4096 bytes, cobre o endereço 0
        mpu.setDracr(0b011 << 8); // AP=3 (RWX)
        PmsaAddressSpace space = new PmsaAddressSpace(physical, mpu);
        ArmCore core = new ArmCore(space, SwiDispatcher.empty(), ArmArchitecture.ARMV7R);
        Pmsav7MpuCoprocessor cp15 = new Pmsav7MpuCoprocessor(mpu, core, ArmArchitecture.ARMV7R);
        mpu.setMpuEnabled(true);
        core.setCoprocessorBus(cp15);
        core.setMemoryAbortListener(cp15);
        core.setModeChangeListener(space);
        core.setBankedRegister(CpuMode.ABORT, 13, 0xA000);
        JitRuntime tiered = JitRuntimeFactory.armThumb(16, 1, ArmArchitecture.ARMV7R);

        tiered.execute(0, core); // 1ª execução: sucesso, popula o tier frio
        // Fecha a região (AP=0): a 2ª execução tenta promover -> re-lift falha -> descartada.
        mpu.setRgnr(0);
        mpu.setDracr(0);
        core.setProgramCounter(0);

        assertDoesNotThrow(() -> tiered.execute(0, core));
    }
}
