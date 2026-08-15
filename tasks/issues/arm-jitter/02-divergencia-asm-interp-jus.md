## Resumo

Divergência entre os backends ASM e interpretado, observada com ROM real: rodando `asmcheck`
a partir de `roms/JUS.ss` (~300 chunks), o estado diverge em **`r1`** no
`block@0x1ff8f44` — diferença de `0x38` num registrador só, todo o resto idêntico.

Isso viola o invariante **G1** (o interpretador é o oráculo; todo backend compilado tem de
produzir estado idêntico).

## Como reproduzir

`asmcheck` a partir do savestate `roms/JUS.ss` do ndsemu, ~300 chunks.

## Estado da investigação

- Encontrada durante a re-medição da fase 2 da C11, em 2026-07-16.
- **Não é causada pelo "Fix C"** (`JitRuntime.reset()`): o backend `ASM_CHECK` não liga
  superblocos de loop, e `JitRuntime.reset()` se comporta byte a byte como o
  `blockCache().clear()` antigo nesse backend.
- **Pré-existente**, versão de origem desconhecida.
- Não investigada.

## Referência

`arm-jitter/tasks/README.md`, seção "Pendências que EXIGEM sessão de modelo forte", item 6.

## Labels sugeridas

`bug`, `jit`, `needs-design`
