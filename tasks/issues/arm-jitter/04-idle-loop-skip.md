## Resumo

**Pedido de desenho (RFC), não bug.** Detectar busy-wait do guest em registrador de I/O e
avançar o relógio direto até o próximo evento agendado, em vez de queimar ciclos.

## Por que

É potencialmente **o maior ganho isolado** para os jogos CPU-bound do ndsemu — o teto in-race
do MKDS (~50 fps) é CPU, e boa parte disso é o guest girando em espera.

## Por que ainda não foi feito

O desenho é **arriscado**: um falso positivo (pular um laço que na verdade não era espera)
trava o jogo ou quebra o timing de forma sutil. Precisa de RFC própria antes de virar task —
não é candidato a implementação direta.

## Pontos que a RFC precisa resolver

- Como reconhecer um laço de espera com segurança (padrão de acesso? repetição do mesmo bloco
  sem escrita de estado observável?).
- Até onde adiantar o relógio (próximo evento do escalonador? Próxima IRQ?).
- Como validar que não houve mudança de comportamento — o harness de equivalência não cobre
  isso, porque a diferença é justamente de tempo.

## Referência

`arm-jitter/tasks/README.md`, "Pendências que EXIGEM sessão de modelo forte", item 4.

## Labels sugeridas

`perf`, `needs-design`
