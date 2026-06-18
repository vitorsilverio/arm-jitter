*-# ROADMAP — Codegen JVM (problema 3)

Plano incremental para alinhar o runtime ao desenho de `ARQUITETURA.html`: emissão real de
bytecode JVM via ASM, mantendo `InterpretedCodeEmitter` como oráculo de corretude e sem quebrar
integradores que usam `JitRuntimeFactory.interpretedArmThumb(...)`.

**Referência visual:** [ARQUITETURA.html](ARQUITETURA.html)

## Objetivo

| Hoje | Alvo |
|------|------|
| `CompiledBlock` = closure que interpreta `IrOp[]` | `CompiledBlock` = método JVM gerado via ASM |
| `JitRuntime` = cache + lift + interpretação IR | Mesmo pipeline; codegen plugável |
| Ganho = amortizar decode/lift | Ganho = eliminar dispatch por `IrOp` no hot path |

## Princípios

1. **Cada fase é mergeável sozinha** — testes verdes; comportamento padrão inalterado até opt-in.
2. **Semântica única** — `InterpretedCodeEmitter` permanece referência; ASM passa em harness de equivalência.
3. **Compatibilidade retroativa** — `JitRuntime`, `CompiledBlock`, factories `interpreted*` mantidos.
4. **Fallback por `IrOp`** — `AsmCodeEmitter` delega ao interpretado o que ainda não emitir.
5. **Critério de feito** — mesmo estado de CPU e mesmo retorno de ciclos internos que o interpretado.

## Legenda de status

| Símbolo | Significado |
|---------|-------------|
| ✅ | Implementado |
| 🟡 | Parcial / infraestrutura |
| ⬜ | Planejado |

## Visão das fases

```
Fase 0  — Documentação e contratos (CodegenBackend)
Fase 1  — Infra ASM mínima (bloco vazio)          ┐
Fase 2  — Refatorar InterpretedCodeEmitter        ├─ Fase 1 ∥ Fase 2
Fase 3  — Harness de equivalência + GuestToHostMapper
Fase 4  — AsmCodeEmitter ALU mínima
Fase 5a–5f — Expansão por categoria de IrOp
Fase 6  — IrOptimizer (fold, DCE, merge flags)
Fase 7  — Política de fallback e métricas
Fase 8  — Default ASM + deprecations suaves (após cobertura GBA)
```

---

## Fase 0 — Clareza de API ✅

**Objetivo:** eliminar ambiguidade sem breaking change.

| Entrega | Status |
|---------|--------|
| Enum `CodegenBackend` (`INTERPRETED_IR`, `JVM_BYTECODE`) | ✅ |
| `CodeEmitter.backend()` | ✅ |
| `JitRuntime.codegenBackend()` | ✅ |
| Javadoc em `JitRuntime`, `CompiledBlock`, `CodeEmitter` | ✅ |
| README — seção “Backends de execução” | ✅ |
| `ARQUITETURA.html` — legenda implementado / planejado | ✅ |

**Não fazer:** renomear `JitRuntime` (custo sem benefício antes do ASM existir).

**Aceite:** documentação alinhada; zero mudança de comportamento padrão.

---

## Fase 1 — Infraestrutura ASM (bloco vazio) ✅

**Objetivo:** gerar, carregar e invocar classe JVM a partir do pipeline.

| Entrega | Arquivo | Status |
|---------|---------|--------|
| Dependência `org.ow2.asm:asm` | `pom.xml` | ✅ |
| `JvmBlockClassLoader` | `codegen/jvm/JvmBlockClassLoader.java` | ✅ |
| `JvmBlockLoader` | `codegen/jvm/JvmBlockLoader.java` | ✅ |
| `AsmBlockBuilder` | `codegen/jvm/AsmBlockBuilder.java` | ✅ |
| `EmptyAsmCodeEmitter` | `codegen/EmptyAsmCodeEmitter.java` | ✅ |
| Teste de fumaça | `EmptyAsmCodeEmitterTest.java` | ✅ |

**Decisões:**

- Um `JvmBlockClassLoader` por emissor (facilita descarte futuro de classes).
- Assinatura gerada: `static int execute(ArmCore core)`.
- Exceções propagadas sem envolver na Fase 1.

**Aceite:** `emit(irBlock).execute(core)` retorna `0` sem lançar exceção.

---

## Fase 2 — Refatorar `InterpretedCodeEmitter` ✅

**Objetivo:** extrair executores espelháveis pelo ASM (~800 linhas antes).

| Extrair | Arquivo | Status |
|---------|---------|--------|
| `IrExecutionSupport` | `codegen/executor/IrExecutionSupport.java` | ✅ |
| `IrAluExecutor` | `codegen/executor/IrAluExecutor.java` | ✅ |
| `IrMemoryExecutor` | `codegen/executor/IrMemoryExecutor.java` | ✅ |
| `IrBranchExecutor` | `codegen/executor/IrBranchExecutor.java` | ✅ |
| `IrTransferExecutor` | `codegen/executor/IrTransferExecutor.java` | ✅ |
| `IrSystemExecutor` | `codegen/executor/IrSystemExecutor.java` | ✅ |
| `IrCycleExecutor` | `codegen/executor/IrCycleExecutor.java` | ✅ |
| `IrBlockExecutor` | `codegen/executor/IrBlockExecutor.java` | ✅ |

`InterpretedCodeEmitter` delega a `IrBlockExecutor`; comportamento inalterado.

**Aceite:** diff de comportamento = zero.

---

## Fase 3 — Harness de equivalência + `GuestToHostMapper` ✅

| Entrega | Arquivo | Status |
|---------|---------|--------|
| `CpuSnapshot` | `codegen/equivalence/CpuSnapshot.java` | ✅ |
| `BlockEquivalenceHarness` | `codegen/equivalence/BlockEquivalenceHarness.java` | ✅ |
| `BlockEquivalenceTest` (base) | `test/.../equivalence/BlockEquivalenceTest.java` | ✅ |
| `GuestToHostMapper` | `codegen/jvm/GuestToHostMapper.java` | ✅ |
| `FlagEmitter` / `MemAccessEmitter` | `codegen/jvm/` | ✅ |
| `StandardJvmEmitters` | `codegen/jvm/StandardJvmEmitters.java` | ✅ |

**Aceite:** falha clara quando ASM divergir do interpretado (`EquivalenceMismatchException`).

---

## Fase 4 — Primeiro `AsmCodeEmitter` real (ALU mínima) ✅

**Coberto:**

- `IrOp.Alu` — `MOV`, `ADD`, `SUB`, `AND`, `CMP` (condição `AL`, sem flags exceto `CMP`)
- `IrOp.Cycle`, `IrOp.Fetch`
- Demais ops / operandos complexos → fallback interpretado no bloco inteiro

| Entrega | Arquivo | Status |
|---------|---------|--------|
| `AsmCodeEmitter` | `codegen/AsmCodeEmitter.java` | ✅ |
| `AsmBlockCompiler` | `codegen/jvm/AsmBlockCompiler.java` | ✅ |
| `AsmNativePolicy` | `codegen/jvm/AsmNativePolicy.java` | ✅ |
| `AsmRuntimeHelpers` | `codegen/jvm/AsmRuntimeHelpers.java` | ✅ |
| `JitRuntimeFactory.jvmArmThumb` | `jit/JitRuntimeFactory.java` | ✅ |
| Testes de equivalência | `AsmCodeEmitterEquivalenceTest.java` | ✅ |

**Aceite:** harness verde para ALU simples; `interpreted*` continua default.

---

## Fases 5a–5f — Expansão por `IrOp` ✅

Todas as 19 categorias de `IrOp` implementadas com condição `AL`. Fallback para blocos
com condições != AL, `Swap`, `ShiftedRegister` em src2/offset.

| Subfase | `IrOp` | Status |
|---------|--------|--------|
| **5a** | Load, Store, LoadLiteral | ✅ |
| **5b** | Branch, BranchExchange, ThumbBL | ✅ |
| **5c** | Multiply, LongMultiply | ✅ |
| **5d** | MultipleTransfer, Push, Pop | ✅ |
| **5e** | PsrTransfer, Swi, Coprocessor | ✅ |
| **5f** | Undefined, ALU expandido (todos os IrOpCodes) | ✅ |

**Arquivos principais:** `AsmBlockCompiler.java`, `AsmNativePolicy.java`, `AsmRuntimeHelpers.java`.

**Testes de equivalência:** `AsmCodeEmitterEquivalenceTest.java` — 10 testes (Load, Store, Branch, BX, Multiply, LDM + 4 de fase 4).

**235 testes verdes.**

---

## Fase 6 — `IrOptimizer` ⬜

Passes mínimos (nessa ordem):

1. Constant fold em `IrOp.Alu`
2. DCE de ALU morto
3. Merge de sequências `setFlags`

`IrOptimizer.identity()` permanece disponível.

---

## Fase 7 — Política de fallback ⬜

| Entrega | Detalhe |
|---------|---------|
| `AsmCodeEmitter.supportedOps()` | ops emitidas nativamente |
| `AsmFallbackPolicy` | `PER_OP`, `WHOLE_BLOCK`, `FAIL_FAST` |
| Contador de fallbacks | debug / trace |

---

## Fase 8 — API pública honesta (após cobertura GBA > 90%) ⬜

- `JitRuntimeFactory.armThumb(...)` → default JVM bytecode.
- `interpretedArmThumb(...)` → debug / oráculo.
- `@Deprecated` suave em nomes enganosos, se necessário.
- Guia de migração no README.

---

## Riscos

| Risco | Mitigação |
|-------|-----------|
| Metaspace / leak de classes | `JvmBlockClassLoader` por runtime; SMC descarta `CompiledBlock` |
| Divergência ASM vs interpretado | Harness obrigatório em cada PR de codegen |
| Branches no bloco | Fase 5b: `ireturn` antecipado; relift no novo PC |
| Condições ARM | guardas no bytecode ou fallback até 5b+ |

## Fora de escopo

- `GbaAddressSpace` / `Nds*AddressSpace` (emulador hospedeiro).
- Handlers SWI GBA/NDS.
- Thumb-2.

---

## Checklist de execução

- [x] Fase 0 — docs + `CodegenBackend`
- [x] Fase 1 — infra ASM (bloco vazio)
- [x] Fase 2 — refatorar `InterpretedCodeEmitter`
- [x] Fase 3 — harness + `GuestToHostMapper`
- [x] Fase 4 — `AsmCodeEmitter` ALU mínima
- [x] Fase 5a — memória
- [x] Fase 5b — branches
- [x] Fase 5c — multiply
- [x] Fase 5d — LDM/STM
- [x] Fase 5e — PSR/SWI/coprocessor
- [x] Fase 5f — Undefined, ThumbBL, ALU completo
- [ ] Fase 6 — `IrOptimizer`
- [ ] Fase 7 — fallback policy
- [ ] Fase 8 — default ASM

---

## Histórico

| Data | Fase | Notas |
|------|------|-------|
| 2026-06-17 | 5 | Todas as 19 categorias de `IrOp` nativas (cond=AL); 235 testes verdes |
| 2026-06-16 | 4 | `AsmCodeEmitter` ALU nativa + fallback + `jvmArmThumb` factory |
| 2026-06-16 | 3 | Harness de equivalência + `GuestToHostMapper` / emitters JVM |
| 2026-06-16 | 2 | Executores IR em `codegen/executor/`; `InterpretedCodeEmitter` enxuto |
| 2026-06-16 | 0–1 | `CodegenBackend`, infra ASM (`EmptyAsmCodeEmitter`), docs |
| 2026-06-16 | — | ROADMAP criado |
