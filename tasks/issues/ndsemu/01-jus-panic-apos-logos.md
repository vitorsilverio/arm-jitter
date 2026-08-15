## Resumo

Jump Ultimate Stars (JUS) — ROM comercial — boota até os logos das publishers e então o ARM9
cai num panic do SDK (`b .`, laço infinito de um branch para si mesmo). Não chega ao menu.

## Como reproduzir

Rodar a ROM do JUS. Os logos aparecem; logo depois a execução do ARM9 para.

## Estado da investigação

Progresso já feito: o boot só chegou até aqui depois de corrigir o `WRAMCNT=3` do direct-boot
e de acrescentar o `CardController` do slot-1.

**Causa provável (não confirmada): o start-mode de DMA de cartucho não está sendo acionado.**
O SDK entra em `b .` quando uma espera por DMA nunca é satisfeita.

## Contexto de perf (não relacionado ao bug, mas relevante para quem for depurar)

O JUS roda a ~99% do realtime depois das otimizações de superbloco — a lentidão não é fator
aqui.

## Labels sugeridas

`bug`, `compat`
