# C0 (implementação) — Loop-superbloco

**Trilha:** C · **Depende de:** medição C0 ✅ ([RELATORIO-C0-MEDICAO.md](RELATORIO-C0-MEDICAO.md))
**Repo:** arm-jitter (+ validação em ndsemu/gbaemu) · **4 sub-tasks: C0.1 → C0.4, um PR cada**

## Contexto (o que os dados mandaram construir)

96–99% dos saltos de corrente nos 3 jogos são UM loop fechado de 2–4 blocos (idle do
NitroSDK), 100% estável, com as correntes morrendo no budget (~90 saltos/run). Cada
salto paga chamada megamórfica + `core.mode()` + lookup de IC. O loop-superbloco
compila o CICLO inteiro como UM método JVM com o loop interno: ~90 saltos viram 1
chamada. Os membros têm ~4–16 instruções no total — longe do limite onde o C2 degrada
(medido no A0: degradação começa entre 80 e 320 instruções), então o backend ASM serve.

**Decisão registrada: idle-skip fica FORA desta rodada.** É a alternativa de maior
teto (não emular o spin), mas mexe com timing cross-CPU (a classe de bug que quebrou
boots com budget alto). O loop-superbloco preserva EXATAMENTE a semântica e o timing
do chaining atual — é o passo seguro. Idle-skip vira candidato só depois, com o
superbloco como baseline.

## Semântica normativa (o coração da spec — G1 vale aqui)

O superbloco de membros `M0..Mk-1` (M0 = head) é equivalente, POR CONSTRUÇÃO, a esta
sequência do runtime atual: executar M0 e então repetir o corpo do chain loop de
`JitRuntime.execute` enquanto os guards permitirem e o PC cair em um membro. Contrato
`CompiledBlock.execute(core)` inalterado: retorna ciclos INTERNOS acumulados; o
runtime soma via `core.addCycles` (não somar dentro!).

Pseudocódigo do método gerado (`int execute(ArmCore core)`):

```
cycles = 0
gen0 = context.generation()            // BlockCache.generation() na ENTRADA
budget = context.chainCycleBudget()    // lido na entrada (não por iteração)
goto label_M0
label_Mi:                              // um label por membro, i = 0..k-1
    <ops do membro Mi — emitXxx idênticos ao compile() normal, incluindo guards
     condicionais por-op e PC_CHANGED/fixup para endPc(Mi) no fim do SEGMENTO>
    cycles += <ciclos internos do segmento — a contagem existente das ops Cycle>
    // ── guards de iteração, MESMA ordem do while do chain loop ──
    if (cycles >= budget) return cycles
    if (core.sleepState() != RUNNING) return cycles
    if (core.interruptLine()) return cycles
    if (context.generation() != gen0) return cycles
    core.mode()                        // re-sincroniza banking, como o chain loop
    pc = core.programCounter()
    if (pc == startPc(M0)) goto label_M0
    ...
    if (pc == startPc(Mk-1)) goto label_Mk-1
    return cycles                      // saiu do ciclo: devolve ao dispatcher
```

Invariantes (violação = bug, não otimização):

- **S1** — Os guards rodam APÓS CADA membro, na ordem exata do `while` de
  `JitRuntime.execute` (budget → sleep → interrupt → generation). Com o mesmo budget,
  o superbloco para EXATAMENTE onde o chain loop pararia ⇒ granularidade de
  interleave cross-CPU inalterada (é isso que protege os handshakes IPC).
- **S2** — `core.mode()` por iteração, como o chain loop faz.
- **S3** — O primeiro membro SEMPRE executa (mesmo com budget 0) — igual ao IC hit
  atual, que executa o bloco antes do while.
- **S4** — G4 continua: Cycle/Fetch sem guard condicional dentro dos segmentos.
- **S5** — Instrução do membro é a MESMA emissão do `AsmBlockCompiler.compile`
  normal (reusar os `emitXxx`; PROIBIDO reimplementar semântica). Ops PER_OP
  (interop) dentro de membros são permitidas — o interop já checa condição e flags.
- **S6** — Nada de estado mutável compartilhado entre execuções além do core: o
  método é reentrante como os blocos normais.

## Desenho de integração

- **`SuperblockContext`** (novo, `codegen/jvm/`): interface mínima
  `{ long generation(); int chainCycleBudget(); }`, implementada por um adaptador do
  `JitRuntime`/`BlockCache`. A classe gerada ganha UM campo `context`, injetado após
  `newInstance()` (mesmo fluxo do `JvmBlockLoader`). Budget lido na ENTRADA de cada
  execute (S1 usa o valor da entrada — igual ao chain loop, que lê o campo por
  iteração mas ele não muda durante um run; `setChainCycleBudget` entre runs já é
  visível na próxima entrada, então NÃO é preciso derrubar superblocos ao mudá-lo).
- **Registro no cache:** o superbloco SUBSTITUI a entrada do head
  (`BlockCache.put(headKey, superblock, coveringStart, coveringEnd)`) onde o range é
  a ENVOLTÓRIA `[min(startPc dos membros), max(endPc))`. Escrita em QUALQUER página
  da envoltória invalida o superbloco (over-invalidation aceita: seguro e simples).
  **Limite: envoltória ≤ 32 KB** (os loops medidos têm 8–10 KB); acima disso, não
  construir (evita marcar páginas demais no índice de invalidação).
- **Entrada:** nenhuma mudança no dispatcher — o IC do head passa a apontar para o
  superbloco naturalmente (put bumpa a geração uma vez, o IC ressincroniza). Depois
  que o superbloco retorna, o chain loop externo continua normalmente a partir do PC
  de saída (comportamento composicional correto: ele mesmo para no budget).
- **Compilação:** lift dos membros na THREAD DE EMULAÇÃO (regra existente do tiered:
  lift toca memória do guest), `emit` no pool de background (`compileExecutor`),
  integração via fila como `integrateCompiled` — espelhar `submitCompile`.

## Detector (barato, no chain loop)

Opt-in por runtime: `JitRuntime.setLoopSuperblocks(boolean)` (default OFF — G3).
Amostragem: a cada 64ª run de corrente (`(runCounter++ & 63) == 0`), observar os
primeiros 4 saltos: se algum `nextPc == pc-do-head`, o ciclo é
`[head, hop1..hopK-1]` (K ≤ 4). Guardar por head os PCs do ciclo; exigir **8
confirmações consecutivas com o MESMO ciclo** antes de construir. Estruturas
pré-alocadas (int[4] + HashMap pequeno); custo fora da amostra = um incremento e um
teste de máscara. Contadores públicos (como `chainedBlocks`): `superblocksBuilt`,
`superblockRuns` (execuções que entraram por um superbloco).

## Sub-tasks

### C0.1 — Harness lockstep de runtime

`codegen/equivalence/RuntimeLockstepHarness`: dois pares (runtime, core) sobre
memórias espelhadas (reusar `EquivalenceTestSupport`/`CpuSnapshot`), executa
`execute()` alternado e compara o snapshot completo após CADA chamada. Racional: o
superbloco muda a GRANULARIDADE do execute; com S1 valendo e o mesmo budget, os
estados devem bater chamada a chamada. Aceite: harness verde comparando
`armThumb` vs `armThumb` (sanidade) e `armThumb` vs `interpretedArmThumb` num
programa de loop sintético (o ping-pong do `ChainProfilerTest` serve de base).
Limitação documentada: programas SEM SMC (generation divergiria os pontos de parada).

### C0.2 — Detector + contadores (sem emissor)

`setLoopSuperblocks(true)` liga só a DETECÇÃO (constrói a lista de membros e
incrementa `superblockCandidates`; loga um resumo acessível). Aceite: (a) teste
unitário com o ping-pong (ciclo de 2 detectado com os PCs exatos); (b) rodar os 3
jogos no ndsemu (arg novo `superblocks` ao lado de `chainprof`) e confirmar que os
candidatos são EXATAMENTE os loops do relatório de medição; (c) bench sem detector
ligado inalterado; suites + gbaemu verdes.

### C0.3 — Emissor + integração (o PR grande)

**Aprendizados da C0.2 (obrigatórios aqui):**
- O detector promove o MESMO ciclo em até K rotações (um head por bloco do ciclo —
  confirmado no MKDS: o loop dominante virou 4 candidatos). Antes de construir,
  **canonicalizar por rotação** (ex.: rotação que começa no menor PC) e construir UM
  superbloco por ciclo; os outros heads continuam entrando pelo chain loop normal e
  caem no superbloco pelo dispatch interno de PC? NÃO — o dispatch interno só roda
  APÓS um segmento; heads alternativos ficam como blocos normais (aceitável: o loop
  converge para o superbloco do head canônico em 1 iteração).
- Candidatos de 1 bloco (self-loop, ex.: `0214D048` no MKDS) são válidos e valem
  superbloco (viram "bloco que se repete até o budget" — o caso mais simples).
- MKDS gerou 55 candidatos promovidos; o cap de construção deve começar conservador
  (ex.: construir só ciclos com ≥2 promoções/rotações OU os primeiros N) e crescer
  com dado de bench.

Emissor `compileLoop(List<IrBlock> membros, SuperblockContext)` no
`AsmBlockCompiler` (ou classe irmã `AsmLoopCompiler` reusando os `emitXxx` —
decidir lendo o código; reuso dos emitters é obrigatório, S5). Integração:
detecção → lift dos membros → compile em background → `put` no cache com range
envoltório. Validação (nesta ordem):
1. Testes de equivalência de unidade: superbloco de 2 e de 4 membros vs execução
   manual do chain loop (mesmos guards), incluindo saídas por budget, por IRQ
   (`setInterruptLine` no meio), por sleep (membro com SWI Halt), por PC fora do
   ciclo e por generation (invalidação no meio — escrever numa página membro).
2. `RuntimeLockstepHarness` (C0.1): superblocos ON vs OFF, mesmo budget, programa
   sintético — snapshots idênticos por chamada. **Aprendizado da C0.1:** o lockstep
   exige runtimes SÍNCRONOS (clássicos — `jvmArmThumb`/`interpretedArmThumb`); como
   a construção do superbloco é assíncrona, este teste precisa de um gancho de
   construção SÍNCRONA (ex.: método package-private `buildSuperblockNow(headPc)`
   usado só em teste) antes de iniciar a comparação.
3. SMC: teste que escreve sobre um membro e prova que o superbloco morre (não
   executa código velho) — reusar o padrão do `JitRuntimeInlineCacheTest`.
4. Suites arm-jitter + gbaemu + ndsemu (G5).

### Resultado da C0.3 (2026-07-09)

Implementada por **composição INVOKESTATIC** (o superbloco chama os `execute0`
estáticos dos membros — S5 por construção, zero re-emissão) com build SÍNCRONO
(evento raro, ≤64/sessão — desvio deliberado do plano de background). Lockstep
ON×OFF verde (2 e 4 membros, budget 0/40, IRQ, SMC). **Bench A/B (savestates):
MKDS 37,7→43,7 fps (+16%), SM64DS 47,7→68,5 (+44%, 114% realtime), JUS 49,8→69,0
(+39%, 115% realtime).** Meta do épico (≥10%) superada em todos os cenários.

### C0.4 — Validação de jogo + A/B + default

No ndsemu: ligar `setLoopSuperblocks(true)` nos DOIS runtimes (config como o chain
budget). Protocolo literal da task C4: boot FRIO dos 4 jogos (JUS, MKDS, SM64DS,
Platinum) até os pontos conhecidos + ~1min de gameplay + `bench` A/B (600–900
frames com savestate) nos 3 cenários de referência. Meta do épico: ≥10% de fps em
pelo menos um cenário sem NENHUMA regressão de boot/gameplay/áudio. gbaemu: fica
OFF (default), suite verde. Se a meta não vier, registrar o resultado negativo no
relatório e reavaliar idle-skip.

## Armadilhas conhecidas

- O ciclo pode conter membro THUMB e head ARM? O IC diferencia por
  `InstructionSet` na tag; o detector deve guardar (pc, set) por membro e o
  dispatch interno do superbloco só compara PC — **restringir a ciclos 100% do
  mesmo InstructionSet** na C0.2 (os medidos são ARM; simplifica o dispatch).
- Membro com `dst=PC + S` / retorno de exceção muda modo — S2 (`core.mode()`)
  cobre; não "otimizar" o mode() fora.
- NÃO reaproveitar o `IrBlock` de um lift antigo: relift na construção (o código
  pode ter mudado desde o compile original dos membros).
- O put do superbloco derruba o bloco normal do head (generation bump). Se o
  superbloco for invalidado por SMC, o head recompila pelo fluxo normal — NADA de
  caminho especial de recuperação.
- `DivergenceCheckingCodeEmitter` compara por bloco: superblocos DEVEM ficar
  desligados no runtime de divergência (não instalar o flag lá; documentar).
