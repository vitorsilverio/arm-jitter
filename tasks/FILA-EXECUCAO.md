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

## Onde estamos (reescrito 2026-09-03, na rodada de spec que esvaziou a fila anterior)

**A fila anterior estava DRENADA e não dizia isso.** Uma sessão de 2026-09-03 abriu este arquivo,
leu "5 tasks pegáveis", foi conferir uma a uma e achou **todas ✅** — o texto listava B13.7, B13.8,
B19.4, B19.5.1, A10.1 e C12.1 como pegáveis depois de elas terem fechado. Pior: o cabeçalho
`**Status:**` dentro de **13 arquivos de task** ainda dizia `⬜` (o status real vive no `INDICE.md`
da trilha, mas ninguém confia num arquivo que se contradiz). **Os 13 cabeçalhos foram corrigidos** e
esta seção passa a ser curta de propósito: a fonte da verdade é o `INDICE.md` de cada trilha, e aqui
fica só o que uma sessão nova precisa para escolher em 2 minutos.

### ✅ Pegáveis AGORA — as 4 têm spec medida e são independentes entre si

| Task | O que | Tamanho | Por que agora |
|---|---|---|---|
| **[B19.5.2](trilha-b-arquiteturas/b19.5.2-curadoria-versao-por-ocorrencia-a64.md)** | Requisito de versão A64 por **ocorrência** + curadoria de 96 linhas de meia precisão | 0 de decode | **Pré-requisito de MEDIÇÃO de B19.5.3-B19.5.6** — enquanto as `_h` contarem `❌` onde `FEAT_FP16` nem existe, o progresso dos degraus seguintes fica ilegível. Maior salto de número da fila: A64 v8.0/v8.1 **88% → 98%**, global **89% → 90%** |
| **[B13.9](trilha-b-arquiteturas/b13.9-neon-1reg-imediato-modificado.md)** | NEON `VMOV`/`VMVN`/`VORR`/`VBIC` **imediato** (A32) | 1 linha de decodetree, 4 famílias | Fecha o buraco que a B13.7 deixou de propósito dentro do frame dela; e entrega o `AdvSIMDExpandImm` compartilhado que a **B19.6** vai precisar |
| **[C12.2](trilha-c-perf/c12.2-per-op-64-bits.md)** | Política `PER_OP` no pipeline de 64 bits | 0 de decode | Único degrau do C12 que dá ganho **sem emitir bytecode novo**: hoje 1 op fora da política derruba o bloco inteiro, com só 24 de 96 `Kind` nativos |
| **[E11](trilha-e-manutencao/e11-inventario-qemu-fixado.md)** | Fixar a revisão do inventário do QEMU | 0 de decode | A tabela de cobertura mede um alvo móvel — ver o achado 1 abaixo. Quanto mais tarde, mais medições ficam suspeitas |

**Ordem sugerida**: E11 primeiro (torna toda medição posterior confiável e reprodutível), depois
B19.5.2 (que depende de medição correta), depois B13.9 e C12.2 em qualquer ordem.
⚠️ **B19.5.2 e E11 tocam as duas a tabela `docs/COBERTURA-ISA.md`** — não rodar na mesma sessão nem
em sessões simultâneas no mesmo checkout (regra 6).

### As 4 dimensões (o mapa: [`ROADMAP-100-ARM.md`](ROADMAP-100-ARM.md))

Um `✅` em `docs/COBERTURA-ISA.md` **não** significa que algum backend compile a instrução.

| # | Dimensão | Onde se mede | Estado |
|---|---|---|---|
| 1 | Decode + interpretado | `docs/COBERTURA-ISA.md` | **89%** · A64 `ARMv8.0-A` 88% (850/960) |
| 2 | Emissão JIT nativa | `docs/COBERTURA-JIT.md` (C12.1 ✅) | ASM 32 **37 ✅ + 9 ⚠️ / 77** · ASM 64 **24/96** |
| 3 | Truffle | `docs/COBERTURA-JIT.md` | 32 bits **40/77** · 64 bits **0/96** (não existe) |
| 4 | Catálogo de processadores | — | downstream de 1 |

### Os 3 achados desta rodada (mudam decisões — não são cosméticos)

1. **A tabela de cobertura mede um alvo MÓVEL, e nada registra qual.** `gerar-cobertura-isa.sh` baixa
   os `.decode` de `qemu/master` e **só baixa se o arquivo não existir** — e `target/` é gitignored.
   Logo o resultado depende de quando aquela máquina baixou pela primeira vez. Medido: com download
   fresco em 2026-09-03, **sem tocar em `core/`**, `t16.decode` vai de 86 para 87 instruções,
   `sve.decode` de 929 para 947, `sme.decode` de 623 para 651, e **v4T/v5TE caem de 100% para 99%**.
   ⚠️ **Isso invalida a manchete da B22.6** ("A32/T16/T32/VFP com `0 ❌`"), repetida abaixo e no plano
   B22 — não é regressão do arm-jitter, é o ARM que cresceu. Vira a **E11**, que também investiga um
   possível gap de gating real que o inventário novo expôs (o espaço de hint T16 mede `✅` em
   v6K/MPCore, mas é **v6T2+**).
2. **`_h` no nome do template ≠ meia precisão** — e o inventário da B19.5 estava errado nos dois
   sentidos. Das 157 linhas de template `_h` em `a64.decode`, **45 já são `✅`** (ali `_h` é
   "elemento halfword INTEIRO": `MUL_vi`, `SMULL_vi`, `SQDMULL_v`…). Das 112 `❌`, só **96** são
   FP16/FHM — o resto é BF16 (3), FP8 (8), `CRC32` (2) e 3 sem constante de feature. E o total de
   FP16 é **88, não 84**: faltavam as 4 reduções across-lanes (`FMAXNMV_h`/`FMINNMV_h`/`FMAXV_h`/
   `FMINV_h`). Uma regra por padrão de nome corromperia a tabela nos dois sentidos — a curadoria
   **tem** que ser enumerada.
3. **`AdvSIMDExpandImm` não existe no projeto, e falta nos DOIS lados.** `Vimm_1r` (A32, B13.9) e
   `Vimm` (A64, atribuída à B19.6) são a mesma função pseudocódigo, ambas `❌`. Quem fizer primeiro
   define se o projeto terá uma implementação ou duas — por isso a spec da B13.9 põe o algoritmo no
   núcleo `advsimd/` desde o início (D1 da RFC B13.2 aplicada **antes** da duplicação, não depois).

**Achado menor, já resolvido**: a **A10.2** ("medir a cobertura Truffle") foi **absorvida pela
C12.1**, que entregou as duas colunas Truffle e o teste de guarda. Auditado: sem resíduo. Registrado
no `INDICE.md` da trilha A; a escada do Truffle segue em **A10.3**.

### O que fechou recentemente (detalhe no `INDICE.md` de cada trilha, nunca aqui)

`B13.7` · `B13.8` (fecham a seção "2-reg-and-shift" do NEON A32, exceto 4 `VCVT` F16) · `B19.4` ·
`B19.5.1` (fundação binary16 do núcleo) · `E10` (aliasing `Rd==Rn` em 6 famílias AdvSIMD) ·
`A10.1` (Truffle: crash → fallback) · `C12.1` (`docs/COBERTURA-JIT.md` medido) · **épico `B22`
inteiro** (B22.1-B22.6).

### O que AINDA precisa de spec (ordem no `ROADMAP-100-ARM.md`)

As escadas somam **126 degraus**; **23 têm spec**. Faltam: B13.10-B13.22, B19.5.3-B19.5.6,
B19.6-B19.9, a task irmã "NEON FP16 AArch32", C12.3-C12.9, A10.3-A10.9, o fechamento do catálogo de
processadores, e os épicos ainda em `📋 plano` — **B14** (VFP incondicional ARMv8-A 32 bits),
**B15** (ARMv8-M), **B16** (MVE/Helium), **B17** (SVE/SVE2), **B18** (SME/SME2), **B20** (perfil R),
**B21** (ARM de 26 bits).

**Escrever spec sem medir é o erro que a remedição do B19 pegou** (a escada original errava por 6×) e
que esta rodada pegou de novo (o inventário FP16 errava por 4 linhas e misturava 5 features). Cada
spec custa medição instrução a instrução contra `target/isa-decode/` **e** contra o código — no ritmo
estabelecido (≈3-4 specs por sessão), o que falta é trabalho de dezenas de sessões.

**Sonnet executa; 1 sessão = 1 task.**

### Tasks que EXISTEM mas não são pegáveis (para não serem redescobertas a cada sessão)

- **`B4.0.5`** (armbox fase 3: fork/execve/pipes) — tem spec, está ⬜, e é **bloqueada pelo
  congelamento de subprojetos** acima. Não pegar até 100% de cobertura de ISA.
- **`B6.5.1`** (banco FP escalar A64) — consta ⬜ no `INDICE.md` da trilha B, mas é **resíduo de
  bookkeeping, não trabalho pendente**: o entregável existe
  (`core64/Aarch64FpRegisters.java`, depois alargado para 128 bits pela B8.6) e B6.5.2-B6.5.4
  fecharam em cima dele. A task nunca ganhou `## Resultado` nem linha `**Status:**`. Não pegar.

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
