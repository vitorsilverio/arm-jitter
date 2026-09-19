package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.decoder.ArmDecoder;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B14.1b — `HLT` sob `ARMV8A_32` (B14.1) executa de verdade, reusando o MESMO contrato de
/// `IrOp.Breakpoint` (`BKPT`, B7.5): sem {@link BkptDispatcher} registrado para o imediato, vira
/// `UNDEFINED`; com um handler registrado, o handler é chamado. Antes desta task,
/// `StandardIrBuilder#lift` não tinha `case HALT` e o comportamento observável era um NOP
/// silencioso (a instrução era decodificada mas nunca executada, achado durante a revisão de
/// cobertura da B14.1).
class HaltExecutorTest {
    private static final int HLT_0X1234 = 0xE101_2374; // HLT #0x1234 (cond AL)

    private static ArmCore newCore() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty(), ArmArchitecture.ARMV8A_32);
        core.memory().write32(0, HLT_0X1234);
        return core;
    }

    @Test
    void haltDecodesAsHaltUnderArmv8a32() {
        ArmCore core = newCore();
        assertSame(InstructionKind.HALT,
                new ArmDecoder(ArmArchitecture.ARMV8A_32).decode(core.memory(), core.programCounter()).kind());
    }

    // ── sem BkptDispatcher instalado: vira UNDEFINED, não um NOP ─────────────────────────────
    @Test
    void haltWithoutDispatcherTakesUndefinedVector() {
        ArmCore core = newCore();

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode(), "sem handler, HLT deve virar UNDEFINED (não NOP)");
        assertEquals(0x04, core.programCounter(), "PC deve estar no vetor de instrução indefinida");
    }

    // ── com handler registrado para o imediato exato: o handler é chamado ───────────────────
    @Test
    void haltWithMatchingHandlerDispatchesToIt() {
        ArmCore core = newCore();
        int[] receivedImmediate = {-1};
        core.setBkptDispatcher(BkptDispatcher.empty());
        core.bkptDispatcher().register(0x1234, state -> {
            receivedImmediate[0] = 0x1234;
            return state;
        });

        core.step();

        assertEquals(0x1234, receivedImmediate[0],
                "o imediato de HLT deve chegar ao handler, mesmo contrato de BKPT");
        assertEquals(CpuMode.SUPERVISOR, core.mode(), "handler não pediu exceção, modo não deve mudar");
    }

    // ── com handler para OUTRO imediato, sem fallback: continua UNDEFINED ───────────────────
    @Test
    void haltWithHandlerForDifferentImmediateStillTakesUndefinedVector() {
        ArmCore core = newCore();
        core.setBkptDispatcher(BkptDispatcher.empty());
        core.bkptDispatcher().register(0x9999, state -> state);

        core.step();

        assertEquals(CpuMode.UNDEFINED, core.mode(), "imediato sem handler dedicado nem fallback -> UNDEFINED");
    }
}
