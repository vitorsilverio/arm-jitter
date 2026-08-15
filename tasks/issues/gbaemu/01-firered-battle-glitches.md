## Resumo

O Pokémon FireRed apresenta 3 glitches visuais durante as batalhas. O jogo é jogável de
ponta a ponta (overworld, batalhas, menus, save), mas o rendering da batalha tem defeitos
reproduzíveis.

## Como reproduzir

1. `pokefirered.gba`, backend **INTERPRETED** (o default do gbaemu).
2. Entrar em qualquer batalha selvagem.
3. Os defeitos aparecem na transição e durante as animações.

## Estado da investigação

- **Não é regressão do backend JIT/ASM.** Isso foi verificado por A/B pelo usuário em
  2026-07-16: os bugs acontecem **iguais** nos backends INTERPRETED e ASM, e a velocidade
  também é igual. A atribuição anterior ao JIT (2026-07-15) estava errada e foi revogada.
- **Não é regressão da task C6** (page-table/`PagedAddressSpace` no gbaemu): confirmado
  pré-existente por A/B.

## Hipóteses registradas (não verificadas)

1. Timing de **HDMA em vblank**.
2. **Wrap de OAM** (sprites no limite da tabela).
3. **OBJWIN** (janela de objeto).

## Referências

- Spec de investigação: `arm-jitter/tasks/trilha-d-compat/d2-pokemon-battle-glitch-interpreted.md`
- Histórico da revogação: seção "Pendências que EXIGEM sessão de modelo forte" do
  `arm-jitter/tasks/README.md`, item 1.
