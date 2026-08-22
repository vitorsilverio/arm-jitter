package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus;
import dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B10.4: `HVC` real (entra em EL2) — ver `tasks/trilha-b-arquiteturas/b10.4-hvc-real.md`. Espelha
/// o estilo de {@link Ir64BlockExecutorB101Test} (`step()` real sobre memória, não chamada direta a
/// `Aarch64Core#enterHypervisorCall`, para provar o caminho de captura completo:
/// decoder→`Ir64BlockExecutor#executePrivilegedCall`→`Aarch64HypervisorCallException`→`step()`).
class Aarch64HypervisorCallTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int HVC_0 = 0xd400_0002; // hvc #0
    private static final int SMC_0 = 0xd400_0003; // smc #0
    private static final int ERET = 0xd69f_03e0;
    private static final long VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS = 0x200L;
    private static final long VECTOR_GROUP_LOWER_EL_SYNCHRONOUS = 0x400L;
    private static final long ESR_EC_HVC_AARCH64 = 0x16L;
    private static final long ESR_EC_UNKNOWN_REASON = 0x00L;
    private static final long ESR_EC_SHIFT = 26L;
    private static final long HCR_EL2_HCD_BIT = 1L << 29;

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(0x1000);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void hvcFromEl1EntersEl2ViaLowerElGroup() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.memory().write32(0x0, HVC_0);
        core.memory().write32(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL2, core.exceptionState().currentEl());
        assertEquals(0x0L, core.exceptionState().elr(Aarch64ExceptionLevel.EL2));
        assertEquals(Aarch64ExceptionLevel.EL1.spsrMode(),
                core.exceptionState().spsr(Aarch64ExceptionLevel.EL2) & 0xF,
                "SPSR_EL2.M deve registrar EL1 como origem, para ERET voltar certo");
        assertEquals(ESR_EC_HVC_AARCH64,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL2) >>> ESR_EC_SHIFT);
        assertEquals(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, core.pc(),
                "EL1->EL2 é 'nível inferior', vetor em VBAR_EL2+0x400");
    }

    @Test
    void hvcFromEl1WithHcrEl2HcdSetIsUndefinedInsteadOfTrappingToEl2() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.setSystemRegisterBus(hcrEl2Bus(HCR_EL2_HCD_BIT));
        core.memory().write32(0x0, HVC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl(),
                "HCR_EL2.HCD=1 desabilita a rota EL1->EL2 — UNDEFINED em EL1 mesmo (self-trap)");
        assertEquals(ESR_EC_UNKNOWN_REASON,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc(),
                "EL1->EL1 é 'mesmo nível, SP_ELx'");
    }

    @Test
    void hvcFromEl2SelfTrapsViaCurrentElGroup() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        core.memory().write32(0x0, HVC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL2, core.exceptionState().currentEl(),
                "HVC em EL2 é auto-chamada real do manual (usada por virtualização aninhada)");
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc());
    }

    @Test
    void hvcFromEl0IsUndefinedInEl1() {
        Aarch64Core core = newCore();
        // EL0 é o nível padrão de um core novo — sem setCurrentEl.
        core.memory().write32(0x0, HVC_0);
        core.memory().write32(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl(),
                "HVC não existe em EL0 — cai em UNDEFINED (EL1), mesma classe de HLT");
        assertEquals(ESR_EC_UNKNOWN_REASON,
                core.exceptionState().esr(Aarch64ExceptionLevel.EL1) >>> ESR_EC_SHIFT);
    }

    @Test
    void hvcFromEl3IsUndefinedInEl3NotEl1() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL3);
        core.memory().write32(0x0, HVC_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core);

        assertEquals(Aarch64ExceptionLevel.EL3, core.exceptionState().currentEl(),
                "HVC não existe em EL3 (quem chama o monitor é SMC) — UNDEFINED se auto-trata em "
                        + "EL3, nunca reduz para EL1");
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc());
    }

    @Test
    void smcStillReturnsPsciNotSupportedFromAnyLevelUnaffectedByHvcChange() {
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.setX(0, 0x1111_1111L);
        core.memory().write32(0x0, SMC_0);

        EXECUTOR.step(core);

        assertEquals(0xFFFF_FFFFL, core.x(0));
        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl(),
                "SMC (B10.5, ainda stub) não entra em EL3 — comportamento pré-B10.4 preservado");
    }

    private static Aarch64SystemRegisterBus hcrEl2Bus(long hcrEl2Value) {
        return new Aarch64SystemRegisterBus() {
            @Override
            public boolean handles(Aarch64SystemRegisterId register) {
                return register == Aarch64SystemRegisterId.HCR_EL2;
            }

            @Override
            public long read(Aarch64SystemRegisterId register) {
                return hcrEl2Value;
            }

            @Override
            public void write(Aarch64SystemRegisterId register, long value) {
                throw new UnsupportedOperationException("teste não escreve HCR_EL2");
            }
        };
    }
}
