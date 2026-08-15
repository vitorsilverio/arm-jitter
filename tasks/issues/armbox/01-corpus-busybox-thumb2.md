## Resumo

Falta um corpus real de **busybox Thumb-2** para validar o preset Thumb-2 com um binário de
userspace grande e de verdade.

## Estado

A task B4.0.3 fechou **parcial**: o item `hello-thumb2.c` (bitfields, `STRD`, `TBB`, `qsort`,
compilado com gcc real) fechou — e de quebra achou e corrigiu um bug real do arm-jitter
(`ARMV7A` decodificava `UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` como UNDEFINED em encoding
**Thumb-2**, porque os decoders eram construídos com o objeto de features errado).

O item 3 (busybox Thumb-2) **não** fechou.

## Bloqueio

Precisa de um toolchain `arm-linux-*` real (musl/glibc), que não existe nesta máquina:
Windows/MSYS2 sem WSL configurado, e o devkitARM instalado é **bare-metal** (sem libc de
userspace). O busybox.net publica binário `armv5l` pronto (foi o que destravou o
`virtual-arm-box`), mas não uma variante Thumb-2.

## O que destrava

Um `arm-linux-gnueabihf-*` real, ou WSL configurado.

## Consequência

Bloqueia a task **B4.0.5** (armbox fase 3: `fork`/`execve`/`pipes`/`wait`), que precisa desse
corpus.

## Labels sugeridas

`blocked:asset`, `infra`
