package dev.vitorsilverio.armjitter.core;

/// Representa o CPSR com helpers para flags, modo e avaliação condicional.
public final class CpsrRegister {
    /// Bit N do CPSR.
    public static final int NEGATIVE_FLAG = 1 << 31;
    /// Bit Z do CPSR.
    public static final int ZERO_FLAG = 1 << 30;
    /// Bit C do CPSR.
    public static final int CARRY_FLAG = 1 << 29;
    /// Bit V do CPSR.
    public static final int OVERFLOW_FLAG = 1 << 28;
    /// Bit Q do CPSR (saturação sticky das instruções DSP/saturadas ARMv5TE).
    public static final int SATURATION_FLAG = 1 << 27;
    /// Deslocamento dos flags GE\[3:0\] do CPSR (bits 19:16, aritmética paralela ARMv6).
    public static final int GE_FLAGS_SHIFT = 16;
    /// Máscara dos flags GE\[3:0\] (bits 19:16), escritos pelas variantes básicas da aritmética
    /// paralela ARMv6 (SADD16/UADD8/...) e lidos por `SEL`.
    public static final int GE_FLAGS_MASK = 0xF << GE_FLAGS_SHIFT;
    /// Bit T do CPSR.
    public static final int THUMB_FLAG = 1 << 5;
    /// Bit I do CPSR.
    public static final int IRQ_DISABLE_FLAG = 1 << 7;
    /// Bit F do CPSR.
    public static final int FIQ_DISABLE_FLAG = 1 << 6;
    /// Bit A do CPSR (mascara de abort imprecisa), alterável por `CPS` (ARMv6). Nenhum
    /// dispatcher de exceção deste emulador consome este bit hoje (sem linha de abort externa
    /// modelada); existe para que `CPS`/`MSR` possam lê-lo/escrevê-lo corretamente.
    public static final int ABORT_DISABLE_FLAG = 1 << 8;
    /// Bit E do CPSR (endianness de dados), setado por `SETEND` (ARMv6). Acessos de dados com
    /// E=1 (big-endian) não são implementados — ver os helpers `readXArm7`/`writeXArm7` em
    /// {@code IrExecutionSupport}.
    public static final int ENDIAN_FLAG = 1 << 9;
    /// Deslocamento da metade BAIXA do ITSTATE\[7:0\] (Thumb-2 IT block, B2.4) — CPSR\[26:25\] =
    /// ITSTATE\[1:0\], confirmado contra o QEMU `cpu.h`/`helper.c`. Bits genuinamente livres em
    /// todo preset existente (confirmado por grep antes da task B2.4: nenhum J-bit nem outro uso
    /// ARMv6T2/v7 já reivindicava 26:25 ou 15:10 neste `CpsrRegister`).
    public static final int IT_LOW_SHIFT = 25;
    /// Máscara da metade baixa do ITSTATE (bits 26:25, 2 bits).
    public static final int IT_LOW_MASK = 0b11 << IT_LOW_SHIFT;
    /// Deslocamento da metade ALTA do ITSTATE\[7:0\] (bits 15:10, 6 bits) — CPSR\[15:10\] =
    /// ITSTATE\[7:2\].
    public static final int IT_HIGH_SHIFT = 10;
    /// Máscara da metade alta do ITSTATE (bits 15:10, 6 bits).
    public static final int IT_HIGH_MASK = 0x3F << IT_HIGH_SHIFT;

    private int value = CpuMode.SYSTEM.bits();

    /// Retorna o valor bruto de 32 bits do CPSR.
    public int get() {
        return value;
    }

    /// Substitui o valor bruto do CPSR.
    public void set(int value) {
        this.value = value;
    }

    /// Retorna `true` quando o bit T indica execução THUMB.
    public boolean isThumbMode() {
        return (value & THUMB_FLAG) != 0;
    }

    /// Ativa ou desativa o bit T de execução THUMB.
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

    /// Avalia uma condição ARM usando os flags atuais.
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

    /// Retorna os flags GE\[3:0\] (bits 19:16) como um valor de 4 bits.
    public int ge() {
        return (value & GE_FLAGS_MASK) >>> GE_FLAGS_SHIFT;
    }

    /// Substitui os flags GE\[3:0\] pelo valor de 4 bits informado (bits altos são ignorados).
    public void setGe(int ge) {
        value = (value & ~GE_FLAGS_MASK) | ((ge & 0xF) << GE_FLAGS_SHIFT);
    }

    /// Retorna `true` quando o bit Q (saturação sticky) está setado.
    public boolean saturation() {
        return (value & SATURATION_FLAG) != 0;
    }

    /// Seta o bit Q. As instruções saturadas só o ativam (sticky); software o limpa via MSR.
    public void setSaturation(boolean saturated) {
        setFlag(SATURATION_FLAG, saturated);
    }

    /// Retorna `true` quando IRQ está mascarada pelo bit I.
    public boolean irqDisabled() {
        return (value & IRQ_DISABLE_FLAG) != 0;
    }

    /// Ativa ou desativa a máscara de IRQ.
    public void setIrqDisabled(boolean disabled) {
        setFlag(IRQ_DISABLE_FLAG, disabled);
    }

    /// Retorna `true` quando FIQ está mascarada pelo bit F.
    public boolean fiqDisabled() {
        return (value & FIQ_DISABLE_FLAG) != 0;
    }

    /// Ativa ou desativa a máscara de FIQ.
    public void setFiqDisabled(boolean disabled) {
        setFlag(FIQ_DISABLE_FLAG, disabled);
    }

    /// Retorna `true` quando abort imprecisa está mascarada pelo bit A.
    public boolean abortDisabled() {
        return (value & ABORT_DISABLE_FLAG) != 0;
    }

    /// Ativa ou desativa a máscara de abort imprecisa.
    public void setAbortDisabled(boolean disabled) {
        setFlag(ABORT_DISABLE_FLAG, disabled);
    }

    /// Retorna `true` quando o bit E indica acessos de dados big-endian.
    public boolean isBigEndian() {
        return (value & ENDIAN_FLAG) != 0;
    }

    /// Ativa ou desativa o bit E de endianness de dados.
    public void setBigEndian(boolean bigEndian) {
        setFlag(ENDIAN_FLAG, bigEndian);
    }

    /// Retorna o ITSTATE\[7:0\] cru (Thumb-2 IT block, B2.4): `0` fora de um IT block. Formato
    /// idêntico ao byte baixo do encoding da instrução `IT` (`firstcond:4 ++ mask:4`), reconstruído
    /// a partir dos dois pedaços não-contíguos do CPSR (bits 26:25 e 15:10) — ver
    /// {@link #setItState}. Consumido por `MRS`/`MSR` (que NÃO devem alterar estes bits ao tocar
    /// apenas N/Z/C/V/Q/GE) e pelo salvamento/restauração automático via SPSR na entrada/saída de
    /// exceção (que já preserva o CPSR inteiro, então ITSTATE "anda de carona" sem código extra).
    public int itState() {
        int high = (value & IT_HIGH_MASK) >>> IT_HIGH_SHIFT;
        int low = (value & IT_LOW_MASK) >>> IT_LOW_SHIFT;
        return (high << 2) | low;
    }

    /// Grava o ITSTATE\[7:0\] cru nos dois pedaços não-contíguos do CPSR. `0` sai do IT block.
    public void setItState(int itState) {
        int high = (itState >>> 2) & 0x3F;
        int low = itState & 0x3;
        value = (value & ~IT_HIGH_MASK & ~IT_LOW_MASK) | (high << IT_HIGH_SHIFT) | (low << IT_LOW_SHIFT);
    }

    private void setFlag(int mask, boolean enabled) {
        if (enabled) {
            value |= mask;
        } else {
            value &= ~mask;
        }
    }
}
