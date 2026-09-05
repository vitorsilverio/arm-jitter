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

## Onde estamos (atualizado 2026-09-05, após C12.4/C12.7 fecharem)

A fila anterior estava **drenada e não dizia isso** (listava 6 tasks já fechadas como pegáveis, e 13
arquivos de task ainda tinham `**Status:** ⬜` no cabeçalho — todos corrigidos). Depois disso, uma
rodada de spec longa escreveu **45 specs**, todas medidas contra `target/isa-decode/` **e** contra o
código.

**Resultado: os quatro épicos que tinham escada medida estão INTEIRAMENTE especificados.**

| Épico | Dimensão | Estado da especificação |
|---|---|---|
| **B19** — gap remanescente do A64 | 1 (decode) | ✅ **completo** — B19.1-B19.4 + B19.5.2 feitas; B19.5.3-B19.5.6 e B19.6-B19.13 com spec |
| **B13** — NEON/AdvSIMD 32 bits | 1 (decode) | ✅ **completo** — B13.1-B13.8 feitas; B13.9-B13.22 com spec |
| **C12** — emissão JIT nativa | 2 | ✅ **completo** — C12.1 feita; C12.2-C12.10 com spec |
| **A10** — Truffle | 3 | ✅ **completo** — A10.1 feita, A10.2 absorvida; A10.3-A10.9 com spec |

O que **não** foi especificado são os 7 épicos ainda em `📋 plano`, que nunca tiveram escada medida —
ver "O que ainda precisa de spec".

### ✅ Pegáveis AGORA

Nenhuma dependência aberta. Lista revalidada em 2026-09-05 contra o `INDICE.md` de cada trilha
(a versão anterior desta tabela listava B13.9/B13.10/C12.2/C12.3/A10.3-A10.5 — **todas já ✅**,
removidas).

| Task | O que | Tamanho |
|---|---|---|
| **[C12.5](trilha-c-perf/c12.5-a64-loadstore-fp-simd-nativo.md)** | Emissão nativa A64: load/store FP/SIMD (4 escalares + 3 estruturadas) | 46/96 → 53/96 |
| **[C12.10](trilha-c-perf/c12.10-a64-sistema-nativo.md)** | Emissão nativa A64: os 8 `Kind` de sistema (`SYSTEM_REGISTER`, `EXCEPTION_RETURN`, `PRIVILEGED_CALL`, ...) | 8 `Kind` |
| **[B13.12](trilha-b-arquiteturas/b13.12-neon-two-reg-misc.md)** | NEON two-reg-misc A32 (`size==0b11`, layout de campos próprio) — abre o 4º frame do épico | ~36 linhas |
| **[B13.17](trilha-b-arquiteturas/b13.17-neon-shared-fcma.md)** | `neon-shared`: `VCMLA`/`VCADD` (`FEAT_FCMA`) — cria o `NeonSharedDecoder` que B13.17-B13.21 compartilham | 4 linhas |
| **[B19.5.3](trilha-b-arquiteturas/b19.5.3-fp16-decode-alcancavel.md)** | `FEAT_FP16`: as 17 linhas já alcançáveis (pairwise escalar, reduções across-lanes, ponto fixo) — 1º gate real de `Aarch64Feature.FP16` | 17 linhas |
| **[B19.6](trilha-b-arquiteturas/b19.6-a64-diversos.md)** | A64 diversos (`SYS`/`SYSL`, `PRFM (literal)`, `PACGA`, `ABS` geral, `DUP` escalar, `FMOV` `Vn.D[1]`, `Vimm` — irmão A64 da B13.9) | 10 linhas |
| **[B19.7](trilha-b-arquiteturas/b19.7-a64-bf16.md)** | A64 `FEAT_BF16` (8 linhas) — `bfloat16` não existe no JDK, conversão é código novo | 8 linhas |
| **[B19.8](trilha-b-arquiteturas/b19.8-a64-feat-lut.md)** | A64 `FEAT_LUT` (`LUTI2`/`LUTI4`, ARMv9.5-A) | 4 linhas |
| **[B19.10](trilha-b-arquiteturas/b19.10-a64-cripto-sha512-sm3-sm4.md)** | A64 cripto SHA-512/SM3/SM4 — mesmo prefixo `0xCE` que a B11.12 abriu pela metade | 13 linhas |
| **[B19.12](trilha-b-arquiteturas/b19.12-a64-i8mm.md)** | A64 `FEAT_I8MM` (`USDOT`/`SUDOT`/`SMMLA`/`UMMLA`/`USMMLA`) | 6 linhas |

**Bloqueadas por dependência aberta** (não pegar ainda): C12.6 (RFC, depende de C12.5), C12.8
(depende de C12.6+B13.22), A10.7 (depende da RFC C12.6), B13.13-B13.16/18-22 (depende de B13.12),
B19.9/11/13 (fechamento/depende de sub-tasks ainda ⬜).

**Ordem sugerida**: qualquer uma das dez acima. **C12.4 FECHADA 2026-09-05** — os 6 `Kind` de FP
escalar restante (`FMADD`/`FCSEL`/`FCCMP`/`FRINT*`/conversão geral/`FMOV` cru) via o MESMO
mecanismo de reconstrução de record que B6.5.4/C12.3 (zero aritmética nova), ASM 64 bits 40→46 de
96, G5 zero-diff nos 5 consumidores. **C12.7 FECHADA 2026-09-05** — os 20 records via `IrOpInterop`
cercado de flush/reload (não helpers dedicados: 5 usam `IrExecutionSupport`, package-private, ver
**Resultado** na task), ASM 32 bits 37→57 de 84, G5 zero-diff nos 5 consumidores. **A10.6 FECHADA
2026-09-04** — Truffle 32 bits 40→42/84 (`DspDualMultiply`/`DspTopWordMultiply`
no `MultiplyOpNode`). **E13 FECHADA 2026-09-04** — 12 células `✅`→`·` em `v6K`/`MPCore`
(não 14, correção de número da spec), G5 verde nos 5 consumidores incl. n3dsemu (ARM11 MPCore, sem
regressão). **E12 FECHADA 2026-09-04** — era a que consertava a MEDIÇÃO, e o denominador agora é
honesto (ver abaixo). **B19.5.2 FECHADA 2026-09-04**, **E11 FECHADA 2026-09-03**.

### ⚠️ A E12 mudou o denominador: números anteriores a 2026-09-04 estão obsoletos

A E12 tirou de `docs/isa-nao-aplicavel.tsv` 111 linhas que escondiam **134 linhas da tabela A64** nas
16 colunas de versão, e reescopou 2 linhas `*` que escondiam mais 9. O denominador global cresceu
**1240 células** e o **global caiu de 89% para 84%** — revelação de trabalho, não regressão
(precedentes B9.11 e B19.5.2). Duas consequências para quem for planejar:

1. **`⚠️` voltou a existir na tabela** (66 células): ao parar de esconder, descobriu-se que 10
   mnemônicos que mediriam `✅` na verdade **misdecodificam** (G8) — `CPY*`/`SETG*` viram
   `FpLoadLiteral64`, `LDRA` vira `NOP_HINT`, `FAMAX`/`FAMIN`/`FSCALE` viram `VectorInsert*`. Isso
   virou **task nova** (ver "O que ainda precisa de spec").
2. **`SEVL` ganhou 16 células `✅`** que a TSV apagava — a tabela também estava SUB-reportando.

### As 4 dimensões (o mapa: [`ROADMAP-100-ARM.md`](ROADMAP-100-ARM.md))

Um `✅` em `docs/COBERTURA-ISA.md` **não** significa que algum backend compile a instrução.

| # | Dimensão | Onde se mede | Estado |
|---|---|---|---|
| 1 | Decode + interpretado | `docs/COBERTURA-ISA.md` | **84%** (pós-E12) · A64 `ARMv8.0-A` 97% (851/877) · `ARMv9.5-A` 77% (891/1146) |
| 2 | Emissão JIT nativa | `docs/COBERTURA-JIT.md` | ASM 32 **57 ✅ + 9 ⚠️ / 84** (pós-C12.7) · ASM 64 **46/96** (pós-C12.4) |
| 3 | Truffle | `docs/COBERTURA-JIT.md` | 32 bits **66/84** (pós-A10.5) · 64 bits **0/96** (não existe) |
| 4 | Catálogo de processadores | — | downstream de 1 |

### Os achados desta rodada que mudam decisões

1. ~~**A tabela de cobertura mede um alvo MÓVEL.**~~ **RESOLVIDO pela E11 (2026-09-03).**
   `gerar-cobertura-isa.sh` agora fixa `QEMU_REV` num SHA (`2931a675e9d3…`), invalida o cache por
   `target/isa-decode/.rev`, e a revisão aparece no cabeçalho de `docs/COBERTURA-ISA.md`. Contra a
   revisão FIXADA o único delta é `t16` 86→87 (`MAYBE_UNDEF_T1_HINT`); `sve`/`sme` ficam 929/623 (o
   +18/+28 que a rodada de spec viu era de commits POSTERIORES ao SHA — B17/B18 seguem corretos).
   `MAYBE_UNDEF_T1_HINT` curado para v4T/v5TE ⇒ **v4T/v5TE seguem 100%** e T16 segue com **0 `❌`**;
   a manchete da B22.6 continua verdadeira, agora ancorada na revisão. O gap de *gating* que o
   commit expôs (espaço de hint T1 INTEIRO é v6T2+ em perfil A — reverte a B9.14 para v6K/MPCore)
   virou a task **E13**.
2. ~~**A curadoria `A64` da TSV esconde trabalho onde a feature EXISTE.**~~ **RESOLVIDO pela E12
   (2026-09-04)** — e a spec da E12 estava **errada em 3 pontos**, corrigidos por re-medição antes de
   executar: eram **111** linhas `A64` (não 123) atingindo **134** da tabela; **8** features sem
   constante (não 2); e havia um **segundo mecanismo** de esconderijo que a spec não citava (linhas
   com arquitetura `*`, que apagavam até 16 células `✅` REAIS de `SEVL`). `isAarch64VersionColumn`
   foi removido e `IsaCoverageReportA64CurationGuardTest` fecha as duas portas. **Lição registrada**:
   a spec v1 mediu por amostragem do TEXTO das justificativas em vez de contra a tabela — é o mesmo
   erro que o rodapé desta seção já alertava ("escrever spec sem medir"), e a sessão de execução
   acertou ao PARAR e reportar em vez de forçar os números.
3. **A escada do B19 tinha 4 grupos sem dono**, achados ao classificar as 116 linhas `❌`: cripto
   SHA-512/SM3/SM4 (13, no MESMO prefixo `0xCE` que a B11.12 abriu e deixou pela metade), `FEAT_FP8`
   (12, a única sem constante em `Aarch64Feature`), `FEAT_I8MM` (6) e `FEAT_FHM` (8, feature própria
   e **não** `FEAT_FP16`). ⇒ B19.10-B19.13.
4. **A escada do C12 tinha 8 `Kind` de sistema sem dono** — a conta 16+6+7+35+**8**=72 só fecha com
   eles. ⇒ **C12.10**.
5. **`VUZP`/`VTRN`/`VZIP` do A32 escrevem DOIS registradores** e não são as `UZP1`/`UZP2`/`TRN1`/…
   do A64 (seis instruções de UM destino). Mapear 1:1 pelo nome produziria metade do resultado.
6. **T32 é transformação mecânica do A32** (`1111_001p_q…` ↔ `111p_1111_q…`, 24 bits baixos
   idênticos) ⇒ B13.16 é um adaptador que delega. E **`neon-shared` dispensa a B13.16**: seu
   encoding já é o mesmo para os dois.
7. **`AdvSIMDExpandImm` não existe no projeto e falta nos DOIS lados** (`Vimm_1r` A32 / `Vimm` A64)
   ⇒ a spec da B13.9 põe o algoritmo no núcleo desde o início, e a B19.6 o reusa.
8. **A pergunta do SIMD nos backends é UMA só** (emitir lane a lane × chamar `AdvSimdLanes` × não
   fazer): **C12.6 é a RFC, A10.7 aplica**. Idem C12.9 ↔ A10.9, que remedem o mesmo arquivo.

### Correção de número importante (B19.5.2)

A primeira versão daquela spec dizia "+192 células". **Errado**: 12 das 96 linhas a curar já são `·`
— mas pela curadoria grossa da TSV. O efeito real são **168 `❌→·`** (v8.0/v8.1) **e 168 `·→❌`**
(v8.2..v9.5, trabalho revelado). **O global fica inalterado em 89%**: a task não infla o número,
torna-o honesto. v8.0/v8.1 88%→97%, v8.2+ 88%→**87%**.

### O que fechou recentemente (detalhe no `INDICE.md` de cada trilha, nunca aqui)

`B13.7` · `B13.8` · `B13.9` · `B13.10` · `B13.11` · `B19.4` · `B19.5.1` · `B19.5.2` · `E10` · `E11`
· `E12` · `E13` · `A10.1` · `A10.3` · `A10.4` · `A10.5` · `A10.6` · `C12.1` · `C12.2` · `C12.3` ·
`C12.4` · `C12.7` · **épico `B22` inteiro**.

### O que AINDA precisa de spec

Os **7 épicos em `📋 plano`**, que nunca tiveram escada medida — **~84 degraus**:

| Épico | O que | Degraus |
|---|---|---:|
| [B14](trilha-b-arquiteturas/b14-plano-vfp-armv8-32bit.md) | VFP incondicional ARMv8-A de 32 bits | 7 |
| [B15](trilha-b-arquiteturas/b15-plano-armv8m.md) | ARMv7E-M / ARMv8-M / ARMv8.1-M | 7 |
| [B16](trilha-b-arquiteturas/b16-plano-mve-helium.md) | MVE / Helium | 14 |
| [B17](trilha-b-arquiteturas/b17-plano-sve.md) | SVE / SVE2 | 26 |
| [B18](trilha-b-arquiteturas/b18-plano-sme.md) | SME / SME2 | 13 |
| [B20](trilha-b-arquiteturas/b20-plano-perfil-r.md) | Perfil R (PMSA/MPU) | 9 |
| [B21](trilha-b-arquiteturas/b21-plano-arm-26-bits.md) | ARMv1-ARMv3, modelo de 26 bits | 8 |

Mais: a task irmã **"NEON FP16 AArch32"** (registrada por B13.6/B13.8/B13.11/B13.13), os **9 `⚠️`
condicionais** de 32 bits (registrados pela C12.7), o **cache de registradores do `Ir64BlockCompiler`**
(dívida da B6.4) e a task de fechamento do catálogo de processadores.

**Duas tasks NOVAS que a E12 mediu e não executou** (ela era zero-decode):

1. **Misdecode A64 / dívida G8** — 10 linhas, 66 células `⚠️`, repro determinístico em
   `IsaCoverageReport.AARCH64_MISDECODED`: `CPYP`/`CPYM`/`CPYE`/`SETGP`/`SETGM`/`SETGE` decodificam
   como `FpLoadLiteral64` (a **mesma classe de bug que a B11.3 corrigiu** para o `LDR (literal)`
   INTEIRO — sobrou o caminho de ponto flutuante), `LDRA` cai no catch-all de hint-space, e
   `FAMAX`/`FAMIN`/`FSCALE` colidem com o espaço de `INS`/`MOV` vetorial.
2. **Coluna `ARMv9.6-A`** — `FEAT_FPRCVT`, `FEAT_F8F16MM` e `FEAT_F8F32MM` já existem como constante
   (14 linhas do inventário) e nenhum preset as declara. Criar a coluna exige auditar TODAS as
   features contra a v9.6 e muda o denominador global.

**Os grupos que a E12 revelou** e seguem sem degrau no B19: `FEAT_MTE2` (26 linhas), `FEAT_PAuth`
(10), `FEAT_MOPS` (9), `FEAT_CRC32` (8, novo), `FEAT_FRINTTS` (8), `FEAT_LRCPC2` (7), família FP8 (7),
`FEAT_FCMA` (6), `FEAT_I8MM` (6), `FEAT_BF16` (5), `FEAT_CSSC` (5), `FEAT_CMPBR` (5), `FEAT_DotProd`
(4), `FEAT_LSE128` (3).

**Escrever spec sem medir é o erro que esta rodada pegou três vezes** (o inventário FP16 errava por 4
linhas e misturava 5 features; a escada do B19 tinha 4 grupos sem dono; a do C12, 8 `Kind`). Cada
spec custa medição instrução a instrução contra o oráculo **e** contra o código — os 7 épicos acima
são trabalho de várias sessões, e **B17/B18 sozinhos somam 39 degraus sobre inventários de 947 e 651
linhas**.

**Sonnet executa; 1 sessão = 1 task.**

### Tasks que EXISTEM mas não são pegáveis (para não serem redescobertas a cada sessão)

- **`B4.0.5`** (armbox fase 3: fork/execve/pipes) — tem spec, está ⬜, e é **bloqueada pelo
  congelamento de subprojetos** acima. Não pegar até 100% de cobertura de ISA.
- **`B6.5.1`** (banco FP escalar A64) — consta ⬜ no `INDICE.md` da trilha B, mas é **resíduo de
  bookkeeping**: o entregável existe (`core64/Aarch64FpRegisters.java`, depois alargado pela B8.6) e
  B6.5.2-B6.5.4 fecharam em cima dele. Não pegar.

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
