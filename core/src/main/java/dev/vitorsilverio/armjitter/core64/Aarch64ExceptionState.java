package dev.vitorsilverio.armjitter.core64;

/// Estado mínimo de exceção EL0→EL1 do {@link Aarch64Core} (task B6.6.4) — sibling
/// DELIBERADAMENTE menor que {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel}
/// (32-bit): só o necessário para um abort de memória entrar em EL1 e um `ERET` voltar para EL0,
/// NÃO um modelo de exceção A64 completo (sem `SVC`/`IRQ`/`FIQ`/`SError`, sem EL2/EL3, ver a task).
///
/// **Única fonte de verdade** para `ELR_EL1`/`SPSR_EL1`/`ESR_EL1`/`FAR_EL1`/`VBAR_EL1`: antes desta
/// task, {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters} (B6.6.3) já
/// tinha campos próprios para esses registradores só para satisfazer `MRS`/`MSR` — este objeto
/// substitui aquele armazenamento (delegado, não duplicado — ver
/// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters}) para que
/// {@link Aarch64Core#enterMemoryAbort} funcione mesmo sem nenhum
/// {@link Aarch64SystemRegisterBus} instalado (ex. teste isolado, sem MMU v8 nenhuma).
///
/// `spsr1` guarda o valor BRUTO de 64 bits escrito/lido por `MSR`/`MRS SPSR_EL1` — armazenamento
/// puro aqui, sem mascarar (mesmo tratamento de `esr1`/`far1`/`vbar1`/`elr1`). A CONVERSÃO
/// semântica de/para {@link PstateRegister#nzcv()} (os únicos flags que este emulador modela) é
/// feita por {@link PstateRegister#toSpsrFormat()}/{@link PstateRegister#setFromSpsrFormat(long)}
/// — `N`/`Z`/`C`/`V` ocupam os bits `[31:28]` do formato real de `SPSR_ELx`
/// (`ARM DDI 0487 C5.2.19`), a MESMA posição do CPSR do ARM32.
public final class Aarch64ExceptionState {
    /// `SP_EL1` — pilha própria de EL1, separada de `SP_EL0` (ver {@link Aarch64Core#sp()}).
    private long sp1;
    /// `ELR_EL1` — endereço de retorno de exceção (equivalente ao `LR` bancado do ARM32).
    private long elr1;
    /// `SPSR_EL1` — valor bruto de 64 bits (ver javadoc da classe).
    private long spsr1;
    /// `ESR_EL1` — síndrome da exceção mais recente (`EC`+`ISS`).
    private long esr1;
    /// `FAR_EL1` — endereço faltoso da exceção mais recente.
    private long far1;
    /// `VBAR_EL1` — base da tabela de vetores de exceção de EL1.
    private long vbar1;
    /// `true` quando o core está executando em EL1 (destino de abort); `false` = EL0. Flag simples
    /// de nível atual — NÃO um enum de 4 níveis completo (só EL0/EL1 existem no escopo de B6.6.4).
    private boolean inEl1;

    /// `SP_EL1`.
    public long sp1() {
        return sp1;
    }

    /// Atualiza `SP_EL1`.
    public void setSp1(long value) {
        sp1 = value;
    }

    /// `ELR_EL1`.
    public long elr1() {
        return elr1;
    }

    /// Atualiza `ELR_EL1`.
    public void setElr1(long value) {
        elr1 = value;
    }

    /// `SPSR_EL1` (valor bruto de 64 bits — ver javadoc da classe).
    public long spsr1() {
        return spsr1;
    }

    /// Atualiza `SPSR_EL1` (valor bruto, sem mascarar).
    public void setSpsr1(long value) {
        spsr1 = value;
    }

    /// `ESR_EL1`.
    public long esr1() {
        return esr1;
    }

    /// Atualiza `ESR_EL1`.
    public void setEsr1(long value) {
        esr1 = value;
    }

    /// `FAR_EL1`.
    public long far1() {
        return far1;
    }

    /// Atualiza `FAR_EL1`.
    public void setFar1(long value) {
        far1 = value;
    }

    /// `VBAR_EL1`.
    public long vbar1() {
        return vbar1;
    }

    /// Atualiza `VBAR_EL1`.
    public void setVbar1(long value) {
        vbar1 = value;
    }

    /// `true` quando o core está atualmente em EL1 (dentro de um handler de abort).
    public boolean inEl1() {
        return inEl1;
    }

    /// Muda o nível atual (`true` = EL1, `false` = EL0).
    public void setInEl1(boolean inEl1) {
        this.inEl1 = inEl1;
    }
}
