package dev.vitorsilverio.armjitter.jit64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.codegen64.Asm64CodeEmitter;
import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.jit.ExecutionThreshold;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

/// F11 (2026-10-02): achado real — `JitRuntime64#execute` nunca consultava `interruptLine` para
/// blocos JÁ compilados (só o caminho frio, `coldExecutor.step`, servia IRQ). O `kernel8.img` do
/// Raspberry Pi 3 ficava preso em laços quentes esperando um tick de timer, onde o interpretado
/// seguia. Lockstep interpretado×JIT apontou a divergência no primeiro bloco quente com IRQ
/// pendente.
class JitRuntime64InterruptTest {
    private static final int BRANCH_TO_SELF = 0x1400_0000; // b .
    private static final long VBAR = 0x100;
    /// `VBAR + 0x480`: IRQ vinda de "lower EL AArch64" (o core de teste não está em EL1).
    private static final long IRQ_VECTOR = VBAR + 0x480;

    private static Aarch64Core newCore() {
        Aarch64Core core = new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(0x1000)));
        core.memory().write32(0, BRANCH_TO_SELF);
        core.exceptionState().setVbar1(VBAR);
        return core;
    }

    private static JitRuntime64 newRuntime() {
        return new JitRuntime64(new BlockCache64(), new Asm64CodeEmitter(), new ExecutionThreshold(1));
    }

    /// Esquenta o laço `b .` até virar bloco compilado no cache.
    private static void heatUp(JitRuntime64 runtime, Aarch64Core core) {
        runtime.execute(0, core);
        runtime.execute(0, core);
        assertEquals(1, runtime.blockCache().size(), "o laço deve estar compilado antes do teste");
    }

    @Test
    void pendingIrqInterruptsAnAlreadyCompiledBlock() {
        Aarch64Core core = newCore();
        JitRuntime64 runtime = newRuntime();
        heatUp(runtime, core);

        core.setInterruptLine(true);
        int cycles = runtime.execute(core.pc(), core);

        assertEquals(1, cycles);
        assertEquals(IRQ_VECTOR, core.pc(), "a IRQ deve ser entregue no limite do bloco compilado");
        assertEquals(0L, core.exceptionState().elr1(), "ELR_EL1 é o PC onde o laço foi interrompido");
        assertTrue(core.pstate().irqDisabled());
    }

    @Test
    void maskedIrqDoesNotInterruptACompiledBlock() {
        Aarch64Core core = newCore();
        JitRuntime64 runtime = newRuntime();
        heatUp(runtime, core);
        core.pstate().setIrqDisabled(true);

        core.setInterruptLine(true);
        runtime.execute(core.pc(), core);

        assertEquals(0L, core.pc(), "IRQ mascarada: o laço continua rodando");
    }

    @Test
    void sleepingCoreStaysAsleepWithoutIrqAndWakesWithIt() {
        Aarch64Core core = newCore();
        JitRuntime64 runtime = newRuntime();
        heatUp(runtime, core);
        core.setSleepState(CpuSleepState.HALTED);

        assertEquals(1, runtime.execute(0, core), "dormindo: só consome um ciclo");
        assertEquals(CpuSleepState.HALTED, core.sleepState());
        assertEquals(0L, core.pc());

        core.setInterruptLine(true);
        runtime.execute(0, core);
        assertEquals(CpuSleepState.RUNNING, core.sleepState());
        assertEquals(IRQ_VECTOR, core.pc());
    }
}
