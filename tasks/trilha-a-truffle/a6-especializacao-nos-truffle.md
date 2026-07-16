# A6 — Especialização de nós Truffle por `IrOp` (compilação real)

**Trilha:** A · **Depende de:** A5 · **Repo:** arm-jitter (módulo `truffle/`)

## Contexto

A5 (demo native-image) foi a primeira vez que o backend Truffle rodou contra blocos
ARM **reais** (loop de verdade via `busybox sh -c`, não os blocos sintéticos retos de
ALU do benchmark A0/A4) e mediu compilação com `TraceCompilation`. Resultado: **0
blocos compilados com sucesso**, tanto no binário native-image quanto na JVM (JBR 25 +
Truffle Unchained) — ver `tasks/trilha-a-truffle/RELATORIO-A5.md` para o relato
completo, incluindo os dois bailouts observados (`FrameWithoutBoxing should not be
materialized` no SVM, `PEGraphDecoder.tooDeepInlining` no JBR).

**Causa raiz (não é bug pontual, é decisão de arquitetura de A2/A3):**
`TruffleBlockRootNode` (`truffle/src/main/java/dev/vitorsilverio/armjitter/truffle/
TruffleBlockRootNode.java`) guarda o bloco como dado (`IrOp[] ops`,
`@CompilationFinal(dimensions = 1)`) e o método `executeBlock` (`@ExplodeLoop`) itera
esse array chamando `IrBlockExecutor#executeOp` para cada op — o MESMO dispatcher
exaustivo (`switch` sobre todo `IrOp.Kind`, cobrindo as 33 categorias seladas de
`IrOp`, ver `core/src/main/java/dev/vitorsilverio/armjitter/ir/IrOp.java`) que o
interpretador puro e o fallback PER_OP do ASM usam. Essa reutilização foi a decisão
CORRETA de A2/A3 (evita reimplementar semântica, mantém G1 — o interpretador como
oráculo) mas tem um custo que só apareceu com blocos reais: a avaliação parcial (PE)
do Graal precisa "ver através" da árvore de nós para especializar/podar ramos em tempo
de compilação, e um `switch` runtime sobre um `enum Kind` de 33 casos, chamado dentro
de um loop `@ExplodeLoop`, não é uma estrutura que o PE consegue podar — ele tenta
expandir/inlinear TODOS os ramos possíveis para QUALQUER bloco, e estoura o orçamento
de inlining (JBR) ou não consegue manter o frame virtualizado através do dispatch
(SVM). Os blocos ALU-somente do bench A0/A4 nunca expuseram isso por serem simples
demais (poucas ops, sem branches/memória/sistema).

## Objetivo

Fazer o backend Truffle compilar de verdade blocos ARM reais — não só os sintéticos
do bench — substituindo o dispatcher único por uma árvore de nós Truffle
especializados por categoria de `IrOp`, no padrão real de implementação de linguagem
Truffle (`@Specialization`, profiling por call-site), preservando G1 (o interpretador
continua sendo o oráculo semântico; os nós novos DELEGAM cálculo, não reimplementam
regras de flags/UNPREDICTABLE/features por arquitetura).

## Não inclui

- Não muda o interpretador (`IrBlockExecutor`) nem o backend ASM — ambos continuam
  como estão, esta task é só do módulo `truffle/`.
- Não precisa cobrir as 33 categorias de `IrOp` de uma vez — like A3, pode (e
  provavelmente deve) ser dividida em PRs por categoria, priorizando o que aparece em
  blocos reais primeiro (ALU condicional, Load/Store, Branch — o essencial de um loop
  típico) antes de categorias raras (Coprocessor, ARMv6 SIMD paralelo, system).
- Não re-executa o bench de 3 JVMs da A4 do zero — só precisa confirmar que os blocos
  antes usados nesse bench continuam corretos, e, se fizer sentido, medir o ganho da
  especialização nos MESMOS blocos de 20/80/320 instruções para comparar com os
  números já registrados.
- Não resolve nem re-tenta o binário nativo da A5 — o fechamento é a task
  [A7](a7-native-image-revalidacao.md) (já especificada), executada em sessão
  separada depois desta.

## Especificação

1. **Desenho de nó por categoria (DECIDIDO — não reavaliar): delegação aos
   executores existentes.** Os executores reais são, em `codegen/executor/`:
   `IrAluExecutor`, `IrMemoryExecutor`, `IrBranchExecutor`, `IrTransferExecutor`,
   `IrSystemExecutor`, `IrCycleExecutor` (+ `IrExecutionSupport` de apoio) — é para
   ELES que `IrBlockExecutor#executeOp` despacha hoje. Cada nó Truffle novo guarda o
   `IrOp` concreto como `@CompilationFinal` e chama DIRETO o método do executor
   correspondente àquela categoria (pulando o switch de 40 casos do `executeOp` —
   o switch é o que mata o PE; a chamada direta monomórfica é o que ele consegue
   inlinar). NENHUMA semântica reimplementada (G1). Taxonomia de nós (1 classe por
   linha, pacote `truffle/`):

   | Nó | Records de `IrOp` cobertos |
   |----|---------------------------|
   | `AluOpNode` | `Alu`, `MoveTop`, `Sel`, `Saturate`, `AbsDiffSum`, `Saturating`, `BitFieldExtract`/`BitFieldInsert`/`BitReverse`/`Divide` (B3.1, se existirem) |
   | `MultiplyOpNode` | `Multiply`, `LongMultiply`, `DspMultiply`, `ParallelAlu` |
   | `MemoryOpNode` | `Load`, `Store`, `LoadLiteral`, `DoubleTransfer`, `Swap`, `LoadExclusive`, `StoreExclusive`, `ClearExclusive` |
   | `TransferOpNode` | `MultipleTransfer`, `Push`, `Pop` |
   | `BranchOpNode` | `Branch`, `BranchExchange`, `ThumbBlPrefix`, `ThumbBlSuffix`, `TableBranch`, `CompareBranchZero` |
   | `SystemOpNode` | `PsrTransfer`, `Swi`, `Coprocessor`, `Undefined`, `ChangeProcessorState`, `SetEndianness`, `StoreReturnState`, `ReturnFromException`, `WaitForInterrupt`, `MemoryBarrier`, `SetItState` |
   | `CycleFetchOpNode` | `Cycle`, `Fetch` |

   Dentro de cada nó, o dispatch entre os poucos records do grupo é um
   `switch` pequeno sobre o TIPO CONCRETO já conhecido em tempo de construção da
   árvore — melhor ainda: escolher a subclasse de nó NA EMISSÃO (ex.
   `MemoryOpNode.forLoad(op)`), de modo que em runtime não haja switch nenhum.
2. **Montagem da árvore por bloco**: `TruffleCodeEmitter`/`TruffleBlockRootNode`
   passam a construir, na hora da emissão (lift do `IrBlock`), um
   `@Children IrOpNode[] ops` (um nó por op, subclasse escolhida na emissão — item 1)
   em vez do array de dados `IrOp[]` percorrido por `switch`. Manter `@ExplodeLoop`
   no `executeBlock` sobre o array `@Children` — este é o padrão canônico Truffle
   (loop explodido sobre filhos homogêneos em quantidade, heterogêneos em tipo) e é
   exatamente o que o PE sabe podar; a contabilidade de `pcChanged` por op (A3)
   continua igual. Isso é o análogo Truffle do que `AsmBlockCompiler` já faz gerando
   bytecode por op — a árvore de nós É o "código gerado" para o Graal.
3. **`@TruffleBoundary` disciplinado — pontos JÁ mapeados** (colocar boundary
   nestes escapes para o hospedeiro, e SÓ neles; adicionar outros apenas se um
   bailout novo apontar): chamadas a `AddressSpace` (read/write — implementação é
   do hospedeiro, opaca ao PE), `SwiDispatcher.dispatch`, `CoprocessorBus.read/write`,
   `ArmTraceListener`, e qualquer `throw` de exceção com construção de mensagem.
   Ops de ALU/flags/registradores NÃO levam boundary (são o que o PE precisa ver).
   A validação de que os boundaries bastam é o próprio Aceite 1 (TraceCompilation).
4. **Profiling por call-site — escopo mínimo fixado**: usar
   `ConditionProfile`/`CountingConditionProfile` em exatamente 2 lugares: (a) o
   guard de condição por-op (`Condition != AL` — a maioria dos blocos executa a
   mesma condição sempre) e (b) o `pcChanged` do fim do bloco. NÃO usar
   `@Specialization`/DSL nesta task (a delegação do item 1 não precisa; DSL entra
   só se uma rodada futura medir ganho) — manter nós escritos à mão.

## Aceite

1. `-Dpolyglot.engine.TraceCompilation=true` contra o MESMO workload de loop real que
   A5 usou (`busybox sh -c 'i=0; while [ $i -lt N ]; do i=$((i+1)); done'`, ou
   equivalente) mostra `opt done` — blocos REALMENTE compilados, não só tentativas.
   Reproduzir tanto no binário native-image do armbox quanto no JBR + Truffle
   Unchained (os dois ambientes onde A5 mediu o bailout).
2. Nenhuma regressão de corretude: suíte `truffle/` + harness de equivalência
   (`BlockEquivalenceHarness`, G1) continuam verdes para todas as categorias
   especializadas nesta task.
3. Ganho mensurável: repetir a medição de A5 (loop de 2000 iterações, `--truffle` vs
   `--interp` no armbox) e confirmar que `--truffle` deixou de ser mais lento que
   `--interp` — idealmente mais rápido, já que é o objetivo original da compilação.
4. `mvn -o test` verde no reactor completo (JBR 25); gbaemu/ndsemu revalidados (G5)
   se algo no core mudar (não deveria — escopo é só `truffle/`).

## Validação

`mvn -o test` (JBR 25) no reactor arm-jitter completo + o diagnóstico manual de
`TraceCompilation` do item 1 do Aceite (binário nativo do armbox requer GraalVM 25 +
ambiente MSVC no Windows — ver `RELATORIO-A5.md` para os passos de build
reproduzíveis já documentados).

## Armadilhas

- **Maior risco arquitetural da trilha A até aqui**: escrever um nó Truffle
  especializado errado (ex. um guard que não cobre todos os casos de uma categoria) é
  o tipo de bug que só aparece sob compilação real, com PE ativo — os testes de
  equivalência do interpretador Truffle (modo não-compilado) podem passar mesmo com um
  nó mal especializado, se a árvore de fallback genérica ainda cobrir o caso. Preferir
  rodar a suíte de equivalência TAMBÉM com compilação forçada (thresholds baixos,
  `engine.CompilationFailureAction=Throw`) para não deixar um bug adormecido até
  alguém compilar de verdade de novo.
- Categorias raras (Coprocessor, ARMv6 SIMD paralelo/GE flags, system) podem não
  valer a pena especializar cedo — o valor está nas categorias que aparecem em loops
  reais (ALU, Load/Store, Branch). Evitar gold-plating: se uma categoria nunca aparece
  nos workloads de referência, ela pode ficar num nó "genérico" (delega pro
  `executeOp` de sempre) sem prejudicar o aceite, desde que isso seja documentado
  explicitamente (não silenciosamente).
- `@ExplodeLoop` sobre uma árvore de nós heterogênea (em vez de um array de dados
  homogêneo) muda a forma como o Graal enxerga o bloco — reconferir se ainda faz
  sentido manter `@ExplodeLoop` no nível do bloco ou se a especialização por nó já
  resolve o problema sem ele (pode ser redundante ou até contraproducente depois da
  mudança).
- G1 continua valendo: NENHUM nó especializado pode reimplementar regras de
  `ArmFeature`/UNPREDICTABLE/flags por conta própria — sempre delegar para o mesmo
  código que o interpretador usa. O ganho desta task é estrutural (como o Graal ENXERGA
  o código), não semântico.
