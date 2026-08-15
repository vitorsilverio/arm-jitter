# G4 — Janela Vulkan (LWJGL 3 + GLFW) apresentando os framebuffers do `gsp` (marco M4)

**Trilha:** G · **Depende de:** G3 · **Repo:** n3dsemu
**Leia a RFC-N3DSEMU.md, em especial D4 (Vulkan/LWJGL) e D6 (telas), antes.**

## Contexto

O `gsp::Gpu` da G3 já recebe `SetBufferSwap` e descarta tudo. Esta task abre a janela e
mostra o que o guest escreveu na memória de framebuffer — sem ainda interpretar nenhuma
lista de comando da PICA200 (isso é a G5).

Muito homebrew (e todo `consoleInit()` do libctru) desenha **direto no framebuffer com a
CPU**, sem tocar a GPU. Por isso este marco já produz imagem útil e é o degrau certo antes do
PICA200.

## Objetivo (marco M4)

`examples/3ds/graphics/printing/hello-world` aparece na janela, nas duas telas, com as cores
e a orientação corretas.

## Inclui

1. Dependências LWJGL 3 no POM, com `natives-*` por plataforma.
2. `gpu/PicaRenderer` (interface) + `gpu/RecordingRenderer` (duplo de teste) +
   `gpu/vulkan/VulkanRenderer`.
3. Janela GLFW, swapchain, upload dos framebuffers do guest como textura, apresentação
   das duas telas.
4. Entrada de teclado/gamepad pela GLFW ligada ao `input/` da G3.
5. `Main` com `--headless` (comportamento atual, sem janela) e modo janela como default.

## NÃO inclui (não fazer)

- **Nenhuma interpretação de command list da PICA200, nenhum shader do guest, nenhuma
  textura do guest.** Só o *blit* dos framebuffers. G5.
- Sem 3D estereoscópico (renderiza só o olho esquerdo; RFC D6).
- Sem GUI Swing, sem menus, sem save state, sem configuração.
- Sem backend de software (decisão explícita do usuário — RFC D4).

## Especificação

### POM

```xml
<properties>
    <lwjgl.version>3.3.6</lwjgl.version>   <!-- confira a última estável ao executar -->
</properties>
```

Use o BOM oficial (`org.lwjgl:lwjgl-bom`, `<scope>import</scope>` em `dependencyManagement`)
e declare `lwjgl`, `lwjgl-glfw`, `lwjgl-vulkan`, `lwjgl-shaderc`. Os `natives` vão por
`<classifier>` em perfis ativados por SO:

```xml
<profile>
    <id>lwjgl-natives-windows</id>
    <activation><os><family>windows</family></os></activation>
    <properties><lwjgl.natives>natives-windows</lwjgl.natives></properties>
</profile>
```

(e equivalentes para `linux` e `mac` com `natives-macos-arm64`). **Atenção:**
`lwjgl-vulkan` **não tem** artefato de natives em Windows/Linux (o carregador Vulkan é do
sistema); só o macOS precisa de `natives-macos-arm64` para o MoltenVK. Declarar natives de
`lwjgl-vulkan` no Windows faz o build falhar por artefato inexistente.

**Consequência de CI (registrar no `ci.yml`):** o runner do GitHub não tem GPU/driver
Vulkan. Todo teste que instancie `VulkanRenderer` tem de ser **pulado** por
`Assumptions.assumeTrue(...)` quando `vkCreateInstance` falhar — mesmo padrão da F6 para
assets ausentes. Os testes reais rodam contra `RecordingRenderer`.

### `PicaRenderer`

A interface que isola a Vulkan do resto do emulador. Nesta task ela é pequena; a G5 a
estende. **Escreva-a já pensando na G5** (a G5 vai acrescentar métodos de geometria/estado,
e a `RecordingRenderer` os grava):

```java
/// Destino de desenho do subsistema gráfico do 3DS.
///
/// Existe para que a máquina de estados da PICA200 (parser de listas de comando,
/// registradores, tradução de shader) seja testável sem GPU: os testes usam
/// {@link RecordingRenderer}, e só o {@link dev.vitorsilverio.n3dsemu.gpu.vulkan.VulkanRenderer}
/// fala com hardware.
public interface PicaRenderer {

    /// Entrega o conteúdo de uma tela para apresentação.
    ///
    /// `pixels` está no formato e na orientação do 3DS (varredura por coluna, origem no
    /// canto inferior esquerdo da tela em retrato) — a conversão é responsabilidade do
    /// renderer, não do chamador.
    void presentScreen(Screen screen, byte[] pixels, PixelFormat format, int stride);

    /// Sinaliza o fim do quadro: o renderer apresenta o que recebeu.
    void endFrame();

    /// Libera os recursos do host.
    void close();
}
```

`Screen` = `TOP` (400×240) / `BOTTOM` (320×240). `PixelFormat` = `RGBA8`, `RGB8`,
`RGB565`, `RGB5A1`, `RGBA4` — os 5 formatos de framebuffer do 3DS
(`https://www.3dbrew.org/wiki/GPU/External_Registers`); todos precisam ser convertidos, o
`consoleInit()` do libctru usa `RGB565` ou `RGB8` dependendo da inicialização.

### Orientação e formato (a parte que dá errado)

**As telas físicas do 3DS são retrato (240 de largura × 400/320 de altura) e o framebuffer é
varrido por coluna.** Uma cópia ingênua produz a imagem girada 90° e espelhada. O caminho
correto:

1. Ler o framebuffer do guest com o *stride* que o `gsp` informou (não presuma
   `largura × bpp` — há alinhamento).
2. Converter o formato de pixel para `RGBA8`.
3. **Transpor** para a orientação de paisagem na hora do upload para a textura.

Faça a transposição no **shader de apresentação** (um quad de tela cheia com coordenadas de
textura trocadas), não em laço Java — é uma linha de GLSL contra um laço de 96.000 pixels por
quadro por tela.

Escreva um teste de unidade da conversão de formato+orientação sobre um framebuffer
sintético pequeno (ex.: 4×4 com um pixel de cor conhecida em cada canto), contra
`RecordingRenderer`. **É o único jeito de validar isso sem olho humano.**

### Vulkan — escopo mínimo

Instância → seleção de dispositivo físico (o primeiro com fila gráfica + apresentação) →
dispositivo lógico → swapchain → render pass → pipeline gráfico único (quad de tela cheia,
shader de apresentação) → 2 texturas (uma por tela) → command buffer por imagem →
sincronização com 2 quadros em voo.

Validation layers ligadas quando `-Dn3dsemu.vulkan.validation=true` (não por padrão: elas
exigem o SDK da LunarG instalado).

Shaders do host em GLSL, compilados para SPIR-V com `lwjgl-shaderc` **na inicialização**
(RFC D4). Guardar os `.vert`/`.frag` como recursos no jar.

### Janela e laço

Uma janela GLFW, as duas telas empilhadas verticalmente (a inferior centrada, como no
console: 400 de largura no topo, 320 embaixo). Escala inteira; redimensionamento recria a
swapchain.

**O laço de emulação e o laço de render vivem na mesma thread** nesta task: `runSlice()` até
o `gsp` sinalizar VBlank, então apresenta e processa eventos da GLFW. Simples e
determinístico. (Threads separadas com sincronização de áudio é problema de muito depois — o
ndsemu levou várias sessões nisso; ver memória `ndsemu-audio-realtime`.)

`--headless` mantém o comportamento da G3 (sem janela, sem LWJGL carregado) para os testes
automatizados continuarem rodando em CI.

### Input

Teclado pela GLFW ligado ao `input/` da G3. Mapeamento fixo (documentado no README), sem tela
de configuração. Touch pelo mouse na área da tela inferior.

## Aceite

- [ ] `mvn -o test` verde; testes de conversão de formato/orientação passando contra
      `RecordingRenderer`.
- [ ] `mvn -o test` verde **também numa máquina sem Vulkan** (simule com
      `-Dn3dsemu.vulkan.force-unavailable=true` ou equivalente): os testes de Vulkan são
      *skipped*, não falhos.
- [ ] `n3dsemu testdata/hello-world.3dsx` abre a janela e mostra o texto do exemplo, legível
      e na orientação correta, nas duas telas. **Validação visual — anexe uma captura de
      tela ao relatório de fechamento.**
- [ ] `n3dsemu --headless` continua funcionando exatamente como na G3.
- [ ] Teclado controla o guest (verificar com `read-controls.3dsx`).
- [ ] Índice do `tasks/README.md` atualizado (G4 ✅).

## Armadilhas

- **`lwjgl-vulkan` não tem natives em Windows/Linux.** Declarar o classifier lá quebra o
  build. Só macOS.
- **A orientação girada é a armadilha número um do 3DS.** Se a imagem sair girada, o bug está
  na apresentação, não no guest — resista a "consertar" no lado do emulador de memória.
- LWJGL exige `MemoryStack`/`memAlloc` disciplinados. Vazamento de memória nativa não aparece
  como `OutOfMemoryError` de heap; use `try (MemoryStack stack = stackPush())`.
- A GLFW **precisa** ser inicializada na thread principal em macOS (`-XstartOnFirstThread`).
  Documente no README, mesmo sem testar em macOS.
- Não instale o Vulkan SDK como requisito de build. `lwjgl-shaderc` já traz o compilador; o
  SDK só é necessário para validation layers.
- Se a swapchain ficar `VK_ERROR_OUT_OF_DATE_KHR` a cada quadro, é redimensionamento não
  tratado — não é bug do emulador.
