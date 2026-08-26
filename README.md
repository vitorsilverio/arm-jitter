# arm-jitter

> 🎯 **Meta do projeto**: emular ARM 100%. Se uma arquitetura/feature ARM existe — de v4T até a
> mais recente AArch64/ARMv9.x, qualquer perfil, qualquer extensão opcional real — ela é alvo,
> cedo ou tarde; nada fica de fora por parecer grande ou raro demais. Ver a regra completa no
> topo de [`tasks/README.md`](tasks/README.md).

[![CI](https://github.com/vitorsilverio/arm-jitter/actions/workflows/ci.yml/badge.svg)](https://github.com/vitorsilverio/arm-jitter/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.vitorsilverio/arm-jitter)](https://central.sonatype.com/artifact/dev.vitorsilverio/arm-jitter)

Biblioteca Java 25 para executar, depurar e compilar (JIT) blocos ARM/THUMB em emuladores de Game Boy Advance, Nintendo DS e outros dispositivos ARM.

O projeto não possui `Main`: ele é um core auxiliar para ser embutido por um emulador hospedeiro. É usado em produção pelo gbaemu e pelo ndsemu.

## Estado atual

Pacotes principais:

- `core`: registradores, CPSR, modos bancados, SPSR, exceções e avaliação condicional; banco VFP (`VfpRegisters` S/D + `FpscrRegister`), monitor de exclusividade compartilhável (`ExclusiveMonitor`) e modelo de exceção plugável (`ExceptionModel`, base do perfil M).
- `memory`: barramento abstrato `AddressSpace` + invalidação SMC; `PagedAddressSpace` (dispatch O(1) por página, usado pelo gbaemu).
- `decoder`: decodificação ARM/THUMB.
- `arch`: `ArmArchitecture`/`ArmFeature` — presets `ARMV4T` e `ARMV5TE` e quirks por arquitetura.
- `ir` / `ir.opt`: representação intermediária imutável + otimizador (constant fold, DCE, flag merge).
- `jit`: runtime tiered, cache de blocos com inline cache, encadeamento de blocos com budget de ciclos, superblocos de loop e warm-start (`hotBlockKeys`/`precompile`).
- `codegen` / `codegen.jvm`: emissores de código (interpretado, bytecode JVM via ASM) + harness de equivalência.
- `coprocessor`: barramento de coprocessadores (CP15 etc. ficam no hospedeiro).
- `swi`: callbacks de SWI sem obrigar entrada na BIOS.
- `debug`: `GdbServer` (stub GDB remote serial para o ARM32) + `Gdb64Server` (mesma coisa para o
  AArch64) e trace listener.

O interpretador é a referência de semântica (G1); todo backend compilado passa em
harness de equivalência e em runs longos de divergence-checking com ROMs reais.

O core já possui bancos de `SP/LR` por modo, banco FIQ para `r8-r14`, `SPSR` por modo privilegiado, entrada real de `SWI` no vetor `0x08` quando não há handler host registrado, entrada de `IRQ` no vetor `0x18` quando `interruptLine` está ativa e o bit I do CPSR está limpo, e entrada de instrução indefinida no vetor `0x04` para opcodes ainda não implementados.
Também há estado explícito de `HALT/STOP` para integração com registradores de I/O do dispositivo e waitstates opcionais por acesso de memória via `AddressSpace.accessCycles(...)`.

## Arquiteturas e features

Presets prontos em `ArmArchitecture` (`arch` package) — cada um liga um conjunto de
`ArmFeature`s e, quando aplicável, extensões de decoder:

| Arquitetura | Guest alvo | Status |
|-------------|-----------|--------|
| `ARMV4T` (ARM7TDMI) | GBA | ✅ produção (gbaemu) — ARM/THUMB completo, emissão ASM nativa |
| `ARMV5TE` (ARM9E) | NDS | ✅ produção (ndsemu) — `BLX`/`CLZ`/DSP multiplies/saturating/`LDRD`/`STRD`, emissão ASM nativa |
| `ARMV6K` | 3DS (núcleo ARM11), Raspberry Pi 1/Zero | ✅ decoder+IR+interpretador+ASM nativo completos (extend/reverse/UMAAL, SIMD paralelo, PKH/SAT/USAD8, LDREX/STREX/CLREX, CPS/SETEND/WFI); validado com binário ELF real no `armbox` |
| `ARMV6K_THUMB2` | subconjunto de ARMv7-A Thumb-2 | ✅ Thumb-2 completo (épico B2): decoder 32-bit, IT blocks, branches/TBB/TBH, paridade de multiplicação/extend/saturação, LDREX/STREX.W, PLD/PLI; validado com binário real no `armbox` |
| `ARMV7A` | Linux/Android ARMv7 user-mode | ✅ produção (épico B3 fechado) — inteiro v7 (MOVW/MOVT, bitfield, SDIV/UDIV, RBIT, MLS, barreiras) + VFPv2 (banco S/D, FPSCR, decoder CP10/11 ARM+Thumb-2, executor interpretado, emissão ASM nativa) completos; user-level only (sem MMU/CP15-VMSA, sem NEON); validado com torture ELF handwritten E binário `gcc` hard-float real (série de Leibniz em `double`) no `armbox`, os 3 backends (JIT/interpretado/divergence-check) idênticos |
| Perfil M (Cortex-M) | Firmware ARMv6-M/v7-M | ✅ produção (épico B7 fechado, B7.1-B7.5) — `ExceptionModel` plugável, MSP/PSP/xPSR/stacking/EXC_RETURN, NVIC/VTOR/SysTick, `MRS`/`MSR` SYSm + `CPS`, presets `ARMV6M`/`ARMV7M`; validado no `armbox --machine=cortex-m` com firmware torture m0/m3 + `hello-cortexm.c` (gcc real, sem CRT) e semihosting (`BKPT`) |
| MMU / full-system 32-bit | Kernel Linux ARMv6/v7 | ✅ épico B4.1 completo (2026-08-14) — `TranslatingAddressSpace`/`Cp15VmsaCoprocessor`/aborts precisos com FAR/FSR nos 3 motores; `virtual-arm-box` roda um kernel Linux (Debian, ARMv5TE/versatilepb) real até um shell `busybox` interativo nos backends INTERPRETED e JIT; ver [RFC-SOFTMMU](docs/RFC-SOFTMMU.md) |
| 3DS (periféricos) | Novo emulador irmão | ✅ lado arm-jitter completo — ARMv6K (B1) + VFPv2 (B3) + monitor de exclusividade global (B5.1) + preset `ArmArchitecture.ARM11_MPCORE` (B5.2, ARMv6K+VFPv2 sem Thumb-2); falta só o emulador hospedeiro (periféricos/timing MPCore/segundo core) |
| AArch64 | Linux/Android arm64 | 🟡 épico B6 quase completo (B6.1-B6.6.5 ✅) — decoder A64 (base ISA inteira, FP/SIMD escalar, exclusivos), `Aarch64Core`/EL0-EL1/aborts, MMU v8 (`TranslatingAddressSpace64`), backend ASM nativo (`jit64`) cobrindo todo `Ir64Op.Kind`; falta só **B6.6.6** (hospedeiro `virt64` até shell, bloqueado em kernel/toolchain `aarch64-linux-*` reais) |

Backend Truffle/GraalVM (compilação alternativa, mesma IR — ver seção abaixo): ✅
funcional em JVM, 🟡 native-image roda mas ainda não compila blocos reais.

Índice completo e status task-a-task: [tasks/README.md](tasks/README.md). Plano
narrativo por trilha: [ROADMAP.md](ROADMAP.md).

## Uso básico

```java
AddressSpace memory = new AddressSpace() {
    @Override public int read8(int address) { return 0; }
    @Override public int read16(int address) { return 0; }
    @Override public int read32(int address) { return 0; }
    @Override public void write8(int address, int value) { notifyWrite(address); }
    @Override public void write16(int address, int value) { notifyWrite(address); }
    @Override public void write32(int address, int value) { notifyWrite(address); }
};

ArmCore core = new ArmCore(memory, SwiDispatcher.empty());
core.setProgramCounter(0x08000000);
core.step();
core.step(16);
```

Coordenadas Maven atuais:

```xml
<dependency>
    <groupId>dev.vitorsilverio</groupId>
    <artifactId>arm-jitter</artifactId>
    <version>1.2.0</version>
</dependency>
```

Guia de uso completo (runtimes JIT, geração de IR, invalidação SMC, GDB, testes de
equivalência): **[docs/USAGE.md](docs/USAGE.md)**.

## Biblioteca nativa (C API)

Módulo opcional (`capi/`) que expõe o núcleo ARM/THUMB como biblioteca nativa
(`arm_jitter.dll`/`.so`, funções `aj_*`) via GraalVM `native-image --shared`, para
embutir o emulador em qualquer linguagem com FFI (C/C++/Rust/Zig/Python/C#/Go) sem
depender de uma JVM. Inerte no build padrão (`mvn test`/`install` não exigem GraalVM).
Detalhes e tabela de funções: **[docs/NATIVE-API.md](docs/NATIVE-API.md)**.

## Roadmap

Planos futuros (backend Truffle/GraalVM, ARMv6K/Thumb-2/ARMv7-A, AArch64, perf) estão
em [ROADMAP.md](ROADMAP.md), divididos em fases com critérios de aceite.

## Compilação e testes

Compilar e testar com **JBR 25** (a JDK do IntelliJ), não o JDK do sistema:

```bash
mvn -o test
```

Mudanças aqui exigem `mvn install` local e as suítes dos consumidores (gbaemu e
ndsemu) verdes antes do commit (invariante G5 do `tasks/README.md`).

Desenvolvimento em conjunto com um consumidor (versão `-SNAPSHOT` local, ainda não
publicada): ver [docs/USAGE.md](docs/USAGE.md#desenvolvendo-a-lib-junto-com-um-consumidor).

## Versionamento

[Versionamento Semântico](https://semver.org). O que conta como **API pública** (e portanto
só quebra em uma versão MAIOR):

- Os pacotes `arch`, `core`, `core64`, `memory`, `jit`, `codegen`, `coprocessor`, `swi`,
  `debug` e `ir`/`ir64` — tipos e assinaturas públicas.
- O comportamento padrão das factories de `JitRuntimeFactory`.

**Não** é API pública (pode mudar em versão MENOR): os pacotes `*.internal`, detalhes de
emissão de bytecode, a forma exata da IR otimizada, e o módulo `arm-jitter-truffle`, que é
experimental enquanto o backend Truffle não fechar sob `native-image`.

## Como contribuir

Issues e pull requests são bem-vindos — ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Autor e contato

Feito por [Vitor Silvério Rodrigues](https://vitorsilverio.dev/) — blog/currículo com mais
detalhes sobre este e outros projetos. Contato: vitor.silverio.rodrigues@gmail.com ou uma
[issue](https://github.com/vitorsilverio/arm-jitter/issues) neste repositório.

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).
