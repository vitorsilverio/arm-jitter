# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/);
o projeto segue [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [1.0.0] — 2026-08-15

Primeira versão publicada. Consolida o que já estava em produção nos emuladores
`gbaemu` e `ndsemu`.

### Adicionado
- Pipeline `cache → decode → lift IR → otimizar → emit` com três backends:
  `INTERPRETED_IR` (oráculo/debug), `JVM_BYTECODE` (ASM, default recomendado,
  tiered com tier frio interpretado + tier quente compilado, fallback `PER_OP`,
  compilação em pool de threads, execução condicional nativa, shifted-register
  nativo, register cache em locals, inline cache de 32K, encadeamento de blocos
  e superblocos de loop) e `TRUFFLE` (módulo opcional `arm-jitter-truffle`,
  compila de verdade em JVM sob JBR+Unchained/GraalVM).
- Arquiteturas guest de 32 bits: ARMv4T (GBA, produção), ARMv5TE (NDS, produção),
  ARMv6K, `ARMV6K_THUMB2` (Thumb-2), ARMv7-A + VFPv2 e o perfil M
  (ARMv6-M/ARMv7-M, `ExceptionModel` plugável com NVIC/VTOR/SysTick e
  semihosting) — todos completos e validados com binários ELF reais (torture
  handwritten e `gcc` real) no `armbox`.
- MMU/softmmu de 32 bits (épico B4.1): page-walk short-descriptor VMSA,
  domínios/AP, aborts precisos (FAR/FSR) nos três motores de execução, geração
  de tradução ciente do `BlockCache`/inline cache; validado com um kernel Linux
  ARMv5TE real (Debian) e busybox estáticos até um shell interativo no
  `virtual-arm-box`.
- AArch64 (épico B6): decoder A64 completo (base ISA inteira, FP/SIMD escalar,
  exclusivos), `Aarch64Core` com EL0/EL1 e aborts precisos, MMU v8
  (`TranslatingAddressSpace64`), backend ASM nativo (`jit64`) cobrindo todo
  `Ir64Op.Kind`; `armbox --arch=aarch64` roda binários ELF64 bare-metal.
- Biblioteca nativa (`arm_jitter.dll`/`.so`) com API C (`capi/`, `native-image
  --shared`), embutível por qualquer linguagem com FFI, backend
  `INTERPRETED_IR`.
- Depuração: `GdbServer` (stub GDB remote serial), trace listener, runtime de
  divergência (`divergenceCheckingArmThumb`) e harness de equivalência entre
  emissores (32 e 64 bits).

### Conhecido / fora de escopo desta versão
- Hospedeiro full-system AArch64 (`virt64`) ainda não fecha (`B6.6.6`) —
  bloqueado em toolchain/kernel `aarch64-linux-*` reais.
- Backend Truffle sob `native-image` ainda não compila blocos de verdade
  (bailout de partial evaluation sob SVM, `A7`/`A9 PR2`); `native-image`
  (perfil `native` do `armbox`) roda hoje só com o backend `INTERPRETED_IR`.
- Sem NEON/SIMD avançado; sem virtualização (EL2), TrustZone (EL3) ou LPAE.
