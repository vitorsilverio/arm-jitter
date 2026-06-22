# arm-jitter — Plano: execução condicional nativa no JIT ASM

**Objetivo.** Parar o JIT ASM de cair pro interpretador em instruções ARM **condicionais** (cond ≠ `AL`), para o JUS (e qualquer código ARMv5 real) rodar de fato compilado.

**Diagnóstico (sessão 2026-06-22, fechado).** `JitRuntimeFactory.armThumb` (a factory de produção do backend ASM do ndsemu) usa `AsmFallbackPolicy.WHOLE_BLOCK` → **qualquer op não-suportado derruba o bloco INTEIRO pro interpretador**. E `AsmNativePolicy` rejeita **toda op com condição ≠ AL**. Como ARM usa predicação condicional o tempo todo (`MOVEQ`, `ADDNE`, `LDRGT`…), quase todo bloco real tem ≥1 op condicional → quase tudo cai. Prova: JFR do JUS com `asm` mostra `IrBlockExecutor.execute` (interp) = 3248 samples vs código compilado `JitRuntime.execute` = 156 → **~95% do JUS ainda interpretado mesmo com o JIT ligado** (só +6% fps; o +31% antigo era fase CPU-bound onde menos blocos têm condicional/DSP). Antes atribuíamos o "+5% na fase de vídeo" ao render — errado: render é ~2%; a causa é o fallback.

**Veredito de complexidade: BAIXA-MÉDIA.** O interpretador já tem o padrão a espelhar: no topo de cada executor (`IrAluExecutor.execute` etc.) faz `if (!core.cpsr().evalCond(op.condition())) return false;`. O JIT precisa do equivalente em bytecode: um **guard por op** (`evalCond ? <op> : pula`). Cada `emitXxx` do `AsmBlockCompiler` já deixa a pilha JVM vazia no fim, então envolver com `IFEQ label` é trivial (o `ClassWriter` usa `COMPUTE_FRAMES`, que recalcula os stack-map frames no merge). O grosso do esforço é **validação**, não implementação.

---

## Mecanismo exato

### 1. Helper de avaliação de condição
Em `codegen/jvm/AsmRuntimeHelpers.java`:
```java
private static final Condition[] CONDITIONS = Condition.values();
public static boolean evalCond(ArmCore core, int ordinal) {
    return core.cpsr().evalCond(CONDITIONS[ordinal]);
}
```
(Mesmo método `cpsr().evalCond` do interpretador ⇒ idêntico por construção. A JVM inlina.)

### 2. Guard por op em `AsmBlockCompiler.compile(...)`
No loop `for (IrOp op : block.operations())` (linha ~106), antes de `switch (op)`:
```java
Condition cond = conditionOf(op);           // novo helper; Cycle/Fetch -> AL
Label condSkip = null;
boolean guard = cond != Condition.AL && !(perOpFallback && !AsmNativePolicy.supports(op));
if (guard) {
    condSkip = new Label();
    method.visitVarInsn(ALOAD, CORE_LOCAL);
    AsmBytecode.visitIntConst(method, cond.ordinal());
    AsmBytecode.invokeStatic(method, HELPERS, "evalCond", "(" + CORE_REF + "I)Z");
    method.visitJumpInsn(IFEQ, condSkip);
}
// ... switch(op) { emitXxx... } (inalterado) ...
if (guard) method.visitLabel(condSkip);
```
`conditionOf(IrOp)`: um `switch` que retorna `op.condition()` para os subtipos que têm; `Cycle`/`Fetch` → `Condition.AL`.

### 3. Relaxar `codegen/jvm/AsmNativePolicy.java`
Remover **só** a exigência `condition() == Condition.AL` de: `Alu` (via `supportsAlu`), `Multiply`, `LongMultiply`, `Load`, `Store`, `LoadLiteral`, `MultipleTransfer`, `Branch`, `Push`, `Pop`, `PsrTransfer`, `Swi`, `Coprocessor`, `Undefined`, `ThumbBlPrefix`, `ThumbBlSuffix`.
**MANTER** as rejeições não-relacionadas a condição: `ShiftedRegister` (Alu src2 e Load/Store offset), shift+setFlags, `dst==15 && setFlags`, **BLX** (`BranchExchange.link`, `ThumbBlSuffix.exchange`), `Saturating`, `DspMultiply`, `DoubleTransfer`, `Swap`.

---

## Invariantes de correção (NÃO esquecer)
1. **`Cycle`/`Fetch` NUNCA recebem guard.** Uma instrução com condição falsa ainda consome o ciclo S + o fetch. São ops AL separados; o interpretador os roda incondicionalmente — o JIT também.
2. **Guard por op, independente** — espelha o `evalCond` por-op do interpretador. Se uma instrução virar 2 ops condicionais e o 1º setar flags, ambos reavaliam (igual ao interp).
3. **Op pulado não toca `PC_CHANGED`** → `emitProgramCounterFixup` põe PC=endPc (sequencial) ⇒ idêntico ao `return false` do interpretador.
4. **Pilha vazia no merge** — cada `emitXxx` termina com a pilha vazia (verificado); o `IFEQ→label` é stack-consistente.
5. **Caminho PER_OP (DSP etc.) NÃO recebe guard** — `IrOpInterop.executeInterpreted`→`executeOp`→executor já checa a condição internamente. Só os ops emitidos NATIVAMENTE recebem guard.

---

## Tarefas (ordem)
1. **Medir primeiro (confirmar a premissa).** Instrumentação temporária em `AsmNativePolicy.supports(IrBlock)`: tally do 1º op que reprova (por kind / "non-AL"). Rodar `Main roms/JUS.nds 1500 asm` e imprimir agregado (quantos blocos caem, % por motivo, % que são *só* condicional → viram compiláveis). Remover instrumentação depois. **Meta: confirmar condicional como #1 e quantificar o ganho potencial.**
2. **`evalCond` helper** + teste unitário.
3. **Guard + `conditionOf`** no `AsmBlockCompiler`.
4. **Relaxar `AsmNativePolicy`** (só condições).
5. **Validar (o rigor):**
   - **`asmcheck`** = `Main roms/JUS.nds N asmcheck` (`DivergenceCheckingCodeEmitter`: roda ASM contra o interp no ROM real e aborta na 1ª divergência) — o teste matador p/ correção de condicional em código real. Rodar bastante (≥1500).
   - Estender `AsmCodeEmitterEquivalenceTest` com variantes condicionais de cada op (cond verdadeira E falsa) para cada código de condição (EQ/NE/CS/CC/MI/PL/VS/VC/HI/LS/GE/LT/GT/LE), afirmando JIT≡interp.
   - `mvn test` em arm-jitter (334+), **gbaemu (215 — DEVE seguir verde; ARMv4T também tem execução condicional, então gbaemu compila mais e é o guarda de regressão)**, ndsemu (138). `mvn install` no arm-jitter antes do ndsemu.
6. **Medir o ganho:** `Main roms/JUS.nds 1500 asm bench` antes/depois + re-profile JFR (esperado: share do `IrBlockExecutor` cai, `JitRuntime.execute` sobe, fps sobe). Re-rodar a medição da tarefa 1 p/ confirmar a queda do fallback%.
7. **(Complemento, mesma sessão) trocar a factory ASM do ndsemu p/ `PER_OP`** (`JitRuntimeFactory.armThumb` linha ~50 usa `WHOLE_BLOCK`): assim um bloco com um op ainda-não-suportado (DSP) compila o resto nativo em vez de cair inteiro — multiplica o ganho do condicional na fase de vídeo. Validar com asmcheck + bench. (Cuidado: medir; PER_OP adiciona o custo de `IrOpInterop` por op não-suportado — só compensa se o resto do bloco for grande.)

---

## Riscos / notas
- **Identidade do `evalCond`**: o helper chama o MESMO `cpsr().evalCond` ⇒ correto por construção.
- **Custo do guard**: ~3 bytecodes + 1 chamada estática (inlinável) por op condicional; só em ops condicionais; AL fica como hoje (sem regressão). Muito mais barato que o fallback de bloco inteiro.
- **gbaemu** fica no INTERPRETED por padrão (problema de input-cut por GC de classloading) — sem mudança p/ o usuário; mas os testes devem seguir verdes.

## Critérios de sucesso
- `asmcheck` no JUS: **zero divergências** num run longo.
- 3 suites verdes (arm-jitter / gbaemu / ndsemu).
- fallback-block% do JUS cai bastante (medição tarefa 1, antes/depois).
- `asm bench` do JUS sobe vs. o baseline de +6% (alvo: aproximar do +31% CPU-bound).

## Próximo item depois deste (fase de vídeo especificamente)
**DSP multiplies nativos** (`IrOp.DspMultiply` — SMUL/SMLA halfword): o Mobiclip é feito disso, é o que sobra na fase de vídeo após o condicional. Maior que o condicional por-op (precisa emitir as variantes halfword/word + flag Q), mas delimitado. Depois: `Saturating` (QADD/QSUB), `DoubleTransfer` (LDRD/STRD), `BLX`.
