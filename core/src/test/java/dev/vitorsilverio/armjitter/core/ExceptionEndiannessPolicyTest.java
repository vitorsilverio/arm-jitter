package dev.vitorsilverio.armjitter.core;

import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regressão do achado real da task F3 (`virtual-arm-box --machine=raspi1`): o kernel Linux real
/// usa `SETEND BE`/`SETEND LE` em pares ao redor de uma rotina, e uma IRQ que chega bem no meio
/// interrompia o código com `CPSR.E=1` — sem este gancho, o handler de exceção herdava esse `E=1`
/// (o `vector_stub` de IRQ lia sua própria tabela de branch com os bytes invertidos e travava o
/// boot). Hardware real reprograma `CPSR.E` para `SCTLR.EE` em toda entrada de exceção,
/// independente do contexto interrompido (ARM DDI 0406C B1.8.3).
class ExceptionEndiannessPolicyTest {

    @Test
    void noneKeepsCpsrEUntouchedOnExceptionEntryDefaultBehavior() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.cpsr().setBigEndian(true);

        core.requestException(ArmException.IRQ);

        assertTrue(core.cpsr().isBigEndian(), "sem policy instalada, E não deve ser tocado (G3)");
    }

    @Test
    void installedPolicyForcesCpsrEFromSctlrEeOnExceptionEntryEvenWhenInterruptedCodeWasSetendBe() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.setExceptionEndiannessPolicy(cpsr -> cpsr.setBigEndian(false));
        core.cpsr().setBigEndian(true); // código interrompido em plena SETEND BE

        core.requestException(ArmException.IRQ);

        assertFalse(core.cpsr().isBigEndian(), "handler de exceção deve rodar em SCTLR.EE, não herdar E do contexto interrompido");
    }

    @Test
    void installedPolicyPreservesInterruptedEInSpsrRegardlessOfNewCpsrE() {
        ArmCore core = new ArmCore(new TestAddressSpace(32), SwiDispatcher.empty());
        core.setExceptionEndiannessPolicy(cpsr -> cpsr.setBigEndian(false));
        core.cpsr().setBigEndian(true);

        core.requestException(ArmException.IRQ);

        assertTrue((core.spsr(CpuMode.IRQ) & CpsrRegister.ENDIAN_FLAG) != 0,
                "SPSR precisa preservar o E real do contexto interrompido, mesmo com a policy forçando CPSR novo");
    }
}
