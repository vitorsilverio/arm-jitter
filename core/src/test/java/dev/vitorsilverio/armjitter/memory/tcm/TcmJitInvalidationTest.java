package dev.vitorsilverio.armjitter.memory.tcm;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.InterpretedCodeEmitter;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.ir.StandardIrBuilder;
import dev.vitorsilverio.armjitter.ir.opt.IrOptimizer;
import dev.vitorsilverio.armjitter.jit.BlockCache;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B20.4, item 5 do "Inclui" / Armadilha 5: a primeira situação do projeto em que o CONTEÚDO de um
/// endereço muda sem ninguém escrever nele — reprogramar/habilitar uma TCM sobre um endereço com
/// bloco JIT já compilado do barramento físico não pode servir o bloco stale. Mesmo padrão de
/// `JitRuntimeTranslationGenerationTest` (B4.1.4), adaptado para `TcmAddressSpace`.
class TcmJitInvalidationTest {
    private static final int SIXTY_FOUR_KB = 64 * 1024;
    private static final int VA = 0x0000_0000;
    private static final int MOV_R0_10 = 0xE3A0_000A; // MOV R0, #10 (código do barramento físico)
    private static final int MOV_R0_20 = 0xE3A0_0014; // MOV R0, #20 (código da ATCM)

    private static JitRuntime newSingleInstructionRuntime() {
        return new JitRuntime(
                new BlockCache(16),
                new ArmDecoder(ArmArchitecture.ARMV7R),
                new StandardIrBuilder(),
                IrOptimizer.identity(),
                new InterpretedCodeEmitter(ArmArchitecture.ARMV7R),
                new ExecutionThreshold(1),
                1);
    }

    @Test
    void jitBlockCompiledFromBusDoesNotSurviveTcmTakingOverTheSameAddress() {
        TestAddressSpace physical = new TestAddressSpace(0x0002_0000);
        physical.put32(VA, MOV_R0_10);
        TestAddressSpace atcmMemory = new TestAddressSpace(SIXTY_FOUR_KB);
        atcmMemory.put32(0, MOV_R0_20);
        TcmAddressSpace tcm = new TcmAddressSpace(physical, atcmMemory, SIXTY_FOUR_KB,
                new TestAddressSpace(SIXTY_FOUR_KB), SIXTY_FOUR_KB);
        // ATCM ainda desabilitada: VA lê do barramento físico.

        ArmCore core = new ArmCore(tcm, SwiDispatcher.empty());
        JitRuntime runtime = newSingleInstructionRuntime();

        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10, core.register(0), "antes de habilitar a TCM, o bloco vem do barramento físico");
        assertEquals(1, runtime.blockCache().size());

        // A MESMA geração de novo: deve reaproveitar o bloco (IC hit), prova de que o cache está
        // ativo antes da reprogramação da TCM.
        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(10, core.register(0));
        assertEquals(1, runtime.blockCache().size());

        // Habilita a ATCM no MESMO endereço: o CONTEÚDO visto em VA muda sem nenhuma escrita.
        tcm.reconfigureAtcm(0, true);

        core.setRegister(0, 0);
        core.setProgramCounter(VA);
        runtime.execute(VA, core);
        assertEquals(20, core.register(0),
                "após habilitar a TCM, o bloco compilado do barramento físico NUNCA pode ser reaproveitado");
        assertEquals(2, runtime.blockCache().size(), "geração nova compila um segundo bloco, não reaproveita o antigo");
    }
}
