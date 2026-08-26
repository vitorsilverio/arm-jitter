# E7 — A64 JIT: 2 bugs reais faziam exceções de GUEST escaparem para o HOST

**Trilha:** E (manutenção) · **Depende de:** — · **Repo:** arm-jitter

Sem spec formal prévia — achada e corrigida na mesma sessão (mesmo padrão de `E4`), durante a
retomada da task `F11` (`virtual-arm-box`) depois que `B6.14` fechou o sétimo bloqueio antigo do
boot. Commit direto.

## Contexto

A F11 retomou o boot do `raspi3-64` com o fix da `B6.14` instalado. O backend INTERPRETED passou a
rodar 10 minutos sem lançar nenhuma exceção; o backend JIT divergia em ~1s com
`MemoryTranslationException64: TRANSLATION_FAULT_L3 em 0x200 (DATA_READ)` não capturada, escapando
até o topo do teste. Investigando essa divergência (candidata "(b)" deixada pela sessão 7 da F11),
apareceram **2 bugs reais independentes** no mundo A64 (`core64`/`jit64`/`codegen64`), nenhum deles
específico desta máquina — os dois afetam QUALQUER consumidor que rode blocos A64 no backend JIT.

## Achado 1 — `Ir64BlockCompiler` nunca cercava o bloco nativo com `try/catch`

O precedente 32-bit (`AsmBlockCompiler#compile`, desde `B4.1.3`) cerca o método gerado inteiro com
um `try/catch` de `MemoryTranslationException`, convertendo a falta numa entrada de exceção real do
guest (`core.enterMemoryAbort`) antes de devolver os ciclos parciais. O `Ir64BlockCompiler` (A64,
`B6.4`) nunca teve o equivalente — um bloco A64 promovido a nativo que lançasse
`MemoryTranslationException64` (`Load64`/`Store64`/`LoadStorePair`/`LoadLiteral64`/
`LoadExclusive`/`StoreExclusive`/o próprio `Fetch`) ou qualquer uma das outras 4 exceções de
controle do A64 (`Aarch64BreakpointException`/`Aarch64UndefinedInstructionException`/
`Aarch64HypervisorCallException`/`Aarch64SecureMonitorCallException`) deixava a exceção escapar do
bloco compilado direto para o host — bug latente em TODO bloco A64 quente, não só neste boot
(confirmado que "todo `Ir64Op.Kind` existente hoje é suportado nativamente", `Ir64NativePolicy`).

**Fix**: `Ir64BlockCompiler` agora cerca o corpo do bloco com 5 `try/catch` (bytecode ASM,
`visitTryCatchBlock`), um por tipo de exceção, espelhando EXATAMENTE os 5 `catch` de
`Ir64BlockExecutor#executeBlock` (G1: interpretador é o oráculo). Ciclos deixaram de ser uma
constante de compilação somada num `int` do compilador (`totalCycles`) e passaram a ser acumulados
em TEMPO DE EXECUÇÃO num local JVM (`LOCAL_CYCLES`) — precisa sobreviver a um `catch` no meio do
bloco, devolvendo só os ciclos das instruções que rodaram ANTES da falta (mesma semântica do
interpretador). Endereço da instrução faltosa rastreado por um local `long` (`LOCAL_FAULT_PC`,
regravado a cada `Fetch` com o endereço — constante de compilação, `LDC2_W`+`LSTORE`), inicializado
ANTES do `tryStart` pelo mesmo motivo do precedente 32-bit (o verificador da JVM trata os handlers
como alcançáveis a partir de QUALQUER bytecode dentro do range protegido, inclusive o primeiro
`Fetch`).

Teste de regressão: `Ir64BlockCompilerMemoryAbortTest` — lifta+compila nativamente um bloco
`MOVZ`+`LDR` onde o `LDR` falta (mesmo layout de página de `Aarch64MemoryAbortTest`), executa o
bloco COMPILADO (nunca passa por `Ir64BlockExecutor#step`) e confirma que entra no handler EL1 do
guest com `ESR`/`FAR`/`ELR` corretos, em vez de lançar. Confirmado FALHANDO sem o fix (mesma
exceção que travava o F11) e passando com ele.

## Achado 2 — `JitRuntime64#execute`: `lift()` de um bloco quente sem proteção nenhuma

Este é o que efetivamente causava `TRANSLATION_FAULT_L3 em 0x200` no boot real (stack trace
confirmado): `StandardIr64BlockLifter#lift`, ao decidir onde um bloco QUENTE termina, decodifica
instruções À FRENTE do PC atual (até `maxBlockInstructions`, reto — sem nenhum desvio visto ainda).
Esse lookahead pode cruzar para uma página de código SEM descritor de página válido — memória que a
execução real talvez NUNCA alcance (um desvio poderia ter ocorrido antes). `JitRuntime64#execute`
chamava `lifter.lift(...)` fora de qualquer `try/catch`, então essa falta (que não é uma falta que o
guest de fato "encontrou" — é só o compilador olhando à frente demais) escapava do runtime inteiro.

Isto é o MESMO achado que `JitRuntime#LIFT_FAULT_CYCLES` já documenta no precedente 32-bit desde a
task `B4.1.5` — nunca tinha sido portado para o mundo A64.

**Fix**: `JitRuntime64#execute` cerca a chamada a `lifter.lift(...)` (caminho "clássico", sem
tiering — `jit64` não tem tiering, D0 de `b6.4-aarch64-asm-backend.md`) com um `try/catch` de
`MemoryTranslationException64`; no `catch`, chama `core.enterMemoryAbort(pc, fault)` (`pc` é o
endereço real que o guest ia executar agora — a PRIMEIRA instrução do bloco, sempre válida de
atribuir a falta) e devolve `LIFT_FAULT_CYCLES = 1` (mesmo valor/mesmo raciocínio do precedente
32-bit — "uma instrução consumiu um ciclo").

Teste de regressão: `JitRuntime64LiftFaultTest` — mapeia identidade só a página `[0x000,0x1000)`
com `MOVZ` (nunca terminal) preenchendo-a inteira, `maxBlockInstructions=2000` (bem além da
capacidade da página), `ExecutionThreshold(1)` (compila na primeira execução). `runtime.execute(0,
core)` não deve lançar, deve entrar em EL1 e devolver `LIFT_FAULT_CYCLES`. Confirmado FALHANDO sem
o fix (mesma exceção do boot real, mesmo endereço de falta na borda da página) e passando com ele.

## Validação

`mvn -o test` verde no `arm-jitter` (core+truffle, todos os testes existentes + os 2 novos) +
`mvn -o install` local. G5 revalidado nos 5 consumidores (gbaemu/ndsemu/virtual-arm-box/n3dsemu/
armbox), todos verdes (armbox com a falha pré-existente `Armv7TortureTest`/`VfpRegisters`, não
relacionada, já documentada em sessões anteriores).

**Efeito medido no F11** (contra este `arm-jitter` local, com os 2 fixes — ver
`virtual-arm-box/.../Raspi364BootTest.java`, sessão 8): o backend JIT deixa de lançar QUALQUER
exceção e roda os 2.000.000 de fatias do orçamento do teste (114s) sem crashar — mas, como o
INTERPRETED, não alcança `EARLYCON_BANNER` dentro do orçamento (mesmo bloqueio "lento vs. preso",
não isolado). **Este fix NÃO foi publicado no Maven Central nesta sessão** (`virtual-arm-box`
continua em `1.1.0`, decisão consciente — publicar é ação externa/irreversível, fica para sessão
dedicada de F5/F7, mesmo padrão de E3/E4/B6.14).

## Não inclui

- Não investiga a frente (a) da F11 (lento vs. preso, ambos os backends) — candidata a sessão
  própria, independente deste achado.
- Não publica release novo no Maven Central nem atualiza consumidores (F5/F7).
