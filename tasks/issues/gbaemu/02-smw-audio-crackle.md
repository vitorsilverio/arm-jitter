## Resumo

Super Mario World (Super Mario Advance 2) tem chiado/estalos no áudio durante o jogo.

## Como reproduzir

`smw.gba`, qualquer backend. O chiado aparece na música de fase.

## Estado da investigação

Não investigado a fundo. Não é o mesmo defeito do offset DC de canal PSG ocioso, que já foi
corrigido (áudio bruto digital + filtro passa-alta) e resolvia o "railing" geral. Este é
específico deste jogo.

Backend-independente (mesma classe de achado de #TBD-firered-battle-glitches).

## Referências

- Spec: `arm-jitter/tasks/trilha-d-compat/d3-smw-audio-crackle.md`
