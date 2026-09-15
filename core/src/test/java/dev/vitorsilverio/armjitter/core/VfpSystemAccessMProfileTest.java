package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B15.3 — `VMSR_VMRS`/`VLDR_sysreg`/`VSTR_sysreg` fim-a-fim (decode + lift + executor
/// interpretado) sobre o preset real `ARMV7M`: move o valor bruto de `ArmCore.fpscr()` de/para um
/// GPR ou memória, reusando o MESMO objeto que o VFP A-profile já leria/escreveria. Mesmo padrão de
/// {@code NocpTest} (B15.2).
class VfpSystemAccessMProfileTest {
    private static final int CODE_BASE = 0x100;
    private static final int MEMORY_SIZE = 0x8000;

    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(MEMORY_SIZE);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV7M);
        core.cpsr().setThumbMode(true);
        core.setRegister(13, 0x1000);
        core.setProgramCounter(CODE_BASE);
        return core;
    }

    private static void put32(ArmCore core, int address, int hi, int lo) {
        ((TestAddressSpace) core.memory()).put16(address, hi);
        ((TestAddressSpace) core.memory()).put16(address + 2, lo);
    }

    // ── VMSR_VMRS ────────────────────────────────────────────────────────────────────────────

    @Test
    void vmsrWritesRegisterIntoFpscrThenVmrsReadsItBack() {
        ArmCore core = newCore();
        core.setRegister(5, 0x0100_0000); // um valor plausível de FPSCR (N=1, resto zero)
        // VMSR FPSCR, r5: 0xEEE1_5A10.
        put32(core, CODE_BASE, 0xEEE1, 0x5A10);
        core.step();
        assertEquals(0x0100_0000, core.fpscr().value());

        // VMRS r3, FPSCR: 0xEEF1_3A10.
        put32(core, core.programCounter(), 0xEEF1, 0x3A10);
        core.step();
        assertEquals(0x0100_0000, core.register(3));
    }

    @Test
    void conditionalVmsrVmrsSkippedWhenConditionFalse() {
        ArmCore core = newCore();
        core.setRegister(5, 0x1234);
        boolean pcChanged = new dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new dev.vitorsilverio.armjitter.ir.IrOp.VfpSystemTransfer(false, 5, Condition.EQ),
                        core.programCounter());
        assertTrue(!pcChanged);
        assertEquals(0, core.fpscr().value(), "condição falsa não deve escrever FPSCR");
    }

    // ── VLDR_sysreg/VSTR_sysreg ──────────────────────────────────────────────────────────────

    @Test
    void vstrSysregStoresFpscrToMemoryThenVldrSysregLoadsItBack() {
        ArmCore core = newCore();
        core.fpscr().setValue(0x0800_0000);
        core.setRegister(2, 0x2000);
        // VSTR_sysreg {FPSCR}, [r2, #8]: reg=1,rn=2,imm7=2,add=1,load=0,p=1,w=0 -> 0xED82_2F82.
        put32(core, CODE_BASE, 0xED82, 0x2F82);
        core.step();
        assertEquals(0x0800_0000, core.memory().read32(0x2008));

        core.fpscr().setValue(0); // limpa para provar que o LDR de fato recarrega
        core.setRegister(2, 0x2000);
        // VLDR_sysreg {FPSCR}, [r2, #8]: 0xED92_2F82 (load=1).
        put32(core, core.programCounter(), 0xED92, 0x2F82);
        core.step();
        assertEquals(0x0800_0000, core.fpscr().value());
        assertEquals(0x2000, core.register(2), "P=1,W=0 não faz writeback");
    }

    @Test
    void vldrSysregPostIndexedWritesBackBaseRegister() {
        ArmCore core = newCore();
        core.fpscr().setValue(0);
        ((TestAddressSpace) core.memory()).put32(0x2000, 0x0400_0000);
        core.setRegister(2, 0x2000);
        // VLDR_sysreg {FPSCR}, [r2], #8: reg=1,rn=2,imm7=2,add=1,load=1,p=0,w=1 -> 0xECB2_2F82.
        put32(core, CODE_BASE, 0xECB2, 0x2F82);
        core.step();
        assertEquals(0x0400_0000, core.fpscr().value());
        assertEquals(0x2008, core.register(2), "post-indexed sempre faz writeback");
    }
}
