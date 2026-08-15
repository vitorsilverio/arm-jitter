# D6 — gbaemu: animação da BIOS muito lenta e interrompida no meio ⚠️ MODELO FORTE

**Trilha:** D · **Depende de:** — · **Repo:** gbaemu · **NÃO passar a agente comum**
(investigação de timing; esta spec dá o protocolo e as hipóteses, não a receita)
**Fecha:** gbaemu#4

## Sintoma (usuário, confirmado 2026-07-16)

Com a BIOS REAL habilitada (Settings), a animação de boot (logo Nintendo/GBA +
som) roda muito devagar e é interrompida no meio, pulando para o jogo.
**Backend-independente: idêntico em INTERPRETED e ASM** (re-testado 2026-07-16 —
a suspeita antiga de ser coisa do JIT caiu). Pré-existente, versão de origem
desconhecida.

## Fatos conhecidos

- gbaemu roda a BIOS real só quando configurado; o caminho HLE (SWI próprios) não
  exibe a animação — o bug é exclusivo do boot com BIOS real.
- "Lenta" e "interrompida" juntas sugerem DOIS efeitos, possivelmente da mesma
  causa: a animação avança por frames de V-blank (lenta = frames demorando ou
  esperas erradas) e o handoff BIOS→jogo acontece por tempo/estado, não pelo fim
  da animação (interrompida = o jogo assume enquanto a animação ainda roda —
  no hardware real a BIOS só entrega quando o logo termina E o cartucho valida).

## Hipóteses, em ordem (verificar uma a uma, registrando o resultado)

1. **Waitstates/prefetch da região da BIOS (0x0000_0000) e de ROM durante o
   boot**: se cada fetch da BIOS custa ciclos errados (ex. tratado como ROM com
   waitstate em vez de 32-bit 1-ciclo), a animação inteira desacelera na
   proporção observada. Medir: contador de ciclos consumidos até o primeiro
   acesso ao cartucho vs os ~160 frames que o boot real leva.
2. **Timers/IntrWait durante o boot**: a BIOS usa VBlankIntrWait e timers para o
   som do logo; o fix antigo de "VBlankIntrWait acordando com qualquer IRQ" (ver
   memória `gba-game-compat`) foi no caminho HLE — conferir se o caminho REAL
   (IRQ handler da BIOS + IE/IF/IME) tem o mesmo problema em outra forma.
3. **Handoff prematuro**: comparar o PC ao longo do boot com o esperado (a BIOS
   real só salta para 0x0800_0000 após a validação do logo) — se o gbaemu injeta
   estado "skip BIOS" parcial mesmo com BIOS real ligada, a animação é cortada.

## Protocolo de diagnóstico

Headless com `--debug-state` + trace de PC por faixa (BIOS vs ROM) por frame;
comparar contra mGBA/hardware (vídeos de referência do boot real: ~3,2s até o
"ping"). O `GdbServer` do arm-jitter serve para pausar no handoff.

## Aceite

Animação completa em velocidade correta (~3s, som "ping" no tempo certo), jogo
assume só depois do fim; idêntico nos 2 backends; suite gbaemu + gba-tests verdes.
