package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B10.5: `SMC` real (entra em EL3) — ver `tasks/trilha-b-arquiteturas/b10.5-smc-real.md`. Mesmo
/// estilo de {@link Aarch64HypervisorCallTest} (`step()` real sobre memória, provando o caminho de
/// captura completo: decoder→`Ir64BlockExecutor#executePrivilegedCall`→
/// `Aarch64SecureMonitorCallException`→`step()`).
class Aarch64SecureMonitorCallTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int SMC_0 = 0xd400_0003; // smc #0
    private static final int ERET = 0xd69f_03e0;
    private static final long VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS = 0x200L;
    private static final long VECTOR_GROUP_LOWER_EL_SYNCHRONOUS = 0x400L;
    private static final long ESR_EC_SMC_AARCH64 = 0x17L;
    private static final long ESR_EC_UNKNOWN_REASON = 0x00L;
    private static final long ESR_EC_SHIFT = 26L;
    private static final long SCR_EL3_SMD_BIT = 1L << 7;

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(0x1000);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void smcFromEl1EntersEl3ViaLowerElGroup() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL3, core.exceptionState().currentEl());
        assertEquals(0x0L, core.exceptionState().elr(Aarch64ExceptionLevel.EL3));
        assertEquals(Aarch64ExceptionLevel.EL1.spsrMode(),
                core.exceptionState().spsr(Aarch64ExceptionLevel.EL3) & 0xF,
                "SPSR_EL3.M deve registrar EL1 como origem, para ERET voltar certo");
        assertEquals(ESR_EC_SMC_AARCH64,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL3) >>> ESR_EC_SHIFT);
        assertEquals(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, core.pc(),
                "EL1->EL3 é 'nível inferior', vetor em VBAR_EL3+0x400");
    }

    @Test
    void smcFromEl1WithScrEl3SmdSetIsUndefinedInsteadOfTrappingToEl3() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.setSystemRegisterBus(scrEl3Bus(SCR_EL3_SMD_BIT));
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl(),
                "SCR_EL3.SMD=1 desabilita a rota EL1->EL3 — UNDEFINED em EL1 mesmo (self-trap)");
        assertEquals(ESR_EC_UNKNOWN_REASON,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc(),
                "EL1->EL1 é 'mesmo nível, SP_ELx'");
    }

    @Test
    void smcFromEl2EntersEl3ViaLowerElGroup() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL3, core.exceptionState().currentEl());
        assertEquals(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, core.pc(),
                "EL2->EL3 é 'nível inferior', vetor em VBAR_EL3+0x400");
    }

    @Test
    void smcFromEl2WithScrEl3SmdSetIsUndefinedInEl2NotEl1() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        core.setSystemRegisterBus(scrEl3Bus(SCR_EL3_SMD_BIT));
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL2, core.exceptionState().currentEl(),
                "self-trap em EL2 (nunca reduz para EL1, mesmo achado real de B10.4 para HVC/EL3)");
        assertEquals(ESR_EC_UNKNOWN_REASON,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL2) >>> ESR_EC_SHIFT);
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc());
    }

    @Test
    void smcFromEl3SelfTrapsViaCurrentElGroup() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL3, core.exceptionState().currentEl(),
                "SMC em EL3 é auto-chamada real do manual");
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc());
    }

    @Test
    void smcFromEl3WithScrEl3SmdSetIsUndefinedInEl3() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        core.setSystemRegisterBus(scrEl3Bus(SCR_EL3_SMD_BIT));
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL3, core.exceptionState().currentEl());
        assertEquals(ESR_EC_UNKNOWN_REASON,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL3) >>> ESR_EC_SHIFT);
    }

    @Test
    void smcFromEl0IsUndefinedInEl1() {
        Aarch64Core core = newCore();
        // EL0 é o nível padrão de um core novo — sem setCurrentEl.
        core.memory().write32(0x0, SMC_0);
        core.memory().write32(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl(),
                "SMC não existe em EL0 — cai em UNDEFINED (EL1), mesma classe de HLT/HVC");
        assertEquals(ESR_EC_UNKNOWN_REASON,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
    }

    private static Aarch64SystemRegisterBus scrEl3Bus(long scrEl3Value) {
        return new Aarch64SystemRegisterBus() {
            @Override
            public boolean handles(Aarch64SystemRegisterId register) {
                return register == Aarch64SystemRegisterId.SCR_EL3;
            }

            @Override
            public long read(Aarch64SystemRegisterId register) {
                return scrEl3Value;
            }

            @Override
            public void write(Aarch64SystemRegisterId register, long value) {
                throw new UnsupportedOperationException("teste não escreve SCR_EL3");
            }
        };
    }
}
