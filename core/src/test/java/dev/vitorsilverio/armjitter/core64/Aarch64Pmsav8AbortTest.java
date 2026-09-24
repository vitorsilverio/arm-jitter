package dev.vitorsilverio.armjitter.core64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.StandardIr64BlockLifter;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.jit64.BlockCache64;
import dev.vitorsilverio.armjitter.jit64.JitRuntime64;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.FaultStatus64;
import dev.vitorsilverio.armjitter.memory.mmu.MemoryTranslationException64;
import dev.vitorsilverio.armjitter.memory.mmu.Pmsav8AddressSpace64;
import dev.vitorsilverio.armjitter.memory.mmu.Pmsav8SystemRegisters64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// B20.8: captura de {@link MemoryTranslationException64} lançada por {@link Pmsav8AddressSpace64}
/// (PMSAv8-64, EL1) e sua conversão numa entrada de exceção síncrona EL0→EL1 REAL
/// ({@link Aarch64Core#enterMemoryAbort}) — mesmo aceite explícito do precedente VMSA64
/// ({@link Aarch64MemoryAbortTest}) e do precedente PMSAv8-32
/// (`dev.vitorsilverio.armjitter.memory.mpu.ArmCorePmsav8AbortTest`), mas com o modelo de memória
/// PMSA em vez de páginas.
///
/// ### Layout físico usado por todos os testes
/// Uma única região de EL1 cobre `[0x000, 0x1000)` com `AP=0b01` (RWX EL1+EL0), `XN=0` — cobre o
/// código principal (`0x000`) e o handler de abort (`0x400`, `VBAR_EL1` no default `0`, único vetor
/// usado é `+0x400` "Synchronous, lower EL AArch64"). `x1 = UNMAPPED_VA` (fora da região, região de
/// fundo desligada) força "No match" da Table C1-4 → {@link FaultStatus64#TRANSLATION_FAULT_L0}.
class Aarch64Pmsav8AbortTest {
    private static final int MOVZ_X1_UNMAPPED = 0xd2c2_0001; // movz x1, #0x1000, lsl #32 (x1 = 1<<44)
    private static final int LDR_X0_X1 = 0xf940_0020;       // ldr x0, [x1]
    private static final int MOVZ_X2_UNMAPPED = 0xd2c4_0002; // movz x2, #0x2000, lsl #32 (x2 = 1<<45)
    private static final int BR_X2 = 0xd61f_0040;           // br x2
    private static final int ERET = 0xd69f_03e0;

    private static final long UNMAPPED_DATA_VA = 1L << 44;
    private static final long UNMAPPED_FETCH_VA = 1L << 45;
    private static final long HANDLER_ADDRESS = 0x400L;
    private static final long ESR_EC_SHIFT = 26;
    private static final long ESR_EC_DATA_ABORT_LOWER_EL = 0x24L;
    private static final long ESR_EC_INSTRUCTION_ABORT_LOWER_EL = 0x20L;
    /// `AP=0b01` (RWX EL1+EL0, bits\[3:2\]), `XN=0`.
    private static final long PRBAR_FULL_ACCESS = 0b01L << 2;

    private record Wired(Aarch64Core core, Pmsav8AddressSpace64 space) {
    }

    private static Wired wiredCoreWithRegionCoveringLowMemory(TestAddressSpace physical) {
        AddressSpace64 wrappedPhysical = AddressSpace64.wrapping(physical);
        Aarch64Core probe = new Aarch64Core(wrappedPhysical);
        Pmsav8SystemRegisters64 bus = new Pmsav8SystemRegisters64(probe, 1);
        bus.write(Aarch64SystemRegisterId.PRSELR_EL1, 0);
        bus.write(Aarch64SystemRegisterId.PRBAR_EL1, 0x000L | PRBAR_FULL_ACCESS);
        bus.write(Aarch64SystemRegisterId.PRLAR_EL1, 0xFFFL | 1L); // limite 4KiB, EN=1
        bus.write(Aarch64SystemRegisterId.SCTLR_EL1, 1L); // M=1
        Pmsav8AddressSpace64 space = new Pmsav8AddressSpace64(wrappedPhysical, bus);
        space.setPrivileged(true);
        Aarch64Core core = new Aarch64Core(space);
        core.setSystemRegisterBus(bus);
        return new Wired(core, space);
    }

    @Test
    void dataAbortOnLoadEntersEl1HandlerAndReturnsViaEretUnderInterpretedBackend() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        physical.put32(0x0, MOVZ_X1_UNMAPPED);
        physical.put32(0x4, LDR_X0_X1);
        physical.put32((int) HANDLER_ADDRESS, ERET);
        Aarch64Core core = wiredCoreWithRegionCoveringLowMemory(physical).core();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.step(core); // movz x1, #0x1000, lsl #32
        assertEquals(UNMAPPED_DATA_VA, core.x(1));

        executor.step(core); // ldr x0, [x1] -> MemoryTranslationException64 (TRANSLATION_FAULT_L0)

        assertTrue(core.exceptionState().inEl1(), "abort de memória deve entrar em EL1");
        assertNotEquals(Aarch64ExceptionLevel.EL3, core.exceptionState().currentEl(),
                "ARMv8-R AArch64 não tem EL3 — nenhum caminho deste teste leva lá");
        assertEquals(HANDLER_ADDRESS, core.pc());
        assertEquals(4, core.exceptionState().elr1(), "ELR_EL1 deve ser o endereço da PRÓPRIA LDR faltosa");
        assertEquals(UNMAPPED_DATA_VA, core.exceptionState().far1(), "FAR_EL1 deve ser o VA faltoso");
        long ec = core.exceptionState().esr1() >>> ESR_EC_SHIFT;
        assertEquals(ESR_EC_DATA_ABORT_LOWER_EL, ec, "ESR_EL1.EC deve ser Data Abort de EL inferior (0x24)");
        long dfsc = core.exceptionState().esr1() & 0x3F;
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0.code(), dfsc,
                "DFSC deve reusar o código VMSA64 de Translation fault nível 0 (achado do DDI 0600A.d)");

        executor.step(core); // eret -> volta a EL0 na LDR faltosa

        assertFalse(core.exceptionState().inEl1());
        assertEquals(4, core.pc(), "ERET deve retomar exatamente na instrução faltosa (PC<-ELR_EL1)");
    }

    @Test
    void instructionAbortUsesInstructionAbortEsrEcAndPermissionDeniedUsesPermissionCode() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        physical.put32(0x0, MOVZ_X2_UNMAPPED);
        physical.put32(0x4, BR_X2);
        physical.put32((int) HANDLER_ADDRESS, ERET);
        Aarch64Core core = wiredCoreWithRegionCoveringLowMemory(physical).core();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();

        executor.step(core); // movz x2, #0x2000, lsl #32
        executor.step(core); // br x2 (desvio tomado; falta só ocorre no PRÓXIMO fetch)
        assertEquals(UNMAPPED_FETCH_VA, core.pc());

        executor.step(core); // fetch de UNMAPPED_FETCH_VA -> MemoryTranslationException64 (INSTRUCTION_FETCH)

        assertTrue(core.exceptionState().inEl1());
        long ec = core.exceptionState().esr1() >>> ESR_EC_SHIFT;
        assertEquals(ESR_EC_INSTRUCTION_ABORT_LOWER_EL, ec,
                "ESR_EL1.EC deve ser Instruction Abort de EL inferior (0x20), DIFERENTE do data abort");
    }

    /// **Equivalência G1**: o mesmo programa que aborta produz o MESMO estado final (`ELR_EL1`/
    /// `FAR_EL1`/`ESR_EL1`/PC pós-`ERET`) nos dois motores — INTERPRETED (teste acima) e o
    /// compilado nativo via {@link Asm64CodeEmitter}. Sem codegen NOVO aqui (fora do escopo da
    /// task B20.8, "Não inclui"): `MOVZ`/`LDR`/`ERET` já têm emissão ASM existente, só a memória por
    /// trás delas é nova.
    @Test
    void dataAbortProducesEquivalentStateUnderCompiledJitBackend() {
        TestAddressSpace physical = new TestAddressSpace(0x1000);
        physical.put32(0x0, MOVZ_X1_UNMAPPED);
        physical.put32(0x4, LDR_X0_X1);
        physical.put32((int) HANDLER_ADDRESS, ERET);
        Aarch64Core core = wiredCoreWithRegionCoveringLowMemory(physical).core();
        JitRuntime64 runtime = new JitRuntime64(
                new BlockCache64(),
                new StandardIr64BlockLifter(),
                new Ir64BlockExecutor(),
                new Asm64CodeEmitter(),
                new ExecutionThreshold(1),
                2); // trava o bloco nas 2 instruções do programa (MOVZ+LDR não é terminal)

        runtime.execute(0, core); // movz x1, ... ; ldr x0,[x1] -> abort dentro do bloco compilado

        assertTrue(core.exceptionState().inEl1(), "abort deve entrar em EL1 mesmo compilado nativamente");
        assertEquals(HANDLER_ADDRESS, core.pc());
        assertEquals(4, core.exceptionState().elr1());
        assertEquals(UNMAPPED_DATA_VA, core.exceptionState().far1());
        long dfsc = core.exceptionState().esr1() & 0x3F;
        assertEquals(FaultStatus64.TRANSLATION_FAULT_L0.code(), dfsc);
    }
}
