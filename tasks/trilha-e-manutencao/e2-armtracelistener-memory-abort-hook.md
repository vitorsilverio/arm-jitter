# E2 — `ArmTraceListener#onMemoryAbort`: fecha a lacuna de observabilidade sob `runBlocks`/JIT

**Depende de**: — (aditivo, G3)

**Fecha**: achado da sessão extra de debug da task `F3` (`virtual-arm-box`) — ver
`tasks/FILA-EXECUCAO.md`, entrada "F3 — sessão extra".

## Contexto

`ArmTraceListener#beforeInstruction`/`afterInstruction` só disparam sob `ArmCore#step()`
(interpretador frio, instrução a instrução). Sob `ArmCore#runBlocks(JitRuntime, int)` — o caminho
real usado por qualquer console/máquina de produção (`Bcm2835Machine#runSlice()` etc.) — blocos
inteiros são executados atomicamente, por desenho (desempenho): nenhum evento por-instrução
dispara nem no bloco interpretado (`IrBlockExecutor#execute`) nem no bloco compilado
(`AsmBlockCompiler`/JIT). Isso criou uma lacuna real durante a investigação do bug de boot do
`raspi1ap` na F3: um `Oops` do kernel guest já aparecia impresso no console ANTES de qualquer
instrução observada pelo `ArmTraceListener` atingir o vetor de abort — o texto era genuíno, mas
não havia como correlacionar com a instrução exata que disparou a falha de memória real.

## O que foi adicionado

Um único gancho novo, aditivo (G3): `ArmTraceListener#onMemoryAbort(ArmCore, int
instructionAddress, MemoryTranslationException)`, chamado no início de `ArmCore#enterMemoryAbort`
— o ÚNICO ponto de convergência dos três caminhos de execução (`step()`, bloco interpretado via
`IrBlockExecutor`, bloco compilado via `AsmBlockCompiler`/JIT), já que os três convertem uma
`MemoryTranslationException` em `PREFETCH_ABORT`/`DATA_ABORT` passando pelo mesmo método, com o PC
exato da instrução faltosa (`IrBlockExecutor#ownerInstructionAddress` já calculava esse PC
corretamente antes desta task — só não havia como observá-lo de fora).

Não força a execução inteira a rodar via `step()` só para ganhar observabilidade (o que mudaria o
timing/desempenho da investigação) — o gancho funciona sob o backend `JIT` de produção sem
nenhuma mudança de comportamento quando nenhum listener está instalado
(`ArmTraceListener.none()`, custo zero).

## Validação

`ArmCoreMemoryAbortTest` ganhou 3 testes novos provando o mesmo PC exato de falta reportado nos
três caminhos (`step()`, bloco interpretado `JitRuntimeFactory.interpretedArmThumb`, bloco
compilado `JitRuntimeFactory.armThumb` com `hotThreshold=1`).

## Aceite

- [x] `ArmTraceListener.onMemoryAbort` novo, `default` vazio (G3).
- [x] `ArmCore.enterMemoryAbort` chama o gancho ANTES de mutar PC/CPSR/banco de registradores.
- [x] Testes provando paridade do PC exato nos 3 caminhos de execução.
- [x] `mvn -o test` verde (core), G5 revalidado (gbaemu/ndsemu/armbox).
