package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.ir64.Ir64Condition;

/// Representa `PSTATE.{N,Z,C,V}` do AArch64 — deliberadamente uma classe NOVA, não
/// {@link dev.vitorsilverio.armjitter.core.CpsrRegister} reaproveitada (decisão registrada na
/// task B6.1/RFC-IR-64BIT.md §3.1: `PSTATE` não é um CPSR "maior", A64 não tem banking de
/// registrador por modo, não tem T-bit/IT-state/GE, e o único subconjunto em comum com o CPSR de
/// 32 bits são justamente os 4 flags de condição — replicar é mais barato e mais claro que
/// generalizar um único registrador para dois mundos incompatíveis).
///
/// Escopo desta task (B6.1): só `N`/`Z`/`C`/`V`, suficiente para avaliar `B.cond`. `PSTATE` tem
/// muitos outros campos no AArch64 real (`DAIF`, `SPSel`, `EL`, `SS`, `IL`...) que ficam fora daqui
/// até serem exigidos por uma task futura (B6.1 é só EL0, sem exceções nem troca de nível).
public final class PstateRegister {
    /// Bit N (negativo) dentro do valor bruto de 4 bits.
    public static final int NEGATIVE_FLAG = 1 << 3;
    /// Bit Z (zero) dentro do valor bruto de 4 bits.
    public static final int ZERO_FLAG = 1 << 2;
    /// Bit C (carry) dentro do valor bruto de 4 bits.
    public static final int CARRY_FLAG = 1 << 1;
    /// Bit V (overflow) dentro do valor bruto de 4 bits.
    public static final int OVERFLOW_FLAG = 1;

    private int nzcv;

    /// Retorna o valor bruto dos 4 flags (`N` no bit 3, `V` no bit 0).
    public int nzcv() {
        return nzcv;
    }

    /// Substitui o valor bruto dos 4 flags de uma vez.
    public void setNzcv(int nzcv) {
        this.nzcv = nzcv & 0xF;
    }

    /// Atualiza os quatro flags individualmente.
    public void setNzcv(boolean negative, boolean zero, boolean carry, boolean overflow) {
        nzcv = (negative ? NEGATIVE_FLAG : 0)
                | (zero ? ZERO_FLAG : 0)
                | (carry ? CARRY_FLAG : 0)
                | (overflow ? OVERFLOW_FLAG : 0);
    }

    /// Retorna `true` quando N está setado.
    public boolean negative() {
        return (nzcv & NEGATIVE_FLAG) != 0;
    }

    /// Retorna `true` quando Z está setado.
    public boolean zero() {
        return (nzcv & ZERO_FLAG) != 0;
    }

    /// Retorna `true` quando C está setado.
    public boolean carry() {
        return (nzcv & CARRY_FLAG) != 0;
    }

    /// Retorna `true` quando V está setado.
    public boolean overflow() {
        return (nzcv & OVERFLOW_FLAG) != 0;
    }

    /// Avalia uma {@link Ir64Condition} contra os flags atuais (`ARM DDI 0487 C1.2.4`,
    /// tabela `ConditionHolds`).
    public boolean evalCond(Ir64Condition condition) {
        return switch (condition) {
            case EQ -> zero();
            case NE -> !zero();
            case CS -> carry();
            case CC -> !carry();
            case MI -> negative();
            case PL -> !negative();
            case VS -> overflow();
            case VC -> !overflow();
            case HI -> carry() && !zero();
            case LS -> !carry() || zero();
            case GE -> negative() == overflow();
            case LT -> negative() != overflow();
            case GT -> !zero() && negative() == overflow();
            case LE -> zero() || negative() != overflow();
            case AL, NV -> true;
        };
    }
}
