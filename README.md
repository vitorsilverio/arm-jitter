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
- `debug`: `GdbServer` (stub GDB remote serial) e trace listener.

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

## Uso basico

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

Para skip BIOS ou restaurar snapshot de boot, configure PC e CPSR juntos para que
os bancos de registradores acompanhem o modo da CPU:

```java
core.setBankedRegister(CpuMode.SUPERVISOR, 13, 0x03007FE0);
core.setBankedRegister(CpuMode.IRQ, 13, 0x03007FA0);
core.configureExecutionState(
        0x08000000,
        CpuMode.SYSTEM,
        InstructionSet.ARM,
        false,
        true);
```

Para investigar loops de BIOS/ROM, instale um trace leve:

```java
core.setTraceListener(new ArmTraceListener() {
    @Override
    public void afterInstruction(ArmCore tracedCore, DecodedInstruction instruction) {
        System.out.printf(
                "pc=%08X raw=%08X kind=%s cpsr=%08X r0=%08X sp=%08X lr=%08X%n",
                instruction.address(),
                instruction.raw(),
                instruction.kind(),
                tracedCore.cpsr().get(),
                tracedCore.register(0),
                tracedCore.register(13),
                tracedCore.register(14));
    }
});
```

Para implementar waitstates de memória no emulador hospedeiro, sobrescreva
`accessCycles`. Esses ciclos extras são acumulados em `core.cycles()`:

```java
@Override
public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
    if ((address & 0x0E000000) == 0x08000000) {
        return type == MemoryAccessType.INSTRUCTION_FETCH ? 3 : 5;
    }
    return 0;
}
```

Para mapear `HALTCNT` ou um mecanismo equivalente:

```java
core.halt();
core.setInterruptLine(true);
```

## Gerando IR

```java
IrBlockLifter lifter = new StandardIrBlockLifter(new ArmDecoder(), new StandardIrBuilder());
IrBlock block = lifter.lift(memory, 0x08000000, 32);
```

## Runtime JIT

O pipeline é o mesmo (cache → decode → lift → otimizar → emit); o que muda é o backend:

| Backend | Factory | Comportamento | Quando usar |
|---------|---------|---------------|-------------|
| `JVM_BYTECODE` | `armThumb(...)` | Tiered: tier frio interpretado + tier quente em bytecode JVM (ASM, fallback `PER_OP`, compilação em pool de threads) | **Padrão recomendado** (default dos consumidores gbaemu/ndsemu) |
| `INTERPRETED_IR` | `interpretedArmThumb(...)` | Loop Java sobre `IrOp[]` | Debug, step-by-step, oráculo de testes |
| `TRUFFLE` | `TruffleJitRuntimeFactory.truffleArmThumb(...)` (módulo opcional `arm-jitter-truffle`) | Tiered: tier frio interpretado + tier quente em nós Truffle/AST, compilados por partial evaluation quando o host roda sob Graal/JVMCI | Blocos/traces grandes (superblocos) e `native-image` (trilha A) — **opt-in**, não é default de nenhum consumidor |
| (ambos) | `divergenceCheckingArmThumb(...)` | Executa cada bloco pelos dois backends e lança na primeira divergência | Diagnóstico de bugs de codegen com ROM real |

### Backend Truffle/GraalVM — quando escolher

Módulo opcional `arm-jitter-truffle` (core não depende de API Truffle), exposto por
`TruffleJitRuntimeFactory.truffleArmThumb(cacheEntries, hotThreshold[, architecture])`.
Único motivo real de existir: viabilizar JIT dentro de **native-image**, onde o backend
ASM não funciona (`defineClass` em runtime não é suportado). Em JVM normal o **ASM
continua a escolha certa** para o tamanho de bloco típico de jogo (poucas dezenas de
instruções) — o Truffle só vence em blocos/traces grandes, e o ponto de crossover é
JVM-dependente (medido entre ~80 e 320 instruções). gbaemu/ndsemu não usam Truffle hoje
(opt-in, sem wiring nos consumidores). A especialização de nós por `IrOp` (task A6)
destravou a compilação real de blocos ARM reais **na JVM** (JBR + Unchained: 1812
`opt done`, 0 `opt failed`); sob **native-image** o bailout de partial evaluation
persiste byte a byte (mesmo `FrameWithoutBoxing should not be materialized` da A5,
0 `opt done`) — medido de novo pós-A6 em [A7](tasks/trilha-a-truffle/a7-native-image-revalidacao.md),
que fechou como resultado misto (corretude ok, ganho de tempo falha nos dois
ambientes): o pipeline JVMCI foi resolvido, mas o pipeline SVM/Enterprise Truffle
Compiler tem causa raiz própria, ainda sem diagnóstico — fica para sessão de modelo
forte dedicada. `native-image` (perfil `native` do armbox) roda hoje só com o backend
`INTERPRETED_IR`, com PGO+`-O3` como default (task A8).

Benchmarks completos, notas sobre a distribuição GraalVM e o relatório do native-image:
**[docs/TRUFFLE-BACKEND.md](docs/TRUFFLE-BACKEND.md)**.

### Uso recomendado

```java
// Produção — bytecode JVM com constant fold + DCE + flag merge (ARMv4T por padrão)
JitRuntime runtime = JitRuntimeFactory.armThumb(1024, 3);

// Para um core ARM9E (NDS): mesmas factories com a arquitetura explícita
JitRuntime arm9 = JitRuntimeFactory.armThumb(1024, 3, ArmArchitecture.ARMV5TE);

int cycles = runtime.execute(core.programCounter(), core);
long frameSliceCycles = core.runBlocks(runtime, 256);
```

### Encadeamento de blocos

Blocos quentes podem se encadear sem voltar ao dispatch do runtime, dentro de um
orçamento de ciclos por chamada de `execute`:

```java
runtime.setChainCycleBudget(96); // 0 desliga (default)
```

O budget é sensível a timing entre CPUs no hospedeiro (handshakes de boot cross-CPU);
suba com medição e validação de boot dos jogos de referência.

### Debug / oráculo

```java
// Interpretador IR puro — útil para comparar comportamento ou depurar
JitRuntime oracle = JitRuntimeFactory.interpretedArmThumb(1024, 3);
```

### Depuração com GDB

`GdbServer` expõe o core como um stub GDB remote serial (como o do mGBA): registradores,
memória, breakpoints em PC, watchpoints de escrita, step e continue:

```java
GdbServer.listenAndServe(3333, core, memory, () -> stepOneInstruction());
// arm-none-eabi-gdb> target remote :3333
```

### Introspecção

```java
CodegenBackend backend = runtime.codegenBackend(); // JVM_BYTECODE para armThumb(...)
```

### Migração de `jvmArmThumb`

`JitRuntimeFactory.jvmArmThumb(...)` está depreciado desde a Fase 8. Substitua por `armThumb(...)`:

```java
// Antes
JitRuntime runtime = JitRuntimeFactory.jvmArmThumb(1024, 3);

// Depois — inclui otimizador GBA (constant fold + DCE + flag merge)
JitRuntime runtime = JitRuntimeFactory.armThumb(1024, 3);
```

### Política de fallback e métricas (AsmCodeEmitter)

```java
// PER_OP: ops ainda não emitidas nativamente são despachadas ao interpretado inline
// no bloco compilado, em vez de derrubar o bloco inteiro
AsmCodeEmitter emitter = new AsmCodeEmitter(
        ArmArchitecture.ARMV5TE, AsmFallbackPolicy.PER_OP, StandardIrOptimizer.gba());

// Contadores de diagnóstico
long native   = emitter.nativeBlockCount();
long fallback = emitter.fallbackBlockCount();
long perOpOps = emitter.perOpFallbackOpCount();
emitter.resetCounters();

// Tipos de IrOp e opcodes ALU emitidos nativamente
Set<Class<? extends IrOp>> supported = AsmCodeEmitter.supportedOps();
Set<IrOpCode> supportedAlu = AsmCodeEmitter.supportedAluOpcodes();
```

### Testes de equivalência entre emissores

```java
BlockEquivalenceHarness harness = new BlockEquivalenceHarness();
harness.assertEquivalent(
        new InterpretedCodeEmitter(),
        candidateEmitter,
        irBlock,
        EquivalenceTestSupport.independentPair(memory, core -> core.setRegister(0, 1)));
```

## Invalidação SMC

Para invalidar blocos automaticamente em escrita de memoria:

```java
AddressSpace memoryWithInvalidation = new InvalidationAwareAddressSpace(deviceMemory, runtime);
ArmCore core = new ArmCore(memoryWithInvalidation, SwiDispatcher.empty());
```

Para interceptar SWIs no host mantendo acesso ao numero solicitado:

```java
SwiDispatcher dispatcher = SwiDispatcher.empty();
dispatcher.fallbackWithNumber((swi, state) -> {
    return state.withR0(swi);
});
```

Para publicar no repositorio Maven local e consumir em outro emulador:

```bash
mvn install
```

Coordenadas Maven atuais:

```xml
<dependency>
    <groupId>dev.vitorsilverio</groupId>
    <artifactId>arm-jitter</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Biblioteca nativa (C API)

O módulo opcional `capi/` (task A9, PR1) expõe o núcleo ARM/THUMB como uma
biblioteca nativa (`arm_jitter.dll`/`.so`) com funções C (`aj_*`), via GraalVM
`native-image --shared` — qualquer linguagem com FFI (C/C++/Rust/Zig/Python
`ctypes`/C#/Go) pode embutir o emulador sem depender de uma JVM. O módulo é
inerte no build padrão: `mvn test`/`install` não exigem GraalVM, só o perfil
`native-lib`.

Backend hoje: `INTERPRETED_IR` (o backend ASM define classes em runtime,
incompatível com native-image). O backend Truffle fica para a task A9 PR2,
que depende de A7 fechar nos dois ambientes.

### Build

```bat
:: 1. Instala o arm-jitter (core) no repo Maven local — JBR 25 é suficiente.
set JAVA_HOME=C:\Users\user\.jdks\jbr-25.0.3
mvn -o install -DskipTests

:: 2. Build da lib nativa — precisa do ambiente MSVC carregado + JAVA_HOME=GraalVM.
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
set JAVA_HOME=E:\graalvm-jdk-25.0.3+9.1
set PATH=%JAVA_HOME%\bin;%PATH%
cd capi
mvn -Pnative-lib -DskipTests package
:: -> target\arm_jitter.dll + arm_jitter.lib + arm_jitter.h + graal_isolate.h
```

Smoke test em C (cria isolate, roda um bloco ARM real, testa callback MMIO,
save/load state e o caminho de erro sem exceção atravessando a fronteira):

```powershell
capi\build-and-run-smoke.ps1
```

### Tabela de funções (API v1)

Toda função recebe `graal_isolatethread_t*` como primeiro parâmetro (gerado de
graça pelo header `graal_isolate.h`). Handles são opacos (`long long`, nunca um
ponteiro Java); nenhuma exceção Java atravessa a fronteira — falhas viram
código de erro negativo + `aj_last_error`.

| Função | Descrição |
|---|---|
| `aj_create(t, architectureId, backendId)` | Cria um core. `architectureId`: 0=ARMV4T, 1=ARMV5TE, 2=ARMV6K, 3=ARMV6K_THUMB2, 4=ARMV7A. `backendId`: 0=INTERPRETED (único suportado no PR1), 1=TRUFFLE (PR2). Devolve handle (`>=0`) ou `-1`. |
| `aj_destroy(t, handle)` | Libera o core e o buffer nativo de erro do handle. |
| `aj_map_ram(t, handle, base, size)` | Mapeia `size` bytes de RAM zerada em `base` (ambos múltiplos de 4KiB — `PagedAddressSpace`, task C3). |
| `aj_write(t, handle, addr, src, len)` / `aj_read(t, handle, addr, dst, len)` | Acesso direto à memória do core, byte a byte. |
| `aj_set_mmio_callbacks(t, handle, readFn, writeFn, userData)` | Instala os callbacks C chamados para todo endereço FORA de qualquer região de `aj_map_ram` (o barramento aberto do handle). `readFn`/`writeFn` são invocados NA THREAD que chamou `aj_run_cycles`/`aj_read`/`aj_write` — sem concorrência. |
| `aj_get_register`/`aj_set_register(t, handle, index, value)` | R0–R15. |
| `aj_get_cpsr`/`aj_set_cpsr(t, handle, value)` | CPSR bruto. |
| `aj_set_pc(t, handle, pc, thumb)` | PC + conjunto de instruções (ARM/THUMB). |
| `aj_run_cycles(t, handle, cycles)` | Executa blocos até acumular pelo menos `cycles` ciclos internos; devolve os ciclos realmente consumidos (pode passar do pedido — blocos não são cortados no meio) ou `-1` em erro. Não pode ser chamado de novo, no mesmo handle, de dentro de um callback MMIO disparado pela mesma chamada (erro claro, não deadlock). |
| `aj_set_irq_line(t, handle, asserted)` | Linha de IRQ do core. |
| `aj_save_state`/`aj_load_state(t, handle, buf, cap/len)` | Serialização via `ArmCore#saveState`/`loadState`. |
| `aj_last_error(t, handle)` | Última mensagem de erro deste handle (ou do buffer global, se o handle já não existe — ex. `aj_create` falho). |

Fora de escopo do v1 (documentado, entram por demanda): múltiplos cores
acoplados, GDB stub, dispatcher de SWI customizado.

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

## Desenvolvendo a lib junto com um consumidor

Desde a publicação no Maven Central (`dev.vitorsilverio:arm-jitter:1.0.0`, task F5), nenhum
consumidor (gbaemu, ndsemu, armbox, virtual-arm-box, n3dsemu) precisa de `mvn install` local
para compilar — a dependência resolve direto do Central.

Enquanto uma mudança da lib não está publicada, o consumidor precisa da versão local:

1. No arm-jitter: `<version>1.0.1-SNAPSHOT</version>` + `mvn -o install`.
2. No consumidor: apontar a dependência para `1.0.1-SNAPSHOT` **sem commitar**.
3. Ao publicar (tag `v1.0.1`, ver `docs/PUBLICAR.md`), voltar os dois para a versão final e
   aí sim commitar.

Nunca commite um consumidor apontando para um `-SNAPSHOT`: o CI não resolve SNAPSHOT do
Central e o build quebra para todo mundo.

## Versionamento

[Versionamento Semântico](https://semver.org). O que conta como **API pública** (e portanto
só quebra em uma versão MAIOR):

- Os pacotes `arch`, `core`, `core64`, `memory`, `jit`, `codegen`, `coprocessor`, `swi`,
  `debug` e `ir`/`ir64` — tipos e assinaturas públicas.
- O comportamento padrão das factories de `JitRuntimeFactory`.

**Não** é API pública (pode mudar em versão MENOR): os pacotes `*.internal`, detalhes de
emissão de bytecode, a forma exata da IR otimizada, e o módulo `arm-jitter-truffle`, que é
experimental enquanto o backend Truffle não fechar sob `native-image`.

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).
