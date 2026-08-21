package dev.vitorsilverio.armjitter.core64;

import dev.vitorsilverio.armjitter.ir64.Ir64Condition;

/// Representa `PSTATE.{N,Z,C,V}` do AArch64 — deliberadamente uma classe NOVA, não
/// {@link dev.vitorsilverio.armjitter.core.CpsrRegister} reaproveitada (decisão registrada na
/// task B6.1/RFC-IR-64BIT.md §3.1: `PSTATE` não é um CPSR "maior", A64 não tem banking de
/// registrador por modo, não tem T-bit/IT-state/GE, e o único subconjunto em comum com o CPSR de
/// 32 bits são justamente os 4 flags de condição — replicar é mais barato e mais claro que
/// generalizar um único registrador para dois mundos incompatíveis).
///
/// Escopo original (B6.1): só `N`/`Z`/`C`/`V`, suficiente para avaliar `B.cond`. `PSTATE` tem
/// muitos outros campos no AArch64 real (`DAIF`, `SPSel`, `EL`, `SS`, `IL`...) que ficavam fora até
/// serem exigidos por uma task futura (B6.1 é só EL0, sem exceções nem troca de nível).
///
/// **B6.6.7 acrescenta só o bit `I` de `DAIF`** ({@link #irqDisabled()}/{@link #setIrqDisabled}) —
/// o mínimo para IRQ mascarável funcionar (`Aarch64Core#enterIrq` PRECISA saber se a máscara está
/// ativa, e precisa setá-la ao entrar no handler para não reentrar em IRQ aninhada). `D`/`A`/`F`
/// (debug/SError/FIQ) continuam FORA — sem consumidor real modelado (SError/FIQ não são gerados
/// por nenhum caminho deste emulador; `D` é debug, fora do épico B6 inteiro).
public final class PstateRegister {
    /// Bit N (negativo) dentro do valor bruto de 4 bits.
    public static final int NEGATIVE_FLAG = 1 << 3;
    /// Bit Z (zero) dentro do valor bruto de 4 bits.
    public static final int ZERO_FLAG = 1 << 2;
    /// Bit C (carry) dentro do valor bruto de 4 bits.
    public static final int CARRY_FLAG = 1 << 1;
    /// Bit V (overflow) dentro do valor bruto de 4 bits.
    public static final int OVERFLOW_FLAG = 1;
    /// Deslocamento de `N`/`Z`/`C`/`V` dentro do formato real de armazenamento de `SPSR_ELx`
    /// (`ARM DDI 0487 C5.2.19`, bits `[31:28]`) — MESMA posição do CPSR do ARM32. Usado por
    /// {@link #toSpsrFormat()}/{@link #setFromSpsrFormat(long)} (task B6.6.4, `ERET`/entrada de
    /// exceção EL0→EL1).
    private static final int SPSR_NZCV_SHIFT = 28;
    /// Deslocamento do bit `I` (máscara de IRQ) dentro do formato real de `SPSR_ELx`/`DAIF`
    /// (`ARM DDI 0487 C5.2.19`, bit `7`) — MESMA posição do bit `I` do CPSR do ARM32 (B6.6.7).
    private static final int SPSR_I_BIT = 1 << 7;

    private int nzcv;
    /// Bit `I` de `DAIF` (`true` = IRQ mascarada, core não aceita interrupção) — B6.6.7.
    private boolean irqDisabled;

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

    /// Retorna `true` quando IRQ está mascarada (`DAIF.I=1`) — B6.6.7.
    public boolean irqDisabled() {
        return irqDisabled;
    }

    /// Atualiza a máscara de IRQ (`DAIF.I`) — B6.6.7.
    public void setIrqDisabled(boolean irqDisabled) {
        this.irqDisabled = irqDisabled;
    }

    /// Codifica o estado atual no formato real de armazenamento de `SPSR_ELx` (`ARM DDI 0487
    /// C5.2.19`): `N`/`Z`/`C`/`V` em `[31:28]` e `I` em `[7]` (B6.6.7) — usado por
    /// {@link Aarch64Core#enterMemoryAbort}/`Aarch64Core#enterIrq`/etc. para preencher o banco de
    /// `SPSR_ELx` certo (ver `Aarch64ExceptionState#setSpsr`); o CHAMADOR ainda tem que somar
    /// (`|`) o campo `M[3:0]` do nível de origem (`Aarch64ExceptionLevel#spsrMode()`, B10.1) —
    /// esta classe não conhece nível de exceção nenhum. `D`/`A`/`F`/`SPSel` (dentro de `M`) ficam
    /// `0` — não modelados ainda (ver javadoc da classe).
    public long toSpsrFormat() {
        return (((long) nzcv) << SPSR_NZCV_SHIFT) | (irqDisabled ? SPSR_I_BIT : 0);
    }

    /// Decodifica um valor de `SPSR_ELx` (ex. {@code Aarch64ExceptionState#spsr1()} num `ERET`,
    /// B6.6.4/B6.6.7), restaurando `N`/`Z`/`C`/`V` (bits `[31:28]`) e `I` (bit `[7]`) — os demais
    /// bits são ignorados (ainda não modelados).
    public void setFromSpsrFormat(long spsrValue) {
        setNzcv((int) ((spsrValue >>> SPSR_NZCV_SHIFT) & 0xF));
        irqDisabled = (spsrValue & SPSR_I_BIT) != 0;
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
