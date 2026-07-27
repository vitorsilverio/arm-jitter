package dev.vitorsilverio.armjitter.ir64;

/// Identifica um registrador de sistema AArch64 acessável via `MRS`/`MSR (register)`
/// (`ARM DDI 0487 C5.2.3`, task B6.6.1) — resolvido pelo DECODER a partir da 5-upla crua
/// `op0:op1:CRn:CRm:op2` do encoding, nunca pelo executor (mesma disciplina de resolução única já
/// usada por {@link Ir64Condition#decode}).
///
/// Cobre só o subconjunto necessário para a MMU v8 (B6.6.3+) e para a entrada/saída de exceção
/// EL0→EL1 futura (`VBAR_EL1`/`ELR_EL1`/`SPSR_EL1`, antecipados aqui como pré-requisito de
/// B6.6.4) — todos com `op0=3`/`op1=0` (registradores "gerais" de EL1). `FPCR`/`FPSR`
/// (pendência de B6.5.1) ficam FORA por decisão explícita de fronteira de épico (D3 da task
/// B6.6.1) — o mecanismo geral já serve para eles, mas a task que os inclui é própria.
public enum Aarch64SystemRegisterId {
    /// `SCTLR_EL1` (`op0=3,op1=0,CRn=1,CRm=0,op2=0`) — controle do sistema (MMU/cache habilitados).
    SCTLR_EL1,
    /// `TTBR0_EL1` (`op0=3,op1=0,CRn=2,CRm=0,op2=0`) — base da tabela de tradução.
    TTBR0_EL1,
    /// `TCR_EL1` (`op0=3,op1=0,CRn=2,CRm=0,op2=2`) — controle de tradução (granule/tamanho).
    TCR_EL1,
    /// `MAIR_EL1` (`op0=3,op1=0,CRn=10,CRm=2,op2=0`) — atributos de memória indexados.
    MAIR_EL1,
    /// `ESR_EL1` (`op0=3,op1=0,CRn=5,CRm=2,op2=0`) — síndrome da exceção mais recente.
    ESR_EL1,
    /// `FAR_EL1` (`op0=3,op1=0,CRn=6,CRm=0,op2=0`) — endereço faltoso da exceção mais recente.
    FAR_EL1,
    /// `VBAR_EL1` (`op0=3,op1=0,CRn=12,CRm=0,op2=0`) — base da tabela de vetores de exceção.
    VBAR_EL1,
    /// `ELR_EL1` (`op0=3,op1=0,CRn=4,CRm=0,op2=1`) — endereço de retorno de exceção.
    ELR_EL1,
    /// `SPSR_EL1` (`op0=3,op1=0,CRn=4,CRm=0,op2=0`) — `PSTATE` salvo na entrada de exceção.
    SPSR_EL1
}
