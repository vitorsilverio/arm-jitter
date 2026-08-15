# RFC — `n3dsemu`: emulador de Nintendo 3DS sobre o arm-jitter

**Status:** aceita (decisões tomadas com o usuário em 2026-08-15) · **Trilha:** G
**Escrita por:** sessão de planejamento de 2026-08-15.
**Esta RFC contém as decisões. As tasks G1–G7 as executam.** Uma sessão de execução **não
deve** reabrir nenhuma decisão marcada como **D**; se achar que uma está errada, PARE e
reporte ao usuário.

---

## 1. Por que agora

O lado `arm-jitter` do 3DS **já está pronto** — a task `b5-3ds.md` do épico B5 foi escrita
exatamente para isso e fechou por completo:

- **B5.1** ✅ — `core/ExclusiveMonitor` compartilhável entre `ArmCore`s: os núcleos ARM11 do
  3DS compartilham memória e `LDREX`/`STREX` (base dos atomics do kernel Horizon) precisam de
  visão global.
- **B5.2** ✅ — preset `ArmArchitecture.ARM11_MPCORE` (ARMv6K + VFPv2, **sem** Thumb-2: o
  MPCore é ARMv6K, não v6T2).

A `b5-3ds.md` já registrou a recomendação que esta RFC adota: **começar por HLE** (sem MMU,
sem Horizon LLE), porque LLE exigiria o épico B4.1 aplicado a um kernel fechado do qual não
temos fonte nem dump.

## 2. Decisões

### D1 — Só o ARM11, e só um núcleo no começo

O 3DS tem dois processadores: o **ARM11 MPCore** (aplicação, 4 núcleos no New3DS, 2 usáveis
no Old3DS) e um **ARM9** (ARMv5TE) que roda o `Process9` — sistema de arquivos, criptografia,
cartucho. Com **kernel em HLE, o ARM9 não é emulado**: os serviços que ele oferece são
implementados em Java. É a mesma escolha do Citra e é o que torna o projeto viável.

Começamos com **um único `ArmCore`** (núcleo 0, o da aplicação). O `ExclusiveMonitor`
compartilhado da B5.1 é usado desde o início (mesmo com um núcleo só), para o segundo núcleo
entrar depois sem refactor.

### D2 — HLE do kernel Horizon, não LLE

Não carregamos nem emulamos o kernel real. As `svc` do guest são interceptadas
(`SwiDispatcher` do arm-jitter, o mesmo mecanismo que gbaemu e ndsemu já usam para a BIOS) e
implementadas em Java: threads, handles, eventos, mutexes, semáforos, memória, IPC.

Consequência aceita: **sem MMU**. O espaço de endereçamento é montado plano pelo host, com as
regiões nos endereços virtuais que o Horizon usaria. Isso basta para `.3dsx` e para a maioria
do homebrew.

### D3 — Formato de ROM: `.3dsx` primeiro; `.cia`/`.3ds` depois (task G6, [REFINAR])

Decisão do usuário. `.3dsx` é aberto, sem criptografia, e o corpus é reprodutível: o
devkitPro **já está instalado nesta máquina** (`C:\devkitPro`) com `devkitARM`, `libctru`,
`C:\devkitPro\examples\3ds` (21 diretórios de exemplos, incluindo 20 exemplos de GPU) e as
ferramentas `3dsxtool`, `picasso` (assembler de shader PICA200), `tex3ds`, `smdhtool`.

Isso replica exatamente o que funcionou no ndsemu com os `nds-examples` (ver memória
`ndsemu-homebrew-testing`): **um corpus de teste independente de jogo comercial, compilável
localmente, com fonte disponível para depurar**.

`.cia`/`.3ds` exigem `boot9.bin` e chaves AES dumpadas de um console físico, e NCCH/ExeFS/
RomFS. Fica para a G6, quando o usuário tiver os dumps.

### D4 — Gráficos: Vulkan via LWJGL 3, em janela GLFW própria

Decisão do usuário (2026-08-15), com o motivo declarado: *"o ndsemu já sofre bastante com
rasterização por CPU"*.

- **LWJGL 3** é o único binding Vulkan maduro para Java. Artefatos Maven:
  `lwjgl`, `lwjgl-vulkan`, `lwjgl-glfw`, `lwjgl-shaderc`, cada um com os `natives-*` por
  plataforma (`natives-windows`, `natives-linux`, `natives-macos-arm64`).
- **Janela GLFW própria**, não um `Canvas` Swing. O n3dsemu **não** herda a GUI Swing do
  gbaemu/ndsemu; o loop de render é o do GLFW.
- Shaders do host escritos em GLSL e compilados para SPIR-V com `lwjgl-shaderc` **em tempo de
  execução, na inicialização** (não em tempo de build) — assim não há passo de build nativo e
  o repo continua sendo "clone e `mvn test`".
- macOS: Vulkan só existe via MoltenVK, que o LWJGL empacota. Não é alvo de teste, mas não
  fazemos nada que o impeça.

**Consequência que a decisão carrega (registrada explicitamente):** o usuário escolheu a
opção *sem* backend de software. Portanto **não haverá renderização em CI/headless**. A
testabilidade automática do subsistema gráfico vem de outro lugar: os testes cobrem o
**parser de command list, a máquina de estados de registradores PICA200 e a tradução de
shader**, contra um `PicaRenderer` de teste que só **grava** as chamadas recebidas (um
*recording double*). O `VulkanRenderer` real é validado **visualmente**, pelo usuário, e por
comparação de capturas de tela. Nenhuma task da trilha G pode ter como aceite automatizado
"o triângulo apareceu".

### D5 — Vertex shader interpretado na CPU; TEV traduzido para SPIR-V

O PICA200 não é uma GPU programável no sentido moderno:

- **Vertex shaders** usam uma ISA própria da Nintendo/DMP (arquivos `.shbin`, montados pelo
  `picasso`). **Decisão: interpretar na CPU**, em Java, e mandar para a Vulkan os vértices
  **já transformados**. É o caminho mais curto para a correção; foi o que o Citra fez antes de
  ter um JIT de shader. Recompilar `.shbin` → SPIR-V é uma otimização futura, não o começo.
- **Fragment** é **fixed-function**: 6 estágios TEV (*texture environment combiner*) +
  teste alpha + blending. **Decisão: gerar um shader de fragmento SPIR-V por configuração de
  TEV**, com cache em memória chaveado pela configuração (é um punhado de configurações por
  jogo, não milhares). Uma "über-shader" com todos os estágios ramificados em tempo de
  execução é a alternativa; fica registrada como plano B se a geração ficar complexa demais.

### D6 — Telas

Superior: 400×240 (800×240 no modo estereoscópico/wide). Inferior: 320×240. **Os
framebuffers na memória do 3DS são armazenados girados** (as telas físicas são 240×400
retrato, varridas por coluna) — a rotação é feita na apresentação. Sem 3D estereoscópico no
começo: renderiza-se só o olho esquerdo.

### D7 — Áudio fora de escopo até M5

O 3DS tem um DSP com firmware próprio (`dspfirm.cdc`, dumpado do console) e o `ndsp` do
libctru fala com ele por IPC. Sem o dump, só dá para fazer HLE do `ndsp`. **Não é escopo da
trilha G como planejada**; vira task própria depois de M5.

### D8 — Repositório e convenções

Repo novo `n3dsemu`, irmão de `gbaemu`/`ndsemu`, `groupId dev.vitorsilverio`, pacote raiz
`dev.vitorsilverio.n3dsemu`, Java 25, `AGENTS.md` espelhando o do ndsemu. Consome
`dev.vitorsilverio:arm-jitter:1.0.0` **do Maven Central** (por isso a trilha G roda depois da
F5/F7 — ver `FILA-EXECUCAO.md`).

## 3. Mapa de memória (Old3DS, visão do ARM11 em HLE)

Referências: [3dbrew.org](https://www.3dbrew.org/wiki/Memory_layout) e o código do Citra.
**Transcrever de lá; não escrever de memória.**

| Base | Tamanho | Região |
|------|---------|--------|
| `0x00100000` | — | Carregamento do executável `.3dsx` (code/rodata/data) |
| `0x08000000` | 2 MiB | Heap linear / `LINEAR` (o `linearAlloc` do libctru) — na prática mapeado sobre a FCRAM |
| `0x14000000` | — | Heap "novo" (`svcControlMemory` MEMOP_ALLOC) |
| `0x18000000` | 6 MiB | **VRAM** (2 bancos de 3 MiB) |
| `0x1FF00000` | 512 KiB | DSP RAM |
| `0x1FF80000` | 4 KiB | **Config memory** (leitura; modelo do console, versão do kernel...) |
| `0x1FF81000` | 4 KiB | **Shared page** (relógio, estado do slider 3D, wifi) |
| `0x20000000` | 128 MiB | **FCRAM** (memória principal) |

TLS de cada thread (com o **buffer de comando IPC** em `+0x80`) fica em páginas alocadas pelo
host; o endereço é devolvido por `svcGetThreadLocalStorage`/lido de `CP15 c13`.

## 4. Escada de marcos (define as tasks)

| Marco | O que prova | Task |
|-------|-------------|------|
| **M1** | Um `.3dsx` compilado do `templates/application` carrega e a CPU executa até a primeira `svc`, com trace | G1 |
| **M2** | Kernel HLE suficiente para o exemplo rodar até o fim e sair: threads, handles, eventos, mutex, memória, `svcOutputDebugString` visível no console do host | G2 |
| **M3** | `srv:` + `APT` + `hid` + `fs:USER` mínimo: o laço principal do libctru (`aptMainLoop`) gira e responde a input | G3 |
| **M4** | Janela Vulkan mostrando os framebuffers que o `gsp::Gpu` entrega — o exemplo `graphics/printing/hello-world` aparece na tela | G4 |
| **M5** | PICA200: command list + vertex shader interpretado + TEV → o exemplo `graphics/gpu/simple_tri` desenha o triângulo | G5 |
| — | ROMs comerciais `.cia`/`.3ds` | G6 [REFINAR] |
| — | Trazer o núcleo Vulkan para o ndsemu | G7 [REFINAR] |

## 5. Fora de escopo (registrar e não fazer)

Segundo núcleo ARM11 e escalonamento real · New3DS (núcleos extras, clock a 804 MHz) ·
ARM9/`Process9` · MMU/LLE do Horizon · DSP/áudio (D7) · câmera, microfone, NFC, giroscópio ·
rede/wifi · 3D estereoscópico · RomFS/`sdmc:` além do stub mínimo · `.cia`/`.3ds` (G6) ·
save states · GUI de configuração.
