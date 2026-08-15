## Resumo

O `armbox` não implementa `fork`/`execve`/`pipe`/`wait` — a "fase 3" do runner user-mode.
Hoje ele roda **um** binário, sem criar processos.

## Estado

Spec pronta: `arm-jitter/tasks/trilha-b-arquiteturas/b4.0.5-armbox-fork-pipes.md`.

**Bloqueada** em #TBD-corpus-busybox-thumb2 — a task precisa do corpus busybox Thumb-2 para
validar, e ele não existe nesta máquina.

## O que já funciona

Fase 2 fechada: busybox musl estático roda `echo`, `sh -c` e `uname` nos 3 backends
(JIT/interpretado/divergence-check), com zero divergência entre eles.

## Labels sugeridas

`feature`, `blocked:asset`
