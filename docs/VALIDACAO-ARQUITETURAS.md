# Matriz de validação por arquitetura

**Pergunta que este documento responde objetivamente: "a arquitetura X realmente
funciona no arm-jitter?"** — com critérios binários, comandos reprodutíveis e sem
depender de julgamento. Atualizar a tabela de status ao fechar cada task que mude
uma célula (a task correspondente sempre cita este arquivo no Aceite).

## Os 4 níveis (cada um subsume os anteriores)

| Nível | O que prova | Como rodar |
|-------|-------------|-----------|
| **N1 — Unidades + equivalência** | Cada instrução decodifica/executa igual ao oráculo (G1) nos 2 backends | `mvn -o test` no arm-jitter (JBR 25): suíte + `BlockEquivalenceHarness` |
| **N2 — Torture ELF handwritten** | Sequências reais auto-verificáveis, JIT×interp×check idênticos, e o harness DETECTA regressão (gêmeo `-broken`) | armbox: `ArmV6TortureTest`/`Thumb2TortureTest`/... (JUnit roda os 3 backends; exit 0 e `-broken` ≠ 0) |
| **N3 — Binário de compilador/real** | Código que nós NÃO escrevemos (gcc/busybox/kernel/firmware) roda com stdout/exit corretos nos 3 backends | armbox `--arch=<x>` + `--interp` + `--check` com o binário do testdata |
| **N4 — Consumidor em produção** | Um emulador/hospedeiro real usa o preset com carga de verdade | suites gbaemu/ndsemu; jogos/kernel/firmware validados pelo usuário |

Regra de ouro: **uma arquitetura só é anunciada como "suportada" no README com N3
verde.** N1/N2 = "implementada". N4 = "provada em produção".

## Status (2026-07-31)

| Preset | N1 | N2 | N3 | N4 | Próximo passo |
|--------|----|----|----|----|---------------|
| `ARMV4T` (GBA) | ✅ | — (coberto por N4) | ✅ busybox-armv5l parcial | ✅ gbaemu 5 jogos | — |
| `ARMV5TE` (NDS ARM9) | ✅ | — | ✅ hello/busybox (B4.0) | ✅ ndsemu (JUS/MKDS/SM64DS) | — |
| `ARMV6K` | ✅ (B1.1-B1.6) | ✅ armv6k-torture (B4.0.1) | 🟡 só hello-armv6k (sinal fraco: sem instrução v6 de compilador) | ✅ MPCore (3DS/Pi1, B5.2, preset `ARM11_MPCORE`, sem emulador hospedeiro ainda) | busybox/gcc armv6k real — o `linuxbox` (B4.1.5) acabou rodando ARMv5TE (ARM926EJ-S), não v6: continua pendente |
| `ARMV6K_THUMB2` | ✅ (B2.1-B2.8 fecharam) | ✅ thumb2-torture (B2.6 estendeu p/ as 4 extensões) | 🟡 hello-thumb2.elf gcc real (B4.0.3, rodado sob `ARMV7A` por causa de bitfields — ver nota); busybox thumb2 NÃO fechado (sem toolchain `arm-linux-*` disponível no ambiente) | ⬜ | busybox Thumb-2 (toolchain `arm-linux-*` real, ex. WSL) fecha o N3 desta linha e destrava B4.0.5 (armbox fork/pipes) |
| `ARMV7A` | ✅ (B3.1-B3.6) | ✅ armv7a-torture (B3.7, 28 checagens: inteiro v7 + VFP) | ✅ hello-float.elf gcc hard-float real (série de Leibniz em `double`, B3.7) + hello-thumb2.elf (B4.0.3, bitfields/TBB/IT/qsort em Thumb-2; achou e corrigiu um bug de fiação — `UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` em Thumb-2 viravam UNDEFINED no preset público, ver `ArmArchitecture.ARMV7A`) | ✅ MPCore (3DS/Pi1, B5.2, VFPv2 do mesmo preset) | épico B3 FECHADO — próximo consumidor real é o emulador hospedeiro do 3DS/Pi (fora do arm-jitter) |
| `ARMV6M`/`ARMV7M` (Cortex-M) | ✅ (B7.1-B7.4) | ✅ cortexm-torture m0/m3 (B7.5: reset/MSP, SVC em MSP+PSP, SysTick, PendSV pendido de outro handler, PRIMASK, MRS/MSR MSP/PSP/CONTROL/PRIMASK; m3 acrescenta MOVW/MOVT/SDIV/UDIV/UBFX/LDREX+STREX) | ✅ hello-cortexm.elf gcc real (semihosting `BKPT 0xAB`, sem CRT/libc) | ⬜ | épico B7 FECHADO (runner bare-metal `armbox --machine=cortex-m` novo — flash/RAM fixos via `PagedAddressSpace`, boot pela tabela de vetores, `BkptDispatcher` novo no core para semihosting); N4 = um consumidor real (nenhum ainda) |
| MMU/full-system (ARMv6 VMSA) | ✅ (B4.1.1-B4.1.4: `TranslatingAddressSpace`, `Cp15VmsaCoprocessor`, aborts precisos nos 3 motores, `translationGeneration`) | ✅ `MemoryAbortEquivalenceTest`/`ArmCoreMemoryAbortTest` (B4.1.3) | ✅ kernel Debian real (`vmlinuz-3.2.0-4-versatile`) + initramfs busybox-armv5l bootam no `linuxbox` (B4.1.5) até **shell interativo** nos backends INTERPRETED e JIT, com o shell respondendo a comando digitado (`VersatilePbBootTest`) | ⬜ | épico B4.1 ✅ FECHADO (2026-08-14). Resta só o desvio de toolchain: kernel Debian 3.2/ATAGs/ARM926EJ-S em vez de `versatile_defconfig` mainline+DTB/ARM1176 — precisa de `arm-linux-gnueabihf-*` ou WSL |
| AArch64 | ✅ (B6.1-B6.5: decoder A64 completo, `Aarch64Core`, executor interpretado, backend ASM `jit64` sem lacunas de `Ir64Op.Kind`, FP/SIMD escalar) | — (sem torture A64 dedicado ainda; corpus real via `aarch64-none-elf-as`/`objdump` cobre decode instrução a instrução) | 🟡 hello-aarch64.elf bare-metal real (devkitA64, B6.2 aceite #1) ✅; busybox aarch64 real (B6.2 aceite #2) NÃO fechado — só existe `armv8l` (ISA errada) no busybox.net e o devkitA64 é bare-metal | ⬜ | épico B6 quase fechado — só falta **B6.6.6** (hospedeiro `virt64`: kernel arm64 real + GICv2/v3/PSCI/DTB, mesmo bloqueio de toolchain do B6.2 aceite #2) |

## Convenções dos artefatos de teste (obrigatórias para tasks novas)

1. **Torture test** (`testdata/<arch>-torture.s` no armbox): auto-verificável,
   exit-code único por checagem (identifica QUAL falhou), `.ltorg` fora do fluxo,
   fonte + ELF versionados + entrada no `build-testdata.ps1`.
2. **Gêmeo `-broken`**: cópia com UM valor esperado errado; o JUnit prova exit ≠ 0
   ("teste do teste" — sem isso um harness quebrado passa em silêncio).
3. **Binário de compilador**: fonte + comando exato de build no `build-testdata.ps1`
   + verificação por objdump das instruções-alvo anexada no PR.
4. **3 backends sempre**: JIT (default), `--interp`, `--check` (divergence) — o
   `--check` é o que transforma qualquer binário em teste de equivalência gratuito.
5. Firmware bare-metal (perfil M): saída/exit via semihosting (`BKPT 0xAB`,
   SYS_WRITE0/SYS_EXIT) — B7.5 define o padrão.
