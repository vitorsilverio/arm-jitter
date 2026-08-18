package dev.vitorsilverio.armjitter.ir64;

/// Identifica um registrador de sistema AArch64 acessável via `MRS`/`MSR (register)`
/// (`ARM DDI 0487 C5.2.3`, task B6.6.1) — resolvido pelo DECODER a partir da 5-upla crua
/// `op0:op1:CRn:CRm:op2` do encoding, nunca pelo executor (mesma disciplina de resolução única já
/// usada por {@link Ir64Condition#decode}).
///
/// Cobre só o subconjunto necessário para a MMU v8 (B6.6.3+), para a entrada/saída de exceção
/// EL0→EL1 (`VBAR_EL1`/`ELR_EL1`/`SPSR_EL1`, B6.6.4) e, a partir de B6.6.7, a superfície mínima de
/// EL1 exigida por um kernel real (identidade da CPU + timer genérico) — todos com `op0=3`/`op1=0`
/// (registradores "gerais" de EL1), EXCETO o grupo de timer genérico (`op0=3`/`op1=3`,
/// acessível de EL0, ver comentário de cada constante). `FPCR`/`FPSR` (pendência de B6.5.1) ficam
/// FORA por decisão explícita de fronteira de épico (D3 da task B6.6.1) — o mecanismo geral já
/// serve para eles, mas a task que os inclui é própria.
///
/// **B6.6.7 — dois grupos de resolução distintos** (ver `Aarch64Core#readIntrinsicSystemRegister`/
/// `Aarch64Decoder#decodeSystemRegisterId`): `CURRENT_EL`/`MPIDR_EL1`/`MIDR_EL1`/`ID_AA64*`/
/// `TPIDR_EL1` são resolvidos DIRETO pelo `Aarch64Core` (identidade da própria CPU emulada — não
/// fazem sentido como algo "plugável" pelo hospedeiro, mesmo espírito de por que `PstateRegister`
/// não é pluggable); o timer genérico (`CNTFRQ_EL0`/`CNTPCT_EL0`/`CNTP_TVAL_EL0`/`CNTP_CTL_EL0`/
/// `CNTP_CVAL_EL0`) continua indo para o {@link dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus}
/// do hospedeiro (mesmo padrão D2 de B6.6.1/B6.6.3 — a frequência/contagem depende do relógio real
/// do host, e a entrega de IRQ do comparador de timer é responsabilidade do consumidor, não deste
/// registrador em si — ver a task B6.6.7, "Não inclui").
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
    SPSR_EL1,

    // ── B6.6.7: identidade da CPU, resolvidos direto pelo `Aarch64Core` (ver javadoc da classe) ──

    /// `CurrentEL` (`op0=3,op1=0,CRn=4,CRm=2,op2=2`) — nível de exceção atual em `[3:2]`
    /// (`0b00`=EL0, `0b01`=EL1; `[1:0]` são `RES0`). Somente leitura (`MSR` não é encoding válido
    /// para este registrador — não checado aqui, decisão de decoder).
    CURRENT_EL,
    /// `MPIDR_EL1` (`op0=3,op1=0,CRn=0,CRm=0,op2=5`) — identificador de multiprocessamento.
    /// Constante (core único emulado): bit `31` (`RES1`) + bit `30` (`U`, uniprocessador) setados,
    /// `Aff0`-`Aff3` em `0` (só um core, `ARM DDI 0487 D19.2.87`).
    MPIDR_EL1,
    /// `MIDR_EL1` (`op0=3,op1=0,CRn=0,CRm=0,op2=0`) — identificador do implementador/modelo da
    /// CPU. Constante fixa no valor real do Cortex-A53 do Raspberry Pi 3 (`0x410FD034`, alvo
    /// primário desta task via a F11 do `virtual-arm-box`) — sem consumidor que precise de um
    /// valor configurável ainda (mesma disciplina "sem hospedeiro plugável para identidade").
    MIDR_EL1,
    /// `ID_AA64PFR0_EL1` (`op0=3,op1=0,CRn=0,CRm=4,op2=0`) — features de processamento. Constante
    /// mínima: `EL0`/`EL1` `=0b0001` (só AArch64, sem AArch32), `EL2`/`EL3=0` (não implementados,
    /// consistente com o escopo do épico B6 — sem virtualização/TrustZone), demais campos (FP/
    /// AdvSIMD/GIC/RAS/SVE) `=0` (implementados na forma básica ou ausentes, ver Armadilhas da
    /// task B6.6.7 — nenhum destes é auditado bit a bit contra hardware real).
    ID_AA64PFR0_EL1,
    /// `ID_AA64ISAR0_EL1` (`op0=3,op1=0,CRn=0,CRm=6,op2=0`) — extensões de conjunto de
    /// instrução (`SHA`/`AES`/`CRC32`/atômicos/...). Constante `0` (nenhuma extensão opcional
    /// implementada) — kernels reais toleram isso desabilitando os caminhos otimizados
    /// correspondentes, não é um bloqueio de boot.
    ID_AA64ISAR0_EL1,
    /// `ID_AA64MMFR0_EL1` (`op0=3,op1=0,CRn=0,CRm=7,op2=0`) — features de gerência de memória.
    /// Constante: `PARange[3:0]=0b0101` (48 bits de endereço físico, casando com o que
    /// `TranslatingAddressSpace64`/B6.6.2 já suporta em VA), `TGran4[31:28]=0b0000` (granule de
    /// 4KiB suportado — único granule que este emulador decodifica).
    ID_AA64MMFR0_EL1,
    /// `ID_AA64DFR0_EL1` (`op0=3,op1=0,CRn=0,CRm=5,op2=0`) — features de debug. Constante:
    /// `DebugVer[3:0]=0b0110` (arquitetura de debug ARMv8, valor real de referência — nenhum
    /// registrador de debug em si é implementado, só o campo de versão para não confundir
    /// detecção de features do kernel com "debug ausente" `=0`, que teria semântica arquitetural
    /// diferente).
    ID_AA64DFR0_EL1,
    /// `TPIDR_EL1` (`op0=3,op1=0,CRn=13,CRm=0,op2=4`) — ponteiro de dados de thread do kernel
    /// (`per_cpu_offset` no Linux real). Armazenamento puro leitura/escrita pelo GUEST — ao
    /// contrário dos registradores de identidade acima, este É mutável, mas ainda não precisa de
    /// hospedeiro plugável (é só um escaninho de 64 bits, guardado direto no `Aarch64Core`).
    TPIDR_EL1,

    // ── B6.6.7: timer genérico, EL0-acessível (`op0=3,op1=3`) — via `Aarch64SystemRegisterBus` ──

    /// `CNTFRQ_EL0` (`op0=3,op1=3,CRn=14,CRm=0,op2=0`) — frequência do contador do timer
    /// genérico, em Hz. Configurada pelo hospedeiro (depende do relógio real emulado).
    CNTFRQ_EL0,
    /// `CNTPCT_EL0` (`op0=3,op1=3,CRn=14,CRm=0,op2=1`) — valor atual do contador físico
    /// (monotônico, crescente). Fonte de verdade é o hospedeiro.
    CNTPCT_EL0,
    /// `CNTP_TVAL_EL0` (`op0=3,op1=3,CRn=14,CRm=2,op2=0`) — valor do temporizador do comparador
    /// físico (contagem regressiva relativa; visão alternativa de {@link #CNTP_CVAL_EL0}).
    CNTP_TVAL_EL0,
    /// `CNTP_CTL_EL0` (`op0=3,op1=3,CRn=14,CRm=2,op2=1`) — controle do comparador físico
    /// (`ENABLE`/`IMASK`/`ISTATUS`).
    CNTP_CTL_EL0,
    /// `CNTP_CVAL_EL0` (`op0=3,op1=3,CRn=14,CRm=2,op2=2`) — valor absoluto de disparo do
    /// comparador físico.
    CNTP_CVAL_EL0
}
