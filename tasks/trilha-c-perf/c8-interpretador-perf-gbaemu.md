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
