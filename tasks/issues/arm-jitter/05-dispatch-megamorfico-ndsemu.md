## Resumo

`JitRuntime.execute` responde por ~12–14% do perfil do ndsemu **depois** dos superblocos
(medição de 2026-07-11, durante a C1) — dispatch megamórfico remanescente.

## O que falta

Não há spec. Precisa de **profiling novo** (o número é de julho e o código mudou desde então)
e de desenho antes de virar task.

## Contexto

O caminho de perf já colheu: register cache em locals, inline cache de 32K, encadeamento de
blocos com budget de ciclos, superblocos de loop, guards condicionais especializados, LDM/STM
inline, ARMv5TE e shifted-register nativos. Este é o que sobrou visível no perfil.

## Referência

`arm-jitter/tasks/README.md`, "Pendências que EXIGEM sessão de modelo forte", item 3.

## Labels sugeridas

`perf`, `jit`, `needs-design`
