# C1 — Flags NZCV em locals JVM dentro do bloco compilado

**Trilha:** C (perf) · **Depende de:** — · **Repo:** arm-jitter
**Risco: ALTO (corretude). É a task mais delicada da trilha — siga os invariantes à risca.**

## ⚠️ Prioridade rebaixada (leia antes de executar)

O re-profile de 2026-07-08 (MKDS corrida, savestate) mostrou que os helpers de flags e
os guards de condição NÃO aparecem nas folhas quentes de gameplay — o ganho desta task
isolada tende a ~0 nesses cenários (era candidata da era title-screen). Ela continua
válida como SUBPRODUTO de C0 (superblocos), onde flags em locals atravessando
fronteiras de bloco fazem sentido. **Só execute isolada com A/B bench provando ganho;
sem ganho medido, feche com o resultado negativo registrado.**

## Contexto

Hoje cada op ALU com `setFlags` lê/escreve os flags no objeto CPSR (campo do core) via
chamadas. A ideia: dentro de um bloco compilado, manter N, Z, C, V como **locals JVM**
(4 ints 0/1, alocados via `GuestToHostMapper`), sincronizando com o CPSR só nas
fronteiras.

## Especificação

### Fluxo

1. **Prólogo do bloco:** carregar N/Z/C/V do CPSR para os locals **apenas se** o bloco
   contém alguma leitura de flag antes da primeira definição completa (análise simples:
   na dúvida, carregar sempre — corretude primeiro, poupar o load é otimização posterior).
2. **Dentro do bloco:** ops que setam flags escrevem os locals; ops que leem
   (ADC/SBC/RSC carry-in, RRX, guards condicionais) leem os locals.
3. **Guards condicionais** deixam de chamar `AsmRuntimeHelpers.evalCond(core, ordinal)`
   e passam a avaliar dos locals com bytecode direto. Tabela normativa (implementar
   EXATAMENTE isto):

   | Cond | Expressão | Cond | Expressão |
   |------|-----------|------|-----------|
   | EQ | Z==1 | HI | C==1 && Z==0 |
   | NE | Z==0 | LS | C==0 \|\| Z==1 |
   | CS | C==1 | GE | N==V |
   | CC | C==0 | LT | N!=V |
   | MI | N==1 | GT | Z==0 && N==V |
   | PL | N==0 | LE | Z==1 \|\| N!=V |
   | VS | V==1 | AL | sempre |
   | VC | V==0 | | |

### Invariantes de sincronização (a lista que NÃO pode falhar)

**FLUSH locals→CPSR obrigatório ANTES de:**
- F1. Qualquer `INVOKESTATIC IrOpInterop.*` (fallback PER_OP — o interpretado lê CPSR).
- F2. Qualquer saída do bloco (`ireturn`, incluindo saídas antecipadas de branch).
- F3. `IrOp.Swi` (handler/entrada de exceção lê e salva CPSR em SPSR).
- F4. `IrOp.PsrTransfer` MRS (lê CPSR) — flush antes; MSR (escreve CPSR) — flush antes
  E **RELOAD depois** (o MSR pode ter alterado NZCV).
- F5. Qualquer helper que receba `core` e possa ler CPSR (auditar TODOS os helpers
  usados pelo compilador um a um; listar no PR quais leem).
- F6. Entrada de exceção por instrução (undefined, aborts futuros).

**RELOAD CPSR→locals obrigatório DEPOIS de:** F1 (interop pode ter setado flags),
F4-MSR, F3.

### Implementação

- `GuestToHostMapper`: reservar 4 locals novos (nomeá-los; G6).
- `FlagEmitter` (codegen/jvm) é onde os acessos a flag são emitidos hoje — a mudança
  concentra-se ali + prólogo/epílogo no `AsmBlockCompiler`.
- Fazer em 2 PRs: (1) locals + flush/reload conservador em TODA fronteira, guards
  ainda via CPSR→ mais lento mas correto — validar; (2) guards dos locals + eliminar
  flush/reload provadamente desnecessários.

## Validação (obrigatória, nesta ordem)

1. Testes exaustivos existentes de condição (14 cond × 16 NZCV) — devem passar sem edição.
2. Teste NOVO: bloco com op PER_OP no meio de sequência que seta/lê flags (cobre F1).
3. Teste NOVO: MSR alterando NZCV no meio do bloco, op seguinte lê carry (cobre F4).
4. `mvn test` arm-jitter + gbaemu + ndsemu.
5. Divergence-check longo (usuário roda): FireRed gbaemu + JUS ndsemu, zero divergências.
6. Bench antes/depois (ndsemu `bench`); publicar números no PR.

## Armadilhas

- O flag C participa de RRX e do shifter — qualquer emissão que hoje leia carry do
  CPSR precisa migrar JUNTO, senão lê valor velho.
- `Cycle`/`Fetch` não tocam flags — não adicionar flush neles (G4 continua).
- Blocos com CHAINING saltam para outro bloco compilado: o flush F2 na saída cobre
  isso (o próximo bloco faz o próprio prólogo) — confirmar que o chaining sai por um
  caminho que executa o flush.
