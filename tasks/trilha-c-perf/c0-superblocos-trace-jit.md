# C0 — Superblocos / trace-JIT (a alavanca grande) **[REFINAR — projeto multi-sessão]**

**Trilha:** C · **Depende de:** — · **Repo:** arm-jitter
**Prioridade: a MAIOR da trilha C, segundo o profiling mais recente.**

## Contexto (por que isto é o item nº 1)

Re-profile de 2026-07-08 (MKDS em corrida, savestate, ASM, render off-thread): o custo
nº 1 do emu thread é o **dispatch entre blocos** — a chamada virtual megamórfica
`icBlocks[..].execute(core)` dentro do loop de chaining do `JitRuntime.execute`
(~26% das amostras; no SM64DS in-game a soma dispatch+chain chega a ~36%). O chaining
já eliminou o round-trip do scheduler; o que sobra é o custo da PRÓPRIA chamada
virtual por bloco (blocos médios de poucos ciclos — spin loops e código de jogo em
blocos curtos). Alavancas pequenas nesse ponto foram DESCARTADAS por medição
(budget sweep 96/8…1024/256 = mesmos fps; flags em locals ≈0 em gameplay — ver C1).

## Ideia

Fundir uma SEQUÊNCIA de blocos encadeados quentes em UM método compilado
("superbloco"/trace): N chamadas megamórficas viram 1, e o C2 da JVM passa a otimizar
através das fronteiras de bloco (registradores/flags vivem em locals ao longo do trace
— o que torna C1/C2 subprodutos disto).

## Questões para o refinamento (escrever spec detalhada antes de codar)

1. **Seleção de traces:** perfil de sequências no chaining atual (contadores de pares
   bloco→bloco no `JitRuntime`) → compilar traces acima de limiar. Onde guardar
   (cache de traces separado? entrada extra no `BlockCache`?).
2. **Saídas laterais:** branch que sai do trace no meio → side exit que devolve
   PC/estado corretos ao runtime (equivalente ao fixup atual de PC).
3. **Invalidação SMC:** trace toca N blocos → invalidar o trace se QUALQUER página
   dos N blocos for escrita (estender o page-index bitset do `BlockCache.invalidate`).
4. **Limites:** budget de ciclos por trace (mesma disciplina do chaining — os
   handshakes cross-CPU do ndsemu continuam valendo: ver C4); tamanho máximo de
   método JVM (64K bytecode) — traces longos precisam de corte.
5. **Interação com tiers:** trace é um tier 2 acima do bloco compilado? Warmup do
   pool de compilação já existente serve?
6. **Oráculo:** o divergence-checker precisa aprender a comparar execução de trace
   (multi-bloco) contra o interpretador — provavelmente comparando nos side exits.

## Aceite (do épico)

- Bench A/B nos cenários de referência (MKDS corrida, SM64DS in-game, JUS): a fatia
  de `JitRuntime.execute`/dispatch no JFR cai substancialmente e o fps sobe de forma
  estável (≥10% num dos cenários para justificar o merge).
- asmcheck/divergence longo zero divergências; boots ×4 do ndsemu ok; gbaemu verde.

## Armadilha maior

Não começar pela infraestrutura: começar pelo CONTADOR de pares/traces e pelos dados
(quais sequências dominam, qual o tamanho típico) — se os traces reais forem curtos
demais, o desenho muda (ex.: inline de blocos-alvo pequenos dentro do chamador).
