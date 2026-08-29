# B15 — Perfil M moderno: ARMv7E-M, ARMv8-M e ARMv8.1-M: épico

**Trilha:** B · **Repo:** arm-jitter (+ revalidação G5 nos consumidores) · **Status:** 📋 plano

Este épico existe por causa de **duas pendências que já apareceram sozinhas** e apontam para a
mesma lacuna:

1. **B12.4** (catálogo de processadores, perfil M) só conseguiu catalogar `ARMv6-M` puro
   (`SC000`/`Cortex-M0`/`M0+`/`M1`). `SC300`/`Cortex-M3`/`M4`/`M7`/`M23`/`M33`/`M35P`/`M52`/`M55`/
   `M85` ficaram FORA, com o achado explícito: *"`ARMV7M` existente inclui `SATURATING`/DSP,
   mapear `Cortex-M3` nele seria entrada factualmente errada, não aproximação conservadora"*. Ou
   seja: faltam os presets `ARMv7-M` puro, `ARMv7E-M`, `ARMv8-M` (baseline e mainline) e `ARMv8.1-M`.
2. **MVE/Helium** (`b16-plano-mve-helium.md`, 352 encodings) é a extensão vetorial do **ARMv8.1-M**
   — não tem como sair de "não se aplica a nenhum preset atual" enquanto ARMv8.1-M não existir.

E há um terceiro grupo que não é "não se aplica", mas está a **0%** e pertence aqui:
`ARMv7-M — coprocessador ausente` (`m-nocp.decode`, 11 encodings, `v6-M 0% (0/11)` ·
`v7-M 0% (0/11)`) — a única linha da tabela inteira que mede 0% num preset REAL.

## O número (medido)

| Grupo | Arquivo | Encodings | Hoje |
|---|---|---:|---|
| ARMv7-M — coprocessador ausente | `m-nocp.decode` | 11 | **0%** em v6-M e v7-M |

Conteúdo de `m-nocp.decode`: `NOCP`/`NOCP_8_1` (a exceção de "coprocessador ausente" propriamente
dita), `VLDR_sysreg`/`VSTR_sysreg`, `VLLDM_VLSTM` (lazy save/restore de estado FP), `VSCCLRM`
(limpeza de registradores em transição segura), `VMSR_VMRS`.

## Por que 0% em `m-nocp` é diferente de "não se aplica"

`NOCP` é o mecanismo pelo qual um Cortex-M **sem** unidade de ponto flutuante levanta UsageFault
ao encontrar uma instrução de coprocessador — é o G8 do perfil M, escrito na arquitetura. Sem ele,
firmware que testa "tenho FPU?" executando uma instrução VFP e capturando a falha simplesmente não
funciona. `VLLDM`/`VLSTM`/`VSCCLRM` são o protocolo de troca de contexto FP entre estado seguro e
não seguro (ARMv8-M Security Extension). São instruções de **infraestrutura de exceção**, não
aritmética — encaixam no `MProfileExceptionModel` que a B7.2 já construiu.

## Escada

| Task | Escopo | Encodings | Depende de |
|---|---|---:|---|
| **B15.1** | Presets que faltam no perfil M clássico: `ARMV7M` puro (sem DSP) e `ARMV7EM` (com DSP) — o `ARMV7M` de hoje é, de fato, um `ARMv7E-M`; **G3**: manter o nome atual com o comportamento atual e introduzir os novos ao lado, nunca redefinir o existente. Destrava `SC300`/`Cortex-M3`/`M4`/`M7` no `ArmProcessor` (B12.4) | 0 | — |
| **B15.2** | `NOCP`/`NOCP_8_1`: exceção de coprocessador ausente no `MProfileExceptionModel` (`CPACR`/`NSACR` + UsageFault com `NOCP` em `CFSR`) — fecha 2 das 11 células a 0% e dá ao perfil M o G8 que a arquitetura exige | 2 | B15.1 |
| **B15.3** | `VMSR_VMRS` + `VLDR_sysreg`/`VSTR_sysreg` (acesso a `FPSCR`/`FPCXT` do perfil M pela forma memory-mapped) | 3 | B15.2 |
| **B15.4** | `ArmFeature.M_PROFILE_SECURITY` + preset `ARMV8M_BASELINE`/`ARMV8M_MAINLINE`: modelo de estado seguro/não-seguro mínimo (banking de MSP/PSP por estado, `SG`/`BLXNS`/`BXNS`, `TT`/`TTT`/`TTA`/`TTAT` — a `TT` já existe desde B9.11, confirmada correta por medição) | ~8 (em `t32`) | B15.1 |
| **B15.5** | `VLLDM_VLSTM` + `VSCCLRM`: lazy FP state preservation e limpeza de registradores na transição segura (ARMv8-M) | 6 | B15.4, B15.3 |
| **B15.6** | Preset `ARMV8_1M` (`extending(ARMV8M_MAINLINE, ...)`) + `ArmFeature.LOW_OVERHEAD_BRANCH`: `WLS`/`WLSTP`/`DLS`/`DLSTP`/`LE`/`LETP`/`LCTP` (Low Overhead Branch, ARMv8.1-M) — `DLS`/`LE`/`LCTP`/`VCTP` já aparecem no inventário `t32.decode` | ~10 | B15.4 |
| **B15.7** | **Fechamento do catálogo**: entradas de `ArmProcessor` destravadas por B15.1/B15.4/B15.6 — `SC300`, `Cortex-M3`, `M4`, `M7`, `M23`, `M33`, `M35P`, `M52`, `M55`, `M85` (as 10 que a B12.4 documentou como pendentes) | 0 | B15.6, B15.5 |

## Meta

- `m-nocp.decode` sai de 0% e é a primeira linha da tabela a fechar 11/11 num preset real.
- Os 10 processadores Cortex-M que a B12.4 teve de deixar de fora entram no catálogo — **fechando
  a pendência que aquela task registrou explicitamente**.
- `ARMV8_1M` passa a existir, que é o pré-requisito duro de `b16-plano-mve-helium.md`.

## Armadilhas

- **Não redefinir `ARMV7M`** (G3): o preset atual já é consumido; a B9.16 acabou de ajustá-lo
  (adicionou as 8 features de DSP). Se `ARMV7M` virar "v7-M puro sem DSP", a B9.16 é revertida na
  prática e T32 v7-M cai de 94% para ~52%. Preset novo ao lado, sempre.
- **`TT` já existe e está correta** — a B9.11 mediu isso ("`TT` (mencionado pela B9.10 como
  possível bug) já estava correto, confirmado por medição"). B15.4 não deve reimplementá-la, só
  gateá-la pelo preset ARMv8-M e acrescentar as variantes `TTT`/`TTA`/`TTAT`.
- **Security Extension é opcional** mesmo no ARMv8-M: `Cortex-M23`/`M33` existem em versões com e
  sem. O preset tem que ser componível (`ArmFeature.M_PROFILE_SECURITY` separado do
  `ARMV8M_BASELINE`), não um monólito.
- `VLLDM`/`VLSTM` mexem em estado FP que o `MProfileExceptionModel` empilha — mudar o formato de
  stacking exige rodar os testes de `B7.2`/`B7.3` (SCS/NVIC/SysTick), não só os novos.
