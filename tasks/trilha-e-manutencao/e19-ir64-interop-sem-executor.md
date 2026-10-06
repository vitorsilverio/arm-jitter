# E19 — JIT A64: `Ir64OpInterop`/`Ir64AsmRuntimeHelpers` sem `Ir64BlockExecutor` por op

**Trilha:** E · **Repo:** arm-jitter (A64-only: `codegen64/`, `executor64/`; G5 não se aplica) · **Depende de:**
E15.4 ✅ · **Status:** ⬜ — spec escrita em 2026-10-06 (pendência anotada na E15.7, registrada como task pela
regra 9 do `tasks/README.md`).

## Contexto

Desde a E15.4, `Ir64BlockExecutor#executeOp(core, op)` é só `return op.execute(core);` — o executor não tem
estado que influencie uma op. Mesmo assim o backend ASM de 64 bits ainda carrega o executor:

- `codegen64/jvm64/Ir64OpInterop` guarda uma lista `EXECUTORS` paralela a `REGISTRY` (uma entrada por op
  registrada, permanente) e `executeInterpreted` faz `EXECUTORS.get(opId).executeOp(core, REGISTRY.get(opId))`;
  `register(op, executor)` recebe o executor só para isso.
- `codegen64/jvm64/Ir64AsmRuntimeHelpers` mantém `private static final Ir64BlockExecutor EXECUTOR` para delegar a
  `executeOp`.

A justificativa no Javadoc ("multiarquitetura: nunca um executor global compartilhado", herdada do
`IrOpInterop` de 32 bits, onde o executor TEM estado) não vale mais para o A64. Custo atual: uma lista que
cresce em paralelo para sempre e uma indireção a mais por op não nativa no JIT.

## Objetivo

O JIT A64 chama `op.execute(core)` direto; nenhum `Ir64BlockExecutor` é guardado por op.

## Inclui

- `Ir64OpInterop`: remover `EXECUTORS`; `register(Ir64Op)`; `executeInterpreted` → `REGISTRY.get(opId).execute(core)`;
  Javadoc atualizado (explicar por que o A64 difere do 32 bits).
- `Ir64AsmRuntimeHelpers`: chamar `op.execute(core)`; remover o executor estático.
- Chamadores de `register` (`Ir64BlockCompiler`) e qualquer nome interno de bytecode por STRING (armadilha das
  E15.4/E15.7: o `javac` não pega descritor de método em string).
- Decidir com o usuário se `Ir64BlockExecutor#executeOp` (público) fica como está (G3: é ponto de entrada estável
  do backend) — recomendação: manter, só deixar de usá-lo internamente.

## Aceite

- Oráculo de bytecode (técnica da E15.7, `e15.7-scripts/AsmBytecodeOracle.java` adaptado para 64 bits): diff só
  na chamada do helper/interop, nenhuma outra instrução emitida muda.
- Suítes de equivalência A64 interpretador × JIT verdes; `mvn -o -pl core verify` verde; JaCoCo sem perda.

## Armadilhas

- `IrOpInterop` (32 bits) NÃO entra: lá o executor tem estado de instância (E15.5) e a lista paralela é necessária.
- `TaskStop` num `mvn` em background deixa o fork do surefire vivo (memória do épico E15).
