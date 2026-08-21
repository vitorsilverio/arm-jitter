package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64ExceptionLevel;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// B10.1: mecânica de exceção generalizada para os 4 níveis (fundação do épico B10 — ver
/// `tasks/trilha-b-arquiteturas/b10-plano-el2-el3.md`). Nenhum roteamento real por `HCR_EL2`/
/// `SCR_EL3` ainda (isso é B10.4/B10.5): estes testes provam só o mecanismo — seleção de grupo de
/// vetor "mesmo nível" vs. "nível inferior", `ERET` lendo o nível-ALVO de `SPSR_ELx.M` em vez de
/// sempre "um abaixo do atual", e `CurrentEL` refletindo os 4 valores.
class Ir64BlockExecutorB101Test {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final int BRK_0 = 0xd420_0000; // brk #0x0
    private static final int ERET = 0xd69f_03e0;
    private static final long VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS = 0x200L;
    private static final long VECTOR_GROUP_LOWER_EL_SYNCHRONOUS = 0x400L;

    private static Aarch64Core newCore() {
        TestAddressSpace raw = new TestAddressSpace(0x1000);
        return new Aarch64Core(AddressSpace64.wrapping(raw));
    }

    @Test
    void brkFromEl0EntersEl1ViaLowerElGroup() {
        Aarch64Core core = newCore();
        core.memory().write32(0x0, BRK_0);
        core.memory().write32(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, ERET);

        EXECUTOR.step(core); // brk, a partir de EL0

        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl());
        assertEquals(VECTOR_GROUP_LOWER_EL_SYNCHRONOUS, core.pc(),
                "EL0->EL1 é 'nível inferior', vetor em VBAR_EL1+0x400");
    }

    @Test
    void brkWhileAlreadyInEl1UsesCurrentElSpxGroupNotLowerElGroup() {
        Aarch64Core core = newCore();
        // Coloca o core "dentro" de um handler de EL1 (nested exception) sem passar pelo BRK
        // anterior — simula um BRK batendo dentro do próprio handler de EL1 (ex.: um BUG_ON do
        // Linux disparado dentro de outro handler).
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL1);
        core.setProgramCounter(0x0);
        core.memory().write32(0x0, BRK_0);
        core.memory().write32(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, ERET);

        EXECUTOR.step(core); // brk, já em EL1

        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl());
        assertEquals(VECTOR_GROUP_CURRENT_EL_SPX_SYNCHRONOUS, core.pc(),
                "EL1->EL1 é 'mesmo nível, SP_ELx', vetor em VBAR_EL1+0x200 — NÃO +0x400");
    }

    @Test
    void currentElReflectsAllFourLevelsViaMrs() {
        Aarch64Core core = newCore();
        for (Aarch64ExceptionLevel level : Aarch64ExceptionLevel.values()) {
            core.exceptionState().setCurrentEl(level);
            long currentEl = core.readIntrinsicSystemRegister(
                    dev.vitorsilverio.armjitter.ir64.Aarch64SystemRegisterId.CURRENT_EL);
            int expectedField = switch (level) {
                case EL0 -> 0b00;
                case EL1 -> 0b01;
                case EL2 -> 0b10;
                case EL3 -> 0b11;
            };
            assertEquals(expectedField, (int) ((currentEl >>> 2) & 0b11), "CurrentEL para " + level);
        }
    }

    @Test
    void eretReturnsToTheLevelEncodedInSpsrNotAlwaysOneBelowCurrent() {
        // Constrói manualmente o cenário "executando em EL2, SPSR_EL2 aponta pra voltar direto
        // pra EL1" (ex.: um hypervisor real que delega de volta ao SO sem passar por EL0) — prova
        // que ERET usa o M[3:0] de SPSR_ELx, não "sempre um nível abaixo do atual".
        Aarch64Core core = newCore();
        core.exceptionState().setCurrentEl(Aarch64ExceptionLevel.EL2);
        core.exceptionState().setElr(Aarch64ExceptionLevel.EL2, 0x1234L);
        core.exceptionState().setSpsr(Aarch64ExceptionLevel.EL2, Aarch64ExceptionLevel.EL1.spsrMode());

        boolean pcChanged = EXECUTOR.executeOp(core, new Ir64Op.ExceptionReturn());

        assertTrue(pcChanged);
        assertEquals(Aarch64ExceptionLevel.EL1, core.exceptionState().currentEl());
        assertEquals(0x1234L, core.pc());
    }
}
