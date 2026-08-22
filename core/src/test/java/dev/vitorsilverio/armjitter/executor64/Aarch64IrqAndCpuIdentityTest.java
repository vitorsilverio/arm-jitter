package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core.CpuSleepState;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64SystemInstructionOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B6.6.7: identidades da CPU resolvidas intrinsecamente (sem {@code Aarch64SystemRegisterBus}),
/// `WFI`/`HVC`/`SMC` e o mecanismo mínimo de entrega de IRQ (espelho de
/// {@code Aarch64MemoryAbortTest}, B6.6.4, mas para a entrada ASSÍNCRONA). Decode em si já coberto
/// por {@code Aarch64DecoderCorpusTest} (offsets 0x318+, corpus real) — aqui: execução.
class Aarch64IrqAndCpuIdentityTest {
    private static final int ERET = 0xd69f_03e0;

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(0x1000);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void currentElReflectsActiveLevel() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.CURRENT_EL, 0));
        assertEquals(0L, core.x(0), "EL0 por padrão");

        core.exceptionState().setInEl1(true);
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.CURRENT_EL, 1));
        assertEquals(0b01L << 2, core.x(1), "CurrentEL[3:2]=01 dentro de EL1");
    }

    @Test
    void mpidrAndMidrAreConstant() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.MPIDR_EL1, 0));
        assertEquals(0xC000_0000L, core.x(0), "RES1(31)+U(30), core único");
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.MIDR_EL1, 1));
        assertEquals(0x410F_D034L, core.x(1), "Cortex-A53 real do Raspberry Pi 3");
    }

    @Test
    void idRegistersDoNotThrowWithoutAnyHostBusInstalled() {
        // Achado central da task: antes de B6.6.7, QUALQUER MRS sem bus lançava — mesmo para
        // identidades que não fazem sentido como "plugáveis". Aqui confirmamos que continuam
        // funcionando mesmo com Aarch64SystemRegisterBus.none() (padrão).
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        for (Aarch64SystemRegisterId id : new Aarch64SystemRegisterId[] {
                Aarch64SystemRegisterId.ID_AA64PFR0_EL1, Aarch64SystemRegisterId.ID_AA64ISAR0_EL1,
                Aarch64SystemRegisterId.ID_AA64MMFR0_EL1, Aarch64SystemRegisterId.ID_AA64DFR0_EL1}) {
            executor.executeOp(core, new Ir64Op.SystemRegister(true, id, 2));
        }
    }

    @Test
    void tpidrEl1IsPlainGuestReadWriteScratch() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        core.setX(3, 0x1234_5678_9ABC_DEF0L);
        executor.executeOp(core, new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.TPIDR_EL1, 3));
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.TPIDR_EL1, 4));
        assertEquals(0x1234_5678_9ABC_DEF0L, core.x(4), "MSR seguido de MRS deve fazer round-trip exato");
    }

    @Test
    void writingReadOnlyIdentityThrows() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op.SystemRegister msr = new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.MIDR_EL1, 0);
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, msr));
    }

    // ── B6.10: CTR_EL0/DCZID_EL0 (identidade de cache), terceiro gap achado pela F11 ───────────

    @Test
    void ctrEl0AndDczidEl0AreConstant() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.CTR_EL0, 3));
        assertEquals(0x8444_8004L, core.x(3), "Cache Type Register real do Cortex-A53 (mesmo que MIDR_EL1)");
        executor.executeOp(core, new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.DCZID_EL0, 4));
        assertEquals(0x10L, core.x(4), "só DZP(4) setado — DC ZVA não implementado, anunciado como desabilitado");
    }

    @Test
    void ctrEl0AndDczidEl0AreIntrinsicAndReadOnly() {
        Aarch64Core core = newCore();
        assertTrue(core.handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId.CTR_EL0));
        assertTrue(core.handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId.DCZID_EL0));
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op.SystemRegister msrCtr = new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.CTR_EL0, 0);
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, msrCtr));
        Ir64Op.SystemRegister msrDczid = new Ir64Op.SystemRegister(false, Aarch64SystemRegisterId.DCZID_EL0, 0);
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, msrDczid));
    }

    @Test
    void genericTimerRegisterStillRoutesThroughHostBusNotIntrinsic() {
        Aarch64Core core = newCore();
        assertFalse(core.handlesSystemRegisterIntrinsically(Aarch64SystemRegisterId.CNTFRQ_EL0));
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        Ir64Op.SystemRegister mrs = new Ir64Op.SystemRegister(true, Aarch64SystemRegisterId.CNTFRQ_EL0, 0);
        // Sem bus instalado (padrão) continua lançando — timer é host-pluggable, não intrínseco.
        assertThrows(UnsupportedOperationException.class, () -> executor.executeOp(core, mrs));
    }

    @Test
    void smcThrowsSecureMonitorCallExceptionViaExecuteOp() {
        // SMC agora é real (B10.5) — a árvore de decisão completa (EL0/EL1/EL2/EL3, SCR_EL3.SMD)
        // é coberta em Aarch64SecureMonitorCallTest via step() real; este teste só confirma que
        // executeOp() (chamado direto, sem o catch de step()/executeBlock()) propaga a exceção de
        // sinalização em vez de escrever um valor de retorno stub (comportamento pré-B10.5).
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        assertThrows(dev.vitorsilverio.armjitter.core64.Aarch64SecureMonitorCallException.class,
                () -> executor.executeOp(core, new Ir64Op.PrivilegedCall(false)));
    }

    @Test
    void wfiHaltsCoreUntilInterruptLineAsserted() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        core.memory().write32(0, 0xd503_207f); // wfi

        executor.step(core); // executa a própria WFI, avança PC
        assertEquals(CpuSleepState.HALTED, core.sleepState());
        assertEquals(4, core.pc());

        long pcBefore = core.pc();
        executor.step(core); // sem IRQ pendente: continua dormindo, PC não avança
        assertEquals(CpuSleepState.HALTED, core.sleepState());
        assertEquals(pcBefore, core.pc());

        core.setInterruptLine(true);
        executor.step(core); // IRQ pendente e não mascarada: acorda E entrega
        assertEquals(CpuSleepState.RUNNING, core.sleepState());
        assertTrue(core.exceptionState().inEl1());
    }

    @Test
    void irqEntersEl1SavesElrAndSpsrMasksItselfAndReturnsViaEret() {
        Aarch64Core core = newCore();
        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        core.memory().write32(0, 0xd280_0fe1); // movz x1, #0x7f (qualquer NOP-like, só avança PC)
        core.exceptionState().setVbar1(0x100);
        core.memory().write32(0x100 + 0x480, ERET);
        core.pstate().setNzcv(true, false, true, false);

        core.setInterruptLine(true);
        executor.step(core); // servicePendingIrq() entrega ANTES de decodificar a instrução em PC=0

        assertTrue(core.exceptionState().inEl1());
        assertEquals(0x100 + 0x480, core.pc(), "PC deve saltar para VBAR_EL1 + offset de IRQ (0x480)");
        assertEquals(0L, core.exceptionState().elr1(), "ELR_EL1 deve ser o PC ATUAL (IRQ é assíncrona)");
        assertTrue(core.pstate().irqDisabled(), "entrada de IRQ deve mascarar IRQ (evita reentrância)");

        core.setInterruptLine(false); // hospedeiro desassertou (nível-sensível) antes do ERET
        executor.step(core); // eret

        assertFalse(core.exceptionState().inEl1());
        assertEquals(0L, core.pc(), "ERET deve retomar em ELR_EL1 (endereço pré-IRQ)");
        assertFalse(core.pstate().irqDisabled(), "ERET deve restaurar a máscara de IRQ salva em SPSR_EL1");
        assertTrue(core.pstate().negative());
        assertTrue(core.pstate().carry());
    }

    @Test
    void irqIsDeferredWhilePstateIrqDisabled() {
        Aarch64Core core = newCore();
        core.pstate().setIrqDisabled(true);
        core.setInterruptLine(true);

        assertFalse(core.servicePendingIrq(), "IRQ mascarada não deve ser entregue");
        assertFalse(core.exceptionState().inEl1());
    }
}
