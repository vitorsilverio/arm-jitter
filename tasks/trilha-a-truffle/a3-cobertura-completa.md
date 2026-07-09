# A3 — Cobertura completa de IrOp no backend Truffle

**Trilha:** A · **Depende de:** A2 · **Repo:** arm-jitter

## Contexto

Expandir o `TruffleCodeEmitter` até a paridade com o backend ASM. A ordem que funcionou
no ASM (fases 5a–5f do antigo ROADMAP, ver git) deve ser repetida — **um PR por
categoria**, cada um mergeável sozinho.

## Objetivo

Todas as categorias de `IrOp` executando no backend Truffle, com condição != AL
suportada, validado por divergence-checking com ROM real.

## Especificação

Ordem dos PRs (cada um = decoder de suporte já existe; só emissão Truffle + testes):

| PR | Categorias | Espelhar testes de |
|----|-----------|--------------------|
| 1 | `Load`, `Store`, `LoadLiteral` | fase 5a do ASM |
| 2 | `Branch`, `BranchExchange`, `ThumbBlPrefix/Suffix` | fase 5b |
| 3 | `Multiply`, `LongMultiply` | fase 5c |
| 4 | `MultipleTransfer`, `Push`, `Pop` | fase 5d |
| 5 | `PsrTransfer`, `Swi`, `Coprocessor` | fase 5e |
| 6 | `Undefined`, ALU completo (todos os `IrOpCode`), `ShiftedRegister` | fase 5f |
| 7 | Condições != AL (guard por op) + ops ARMv5TE (DSP/Saturating/LDRD-STRD/Swap) | plano condicional (git) |

Regras herdadas do ASM que valem aqui:

- **G4:** `Cycle`/`Fetch` nunca recebem guard condicional.
- Op com condição falsa não altera PC — o fixup de PC sequencial deve ser idêntico ao
  do interpretador (`return false` do executor).
- A avaliação de condição usa o MESMO `cpsr().evalCond(...)` do interpretador.

## Aceite

1. Por PR: testes de equivalência da categoria (verdadeiro E falso para cada condição
   nas ops condicionais — existe teste exaustivo 14 condições × 16 NZCV no ASM; reusar
   a técnica).
2. Ao final: criar `divergenceCheckingTruffleArmThumb(...)` interno de teste (mesmo
   desenho de `JitRuntimeFactory.divergenceCheckingArmThumb`) e rodar um ROM real
   longo — FireRed no gbaemu ou JUS no ndsemu (`asmcheck`-like) — com **zero
   divergências**. Este run é executado pelo usuário; forneça as instruções exatas.
3. `fallbackBlockCount()` ≈ 0 nos ROMs de referência.

## Armadilhas

- `Swap` e writeback de `MultipleTransfer` têm quirks por arquitetura (`ArmFeature.
  LDM_WRITEBACK_BASE_IN_LIST`, `EMPTY_RLIST_NO_TRANSFER`, STM base-na-lista v4 vs v5).
  NUNCA hardcode o comportamento: consulte `architecture.has(feature)` como os
  executores fazem.
- Loads desalinhados têm rotação específica do ARM7TDMI — reuse o executor, não
  reimplemente.
- `@TruffleBoundary` em chamadas para código não-parcialmente-avaliável (ex.:
  `AddressSpace` do hospedeiro), senão a compilação Truffle explode ou deoptimiza.
