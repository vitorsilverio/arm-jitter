## Resumo

A animação de boot da BIOS do GBA roda visivelmente mais devagar que no hardware real.

## Como reproduzir

Iniciar o emulador com a BIOS real (`gba_bios.bin`) e observar a animação do logo.

## Por que importa

É um sintoma de timing, não só um detalhe cosmético: se a animação da BIOS está lenta, algum
caminho de temporização (waitstates, contagem de ciclos, IRQ de raster) está sistematicamente
errado, e isso afeta jogos de formas menos óbvias.

## Estado da investigação

Não investigado. Marcada no projeto como pendência que **exige sessão de modelo forte** — não
é candidata a correção mecânica.

## Referências

- Spec: `arm-jitter/tasks/trilha-d-compat/d6-gbaemu-bios-lenta.md`
