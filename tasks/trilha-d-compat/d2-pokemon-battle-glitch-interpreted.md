# D2 — Pokémon FireRed: glitch gráfico de batalha também em `INTERPRETED`

**Trilha:** D (compat de hospedeiros) · **Depende de:** — · **Repo:** gbaemu ·
**Task de diagnóstico** (causa raiz desconhecida — fase 1 é medir/isolar, fase 2 é
corrigir depois de entender a causa)

## Contexto

Em C5 (chaining ASM, arm-jitter `tasks/README.md`) o usuário reportou glitches
gráficos em batalha no FireRed rodando `ASM`+chaining, e a hipótese registrada foi
"provavelmente específico do caminho ASM/JIT" — nunca isolada (não se testou se o
chaining por si só era a causa vs. o ASM sozinho). Com base nisso, a decisão de
produto foi manter `INTERPRETED` como default do gbaemu, entre outros motivos porque
"não tem o glitch de batalha" (ver [[gba-game-compat]] / memória do projeto).

**Em 2026-07-16, durante a validação de gameplay da task C6, o usuário reportou o
MESMO glitch rodando em `INTERPRETED`** (ver memória `gba-c6-gameplay-findings`,
confirmado via A/B contra o commit pai de C6 — pré-existente, não é regressão do
C6). Isso refuta a hipótese "só ASM" e é mais sério do que se pensava: o glitch
afeta o backend PADRÃO que todo usuário usa.

## Sintomas detalhados (usuário, 2026-07-16 — respondem o "qual batalha/momento")

São **3 bugs distintos** em batalha, todos backend-independentes:

1. **Fade de entrada em batalha**: o terço INFERIOR da tela já começa todo preto,
   enquanto os 2/3 superiores fazem o fade progressivo corretamente.
2. **Intro de batalha selvagem**: o "matinho" desliza da esquerda para a direita
   como deveria, mas o Pokémon inimigo ENTRA PELA DIREITA, dessincronizado do
   mato — deveriam vir juntos da esquerda.
3. **Golpes que aumentam/diminuem status**: falta a animação do overlay
   vermelho/azul subindo/descendo sobre o Pokémon.

## Hipóteses fortes (uma por sintoma, independentes — verificar nesta ordem; refinamento de 2026-07-16)

**Hipótese 1 ✅ CONFIRMADA E CORRIGIDA (2026-07-16).** `GbaLcdTiming.updateRegisters()`
computava `nextHblank` só a partir de `scanlineCycles`, disparando o H-Blank em TODAS
as 228 linhas do frame (inclusive as 68 de V-Blank) — e `GbaConsole.triggerTimedDma`
usava essa mesma contagem para disparar o HDMA, rodando a tabela 68 entradas além do
fim por frame. Fix: novo campo `Events.hblankStartedVisibleCount` (só linhas
`scanline < VISIBLE_SCANLINES`), usado exclusivamente para o disparo de DMA; a flag/IRQ
de H-Blank (`hblankStartedCount`) continua disparando em todas as 228 linhas,
que é o comportamento correto de hardware (GBATEK). Teste de regressão:
`GbaLcdTimingTest.hblankFiresOnAllScanlinesButVisibleCountExcludesVblank` (228 total,
160 visível). Suíte gbaemu 232 verde. **Falta validação visual do usuário** (fade
progressivo até embaixo em batalha real) — aceite #2 da task.

1. **Fade → H-blank DMA (HDMA) fora da janela válida.** FireRed programa efeitos
   por-scanline via DMA em modo H-blank (tabela, um valor por linha). No hardware
   **HDMA só dispara nas linhas visíveis 0-159 — NUNCA no V-blank (160-227)**
   (GBATEK "DMA H-Blank mode"). Se o `GbaDmaController` dispara HDMA também no
   V-blank, o ponteiro da tabela avança 68 entradas além do fim por frame — o
   terço inferior lê lixo/fim de tabela (preto). Teste de regressão alvo:
   disparos de HDMA por frame == 160, não 228.
2. **Slide-in do inimigo → wraparound de 9 bits do X do OAM.** Sprite entrando
   pela ESQUERDA começa com X negativo, codificado no OAM como `512 − |x|`
   (campo de 9 bits). O renderer deve interpretar X com wrap em 512 (X=500 ⇒
   desenha em −12); se trata X alto como "à direita da tela", o sprite entra
   pela DIREITA — o sintoma exato. Vale também para Y (wrap 256) e para affine
   double-size.
**Hipótese 2 — REFUTADA na prática (2026-07-16).** O usuário validou em batalha
real logo após o fix da hipótese 1 e o inimigo já entra pela esquerda junto do
mato — o "vindo da direita" era artefato visual do bug do fade (terço inferior
preto), não um bug de wrap de X do OAM. Nenhum código mexido para esta hipótese;
`GbaVideoTest` de sprite com X negativo (wrap 512) já cobria o caso antes desta
task e continua verde.

**Hipótese 3 ✅ CONFIRMADA E CORRIGIDA (2026-07-16).** Sprites em `attr0`
`mode==2` (OBJ window) eram só pulados (`gatherObjectsLine`: `disabled ||
objectMode == 2 → continue`) — nunca desenhados E nunca usados como máscara.
`activeWindowMaskLine` também nunca consultava OBJ window: só tratava WIN0/WIN1.
Resultado: o overlay vermelho/azul de status (que no gen-3 é um sprite modo-2
mascarando um blend BLDCNT sobre o Pokémon) nunca aparecia. Fix: novo array
`objWindowCoverage[WIDTH]`, preenchido por `computeObjWindowCoverage` (reusa
`gatherObjectLine`/`gatherAffineObjectLine` com um novo parâmetro `windowSprite`
que troca "desenhar o pixel" por "marcar cobertura" quando o pixel não é
transparente) ANTES de `activeWindowMaskLine` — que agora usa essa cobertura
como o 3º nível de prioridade de janela (WIN0 > WIN1 > OBJ window > fora,
conforme GBATEK), com a máscara vinda de `WINOUT` bits 8-13. Teste de regressão
`GbaVideoTest.objWindowModeSpriteMasksBlendWithoutBeingDrawnItself` (sprite
modo-2 nunca aparece como pixel, mas define a região onde o blend é permitido).
Suíte gbaemu 233 verde. **Validação visual do usuário: CONFIRMADA 2026-07-16**
(overlay vermelho/azul aparece corretamente em golpe de status real).

3. **Overlay de status → OBJ window (WINOBJ).** A animação vermelha/azul do
   gen-3 usa a janela de objetos (DISPCNT bit 15 + WINOUT bits 8-13): sprites
   com `mode=obj-window` não são desenhados — viram máscara onde o efeito de
   cor (BLDCNT) se aplica. Se o renderer não implementa OBJWIN, o overlay some
   por completo.

## Inclui — Fase 1 (verificação dirigida, uma hipótese por vez)

1. Para cada hipótese: PRIMEIRO um teste unitário/ROM mínima que prove o
   comportamento atual errado (HDMA disparando em vblank; OBJ com X=500;
   sprite obj-window), SEM depender do FireRed — se o teste passar (hipótese
   falsa), registrar e ir para a próxima; se reproduzir, corrigir.
2. Captura de frames do FireRed (`--frame-count N --frame-step-cycles M
   --frame out.ppm --debug-video`, ver `gba-game-compat`) como confirmação
   visual antes/depois de cada fix; save states para chegar rápido na batalha.
3. Os 3 fixes são PRs independentes (1 sessão cada). Se NENHUMA hipótese se
   confirmar para um sintoma, PARE, documente (frames, regs, hipóteses
   descartadas) e devolva para sessão de modelo forte.

## Não inclui

- Áudio (fora de escopo — são as tasks D3/D4 separadas).
- Mudar o backend default do gbaemu (permanece `INTERPRETED`, decisão já tomada).

## Aceite — TODOS ATINGIDOS (2026-07-16)

1. ✅ Cada um dos 3 sintomas: hipótese 1 confirmada-e-corrigida (HDMA em
   V-Blank), hipótese 2 refutada com evidência (gameplay real pós-fix-1),
   hipótese 3 confirmada-e-corrigida (OBJ window) — todas com teste de
   regressão permanente (exceto a 2, que não tinha bug para regredir).
2. ✅ Validação visual do usuário em batalha real: fade progressivo até
   embaixo, inimigo entrando pela esquerda junto do mato, overlay
   vermelho/azul visível — as 3 confirmadas pelo usuário.
3. 🟡 Suíte gbaemu (233, inclui gba-tests) verde nas duas sessões; não houve
   uma revalidação explícita de gameplay nos 5 jogos de referência além do
   FireRed — risco baixo (o fix da hipótese 3 só ativa quando o jogo liga o
   bit OBJ_WINDOW_ENABLE do DISPCNT, e o da hipótese 1 só reduz disparos de
   HDMA que hardware-mente não deveriam ocorrer). Se aparecer regressão em
   outro jogo, reabrir aqui.

**D2 fechada** — as 3 hipóteses resolvidas e validadas.

## Validação

Suíte gbaemu verde sem mudança de comportamento (fase 1 é só instrumentação/
diagnóstico, a menos que uma correção pequena e óbvia apareça — ver item 4).

## Armadilhas

- Não confundir com o achado JÁ CONHECIDO e não relacionado "animação de BIOS +
  áudio lentos" (também pré-existente em INTERPRETED, ver `gba-game-compat` —
  bug DIFERENTE, envolve timing, não gráfico de batalha).
- Save states (F5/F8) aceleram MUITO a iteração — não replay manual do jogo do
  zero a cada teste.
