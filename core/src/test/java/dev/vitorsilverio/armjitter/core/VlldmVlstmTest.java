package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B15.5 — `VLLDM`/`VLSTM` fim-a-fim (decode + lift + executor interpretado) sobre o preset real
/// `ARMV7M`: sem FPU real, sempre `UNDEFINED` — seta `UFSR.UNDEFINSTR` (bit DIFERENTE do
/// `UFSR.NOCP` que {@code NocpTest} cobre) e entra em `USAGE_FAULT`, mesmo padrão de `NocpTest`.
class VlldmVlstmTest {
    private static final int USAGE_FAULT_VECTOR_ADDRESS = 4 * MProfileException.USAGE_FAULT.number();
    private static final int USAGE_FAULT_HANDLER_PC = 0x2000;
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;
    /// Bit `UNDEFINSTR` do `UFSR` — `bit[0]` do `UFSR`, byte alto do `CFSR` (`bits[31:16]`).
    private static final int CFSR_UFSR_UNDEFINSTR_BIT = 1 << 16;

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

    @Test
    void vlldmVlstmEntersUsageFaultWithUfsrUndefinstrSet() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(model);
        // VLLDM [r10]! (l=0,rn=0xA,op=1): 0xEC2A_0A80 (mesmo raw de Thumb2VlldmVlstmVscclrmDecoderTest).
        put32(core, CODE_BASE, 0xEC2A, 0x0A80);

        core.step();

        assertEquals(MProfileException.USAGE_FAULT.number(), model.currentException(),
                "VLLDM/VLSTM sem FPU real deve entrar em USAGE_FAULT");
        assertEquals(USAGE_FAULT_HANDLER_PC, core.programCounter());
        assertEquals(CFSR_UFSR_UNDEFINSTR_BIT, model.cfsr() & CFSR_UFSR_UNDEFINSTR_BIT,
                "UFSR.UNDEFINSTR deve estar setado (não UFSR.NOCP)");
    }

    @Test
    void conditionalVlldmVlstmSkippedWhenConditionFalse() {
        MProfileExceptionModel model = new MProfileExceptionModel();
        ArmCore core = newCore(model);
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG); // Z=0 -> EQ falsa
        int pcBefore = core.programCounter();

        boolean pcChanged = new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new dev.vitorsilverio.armjitter.ir.IrOp.VlldmVlstm(Condition.EQ), pcBefore);

        assertTrue(!pcChanged, "condição falsa não deve mudar o PC nem entrar em exceção");
        assertEquals(0, model.currentException(), "sem exceção quando a condição é falsa");
        assertEquals(pcBefore, core.programCounter());
    }
}
