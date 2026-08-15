## Resumo

Metroid Fusion: a melodia sai errada/incompleta. Suspeita centrada nos canais Direct Sound
(FIFO A/B) e no "channel accelerated" do mixer.

## Como reproduzir

Metroid Fusion, backend indiferente, logo no início do jogo.

## Estado da investigação

O jogo em si está **jogável** — o crash de novo jogo foi corrigido (endpoints de DMA não
forçados ao alinhamento do tamanho da transferência; o jogo passa um endereço THUMB ímpar
como origem de um DMA de 32 bits). O que resta é áudio.

Também já corrigidos e **não** relacionados: pitch de PSG (relógio GB→GBA), `VBlankIntrWait`
acordando com qualquer IRQ, `MidiKey2Freq`, epílogo de IRQ da BIOS `0x138`.

## Referências

- Spec: `arm-jitter/tasks/trilha-d-compat/d4-metroid-audio-channel-accelerated.md`
