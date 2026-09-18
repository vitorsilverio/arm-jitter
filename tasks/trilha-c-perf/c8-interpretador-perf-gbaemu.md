# C8 — Perf do interpretador (o caminho de PRODUÇÃO do gbaemu)

**Trilha:** C · **Depende de:** — (C6 recomendada antes: mesmo bench) · **Repos:** arm-jitter (+ bench no gbaemu)

## Contexto — por que o interpretador importa (decisão do usuário, 2026-07-15)

O gbaemu roda `INTERPRETED` **por design, não por falta do JIT**: para GBA o
compilado não dá ganho e a granularidade de bloco atrapalha a fidelidade — IRQs
de H-blank/V-blank/STAT precisam de precisão por instrução, e "esperar o bloco
inteiro executar" quebra a lógica dos jogos (Pokémon em batalha fica visivelmente
errado no ASM). O interpretado é o modo fiel e ficará como default. Consequência:
**toda a trilha C até hoje otimizou só o caminho JIT (bancada ndsemu); o caminho
interpretado nunca foi medido nem otimizado — e é ele que roda GBA em produção.**
O mesmo caminho serve `--interp`/`--check` do armbox e o tier frio de todos os
consumidores.

## Restrição INEGOCIÁVEL (a razão de ser do modo)

Nenhuma otimização pode mudar a granularidade observável: exceção/IRQ continua
podendo entrar entre DUAS INSTRUÇÕES quaisquer, ciclos continuam contados por
instrução (G4), e `JitInterpreterDivergenceTest`/gba-tests continuam bit-exatos.
Qualquer ideia que "junte" instruções do ponto de vista de interrupção está FORA
(isso seria reinventar o JIT e reintroduzir o bug que o INTERPRETED evita).

## Fase 1 — medir (entregável próprio, mesmo se a fase 2 mudar)

1. Bench interpretado no gbaemu espelhando `ChainCycleBudgetBenchTest` (C5):
   `InterpretedThroughputBenchTest`, 5 jogos de referência, 50M ciclos, tabela
   ms/jogo — roda com `mvn -o -Dtest=InterpretedThroughputBenchTest test`.
   Este bench é o ANTES/DEPOIS de toda a task.
2. JFR de 120s de FireRed em batalha (savestate) no modo interpretado — anexar o
   top-10 de métodos no PR. Só então decidir QUAL item da fase 2 atacar primeiro
   (a lista abaixo é de candidatos identificados por leitura de código, em ordem
   de expectativa — o profile manda).

## Fase 2 — candidatos (implementar um por PR, re-medindo; parar quando <2%)

| # | Candidato | Ideia |
|---|-----------|-------|
| 1 | Dispatch do `IrBlockExecutor#executeOp` | O switch exaustivo sobre ~40 tipos roda POR OP executada. O bloco é imutável pós-lift: pré-resolver o dispatch UMA vez por bloco (array paralelo de handlers/`MethodHandle`s constantes, ou ordinal compacto → tabela) e o loop quente só indexa. Manter `executeOp` público intacto (fallback PER_OP do ASM usa — G3) |
| 2 | Decode/lift repetido | Verificar se blocos interpretados ficam no `BlockCache` com IR pronta ou se há re-lift; se o cache já cobre, pular |
| 3 | Condição AL | Guard de condição consultado por op mesmo em bloco 100% AL (a maioria) — flag pré-computada no bloco "nenhuma op condicional" pulando a checagem |
| 4 | `addMemoryCycles`/waitstate por acesso | Par com C6: com `PagedAddressSpace`, custo de ciclo por página em lookup direto |
| 5 | Alocação por op/bloco | O que o JFR de alocação mostrar (iteradores, boxing) — só com evidência |

## Aceite

- Tabela antes/depois dos 5 jogos publicada por PR; ganho agregado alvo ≥15% no
  interpretado (é a primeira passada de perf nesse caminho — se o profile mostrar
  que não há nem isso, registrar e fechar com o resultado honesto, como C1).
- `JitInterpreterDivergenceTest` + gba-tests + suíte gbaemu 216 verdes por PR;
  ndsemu 175 verde (o tier frio dele usa o mesmo executor).
- Validação do usuário no fim: FireRed batalha + os glitches que motivaram o
  INTERPRETED continuam ausentes (o modo fiel continua fiel).

## Armadilhas

- NÃO usar o bench do ndsemu como métrica — a bancada desta task é gbaemu
  interpretado; ndsemu só valida regressão.
- `MethodHandle`/lambda por op pode ser MAIS lento que o switch (megamorfismo no
  call site do handler) — por isso a fase 1 mede primeiro e cada PR re-mede; um
  candidato que não ganhar é revertido, não "deixado porque não atrapalha".
- O tier frio do JIT dos OUTROS consumidores passa por aqui — qualquer mudança
  estrutural no executor precisa das suítes dos 3 (arm-jitter/gbaemu/ndsemu)
  verdes, sempre.

## Resultado

**🟡→✅ Fase 1 (medir) concluída em 2026-07-17.** `InterpretedThroughputBenchTest`
novo (espelha `ChainCycleBudgetBenchTest` de C5, `useJit=false`); ANTES publicado:
pokefirered 612ms, smw 635ms, castlevania 150ms, metroid 227ms, mariokart 301ms
(50M ciclos cada). JFR de 120s (`InterpretedProfileMain`, harness manual não-teste)
rodado em FireRed interpretado — **savestate de batalha não disponível nesta
sessão** (nenhum `.ss` de batalha em `roms/`), perfil coletado em boot+overworld em
vez disso (limitação registrada, não bloqueante: o hot path do dispatcher não
deveria variar por fase do jogo). Top do profile confirma a expectativa da spec:
`IrBlockExecutor.execute` (o switch de dispatch) domina (~5000 de ~8400 amostras),
seguido por `IrAluExecutor`/`IrMemoryExecutor`/`GbaBus$MemorySpaceGroup.owner`
(waitstate lookup, candidato #4). Suites arm-jitter (sem mudança) + gbaemu
(`JitInterpreterDivergenceTest` sem divergência) verdes.

**Fase 2, candidato #1 (dispatch) implementado e MANTIDO (2026-07-17, sessão
seguinte)**: `IrOp.kind()` (chamada virtual megamórfica sobre ~40 subtipos selados)
deixou de ser chamado por op a cada execução do bloco; `IrBlock` agora resolve um
`int[] kindsArray` uma única vez na construção (paralelo a `operationsArray()`), e
`IrBlockExecutor#execute` faz `switch (kinds[i])` em vez de `switch (op.kind())` — o
tableswitch em si não mudou, só a origem do discriminador. `executeOp` (fallback
PER_OP do ASM) não foi tocado. Ganho medido: 10 execuções antes/depois (para
absorver ruído de máquina, até ±20% run-a-run) — pokefirered 929→810ms, smw
832→657ms, castlevania 172→148ms, metroid 280→258ms, mariokart 365→304ms,
**agregado −15,6%** (bate a meta ≥15% do Aceite). Suítes arm-jitter 745+13, gbaemu
240, ndsemu 179 — todas verdes.

**Candidato #2 investigado, sem PR (nada a mudar)**: verificado no código
(`JitRuntime#execute`, caminho não-tiered, `hotThreshold=1`) que o `lift` só roda na
primeira execução de cada `pc`; a partir daí o `IrBlock` fica no `BlockCache` para
sempre (ou até invalidação por SMC), sem re-lift em cache hit. Consistente com o
profile da fase 1. **Fechado como "já coberto", zero código.**

**Candidato #4 (`GbaBus$MemorySpaceGroup.owner`) implementado em seguida** (não o
#3 — o profile também não credenciava, `evalCond` já é um `switch` barato). Bucket
de I/O ganhou uma tabela endereço→dono pré-computada na montagem
(`GbaBus#mapIoBucket`, `MemorySpaceGroup` aceita `fastOwners`/`fastBase`
opcionais), substituindo a varredura linear de `owner()` por um índice de array —
reproduz EXATAMENTE a mesma regra de prioridade do `owner()` original; buckets
pequenos (≤2 membros) não usam a tabela. Ganho medido: agregado médio
2350ms→2194ms, **≈ −6,6%** (acima do piso de 2% do Aceite). Suíte gbaemu 240 verde;
mudança 100% gbaemu (`GbaBus.java`), ndsemu não revalidado (não usa `GbaBus`).

**Fase 2 encerrada** (meta ≥15% batida pelo #1, #2 sem custo real, #4 deu ganho
extra positivo; candidato #3 descartado por análise e #5 exigiria evidência de
alocação que o profile desta sessão não coletou). **Validação do usuário
2026-07-17**: FireRed continua com velocidade normal — sem regressão perceptível de
fidelidade/timing das otimizações da fase 2. **C8 FECHADA.**
