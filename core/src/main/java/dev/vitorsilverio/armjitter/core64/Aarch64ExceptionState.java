package dev.vitorsilverio.armjitter.core64;

import java.util.Objects;

/// Estado de exceção do {@link Aarch64Core}, banco por nível — generalizado em B10.1 (era só
/// EL0↔EL1 desde B6.6.4; ver `tasks/trilha-b-arquiteturas/b10-plano-el2-el3.md`).
///
/// **Única fonte de verdade** para `SP_ELx`/`ELR_ELx`/`SPSR_ELx`/`ESR_ELx`/`FAR_ELx`/`VBAR_ELx`
/// (`x` em `1`-`3` — `EL0` não tem banco: usa {@link Aarch64Core#spEl0} e não tem `ELR`/`SPSR`/
/// `ESR`/`FAR`/`VBAR` próprios). {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters}
/// (B6.6.3) delega aqui para `MRS`/`MSR` funcionarem mesmo sem nenhum {@link Aarch64SystemRegisterBus}
/// instalado.
///
/// `spsrN` guarda o valor BRUTO de 64 bits escrito/lido por `MSR`/`MRS SPSR_ELx` — armazenamento
/// puro, sem mascarar (mesmo tratamento de `esrN`/`farN`/`vbarN`/`elrN`). A CONVERSÃO semântica
/// de/para {@link PstateRegister#nzcv()}/{@link PstateRegister#irqDisabled()} é feita por
/// {@link PstateRegister#toSpsrFormat()}/{@link PstateRegister#setFromSpsrFormat(long)}; o nível-
/// ALVO de um `ERET` vem de {@link Aarch64ExceptionLevel#fromSpsrValue(long)} sobre o MESMO valor
/// bruto (`N`/`Z`/`C`/`V` em `[31:28]`, `I` em `[7]`, `M[3:0]` em `[3:0]` — todos dentro do MESMO
/// long, formato real de `SPSR_ELx`, `ARM DDI 0487 C5.2.19`).
public final class Aarch64ExceptionState {
    /// Quantidade de níveis endereçáveis pelos arrays abaixo (`EL0`-`EL3`, `ordinal()` 0-3) — o
    /// índice `0` (`EL0`) fica SEMPRE zerado/não lido (`EL0` não tem banco, ver javadoc da classe);
    /// existe só para que `level.ordinal()` indexe direto sem deslocamento (-1 em toda chamada).
    private static final int LEVEL_COUNT = 4;

    private final long[] sp = new long[LEVEL_COUNT];
    private final long[] elr = new long[LEVEL_COUNT];
    private final long[] spsr = new long[LEVEL_COUNT];
    private final long[] esr = new long[LEVEL_COUNT];
    private final long[] far = new long[LEVEL_COUNT];
    private final long[] vbar = new long[LEVEL_COUNT];
    private Aarch64ExceptionLevel currentEl = Aarch64ExceptionLevel.EL0;

    private static void requireBankedLevel(Aarch64ExceptionLevel level) {
        if (level == Aarch64ExceptionLevel.EL0) {
            throw new IllegalArgumentException("EL0 não tem banco de registradores de exceção");
        }
    }

    /// Nível de exceção atual do core.
    public Aarch64ExceptionLevel currentEl() {
        return currentEl;
    }

    /// Muda o nível de exceção atual.
    public void setCurrentEl(Aarch64ExceptionLevel level) {
        currentEl = Objects.requireNonNull(level);
    }

    /// Conveniência pré-B10.1 (EL0/EL1 só): `true` quando {@link #currentEl()} é `EL1`. Mantida
    /// para não quebrar o código/testes que só conheciam 2 níveis — o código novo deve preferir
    /// {@link #currentEl()}.
    public boolean inEl1() {
        return currentEl == Aarch64ExceptionLevel.EL1;
    }

    /// Conveniência pré-B10.1: equivalente a {@code setCurrentEl(inEl1 ? EL1 : EL0)}.
    public void setInEl1(boolean inEl1) {
        currentEl = inEl1 ? Aarch64ExceptionLevel.EL1 : Aarch64ExceptionLevel.EL0;
    }

    /// `SP_ELx` do nível indicado (`x` em `1`-`3`; `EL0` lança — ver {@link Aarch64Core#spEl0}).
    public long sp(Aarch64ExceptionLevel level) {
        requireBankedLevel(level);
        return sp[level.ordinal()];
    }

    /// Atualiza `SP_ELx` do nível indicado.
    public void setSp(Aarch64ExceptionLevel level, long value) {
        requireBankedLevel(level);
        sp[level.ordinal()] = value;
    }

    /// `ELR_ELx` do nível indicado.
    public long elr(Aarch64ExceptionLevel level) {
        requireBankedLevel(level);
        return elr[level.ordinal()];
    }

    /// Atualiza `ELR_ELx` do nível indicado.
    public void setElr(Aarch64ExceptionLevel level, long value) {
        requireBankedLevel(level);
        elr[level.ordinal()] = value;
    }

    /// `SPSR_ELx` (valor bruto) do nível indicado.
    public long spsr(Aarch64ExceptionLevel level) {
        requireBankedLevel(level);
        return spsr[level.ordinal()];
    }

    /// Atualiza `SPSR_ELx` (valor bruto, sem mascarar) do nível indicado.
    public void setSpsr(Aarch64ExceptionLevel level, long value) {
        requireBankedLevel(level);
        spsr[level.ordinal()] = value;
    }

    /// `ESR_ELx` do nível indicado.
    public long esr(Aarch64ExceptionLevel level) {
        requireBankedLevel(level);
        return esr[level.ordinal()];
    }

    /// Atualiza `ESR_ELx` do nível indicado.
    public void setEsr(Aarch64ExceptionLevel level, long value) {
        requireBankedLevel(level);
        esr[level.ordinal()] = value;
    }

    /// `FAR_ELx` do nível indicado.
    public long far(Aarch64ExceptionLevel level) {
        requireBankedLevel(level);
        return far[level.ordinal()];
    }

    /// Atualiza `FAR_ELx` do nível indicado.
    public void setFar(Aarch64ExceptionLevel level, long value) {
        requireBankedLevel(level);
        far[level.ordinal()] = value;
    }

    /// `VBAR_ELx` do nível indicado.
    public long vbar(Aarch64ExceptionLevel level) {
        requireBankedLevel(level);
        return vbar[level.ordinal()];
    }

    /// Atualiza `VBAR_ELx` do nível indicado.
    public void setVbar(Aarch64ExceptionLevel level, long value) {
        requireBankedLevel(level);
        vbar[level.ordinal()] = value;
    }

    // ── Conveniências nomeadas pré-B10.1 (EL1) — equivalentes a chamar as genéricas acima com
    // ── Aarch64ExceptionLevel.EL1; mantidas porque EL1 continua o nível mais usado (todo o
    // ── código pré-B10 e boa parte dos testes já as usam por nome).

    /// `SP_EL1`.
    public long sp1() {
        return sp(Aarch64ExceptionLevel.EL1);
    }

    /// Atualiza `SP_EL1`.
    public void setSp1(long value) {
        setSp(Aarch64ExceptionLevel.EL1, value);
    }

    /// `ELR_EL1`.
    public long elr1() {
        return elr(Aarch64ExceptionLevel.EL1);
    }

    /// Atualiza `ELR_EL1`.
    public void setElr1(long value) {
        setElr(Aarch64ExceptionLevel.EL1, value);
    }

    /// `SPSR_EL1` (valor bruto de 64 bits — ver javadoc da classe).
    public long spsr1() {
        return spsr(Aarch64ExceptionLevel.EL1);
    }

    /// Atualiza `SPSR_EL1` (valor bruto, sem mascarar).
    public void setSpsr1(long value) {
        setSpsr(Aarch64ExceptionLevel.EL1, value);
    }

    /// `ESR_EL1`.
    public long esr1() {
        return esr(Aarch64ExceptionLevel.EL1);
    }

    /// Atualiza `ESR_EL1`.
    public void setEsr1(long value) {
        setEsr(Aarch64ExceptionLevel.EL1, value);
    }

    /// `FAR_EL1`.
    public long far1() {
        return far(Aarch64ExceptionLevel.EL1);
    }

    /// Atualiza `FAR_EL1`.
    public void setFar1(long value) {
        setFar(Aarch64ExceptionLevel.EL1, value);
    }

    /// `VBAR_EL1`.
    public long vbar1() {
        return vbar(Aarch64ExceptionLevel.EL1);
    }

    /// Atualiza `VBAR_EL1`.
    public void setVbar1(long value) {
        setVbar(Aarch64ExceptionLevel.EL1, value);
    }
}
