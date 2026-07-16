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
