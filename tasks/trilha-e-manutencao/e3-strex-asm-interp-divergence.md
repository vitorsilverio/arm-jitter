# E3 — `StoreExclusive` (STREX): divergência real ASM×interpretador

**Depende de**: — (bug pré-existente, revelado por G4.1/n3dsemu, sem relação de dependência real)
**Repo:** arm-jitter · Task de INVESTIGAÇÃO — o objetivo é isolar a causa raiz e decidir o fix,
não necessariamente aplicá-lo na mesma sessão.

## Contexto

Validando a G4 do n3dsemu (`tasks/trilha-g-3ds/g4-vulkan-apresentacao.md`), uma sessão corrigiu 2
bugs reais do n3dsemu (`APT:S`/`APT:A` como alias de `APT:U`; framebuffer lido da memória
compartilhada do `gsp`, ver commit `f36a6aa` do n3dsemu) que fazem o boot avançar bem mais longe
do que antes. Isso expôs um bug PRÉ-EXISTENTE do `arm-jitter`, nunca alcançado antes: `mvn -o
test` do n3dsemu, que antes passava 135/135, agora falha em 2 testes
(`Application3dsxTest.alocaOsDoisHeapsComSucessoNosTresBackendsSemSvcBreak` e
`.passaDeSrvInitEAlcancaSvcSendSyncRequestNosTresBackends`) com
`EquivalenceMismatchException` do `BlockEquivalenceHarness`/`DivergenceCheckingCodeEmitter` (G1:
"o interpretador é o oráculo").

**Confirmado que é bug pré-existente, não introduzido pelo fix do n3dsemu**: rodando os mesmos
testes com `git stash` (revertendo os 2 fixes do n3dsemu) eles passam — mas isso só prova que o
código antes NUNCA CHEGAVA a este bloco, não que o `arm-jitter` estivesse correto ali. O bloco em
si (`block@0x106980`, dentro de `srvInit`/inicialização de sessão do 3DS real, no preset
`ARM11_MPCORE`) não foi tocado por nenhuma mudança do n3dsemu.

Divergência observada (registradores após o bloco, formato `[r0..r15]` — conferir contra
`CpuSnapshot`/`ArmCore` a ordem exata):

```
reference (interpretador) = [...,  0,   0, ...]  (posições 2 e 3)
candidate  (ASM nativo)   = [...,  1,   1, ...]
```

Bloco (IR, já decodificado — ver saída completa do teste para os operandos exatos):

```
Alu[MOV dst=1, imm=0]
StoreExclusive[dst=2, src=3, base=0, offset=0, sizeBytes=4]
Alu[UXTB dst=3, src=Register(2)]
Alu[CMP dst=0, src1=3, imm=0, setFlags=true]
Branch[target=<retry>, condition=NE]
```

Padrão clássico de **spinlock/retry com LDREX/STREX** (`STREX r2, r3, [r0]`; `UXTB r3, r2`; `CMP
r3, #0`; `BNE retry` — o resultado de STREX, 0=sucesso/1=falha, decide se tenta de novo). O
interpretador registra sucesso (r2=0) onde o ASM nativo registra falha (r2=1) — ou vice-versa,
conferir o snapshot exato — no MESMO bloco, MESMO estado de entrada (senão o harness de
equivalência não teria como comparar). Isso aponta para o **monitor de exclusividade** (estado
que STREX consulta para decidir sucesso/falha) divergindo entre os dois backends — não para o
LDREX/STREX em si terem semântica errada isoladamente (ambos "parecem" implementados desde muito
antes, ver `arm-jitter UNALIGNED_ACCESS` na memória do projeto para um bug irmão, já corrigido,
de decomposição de LDR/STR alinhado sob ARMv6K+).

## Objetivo

Isolar por que o monitor de exclusividade (ou o que quer que decida o resultado de `STREX`)
diverge entre o interpretador (`InterpretedCodeEmitter`, oráculo por G1) e o backend ASM nativo,
neste bloco específico, e decidir o fix.

## Inclui

1. Reproduzir isolado: escrever um teste mínimo no `arm-jitter` (fora do n3dsemu) que execute
   `LDREX`/`STREX` num padrão de retry como o do bloco acima, sob `ARM11_MPCORE`/ARMv6K, nos dois
   backends, e capture a mesma divergência — sem depender do n3dsemu/boot do 3DS para reproduzir.
2. Inspecionar a implementação do monitor de exclusividade em cada backend (`InterpretedCodeEmitter`
   vs. o codegen ASM nativo — buscar por `LDREX`/`STREX`/`ExclusiveMonitor` no código) e comparar
   contra ARM DDI 0406C (semântica de monitor local/global, granularidade da região marcada).
3. Determinar se a divergência é de **estado** (um backend não limpa/seta o monitor no mesmo ponto
   que o outro — ex. um `MOV`/`CMP` entre LDREX e STREX que deveria ou não invalidar o monitor) ou
   de **valor comparado** (STREX comparando o endereço/tag errado).
4. Documentar a causa raiz com evidência (não suposição) e recomendar o fix.
5. Se o fix for pequeno e óbvio (mesmo padrão de outros bugs já corrigidos nesta trilha), aplicar
   com teste de regressão novo no `arm-jitter` (`BlockEquivalenceHarness`, mesmo padrão de G1).

## NÃO inclui (não fazer)

- Não é obrigatório fechar via boot completo do n3dsemu — o teste mínimo isolado (item 1) é o
  aceite real desta task.
- Não mexer no n3dsemu além de, se necessário, confirmar que os 2 testes voltam a passar depois
  do fix (G5: consumidores continuam verdes).
- Se a causa raiz não for óbvia dentro do orçamento normal de sessão (~60-80 tool calls, mesma
  disciplina do `tasks/FILA-EXECUCAO.md`), PARE, documente o que foi descartado/aprendido, e
  devolva — não é obrigatório corrigir tudo numa sessão só (mesmo padrão da investigação de F3).

## Validação

`mvn -o test` verde no `arm-jitter` (core + truffle) com o teste de regressão novo. Depois,
`mvn -o install` local + `mvn -o test` no n3dsemu confirmando que os 2 testes voltam a passar
(G5).

## Armadilhas

- **Não presumir que é o mesmo bug do `arm-jitter UNALIGNED_ACCESS`** (memória do projeto) — aquele
  já foi corrigido (F3, 2026-08-16) e era sobre decomposição de LDR/STR alinhado sob ARMv6K+, não
  sobre o monitor de exclusividade de LDREX/STREX.
- O `ARM11_MPCORE` preset é multi-core no hardware real (monitor GLOBAL entre núcleos) mas o
  n3dsemu só emula 1 núcleo — não complicar a investigação com semântica multi-core que não se
  aplica aqui; o bug é observável num único núcleo já.
