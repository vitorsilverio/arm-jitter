## Resumo

Falta um corpus real de **busybox AArch64** para validar o backend A64 com um binário de
userspace grande.

## Estado

A task B6.2 fechou **parcial**. O aceite #1 fechou: `hello-aarch64.s` escrito à mão (`svc`
write+exit cruas, sem libc), montado com `aarch64-none-elf-gcc -nostdlib -static` do
devkitA64, roda em `armbox --arch=aarch64` e sai com código 42.

O aceite #2 (busybox aarch64) **não** fechou.

## Bloqueio

- O busybox.net **não** publica binário estático aarch64/arm64 — só `armv8l`, que é ARM
  **32 bits** em silício v8 (ISA errada).
- O devkitA64 instalado é bare-metal, sem libc de userspace para compilar da fonte.

## O que destrava

Uma fonte confiável de busybox estático arm64, ou um toolchain `aarch64-linux-*`
(musl/glibc) real.

## Efeito colateral

O bench "busybox ≥3× o interpretador", que era a meta de perf do backend ASM 64-bit (épico
B6.4/B6.5), segue sem poder ser medido.

## Labels sugeridas

`blocked:asset`, `infra`
