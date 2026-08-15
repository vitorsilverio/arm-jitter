> **Nota:** o `virtual-arm-box` ainda **não tem repositório no GitHub** (decisão do usuário,
> 2026-08-15). Esta issue fica versionada aqui até haver onde postá-la.

## Resumo

A máquina `versatilepb` boota Linux até shell, mas com dois desvios conscientes em relação ao
que a RFC-SOFTMMU especificava.

## Os desvios

1. **Kernel**: Debian 3.2 pré-compilado (`vmlinuz-3.2.0-4-versatile`) com **ATAGs**, em vez
   de um `versatile_defconfig` mainline com **Device Tree**.
2. **CPU**: ARM926EJ-S / **ARMv5TE** (+ `ArmFeature.VFPV2`, o que o `-cpu arm926` do QEMU
   expõe), em vez do ARM1176 / **ARMv6K** que a decisão 2 da RFC pedia.

## Por que

Não são bugs — é falta de toolchain. Fechar o desvio exige um `arm-linux-gnueabihf-*` real ou
WSL configurado, exatamente o mesmo bloqueio das issues de corpus do `armbox`.

O kernel real disponível determinou a CPU: o `busybox-armv5l` de `testdata/` é **hard-float**
(`EF_ARM_ABI_FLOAT_HARD`) e usa prólogos VFP (`VPUSH {d8-d14}`) não gateados por `HWCAP_VFP`
— sem VFP o PID 1 tomava SIGILL.

## Caminho alternativo (já planejado)

A task **F3** (`--machine=raspi1`) contorna o bloqueio por outro lado: os kernels e DTBs do
Raspberry Pi são **baixáveis prontos** do repositório `raspberrypi/firmware`, e o Pi 1 é
ARM1176JZF-S/ARMv6K — exatamente a CPU e o mecanismo (Device Tree) que a RFC queria, sem
precisar compilar kernel nenhum.

## Referência

`virtual-arm-box/testdata/README.md` (seção "Blocked") e o Javadoc de `VersatilePbMachine`.

## Labels sugeridas

`infra`, `blocked:asset`
