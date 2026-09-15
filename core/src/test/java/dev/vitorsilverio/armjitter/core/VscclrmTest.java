package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// B15.5 — `VSCCLRM`: zera um intervalo de registradores FP (armazenamento puro desde a B3.3, sem
/// dependência de FPU real). Cobre as duas formas (`S`/precisão simples, `D`/precisão dupla), o
/// recorte defensivo quando `lastRegister` excede o banco real, e a condição falsa.
class VscclrmTest {
    private static ArmCore newCore() {
        TestAddressSpace memory = new TestAddressSpace(0x1000);
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV7M);
    }

    @Test
    void singlePrecisionZeroesInclusiveRange() {
        ArmCore core = newCore();
        for (int s = 0; s < 8; s++) {
            core.vfp().setS(s, 0xFFFF_FFFF);
        }

        new IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new IrOp.Vscclrm(false, 2, 5, Condition.AL), 0);

        assertEquals(0xFFFF_FFFF, core.vfp().s(0), "fora do intervalo, preservado");
        assertEquals(0xFFFF_FFFF, core.vfp().s(1), "fora do intervalo, preservado");
        for (int s = 2; s <= 5; s++) {
            assertEquals(0, core.vfp().s(s), "S" + s + " deve ser zerado");
        }
        assertEquals(0xFFFF_FFFF, core.vfp().s(6), "fora do intervalo, preservado");
        assertEquals(0xFFFF_FFFF, core.vfp().s(7), "fora do intervalo, preservado");
    }

    @Test
    void doublePrecisionZeroesInclusiveRange() {
        ArmCore core = newCore();
        for (int d = 0; d < 4; d++) {
            core.vfp().setD(d, 0xFFFF_FFFF_FFFF_FFFFL);
        }

        new IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new IrOp.Vscclrm(true, 1, 2, Condition.AL), 0);

        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, core.vfp().d(0), "fora do intervalo, preservado");
        assertEquals(0L, core.vfp().d(1));
        assertEquals(0L, core.vfp().d(2));
        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, core.vfp().d(3), "fora do intervalo, preservado");
    }

    @Test
    void lastRegisterBeyondBankIsClampedNotCrashed() {
        ArmCore core = newCore();
        core.vfp().setS(31, 0xFFFF_FFFF);

        // lastRegister muito além do banco real (encoding UNPREDICTABLE com imm grande): não deve
        // lançar, só recortar ao índice máximo real (31).
        new IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new IrOp.Vscclrm(false, 30, 500, Condition.AL), 0);

        assertEquals(0, core.vfp().s(31), "recortado ao topo real do banco, ainda zera S31");
    }

    @Test
    void emptyRangeIsNoOp() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0xFFFF_FFFF);

        // last < first (imm=0 no encoding real): intervalo vazio, nada é zerado.
        new IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new IrOp.Vscclrm(false, 0, -1, Condition.AL), 0);

        assertEquals(0xFFFF_FFFF, core.vfp().s(0));
    }

    @Test
    void conditionalVscclrmSkippedWhenConditionFalse() {
        ArmCore core = newCore();
        core.vfp().setS(0, 0xFFFF_FFFF);
        core.cpsr().set(core.cpsr().get() & ~CpsrRegister.ZERO_FLAG); // Z=0 -> EQ falsa

        boolean pcChanged = new IrBlockExecutor(ArmArchitecture.ARMV7M)
                .executeOp(core, new IrOp.Vscclrm(false, 0, 0, Condition.EQ), 0);

        assertTrue(!pcChanged);
        assertEquals(0xFFFF_FFFF, core.vfp().s(0), "condição falsa não deve zerar nada");
    }
}
