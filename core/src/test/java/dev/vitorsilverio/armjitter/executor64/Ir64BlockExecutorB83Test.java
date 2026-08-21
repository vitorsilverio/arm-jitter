package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Semântica dos ops novos da B8.3 (`BRK`/`HLT`, `CLREX`, `DAIFSet`/`DAIFClr`, `WFET`/`WFIT`,
/// `MSR (immediate)` sem efeito observável) direto no executor (interpretador = oráculo, G1).
/// Complementa {@code Aarch64DecoderCorpusTest} (decode). `BRK`/`HLT` espelham o estilo de
/// {@code Aarch64MemoryAbortTest} (entrada de exceção síncrona real via `step`, não `executeOp`
/// direto, já que o contrato inteiro — `ESR_EL1`/`ELR_EL1`/salto pro vetor — passa pelo `catch` de
/// {@link Ir64BlockExecutor#step}).
class Ir64BlockExecutorB83Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final long ESR_EC_SHIFT = 26;
    private static final long ESR_EC_BREAKPOINT = 0x3CL;
    private static final long ESR_EC_UNKNOWN_REASON = 0x00L;
    private static final long ESR_ISS_MASK = 0xFFFFL;
    private static final long HANDLER_ADDRESS = 0x400L;
    private static final int BRK_1234 = 0xd422_4680; // brk #0x1234
    private static final int HLT_5678 = 0xd44a_cf00; // hlt #0x5678
    private static final int ERET = 0xd69f_03e0;

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(0x1000);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void brkEntersEl1HandlerWithBreakpointEsrAndImmediateAsIss() {
        Aarch64Core core = newCore();
        core.memory().write32(0x0, BRK_1234);
        core.memory().write32(HANDLER_ADDRESS, ERET);
        core.markExclusiveMonitor(0x100, 8);

        EXECUTOR.step(core); // brk #0x1234

        assertTrue(core.exceptionState().inEl1());
        assertEquals(HANDLER_ADDRESS, core.pc());
        assertEquals(0L, core.exceptionState().elr1(), "ELR_EL1 é o endereço do próprio BRK");
        long esr = core.exceptionState().esr1();
        assertEquals(ESR_EC_BREAKPOINT, esr >>> ESR_EC_SHIFT);
        assertEquals(0x1234L, esr & ESR_ISS_MASK, "ISS[15:0] é o imediato de 16 bits do BRK");
        assertEquals(-1L, core.exclusiveMonitorAddress(),
                "entrada de exceção deve abrir o monitor de exclusividade (mesma disciplina de abort/IRQ)");

        EXECUTOR.step(core); // eret

        assertFalse(core.exceptionState().inEl1());
        assertEquals(0L, core.pc(), "ERET retoma exatamente no BRK (PC<-ELR_EL1)");
    }

    @Test
    void hltEntersEl1HandlerWithUnknownReasonEsr() {
        Aarch64Core core = newCore();
        core.memory().write32(0x0, HLT_5678);
        core.memory().write32(HANDLER_ADDRESS, ERET);

        EXECUTOR.step(core); // hlt #0x5678 -> UNDEFINED (sem debug state)

        assertTrue(core.exceptionState().inEl1());
        assertEquals(HANDLER_ADDRESS, core.pc());
        assertEquals(0L, core.exceptionState().elr1());
        long esr = core.exceptionState().esr1();
        assertEquals(ESR_EC_UNKNOWN_REASON, esr >>> ESR_EC_SHIFT);
    }

    @Test
    void clrexClosesExclusiveMonitorWithoutCompletingAnyStore() {
        Aarch64Core core = newCore();
        core.markExclusiveMonitor(0x100, 8);

        boolean pcChanged = EXECUTOR.executeOp(core,
                new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.CLEAR_EXCLUSIVE));

        assertFalse(pcChanged);
        assertEquals(-1L, core.exclusiveMonitorAddress());
    }

    @Test
    void daifSetMasksIrqOnlyWhenBitIIsInTheMask() {
        Aarch64Core core = newCore();
        assertFalse(core.pstate().irqDisabled());

        // mask=0b0010: só o bit I (posição 1, ordem D:A:I:F) — D/A/F ignorados (sem consumidor
        // modelado, ver javadoc de Ir64Op.InterruptMask).
        EXECUTOR.executeOp(core, new Ir64Op.InterruptMask(true, 0b0010));

        assertTrue(core.pstate().irqDisabled());
    }

    @Test
    void daifSetWithoutBitIDoesNotMaskIrq() {
        Aarch64Core core = newCore();

        EXECUTOR.executeOp(core, new Ir64Op.InterruptMask(true, 0b1101)); // D,A,F, sem I

        assertFalse(core.pstate().irqDisabled(), "D/A/F não têm consumidor modelado neste emulador");
    }

    @Test
    void daifClearUnmasksIrq() {
        Aarch64Core core = newCore();
        core.pstate().setIrqDisabled(true);

        EXECUTOR.executeOp(core, new Ir64Op.InterruptMask(false, 0b0010));

        assertFalse(core.pstate().irqDisabled());
    }

    @Test
    void wfetIsANopLikeWfeNoTimeoutModeled() {
        Aarch64Core core = newCore();

        EXECUTOR.executeOp(core, new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.NOP_HINT));

        assertEquals(CpuSleepState.RUNNING, core.sleepState());
    }

    @Test
    void wfitSleepsUntilIrqLikeWfi() {
        Aarch64Core core = newCore();

        EXECUTOR.executeOp(core, new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.WFI));

        assertEquals(CpuSleepState.HALTED, core.sleepState());
    }

    @Test
    void pstateFieldNopHasNoObservableEffect() {
        Aarch64Core core = newCore();

        boolean pcChanged = EXECUTOR.executeOp(core,
                new Ir64Op.SystemInstruction(Ir64SystemInstructionOp.PSTATE_FIELD_NOP));

        assertFalse(pcChanged);
    }
}
