## Resumo

Na cutscene de introdução do FireRed, os pés do rival têm um pequeno recorte incorreto de
transformação afim.

## Como reproduzir

FireRed, cutscene de abertura (a do Professor Oak), no momento em que o rival aparece.

## Estado da investigação

Defeito **menor e isolado**. O bug maior da mesma cutscene (o "Oak verde") **já foi
corrigido**: era timing de DMA imediato — dois preenchimentos imediatos consecutivos se
sobrescreviam dentro de um mesmo bloco JIT. A correção foi executar cada DMA imediato no
tempo do ARM (`GbaDmaController`), que é o correto no hardware; uma tentativa anterior de
enfileirar para o fim do bloco quebrava o Castlevania.

Este recorte afim é o que sobrou.

## Prioridade

Baixa — cosmético, numa cena de introdução.
