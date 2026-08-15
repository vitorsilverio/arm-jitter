## Resumo

Ao iniciar (ou logo depois de trocar de cena), o emulador fica lento até o JIT aquecer.

## Estado da investigação

Existe uma spec de warm-start (`arm-jitter/tasks/trilha-c-perf/c10-jit-warmstart-ndsemu.md`,
`hotBlockKeys`/`precompile`), **bloqueada** porque a medição precisa de ROM real e do usuário
presente.

Um caso extremo relacionado **já foi corrigido**: o JIT ficava frio por mais de 10 minutos
depois de restaurar um savestate, porque `blockCache().clear()` não zerava `superblockHeads`
nem o estado do detector de superblocos; `JitRuntime.reset()` resolveu e a janela caiu para
20–40 s.

O que resta é o warmup **normal**, de início de execução.

## Labels sugeridas

`perf`, `blocked:user`
