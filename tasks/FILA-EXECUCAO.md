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
| 4 | **B4.0.4** — TLS/TPIDRURO no armbox | armbox | Pequena; pré-requisito de B4.0.3 | Paralela a qualquer arm-jitter |
| 5 | **C6** — PagedAddressSpace no gbaemu | gbaemu | Perf do modo default (INTERPRETED) | Paralela; validação de gameplay do usuário no fim |
| 6 | **D1** — RTC GPIO (Emerald) | gbaemu | Compat barata | Após C6 (mesmo repo) |
| 7 | **A6** — especialização de nós Truffle | arm-jitter (módulo `truffle/`) | Destrava A7/A8 | Paralela (módulo isolado); é a mais difícil da onda — se travar, devolver |
| 8 | **B5.1** — monitor exclusivo global | arm-jitter | Pequena; independente | Após B1.7 (mesmo repo, toca ArmCore) |
| 9 | **E1** — javadocs em inglês → português (regra G7) | arm-jitter | Higiene; risco zero de código | **1 sessão POR LOTE** (4 lotes, checklist no próprio arquivo); pode intercalar entre tasks maiores; NÃO rodar em paralelo com outra task de arm-jitter (diff de comentário conflita com tudo) |

## Onda 2 — assim que a dependência da onda 1 fechar

| Task | Depois de | Nota |
|------|-----------|------|
| **B2.7** (paridade Thumb-2, PR1→PR2→PR3) | B2.6 | 3 sessões, uma por PR, na ordem |
| **B2.8** (PLD/PLI) | B2.6 | 1 sessão, pequena |
| **B3.1** (inteiro v7 ARM) | B2.6 | Pode andar em paralelo com B2.7 se PRs não tocarem os mesmos decoders (B3.1 = decoder ARM novo; B2.7 = decoders Thumb-2) |
| **B3.3** (banco VFP) | B5.1 (mesmo repo/ArmCore) | Tecnicamente independente |
| **B7.1** (ExceptionModel refactor) | B2.6 + B3.3 (toca ArmCore — sequenciar) | Zero-diff; bench de sanidade no fim |
| **C7** (Paged ndsemu) | C6 | Validação de gameplay do usuário |
| **C8** (perf interpretador) | C6 | Fase 1 (medir) e cada candidato = sessões separadas |
| **C10** (warm-start `.hotpcs`) | C11 (reusa as APIs) | 1 sessão |
| **A7** (revalidação native-image) | A6 | Precisa do ambiente GraalVM+MSVC (usuário presente) |

## Onda 3 — fechamentos

B3.2 → B3.4 → B3.5 → B3.6 → B3.7 (cadeia VFP) · B7.2 → B7.3 → B7.4 → B7.5
(cadeia Cortex-M) · **B4.0.3** (após B2.7+B1.7+B2.8+B4.0.4) · B4.0.5 · C9 (após
C7) · A8 (após A7) · B5.2 (após B3.5) · B4.1.1+ e B6.1+ quando priorizados.

## Onde o USUÁRIO entra (planejar presença)

- C11 fase 1: fornecer o save state do SM64DS; C11/C6/C7/D1: validar gameplay.
- A7/A8: rodar na máquina com GraalVM 25 (`E:\graalvm-jdk-25.0.3+9.1`) + MSVC.
- B4.0.3/B3.7: toolchain (`arm-none-eabi-gcc`) já usada nos testdata do armbox.
