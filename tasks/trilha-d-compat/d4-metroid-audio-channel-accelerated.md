# D4 — Metroid Fusion: canal de áudio baixo e acelerado

**Trilha:** D (compat de hospedeiros) · **Depende de:** — · **Repo:** gbaemu ·
**Task de diagnóstico** (causa raiz desconhecida)

## Contexto

Achado do usuário em 2026-07-16 (validação de gameplay da task C6, ver memória
`gba-c6-gameplay-findings`): algum canal de áudio do Metroid Fusion fica baixo
(volume) e acelerado (pitch/tempo), soando "esquisito" — confirmado PRÉ-EXISTENTE
via A/B contra o commit pai de C6 (não é regressão do C6).

Metroid já teve DOIS bugs de áudio corrigidos antes (ver `gba-audio-status`):
(1) `MidiKey2Freq` (SWI 0x1F) stub retornando 0 → só percussão, sem melodia — já
corrigido; (2) crash de novo-jogo por epílogo de IRQ do BIOS incompleto — já
corrigido, não é áudio mas foi na mesma sessão. Este é um achado NOVO, distinto
dos dois anteriores (o sintoma "baixo + acelerado" não bate com nenhum dos dois:
melodia já toca, jogo não crasha).

## Detalhe adicional do sintoma (usuário, 2026-07-16, segundo relato)

O canal afetado parece ser **a MELODIA da música**: baixo, dessincronizado E
acelerado em relação ao resto. **Intermitente**: numa ocasião a melodia nem
veio, e depois passou a vir — isso sugere estado que se corrompe/recupera
(buffer/FIFO), não um erro constante de frequência.

## Hipótese adicional (refinamento 2026-07-16): DirectSound/FIFO, não PSG

Metroid Fusion usa o driver m4a/MP2k: a MÚSICA inteira (melodia incluída) é PCM
mixado por software nos FIFOs DirectSound A/B (DMA1/DMA2 disparados por
Timer 0/1); os PSG fazem SFX/complemento. Se a melodia está num dos FIFOs:

- **FIFO com timer errado**: se A e B usam timers distintos e a taxa de um deles
  é derivada errado (reload/prescaler, ou taxa fixa assumida em vez do timer
  real), um lado da mixagem corre em outra velocidade — "acelerado +
  dessincronizado".
- **Cadência do DMA de FIFO**: o request deve transferir 4 words quando o FIFO
  fica com ≤ 16 bytes; cadência errada (por tick de timer em vez de por nível)
  explica "baixo" (amostras zeradas intercaladas) e o comportamento
  INTERMITENTE (FIFO esvazia e re-enche — a melodia "sumir e voltar" bate).
- "Baixo" também pode ser SOUNDCNT_H (mix 50%/100% por canal DS) ignorado.

A instrumentação da fase 1 decide entre PSG (hipóteses abaixo) e FIFO (acima):
logar por-FIFO a taxa efetiva (amostras/s), underruns/s e o timer fonte por 10s.

## Inclui — Fase 1 (diagnóstico)

1. Reproduzir e caracterizar: qual canal (PSG pulso/onda/ruído, ou Direct Sound
   A/B)? Constante ou só em certos sons/músicas? Save states pra chegar rápido no
   ponto exato (pedir ao usuário se necessário).
2. `-Daudio.diag=1` generalizado pro Metroid (ver mesmo mecanismo citado em D3),
   `.wav` do trecho afetado.
3. "Acelerado" sugere pitch/frequência errada (padrão já visto neste projeto: o
   bug de PSG "2 oitavas alto" por falta de conversão do clock GB→GBA, já
   corrigido — CONFERIR se não é uma variante/regressão desse fix específica de
   um canal, timer, ou combinação de registrador não coberta pelo teste
   existente `pulseChannelPlaysAtGbaTunedFrequencyNotFourTimesTooHigh`). "Baixo"
   sugere volume/envelope ou mixagem — pode ser um problema SEPARADO do
   "acelerado", não necessariamente uma causa só.
4. Comparar o padrão do timer/frequência do canal afetado contra os outros 4
   jogos (que não têm esse sintoma) pra achar o que Metroid faz diferente
   (registrador de som específico, sweep, envelope, ou um SWI de áudio que só
   Metroid chama — `MidiKey2Freq` é o candidato óbvio, dado o histórico).

## Aceite (fase 1)

1. `.wav` do trecho com o canal afetado, com o canal isolado (PSG ou DS,
   especificando qual).
2. Hipótese de causa raiz com evidência (não só "parece ser X") — idealmente
   ligada a um registrador/timer/SWI específico que Metroid usa diferente dos
   outros 4 jogos de referência.
3. Se pequena e óbvia, correção pode entrar na mesma sessão; senão, documentar e
   devolver.

## Validação

Suíte gbaemu verde. Se corrigir nesta sessão, regressão guard novo (mesmo padrão
de `GbaAudioTest`) + reconfirmar os 191 testes que já cobrem os 2 bugs anteriores
do Metroid continuam verdes (não pode reabrir nenhum dos dois).

## Armadilhas

- NÃO confundir com o bug já corrigido de PSG 2-oitavas-alto (`gba-audio-status`)
  sem antes confirmar que o teste de regressão dele (`pulseChannelPlaysAtGba
  TunedFrequencyNotFourTimesTooHigh`) continua passando — se ele falhar, é
  REGRESSÃO desse fix, não bug novo, e o tratamento é diferente.
- Ver a lista completa de bugs de áudio já corrigidos em `gba-audio-status` antes
  de investigar, mesmo raciocínio de D3.
