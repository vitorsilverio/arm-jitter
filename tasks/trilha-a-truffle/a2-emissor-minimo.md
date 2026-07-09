# A2 — TruffleCodeEmitter mínimo (ALU)

**Trilha:** A · **Depende de:** A0 (decisão DSL/AST), A1 (módulo) · **Repo:** arm-jitter

## Contexto

Replay da fase 4 do codegen JVM (ver histórico no git: `AsmCodeEmitter` inicial): um
emissor novo que cobre um subconjunto mínimo com fallback total para o interpretado.
O desenho de A0 (Bytecode DSL ou AST) define o "como"; esta task define o "o quê".

## Objetivo

`TruffleCodeEmitter` no módulo `arm-jitter-truffle` que compila blocos contendo apenas
ALU simples, `Cycle` e `Fetch`, caindo para o interpretado em qualquer outro caso.

## Especificação

1. **No core** (única mudança no core): adicionar `TRUFFLE` ao enum
   `codegen/CodegenBackend.java`, com javadoc `///` explicando que a implementação vive
   em `arm-jitter-truffle`.
2. **No módulo truffle**: classe `TruffleCodeEmitter implements CodeEmitter`:
   - Construtor `(ArmArchitecture architecture)` — espelhar `AsmCodeEmitter`.
   - `backend()` → `CodegenBackend.TRUFFLE`.
   - `emit(IrBlock block)`:
     - Se TODAS as ops do bloco são suportadas → construir a árvore/bytecode Truffle e
       devolver um `CompiledBlock` que faz `callTarget.call(core)` e retorna os ciclos.
     - Caso contrário → delegar o bloco INTEIRO a `new InterpretedCodeEmitter(architecture)`
       (fallback WHOLE_BLOCK; PER_OP é A3+ se fizer sentido).
   - Suportado nesta task: `IrOp.Cycle`, `IrOp.Fetch`, e `IrOp.Alu` com
     `condition() == AL`, opcodes MOV/ADD/SUB/AND/CMP, operandos `Register`/`Immediate`
     (sem `ShiftedRegister`), `dst != 15`.
3. **Semântica**: NÃO reimplementar semântica de flags/ALU. Reusar os mesmos executores
   (`codegen/executor/IrAluExecutor` etc.) ou os mesmos helpers estáticos que o backend
   ASM usa (`AsmRuntimeHelpers`) atrás de `@TruffleBoundary`/nós — a fonte de verdade é
   uma só (invariante G1). Otimizar chamadas depois, em A3/A4, com dados.
4. Contadores `nativeBlockCount()`/`fallbackBlockCount()` como no `AsmCodeEmitter`.

## Não fazer

- NÃO adicionar dependência Truffle no core (só o valor de enum).
- NÃO cobrir condições != AL, memória, branches (é A3).
- NÃO criar factory pública em `JitRuntimeFactory` (é A4).

## Aceite

1. Teste no módulo truffle usando `BlockEquivalenceHarness` (está no core, em
   `codegen/equivalence/`) comparando `TruffleCodeEmitter` vs `InterpretedCodeEmitter`
   para: bloco ALU puro, bloco com op não suportada (deve cair no fallback e continuar
   equivalente), bloco vazio.
2. Espelhar os casos de teste de `AsmCodeEmitterEquivalenceTest` da fase 4 (ver git).
3. `mvn test` verde na raiz (core + truffle).

## Armadilhas

- `CompiledBlock.execute(core)` retorna ciclos INTERNOS do bloco — copie o contrato
  exato do javadoc de `CompiledBlock` e dos emitters existentes.
- O harness falha com `EquivalenceMismatchException` descritiva — se falhar, o bug está
  no emissor novo, nunca "ajuste" o interpretador para passar.
- Truffle em JVM sem Graal roda interpretado — os testes de equivalência valem mesmo
  assim (corretude não depende de compilação).
