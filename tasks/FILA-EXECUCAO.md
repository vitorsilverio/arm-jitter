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

## Onde estamos (reescrito 2026-09-03, após a rodada "spec de tudo")

A fila anterior estava **drenada e não dizia isso** (listava 6 tasks já fechadas como pegáveis, e 13
arquivos de task ainda tinham `**Status:** ⬜` no cabeçalho — todos corrigidos). Depois disso, uma
rodada de spec longa escreveu **45 specs**, todas medidas contra `target/isa-decode/` **e** contra o
código.

**Resultado: os quatro épicos que tinham escada medida estão INTEIRAMENTE especificados.**

| Épico | Dimensão | Estado da especificação |
|---|---|---|
| **B19** — gap remanescente do A64 | 1 (decode) | ✅ **completo** — B19.1-B19.4 feitas; B19.5.2-B19.5.6 e B19.6-B19.13 com spec |
| **B13** — NEON/AdvSIMD 32 bits | 1 (decode) | ✅ **completo** — B13.1-B13.8 feitas; B13.9-B13.22 com spec |
| **C12** — emissão JIT nativa | 2 | ✅ **completo** — C12.1 feita; C12.2-C12.10 com spec |
| **A10** — Truffle | 3 | ✅ **completo** — A10.1 feita, A10.2 absorvida; A10.3-A10.9 com spec |

O que **não** foi especificado são os 7 épicos ainda em `📋 plano`, que nunca tiveram escada medida —
ver "O que ainda precisa de spec".

### ✅ Pegáveis AGORA

Nenhuma dependência aberta.

| Task | O que | Tamanho |
|---|---|---|
| **[E11](trilha-e-manutencao/e11-inventario-qemu-fixado.md)** | Fixar a revisão do inventário do QEMU | 0 de decode |
| **[E12](trilha-e-manutencao/e12-tsv-a64-esconde-trabalho.md)** | A curadoria `A64` da TSV esconde trabalho onde a feature EXISTE (102 de 123 linhas) | 0 de decode |
| **[B19.5.2](trilha-b-arquiteturas/b19.5.2-curadoria-versao-por-ocorrencia-a64.md)** | Requisito de versão A64 por **ocorrência** + curadoria de 96 linhas | 0 de decode |
| **[B13.9](trilha-b-arquiteturas/b13.9-neon-1reg-imediato-modificado.md)** | NEON `VMOV`/`VMVN`/`VORR`/`VBIC` imediato (A32) | 1 linha, 4 famílias |
| **[B13.10](trilha-b-arquiteturas/b13.10-neon-3reg-different-lengths.md)** | NEON 3-reg-different-lengths — abre o 3º frame do épico | 26 linhas |
| **[C12.2](trilha-c-perf/c12.2-per-op-64-bits.md)** | Política `PER_OP` no pipeline de 64 bits | 0 de decode |
| **[C12.3](trilha-c-perf/c12.3-a64-inteiro-nativo.md)** | Emissão nativa A64 do inteiro (16 `Kind`) | 24/96 → 40/96 |
| **[C12.7](trilha-c-perf/c12.7-32bits-records-restantes.md)** | Emissão nativa 32 bits, 20 records (⚠️ pipeline em produção) | 37 → 57 de 77 |
| **[A10.3](trilha-a-truffle/a10.3-nos-vfp.md)** · **[A10.4](trilha-a-truffle/a10.4-nos-bitfield-divide.md)** · **[A10.5](trilha-a-truffle/a10.5-nos-sistema.md)** · **[A10.6](trilha-a-truffle/a10.6-nos-dsp.md)** | Nós Truffle: VFP (14), bitfield/divide (4), sistema (6), DSP (2) | 40/77 → 66/77 |

**Ordem sugerida**: **E11 → E12 → B19.5.2** primeiro — as três consertam a MEDIÇÃO, e qualquer task
de cobertura executada antes delas mede contra um denominador errado. Depois, qualquer uma.

⚠️ **E11, E12 e B19.5.2 tocam as três `docs/COBERTURA-ISA.md`** — não rodar na mesma sessão nem em
sessões simultâneas no mesmo checkout (regra 6).

### As 4 dimensões (o mapa: [`ROADMAP-100-ARM.md`](ROADMAP-100-ARM.md))

Um `✅` em `docs/COBERTURA-ISA.md` **não** significa que algum backend compile a instrução.

| # | Dimensão | Onde se mede | Estado |
|---|---|---|---|
| 1 | Decode + interpretado | `docs/COBERTURA-ISA.md` | **89%** · A64 `ARMv8.0-A` 88% (850/960) |
| 2 | Emissão JIT nativa | `docs/COBERTURA-JIT.md` | ASM 32 **37 ✅ + 9 ⚠️ / 77** · ASM 64 **24/96** |
| 3 | Truffle | `docs/COBERTURA-JIT.md` | 32 bits **40/77** · 64 bits **0/96** (não existe) |
| 4 | Catálogo de processadores | — | downstream de 1 |

### Os achados desta rodada que mudam decisões

1. **A tabela de cobertura mede um alvo MÓVEL.** `gerar-cobertura-isa.sh` baixa de `qemu/master` e
   **só baixa se o arquivo não existir** — e `target/` é gitignored, então o resultado depende de
   quando aquela máquina baixou. Medido: com download fresco e **sem tocar em `core/`**, `t16` vai
   de 86 para 87 linhas, `sve` 929→947, `sme` 623→651, e **v4T/v5TE caem de 100% para 99%**. Isso
   **invalida a manchete da B22.6** ("A32/T16/T32/VFP com 0 ❌"). ⇒ **E11**, que também investiga um
   possível gap de *gating* real que o inventário novo expôs (o espaço de hint T16 mede `✅` em
   v6K/MPCore, mas é **v6T2+**).
2. **A curadoria `A64` da TSV esconde trabalho onde a feature EXISTE.** `isAarch64VersionColumn` faz
   TODA linha com arquitetura `A64` casar as 16 colunas de versão; **102 das 123** citam uma versão
   ≥ ARMv8.1-A. É por isso que BF16 (6 de 8), I8MM (6 de 6), FHM (8 de 8) e cripto (7 de 13) estão
   invisíveis hoje. Quase todas as features já têm constante. ⇒ **E12**.
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

`B13.7` · `B13.8` · `B19.4` · `B19.5.1` · `E10` · `A10.1` · `C12.1` · **épico `B22` inteiro**.

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

Mais: a task irmã **"NEON FP16 AArch32"** (registrada por B13.6/B13.8/B13.11/B13.13), os grupos que a
**E12** vai revelar (`FEAT_PAuth`, `FEAT_MTE2`, `FEAT_MOPS`, `FEAT_FRINTTS`, `FEAT_CSSC`, …), os
**9 `⚠️` condicionais** de 32 bits (registrados pela C12.7), o **cache de registradores do
`Ir64BlockCompiler`** (dívida da B6.4) e a task de fechamento do catálogo de processadores.

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
