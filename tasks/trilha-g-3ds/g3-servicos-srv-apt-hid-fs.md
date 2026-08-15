# G3 — Serviços em HLE: IPC, `srv:`, `APT`, `hid`, `fs:USER`, `gsp::Gpu` (marco M3)

**Trilha:** G · **Depende de:** G2 · **Repo:** n3dsemu
**Leia a RFC-N3DSEMU.md antes.**

## Contexto

A G2 parou no `svcSendSyncRequest`: ele decodifica o cabeçalho IPC e lança. Esta task
constrói a camada de serviços por cima, até o laço principal do libctru girar de verdade e
responder a input.

## Objetivo (marco M3)

`examples/3ds/input/read-controls` roda: `aptMainLoop()` gira, `hidScanInput()` enxerga os
botões que o host injeta, e o programa sai quando o botão START é pressionado.

## Inclui

1. `ipc/` — codificação/decodificação do buffer de comando IPC.
2. `service/` — despacho por porta + os serviços: `srv:`, `APT:U`, `hid:USER`, `fs:USER`
   (mínimo), `gsp::Gpu` (**só o suficiente para o M3**; a renderização é a G4/G5),
   `cfg:u`, `ptm:u`.
3. `input/` — estado de botões/touch/circle-pad e uma forma de injetá-los sem GUI.

## NÃO inclui (não fazer)

- **Sem janela, sem Vulkan, sem LWJGL** — a G4 é que traz isso. O `gsp::Gpu` desta task
  aceita comandos e **descarta** o desenho; só o bookkeeping (memória compartilhada, eventos
  de VBlank) é real.
- Sem `ndsp`/áudio (RFC D7), sem `am`/`ns`/`cam`/`nfc`/`ac`.
- Sem RomFS e sem `sdmc:` real: `fs:USER` responde o mínimo para o libctru inicializar.

## Especificação

### IPC

Referência: `https://www.3dbrew.org/wiki/IPC`. O buffer de comando fica no **TLS da thread**,
em `TLS + 0x80`. Palavra 0 = cabeçalho:

```
bits 31..16 = comando
bits 15..6  = nº de parâmetros normais
bits 5..0   = nº de parâmetros "traduzidos"
```

Seguem os parâmetros normais e depois os traduzidos (handles, ponteiros de buffer estático,
mapeamentos de memória). A resposta é escrita **no mesmo buffer**, com palavra 1 = código de
resultado.

Modele isso como `IpcRequest`/`IpcResponse` (leitura/escrita tipada), **não** como
manipulação de `int[]` espalhada por cada serviço. Testes de unidade sobre o codec, com
vetores montados à mão.

### Despacho

```java
/// Uma porta de serviço do Horizon. `svcConnectToPort("srv:")` e, depois,
/// `srv:GetServiceHandle("hid:USER")` devolvem sessões que caem aqui.
public interface Service {
    String name();
    void handleRequest(IpcRequest request, IpcResponse response);
}
```

Um `ServiceRegistry` mapeia nome → `Service`. Comando desconhecido dentro de um serviço
conhecido: **logar `serviço + comando + parâmetros` e devolver erro**, não lançar — assim o
guest segue e o log mostra tudo que falta de uma vez. (Esse é o oposto da política da G2, de
propósito: lá interessava parar cedo; aqui interessa mapear a superfície.)

### `srv:`

Comandos mínimos: `Initialize (0x1)`, `GetServiceHandle (0x5)`, `EnableNotification (0x2)`,
`Subscribe`/`Unsubscribe`, `ReceiveNotification`. `GetServiceHandle` é o que devolve sessão
para os demais serviços.

### `APT:U`

O mais chato e o que trava tudo se estiver errado. O libctru (`aptInit`/`aptMainLoop`)
espera uma coreografia: `GetLockHandle (0x1)`, `Initialize (0x2)`, `Enable (0x3)`,
`GetAppletManInfo (0x5)`, `NotifyToWait (0x43)`, `ReceiveParameter (0xD)`,
`GlanceParameter (0xE)`, `AppletUtility (0x4B)`, `SetAppCpuTimeLimit (0x4F)`,
`GetAppCpuTimeLimit (0x50)`, `PrepareToStartLibraryApplet`, `ReplySleepQuery (0x3E)`.

**Modelo mínimo que funciona:** o app é sempre o *foreground*, nunca é suspenso, e
`ReceiveParameter` entrega uma vez o sinal `APTSIGNAL_WAKEUP` e depois fica sem parâmetro.
`aptMainLoop()` então devolve `true` para sempre e o programa roda. Se o exemplo travar em
`aptMainLoop`, o problema está aqui — leia `libctru/source/services/apt.c`
(`C:\devkitPro\libctru\` está instalado, **com fonte**, aproveite: é o oráculo exato do que
o guest espera receber).

### `hid:USER`

`GetIPCHandles (0xA)` devolve o handle da memória compartilhada de HID + 5 eventos
(`PAD`, `accelerometer`, `gyroscope`, `debugPad`, `touch`). A memória compartilhada tem
buffers em anel com índice de entrada; o libctru lê o mais recente.

Layout: `https://www.3dbrew.org/wiki/HID_Shared_Memory`. Preencher a cada quadro simulado
(60 Hz sobre o relógio de ticks da G2) e sinalizar o evento correspondente.

Injeção de input **sem GUI**: `N3dsMachine.pressButtons(int mask)` / `releaseButtons` /
`setTouch(x, y)` / `setCirclePad(x, y)`, mais uma opção de linha de comando
`--script=<arquivo>` com uma sequência simples `<quadro> <ação>` por linha. É isso que
torna o M3 testável automaticamente — mesmo padrão dos `*Drive.java`/`*Probe.java` que o
ndsemu usa.

### `fs:USER`

Só o suficiente para `fsInit()` do libctru não falhar: `Initialize (0x801)`,
`InitializeWithSdkVersion (0x861)`, `SetPriority (0x862)`, `GetPriority`. Qualquer abertura
de arquivo devolve erro de "não encontrado". Sem `sdmc:` e sem RomFS (fora de escopo).

### `gsp::Gpu` (mínimo do M3)

`AcquireRight (0x16)`, `RegisterInterruptRelayQueue (0x13)` (devolve o handle da memória
compartilhada de eventos GSP + o índice de thread), `WriteHWRegs (0x1)`,
`WriteHWRegsWithMask (0x2)`, `SetBufferSwap (0x5)`, `FlushDataCache (0x8)`,
`SetLcdForceBlack (0xB)`, `TriggerCmdReqQueue (0xC)`.

**Nesta task:** guardar o estado, gerar o evento de **VBlank a 60 Hz** (é ele que faz
`gspWaitForVBlank()` retornar — sem isso o app trava), e **descartar** qualquer desenho. As
listas de comando são guardadas e contadas, mas não interpretadas (isso é a G5).

### `cfg:u` e `ptm:u`

`cfg:u`: `GetConfigInfoBlk2 (0x1)` para os blocos que o libctru consulta (idioma, modelo do
console). `ptm:u`: nível de bateria e estado de carga fixos. Ambos triviais e ambos exigidos
por alguns exemplos.

## Aceite

- [ ] `mvn -o test` verde.
- [ ] `read-controls.3dsx` roda; com `--script` pressionando START no quadro 60, o programa
      sai sozinho com código 0.
- [ ] `application.3dsx` (M2) **continua** saindo com código 0.
- [ ] Nenhuma exceção não tratada durante os dois: serviço/comando desconhecido aparece como
      **linha de log**, e o guest segue.
- [ ] Testes de unidade: codec IPC (cabeçalho, parâmetros normais e traduzidos), buffer em
      anel do HID, coreografia do `APT` (sequência esperada de comandos).
- [ ] JIT e `--interp` com a mesma sequência de serviços/comandos.
- [ ] Índice do `tasks/README.md` atualizado (G3 ✅).

## Armadilhas

- **`C:\devkitPro\libctru` tem o código-fonte do lado do guest.** Sempre que houver dúvida
  sobre o que um serviço deve responder, a resposta está lá — é literalmente o cliente. Não
  adivinhe a partir do 3dbrew quando o fonte está disponível.
- **Sem o evento de VBlank, tudo trava.** `gspWaitForVBlank()` é a primeira coisa de todo
  laço de render.
- O buffer IPC vive **no TLS da thread corrente**. Se a G2 não estiver entregando TLS
  distinto por thread, os serviços vão se corromper entre threads de formas confusas.
- Comando desconhecido que **lança** em vez de logar faz você descobrir uma lacuna por
  execução. Logar e seguir descobre todas de uma vez.
- O `APT` é onde o libctru mais depende de estado entre chamadas. Um `Enable` sem
  `Initialize` anterior tem de ser tolerado (logado), não explodir.
