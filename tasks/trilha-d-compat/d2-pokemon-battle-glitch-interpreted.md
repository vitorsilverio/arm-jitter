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

## Aceite

1. Cada um dos 3 sintomas: hipótese confirmada-e-corrigida (com teste de
   regressão permanente) OU refutada com evidência documentada.
2. Validação visual do usuário em batalha real (fade progressivo até embaixo,
   inimigo entrando pela esquerda junto do mato, overlay vermelho/azul visível).
3. 5 jogos de referência sem regressão visual (o histórico de DMA é sensível —
   a fila de DMA já quebrou Castlevania uma vez, ver `gba-video-save-status`);
   gba-tests + suíte verdes.

## Validação

Suíte gbaemu verde sem mudança de comportamento (fase 1 é só instrumentação/
diagnóstico, a menos que uma correção pequena e óbvia apareça — ver item 4).

## Armadilhas

- Não confundir com o achado JÁ CONHECIDO e não relacionado "animação de BIOS +
  áudio lentos" (também pré-existente em INTERPRETED, ver `gba-game-compat` —
  bug DIFERENTE, envolve timing, não gráfico de batalha).
- Save states (F5/F8) aceleram MUITO a iteração — não replay manual do jogo do
  zero a cada teste.
