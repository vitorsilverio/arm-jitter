package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B15.2 — `NOCP`/`NOCP_8_1` fim-a-fim (decode + lift + executor interpretado) sobre o preset real
/// `ARMV7M`: seta `UFSR.NOCP` (`CFSR` em `0xE000ED28`), entra em `USAGE_FAULT`
/// ({@link MProfileExceptionModel}) e busca o vetor — mesmo padrão de
/// {@code ArmArchitectureMProfilePresetsTest#svcOnArmv6mEntersMProfileException}. Também cobre a
/// semântica write-1-to-clear do `CFSR` via {@link MProfileSystemControl}.
class NocpTest {
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    /// Bit `NOCP` do `UFSR` — `bit[3]` do `UFSR`, que é o byte alto do `CFSR` (`bits[31:16]`).
    private static final int CFSR_UFSR_NOCP_BIT = 1 << 19;
    private static final int CFSR_OFFSET = 0xD28;

    private static ArmCore newCore(MProfileExceptionModel model) {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        memory.put32(USAGE_FAULT_VECTOR_ADDRESS, USAGE_FAULT_HANDLER_PC | 1); // vetor Thumb
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV7M);
        core.setExceptionModel(model);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int hi, int lo) {
        ((TestAddressSpace) core.memory()).put16(address, hi);
        ((TestAddressSpace) core.memory()).put16(address + 2, lo);
    }

    // ── Forma 1 (MCR/MRC clássico): entra em USAGE_FAULT com UFSR.NOCP setado ──────────────────

    @Test
    void form1EntersUsageFaultWithUfsrNocpSet() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(model);
        // MCR p15,0,r1,c9,c1,0 (mesmo raw de Thumb2NocpDecoderTest): 0xEE09_1F11.
        put32(core, CODE_BASE, 0xEE09, 0x1F11);

        core.step();

        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException(),
                "NOCP deve entrar em USAGE_FAULT");
        assertEquals(USAGE_FAULT_HANDLER_PC, core.programCounter());
        assertEquals(CFSR_UFSR_NOCP_BIT, model.cfsr() & CFSR_UFSR_NOCP_BIT, "UFSR.NOCP deve estar setado");
    }

    // ── NOCP_8_1 (ARMv8.1-M): mesmo comportamento ────────────────────────────────────────────

    @Test
    void nocp81EntersUsageFaultWithUfsrNocpSet() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(model);
        put32(core, CODE_BASE, 0xEF00, 0x0000);

        core.step();

        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException());
        assertEquals(CFSR_UFSR_NOCP_BIT, model.cfsr() & CFSR_UFSR_NOCP_BIT);
    }

    // ── Condição falsa (via IrOp.Nocp direto, mesmo padrão de outras ops condicionais): não
    // dispara a exceção nem muda o PC ──────────────────────────────────────────────────────

    @Test
    void conditionalNocpSkippedWhenConditionFalse() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(model);
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG); // Z=0 -> EQ falsa
        int pcBefore = core.programCounter();

        boolean pcChanged = new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new dev.vitorsilverio.armjitter.ir.IrOp.Nocp(15, Condition.EQ), pcBefore);

        assertTrue(!pcChanged, "condição falsa não deve mudar o PC nem entrar em exceção");
        assertEquals(0, model.currentException(), "sem exceção quando a condição é falsa");
        assertEquals(pcBefore, core.programCounter());
    }

    // ── CFSR write-1-to-clear via MProfileSystemControl ─────────────────────────────────────

    @Test
    void cfsrIsWriteOneToClearThroughSystemControl() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        model.setUsageFaultNocp();
        MProfileSystemControl scs = new MProfileSystemControl(model, 0, () -> {});

        assertEquals(CFSR_UFSR_NOCP_BIT, scs.read32(CFSR_OFFSET) & CFSR_UFSR_NOCP_BIT);

        scs.write32(CFSR_OFFSET, 0); // escrever 0 não limpa nada (write-1-to-clear)
        assertEquals(CFSR_UFSR_NOCP_BIT, scs.read32(CFSR_OFFSET) & CFSR_UFSR_NOCP_BIT);

        scs.write32(CFSR_OFFSET, CFSR_UFSR_NOCP_BIT); // escrever 1 no bit limpa
        assertEquals(0, scs.read32(CFSR_OFFSET) & CFSR_UFSR_NOCP_BIT);
    }
}
