# B22 — Resíduos de 32 bits: as últimas células ❌/⚠️ dos presets que já existem: épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** ✅ (2026-09-02, fechado pela B22.6)

Documento MESTRE do épico. Escrito na auditoria de 2026-08-29 ("escrever a spec de todas as
extensões ainda faltantes"). Os épicos B13-B21 cobrem extensões e arquiteturas INTEIRAS que faltam;
este cobre o resto — as **61 células** que ainda não decodificam nos presets de 32 bits que já
existem e já são usados. É o que separa a tabela de "100%" no lado de 32 bits, e o congelamento de
subprojetos (`tasks/README.md`) só sai quando isto fechar junto.

## Inventário completo (medido nesta sessão a partir de `docs/COBERTURA-ISA.md`)

| Grupo | Células | Quais |
|---|---:|---|
| A32 | 19 | `MRS_bank`/`MSR_bank`/`ERET` (v4T, v5TE, v6K, MPCore, v7-A) + `HVC` (v4T, v5TE, v6K, MPCore) |
| T16 | 30 | `HLT` (todos os 7 presets) · `BLX_r`/`SETEND` (v6-M, v7-M) · `YIELD`/`WFE`/`SEV`/`NOP`/`IT` (v4T, v5TE) · `CBZ` (v4T, v5TE) · `BKPT` (v4T) · `BLX_suffix` (v4T, v7-A, v6-M, v7-M) · `BL_BLX_prefix`/`BL_suffix` (v6-M) |
| T32 | 10 | `MRS_bank`/`MSR_bank` (v7-A, v6-M, v7-M) · `ERET` (v6-M, v7-M) · `SMC`/`HVC` (v7-M) |
| VFP | 2 ⚠️ | `VMOV_half` (MPCore, v7-A) |

**As 3 naturezas são diferentes e não se resolvem do mesmo jeito** — é o achado central desta
auditoria, e a razão de o épico existir em vez de uma task só:

1. **Gap de decode REAL** (trabalho de verdade): `HLT`, `VMOV_half`.
2. **Gap de GATING** (decoder pronto, preset sem a feature — mesma classe da B9.16, que fez o T32
   de v7-M saltar de 52% para 94% sem escrever decode nenhum): `BLX_r` em perfil M.
3. **Denominador errado** (a instrução é POSTERIOR à arquitetura da coluna, ou não existe naquele
   perfil — curadoria em `docs/isa-nao-aplicavel.tsv`, mesma classe de B9.10/B9.12/B9.15): hints e
   `IT`/`CBZ` em v4T/v5TE, `BKPT` em v4T, `SETEND` em perfil M, o par legado `BL`/`BLX` onde
   `THUMB2` já o substituiu por `LONG_BRANCH_32`.

E um quarto grupo, que era decisão do usuário e **deixou de ser**: os 29
`ERET`/`HVC`/`SMC`/`MRS_bank`/`MSR_bank`, mantidos `❌` desde sempre (registrado em B9.15/B9.16/
B9.17) — **o usuário decidiu em 2026-08-29 que serão implementados**. Viraram a B22.5, com spec
completa e sem bloqueio.

## Escada

| Task | Escopo | Células | Depende de |
|---|---|---:|---|
| **B22.1** | **`HLT`** (ARM DDI 0487, debug halt): encodings T16 (`1011 1010 10xx xxxx`) e A32/T32; `ArmFeature` própria (é ARMv8-A / ARMv8-M — **em nenhum preset atual deveria decodificar**, o que torna esta task metade decode e metade curadoria: as colunas v4T-v7-M viram `·`, e a feature nasce desligada esperando o preset ARMv8-A de 32 bits de **B14**) | 7 | — |
| **B22.2** | **`VMOV_half`** — hoje `⚠️`, isto é, **decodifica como OUTRA COISA** (cai no caminho genérico de coprocessador `MCR`/`CDP`, que ocupa o mesmo espaço `cp10`/`cp11`). É violação de **G8** viva na tabela, e o único `⚠️` que sobrou no projeto: transferência de meia precisão entre registrador ARM e `S`, gate `FEAT_FP16`/VFPv3-HP. Casa com **B19.5** (o `FEAT_FP16` do lado A64) e com **B14** | 2 | — |
| **B22.3** | **Gating de `BLX_r` no perfil M** (v6-M/v7-M): ARMv6-M **tem** `BLX` registrador; o preset não declara a feature que o `ThumbDecoder` exige. Espelho exato da B9.16 — conferir se `BLX` é a única (auditar o preset inteiro contra o ARM ARM do perfil M antes de mexer) | 4 | — |
| **B22.4** | **Curadoria de denominador** em `isa-nao-aplicavel.tsv`, com a versão que introduziu cada uma: hints `YIELD`/`WFE`/`SEV`/`NOP` e `IT` (ARMv6K/ARMv6T2) e `CBZ` (ARMv6T2) em v4T/v5TE; `BKPT` (ARMv5T) em v4T; `SETEND` em perfil M (não existe); `BLX_suffix`/`BL_BLX_prefix`/`BL_suffix` onde `THUMB2` já os substituiu por `LONG_BRANCH_32` (B2.6 — a linha legada é INALCANÇÁVEL por decisão de decode, não por falta dela). Usar as colunas `grupo`/`ocorrencia` (B9.15/B9.17) para não apagar cobertura real | ~19 | — |
| **[B22.5](b22.5-eret-hvc-smc-banked.md)** | ✅ **DESBLOQUEADA — o usuário decidiu em 2026-08-29 que serão implementadas** (spec completa escrita). As 29 células de `ERET`/`HVC`/`SMC`/`MRS_bank`/`MSR_bank`, com as 3 causas medidas: (1) **incoerência real** em `ARMV7A`, que declara `HYPERVISOR_CALL` mas não `VIRTUALIZATION_EXTENSIONS` — no ARM real são a MESMA extensão, e é isso que mantém `ERET`/`MRS_bank`/`MSR_bank` `❌` num preset que já tem `HVC`; (2) pré-v7 (v4T/v5TE/v6K/MPCore): posteriores, curadoria com fonte; (3) perfil M: não existem em NENHUM perfil M (fonte interna: B9.11). Aceite exige teste de EXECUÇÃO (entrar/retornar de Hyp, banco correto), não só decode | 29 | — |
| **B22.6** | **Fechamento**: remedir, atualizar `docs/VALIDACAO-ARQUITETURAS.md` e registrar o estado final do lado de 32 bits | 0 | B22.1-B22.5 |

## Meta — ATINGIDA (medição da B22.6, 2026-09-02; revalidada pela E11, 2026-09-03)

Medição reproduzível sobre `docs/COBERTURA-ISA.md` (contagem de células por seção,
`./gerar-cobertura-isa.sh` produz a tabela byte a byte idêntica à versionada).

**Qualificação da E11 (2026-09-03):** este resultado vale **contra a revisão do inventário do
QEMU fixada** por `gerar-cobertura-isa.sh` (variável `QEMU_REV`, hoje
`2931a675e9d3fcddedf673509fe9759955fc616d`). Antes da E11 o script baixava de `master` e o
resultado dependia de quando cada máquina baixou os `.decode` — a manchete "0 `❌`" era um fato
sem âncora. Essa revisão introduz a linha `MAYBE_UNDEF_T1_HINT` em `t16.decode` (86→87 linhas),
que a E11 curou para v4T/v5TE (fonte: o próprio commit); T16 continua com **0 `❌`**. Um bump de
`QEMU_REV` pode reintroduzir `❌` — é trabalho novo descoberto, não regressão. A questão de
gating dos hints T16 em v6K/MPCore que esse commit levanta é a task **E13**, não resíduo de B22.

| Seção | `❌` | `⚠️` |
|---|---:|---:|
| `## A32 — instruções ARM de 32 bits` | **0** | **0** |
| `## T16 — Thumb clássico` | **0** | **0** |
| `## T32 — Thumb-2` | **0** | **0** |
| `## VFP — ponto flutuante (condicional)` | **0** | **0** |

**`⚠️` = 0 no projeto INTEIRO** (eram 2, `VMOV_half` em MPCore/v7-A, mortos pela B22.2 — era a
violação de G8 viva mais cara da tabela).

**Delimitação obrigatória** (escrever "32 bits em 100%" sem isto engana a próxima sessão):

- As COLUNAS `v6-M` (88%, 82/93) e `v7-M` (96%, 322/333) **não** chegam a 100%, e é correto: as
  11 células que faltam em cada uma são o grupo `## ARMv7-M — coprocessador ausente` (`m-nocp`:
  `NOCP`/`VLLDM`/`VSCCLRM`/`VLDR_sysreg`), território da **B15** (ARMv7E-M/ARMv8-M/ARMv8.1-M) — o
  único grupo a 0% num preset REAL. **Não é resíduo de B22.**
- `## VFP — formas incondicionais (ARMv8-A)` (17 encodings) e os 3 grupos NEON são
  `NOT_IN_ANY_PRESET` — nem entram no denominador — e são **B14** / **B13**.
- Fechar B22 **NÃO descongela os subprojetos**: o congelamento (`tasks/README.md`) é sobre a
  cobertura TOTAL, e o A64 sozinho tem 2020 células `❌` (**B19**).

Com a decisão do usuário de 2026-08-29 (B22.5 desbloqueada), não sobrou nenhuma exclusão por
decisão: tudo neste épico foi trabalho executável, e foi executado (B22.1-B22.6 ✅).

## Invariantes específicos deste épico

- **A regra máxima do `tasks/README.md` vale integralmente na curadoria**: `isa-nao-aplicavel.tsv`
  nunca recebe "está fora do nosso alvo". A única entrada legítima é "esta versão introduziu a
  instrução depois desta coluna", **com a fonte**. Na dúvida, a célula fica `❌` e vira trabalho.
- **G8 é o motivo de B22.2 existir**: uma instrução não implementada tem que ser RECUSADA, não
  confundida com outra. Enquanto `VMOV_half` decodificar como coprocessador genérico, o projeto tem
  um caso conhecido de corrupção silenciosa em vez de exceção de instrução indefinida.
- **G3**: `HLT` e `VMOV_half` nascem gateados por features que nenhum preset atual declara — os 5
  consumidores não podem ver diferença nenhuma.

## Armadilhas conhecidas

- **`⚠️` não é "quase pronto", é pior que `❌`.** A legenda da própria tabela diz: "decodifica como
  OUTRA coisa... Não é suporte — é o decoder não sabendo recusar". Tratar `VMOV_half` como
  prioridade menor que `HLT` porque "são só 2 células" inverte o risco real.
- **Curadoria por NOME apaga cobertura real**: `NOP`, `IT`, `CBZ` e o par `BL`/`BLX` existem em
  MAIS de um arquivo `.decode` e em mais de uma linha do mesmo arquivo. Sem as colunas `grupo`
  (B9.15) e `ocorrencia` (B9.17), excluir por nome derruba células que hoje são ✅ — foi exatamente
  o erro que a B9.10 evitou de propósito e a B9.15 teve que destravar depois.
- **`BLX_r` em perfil M pode não ser o único**: a B9.16 achou 8 features faltando de uma vez num
  preset que parecia completo. Auditar o preset M inteiro contra o manual ANTES de adicionar uma
  feature isolada.
- **`HLT` é ARMv8, não ARMv7**: acrescentá-la a um preset atual seria o erro oposto ao que a tabela
  aponta — a célula certa depois de B22.1 é `·` (não aplicável por ser posterior) em tudo que existe
  hoje, e `✅` só quando **B14** criar o preset ARMv8-A de 32 bits.
