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

## Inclui — Fase 1 (diagnóstico, esta sessão)

1. Reproduzir o glitch de forma determinística: identificar QUAL batalha/momento
   dispara (o relato original não especifica — perguntar ao usuário se necessário,
   ou usar save states pra chegar rápido numa batalha, ver `gba-desktop-gui`).
2. Capturar frames (`--frame-count N --frame-step-cycles M --frame out.ppm
   --debug-video`, ver `gba-game-compat`) ANTES e DURANTE o glitch, nos dois
   backends (`INTERPRETED` e `ASM`+chaining), pra comparar visualmente e por regs
   de vídeo (DISPCNT/BG/OBJ/paleta) no momento exato da divergência visual.
3. Isolar a camada afetada (BG afim/tile, sprite/OBJ, paleta, scroll) a partir dos
   dumps — não adivinhar.
4. Se a causa for encontrada com confiança nesta sessão E for pequena/local, pode
   virar Fase 2 (correção) na MESMA sessão — mas só se o Passo 3 apontar uma causa
   clara; senão, PARE, documente o achado (frames, regs, hipóteses descartadas) e
   devolva pra uma sessão de correção dedicada.

## Não inclui

- Áudio (fora de escopo — são as tasks D3/D4 separadas).
- Mudar o backend default do gbaemu (permanece `INTERPRETED`, decisão já tomada).

## Aceite (fase 1)

1. Reprodução determinística documentada (ROM, ponto do jogo, passos).
2. Camada de vídeo afetada identificada (BG/OBJ/paleta/scroll) com evidência
   (frames + regs), não suposição.
3. Confirmado se acontece IGUAL nos dois backends ou se há alguma diferença sutil
   entre eles (mesmo que ambos glitchem, pode não ser bit-a-bit idêntico).

## Validação

Suíte gbaemu verde sem mudança de comportamento (fase 1 é só instrumentação/
diagnóstico, a menos que uma correção pequena e óbvia apareça — ver item 4).

## Armadilhas

- Não confundir com o achado JÁ CONHECIDO e não relacionado "animação de BIOS +
  áudio lentos" (também pré-existente em INTERPRETED, ver `gba-game-compat` —
  bug DIFERENTE, envolve timing, não gráfico de batalha).
- Save states (F5/F8) aceleram MUITO a iteração — não replay manual do jogo do
  zero a cada teste.
