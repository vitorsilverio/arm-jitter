package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64AddressingMode;
import dev.vitorsilverio.armjitter.ir64.Ir64BranchForm;
import dev.vitorsilverio.armjitter.ir64.Ir64Condition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64PointerAuthOp;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// B19.15 — semântica em tempo de execução da rota (b) (identidade/delegação, interpretador =
/// oráculo, G1): `PointerAuthInPlace` não altera nenhum estado observável (mesmo precedente de
/// `PACGA`); `BRAZ`/`BLRAZ`/`RETA`/`BRA`/`BLRA`/`ERETA`/`LDRA` reaproveitam `Branch64`/`Load64`/
/// `ExceptionReturn` sem novo código de execução — a fidelidade desses já é coberta pelos testes
/// existentes de `BR`/`BLR`/`RET`/`LDR`; este arquivo cobre só o caminho feliz descrito no Aceite da
/// task (desvia/lê do endereço correto) via `decode`+`executeOp` ponta a ponta.
class Ir64PauthResidualExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── PointerAuthInPlace: identidade pura ────────────────────────────────────────────────────

    @Test
    void autdaDoesNotChangeAnyRegister() {
        Aarch64Core core = newCore();
        core.setX(0, 0x1234_5678_9ABC_DEF0L);
        core.setX(1, 0xDEAD_BEEFL);
        long x0Before = core.x(0);
        long x1Before = core.x(1);
        EXECUTOR.executeOp(core, new Ir64Op.PointerAuthInPlace(Ir64PointerAuthOp.AUTDA, 0, 1));
        assertEquals(x0Before, core.x(0), "Xd (o ponteiro) tem que sair INALTERADO sob a rota (b)");
        assertEquals(x1Before, core.x(1), "Xn (modificador) nunca é lido/gravado");
    }

    @Test
    void xpaciDoesNotChangePointer() {
        Aarch64Core core = newCore();
        core.setX(0, 0x0080_0000_1234_5678L); // ponteiro com bits altos "sujos" de assinatura
        long before = core.x(0);
        EXECUTOR.executeOp(core, new Ir64Op.PointerAuthInPlace(Ir64PointerAuthOp.XPACI, 0, -1));
        assertEquals(before, core.x(0), "XPACI não tem bits de assinatura reais para remover");
    }

    // ── LDRA: caminho feliz (lê do endereço correto) ───────────────────────────────────────────

    @Test
    void ldraaReadsFromBaseRegisterPlusOffset() {
        Aarch64Core core = newCore();
        long baseAddress = 0x10L;
        core.setX(1, baseAddress);
        core.memory().write64(baseAddress + 8, 0x1122_3344_5566_7788L);
        EXECUTOR.executeOp(core, new Ir64Op.Load64(
                0, 1, Ir64MemSize.DOUBLEWORD, false, true, Ir64AddressingMode.OFFSET, 8L, -1, null, 0));
        assertEquals(0x1122_3344_5566_7788L, core.x(0));
        assertEquals(baseAddress, core.x(1), "sem writeback (idx=01/LDRAA sem `!`), Xn não muda");
    }

    @Test
    void ldraaPreIndexedWritesBackAddress() {
        Aarch64Core core = newCore();
        long baseAddress = 0x20L;
        core.setX(1, baseAddress);
        core.memory().write64(baseAddress + 8, 42L);
        EXECUTOR.executeOp(core, new Ir64Op.Load64(
                0, 1, Ir64MemSize.DOUBLEWORD, false, true, Ir64AddressingMode.PRE_INDEX, 8L, -1, null, 0));
        assertEquals(42L, core.x(0));
        assertEquals(baseAddress + 8, core.x(1), "LDRAA com `!` grava o endereço de volta em Xn");
    }

    // ── BRA/BLRA/BRAZ/BLRAZ/RETA: caminho feliz (desvia para o registrador correto) ────────────

    @Test
    void braaBranchesToRegisterTargetIgnoringModifier() {
        Aarch64Core core = newCore();
        core.setX(1, 0x1000L);
        core.setProgramCounter(0x800L);
        EXECUTOR.executeOp(core, new Ir64Op.Branch64(
                Ir64BranchForm.REGISTER, 0x800L, 0L, 1, false, Ir64Condition.AL));
        assertEquals(0x1000L, core.pc());
    }

    @Test
    void blraazSetsLinkRegisterAndBranches() {
        Aarch64Core core = newCore();
        core.setX(1, 0x2000L);
        long instructionAddress = 0x900L;
        EXECUTOR.executeOp(core, new Ir64Op.Branch64(
                Ir64BranchForm.REGISTER, instructionAddress, 0L, 1, true, Ir64Condition.AL));
        assertEquals(0x2000L, core.pc());
        assertEquals(instructionAddress + 4, core.x(30));
    }

    @Test
    void retaaBranchesToX30() {
        Aarch64Core core = newCore();
        core.setX(30, 0x3000L);
        EXECUTOR.executeOp(core, new Ir64Op.Branch64(
                Ir64BranchForm.REGISTER, 0xA00L, 0L, 30, false, Ir64Condition.AL));
        assertEquals(0x3000L, core.pc());
    }
}
