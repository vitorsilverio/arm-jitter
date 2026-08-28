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
    /// `TTBR1_EL1` (`op0=3,op1=0,CRn=2,CRm=0,op2=1`) — base da tabela de tradução do espaço de
    /// endereço ALTO (kernel, VA com bit 55 setado). Achado real da F11: a hipótese original de
    /// `B6.13` (bit 63 selecionaria) foi refutada por instrumentação — o QEMU real
    /// (`aa64_va_parameters`) seleciona por bit 55; implementado de verdade aqui (não mais
    /// armazenamento puro) porque o `kernel8.img` real programa este registrador antes de ativar
    /// a MMU, e o espaço de endereço do próprio kernel depende dele.
    TTBR1_EL1,
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
    /// `CPACR_EL1` (`op0=3,op1=0,CRn=1,CRm=0,op2=2`) — controle de trap de FP/SIMD/SVE para EL1
    /// (mesmo `CRn`/`CRm` de {@link #SCTLR_EL1}, só `op2` diferente — achado real da F11, gap que
    /// bloqueava `cpacr_el1` sendo escrito no início de `head.S`). Armazenamento puro, mesma
    /// disciplina de {@code CPTR_EL2}/{@code CPTR_EL3} (B10.2/B10.3): sem trap real modelado, o
    /// decoder A64 deste emulador nunca consulta os bits `FPEN` para decidir se uma instrução VFP/
    /// AdvSIMD é permitida.
    CPACR_EL1,

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
    /// `ID_AA64MMFR1_EL1` (`op0=3,op1=0,CRn=0,CRm=7,op2=1`) — features de gerência de memória
    /// (parte 2: `VHE`/`HPDS`/`LOR`/`PAN`/`VMIDBits`/...). Constante `0` (nenhuma extensão
    /// opcional implementada), mesma disciplina de {@link #ID_AA64ISAR0_EL1} — achado real da F11
    /// (`kernel8.img` real lê este registrador logo depois de `CPACR_EL1` em `head.S`).
    ID_AA64MMFR1_EL1,
    /// `ID_AA64MMFR2_EL1` (`CRn=0,CRm=7,op2=2`) — features de gerência de memória (parte 3).
    /// Constante `0`, mesma disciplina de {@link #ID_AA64MMFR1_EL1} — achado real da F11.
    ID_AA64MMFR2_EL1,
    /// `ID_AA64MMFR3_EL1` (`CRn=0,CRm=7,op2=3`) — features de gerência de memória (parte 4:
    /// `MEC`/`S1PIE`/`S1POE`/`SCTLRX`/...). Constante `0`, achado real da F11 (bloqueio
    /// imediatamente seguinte a `ID_AA64MMFR1_EL1` no `head.S` real).
    ID_AA64MMFR3_EL1,
    /// `ID_AA64MMFR4_EL1` (`CRn=0,CRm=7,op2=4`) — features de gerência de memória (parte 5).
    /// Constante `0`, mesma disciplina de {@link #ID_AA64MMFR1_EL1}.
    ID_AA64MMFR4_EL1,
    /// `ID_AA64PFR1_EL1` (`CRn=0,CRm=4,op2=1`) — features de processamento (parte 2: `BT`/`SSBS`/
    /// `MTE`/`SME`/...). Constante `0` (nenhuma extensão opcional implementada).
    ID_AA64PFR1_EL1,
    /// `ID_AA64ZFR0_EL1` (`CRn=0,CRm=4,op2=4`) — features de SVE. Constante `0` (SVE não
    /// implementada — coerente com {@link #ID_AA64PFR0_EL1}, que não anuncia SVE).
    ID_AA64ZFR0_EL1,
    /// `ID_AA64DFR1_EL1` (`CRn=0,CRm=5,op2=1`) — features de debug (parte 2). Constante `0`.
    ID_AA64DFR1_EL1,
    /// `ID_AA64ISAR1_EL1` (`CRn=0,CRm=6,op2=1`) — extensões de conjunto de instrução (parte 2:
    /// `DPB`/`APA`/`API`/`JSCVT`/`FCMA`/...). Constante `0`, mesma disciplina de
    /// {@link #ID_AA64ISAR0_EL1}.
    ID_AA64ISAR1_EL1,
    /// `ID_AA64ISAR2_EL1` (`CRn=0,CRm=6,op2=2`) — extensões de conjunto de instrução (parte 3).
    /// Constante `0`, mesma disciplina de {@link #ID_AA64ISAR0_EL1}.
    ID_AA64ISAR2_EL1,
    /// `REVIDR_EL1` (`CRn=0,CRm=0,op2=6`) — revisão específica do implementador, sem significado
    /// arquitetural padronizado (`ARM DDI 0487 D19.2.109`). Constante `0` (mesmo valor default
    /// documentado quando o implementador não define nada específico).
    REVIDR_EL1,
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
    /// `TPIDR_EL0` (`op0=3,op1=3,CRn=13,CRm=0,op2=2`, B8.14) — ponteiro de dados de thread do
    /// USERSPACE (o TLS base que `musl`/`glibc` gravam no `crt0`, antes de qualquer outra coisa —
    /// achado real: sem isso NENHUM binário aarch64 real com libc chega a rodar). Armazenamento
    /// puro leitura/escrita, mesma disciplina de {@link #TPIDR_EL1} (não há hospedeiro plugável).
    /// No hardware real é `R/W` de EL0 e EL1; este emulador não modela essa distinção de
    /// privilégio (mesma simplificação já aplicada aos registradores de debug, B10.7).
    TPIDR_EL0,
    /// `TPIDRRO_EL0` (`op0=3,op1=3,CRn=13,CRm=0,op2=3`, B8.14) — segundo escaninho de thread,
    /// `R/O` de EL0 e `R/W` de EL1 no hardware real (glibc o usa para um ponteiro de TLS
    /// alternativo em alguns ABIs). Mesma simplificação de {@link #TPIDR_EL0}: sem enforcement de
    /// privilégio, aceita `MSR`/`MRS` dos dois lados.
    TPIDRRO_EL0,
    /// `FPCR` (`op0=3,op1=3,CRn=4,CRm=4,op2=0`, B8.15) — Floating-point Control Register
    /// (arredondamento/exceções habilitadas/flush-to-zero). Pendência EXPLÍCITA desde B6.6.1 (D3)
    /// e B6.5.1: armazenamento puro leitura/escrita — o modo de arredondamento efetivo continua
    /// FIXO em round-to-nearest-even em toda a aritmética FP (mesma simplificação documentada em
    /// `FRINTX`/`FRINTI`, `Aarch64Decoder`); o guest pode ler/escrever o registrador sem crashar,
    /// mas mudar `RMode` não muda o comportamento real das operações — modelar isso de verdade é
    /// escopo de uma task futura própria (não presumido desnecessário, só sequenciado depois).
    FPCR,
    /// `FPSR` (`op0=3,op1=3,CRn=4,CRm=4,op2=1`, B8.15) — Floating-point Status Register (flags de
    /// exceção cumulativas: `IOC`/`DZC`/`OFC`/`UFC`/`IXC`/`IDC`). Mesma disciplina de "armazenamento
    /// puro" de {@link #FPCR}: nenhuma operação FP deste emulador seta essas flags de verdade
    /// ainda (nenhuma condição de exceção FP é detectada hoje) — o guest sempre lê o que ele mesmo
    /// escreveu, nunca um flag setado pelo hardware emulado.
    FPSR,
    /// `CTR_EL0` (`op0=3,op1=3,CRn=0,CRm=0,op2=1`) — Cache Type Register, somente leitura
    /// (`PL0_R` no hardware real). Constante fixa no valor real do Cortex-A53 do Raspberry Pi 3
    /// (`0x84448004`, mesmo alvo de {@link #MIDR_EL1}, task B6.10) — apesar de viver no mesmo
    /// grupo de bits `op1=3` do timer genérico (ver javadoc da classe), é identidade CONSTANTE da
    /// CPU, não algo dependente do relógio do hospedeiro — resolvida DIRETO pelo `Aarch64Core`,
    /// mesma disciplina de `MIDR_EL1`/`ID_AA64*`.
    CTR_EL0,
    /// `DCZID_EL0` (`op0=3,op1=3,CRn=0,CRm=0,op2=7`) — Data Cache Zero ID, somente leitura.
    /// Constante `0x10` (só o bit `DZP`, "DC ZVA desabilitado") — este emulador não implementa a
    /// instrução `DC ZVA`, então anunciar `DZP=1` é o valor correto (task B6.10), evitando que o
    /// guest tente usá-la e bata num `UnsupportedOperationException` de decode em vez de
    /// simplesmente não usar o caminho otimizado.
    DCZID_EL0,

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
    CNTP_CVAL_EL0,
    /// `CNTVCT_EL0` (`op0=3,op1=3,CRn=14,CRm=0,op2=2`, B8.16) — valor atual do contador VIRTUAL
    /// (`= CNTPCT_EL0 - CNTVOFF_EL2`; sem `CNTVOFF_EL2` modelado, mesmo raciocínio de
    /// simplificação já aplicado ao resto da árvore — o hospedeiro decide o que devolver, mesmo
    /// papel de {@link #CNTPCT_EL0}).
    CNTVCT_EL0,
    /// `CNTV_TVAL_EL0`/`CNTV_CTL_EL0`/`CNTV_CVAL_EL0` (B8.16, `CRn=14,CRm=3`) — comparador
    /// VIRTUAL, mesmo layout/papel de {@link #CNTP_TVAL_EL0}/{@link #CNTP_CTL_EL0}/
    /// {@link #CNTP_CVAL_EL0}, só `CRm` muda (`2`→`3`).
    CNTV_TVAL_EL0,
    CNTV_CTL_EL0,
    CNTV_CVAL_EL0,

    // ── B8.16: PSTATE via MRS/MSR (`op0=3,op1=3,CRn=4,CRm=2`) — ESTADO REAL do core, resolvido
    // ── intrinsecamente (não pluggable, mesma razão de CurrentEL/identidades da CPU) ────────────

    /// `NZCV` (`op0=3,op1=3,CRn=4,CRm=2,op2=0`, B8.16) — os mesmos 4 flags de condição que toda
    /// ALU/`B.cond` já lê via {@code Aarch64Core#pstate()} (`PstateRegister`), só expostos por uma
    /// segunda via de acesso (`MRS`/`MSR` em vez de efeito colateral de instrução aritmética).
    /// Formato do valor de 64 bits: `N`/`Z`/`C`/`V` em `[31:28]`, resto `RES0` — MESMA posição de
    /// {@code PstateRegister#toSpsrFormat()}. Ler/escrever aqui MUDA o estado real (uma `B.cond`
    /// logo depois de um `MSR NZCV` vê o valor novo) — não é um escaninho paralelo.
    NZCV,
    /// `DAIF` (`op0=3,op1=3,CRn=4,CRm=2,op2=1`, B8.16) — só o bit `I` (máscara de IRQ, bit `7`,
    /// {@code PstateRegister#irqDisabled()}) tem efeito real, mesma disciplina já registrada por
    /// B6.6.7 (`D`/`A`/`F` — debug/SError/FIQ — não são modelados, `WI`: aceitos na escrita, mas
    /// sempre lidos como `0`, nunca setados de verdade). Ler/escrever o bit `I` aqui afeta o MESMO
    /// estado que `Aarch64Core#enterIrq` consulta, não um escaninho paralelo.
    DAIF,

    // ── B10.2: registradores de sistema EL2 (`op0=3,op1=4`), armazenamento puro por enquanto —
    // ── SEM side effect funcional (nenhum código roda em EL2 ainda, ver `Aarch64VmsaSystemRegisters`;
    // ── roteamento real de HVC/SMC/IRQ/abort é B10.4/B10.5, stage-2 é B10.8).

    /// `SCTLR_EL2` (`op0=3,op1=4,CRn=1,CRm=0,op2=0`) — controle do sistema em EL2. Armazenamento
    /// puro: NÃO liga a MMU de stage-1 EL1 (`TranslatingAddressSpace64`) — essa é controlada só
    /// por {@link #SCTLR_EL1}; ligar stage-2 real é B10.8.
    SCTLR_EL2,
    /// `HCR_EL2` (`op0=3,op1=4,CRn=1,CRm=1,op2=0`) — controle de virtualização (`VM`, `TGE`, ...).
    /// Armazenamento puro; o roteamento real que estes bits deveriam decidir é B10.4/B10.8.
    HCR_EL2,
    /// `MDCR_EL2` (`op0=3,op1=4,CRn=1,CRm=1,op2=1`) — controle de debug/trace delegado a EL2.
    /// Armazenamento puro (sem debugger de hardware conectado, mesma disciplina de B10.7).
    MDCR_EL2,
    /// `CPTR_EL2` (`op0=3,op1=4,CRn=1,CRm=1,op2=2`) — controle de trap de FP/SIMD/SVE para EL2.
    /// Armazenamento puro (sem trap real modelado ainda).
    CPTR_EL2,
    /// `TCR_EL2` (`op0=3,op1=4,CRn=2,CRm=0,op2=2`) — controle de tradução de stage-1 EL2.
    /// Armazenamento puro (mesma disciplina de {@link #TCR_EL1}: granule/tamanho fixos, o valor
    /// escrito não é lido pela stage-1 de EL2).
    TCR_EL2,
    /// `TTBR0_EL2` (`op0=3,op1=4,CRn=2,CRm=0,op2=0`) — base da tabela de tradução de stage-1 EL2
    /// (tasks B10.6b, `AT S1E2R`/`S1E2W`). Liga de verdade em
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64PrivilegedStage1TranslatingAddressSpace64#setTtbr0}
    /// — diferente de {@link #TCR_EL2} (armazenamento puro), este registrador tem efeito
    /// observável real desde que existe.
    TTBR0_EL2,
    /// `VTTBR_EL2` (`op0=3,op1=4,CRn=2,CRm=1,op2=0`) — base da tabela de tradução de stage-2.
    /// Armazenamento puro; ligar de verdade em `TranslatingAddressSpace64` é B10.8.
    VTTBR_EL2,
    /// `VTCR_EL2` (`op0=3,op1=4,CRn=2,CRm=1,op2=2`) — controle de tradução de stage-2.
    /// Armazenamento puro; mesmo destino futuro de {@link #VTTBR_EL2}.
    VTCR_EL2,
    /// `SPSR_EL2` (`op0=3,op1=4,CRn=4,CRm=0,op2=0`) — `PSTATE` salvo na entrada de exceção em EL2.
    /// Delega ao banco por nível de {@link dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState}
    /// (generalizado em B10.1) — mesma fonte única já usada por {@link #SPSR_EL1}.
    SPSR_EL2,
    /// `ELR_EL2` (`op0=3,op1=4,CRn=4,CRm=0,op2=1`) — endereço de retorno de exceção em EL2.
    /// Delega ao banco por nível, mesma disciplina de {@link #SPSR_EL2}.
    ELR_EL2,
    /// `FAR_EL2` (`op0=3,op1=4,CRn=6,CRm=0,op2=0`) — endereço faltoso da exceção mais recente em
    /// EL2. Delega ao banco por nível, mesma disciplina de {@link #SPSR_EL2}.
    FAR_EL2,
    /// `ESR_EL2` (`op0=3,op1=4,CRn=5,CRm=2,op2=0`) — síndrome da exceção mais recente em EL2.
    /// Delega ao banco por nível, mesma disciplina de {@link #SPSR_EL2}.
    ESR_EL2,
    /// `CNTHCTL_EL2` (`op0=3,op1=4,CRn=14,CRm=1,op2=0`) — controle do timer genérico visto de EL2.
    /// Armazenamento puro (o timer genérico deste emulador, B6.6.7, não modela os traps que este
    /// registrador controlaria).
    CNTHCTL_EL2,
    /// `VBAR_EL2` (`op0=3,op1=4,CRn=12,CRm=0,op2=0`) — base da tabela de vetores de exceção de
    /// EL2. Delega ao banco por nível, mesma disciplina de {@link #SPSR_EL2}.
    VBAR_EL2,

    // ── B10.3: registradores de sistema EL3 (`op0=3,op1=6`), mesma disciplina de B10.2:
    // ── armazenamento puro por enquanto — SEM side effect funcional (nenhum código roda em EL3
    // ── ainda; roteamento real de SMC é B10.5). `ESR_EL3`/`FAR_EL3` deliberadamente FORA (não
    // ── listados no plano mestre para esta task, ao contrário de EL2).

    /// `SCTLR_EL3` (`op0=3,op1=6,CRn=1,CRm=0,op2=0`) — controle do sistema em EL3. Armazenamento
    /// puro: NÃO liga nenhuma MMU (stage-1 de EL3 não é modelada por este épico).
    SCTLR_EL3,
    /// `SCR_EL3` (`op0=3,op1=6,CRn=1,CRm=1,op2=0`) — controle seguro (`NS`, roteamento de
    /// `SMC`/IRQ/FIQ para EL3). Armazenamento puro; o roteamento real que estes bits deveriam
    /// decidir é B10.5.
    SCR_EL3,
    /// `CPTR_EL3` (`op0=3,op1=6,CRn=1,CRm=1,op2=2`) — controle de trap de FP/SIMD/SVE para EL3.
    /// Armazenamento puro (sem trap real modelado ainda).
    CPTR_EL3,
    /// `MDCR_EL3` (`op0=3,op1=6,CRn=1,CRm=3,op2=1`) — controle de debug/trace delegado a EL3.
    /// Armazenamento puro (sem debugger de hardware conectado, mesma disciplina de B10.7).
    MDCR_EL3,
    /// `SPSR_EL3` (`op0=3,op1=6,CRn=4,CRm=0,op2=0`) — `PSTATE` salvo na entrada de exceção em EL3.
    /// Delega ao banco por nível de {@link dev.vitorsilverio.armjitter.core64.Aarch64ExceptionState}
    /// (generalizado em B10.1) — mesma fonte única já usada por {@link #SPSR_EL2}.
    SPSR_EL3,
    /// `ELR_EL3` (`op0=3,op1=6,CRn=4,CRm=0,op2=1`) — endereço de retorno de exceção em EL3.
    /// Delega ao banco por nível, mesma disciplina de {@link #SPSR_EL3}.
    ELR_EL3,
    /// `VBAR_EL3` (`op0=3,op1=6,CRn=12,CRm=0,op2=0`) — base da tabela de vetores de exceção de
    /// EL3. Delega ao banco por nível, mesma disciplina de {@link #SPSR_EL3}.
    VBAR_EL3,
    /// `TTBR0_EL3` (`op0=3,op1=6,CRn=2,CRm=0,op2=0`) — base da tabela de tradução de stage-1 EL3
    /// (task B10.6c, `AT S1E3R`/`S1E3W`). Liga de verdade em
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64PrivilegedStage1TranslatingAddressSpace64#setTtbr0}
    /// — mesma disciplina de {@link #TTBR0_EL2}.
    TTBR0_EL3,
    /// `TCR_EL3` (`op0=3,op1=6,CRn=2,CRm=0,op2=2`) — controle de tradução de stage-1 EL3.
    /// Armazenamento puro, mesma disciplina de {@link #TCR_EL2}.
    TCR_EL3,

    // ── B10.6: `PAR_EL1` (`op0=3,op1=0,CRn=7,CRm=4,op2=0`) — resultado da instrução `AT`. ──

    /// `PAR_EL1` (`op0=3,op1=0,CRn=7,CRm=4,op2=0`) — resultado da última `AT` (ou escrito
    /// diretamente pelo guest via `MSR`, arquiteturalmente válido). Armazenamento puro em
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters}; quem calcula o
    /// valor real é {@code Ir64Op.AddressTranslate} via
    /// {@link dev.vitorsilverio.armjitter.core64.Aarch64SystemRegisterBus#addressTranslate}.
    PAR_EL1,

    // ── B10.7: registradores de debug (`op0=2,op1=0`) — sem debugger de hardware conectado, só
    // ── armazenamento puro (leitura devolve exatamente o que foi escrito), SEM enforcement de
    // ── `RO`/`WO` mesmo onde o hardware real os teria (`OSLAR_EL1` é `WO`, `OSLSR_EL1` é `RO`) —
    // ── decisão explícita da task: tolerar o guest, não travá-lo. Só `n=0` de
    // ── `DBGBVR`/`DBGBCR`/`DBGWVR`/`DBGWCR` (consistente com `ID_AA64DFR0_EL1.BRPs=WRPs=0` já
    // ── anunciado por `Aarch64Core`, B6.6.7/B6.10 — ver "Decisão de escopo" da task B10.7).

    /// `MDSCR_EL1` (`op0=2,op1=0,CRn=0,CRm=2,op2=2`) — controle do monitor de debug (`MDE`/`KDE`/
    /// `SS`/...). Armazenamento puro; nenhum bit tem efeito observável (sem debugger conectado).
    MDSCR_EL1,
    /// `OSLAR_EL1` (`op0=2,op1=0,CRn=1,CRm=0,op2=4`) — OS Lock Access Register. `WO` no hardware
    /// real; aqui é armazenamento puro leitura/escrita (ver javadoc da classe, "não travar o
    /// guest").
    OSLAR_EL1,
    /// `OSLSR_EL1` (`op0=2,op1=0,CRn=1,CRm=1,op2=4`) — OS Lock Status Register. `RO` no hardware
    /// real; aqui é armazenamento puro leitura/escrita, mesma disciplina de {@link #OSLAR_EL1}.
    OSLSR_EL1,
    /// `DBGBVR0_EL1` (`op0=2,op1=0,CRn=0,CRm=0,op2=4`) — valor de endereço do breakpoint 0.
    /// Armazenamento puro.
    DBGBVR0_EL1,
    /// `DBGBCR0_EL1` (`op0=2,op1=0,CRn=0,CRm=0,op2=5`) — controle do breakpoint 0. Armazenamento
    /// puro.
    DBGBCR0_EL1,
    /// `DBGWVR0_EL1` (`op0=2,op1=0,CRn=0,CRm=0,op2=6`) — valor de endereço do watchpoint 0.
    /// Armazenamento puro.
    DBGWVR0_EL1,
    /// `DBGWCR0_EL1` (`op0=2,op1=0,CRn=0,CRm=0,op2=7`) — controle do watchpoint 0. Armazenamento
    /// puro.
    DBGWCR0_EL1
}
