# C11 — Restaurar save state deixa o JIT frio por ~10 minutos (SM64DS, relato do usuário)

**Trilha:** C · **Depende de:** — (C10 relacionada; ver §Fix A) · **Repos:** ndsemu + arm-jitter (API)

## Sintoma (usuário, 2026-07-15, SM64DS in-game)

Jogando a 60fps cravados → F5 (save state) → F8 (restore) → **muito lento, >10
minutos até voltar aos 60fps**. Hipótese do usuário: blocos criados "no meio do
código" pós-restore atrapalhando.

## O que o código já diz (verificado 2026-07-15 — partir daqui, não re-descobrir)

- `NdsConsole.loadState` (`NdsConsole.java:1028-1032`) chama
  `runtime9.blockCache().clear()` + `runtime7...` — correto para corretude (a RAM
  foi trocada por baixo do JIT), e `BlockCache.clear()` (`BlockCache.java:181`)
  também incrementa `generation` (derruba o inline cache) e limpa o índice de
  páginas.
- **Consequência**: TODO o working set volta ao tier frio e cada bloco precisa
  re-atingir `hotThreshold` execuções + esperar a fila do pool de compilação. No
  boot isso é rápido (working set pequeno e concentrado); **in-game o working set
  é 10-100× maior** (engine+fase+atores) → re-aquecimento longo. O sintoma é
  coerente com isso mesmo sem nenhum bug adicional.
- Hook de repro JÁ EXISTE: `NdsConsole.loadStateKeepingJit` (`NdsConsole.java:934`)
  restaura SEM limpar o cache (debug headless) — é o "grupo controle" perfeito
  para o A/B da fase 1.
- Estado que `clear()` NÃO toca (candidato a lixo residual, checar na fase 1):
  `ChainProfiler` (se instalado), `LoopSuperblockDetector`/contadores de ciclo, e
  qualquer contador de hotness que viva FORA da entrada do cache.

## Fase 1 — reproduzir e medir (headless, mecânico)

1. Repro determinística: savestate de SM64DS in-game (pedir ao usuário o `.ss`
   dele ou criar um equivalente). Rodar headless N=36000 frames (10 min de guest)
   em 3 cenários: (a) `loadState` normal (frio), (b) `loadStateKeepingJit`
   (quente — o "teto"), (c) boot frio até o mesmo ponto NÃO é necessário.
2. Instrumentação temporária (contadores no `JitRuntime`, atrás de flag de
   diagnóstico, mesmo padrão do `[gui-prof]` do ndsemu): por janela de 60 frames,
   registrar — ms/frame, blocos compilados na janela, profundidade da fila do
   pool, execuções no tier frio vs compilado. Tabela CSV no PR.
3. Saída da fase: quanto do gap frio→quente é (i) re-aquecer threshold, (ii) fila
   de compilação, (iii) outra coisa inesperada (se (iii) dominar — ex. algo
   recompilando em loop por invalidação — PARAR e reportar; vira sessão de
   modelo forte).

## Fase 2 — fixes, em ordem (cada um re-medido contra a fase 1)

**Fix A (principal) — o savestate SABE o working set quente: embutir e pré-compilar.**
É o caso ideal da C10: em `saveState`, anexar `runtime.hotBlockKeys(1024)` dos
dois cores ao stream (bump `SAVE_STATE_VERSION` 2→3, leitura tolerante a estados
v2 sem a seção); em `loadState`, após o `clear()`, chamar
`runtime.precompile(keys)` (API da C10 — implementar as duas APIs da C10 como
parte desta task se a C10 ainda não tiver rodado; os specs não podem duplicar:
quem rodar primeiro cria, o outro reusa). Isso recompila exatamente o que estava
quente, em background, a partir da memória JÁ restaurada — corretude por
construção (mesmo argumento da C10).

**Fix B — modo warm pós-restore.** Enquanto a fila do Fix A drena, o threshold
alto segura blocos novos no tier frio: após restore, baixar temporariamente o
`hotThreshold` efetivo (ex. para 4) por ~600 frames e restaurar — espelhar o
mecanismo de troca pós-boot que C4 já criou (`setRuntimeChainBudgets`/switchframe:
mesmo padrão, outro parâmetro). Medir isolado E combinado com A.

**Fix C — reset completo e honesto.** `JitRuntime.reset()` novo no arm-jitter:
limpa cache + IC + estado de chaining/superbloco/profiler num método só, com
javadoc "usar em restore de estado"; `NdsConsole.loadState` troca os dois
`blockCache().clear()` por ele. Provavelmente não muda o tempo (generation já
cobre o IC), mas elimina a classe de lixo residual e é a API certa para os
hospedeiros — gbaemu (save states F5/F8 também!) deve adotar no mesmo PR do lado
de lá se o mesmo padrão `clear()`-only existir (verificar `GbaConsole`).

## Aceite

1. Tabela da fase 1 no PR (frio vs quente, as 4 métricas por janela).
2. Pós-fix: tempo até fps estável após F8 no cenário do relato ≤ 60s (ideal:
   ≤ 15s), medido pela métrica da C10 (média móvel a <5% da final).
3. `asmcheck` JUS zero divergências com Fix A ativo; boot dos 4 jogos OK;
   save/load ida-e-volta v3 + leitura de estado v2 antigo OK (só sem warm-up).
4. Suites ndsemu + arm-jitter + gbaemu verdes.
5. Validação do usuário: repetir o cenário real dele (SM64DS, F5→F8) e confirmar.

## Armadilhas

- `precompile` pós-restore compila com a MEMÓRIA restaurada — nunca guardar
  bytecode no estado, só chaves (argumento de segurança da C10).
- O bump de versão do `.ss` invalida estados antigos se a leitura não for
  tolerante — a seção nova vai NO FIM do stream e a versão 2 é aceita sem ela
  (padrão do `loadState` versionado da B3.3).
- Os contadores de diagnóstico da fase 1 saem do caminho quente no PR final
  (flag off = zero custo), como o `[gui-prof]` faz.
- gbaemu: o warm-up lá é irrelevante (INTERPRETED default), mas o Fix C se aplica
  por higiene se o padrão existir — NÃO portar Fix A/B para o gbaemu.
