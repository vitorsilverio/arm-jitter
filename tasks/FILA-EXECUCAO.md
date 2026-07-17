# Fila de execução (2026-07-15) — para agentes com contexto limitado

**Regras de sessão (obrigatórias, existem para o agente NÃO se perder):**

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a
   próxima task na mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes
   G1-G7) e depois SÓ o arquivo da task + os fontes que ela cita. Não explorar o
   repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao
   usuário — não improvisar.
4. Nunca pegar itens da seção "Pendências que EXIGEM modelo forte" do
   `tasks/README.md`.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25 + gbaemu +
   ndsemu), status atualizado no índice do `tasks/README.md`, 1 commit começando
   com o ID (`B2.6: ...`).
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale
   só entre repos DIFERENTES (arm-jitter ∥ gbaemu ∥ armbox). Duas sessões no
   mesmo working tree misturam WIP uma da outra (já aconteceu em 2026-07-16:
   um `git add -A` de uma sessão de docs varreu os arquivos half-done da A6
   para dentro do commit errado — desfeito, mas é exatamente o acidente que
   esta regra evita). Pelo mesmo motivo: **commits sempre com paths explícitos
   (`git add <arquivos da SUA task>`), nunca `git add -A`.**

**Prompt de kickoff (copiar/colar, trocando o ID):**

> Leia `arm-jitter/tasks/README.md` inteiro. Depois execute APENAS a task
> **<ID>** (arquivo `<caminho>`), seguindo o protocolo do README. Leia os
> arquivos-fonte citados na task antes de escrever código. Implemente somente o
> que está em "Inclui"; respeite as decisões já tomadas (não reavaliar). Ao
> final: suites verdes, índice atualizado, um commit `"<ID>: ..."`.

---

## Onda 1 — executáveis AGORA (sem dependência pendente)

Ordem dentro do mesmo repo é obrigatória; repos diferentes podem andar em paralelo.

| # | Task | Repo | Por quê agora | Nota de sessão |
|---|------|------|---------------|----------------|
| 1 | ~~C11~~ ✅ FECHADA 2026-07-16 (Fix C `JitRuntime.reset()` bastou — estado de superbloco sobrevivia ao `clear()`; gap agora fecha em 20-40s) | — | — | — |
| 2 | ~~B2.6~~ ✅ FECHADA 2026-07-16 (`c1c2ab4`) — **B2.7, B2.8, B3.1 e B7.1 (onda 2) estão DESTRAVADAS agora** | — | — | — |
| 3 | ~~B1.7~~ ✅ FECHADA 2026-07-16 (`8f942b2`) — `ArmFeature.UNALIGNED_ACCESS` em ARMV6K/ARMV6K_THUMB2; suítes arm-jitter 606+13, gbaemu 216, ndsemu 175, armbox 26 verdes | — | — | — |
| 4 | ~~B4.0.4~~ ✅ FECHADA 2026-07-16 (armbox `ArmboxCp15`; 2 gaps reportados viraram B4.0.4.1 e o item MCR/MRC-Thumb do PR3 da B2.7) | — | — | — |
| 4b | ~~B4.0.4.1~~ ✅ FECHADA 2026-07-16 — `CoprocessorBus.handles` fino implementado (arm-jitter + armbox); B4.0.3 segue dependendo só de B2.7/B2.8 agora | — | — | — |
| 5 | ~~C6~~ ✅ FECHADA 2026-07-16 (`64e08c1`) — validação de gameplay A/B confirmou os 3 problemas observados como pré-existentes, não regressão | — | — | — |
| 6 | ~~D1~~ ✅ FECHADA 2026-07-16 — protocolo S-3511A verificado contra o GBATEK real (fetch direto); `S3511aRtc`+`GbaRtcDetector`+GPIO opcional em `GbaRom`; suite gbaemu 231 verde | — | — | — |
| 6b | ~~D2~~ ✅ FECHADA 2026-07-16 — hipótese 1 (HDMA em V-Blank) e hipótese 3 (OBJ window) corrigidas; hipótese 2 (wrap OAM) REFUTADA (era artefato do bug 1). Validação visual do usuário CONFIRMADA (fade, direção do inimigo, overlay de status) | — | — | — |
| 6c | ~~D3~~ ✅ FECHADA 2026-07-16 — sem problema real (usuário confirmou o áudio aceitável após ouvir os WAVs/jogar) | — | — | — |
| 6d | ~~D4~~ ✅ FECHADA 2026-07-16 — sem problema real (usuário confirmou o áudio aceitável após ouvir os WAVs/jogar; hipótese de FIFO/timer do enunciado já tinha sido REFUTADA na fase 1) | — | — | — |
| 7 | ~~A6~~ ✅ FECHADA 2026-07-16 (`a8090d8`) — objetivo central (compilação real no JBR) alcançado; native-image segue com o mesmo bailout de A5, herdado por A7 | — | — | — |
| 8 | ~~B5.1~~ ✅ FECHADA 2026-07-16 (`f390a7a`) — `core/ExclusiveMonitor.java` compartilhável entre cores; reserva por core (`IdentityHashMap`); hook de escrita comum em todos os stores de baixo nível dos 2 backends; 4 testes novos + suites B1.4/gbaemu/ndsemu verdes | — | — |
| 9 | ~~E1~~ ✅ FECHADA 2026-07-16 — javadocs em inglês → português (regra G7), 4/4 lotes concluídos (Lote 4: `truffle/` já estava 100% português, zero diff; varredura final do grep limpa) | — | — | — |

## Onda 2 — assim que a dependência da onda 1 fechar

| Task | Depois de | Nota |
|------|-----------|------|
| ~~B2.7~~ ✅ FECHADA 2026-07-16/17 (PR1+PR2+PR3, ver `tasks/README.md`) | — | — |
| ~~B2.8~~ ✅ FECHADA 2026-07-17 — `ArmFeature.PRELOAD_HINTS` novo; `PLD`/`PLDW`/`PLI` como NOP em ARM (`ArmDecoder`) e Thumb-2 (`Thumb2LoadStoreDecoder`, carve-out em `Rt=PC`); suítes arm-jitter 682+13, gbaemu 243, ndsemu 175 verdes | — | — |
| ~~B3.1~~ ✅ FECHADA 2026-07-17 — todas as 13 instruções da tabela colidiam com dispatches genéricos do `ArmDecoder` (achado do Passo 0); viraram carve-outs diretos (não uma `DecoderExtension`), como a própria spec previa como contingência. Suítes arm-jitter 698+13, gbaemu e ndsemu verdes | — | — |
| ~~B3.3~~ ✅ FECHADA 2026-07-17 — `VfpRegisters`+`FpscrRegister` novos, estado só (sem instrução ainda); `STATE_VERSION` interno no `ArmCore` (retrocompat de leitura) + bump de `GbaConsole`/`NdsConsole`; `CpuSnapshot` cobre VFP. Suites arm-jitter 710+13, gbaemu 239, ndsemu 175 verdes | — | — |
| ~~B7.1~~ ✅ FECHADA 2026-07-17 — `ExceptionModel`/`AProfileExceptionModel` extraídos zero-diff; intercept plugado nos 3 caminhos de PC-vindo-de-dado nos 2 backends (achado: ASM não tinha ponto de entrada único para load-to-PC, unificado num `loadToPc` novo). Suítes arm-jitter 716+13, gbaemu, ndsemu verdes; bench JUS normal | — | — |
| **C7** (Paged ndsemu) | C6 | Validação de gameplay do usuário |
| **C8** (perf interpretador) | C6 | Fase 1 ✅ e fase 2 ENCERRADA 2026-07-17: candidato #1 (dispatch) −15,6%, candidato #2 (decode/lift) já coberto sem PR, candidato #4 (`GbaBus` owner table) +≈−6,6% extra (ver `tasks/README.md`) — falta só validação do usuário (FireRed batalha) |
| ~~C10~~ ✅ FECHADA 2026-07-17 — `JitRuntime#hotBlockKeys`/`#precompile` novos (arm-jitter) + `HotBlockStore` (ndsemu, `.hotpcs` texto versionado + guarda CRC32); precompile chamado SÍNCRONO na thread de emulação (BlockCache não é thread-safe p/ chamada de outra thread). Suítes arm-jitter 724+13, ndsemu 179, gbaemu verdes. Aceite #1 (medição fps MKDS) e #2 (asmcheck JUS real) PENDENTES — sem ambiente de ROM real nesta sessão | — | — |
| **A7** (revalidação native-image) | A6 | Precisa do ambiente GraalVM+MSVC (usuário presente) |

## Onda 3 — fechamentos

~~B3.2~~ ✅ FECHADA 2026-07-17 — `SBFX`/`UBFX`/`BFI`/`BFC` (`Thumb2DataProcessingDecoder`),
`MLS`/`SDIV`/`UDIV` (`Thumb2MultiplyDecoder`), `RBIT` (`Thumb2RegisterDataProcessingDecoder`);
zero IR nova, layout confirmado contra o QEMU `t32.decode`; suítes arm-jitter 745+13, gbaemu 239,
ndsemu 179 verdes (ver `tasks/README.md`)

B3.4 → B3.5 → B3.6 → B3.7 (cadeia VFP) · B7.2 → B7.3 → B7.4 → B7.5
(cadeia Cortex-M) · **B4.0.3** (após B2.7+B2.8 — B2.6/B1.7/B4.0.4/B4.0.4.1 já ✅;
o PR3 da B2.7 inclui o decode MCR/MRC Thumb-2 que a B4.0.4 confirmou faltar) ·
B4.0.5 · C9 (após C7) · A8 (após A7) · **A9 PR1** (lib nativa `.dll`/`.so` com
API C, backend interpretado — pode rodar a qualquer momento com o usuário
presente p/ GraalVM+MSVC; **A9 PR2** após A7) · B5.2 (após B3.5) · B4.1.1+ e
B6.1+ quando priorizados.

## Fila de BUGS de compat (trilha D) — sessões separadas da fila principal

Backend-independentes (confirmado 2026-07-16: INTERPRETED e ASM idênticos em
sintoma e velocidade no GBA — a atribuição antiga ao ASM está REVOGADA).

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D2** — FireRed 3 bugs de batalha | Hipóteses FORTES já na spec (HDMA em vblank / wrap 9-bit OAM X / OBJWIN), 1 PR por sintoma, teste-primeiro | Agente comum PODE tentar (a spec é dirigida); se a hipótese do sintoma falhar → devolver |
| ~~D4~~ ✅ FECHADA 2026-07-16 — sem problema real | — | — |
| ~~D3~~ ✅ FECHADA 2026-07-16 — sem problema real | — | — |
| **D6** — BIOS lenta/interrompida | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| **D5** — Platinum trava (Buneary) | ndsemu; ler pistas falsas em `ndsemu-game-compat` ANTES | ⚠️ MODELO FORTE |
| (sem task) Divergência ASM×interp no JUS | ver pendência 6 do tasks/README | ⚠️ MODELO FORTE |

## Onde o USUÁRIO entra (planejar presença)

- C11 fase 1: fornecer o save state do SM64DS; C11/C6/C7/D1: validar gameplay.
- A7/A8/A9: rodar na máquina com GraalVM 25 (`E:\graalvm-jdk-25.0.3+9.1`) + MSVC
  (A9 também usa o `cl.exe` para compilar o smoke test em C).
- B4.0.3/B3.7: toolchain (`arm-none-eabi-gcc`) já usada nos testdata do armbox.
