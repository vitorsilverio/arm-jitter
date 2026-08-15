# G7 — [REFINAR] Trazer o núcleo gráfico Vulkan para o ndsemu

**Trilha:** G · **Depende de:** G5 · **Repo:** ndsemu (+ possivelmente um módulo comum)
**[REFINAR]:** especificação de alto nível, **condicional**. **Não execute.**

## Contexto

Pedido do usuário em 2026-08-15, explicitamente condicional:

> "provavelmente teremos que incluir alguma biblioteca grafica para ter suporte a opengl ou
> vulkan (preferivel) e porque o ndsemu ja sofre bastante com rasterização por cpu (se der
> certo trazer esse nucleo grafico para o ndsemu tambem)"

O gatilho é **"se der certo"**: esta task só é refinada depois que a G5 fechar e o
`VulkanRenderer` do n3dsemu estiver provado com conteúdo real.

## O que já se sabe hoje (para o refinamento não começar do zero)

- O rasterizador 3D do ndsemu é por CPU e **já foi otimizado** várias vezes: o rasterizador W
  (−2%), a cobertura de borda do DS (`d09a3ca`, fechou as rachaduras do mapa do Platinum), a
  ordenação/profundidade exatas do hardware (`d6e3e5a`, fechou grama-sobre-personagem).
  **Essa fidelidade é cara e é o principal risco de uma reescrita em GPU:** o DS tem quirks
  (Y-sort, translúcidos por último, Z quantizado, comparação estrita) que uma GPU moderna não
  reproduz de graça.
- A medição de perf do ndsemu (memória `ndsemu-perf-plan`) concluiu que a **CPU** é o teto
  in-race do MKDS (~50 fps), não a rasterização. O ganho de mover para GPU é real mas
  **provavelmente menor do que parece** — o refinamento precisa medir antes de prometer.
- O ndsemu tem GUI **Swing** com save states, menus, mute por canal, gamepad. O n3dsemu tem
  janela **GLFW**. Unificar os dois modelos de janela é parte do custo e **não** é trivial.

## Perguntas que o refinamento tem de responder

1. **Vale a pena?** Medir: que fração do tempo de quadro do ndsemu é rasterização hoje, em
   MKDS/Platinum/SM64DS? Se for <15%, a resposta pode ser "não".
2. **Onde mora o código comum?** Um módulo Maven novo (`arm-jitter`-style, ex.:
   `vulkan-common`) consumido pelos dois? Ou copiar? Copiar duplica manutenção; extrair cedo
   demais engessa uma API que ainda não estabilizou.
3. **Como manter os quirks do DS?** Depth buffer com a mesma quantização, ordenação por
   polígono, `POLYGON_ATTR` (modos de translucidez, IDs de stencil). Alguns podem exigir
   *fragment shader interlock* ou passes múltiplos.
4. **GUI:** manter Swing e renderizar Vulkan num `Canvas` embutido (chato no Windows), ou
   migrar o ndsemu para GLFW e reescrever a GUI (custo alto, perde funcionalidade)?
5. **Como não regredir?** O ndsemu tem uma técnica de validação pronta: savestates do melonDS
   como oráculo (memória `ndsemu-melonds-oracle`). O renderer novo tem de ser comparável
   contra o de CPU quadro a quadro antes de virar default.

## Recomendação registrada

Se a G5 fechar bem, **não** migre o ndsemu inteiro: acrescente o renderer de GPU como
**backend alternativo** (`--renderer=vulkan`), com o de CPU permanecendo o default até que a
comparação quadro-a-quadro contra o de CPU e contra o melonDS mostre paridade. É o mesmo
padrão que o próprio projeto já usa para backends de JIT (interpretado é o oráculo, G1 do
arm-jitter).
