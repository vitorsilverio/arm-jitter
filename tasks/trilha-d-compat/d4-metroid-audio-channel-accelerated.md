# D4 — Metroid Fusion: canal de áudio baixo e acelerado

**Trilha:** D (compat de hospedeiros) · **Depende de:** — · **Repo:** gbaemu ·
**Task de diagnóstico** (causa raiz desconhecida) · **Fecha:** gbaemu#3

## FECHADA 2026-07-16 — sem problema real

Usuário ouviu os WAVs/jogou e confirmou que o áudio está aceitável — não é um
bug que precise de correção. Nenhuma ação adicional necessária.

## Status (2026-07-16) — fase 1 concluída, hipótese do enunciado REFUTADA, devolvida ao usuário

Instrumentação nova e permanente em `GbaAudio` (`directSoundTimer`, `fifoUnderruns`,
`fifoSamplesRequested`, `maxOverflowsPerCall`, `resetFifoDiagnostics`) e em
`GbaTimerController` (`overflowPeriodCycles`) para testar a hipótese de FIFO/timer do
enunciado. `MetroidAudioDiagnosticTest` novo (`-Daudio.diag=1`, ROM `roms/metroid.gba`,
gitignored) roda 60s de gameplay real (auto-mash START/A pelos primeiros 30s pra passar
de título/file-select/cutscene, depois segura RIGHT com pulo/tiro ocasional) logando por
janela de 5s: timer fonte, Hz esperado (do reload/prescaler real do timer), Hz efetivo
medido, fração de underrun de FIFO e o maior lote de overflows servidos numa única
chamada. Roda também um WAV da mixagem completa e um WAV por canal isolado (mesmo padrão
da D3).

**Achados**: Direct Sound A e B usam o **mesmo timer (0)** o jogo inteiro; Hz
esperado=10512,0 bate com o Hz efetivo medido (10218-10560, a variação é ruído de
quantização da janela de 5s, não um desvio real) em TODAS as 12 janelas de 60s;
**underrunFraction=0,0000 e maxOverflowsPerCall=1 o tempo inteiro** — nunca houve
FIFO vazio nem lote de mais de 1 overflow por chamada. **Isso REFUTA as duas hipóteses
concretas do enunciado** (FIFO com timer errado / cadência de DMA errada causando
"baixo"+"acelerado"+intermitência) — pelo menos na janela de 60s de gameplay
sintético capturada aqui. Mixagem completa saudável (railed=0,05%, click=0%, sem
assinatura de nenhum bug já corrigido).

**Achado novo, não previsto no enunciado**: os canais PSG CH1/CH2 pulse ficam ativos
mas com amplitude MUITO baixa (pico 7-12 numa faixa de ~127) o jogo inteiro, contra
~85-88 do Direct Sound A/B; CH3 wave e CH4 noise ficam mudos (min=max=0). Se a
"melodia" relatada pelo usuário for na verdade um desses canais PSG quietos (não a
mixagem alta do Direct Sound), isso explicaria "baixo" diretamente — mas "acelerado"/
"dessincronizado"/intermitente precisa de confirmação auditiva por canal, que as
métricas agregadas (railed/click/min/max) não capturam.

**Devolvido ao usuário** (critério de aceite #3: causa não pequena/óbvia) com os WAVs
em `target/metroid-audio-gameplay.wav` (mix completo) e
`target/metroid-audio-gameplay-solo-{ch1-pulse,ch2-pulse,ch3-wave,ch4-noise,directA,
directB}.wav` — pedir pra identificar em qual WAV o sintoma aparece (e se o auto-mash
de 60s realmente chegou na cena/música onde o usuário ouviu o problema; se não, precisa
de um save state mais próximo do ponto exato). Suite gbaemu 239 verde (17 skipped =
opt-in `-Daudio.diag=1` + pré-existentes), sem regressão nos 2 bugs de áudio já
corrigidos do Metroid (`GbaAudioTest` verde, incl.
`pulseChannelPlaysAtGbaTunedFrequencyNotFourTimesTooHigh`).

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
