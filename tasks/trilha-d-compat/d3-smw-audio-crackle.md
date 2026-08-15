# D3 — Super Mario World (Advance 2): chiado/estática no áudio

**Trilha:** D (compat de hospedeiros) · **Depende de:** — · **Repo:** gbaemu ·
**Task de diagnóstico** (causa raiz desconhecida) · **Fecha:** gbaemu#2

## Contexto

Achado do usuário em 2026-07-16 (validação de gameplay da task C6, ver memória
`gba-c6-gameplay-findings`): o áudio do SMW soa "meio chiado" — confirmado
PRÉ-EXISTENTE via A/B contra o commit pai de C6 (não é regressão do C6). Nenhum
diagnóstico foi feito ainda.

SMW usa save EEPROM (ver [[gba-game-compat]]) — é o único dos 5 jogos de
referência nessa categoria de save, mas isso é improvável de ter relação com
áudio; mencionado só pra descartar cedo se alguém suspeitar de interferência de
timing do EEPROM.

## Inclui — Fase 1 (diagnóstico)

1. Reproduzir e caracterizar o "chiado": é constante (todo o jogo) ou aparece em
   momentos específicos (menu, in-game, um efeito sonoro específico)? Pedir ao
   usuário se a caracterização inicial não bastar.
2. Usar `-Daudio.diag=1` (`AudioDiagnosticTest`/`measurePerFrameCost`, ver memória
   `gba-audio-status`) adaptado pro SMW (hoje é FireRed-specific — generalizar o
   ROM alvo) pra gerar um `.wav` e inspecionar clicks/railing/DC offset como já
   foi feito outras vezes nesse projeto (ver histórico em `gba-audio-status`: DC
   offset do PSG idle, DMA de áudio não recarregando, PSG 2 oitavas errado —
   TODOS já corrigidos; este é um bug NOVO, não repetir diagnóstico já feito).
3. Comparar o padrão de "chiado" com os já corrigidos (é ruído de alta frequência
   tipo clique de DMA? É railing tipo DC offset? É harmônico tipo problema de
   pitch?) pra não reabrir uma classe de bug já fechada por engano — se bater com
   um padrão já corrigido, é uma REGRESSÃO desses fixes anteriores, tratar
   diferente de um bug novo.
4. Isolar canal: desligar PSG/Direct Sound seletivamente (os toggles `DBG_DISABLE_*`
   já existiram no projeto pra isso antes, ver histórico) pra saber qual caminho é
   a fonte.

## Aceite (fase 1)

1. `.wav` capturado do trecho com chiado, inspecionado (waveform/espectro se
   necessário).
2. Canal/caminho de áudio isolado como fonte (PSG vs Direct Sound, e qual canal).
3. Se a causa for óbvia e pequena a partir do diagnóstico, correção pode entrar
   na mesma sessão (fase 2); senão, devolver com o achado documentado.

## Validação

Suíte gbaemu verde. Regressão guard novo se uma correção entrar nesta sessão
(mesmo padrão dos fixes anteriores em `GbaAudioTest`/`GbaDmaControllerTest`).

## Armadilhas

- Não é o mesmo bug do Metroid (D4) nem do glitch de batalha do Pokémon (D2) —
  jogos diferentes, sem relação óbvia; não presumir causa compartilhada sem
  evidência.
- Ver a lista de bugs de áudio JÁ corrigidos em `gba-audio-status` antes de
  investigar — évitar reabrir/reimplementar um fix que já existe.

## Achados da fase 1 (2026-07-16) — sem causa raiz encontrada, devolvido ao usuário

`SmwAudioDiagnosticTest` novo (`src/test/.../audio/`, `-Daudio.diag=1`, precisa de
`smw.gba` na raiz do módulo — copiado de `roms/smw.gba`, gitignored). 4 testes:
mix completo no título (15s), cada um dos 6 canais isolado no título, mix completo
em gameplay real (60s, auto-mash de START/A pra passar do título/file-select +
segurar RIGHT/A ocasional pra andar e pular), e cada canal isolado em gameplay.

- **Railing/clicks: zero** em toda captura (título e gameplay) — não bate com
  nenhum padrão já corrigido (DC offset do PSG idle, DMA não recarregando,
  pitch 2 oitavas errado). Ver `gba-audio-status` pros detalhes desses fixes.
- **PSG (CH1-4) mudo o jogo inteiro**, título E gameplay com pulos — este port
  de SMW (Super Mario Advance 2) parece rotear música E efeitos só por Direct
  Sound A/B. PSG descartado como fonte do chiado.
- **Espectro (FFT, bandas bass/mid/treble/ultra)** do mix não mostra energia
  elevada em agudos (~2% em 4-12kHz, ~0,5% acima de 12kHz) — sem assinatura de
  ruído branco/"hiss" clássico.
- **Único sinal não trivial**: a fração de saltos pequenos (10-40 de amplitude
  em 8 bits, abaixo do limiar de "click" de 100) do MIX combinado em gameplay
  (3,64%) é maior que a SOMA dos dois canais Direct Sound isolados
  (directA=0,46% + directB=0,63% = 1,09%). Pode ser só superposição normal de
  duas fontes de áudio independentes (não anômalo — mixagem soma variância) ou
  uma pista fraca de interação/aliasing entre os dois FIFOs A/B tocando em
  timers diferentes. **Não investigado a fundo** — não virou hipótese forte o
  bastante pra tentar um fix.

**Por que devolvido em vez de tentar um fix**: nenhum dos sinais acima é "óbvio
e pequeno" (critério de aceite #3) — não há uma assinatura clara tipo as dos
bugs já corrigidos. Precisa de caracterização melhor do usuário antes da
próxima sessão: quando exatamente o chiado é audível (título, in-game, um
efeito específico como moeda/pulo)? WAVs em `target/smw-audio-*.wav` (8 no
total) pra ouvir e comparar.

Suite gbaemu verde (`mvn -o test`, sem regressão nos 5 jogos de referência).

## FECHADA 2026-07-16 — sem problema real

Usuário ouviu os WAVs/jogou e confirmou que o áudio está aceitável — não é um
bug que precise de correção. Nenhuma ação adicional necessária.
