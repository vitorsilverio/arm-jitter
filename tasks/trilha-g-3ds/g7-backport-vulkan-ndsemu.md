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

## Rodada de refinamento (2026-08-27) — pergunta 1 respondida, resultado: NÃO vale a pena

A G5 fechou (2026-08-21, triângulo do `simple_tri` renderiza) — o gatilho condicional da task
está satisfeito. Esta sessão respondeu a pergunta 1 do refinamento ("vale a pena? medir que
fração do tempo de quadro é rasterização") por medição direta, não estimativa.

**Método**: instrumentação temporária em `RenderPipeline.submit3d`/`Main.runBenchmark`
(2 `AtomicLong` medindo `job.rasterize()` — a rasterização 3D de verdade — separado da
composição 2D `videoA/videoB.renderFrame`; e o tempo de CPU isolado por frame no laço do
bench). Revertida ao final (`git checkout`), não commitada — mesmo padrão de instrumentação
descartável já usado por G3.3/G5.1/G5.2. Rodado com `mvn exec:java ... Main "<rom>" <frames>
bench asm` (backend ASM/JIT, o mesmo que a GUI usa por default — não o `INTERPRETED`, que é
~2x mais lento e enviesaria a conta a favor da GPU).

| Jogo | cpu/frame | raster3d/frame | composite2d/frame | orçamento (60fps) |
|---|---|---|---|---|
| Mario Kart DS (corrida) | 21,27ms | 3,97ms | 6,50ms | 16,67ms |
| Super Mario 64 DS | 13,46ms | 4,53ms | 6,72ms | 16,67ms |

**Achado decisivo, que a arquitetura já previa mas nunca tinha sido medido**: o
`RenderPipeline` (ver Javadoc da classe, já dizia isso por inspeção, agora confirmado por
medição) roda a rasterização 3D + composição 2D numa thread PRÓPRIA, OVERLAPADA com a CPU do
próximo quadro via ping-pong de 2 slots — o comentário `awaitSlot` já afirmava "render is far
cheaper than a frame of CPU, so that join never actually stalls", e os números confirmam: em
AMBOS os jogos, `raster3d + composite2d` (10,5ms / 11,3ms) fica folgadamente ABAIXO do tempo de
CPU por quadro (21,3ms / 13,5ms) — o teto de fps é a CPU, não a renderização, exatamente como
`ndsemu-perf-plan` (memória) já tinha concluído para o MKDS por outro caminho (perfil de JIT).
Mesmo que o `raster3d` (a única parte que uma GPU substituiria — a composição 2D dos 4 fundos +
sprites do DS não é o alvo do G7) fosse a **ZERO**, o tempo de quadro não mudaria: a thread de
render já termina antes da CPU liberar o próximo quadro, então uma GPU mais rápida não tem
folga nenhuma para preencher.

**Conclusão**: mover a rasterização 3D para Vulkan **não aumentaria o fps do ndsemu** nos dois
jogos medidos (um de corrida, geometria simples repetida; um de plataforma 3D, geometria mais
variada) — o gargalo é a emulação de CPU (ARM9+ARM7 interpretados/JIT), não a rasterização, e
ela já roda em paralelo à CPU sem competir pelo mesmo núcleo. As perguntas 2-5 do refinamento
(onde mora o código comum, como manter os quirks do DS, GUI Swing×GLFW, como não regredir) só
importam se a resposta à pergunta 1 fosse "sim" — como não é, não foram investigadas.

**Decisão**: G7 **não avança** como proposta original (ganho de fps). Não fechada como "nunca
fazer" (a Regra Máxima do `tasks/README.md` não permite excluir permanentemente) — registrada
como **candidata só se surgir outro motivo** (não-performance) para renderizar em GPU: ex.
upscaling de resolução acima do nativo do DS, efeitos pós-processamento, ou liberar o núcleo
que hoje faz composição 2D para outro uso. Nenhum desses motivos foi pedido pelo usuário até
agora. G5-invariante não se aplica (nenhum arquivo tocado — instrumentação revertida).
