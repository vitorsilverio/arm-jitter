package dev.vitorsilverio.armjitter.executor64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.ir64.Ir64CompareBranchCondition;
import dev.vitorsilverio.armjitter.ir64.Ir64MemSize;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// B19.22 — semântica de `CB_cond`/`CB_cond_imm` (`FEAT_CMPBR`, interpretador = oráculo, G1).
/// Cobre as duas Armadilhas centrais da task: `NZCV` intocado e `CBB`/`CBH` comparando só os bits
/// baixos.
class Ir64CompareAndBranchConditionalExecutorTest {
    private static final Ir64BlockExecutor EXECUTOR = new Ir64BlockExecutor();
    private static final long TARGET = 0x1000L;

    private static Aarch64Core newCore() {
        return new Aarch64Core(AddressSpace64.wrapping(new TestAddressSpace(256)));
    }

    // ── forma registrador: cada condição, um caso que desvia e um que não ─────────────────────────

    @Test
    void greaterThanSignedBranchesWhenTrue() {
        Aarch64Core core = newCore();
        core.setX(1, 5L);
        core.setX(2, 3L);
        boolean taken = EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET));
        assertTrue(taken);
        assertEquals(TARGET, core.pc());
    }

    @Test
    void greaterThanSignedDoesNotBranchWhenFalse() {
        Aarch64Core core = newCore();
        core.setX(1, 3L);
        core.setX(2, 5L);
        boolean taken = EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET));
        assertFalse(taken);
    }

    @Test
    void greaterOrEqualSigned() {
        Aarch64Core core = newCore();
        core.setX(1, 5L);
        core.setX(2, 5L);
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_OR_EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
        core.setX(1, 4L);
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_OR_EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
    }

    @Test
    void greaterThanUnsignedRespectsUnsignedOrdering() {
        // -1 (0xFFFF...FFFF) é o MAIOR valor sem sinal.
        Aarch64Core core = newCore();
        core.setX(1, -1L);
        core.setX(2, 5L);
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 2, 1, Ir64MemSize.DOUBLEWORD, TARGET)));
    }

    @Test
    void greaterOrEqualUnsigned() {
        Aarch64Core core = newCore();
        core.setX(1, 5L);
        core.setX(2, 5L);
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_OR_EQUAL_UNSIGNED, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
        core.setX(1, 4L);
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_OR_EQUAL_UNSIGNED, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
    }

    @Test
    void equalAndNotEqual() {
        Aarch64Core core = newCore();
        core.setX(1, 7L);
        core.setX(2, 7L);
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.NOT_EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));

        core.setX(2, 8L);
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.NOT_EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET)));
    }

    // ── CBB/CBH: só os bits baixos ─────────────────────────────────────────────────────────────────

    @Test
    void byteFormComparesOnlyLowByte() {
        Aarch64Core core = newCore();
        core.setX(1, 0x1234_5600L | 0xAAL); // bits altos diferentes, byte baixo = 0xAA
        core.setX(2, 0x9999_9900L | 0xAAL); // bits altos diferentes, byte baixo = 0xAA
        boolean taken = EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.EQUAL, 1, 2, Ir64MemSize.BYTE, TARGET));
        assertTrue(taken, "bytes baixos iguais (0xAA) devem comparar iguais, apesar dos bits altos");
    }

    @Test
    void halfwordFormComparesOnlyLowHalfword() {
        Aarch64Core core = newCore();
        core.setX(1, 0x1111_0000_BEEFL);
        core.setX(2, 0x2222_0000_BEEFL);
        boolean taken = EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.EQUAL, 1, 2, Ir64MemSize.HALF, TARGET));
        assertTrue(taken, "halfwords baixos iguais (0xBEEF) devem comparar iguais");
    }

    @Test
    void byteFormSignExtendsForSignedCondition() {
        // byte baixo 0xFF = -1 (com sinal) mas 255 (sem sinal); Rm=5.
        Aarch64Core core = newCore();
        core.setX(1, 0xFFL);
        core.setX(2, 5L);
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN, 1, 2, Ir64MemSize.BYTE, TARGET)),
                "com sinal: -1 não é maior que 5");
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 1, 2, Ir64MemSize.BYTE, TARGET)),
                "sem sinal: 255 é maior que 5");
    }

    // ── forma imediata ──────────────────────────────────────────────────────────────────────────

    @Test
    void immediateFormComparesAgainstUnsignedImmediate() {
        Aarch64Core core = newCore();
        core.setX(1, 10L);
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchImmediate(
                Ir64CompareBranchCondition.GREATER_THAN, 1, true, 5, TARGET)));
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchImmediate(
                Ir64CompareBranchCondition.LESS_THAN, 1, true, 5, TARGET)));
    }

    @Test
    void immediateFormNarrowSignExtendsSignedCondition() {
        // W1 = 0xFFFFFFFF (-1 em 32 bits); comparação com sinal deve tratar como -1.
        Aarch64Core core = newCore();
        core.setX(1, 0xFFFF_FFFFL);
        assertFalse(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchImmediate(
                Ir64CompareBranchCondition.GREATER_THAN, 1, false, 5, TARGET)),
                "com sinal: -1 não é maior que 5");
        assertTrue(EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchImmediate(
                Ir64CompareBranchCondition.GREATER_THAN_UNSIGNED, 1, false, 5, TARGET)),
                "sem sinal: 0xFFFFFFFF é maior que 5");
    }

    // ── NZCV intocado (Aceite explícito da task) ───────────────────────────────────────────────────

    @Test
    void registerFormNeverTouchesNzcv() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(0b1011); // N=1,Z=0,C=1,V=1 — estado arbitrário e distintivo
        int before = core.pstate().nzcv();
        core.setX(1, 5L);
        core.setX(2, 3L);
        EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.GREATER_THAN, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET));
        assertEquals(before, core.pstate().nzcv());

        EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, TARGET));
        assertEquals(before, core.pstate().nzcv(), "também não muda quando a condição é falsa");
    }

    @Test
    void immediateFormNeverTouchesNzcv() {
        Aarch64Core core = newCore();
        core.pstate().setNzcv(0b0110);
        int before = core.pstate().nzcv();
        core.setX(1, 10L);
        EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchImmediate(
                Ir64CompareBranchCondition.GREATER_THAN, 1, true, 5, TARGET));
        assertEquals(before, core.pstate().nzcv());
    }

    // ── offset PC-relativo (frente e trás) ─────────────────────────────────────────────────────────

    @Test
    void branchForwardAndBackward() {
        Aarch64Core core = newCore();
        core.setX(1, 1L);
        core.setX(2, 1L);
        boolean forward = EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchRegister(
                Ir64CompareBranchCondition.EQUAL, 1, 2, Ir64MemSize.DOUBLEWORD, 0x2000L));
        assertTrue(forward);
        assertEquals(0x2000L, core.pc());

        boolean backward = EXECUTOR.executeOp(core, new Ir64Op.CompareAndBranchImmediate(
                Ir64CompareBranchCondition.EQUAL, 1, true, 1, 0x10L));
        assertTrue(backward);
        assertEquals(0x10L, core.pc());
    }
}
