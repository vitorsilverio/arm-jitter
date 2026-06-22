package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsmRuntimeHelpersTest {

    /// `evalCond(core, ordinal)` deve coincidir com `cpsr().evalCond(Condition.values()[ordinal])`
    /// (o MESMO método usado pelo interpretador) para todo código de condição e todo estado NZCV.
    /// É a garantia de identidade do guard condicional emitido pelo {@link AsmBlockCompiler}.
    @Test
    void evalCondMatchesCpsrForEveryConditionAndFlagState() {
        Condition[] conditions = Condition.values();
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        for (int nzcv = 0; nzcv < 16; nzcv++) {
            core.cpsr().setNzcv((nzcv & 8) != 0, (nzcv & 4) != 0, (nzcv & 2) != 0, (nzcv & 1) != 0);
            for (int ordinal = 0; ordinal < conditions.length; ordinal++) {
                boolean expected = core.cpsr().evalCond(conditions[ordinal]);
                assertEquals(expected, AsmRuntimeHelpers.evalCond(core, ordinal),
                        "cond=" + conditions[ordinal] + " nzcv=" + Integer.toBinaryString(nzcv));
            }
        }
    }

    @Test
    void evalCondAlwaysTrueForAl() {
        ArmCore core = new ArmCore(new TestAddressSpace(16), SwiDispatcher.empty());
        for (int nzcv = 0; nzcv < 16; nzcv++) {
            core.cpsr().setNzcv((nzcv & 8) != 0, (nzcv & 4) != 0, (nzcv & 2) != 0, (nzcv & 1) != 0);
            assertEquals(true, AsmRuntimeHelpers.evalCond(core, Condition.AL.ordinal()));
        }
    }
}
