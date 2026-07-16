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
