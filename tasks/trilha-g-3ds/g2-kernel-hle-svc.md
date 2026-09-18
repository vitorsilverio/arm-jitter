# G2 — Kernel Horizon em HLE: threads, handles, sincronização e memória (marco M2)

**Trilha:** G · **Depende de:** G1 · **Repo:** n3dsemu
**Leia a RFC-N3DSEMU.md antes.** Task grande — fatie em 2 PRs se precisar (PR1: handles +
memória + `svcOutputDebugString`; PR2: threads + sincronização).

## Contexto

A G1 deixou `SvcTable` logando e lançando em toda chamada. Esta task implementa o kernel em
Java (RFC D2 — HLE, sem MMU, sem kernel real).

## Objetivo (marco M2)

O `.3dsx` do `templates/application` roda do início ao fim e sai limpo por `svcExitProcess`,
com o que ele escreve por `svcOutputDebugString` aparecendo no console do host.

## Inclui

`kernel/` com: tabela de handles, objetos de kernel (evento, mutex, semáforo, thread,
`AddressArbiter`, sessão/porta de IPC), escalonador cooperativo, gestão de memória
(`svcControlMemory`, `svcQueryMemory`), relógio (`svcGetSystemTick`), e as SVCs listadas
abaixo.

## NÃO inclui (não fazer)

- **Nenhum serviço** — `srv:`, `APT`, `hid`, `gsp`, `fs` são a G3. Esta task para no
  `svcConnectToPort`: ele cria a sessão e devolve handle; o `svcSendSyncRequest` sobre ela
  **loga o cabeçalho de comando IPC e lança** `UnsupportedServiceException`. Isso é o
  progresso esperado, não uma falha.
- Sem gráficos, sem áudio, sem segundo núcleo.
- Sem preempção por tempo (ver "escalonador" abaixo).

## Especificação

Referência primária: `https://www.3dbrew.org/wiki/SVC` (assinatura e semântica de cada
chamada) e o código do Citra (`core/hle/kernel/`) para a estrutura. **Transcrever de lá.**

### Convenção de chamada

Argumentos em `r0`–`r3` (e além, conforme a SVC); retorno: `r0` = código de resultado
(`0` = sucesso), valores de saída em `r1`+. Códigos de erro do 3DS são um `u32` com campos
(descrição, módulo, resumo, nível) — defina `Result` como record com os campos nomeados e
constantes para os erros usados, **nunca literais hexadecimais soltos**.

### Handles

`HandleTable` por processo: `int` → objeto de kernel. Handles pseudo-reservados:
`0xFFFF8000` = processo atual, `0xFFFF8001` = thread atual. `svcCloseHandle` libera;
`svcDuplicateHandle` copia. Um handle inválido devolve o erro de handle inválido — **nunca**
uma `NullPointerException` do lado Java.

### SVCs desta task

| Nº | Nome | Nota |
|----|------|------|
| `0x01` | `svcControlMemory` | `MEMOP_ALLOC`/`FREE`/`MAP`/`UNMAP` sobre o heap; devolve endereço |
| `0x02` | `svcQueryMemory` | estado/permissões da região que contém o endereço |
| `0x03` | `svcExitProcess` | encerra a emulação com sucesso |
| `0x08` | `svcCreateThread` | cria e enfileira; ver escalonador |
| `0x09` | `svcExitThread` | |
| `0x0A` | `svcSleepThread` | nanossegundos; ver relógio |
| `0x0B` | `svcGetThreadPriority` | |
| `0x0C` | `svcSetThreadPriority` | |
| `0x13` | `svcCreateMutex` | |
| `0x14` | `svcReleaseMutex` | recursivo (o mutex do 3DS conta reentradas) |
| `0x15` | `svcCreateSemaphore` | |
| `0x16` | `svcReleaseSemaphore` | |
| `0x17` | `svcCreateEvent` | `RESET_ONESHOT`/`RESET_STICKY`/`RESET_PULSE` |
| `0x18` | `svcSignalEvent` | |
| `0x19` | `svcClearEvent` | |
| `0x1E` | `svcCreateMemoryBlock` | memória compartilhada — necessário para o `gsp` na G3 |
| `0x1F` | `svcMapMemoryBlock` | |
| `0x20` | `svcUnmapMemoryBlock` | |
| `0x22` | `svcArbitrateAddress` | `AddressArbiter` — o libctru usa em condvars |
| `0x23` | `svcCloseHandle` | |
| `0x24` | `svcWaitSynchronization1` | com timeout |
| `0x25` | `svcWaitSynchronizationN` | `waitAll` + índice do que acordou |
| `0x27` | `svcDuplicateHandle` | |
| `0x28` | `svcGetSystemTick` | ver relógio |
| `0x2D` | `svcConnectToPort` | cria sessão para um nome de porta; a G3 preenche as portas |
| `0x32` | `svcSendSyncRequest` | **nesta task: loga o cabeçalho IPC e lança** |
| `0x35` | `svcGetProcessId` | |
| `0x37` | `svcGetThreadId` | |
| `0x38` | `svcGetResourceLimit` | valores plausíveis fixos |
| `0x3A` | `svcGetResourceLimitCurrentValues` | idem |
| `0x3C` | `svcBreak` | encerra com diagnóstico ("o guest chamou `svcBreak`", com o motivo) |
| `0x3D` | `svcOutputDebugString` | **imprime no `stdout` do host** — é o que fecha o M2 |

Toda SVC fora dessa lista continua no default de G1 (loga o nome e lança). **Não implemente
"por precaução" nada que não esteja aqui.**

### Escalonador

**Cooperativo, sem preempção por tempo.** A troca de thread acontece só em pontos de
suspensão explícitos: `svcSleepThread`, `svcWaitSynchronization*`, `svcArbitrateAddress`,
`svcExitThread` e liberação de mutex com quem esperando.

Justificativa (registrar no Javadoc): o 3DS real é preemptivo por prioridade, mas homebrew
raramente depende disso, e um escalonador preemptivo exige salvar/restaurar contexto em
pontos arbitrários do JIT — complexidade grande antes de haver o que testar. **Se algum
exemplo travar por falta de preempção, isso é um achado a reportar, não a consertar aqui.**

Contexto de thread = snapshot dos registradores do `ArmCore` (`CpuSnapshot` do arm-jitter
já existe e é usado pelo harness de equivalência — reuse-o em vez de escrever outro).

### Relógio

`svcGetSystemTick` devolve o contador do ARM11 a **268.111.856 Hz** (`SYSCLOCK_ARM11`).
Derive-o dos ciclos já contados pelo `ArmCore` — **não** use `System.nanoTime()`, que
tornaria a execução não determinística e quebraria a comparabilidade JIT×interpretado.

`svcSleepThread` converte nanossegundos em ticks e agenda o despertar no mesmo relógio.

### Memória

`svcControlMemory` opera sobre o heap (`0x08000000` linear e `0x14000000`). Como não há MMU
(RFC D2), "mapear" é registrar a região no `PagedAddressSpace` e devolver o endereço. Manter
uma lista de regiões com estado/permissão para `svcQueryMemory` responder de verdade — o
libctru consulta.

## Aceite

- [ ] `mvn -o test` verde.
- [ ] `n3dsemu testdata/application.3dsx` executa até `svcExitProcess` e sai com **código 0**.
- [ ] O que o exemplo escreve por `svcOutputDebugString` aparece no `stdout`.
- [ ] **JIT e `--interp` produzem exatamente a mesma sequência de SVCs** (compare os traces
      com `--trace-svc`, byte a byte). Divergência aqui é bug do arm-jitter ou do kernel —
      investigue antes de fechar.
- [ ] Teste unitário por objeto de kernel (evento oneshot/sticky/pulse, mutex recursivo,
      semáforo, `waitAll` vs `waitAny`, timeout que expira) — não só o teste de integração.
- [ ] `svcSendSyncRequest` loga o cabeçalho IPC decodificado (comando, nº de parâmetros
      normais e traduzidos) antes de lançar.
- [ ] Índice do `tasks/README.md` atualizado (G2 ✅).

## Armadilhas

- **O mutex do 3DS é recursivo e tem dono.** `svcReleaseMutex` por thread que não é a dona é
  erro, e a contagem de reentradas importa. Errar isso trava o libctru de formas difíceis de
  diagnosticar.
- **`svcWaitSynchronizationN` devolve o índice** do objeto que acordou em `r1` (e o resultado
  em `r0`). Devolver só o resultado faz o libctru tomar decisões erradas silenciosamente.
- Eventos `RESET_STICKY` permanecem sinalizados até `svcClearEvent`; `RESET_ONESHOT` acorda
  **uma** thread e limpa. Confundir os dois causa deadlock ou spin infinito.
- Não use `Thread` do Java para as threads do guest. Um `ArmCore`, uma fila de contextos.
- `svcGetSystemTick` que não avança monotonicamente trava qualquer laço de espera do libctru.

## Resultado

🟡 PARCIAL — **PR1** (2026-08-15, commit `b6198c5`): handles + memória +
`svcOutputDebugString`.

**PR2** (2026-08-15, sessão seguinte): `kernel/Scheduler` novo (cooperativo, um único
`ArmCore` reutilizado por todas as threads via `ArmCore#saveState`/`loadState` completo — não
o `CpuState` de 8 campos do `SwiDispatcher`, que não cobre r4-r12/bancos privilegiados; TLS
por thread alocado e trocado via `N3dsCp15#setThreadLocalStorage` a cada troca);
`ThreadObject` deixou de ser um `record` (agora mutável: prioridade/estado/contexto salvo
mudam em vida); `MutexObject` (recursivo, com dono), `SemaphoreObject`, `EventObject`
(oneshot/sticky) — os 3 implementam `Waitable`, contrato novo (`isAvailableFor`/`acquire`)
que também cobre esperar uma HANDLE DE THREAD terminar; `AddressArbiterObject`,
`SessionObject`, `MemoryBlockObject`. `SvcTable` ganhou `svcCreateThread`/`ExitThread`/
`SleepThread`/`Get`+`SetThreadPriority`, `svcCreateMutex`/`ReleaseMutex`,
`svcCreateSemaphore`/`ReleaseSemaphore`, `svcCreateEvent`/`SignalEvent`/`ClearEvent`,
`svcCreateMemoryBlock`/`Map`/`UnmapMemoryBlock`, `svcArbitrateAddress`,
`svcWaitSynchronization1`/`N` (índice do objeto acordado em `r1`, `waitAll` vs `waitAny`),
`svcConnectToPort` (cria `SessionObject`) e `svcSendSyncRequest` (loga o cabeçalho IPC
decodificado de TLS+0x80 e lança, "não inclui" da task — serviços reais são a G3).

**Achado real, implementado além da lista literal de SVCs da task**: `svcCreateAddressArbiter`
(`0x21`) não estava na lista, mas `svcArbitrateAddress` (`0x22`, que ESTÁ) não tem como
funcionar sem um handle de arbiter real — o próprio teste da PR1 já documentava isso como
bloqueio ("para o boot real progredir além da primeira svc observada"); implementado como
pré-requisito estrito do que já estava no escopo, não "por precaução". Convenções de
registrador de TODAS as SVCs novas conferidas contra a montagem REAL dos wrappers
(`arm-none-eabi-objdump -d libctru.a`, mesma metodologia da PR1) — achado extra:
`svcWaitSynchronization`/`N`/`ArbitrateAddress` passam o timeout de 64 bits em pares de
registrador não-óbvios (`r2:r3` no primeiro, `r0`+`r4` no N, `r4:r5` no arbiter), sem
shuffling de pilha, dependendo do padding AAPCS natural.

**Achado real reportado, NÃO corrigido (fora do escopo desta task)**: com `0x21`
implementado, `n3dsemu testdata/application.3dsx` progride além da primeira `svc` e esbarra
numa decisão ARQUITETURAL de OUTRO épico do arm-jitter — `FpscrRegister#setValue` (decisão
nº 3 do épico B3) só suporta o modo IEEE round-to-nearest da VFP, e o `crt0`/newlib do
libctru grava um FPSCR com `LEN`/`STRIDE` (aritmética vetorial VFPv2) diferente de zero antes
de `main()`. **O aceite objetivo "roda até `svcExitProcess`, sai código 0" desta task NÃO é
alcançável enquanto essa decisão do arm-jitter não for revisitada** (seria uma task própria
do arm-jitter, fora do escopo de kernel HLE desta) — confirmado idêntico nos 3 backends
(JIT/`--interp`/`--check`, `Application3dsxTest` parametrizado) e com `n3dsemu --trace-svc`
real.

Todo o resto do aceite fechou: `svcSendSyncRequest` loga o cabeçalho IPC antes de lançar;
teste unitário por objeto de kernel (`MutexObjectTest` — recursão + release por não-dono,
`SemaphoreObjectTest`, `EventObjectTest` — oneshot/sticky, `SvcTableTest` — `waitAll` vs
`waitAny` com índice, timeout que expira via bloqueio real + adiantamento do relógio virtual
`core.addCycles`, nunca `System.nanoTime()`). `mvn -o test` verde (84 testes; só o repo
`n3dsemu` tocado, G5 não se aplica).

**Sessão de continuação (2026-08-16, motivada pela investigação que achou os 2 gaps abaixo
dos SVCs `0x38`/`0x3A`)**: 2 bugs reais fechados. **(1)** `svc 0x39`
(`svcGetResourceLimitLimitValues`), fora da lista original da task — mesmo padrão do achado
de `svcCreateAddressArbiter` da PR2: um SVC vizinho que o `__system_allocateHeaps` do crt0
usa e a spec original não previu. Sem ele, o array de saída do teto de `COMMIT` nunca era
escrito (ficava com o que já estava na pilha do guest, tipicamente `0`), o tamanho de heap
calculado pelo crt0 virava `0` e o `svcControlMemory(MEMOP_ALLOC)` seguinte falhava com
`MISALIGNED_SIZE` — implementado espelhando `handleGetResourceLimitCurrentValues` (mesma
convenção de registrador), com `ResourceLimitValues#limitValueOf` novo.

**(2)** Achado arquitetural mais profundo, descoberto ao investigar por que o `ALLOC`
continuava falhando mesmo com um teto plausível: `MemoryMap.LINEAR_HEAP_BASE`(`0x08000000`)/
`NEW_HEAP_BASE`(`0x14000000`) deste projeto estavam com os endereços TROCADOS em relação ao
3dbrew real (`Memory_layout`: o heap "geral", mapeado por `ControlMemory` **sem** a flag
`LINEAR`, fica em `0x08000000`; o heap LINEAR de verdade, via `MEMOP_ALLOC_LINEAR`, fica em
`0x14000000` — o oposto) — confirmado via `WebFetch` na wiki antes de corrigir, não um
palpite. Consequência prática: o segundo `svcControlMemory` do crt0 (que usa a flag `LINEAR`,
endereço escolhido pelo kernel) caía no pool errado e falhava com `OUT_OF_RANGE`. Corrigido
renomeando para `GENERAL_HEAP_BASE`/`LINEAR_HEAP_BASE` nos endereços certos
(`MemoryManager`/`N3dsMachine`/`N3dsAddressSpace` atualizados) — e, como bug lateral
necessário para o primeiro `ALLOC` (endereço explícito, sem a flag) continuar funcionando,
`MemoryManager#controlMemory` passou a escolher o pool pelo ENDEREÇO quando `addr0≠0` (a flag
`LINEAR` só decide quando o kernel escolhe o endereço, `addr0==0` — um `addr0` explícito já é
auto-suficiente no Horizon real). Os dois heaps (16 MiB cada, antes 2 MiB/16 MiB
desbalanceados) agora batem exatamente o teto de `COMMIT` que `ResourceLimitValues` relata,
já que o crt0 reparte esse teto meio a meio quando não há `.smdh`/exheader.

**`n3dsemu testdata/application.3dsx` não panica mais** (era `svcBreak(PANIC)` toda vez) — os
dois `svcControlMemory` de heap agora sucedem nos 3 backends. **Novo limite encontrado, NÃO
corrigido (fora do escopo desta continuação cirúrgica)**: o backend JIT entra num laço que
chama `svcCreateAddressArbiter` (`0x21`) repetida e indefinidamente no MESMO PC — confirmado
manualmente até 200 mil fatias sem sair sozinho; provavelmente precisa de
sincronização/escalonador cooperativo de verdade reagindo a esse padrão (fora do "não inclui"
desta task, território de G3 ou de uma G2.2). **Achado extra**: INTERPRETED/CHECK avançam
MUITO mais devagar por fatia que o JIT (blocos compilados/encadeados cobrem mais instruções
por fatia) — dentro do mesmo orçamento de fatias que o JIT já usa para alcançar o laço, os
outros dois backends ainda não saíram do segundo `svcControlMemory`; o aceite original da G2
("JIT e `--interp` produzem exatamente a mesma sequência de SVCs") não foi revalidado ponta a
ponta por essa divergência de RITMO (não necessariamente de comportamento) — fica para a
sessão que atacar o laço do arbiter também confirmar isso com um orçamento de fatias generoso
o bastante para os 3 backends convergirem. `mvn -o test` verde (87 testes; só `n3dsemu`
tocado, G5 não se aplica).
