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
