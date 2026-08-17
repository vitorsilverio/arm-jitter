# F3 — `virtual-arm-box --machine=raspi1`: Raspberry Pi 1 / Zero (BCM2835, ARMv6K)

**Trilha:** F (infra) · **Depende de:** F2 · **Repo:** virtual-arm-box · **Fecha:** virtual-arm-box#1
**Task LONGA (2–3 sessões).** Fatie pelos marcos M1/M2/M3 do Aceite: **um marco por
sessão/PR**, commitando o que funcionar. Se um marco não fechar, commite o anterior, escreva
o que foi descoberto no README e **PARE** — não improvise periférico não especificado aqui.

## Contexto

O `virtual-arm-box` (F2) tem hoje uma única máquina, `versatilepb`, com CPU ARMv5TE e boot
por **ATAGs**. O Raspberry Pi 1/Zero é o segundo degrau natural do ROADMAP do repo e traz
três coisas novas de uma vez:

- **ARM1176JZF-S = ARMv6K + VFPv2** — exatamente o preset `ArmArchitecture.ARM11_MPCORE`
  que a task B5.2 criou (ARMv6K + VFPv2, sem Thumb-2). Hoje o ARMv6K só foi validado em
  **user-mode** (armbox, binários ELF); nunca num kernel de sistema com MMU. Esta task é a
  primeira validação real de ARMv6K + `TranslatingAddressSpace` + `Cp15VmsaCoprocessor`.
- **Device Tree em vez de ATAGs** — os kernels Raspberry Pi modernos são DT. É a primeira
  vez que o projeto passa um DTB para um guest.
- **Assets baixáveis prontos**: kernel e DTB oficiais vêm compilados do repositório
  `raspberrypi/firmware`. Isso **contorna** o bloqueio de toolchain `arm-linux-*` que trava
  B4.0.3 item 3, B6.2 aceite #2 e B6.6.6 — nenhum compilador cruzado é necessário aqui.

## Objetivo

`virtual-arm-box --machine=raspi1 <kernel.img> <initramfs> [dtb]` leva um kernel Raspberry Pi
oficial até um shell `busybox` interativo, nos backends interpretado e JIT.

## Inclui

1. Aquisição e versionamento dos assets em `testdata/raspi1/` + `testdata/raspi1/README.md`
   documentando a proveniência exata (URL, commit/tag, sha256) — mesmo padrão do
   `testdata/README.md` que já existe para o versatilepb.
2. Periféricos BCM2835 mínimos, um arquivo por periférico em `device/bcm2835/`.
3. `Bcm2835Machine implements Machine`, registrada como `raspi1` no `Main`.
4. Testes de unidade por periférico + `Raspi1BootTest` (mesmo formato do
   `VersatilePbBootTest`).

## NÃO inclui (não fazer)

- **Sem GPU/VideoCore.** Nada de framebuffer, HDMI, `bcm2835_v3d`. O console é **serial**.
- Sem USB (`dwc_otg`), sem Ethernet (`smsc95xx`), sem SD/MMC real, sem PWM, sem I2C/SPI,
  sem DMA do BCM2835. Todos servidos por `OpenBus` (o padrão que o versatilepb já usa para
  o que está fora de escopo).
- Sem `start.elf`/`bootcode.bin` (o firmware VideoCore proprietário). O host **é** o
  bootloader: carrega kernel + DTB na RAM e salta, como faz o `-kernel` do QEMU.
- Sem Raspberry Pi 2/3/4 (Cortex-A7/A53, multi-core, GIC) — outra máquina, outra task.
- Sem mudança no `arm-jitter`. **Exceção**: se aparecer um bug real da lib (foi o que
  aconteceu 4 vezes em B4.1.5), corrigir lá em **commit separado**, com teste de regressão
  no arm-jitter, e revalidar gbaemu+ndsemu+armbox (G5).

## Especificação

### Assets (`testdata/raspi1/`)

| Arquivo | Origem | Observação |
|---------|--------|-----------|
| `kernel.img` | `https://github.com/raspberrypi/firmware` → `boot/kernel.img` | O **sem sufixo** é o de ARMv6 (Pi 1/Zero). `kernel7.img` é ARMv7/Pi 2 e `kernel8.img` é AArch64 — **não servem**. |
| `bcm2708-rpi-b.dtb` | mesmo repo, `boot/bcm2708-rpi-b.dtb` | DTB do Pi 1 modelo B. |
| `initramfs.cpio.gz` | reaproveitar o do versatilepb, se o `busybox-armv5l` rodar | ARMv5 executa em ARMv6 (compatibilidade para trás). Se não rodar, baixar `busybox-armv6l` de `https://busybox.net/downloads/binaries/` (a variante `armv6l` existe, foi assim que B4.1.5 resolveu o ARMv5). |

Fixar a **tag** do `raspberrypi/firmware` usada (ex.: `stable`/uma release datada) e o
`sha256` de cada arquivo no `README.md` do diretório. Baixar sempre a mesma tag torna o teste
reprodutível; "o último master" não.

### CPU do guest

```java
ArmArchitecture.ARM11_MPCORE   // B5.2: ARMv6K + VFPv2, sem Thumb-2
```

Registrar `VfpDecoder` e `CoprocessorDecoder` como o `VersatilePbMachine` já faz. O
BCM2835 tem **um** núcleo — não usar o monitor de exclusividade compartilhado (B5.1); o
default por-core basta.

### Mapa de memória (BCM2835, "low peripheral" `0x20000000`)

O ARM enxerga os periféricos em `0x20000000` (no Pi 2+ é `0x3F000000` — cuidado ao ler
documentação/código de Pi 2). Fonte: *BCM2835 ARM Peripherals* (datasheet oficial) e
`hw/arm/bcm2835_peripherals.c` / `hw/*/bcm2835_*.c` do QEMU — **transcrever do QEMU**, como
B4.1.5 fez com PL011/SP804/PL190, não escrever de memória.

| Base | Periférico | Precisa? |
|------|-----------|----------|
| `0x00000000` | RAM (512 MiB no Pi 1 B rev 2; use 256 MiB se o DTB pedir) | ✅ `PagedAddressSpace` |
| `0x20003000` | System Timer (contador de 1 MHz, 4 comparadores) | ✅ |
| `0x2000B200` | Interrupt controller (`ARMCTRL-IC`: básico + 2 bancos GPU) | ✅ |
| `0x2000B400` | ARM Timer (SP804-like, **não** é um SP804 de verdade) | ✅ |
| `0x2000B880` | Mailbox 0/1 (property channel) | ✅ mínimo — ver abaixo |
| `0x20200000` | GPIO | 🟡 só leitura/escrita de registrador, sem efeito |
| `0x20201000` | PL011 UART0 | ✅ **console** — reusar `device/Pl011Uart` que já existe! |
| `0x20215000` | AUX (mini-UART UART1 + SPI1/2) | 🟡 ver "console" abaixo |
| resto | — | `OpenBus` |

**Console:** o kernel do Raspberry Pi usa `ttyAMA0` (PL011) **ou** `ttyS0` (mini-UART),
dependendo de `console=` na cmdline e do DTB. Force o PL011 pela cmdline
(`console=ttyAMA0,115200`) e reaproveite a classe `Pl011Uart` **sem modificá-la** — é o
mesmo IP block ARM. Se o kernel insistir no mini-UART, implemente o AUX mini-UART mínimo
(registradores `AUX_MU_IO`/`AUX_MU_LSR`/`AUX_MU_IER` em `0x20215040`+) — mas só se
necessário, e documente por quê.

**Mailbox:** o kernel do Pi consulta a mailbox no boot (property channel, tags como
`GET_BOARD_REVISION`, `GET_ARM_MEMORY`, `GET_CLOCK_RATE`). Implementar apenas o suficiente
para o kernel não travar: aceitar a escrita, ler a estrutura de tags da RAM, marcar cada tag
desconhecida como respondida com valor 0 e ligar o bit de sucesso no cabeçalho. Se o kernel
ficar preso esperando resposta de uma tag específica, implemente **aquela** tag e anote no
Javadoc. Não implementar o channel de framebuffer.

### Protocolo de boot

O host é o bootloader (`hw/arm/boot.c` do QEMU é a referência, igual à B4.1.5):

- `kernel.img` é um **zImage** — copiar como está para `0x00008000` (o load address clássico
  do Pi; conferir contra o `hw/arm/raspi.c` do QEMU).
- DTB carregado num endereço alinhado a 4 bytes fora do caminho do kernel (o QEMU usa o topo
  da região de boot); passar o endereço em **`r2`**.
- `r0 = 0`, `r1 = machine type`. Com Device Tree, `r1` é ignorado pelo kernel moderno, mas
  passe `0xC42` (`BCM2708`, `arch/arm/tools/mach-types`) por segurança.
- CPU em modo **SVC**, ARM state, IRQ/FIQ mascaradas, MMU desligada — igual ao que o
  `VersatilePbMachine` já monta (copie o método de handoff de lá).
- A cmdline **não** vai em ATAG: vai no nó `/chosen/bootargs` do DTB. Duas opções, escolha e
  documente: (a) editar o `bootargs` do DTB carregado, em Java, com um patcher mínimo de
  FDT (o formato é simples: cabeçalho big-endian + blob de estrutura + bloco de strings); ou
  (b) confiar no `bootargs` que já vier no DTB. **Prefira (a)** — sem controlar a cmdline
  você não consegue apontar o console nem o `rdinit`.

### Modelo de tempo/IRQ

Copiar o padrão que já funciona no `VersatilePbMachine`: fatia de N blocos no `runSlice()`,
depois sondagem de `irqAsserted()` de cada periférico → controlador de IRQ → `ArmCore`.
**Não** introduzir callbacks síncronos periférico→core; o modelo por sondagem é decisão de
arquitetura já tomada no repo.

### Oráculo externo disponível (achado de 2026-08-15)

**O QEMU 8.0.0 está instalado** em `C:\Program Files\qemu\` — binários, sem fonte. Em
especial, `qemu-system-arm.exe` **boota `-M raspi1ap`/`-M versatilepb` com o mesmo kernel e o
mesmo DTB**. Quando o boot travar e não estiver claro se o defeito é do periférico, do DTB ou
do protocolo de handoff, **boote a mesma combinação no QEMU e compare o log serial**. É a
ferramenta de diagnóstico mais barata desta task — use antes de depurar às cegas.

## Passos

1. **Reconhecimento antes de escrever código** (obrigatório, ~1h): baixar os assets, rodar
   `file`/`readelf`/`hexdump` no `kernel.img`, decodificar o cabeçalho do DTB, e ler
   `hw/arm/raspi.c` + `hw/arm/bcm2835_peripherals.c` + `hw/intc/bcm2835_ic.c` +
   `hw/timer/bcm2835_systmr.c` + `hw/misc/bcm2835_mbox.c` do QEMU. **O código-fonte do QEMU
   NÃO está nesta máquina** (só os binários — ver abaixo): obtenha esses arquivos do
   repositório público (`https://gitlab.com/qemu-project/qemu`), como PL011/SP804/PL190 foram
   transcritos na B4.1.5. **Não escreva os periféricos de memória.**
2. Assets + `testdata/raspi1/README.md` com proveniência e sha256.
3. Periféricos, um por vez, cada um com teste de unidade próprio (espelhar
   `Pl011UartTest`/`Sp804DualTimerTest`/`Pl190VicTest`).
4. Patcher de FDT para o `bootargs` + teste de unidade (patch e releitura).
5. `Bcm2835Machine implements Machine`, registro `raspi1` no `Main`.
6. `Raspi1BootTest` com os marcos M1→M3 abaixo.

## Aceite (marcos, um por sessão/PR)

- [x] **M1** — o kernel imprime pelo console a mensagem de descompressão
      (`Uncompressing Linux... done, booting the kernel.`) nos dois backends. **Redefinido**:
      marcador real é `Booting Linux on physical CPU` (via `earlycon`) — o texto literal do
      enunciado não existe neste `kernel.img` oficial, ver `testdata/raspi1/README.md`.
- [x] **M2** — o log do kernel avança até `Freeing unused kernel memory` sem abort
      recursivo, nos dois backends. **Marcador ajustado**: `"Freeing unused kernel"` (prefixo
      estável) — este `kernel.img` (6.18.33) imprime `"Freeing unused kernel image (initmem)
      memory"`, ver Javadoc de `Raspi1BootTest`.
- [ ] **M3** — shell `busybox` interativo: o teste espera o prompt, digita
      `echo RASPI"1-SHELL-OK"` e exige a saída **sem as aspas** (o eco do tty sozinho não
      passa — mesma técnica do `VersatilePbBootTest`). **Sessão do CPRMAN (2026-08-17)**:
      `Bcm2835Cprman` mínimo corrige o `ETIMEDOUT`/*deferred probe* que atrasava o registro real
      de `ttyAMA0` (bug real, confirmado por trace de boot) — mas M3 continua NÃO fechado: um
      bloqueio novo e diferente (retry infinito de `mmc0`/`sdhost`, fora do escopo desta task,
      inundando o console bem mais rápido que o tempo real por causa da compressão de tempo do
      `Bcm2835SystemTimer`) impede confirmar se/quando o prompt aparece dentro de um orçamento de
      teste prático. Ver Javadoc de `Bcm2835Machine`/`Raspi1BootTest` para o achado completo e o
      próximo passo recomendado (estender `FdtPatcher` para desabilitar o nó `mmc@7e202000`).
- [x] `mvn -o test` verde no virtual-arm-box; `VersatilePbBootTest` **continua verde** (76
      testes, 2 skipped = M3×2).
- [x] `testdata/raspi1/README.md` documenta URL + tag + sha256 de cada asset.
- [ ] Índice do `tasks/README.md` atualizado (F3, com o marco alcançado se for parcial) — fora do
      escopo desta sessão (ver `tasks/FILA-EXECUCAO.md` para o relato desta sessão).

## Validação

`mvn -o test` no virtual-arm-box. Se algum arquivo do arm-jitter for tocado (correção de bug
real), aplicar G5 completo: arm-jitter + gbaemu + ndsemu + armbox verdes.

## Armadilhas

- **`kernel7.img`/`kernel8.img` são a ISA errada.** Só `kernel.img` (sem sufixo) é ARMv6.
- **Base de periféricos `0x20000000`, não `0x3F000000`.** Metade da documentação e do código
  que você vai achar na internet é de Pi 2/3. Conferir sempre contra `hw/arm/raspi.c`.
- **O ARM Timer do BCM2835 não é um SP804.** É "SP804-like" com registradores a mais e
  semântica diferente. **Não reuse `Sp804DualTimer`** — escreva `Bcm2835ArmTimer` separado.
  (O PL011, ao contrário, é o mesmo IP e **deve** ser reusado.)
- **O `Pl011Uart` tem FIFO de recepção de 16 posições e descarta o excedente**, como o
  hardware real. Digitar uma linha inteira de uma vez faz o guest receber só 16 bytes, sem o
  `\n`. O teste tem que digitar **um byte por lote de fatias** — armadilha já registrada em
  B4.1.5, custou uma rodada de teste lá.
- Se o boot travar num loop de abort, o histórico de B4.1.5 é o melhor guia de suspeitos:
  (1) operação de TLB de CP15 caindo em UNDEFINED, (2) privilégio user/priv não sincronizado
  com o modo do core, (3) lift adiantado de bloco encostando em página não mapeada,
  (4) feature de CPU faltando no preset. Leia a entrada de B4.1.5 na `FILA-EXECUCAO.md`
  inteira **antes** de depurar do zero.
- ARMv6K tem `LDREX`/`STREX` e `WFI` reais, que o ARMv5TE do versatilepb não exercitava num
  kernel. Um travamento em `WFI` provavelmente significa que nenhuma IRQ está sendo entregue
   — investigue o controlador de IRQ antes da CPU.
