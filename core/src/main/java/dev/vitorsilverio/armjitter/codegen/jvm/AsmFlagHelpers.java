package dev.vitorsilverio.armjitter.codegen.jvm;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;

/// Helpers estáticos invocados pelo bytecode ASM gerado: condição de execução (guards `condXx` e `evalCond`), guard do loop-superbloco, flags NZCV da ALU, shifts e operando shifted-register.
///
/// Cada método espelha a lógica do executor interpretado correspondente, garantindo
/// equivalência verificável pelo {@link dev.vitorsilverio.armjitter.codegen.equivalence.BlockEquivalenceHarness}.
/// Públicos porque o bytecode gerado roda em outro class loader.
public final class AsmFlagHelpers {
    private AsmFlagHelpers() {
    }

    /// Cacheado: {@link Condition#values()} clona o array a cada chamada; o guard condicional
    /// roda por op compilado, então indexamos este array fixo pelo ordinal.
    private static final Condition[] CONDITIONS = Condition.values();

    // ── condição ───────────────────────────────────────────────────────────────

    /// Avalia a condição de execução de uma op a partir do ordinal de {@link Condition}.
    ///
    /// Chama o MESMO {@code cpsr().evalCond} do interpretador (ver topo de cada executor, ex.
    /// {@link dev.vitorsilverio.armjitter.codegen.executor.IrAluExecutor}), garantindo que o guard
    /// condicional emitido pelo {@link AsmBlockCompiler} seja idêntico por construção. A JVM inlina.
    public static boolean evalCond(ArmCore core, int ordinal) {
        return core.cpsr().evalCond(CONDITIONS[ordinal]);
    }

    /// Guard de iteração do loop-superbloco (task C0.3): espelha as condições de sleep e
    /// interrupção do chain loop de `JitRuntime.execute`, NESSA ordem (invariante S1 — o
    /// desfecho é o mesmo para ambas: sair da corrente). Budget e generation são checados
    /// separadamente no bytecode gerado pelo {@link AsmSuperblockCompiler}.
    public static boolean superblockKeepRunning(ArmCore core) {
        return core.sleepState() == dev.vitorsilverio.armjitter.core.CpuSleepState.RUNNING
                && !core.interruptLine();
    }

    // Guards especializados POR CONDIÇÃO, escolhidos em tempo de compilação pelo
    // AsmBlockCompiler: eliminam o switch-por-execução de evalCond (o guard roda por op
    // condicional compilado — código ARM é denso em condições, e este era um dos leaves mais
    // quentes do JFR). Cada um espelha um caso de CpsrRegister.evalCond e inlina a um teste
    // de bits.

    public static boolean condEq(ArmCore core) {
        return core.cpsr().zero();
    }

    public static boolean condNe(ArmCore core) {
        return !core.cpsr().zero();
    }

    public static boolean condCs(ArmCore core) {
        return core.cpsr().carry();
    }

    public static boolean condCc(ArmCore core) {
        return !core.cpsr().carry();
    }

    public static boolean condMi(ArmCore core) {
        return core.cpsr().negative();
    }

    public static boolean condPl(ArmCore core) {
        return !core.cpsr().negative();
    }

    public static boolean condVs(ArmCore core) {
        return core.cpsr().overflow();
    }

    public static boolean condVc(ArmCore core) {
        return !core.cpsr().overflow();
    }

    public static boolean condHi(ArmCore core) {
        return core.cpsr().carry() && !core.cpsr().zero();
    }

    public static boolean condLs(ArmCore core) {
        return !core.cpsr().carry() || core.cpsr().zero();
    }

    public static boolean condGe(ArmCore core) {
        return core.cpsr().negative() == core.cpsr().overflow();
    }

    public static boolean condLt(ArmCore core) {
        return core.cpsr().negative() != core.cpsr().overflow();
    }

    public static boolean condGt(ArmCore core) {
        return !core.cpsr().zero() && core.cpsr().negative() == core.cpsr().overflow();
    }

    public static boolean condLe(ArmCore core) {
        return core.cpsr().zero() || core.cpsr().negative() != core.cpsr().overflow();
    }

    // ── flags ALU ──────────────────────────────────────────────────────────────

    public static void updateCmpFlags(ArmCore core, int left, int right) {
        int result = left - right;
        updateSbcFlags(core, left, right, 0, result);
    }

    public static void updateAddFlags(ArmCore core, int left, int right, int result) {
        boolean carry = Integer.compareUnsigned(result, left) < 0;
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    public static void updateAdcFlags(ArmCore core, int left, int right, int carryIn, int result) {
        long unsigned = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right) + carryIn;
        long signed = (long) left + (long) right + carryIn;
        boolean carry = (unsigned >>> 32) != 0;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    public static void updateSbcFlags(ArmCore core, int left, int right, int borrow, int result) {
        long subtrahend = Integer.toUnsignedLong(right) + borrow;
        long signed = (long) left - (long) right - borrow;
        boolean carry = Integer.toUnsignedLong(left) >= subtrahend;
        boolean overflow = signed > Integer.MAX_VALUE || signed < Integer.MIN_VALUE;
        core.cpsr().setNzcv(result < 0, result == 0, carry, overflow);
    }

    public static void updateLogicFlags(ArmCore core, int result, boolean carry) {
        core.cpsr().setNzcv(result < 0, result == 0, carry, core.cpsr().overflow());
    }

    public static void updateNzFlags(ArmCore core, int result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    public static void updateLongNzFlags(ArmCore core, long result) {
        core.cpsr().setNzcv(result < 0, result == 0, core.cpsr().carry(), core.cpsr().overflow());
    }

    // ── shifts ─────────────────────────────────────────────────────────────────

    public static int doLsl(int value, int amount) {
        return amount >= 32 ? 0 : value << amount;
    }

    public static int doLsr(int value, int amount) {
        return amount == 0 ? value : (amount >= 32 ? 0 : value >>> amount);
    }

    public static int doAsr(int value, int amount) {
        return amount == 0 ? value : (amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount);
    }

    public static int doRor(int value, int amount) {
        return amount == 0 ? value : Integer.rotateRight(value, amount & 31);
    }

    // ── operando shifted-register ──────────────────────────────────────────────

    // Ordinais de ShiftType (LSL/LSR/ASR/ROR) — o bytecode passa `ShiftType.ordinal()`.
    private static final int SHIFT_LSL = 0;
    private static final int SHIFT_LSR = 1;
    private static final int SHIFT_ASR = 2;
    private static final int SHIFT_ROR = 3;

    /// Valor de um operando shifted-register (espelha IrExecutionSupport.shiftedRegisterOperand).
    /// `value` já foi lido pelo bytecode (via register cache); `shiftType` é o ordinal de
    /// ShiftType (LSL/LSR/ASR/ROR); para shift por registrador, `amount` já vem mascarado com
    /// 0xFF e `regSpecified`=true (amount 0 devolve o valor intacto); RRX consome o carry atual.
    public static int shiftedOperand(ArmCore core, int value, int shiftType, int amount,
                                     boolean regSpecified, boolean rrx) {
        if (rrx) {
            return (core.cpsr().carry() ? 0x8000_0000 : 0) | (value >>> 1);
        }
        if (regSpecified && amount == 0) {
            return value;
        }
        return switch (shiftType) {
            case SHIFT_LSL -> amount >= 32 ? 0 : value << amount;
            case SHIFT_LSR -> amount >= 32 ? 0 : value >>> amount;
            case SHIFT_ASR -> amount >= 32 ? (value < 0 ? -1 : 0) : value >> amount;
            default -> Integer.rotateRight(value, amount & 31);         // ROR
        };
    }

    /// Carry-out do barrel shifter de um operando shifted-register (espelha
    /// IrExecutionSupport.shiftedRegisterCarryOut) — mesmos parâmetros de {@link #shiftedOperand}.
    /// RRX devolve o bit 0 do valor; amount 0 (shift por registrador com Rs&0xFF==0) mantém o
    /// carry atual. Deve ser chamado ANTES de o resultado ser escrito nos registradores/flags.
    public static boolean shiftedOperandCarry(ArmCore core, int value, int shiftType, int amount,
                                              boolean regSpecified, boolean rrx) {
        if (rrx) {
            return (value & 1) != 0;
        }
        if (amount == 0) {
            return core.cpsr().carry();
        }
        return shiftCarryOut(value, shiftType, amount);
    }

    /// Carry-out de um shift efetivo (amount > 0), espelhando IrExecutionSupport.shiftCarryOut.
    private static boolean shiftCarryOut(int value, int shiftType, int amount) {
        if (amount <= 0) {
            return false;
        }
        return switch (shiftType) {
            case SHIFT_LSL -> amount < 32
                    ? ((value >>> (32 - amount)) & 1) != 0
                    : amount == 32 && (value & 1) != 0;
            case SHIFT_LSR -> amount < 32
                    ? ((value >>> (amount - 1)) & 1) != 0
                    : amount == 32 && value < 0;
            case SHIFT_ASR -> amount >= 32 ? value < 0 : ((value >>> (amount - 1)) & 1) != 0;
            default -> { // ROR: reduz módulo 32; múltiplo exato de 32 devolve o bit 31
                int effective = amount & 31;
                yield effective == 0 ? value < 0 : ((value >>> (effective - 1)) & 1) != 0;
            }
        };
    }

    // ── shifts de opcode ALU com S (LSLS/LSRS/ASRS/RORS) ───────────────────────
    // Espelham o caso LSL/LSR/ASR/ROR + setFlags do IrAluExecutor: N/Z do resultado, C = carry
    // do shifter (amount 0 mantém o C atual — vale para registrador E imediato, pois o builder
    // normaliza LSR#0/ASR#0 para #32 e ROR#0 para RRX), V inalterado. Devolvem o resultado.

    public static int doLslS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_LSL, amount);
        int result = doLsl(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }

    public static int doLsrS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_LSR, amount);
        int result = doLsr(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }

    public static int doAsrS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_ASR, amount);
        int result = doAsr(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }

    public static int doRorS(ArmCore core, int value, int amount) {
        boolean carry = amount == 0 ? core.cpsr().carry() : shiftCarryOut(value, SHIFT_ROR, amount);
        int result = doRor(value, amount);
        updateLogicFlags(core, result, carry);
        return result;
    }
}
