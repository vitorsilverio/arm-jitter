package dev.vitorsilverio.armjitter.core;

/// Representa o CPSR com helpers para flags, modo e avaliacao condicional.
public final class CpsrRegister {
    /// Bit N do CPSR.
    public static final int NEGATIVE_FLAG = 1 << 31;
    /// Bit Z do CPSR.
    public static final int ZERO_FLAG = 1 << 30;
    /// Bit C do CPSR.
    public static final int CARRY_FLAG = 1 << 29;
    /// Bit V do CPSR.
    public static final int OVERFLOW_FLAG = 1 << 28;
    /// Bit T do CPSR.
    public static final int THUMB_FLAG = 1 << 5;
    /// Bit I do CPSR.
    public static final int IRQ_DISABLE_FLAG = 1 << 7;
    /// Bit F do CPSR.
    public static final int FIQ_DISABLE_FLAG = 1 << 6;

    private int value = CpuMode.SYSTEM.bits();

    /// Retorna o valor bruto de 32 bits do CPSR.
    public int get() {
        return value;
    }

    /// Substitui o valor bruto do CPSR.
    public void set(int value) {
        this.value = value;
    }

    /// Retorna `true` quando o bit T indica execucao THUMB.
    public boolean isThumbMode() {
        return (value & THUMB_FLAG) != 0;
    }

    /// Ativa ou desativa o bit T de execucao THUMB.
    public void setThumbMode(boolean enabled) {
        setFlag(THUMB_FLAG, enabled);
    }

    /// Retorna o modo de CPU codificado nos cinco bits inferiores.
    public CpuMode mode() {
        return CpuMode.fromBits(value);
    }

    /// Atualiza somente os bits de modo do CPSR.
    public void setMode(CpuMode mode) {
        value = (value & ~0b11111) | mode.bits();
    }

    /// Atualiza os flags NZCV de uma vez.
    public void setNzcv(boolean negative, boolean zero, boolean carry, boolean overflow) {
        setFlag(NEGATIVE_FLAG, negative);
        setFlag(ZERO_FLAG, zero);
        setFlag(CARRY_FLAG, carry);
        setFlag(OVERFLOW_FLAG, overflow);
    }

    /// Avalia uma condicao ARM usando os flags atuais.
    public boolean evalCond(Condition condition) {
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
            case AL -> true;
        };
    }

    /// Retorna `true` quando N esta setado.
    public boolean negative() {
        return (value & NEGATIVE_FLAG) != 0;
    }

    /// Retorna `true` quando Z esta setado.
    public boolean zero() {
        return (value & ZERO_FLAG) != 0;
    }

    /// Retorna `true` quando C esta setado.
    public boolean carry() {
        return (value & CARRY_FLAG) != 0;
    }

    /// Retorna `true` quando V esta setado.
    public boolean overflow() {
        return (value & OVERFLOW_FLAG) != 0;
    }

    private void setFlag(int mask, boolean enabled) {
        if (enabled) {
            value |= mask;
        } else {
            value &= ~mask;
        }
    }
}
