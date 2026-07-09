# A0 — Spike de viabilidade Truffle

**Trilha:** A (Truffle/GraalVM) · **Depende de:** — · **Repo:** arm-jitter (branch descartável)

## Contexto

Queremos um terceiro backend de codegen (`TRUFFLE`) que converta o IR próprio em nós
Truffle, para ter JIT dentro de native-image e comparar perf com o backend ASM. Antes
de investir, precisamos de dados. Este spike é **código descartável** — nada dele será
mergeado; o entregável é um relatório.

## Objetivo

Responder, com medições, às 4 perguntas abaixo e registrar em `tasks/trilha-a-truffle/RELATORIO-A0.md`.

1. Truffle consegue JIT (compilação real, não modo interpretado) rodando no **JBR 25**
   via "Truffle Unchained" (compilador Graal como dependência Maven + JVMCI)? E no
   **GraalVM CE** mais recente?
2. Qual API usar: **Bytecode DSL** (mais nova, boa para IR linear como o nosso) ou
   **AST clássica** (`RootNode` + nós)? Avaliar maturidade/estabilidade da Bytecode DSL
   na versão corrente do Truffle.
3. Perf de um bloco quente ALU: quantos % do backend ASM o Truffle atinge em cada JVM?
4. O modelo de `CompiledBlock` (método estático `int execute(ArmCore)`) mapeia bem para
   `CallTarget.call(...)`? Algum obstáculo (boxing de argumentos, boundaries)?

## Inclui

- Branch `spike/truffle-a0` a partir de `master`.
- Dependências (só no branch): `org.graalvm.truffle:truffle-api` e o runtime/compiler
  necessário para Unchained (verificar artefatos atuais na doc oficial do GraalVM —
  não confiar em nomes de memória).
- Um `RootNode` que executa um `IrBlock` fixo (MOV/ADD/SUB/CMP em loop, ~20 ops),
  construído com o `StandardIrBlockLifter` existente ou montado à mão com `IrOp`.
- Verificação de compilação com `-Dpolyglot.engine.TraceCompilation=true` (ou flag
  equivalente da versão corrente).
- Microbench manual (loop de aquecimento + medição de ns/execução) comparando:
  Truffle vs `AsmCodeEmitter` vs `InterpretedCodeEmitter`, mesmo `IrBlock`, nas JVMs
  disponíveis (JBR 25; GraalVM CE se instalável).

## Não fazer

- NÃO mergear nada deste branch.
- NÃO alterar `pom.xml` do master, nem qualquer classe existente.
- NÃO implementar cobertura além do mínimo para o bench rodar.

## Aceite

- `RELATORIO-A0.md` commitado no master (só o relatório) contendo: tabela de perf
  (JVM × backend × ns/op), decisão Bytecode DSL vs AST com justificativa, se Unchained
  funciona no JBR 25 (com as flags exatas usadas), e o veredito da trilha.
- **Kill criterion:** se em NENHUMA JVM o Truffle atingir ≥50% do ASM em bloco quente,
  registrar no relatório que a trilha A vira "somente native-image" (A5 continua,
  A4 perde prioridade) e atualizar o ROADMAP.md.

## Armadilhas

- Truffle sem o compilador Graal ativo roda em **modo interpretado silenciosamente** —
  sempre confirmar via TraceCompilation que houve compilação antes de medir.
- Aquecer o suficiente (dezenas de milhares de iterações) antes de medir; medir com
  `System.nanoTime` em janelas, descartando as primeiras.
- Versões: usar a versão estável mais recente do Truffle e conferir a documentação da
  MESMA versão — a API de embedding mudou várias vezes.
