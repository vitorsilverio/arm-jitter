package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;

/// Helpers invocados pelo bytecode ASM gerado (flags e operações não triviais).
public final class AsmRuntimeHelpers {
    private AsmRuntimeHelpers() {
    }

    /// Atualiza NZCV após {@code CMP} (borrow = 0).
    public static void updateCmpFlags(ArmCore core, int left, int right) {
        int result = left - right;
        setSbcFlags(core.cpsr(), left, right, 0, result);
    }

    private static void setSbcFlags(CpsrRegister cpsr, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        cpsr.setNzcv(result < 0, result == 0, carry, overflow);
    }
}
