# D1 — GBA: RTC via GPIO do cartucho (Pokémon Emerald e afins)

**Trilha:** D (compat de hospedeiros) · **Depende de:** — · **Repo:** gbaemu

## Contexto

Cartuchos GBA com RTC (Pokémon Ruby/Sapphire/Emerald, Boktai, Sennen Kazoku)
expõem um chip S-3511A por 3 pinos de GPIO mapeados DENTRO da região de ROM:
`0x080000C4` (dados), `0x080000C6` (direção), `0x080000C8` (enable). Sem isso,
Emerald até roda mas relógio/eventos baseados em tempo quebram (berry growth,
maré, "the internal battery has run dry"). O gbaemu não implementa nada disso
hoje. Referência única e suficiente: GBATEK, seções "GBA Cart I/O Port (GPIO)" e
"GBA Cart Real-Time Clock (RTC)".

## Inclui

1. **GPIO do cartucho** (`cartridge`/pacote do backup atual): leitura/escrita nos
   3 registradores acima interceptada ANTES do fallback de leitura de ROM, SÓ
   quando o cartucho tem RTC (detecção: lista por game code no mesmo lugar onde o
   override A2CE→SRAM do Castlevania já vive — adicionar `AXVE`/`AXPE`/`BPEE` +
   os Boktai `U3IJ`/`U32J`/`U33J`; ver como `CartridgeBackup` decide hoje e
   espelhar). Registro `0xC8` bit 0 = enable (quando 0, leituras devolvem o
   comportamento de ROM normal — jogos testam isso).
2. **S-3511A**: protocolo serial de 3 fios (SCK/SIO/CS nos bits 0/1/2) — máquina
   de estados de comando de 8 bits (LSB-first, com o nibble de comando `0110`):
   reset (0), status (1), data/hora (2), hora (3), alarme/IRQ fora do escopo
   (devolver 0 e anotar). Data/hora em BCD, 7 bytes (ano 00-99, mês, dia,
   dia-da-semana, hora com bit AM/PM conforme modo 12/24 do status, min, seg),
   lidos do relógio do HOST (`java.time.LocalDateTime.now()`) com um offset
   persistido opcional — v1: sem offset, hora real direto, documentado.
3. **Save state**: o estado da máquina serial (comando corrente, bit shift,
   direção dos pinos) entra no `.ss` (bump de versão seguindo o padrão v2 atual);
   a HORA não é salva (vem do host — comportamento igual a hardware com bateria).
4. **GUI**: nada novo (sem UI de ajuste de hora na v1 — anotar como follow-up se
   o usuário pedir).

## Testes mínimos

1. Máquina serial pura (unit): sequência de escrita GPIO que o Emerald usa
   (transcrever do GBATEK: CS↑, comando status, leitura) → bytes esperados
   bit a bit, com um relógio FAKE injetável (não `now()` em teste).
2. Direção de pinos: ler pino configurado como saída devolve o último valor
   escrito; enable=0 volta a ler como ROM.
3. Detecção por game code: `BPEE` liga RTC; FireRed (`BPRE`) NÃO (regressão — os
   5 jogos de referência continuam com o comportamento atual).
4. Save state ida-e-volta no meio de uma transação serial.
5. Validação do usuário: Emerald — criar save, esperar/ajustar relógio do host,
   verificar evento de tempo (berry/maré) e ausência de "battery has run dry".

## Armadilhas

- Os endereços de GPIO ficam DENTRO do espaço de ROM — se C6 (PagedAddressSpace)
  já tiver sido feita, a página da ROM que contém `0xC4-0xC8` vira `mapHandler`
  quando o cartucho tem RTC; se não, o if vai no caminho de leitura de ROM atual.
  As duas ordens de execução (D1 antes/depois de C6) devem funcionar — dizer no
  PR qual foi.
- Leitura de GPIO só funciona com o bit "read enable" (0xC8) — jogos leem 0xC4
  com enable desligado esperando dados de ROM; devolver ROM nesse caso (teste 2).
- BCD: 0x59 minutos = 59, não 89 — conversão nos DOIS sentidos com teste.

## Resultado

✅ Concluída (repo gbaemu; protocolo verificado contra o GBATEK real via fetch
direto — "GBA Cart I/O Port (GPIO)", "GBA Cart Real-Time Clock (RTC)" e "DS
Real-Time Clock (RTC)" [o GBA reaproveita quase todo o protocolo serial do NDS,
só troca a tabela de comandos e move o bit AM/PM de hour.bit6→bit7].
`S3511aRtc` novo [`cartridge/rtc/`]: máquina de estados do chip S-3511A só com
os 3 fios SCK/SIO/CS [`updatePins`], LSB-first, byte de comando `0110 CCC D`
decodificado sem depender de nenhum estado do GPIO — não sabe nada sobre
Direção, isso é responsabilidade de quem chama. **v1 (decisão do próprio
enunciado da task): data/hora sempre lida AO VIVO do `GbaRtcClock` no instante
do comando** — sem offset, sem campos internos de ano/mês/dia persistidos; o
único registrador realmente stateful é o de controle [modo 12/24h e
power-off auto-clear-on-read, `CONTROL_RESET_VALUE=0x00` para nunca mostrar
"battery has run dry" sem ter modelado perda de energia real]. `GbaRom` ganha
uma janela de GPIO opcional [0x080000C4-C9, só interceptada quando
`rtc != null` — G3 intacto para os 5 jogos de referência]: leitura de pino de
saída = latch simples, leitura do pino SIO como entrada = `rtc.sioReadback()`,
gate de habilitação global via bit0 do registrador de controle do GPIO
[distinto do registrador de controle DO CHIP — nomenclatura cuidada no código
para não confundir as duas camadas]. `GbaRtcDetector` [game code, mesmo
padrão do override A2CE→SRAM]: `AXVE`/`AXPE`/`BPEE` [Ruby/Sapphire/Emerald] +
`U3IJ`/`U32J`/`U33J` [Boktai]; `GbaCartridge.hasRtc()` novo. Save state:
`SAVE_STATE_VERSION` 2→3, bloco do RTC [fase/bit-shift/comando/direção/buffer
de parâmetro/registrador de controle — NÃO a hora] escrito só quando
`cartridge.hasRtc()`, resolvido via `bus.find(GbaRom.class).rtc()` [sem mudar
a assinatura do construtor de `GbaConsole`]. Armadilha do enunciado sobre
ordem D1×C6 resolvida: como `GbaRom.contains()` não mudou, a tabela de páginas
de C6 [`probeBucketMembers`, que só sonda os EXTREMOS do bloco] continua
enxergando `GbaRom` do jeito que já enxergava antes — nenhuma mudança em
`GbaBus` foi necessária, D1 funciona igual antes/depois de C6. 25 testes
novos [`S3511aRtcTest` 6 — protocolo puro com relógio FAKE, BCD 59→0x59 não
89/0x3B, wrap de meio-dia em modo 12h, reset limpa o registrador de controle;
`GbaRomGpioTest` 4 — regressão sem RTC, gate de habilitação [teste 2 da
task], readback de saída, SIO como entrada fim-a-fim; `GbaRtcDetectorTest` 4
— jogos com/sem RTC incl. FireRed; `GbaConsoleRtcSaveStateTest` 1 — save
state NO MEIO de uma transação serial, retomada com sucesso após reload
[teste 4 da task]]. Suite gbaemu 231 verde [219+12 já contando testes de
tasks anteriores]. arm-jitter não tocado nesta task — gate de regressão é só
gbaemu [G5 não se aplica, sem mudança na lib compartilhada].
