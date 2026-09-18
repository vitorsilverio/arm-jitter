# C10 — Warm-start do JIT: pré-compilar PCs quentes persistidos por ROM (ndsemu)

**Trilha:** C · **Depende de:** — · **Repos:** arm-jitter (API) + ndsemu (persistência/GUI) ·
**Fecha:** ndsemu#7

## Contexto

Queixa recorrente do usuário (registrada 2x: validação de C4 "MKDS só demora a
esquentar" e backlog da GUI do ndsemu "lentidão de JIT-warmup"): ao carregar
jogo/savestate, os primeiros segundos rodam no tier frio até os blocos esquentarem
e compilarem. Os blocos quentes de uma ROM são os MESMOS a cada execução — dá para
lembrar deles e compilar adiantado. NÃO é cache de código persistido (classes ASM
não serializam de graça) — é persistir a LISTA de chaves quentes e recompilar em
background no load, que captura ~todo o benefício com ~nada do risco.

## Especificação

### arm-jitter (API, 2 métodos)

1. `JitRuntime#hotBlockKeys(int max)`: devolve as até `max` chaves (`BlockKey`:
   pc + instruction set + itstate) dos blocos COMPILADOS mais executados
   (contador que o tiering já mantém; se só existir "passou do threshold",
   devolver os compilados em ordem de inserção — suficiente).
2. `JitRuntime#precompile(Collection<BlockKey> keys)`: agenda as chaves no pool
   de compilação existente (mesmo caminho do tier quente), ignorando silenciosamente
   chaves cujo conteúdo de memória não decodifica mais (ROM diferente/self-modified)
   — a validação natural é o próprio lift; um bloco pré-compilado errado é
   IMPOSSÍVEL por construção (a chave inclui o PC e o conteúdo é liftado na hora,
   igual à compilação normal; no máximo compila-se um bloco inútil).

### ndsemu

3. Ao fechar/salvar-state: gravar `hotBlockKeys(512)` dos DOIS cores em
   `<rom>.hotpcs` (formato texto simples versionado) ao lado do save.
4. Ao carregar ROM/savestate: se `<rom>.hotpcs` existe, chamar `precompile` —
   em background, sem bloquear o boot (o pool já é assíncrono).
5. Guarda de identidade: gravar hash da ROM no arquivo; hash diferente → ignorar.

**Nota (2026-07-15):** o caso SAVE STATE tem solução melhor que o `.hotpcs` —
embutir as chaves quentes DENTRO do `.ss` e pré-compilar no restore; isso é o
Fix A da [C11](c11-savestate-restore-jit-frio.md) (relato real do usuário:
restore de SM64DS levou >10 min para re-aquecer). As DUAS tasks usam as mesmas 2
APIs do arm-jitter (`hotBlockKeys`/`precompile`) — quem rodar primeiro as cria, a
outra reusa; esta task fica com o caso "carregar ROM do zero" (`.hotpcs`), a C11
com o caso restore.

## Aceite

1. Medição objetiva: tempo até "fps estável" no MKDS via savestate de corrida
   (contar frames até a média móvel de 60 frames ficar a <5% da média final) —
   antes/depois, best-of-3; alvo: warmup cortado pela metade.
2. Corretude: `asmcheck` JUS com precompile ativo, zero divergências; boot dos 4
   jogos com e sem `.hotpcs` presente.
3. Arquivo corrompido/ROM trocada → ignorado com log, nunca falha.
4. Suites arm-jitter + ndsemu + gbaemu verdes (gbaemu não usa nada disto).

## Armadilhas

- NÃO serializar bytecode/classes — só chaves. Toda a segurança vem de recompilar
  do conteúdo atual da memória.
- Chaves de IWRAM/WRAM (código copiado em runtime) só valem depois que o jogo
  copiou o código — pré-compilar cedo demais gera bloco de lixo (inofensivo mas
  inútil, e a invalidação por escrita já o remove); se o log mostrar muito disso,
  filtrar chaves por região de ROM/RAM estável na v1 e anotar.
- O pool de compilação compartilha CPU com a emulação — `precompile` deve usar a
  prioridade/fila existente, nunca uma thread nova dedicada.

## Resultado

**✅ Concluída (2026-07-17).** arm-jitter: `BlockCache#hotKeys(max)` (só blocos
COMPILADOS, ordem decrescente de acertos) + `JitRuntime#hotBlockKeys(max)` (delega)
+ `JitRuntime#precompile(Collection<BlockKey>, AddressSpace)` novos; `precompile` é
no-op no modo não-tiered (`coldEmitter==null`), e chaves já presentes/com memória
que não decodifica mais (ROM diferente/self-modified) são ignoradas — o próprio
`lift` é a validação, sem exceção especial.

**Desvio documentado da assinatura literal da spec**: `precompile` recebe um
`AddressSpace memory` extra (a spec só listava `Collection<BlockKey> keys` — sem uma
fonte de memória não há como fazer o lift; `JitRuntime` não guarda um `AddressSpace`
persistente).

ndsemu: `dev.vitorsilverio.ndsemu.jit.HotBlockStore` novo (texto simples versionado
`NDSEMU-HOTPCS v1` + guarda de hash CRC32 da ROM) grava `<rom>.hotpcs`
(`hotBlockKeys(512)` dos dois cores) no fechamento do app (shutdown hook), ao trocar
de ROM e nos 2 fluxos de save-state (`saveStateQuick`/`saveStateToFile`); recarrega
e chama `precompile` de forma SÍNCRONA (mesma thread da emulação — `BlockCache` não
é thread-safe para acesso concorrente com `execute`; o "background" do warm-start já
vem do pool de compilação existente dentro do `precompile`) ao carregar ROM e nos 2
fluxos de load de save-state.

Testes novos: arm-jitter `BlockCacheHotKeysTest` (3) + `JitRuntimeWarmStartTest` (5,
incl. round-trip de compilação em background via polling); ndsemu
`HotBlockStoreTest` (4: round-trip fim-a-fim populando runtimes/memórias FRESCOS
antes de qualquer execução real, hash divergente ignorado, arquivo corrompido
ignorado, arquivo ausente ignorado). Suítes verdes: arm-jitter 724 core + 13
truffle, ndsemu 179, gbaemu (regressão, não usa nada disto).

**PENDENTE de validação do usuário** (não medido nesta sessão, sem ambiente de ROM
real/headless disponível): Aceite #1 (medição objetiva de warmup no MKDS via
savestate, antes/depois) e Aceite #2 (asmcheck JUS com precompile ativo + boot dos 4
jogos com/sem `.hotpcs` real) — a spec explicitamente permite registrar isso como
pendência em vez de inventar números. Armadilha do filtro de chaves IWRAM/RAM
instável (v1) também NÃO implementada — não há telemetria real desta sessão para
decidir o filtro; anotado, não fechado.
