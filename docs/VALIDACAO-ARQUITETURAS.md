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

## Status (2026-07-15)

| Preset | N1 | N2 | N3 | N4 | Próximo passo |
|--------|----|----|----|----|---------------|
| `ARMV4T` (GBA) | ✅ | — (coberto por N4) | ✅ busybox-armv5l parcial | ✅ gbaemu 5 jogos | — |
| `ARMV5TE` (NDS ARM9) | ✅ | — | ✅ hello/busybox (B4.0) | ✅ ndsemu (JUS/MKDS/SM64DS) | — |
| `ARMV6K` | ✅ (B1.1-B1.6) | ✅ armv6k-torture (B4.0.1) | 🟡 só hello-armv6k (sinal fraco: sem instrução v6 de compilador) | ⬜ (3DS futuro) | busybox/gcc armv6k real — entra de carona no linuxbox (B4.1.5, kernel versatile é v6) |
| `ARMV6K_THUMB2` | ✅ (B2.1-B2.8 fecharam) | ✅ thumb2-torture (B2.6 estendeu p/ as 4 extensões) | 🟡 hello-thumb2.elf gcc real (B4.0.3, rodado sob `ARMV7A` por causa de bitfields — ver nota); busybox thumb2 NÃO fechado (sem toolchain `arm-linux-*` disponível no ambiente) | ⬜ | busybox Thumb-2 (toolchain `arm-linux-*` real, ex. WSL) fecha o N3 desta linha |
| `ARMV7A` | ✅ (B3.1-B3.6) | ✅ armv7a-torture (B3.7, 28 checagens: inteiro v7 + VFP) | ✅ hello-float.elf gcc hard-float real (série de Leibniz em `double`, B3.7) + hello-thumb2.elf (B4.0.3, bitfields/TBB/IT/qsort em Thumb-2; achou e corrigiu um bug de fiação — `UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` em Thumb-2 viravam UNDEFINED no preset público, ver `ArmArchitecture.ARMV7A`) | ⬜ | épico B3 FECHADO — próximo consumidor real é o 3DS (B5, MPCore) |
| `ARMV6M`/`ARMV7M` (Cortex-M) | ✅ (B7.1-B7.4) | ✅ cortexm-torture m0/m3 (B7.5: reset/MSP, SVC em MSP+PSP, SysTick, PendSV pendido de outro handler, PRIMASK, MRS/MSR MSP/PSP/CONTROL/PRIMASK; m3 acrescenta MOVW/MOVT/SDIV/UDIV/UBFX/LDREX+STREX) | ✅ hello-cortexm.elf gcc real (semihosting `BKPT 0xAB`, sem CRT/libc) | ⬜ | épico B7 FECHADO (runner bare-metal `armbox --machine=cortex-m` novo — flash/RAM fixos via `PagedAddressSpace`, boot pela tabela de vetores, `BkptDispatcher` novo no core para semihosting); N4 = um consumidor real (nenhum ainda) |
| MMU/full-system (ARMv6 VMSA) | ⬜ | ⬜ | ⬜ | ⬜ | épico B4.1 (N3/N4 = kernel Linux versatile até shell no linuxbox) |
| AArch64 | ⬜ | ⬜ | ⬜ | ⬜ | épico B6 (B6.2 = hello arm64; B6.3 = busybox arm64) |

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
