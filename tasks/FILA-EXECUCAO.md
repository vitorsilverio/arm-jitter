# Fila de execução — para agentes com contexto limitado

**Este arquivo é enxuto de propósito** (limpo em 2026-08-28 — estava com 1113 linhas de narrativa
histórica, causando sessões novas perderem tempo tentando pegar tasks já fechadas). Status de cada
task vive no `INDICE.md` da trilha correspondente (`tasks/README.md` tem a tabela de trilhas); o
histórico narrativo completo (o que foi feito, achados, decisões) vive na própria task, seção
`## Resultado`, ou em `tasks/FILA-HISTORICO.md` para sessões antigas sem task própria. **Antes de
pegar qualquer coisa, confira o `INDICE.md` da trilha — não confie em texto solto sobre "o que falta"
sem checar o status real ali.**

## Regras de sessão (obrigatórias)

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a próxima task na
   mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes G1-G8), depois o
   `INDICE.md` da trilha (confirma que a task está ⬜/não pega uma já ✅), depois SÓ o arquivo da
   task + os fontes que ela cita. Não explorar o repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao usuário.
4. Nunca pegar itens de "Pendências que EXIGEM modelo forte" (`tasks/README.md`) nem da seção
   "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25) + G5 nos consumidores relevantes,
   status atualizado no `INDICE.md` da trilha, seção `## Resultado` na própria task, 1 commit
   começando com o ID (`B11.x: ...`), `git push`.
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale só entre repos
   DIFERENTES. Commits sempre com paths explícitos (`git add <arquivos da SUA task>`), nunca
   `git add -A`.
7. **Ao fechar uma task, não reescreva a narrativa aqui** — só atualize o `INDICE.md` da trilha (o
   `## Resultado` da própria task já é o histórico). Este arquivo só muda quando o estado descrito
   abaixo ("Onde estamos") muda de verdade.

## Disciplina de custo

1. **G5 "leve" durante iteração, G5 completo só uma vez por sessão**, pouco antes do commit final.
2. **Backend INTERPRETED em boot de sistema real é caro** — rode só quando o JIT já confirmar o
   marco; se não terminar em ~10-15min, documente "não concluído" e siga.
3. **Nunca lance um teste/boot longo em background e pare a sessão "esperando notificação"** — rode
   bloqueante com timeout alto, ou faça polling dentro da mesma chamada.
4. **Orçamento de ~60-80 tool-calls por sessão de investigação aberta.** Se a causa raiz não foi
   isolada, pare, documente o que foi descartado/aprendido e devolva.

## 🔒 Congelamento de subprojetos até 100% de cobertura (decisão do usuário, 2026-08-27)

**Nenhuma task de `armbox`/`gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu` deve ser pega** — nem
investigação, nem feature, nem bugfix — **enquanto `docs/COBERTURA-ISA.md` não mostrar cobertura
completa das arquiteturas/perfis/features/modos ARM alvo.** Só trabalho de cobertura de ISA no
`arm-jitter` é elegível agora. Ver `tasks/README.md` e a memória do agente
`feedback-100-cobertura-antes-subprojetos`. `1.4.0` fica reservada para 100% — ver `tasks/README.md`
para as regras de release (suspensas até lá).

## Onde estamos (atualizado 2026-08-28, após a rodada de spec B13-B18)

🆕 **Rodada de spec 2026-08-28 (pedido direto do usuário)**: os 7 grupos que
`docs/COBERTURA-ISA.md` marcava "não se aplica a nenhum preset atual" **nunca foram decisão de
escopo** — eram lacuna de infraestrutura, exatamente como o B11 diagnosticou para o A64. O usuário
reafirmou o princípio: *"é uma biblioteca ARM que vai implementar ARM 100% para todos os
processadores, independente de eu fazer um projeto ou não — já está publicada no Maven Central,
qualquer um pode fazer um projeto"*. Foram escritos 6 épicos cobrindo os **2246 encodings**
represados, com escada medida contra o inventário real:

| Épico | O que | Encodings | Bloqueio de infra achado |
|---|---|---:|---|
| [B13](trilha-b-arquiteturas/b13-plano-neon-a32.md) | NEON/AdvSIMD 32 bits (A32+T32) | 325 | `ArmFeature` sem feature SIMD; `VfpRegisters` é `int[32]` (só D0-D15) |
| [B14](trilha-b-arquiteturas/b14-plano-vfp-armv8-32bit.md) | VFP incondicional ARMv8-A 32 bits | 17 | sem preset ARMv8-A de 32 bits (mesmo bloqueio de B12.6/`Cortex-A32`) |
| [B15](trilha-b-arquiteturas/b15-plano-armv8m.md) | ARMv7E-M / ARMv8-M / ARMv8.1-M | 11 (`m-nocp`, a 0%) | sem preset ARMv8-M (mesmo bloqueio de B12.4, 10 Cortex-M fora do catálogo) |
| [B16](trilha-b-arquiteturas/b16-plano-mve-helium.md) | MVE / Helium | 352 | depende de B15 (ARMv8.1-M) + banco Q de B13.1 |
| [B17](trilha-b-arquiteturas/b17-plano-sve.md) | SVE / SVE2 | 929 | `Aarch64Feature` sem NENHUMA constante SVE; sem modelo `Z`/`P`/`FFR` |
| [B18](trilha-b-arquiteturas/b18-plano-sme.md) | SME / SME2 | 623 | `SCALABLE_MATRIX_EXTENSION` é placeholder confesso; sem `ZA`/`SVCR` real |

🆕 **Auditoria de 2026-08-29 (pedido do usuário: "spec de todas as extensões ainda faltantes")**:
os 6 épicos acima cobriam só os grupos `NOT_IN_ANY_PRESET`. A auditoria mediu o que sobrava e
escreveu **mais 4 épicos**, fechando o mapa:

| Épico | O que | Tamanho medido |
|---|---|---|
| [B19](trilha-b-arquiteturas/b19-plano-a64-gap-remanescente.md) | Gap remanescente do **A64** (preset REAL, não `NOT_IN_ANY_PRESET`) | 174 células `❌`/119 mnemônicos, ~18% em todas as 16 versões |
| [B20](trilha-b-arquiteturas/b20-plano-perfil-r.md) | **Perfil R** (PMSA/MPU, ARMv7-R + ARMv8-R) | 0 de decode; falta o modelo de sistema inteiro |
| [B21](trilha-b-arquiteturas/b21-plano-arm-26-bits.md) | **ARMv1-ARMv3**, modelo de 26 bits (`R15`=PC+PSR) | inventário ainda não existe (QEMU não traz) |
| [B22](trilha-b-arquiteturas/b22-plano-residuos-32-bits.md) ✅ **FECHADO 2026-09-02** | **Resíduos** ❌/⚠️ dos presets de 32 bits que já existem | 61 células (B22.1-B22.6 todas ✅; A32/T16/T32/VFP em `0 ❌`/`0 ⚠️`) |

🆕 **2026-08-29 — o usuário desbloqueou `ERET`/`HVC`/`SMC`/`MRS_bank`/`MSR_bank`** ("serão
implementados sim"), revertendo a exclusão registrada em B9.15/B9.16/B9.17: virou a
**[B22.5](trilha-b-arquiteturas/b22.5-eret-hvc-smc-banked.md)** (✅ 2026-08-29), 29 células, e
com ela **nenhuma célula da tabela depende mais de decisão do usuário**.

**Ordem recomendada**: B13 → B14 → B15 → B16 → B17 → B18. **B19 é pegável em paralelo a qualquer
momento** e não depende de nenhum deles — é o maior salto de cobertura global disponível hoje.
(~~B22.2 (`VMOV_half`) é a única violação de G8 viva~~ — **fechada em 2026-08-29**; o projeto está
com **zero `⚠️`**. **O épico B22 inteiro fechou em 2026-09-02 pela B22.6** — A32/T16/T32/VFP com
`0 ❌`/`0 ⚠️`; NÃO descongela subprojetos.) B20 e B21 são épicos de modelo, não de decode, e o usuário decide quando abrir. B13 e B14 compartilham pipeline e dão o
maior retorno prático (binário ARMv7-A/ARMv8-A real emite NEON o tempo todo); B15 destrava
pendências que JÁ estavam registradas (B12.4/B12.6); B17/B18 são os maiores e ficam por último.

**B13.1 ✅ (2026-08-28)** — banco D0-D31/Q0-Q15 pronto. **B13.2 (RFC) ✅ (2026-08-29)**: venceu a
EXTRAÇÃO de um núcleo vetorial compartilhado, no nível da palavra de 64 bits — ler
`docs/RFC-NEON-NUCLEO-VETORIAL.md` INTEIRA antes de pegar qualquer task de B13.3 em diante (ela
muda como cada família é implementada: migrar a operação para `advsimd/AdvSimdLanes` em vez de
reescrever a semântica, e decodificar entregando o `IrOp` pronto via `DecodedInstruction#liftedOp`).

**B13.3 ✅ (2026-08-29)** — NEON load/store A32 fechada (`NeonLoadStoreDecoder` + `IrNeonExecutor`,
as 5 linhas de `neon-ls.decode`). **B22.5 ✅ (2026-08-29)** — `ERET`/`HVC`/`SMC`/`MRS_bank`/`MSR_bank`
(as 29 células que estavam `❌` "por decisão do usuário" foram implementadas).

**B13.4 ✅ (2026-08-29)** — NEON 3-reg-same INTEIRO A32 (aritmética/comparação/lógica/pairwise, ~34
ops + pairwise). `AdvSimdThreeSameOp` de 2→34; as 34 ops inteiras não saturantes MIGRARAM do
`switch` do executor A64 para `AdvSimdLanes` (D1 da RFC — A64 ficou só com as 16 saturantes/shift +
`default -> throw`); `AdvSimdPairwiseOp`/`IrOp.NeonPairwise` novos; `NeonDataProcessingDecoder`
cresceu do protótipo B13.2 para o frame inteiro (`(raw & 0xFE80_0000) == 0xF200_0000`, G8 fecha o
resto). Zero-diff: `COBERTURA-ISA.md` byte-idêntica (grupo segue `NOT_IN_ANY_PRESET` até B13.22),
suíte A64 sem alteração, G5 verde.

**B13.5 ✅ (2026-08-29)** — 3-reg-same saturante / deslocamento por registrador A32 (`VQADD`/`VQSUB`/
`VSHL`/`VQSHL`/`VRSHL`/`VQRSHL`/`VQDMULH`/`VQRDMULH`/`VQRDMLAH`/`VQRDMLSH`). `AdvSimdThreeSameOp`
34→50; as 16 saturantes/shift MIGRARAM do `switch` A64 para `AdvSimdLanes` (helpers de saturação
copiados verbatim; os 4 de deslocamento por registrador movidos) — `executeThreeSame` A64 ficou só
delegando, `sharedThreeSameOp` mapeia as 50. `ArmFeature.ADVANCED_SIMD_RDM` novo (nenhum preset;
`VQRDMLAH`/`VQRDMLSH` sem ela → `unimplemented`). Decoder: troca `vn`↔`vm` nas 4 famílias
`@3same_rev`, `esz∈{1,2}` nas 4 doubling, `size==3` liberado p/ `VQADD`/`VQSUB`/shifts. **`FPSCR.QC`
NÃO modelado** (paridade com o A64 — task futura própria nos dois lados). Zero-diff: `COBERTURA-ISA.md`
byte-idêntica (`NOT_IN_ANY_PRESET` até B13.22), suíte A64 (84 + `Aarch64AdvSimd*DecoderTest`) sem
alteração, G5 verde nos 5.

**B13.6 ✅ (2026-08-29, `b13.6-neon-3reg-same-fp.md`)** — 3-reg-same **ponto flutuante** A32
(F32): `VFMA`/`VFMS` (fundido) · `VADD`/`VSUB`/`VABD`/`VMUL` · `VMLA`/`VMLS` (NÃO fundido) · `VCEQ`/
`VCGE`/`VCGT`/`VACGE`/`VACGT` · `VMAX`/`VMIN`/`VMAXNM`/`VMINNM`/`VRECPS`/`VRSQRTS` · pairwise `VPADD`/
`VPMAX`/`VPMIN` (22 linhas). **3 decisões já resolvidas na spec**: (1) formas F16 (`sz=1`) →
`UNIMPLEMENTED`, task futura irmã da B19.5 (paridade com A64: núcleo não tem caminho F16); (2) migra
o FP three-same/pairwise do A64 (B8.9) para `AdvSimdLanes` — 1º caminho FP do núcleo, D1 da RFC; (3)
`VMLA_fp`/`VMLS_fp` NEON são NÃO fundidos (dois arredondamentos), `VFMA`/`VFMS` fundidos → 4
constantes `MLA`/`MLS`/`FMLA`/`FMLS` no `AdvSimdFpThreeSameOp` novo. `IrOp.NeonFpThreeSame`/
`NeonFpPairwise` (`Kind` 71/72). Fechada na rodada de execução; **o próximo degrau da escada B13 é a
B13.7**, com spec escrita (ver a rodada de spec no fim deste arquivo).

**B19.1 ✅ (2026-08-29)** — A64 atômicos `FEAT_LSE` (`LDADD`…`SWP`) + `LDAPR` (`FEAT_LRCPC`, feature
nova em `ARMV8_3_A`) fechada (`Ir64AtomicOp` + `Ir64Op.AtomicMemoryOp` Kind 93, `decodeAtomicMemoryOp`,
`executeAtomicMemoryOp` interpretado; `LDAPR` reaproveita `Load64`). Global 83%→84%, A64
`ARMv8.1-A` 82%→83%. Zero-diff nos 5 consumidores.

**B19.2 ✅ (2026-08-29)** — as 14 linhas `_sd` (AdvSIMD FP escalar "three same" `FMULX_s`/…/
`FRSQRTS_s` + pairwise escalar `FADDP_s`/…/`FMINNMP_s`) decodificam e executam, golden devkitA64.
`boolean scalar` nos 2 records; `fpThreeSameOpHasScalarForm` restringe o three-same escalar a 9 ops
(G8); `decodeVectorFpScalarPairwiseOpcode` nova; executor `combinePair` extraído. `_h` recusada pela
ESTRUTURA (regressão negativa), **não** na TSV — **B19.5 herda as 14 `_h`**. A64 v8.0 82%→84%,
global 84%→86%; marco de release segue suspenso. G5 verde nos 5 consumidores. Ver `## Resultado`.

**B19.3 ✅ (2026-08-29)** — AdvSIMD FP escalar "two-reg-misc" + conversões escalares int↔FP (29
linhas `_sd`/`_s`); `Ir64Op.VectorFpConvertFixedPoint` (Kind 94) novo, `FRECPX`/`FCVTXN` com
arredondamento real. A64 v8.0 84%→87%, global 86%→88%. `_h` → B19.5. **B19.4 ganhou spec na rodada
de 2026-08-29 (11 linhas, remedida); B19.5 virou `[REFINAR]` (84 linhas); B19.6-B19.9 sem spec.**

**B22.1-B22.6 ✅ — ÉPICO B22 CONCLUÍDO (2026-09-02)** — `HLT` (B22.1), `VMOV_half` deixa de
decodificar como coprocessador (B22.2, mata o único `⚠️` do projeto), `BLX_r` no perfil M /
separação `BLX`↔`BLX_IMMEDIATE` (B22.3), curadoria de denominador T16 (B22.4, T16 chega a 100% em
todas as 7 colunas), `ERET`/`HVC`/`SMC`/`MRS_bank`/`MSR_bank` (B22.5, as 29 células desbloqueadas)
e o fechamento (B22.6, 2026-09-02): `./gerar-cobertura-isa.sh` byte-idêntica, medição confirma
A32/T16/T32/VFP com `0 ❌`/`0 ⚠️` e `⚠️` zero no projeto inteiro; `docs/VALIDACAO-ARQUITETURAS.md`
redatada. **Colunas `v6-M`/`v7-M` param em 88%/96% por `m-nocp` (B15), A64 tem 2020 `❌` (B19) —
fechar B22 NÃO descongela subprojetos.** Achado colateral de B22.3, candidato a task própria: `ARMV6M_FEATURES`
não declara `EXTEND_ROTATE` (`SXTB`/`SXTH`/`UXTB`/`UXTH` T16 são base do ARMv6-M, hoje mascaradas por
curadoria TSV sem `grupo`).

## 🆕 Rodada de spec de 2026-08-29 (segunda do dia, pedido do usuário: "nova rodada de specs")

A rodada de EXECUÇÃO anterior fechou B13.5, B13.6, B19.1-B19.3 e B22.1-B22.5, e esvaziou a fila.
Esta rodada mediu o que sobrou e escreveu **3 specs pegáveis**:

| Task | O que | Tamanho medido | Pegável? |
|---|---|---:|---|
| [B13.7](trilha-b-arquiteturas/b13.7-neon-2reg-shift-imediato.md) | NEON 2-reg-and-shift: deslocamento por IMEDIATO (A32) | **56 linhas** | ✅ agora |
| [B19.4](trilha-b-arquiteturas/b19.4-a64-advsimd-fp-vetorial-convert.md) | A64 `FCVTL_v`/`FCVTN_v`/`FCVTXN_v` + conversões vetoriais FP↔ponto fixo | **11 linhas** | ✅ agora |
| [B22.6](trilha-b-arquiteturas/b22.6-fechamento-32-bits.md) | Fechamento do épico B22 (remedir + `VALIDACAO-ARQUITETURAS.md`) | 0 de decode | ✅ **FEITA 2026-09-02** |

**Os 3 são independentes entre si** — qualquer ordem serve, e B22.6 é a mais curta. **B22.6 ✅ 2026-09-02.**

### Os dois achados de medição desta rodada (mudam decisões, não são cosméticos)

1. **A escada do épico B19 estava mal dimensionada.** Refazendo o join da tabela com o `a64.decode`
   **classificando por TEMPLATE de encoding** (`@qrr_h` × `@qrr_sd` …) — o que a medição original não
   fez — as 121 células `❌` restantes do A64 se distribuem assim: **B19.4 = 11** (não ~40: a B8.9 já
   fizera todas as `_sd` vetoriais de two-reg-misc), **B19.5 = 84** (não 13 — é o MAIOR degrau do
   épico, acumulou as `_h` de B8.9/B19.2/B19.3/B19.4), B19.6 = 10, B19.7 = 12, B19.8 = 4. **B19.5
   deixou de ser um degrau e virou `[REFINAR]`** — ✅ **decomposta na rodada de 2026-09-02**
   (`b19.5-plano-fp16.md`, escada B19.5.1-B19.5.6).
2. **O lado de 32 bits já atingiu a meta do épico B22**: A32/T16/T32/VFP com **0 `❌` e 0 `⚠️`**, e
   **zero `⚠️` no projeto inteiro** (eram 2). O que falta nas colunas `v6-M`/`v7-M` (88%/96%) é
   `m-nocp`, território da **B15**. ⚠️ **Isso NÃO descongela os subprojetos** — o congelamento é
   sobre a cobertura TOTAL e o A64 sozinho tem 2020 células `❌`.

## 🆕 Rodada de spec de 2026-09-02 (terceira; nada foi executado entre ela e a anterior)

As 3 specs da rodada anterior seguiam ⬜ intactas, então esta rodada **escreveu o próximo lote** em
vez de remedir. Agora há **5 tasks pegáveis** e 1 plano novo:

| Task | O que | Tamanho medido | Pegável? |
|---|---|---:|---|
| [B13.7](trilha-b-arquiteturas/b13.7-neon-2reg-shift-imediato.md) | NEON 2-reg-and-shift: deslocamento por imediato | 56 linhas | ✅ |
| [B13.8](trilha-b-arquiteturas/b13.8-neon-2reg-shift-narrow-widen-vcvt.md) | NEON estreitamento/alargamento + `VCVT` fixo↔float F32 — **fecha a seção "2-reg-and-shift"** | 34 (+4 F16 adiadas) | ✅ **depois de B13.7** |
| [B19.4](trilha-b-arquiteturas/b19.4-a64-advsimd-fp-vetorial-convert.md) | A64 `FCVTL_v`/`FCVTN_v`/`FCVTXN_v` + conversões vetoriais FP↔ponto fixo | 11 linhas | ✅ |
| [B19.5.1](trilha-b-arquiteturas/b19.5.1-nucleo-meia-precisao.md) | **Fundação binary16** do núcleo vetorial (`esz=1` em `AdvSimdLanes`) | 0 de decode | ✅ **depois de B19.4** |
| ~~[B22.6](trilha-b-arquiteturas/b22.6-fechamento-32-bits.md)~~ | Fechamento do épico B22 | 0 de decode | ✅ **FEITA 2026-09-02 — épico B22 fechado** |

Novo **plano** (não é task): [B19.5](trilha-b-arquiteturas/b19.5-plano-fp16.md) — escada
B19.5.1-B19.5.6 do `FEAT_FP16`.

⚠️ **B13.8 e B19.4 tocam `executeConvertFixedPoint`** — não rodar as duas na mesma sessão nem em
sessões simultâneas no mesmo checkout (regra 6 acima). ~~B22.6 é a mais curta e não colide com nada.~~ (B22.6 já feita.)

### Os três achados desta rodada

1. **`Aarch64Feature.FP16` já é declarada em `ARMV8_2_A` e NENHUM ponto de `core/src/main` a
   consulta** (`grep` devolve só a declaração e o enum). O preset **anuncia hoje uma extensão que o
   decoder recusa inteira** — mesma classe de achado que o B11 fez para o A64 e o B18 registra para
   `SCALABLE_MATRIX_EXTENSION`. B19.5 não é "acrescentar feature", é tornar real uma promessa.
2. **A curadoria de versão A64 é por MNEMÔNICO e `FEAT_FP16` é por LINHA.**
   `IsaCoverageReport.AARCH64_VERSION_REQUIREMENTS` é `Map<String, Aarch64Feature>`; `FADD_v` tem
   `_h` (v8.2) e `_sd` (ISA base). Marcar por nome derrubaria a `_sd`, hoje ✅. É a limitação que a
   **B9.17** resolveu em 32 bits com a coluna `ocorrencia` — precisa do espelho A64. Efeito atual:
   **168 células com denominador errado** (as 84 `_h` contam `❌` em v8.0/v8.1, onde a feature nem
   existe). Virou B19.5.2 e vem ANTES do decode.
3. **`AdvSimdLanes.fpThreeSame`/`fpCombinePair` computam `esz` `0`/`1` SILENCIOSAMENTE como
   binary64** — são `if (esz == 2) { … } else { <double> }`. Nenhum chamador passa esses valores
   hoje, mas é corrupção silenciosa esperando o primeiro (que seria justamente o FP16). A B19.5.1
   fecha com dispatch explícito + `default -> throw`.

## 🗺️ Rodada de 2026-09-02 (segunda): o MAPA para 100% — [`ROADMAP-100-ARM.md`](ROADMAP-100-ARM.md)

Pedido do usuário: *"faça as specs de tudo que ainda falta para chegarmos a 100% da arquitetura ARM
implementada e todos os processadores terem todas as features que devem ter, ainda temos um grande
trabalho depois para fazer o jit de tudo e o truffle realmente funcionar"*.

A medição mostrou que **o mapa estava incompleto de um jeito que não aparecia**: os épicos B13-B22
cobrem DECODE, e havia consenso implícito de que "falta só isso". São **4 dimensões**, e duas não
tinham épico nenhum:

| # | Dimensão | Media-se? | Estado | Épico |
|---|---|---|---|---|
| 1 | Decode + interpretado | ✅ `COBERTURA-ISA.md` | **88%** | B13-B22 |
| 2 | **Emissão JIT nativa** | ❌ **não** | ASM32 **46/73** · ASM64 **24/95** | **[C12](trilha-c-perf/c12-plano-jit-nativo.md)** 🆕 |
| 3 | **Truffle** | ❌ **não** | **40/73** e **QUEBRA** · A64 **0** | **[A10](trilha-a-truffle/a10-plano-truffle-completo.md)** 🆕 |
| 4 | Catálogo de processadores | parcial | downstream de 1 | B12 (+ task de fechamento futura) |

**Um `✅` em `COBERTURA-ISA.md` não significa que algum backend compile a instrução.**

### Os dois achados que mudam a prioridade

1. **O backend Truffle não degrada — ele QUEBRA.** `TruffleCodeEmitter#supports` é literalmente
   `return true;` e `IrOpNodeFactory` lança `IllegalStateException` em 33 dos 73 `Kind`. Qualquer
   binário ARM com uma instrução de **ponto flutuante** mata o backend. Nunca apareceu porque a
   superfície quebrada nunca foi exercitada (gbaemu=`ARMV4T`, ndsemu=`ARMV5TE`, sem VFP; armbox não
   usa Truffle por padrão). O conserto — **[A10.1](trilha-a-truffle/a10.1-truffle-supports-honesto.md)** —
   é **pequeno**: o campo `fallback`, o contador e o `if (!supports(block))` já existem e já estão
   fiados, só nunca são `false`.
2. **O Javadoc de `Ir64NativePolicy` afirma cobertura nativa exaustiva** — era verdade na B6.5.4
   (24 Kinds). Hoje são 95, e os 71 que faltam incluem **toda a AdvSIMD do AArch64**. Como a política
   é `WHOLE_BLOCK`, **uma** op não suportada derruba o **bloco inteiro** para o interpretador. Quem
   ler o Javadoc conclui que não há trabalho ali — **[C12.1](trilha-c-perf/c12.1-cobertura-jit-medida.md)**
   cria `docs/COBERTURA-JIT.md` por medição e corrige o texto.

### Specs novas desta rodada (2 pegáveis + 2 épicos + o mapa)

| Item | O que | Pegável? |
|---|---|---|
| [`ROADMAP-100-ARM.md`](ROADMAP-100-ARM.md) | O mapa medido das 4 dimensões + ordem recomendada | — (documento) |
| [C12](trilha-c-perf/c12-plano-jit-nativo.md) | ÉPICO emissão JIT nativa, escada C12.1-C12.9 | — (plano) |
| [A10](trilha-a-truffle/a10-plano-truffle-completo.md) | ÉPICO Truffle, escada A10.1-A10.9 | — (plano) |
| **[A10.1](trilha-a-truffle/a10.1-truffle-supports-honesto.md)** | Truffle: crash → fallback | ✅ **urgente e pequena** |
| **[C12.1](trilha-c-perf/c12.1-cobertura-jit-medida.md)** | `docs/COBERTURA-JIT.md` medido + Javadoc corrigido | ✅ |

### ⚠️ O que esta rodada NÃO fez

**Não decompôs os 106 degraus restantes.** As escadas somam **126 degraus** (B13=22, B14=7, B15=7,
B16=14, B17=26, B18=13, B19=9+6, B20=9, B21=8, B22=6) e **20 têm spec**. Escrever as outras 106 exige
medição instrução a instrução contra `target/isa-decode/` — no ritmo estabelecido (≈3 specs por
sessão, cada uma medida contra o oráculo real E contra o código), é trabalho de **dezenas de
sessões**. Escrever spec sem medir é exatamente o erro que a remedição do B19 pegou (6× de
diferença). A ordem recomendada para as próximas rodadas está no `ROADMAP-100-ARM.md`.

**Ainda precisam de spec**: B13.9+, B19.5.2-B19.5.6, B19.6-B19.9, a task irmã "NEON FP16 AArch32",
C12.2-C12.9, A10.2-A10.9, a task de fechamento do catálogo de processadores, e os épicos em
`📋 plano` — B14, B15, B16, B17, B18, B20, B21.

**Sonnet executa; 1 sessão = 1 task.**

Achado aberto da RFC B13.2 que vale como task própria a qualquer momento: o backend **Truffle quebra
com QUALQUER op de VFP** (`IrOpNodeFactory` não tem casos VFP e `TruffleCodeEmitter#supports` devolve
`true` sempre) — pré-existente, verificado, e bloqueia NEON no Truffle assim que algum preset
declarar `ADVANCED_SIMD`.



`B9.17` (`trilha-b-arquiteturas/b9.17-vfp-scalar-mov-byte-halfword-ocorrencia.md`) fechou o
candidato "VFP condicional em MPCore/v7-A" citado abaixo: as 4 células `❌` eram `VMOV_to_gp`/
`VMOV_from_gp` byte/halfword — NEON de verdade (fora de escopo, decisão já tomada pela B9.5), mas
a ferramenta não conseguia excluir só essas 2 das 3 linhas de cada mnemônico porque todas moram no
MESMO arquivo `vfp.decode` (a coluna `grupo` da B9.15 só resolve duplicação ENTRE arquivos
diferentes). `IsaCoverageReport` ganhou uma coluna `ocorrencia` opcional (5ª coluna TSV, requer
`grupo`) que casa pela posição 1-based da linha entre as de mesmo nome+arquivo. VFP MPCore
90%→98%, v7-A 92%→98% — a célula que sobra em cada coluna (`VMOV_half`, `⚠️`) é achado
pré-existente não relacionado. Zero mudança em `core/src/main`, G5 verde.



`B9.16` (`trilha-b-arquiteturas/b9.16-t32-v7m-dsp-gate.md`) fechou a maior lacuna absoluta de 32
bits (T32 `v7-M`, era 52%): não era curadoria, era gate real — `ARMV7M_FEATURES` nunca ganhou os 8
`ArmFeature`s da extensão DSP + base (`CLZ`/`LDRD_STRD`/`PRELOAD_HINTS`/`PACK_SATURATE`/
`PARALLEL_SIMD`/`SIGNED_MULTIPLY_MEDIA`/`DSP_MULTIPLY`/`UMAAL`) desde que o preset foi criado (B7.4,
2026-07-23), embora os decoders Thumb-2 já tivessem decode+IR+executor completos havia sessões
(B1.3/B1.4/B2.7/B3.1/B3.2/B9.1/B9.7) — zero decode novo, só gating + curadoria TSV dos 14
mnemônicos genuinamente N/A a M-profile. T32 v7-M 52%→94%, global 82%→83%. `ERET`/`HVC`/`SMC`/
`MRS_bank`/`MSR_bank` continuam `❌` por decisão do usuário (mesmos 5 de sempre, não excluídos).

**Próxima sessão de cobertura de ISA**: candidatos abertos que restam medidos — A64 com ~18-19% de
gap achatado em todas as versões (ARMv8.0-A 82%, 796/970); v6-M T32 ainda em 72% (8/11, só
`ERET`/`MRS_bank`/`MSR_bank`, mesmos 3 de sempre — não é mais candidato de verdade, só sobra a
mesma pendência intencional); VFP condicional em `MPCore`/`v7-A` também não é mais candidato — só
sobra `VMOV_half` (`⚠️`, achado pré-existente não relacionado, ver B9.17). Nenhum dos restantes
foi triado ainda.

`B9.15` (`trilha-b-arquiteturas/b9.15-t32-v6m-curadoria-nome-por-grupo.md`) achou e resolveu a
causa raiz de por que `T32 v6-M` ainda estava em 9% (8/82) mesmo depois da B9.10: não era falta de
triagem, era uma LIMITAÇÃO DA FERRAMENTA — `isa-nao-aplicavel.tsv` casava exclusão só por nome do
mnemônico, e ~48 nomes existem em `t16.decode` E `t32.decode` como encodings distintos (`REV`/
`NOP`/`UDF`/`B_cond_thumb`/`ADD_rri`/...), então excluí-los apagaria cobertura real de 16 bits —
a B9.10 tinha deixado esses de fora deliberadamente por esse motivo, documentado no próprio tsv.
`IsaCoverageReport` ganhou uma coluna `grupo` opcional (4ª coluna TSV, retrocompatível) que escopa
a exclusão a um `.decode` específico; as 48 linhas entraram escopadas a `t32.decode`. T32 v6-M
9%→72% (só `ERET`/`MRS_bank`/`MSR_bank`, gaps reais não relacionados, continuam `❌`); T16 v6-M
inalterado (zero colateral); v6-M global 47%→80%; global 82%→83%. Zero mudança em
`core/src/main` (só ferramenta de teste + docs), G5 verde.

`B9.13` (`trilha-b-arquiteturas/b9.13-mcr-mrc-armv4t.md`) fechou o achado colateral da B9.12: `MCR`/
`MRC` sob `ARMV4T` era gap real (ARMv3+, não curadoria) — `CoprocessorDecoder` só estava anexado a
`ARMV5TE`+. Extraída `CoprocessorRegisterDecoder` (só o espaço simples `MCR`/`MRC`) e anexada a
`ARMV4T`, mantendo `MCRR`/`MRRC` (ARMv5TE) fora dela (G2). v4T 93%→94%, global 82% (+2 células).
Zero mudança de runtime; G5 (gbaemu/ndsemu/armbox) verde.


`B9.12` (`trilha-b-arquiteturas/b9.12-v4t-v5te-curadoria-denominador.md`) fechou: v4T e v5TE eram os
piores números do 32 bits depois de v6-M (64%/70%) pelo mesmo motivo já visto na B9.10 — dezenas de
células `❌` eram instruções ARMv5T/ARMv5TE/ARMv6/ARMv6K/ARMv6T2/ARMv7 genuínas (todas posteriores
ao próprio ARMv4T/ARMv5TE), só faltava a curadoria em `isa-nao-aplicavel.tsv`. Medição: v4T 64%→93%,
v5TE 70%→95%, global 81%→82% (marco de release continua suspenso). Zero mudança de código de
produção — G5 (gbaemu/ndsemu/armbox) verde. Achados colaterais NÃO resolvidos, documentados na task:
`MCR`/`MRC` sob `v4T` (gap real de decode, ARMv3+ deveria decodificar) e os hints T16 (`YIELD`/`WFE`/
`WFI`/`SEV`/`NOP`/`IT`/`CBZ`, que também mostram `❌` em `v6K`/`MPCore` — gap real, não denominador).
Ambos candidatos a task própria.

`B9.10` (`trilha-b-arquiteturas/b9.10-t32-armv6m-triagem.md`) fechou: `v6-M` era a pior cobertura do
projeto (24%) por ter um denominador errado (211 células que a arquitetura real não tem) mais 2
gaps/bugs reais pequenos (`REV`-family faltando, `B.W`/`TBB`/`TBH` decodificando indevidamente) —
denominador correto ajustado (383→172).

`B9.11` (`trilha-b-arquiteturas/b9.11-armv6m-thumb2miscdecoder-auditoria.md`) fechou o achado
colateral que a B9.10 tinha deixado pendente: auditoria completa de `Thumb2MiscDecoder` sob
`ARMV6M` achou 10 falsos-positivos (não os 4 estimados originalmente) — hints largos
(`NOP.W`/`YIELD.W`/`WFE.W`/`WFI.W`/`SEV.W`/`ESB`), `CPS.W`, `UDF.W` e o alias de exception-return
`SUBS PC,LR,#imm`/`ERET` decodificavam sob `ARMV6M` sem gate nenhum. `ArmFeature.M_PROFILE_WIDE_MISC_CONTROL`
novo (só em `ARMV7M`) fecha os hints/`UDF.W`; o alias `ERET`/`SUB_rri` foi gateado por `M_PROFILE`
puro (não existe em NENHUM perfil M, nem v7-M — 2 células a menos também em v7-M, 61%→60%). Medição
honesta: v6-M 52%→47%, global 81%→81% (sem marco cruzado, regra de release continua suspensa).
`TT` (mencionado pela B9.10 como possível bug) já estava correto, confirmado por medição — a
trilha B (arquiteturas 32-bit A/M-profile) não tem mais nenhum achado colateral pendente
registrado. Ver `INDICE.md` da trilha B, linhas B9.10/B9.11.


A escada `B11` (`trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`, torna o A64 componível
por versão/feature) está com B11.1-B11.12 ✅ — **completa, as 9 features de B11.5 todas gateadas** —
ver `INDICE.md` da trilha B, linha B11, para o resumo de cada uma. B11.12 revelou que a premissa
"`SHA3` só precisa de gate" estava errada (`EOR3`/`BCAX`/`RAX1`/`XAR` nunca tinham decoder/executor —
implementados do zero nesta sessão; ver `b11.12-aarch64-feature-sha3.md`).

A escada `B12` (`trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`, catálogo de processadores
ARM nomeados) está com B12.1-B12.6 ✅ — **todos os núcleos catalogáveis sem decode novo estão
cobertos** (B12.5 fechou os 3 últimos aditivos possíveis: `ARMV6`/`ARMV6T2`/`ARMV6Z` puros). Restam
só pendências que exigem trabalho fora do escopo de catalogação pura de B12 (ver `INDICE.md` da
trilha B, linha B12, para o detalhe): `Cortex-A32` (precisa `LDA`/`STL`/`CRC32` novos, task de
decode própria), perfil R inteiro (nunca modelado, épico próprio) e `ARMv1`/`ARMv2`/`ARMv2a`/`ARMv3`
(modelo de registrador/exceção pré-ARMv3 diferente do resto do projeto, épico próprio). **Nenhuma
candidata de catálogo elegível agora sem abrir um desses épicos maiores primeiro** — usuário
prioriza qual abrir.
- Lacunas A64 pequenas remanescentes: já fechadas por B8.20 em 2026-08-28 — conferir `INDICE.md` da
  trilha B antes de supor que ainda há alguma pendente aqui.
- `B4.0.5` (armbox fork/pipes) — **bloqueada pelo congelamento acima**, não pegar até 100% de ISA.

## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) — **também bloqueada pelo congelamento de subprojetos** | **C9** |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real — **também bloqueada pelo congelamento** | fecha C10 |
| ~~B6.6.6~~ **EM ESPERA** — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Toolchain resolvido (WSL2+Ubuntu); falta kernel arm64 mainline real + o gap `LDR`/`STR` SIMD&FP reg-imediato do B6.2 se o initramfs precisar dele | fecha o épico B6.6 |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2) | Gap real de decode A64 (`LDR`/`STR` SIMD&FP reg-imediato) — verificar se já foi fechado por B8.13/B8.20 antes de reabrir | fecha B6.2 |

## Fila de BUGS de compat (trilha D) — sessões separadas, **bloqueadas pelo congelamento de subprojetos** até 100% de ISA

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D6** — BIOS lenta/interrompida (gbaemu) | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| (sem task) Platinum billboard do char invisível — divergência de alocação de VRAM de textura | ndsemu | ⚠️ MODELO FORTE |
| **PROJETO WiFi** (multi-sessão) — Fase 1 shipped, falta Fase 2+ (handshake WM ARM9↔ARM7) | ndsemu | ⚠️ MODELO FORTE |
| Platinum não boota em INTERPRETED — race de boot cross-CPU | ndsemu | ⚠️ MODELO FORTE |
| Divergência ASM×interp no JUS | ver pendência 6 do `tasks/README.md` | ⚠️ MODELO FORTE |
