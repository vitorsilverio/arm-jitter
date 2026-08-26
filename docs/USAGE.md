# Guia de uso

Referência detalhada da API pública. Para uma visão geral do projeto, veja o
[README](../README.md).

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
(opt-in, sem wiring nos consumidores).

Benchmarks completos, notas sobre a distribuição GraalVM e o relatório do native-image:
**[TRUFFLE-BACKEND.md](TRUFFLE-BACKEND.md)**.

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

Para invalidar blocos automaticamente em escrita de memória:

```java
AddressSpace memoryWithInvalidation = new InvalidationAwareAddressSpace(deviceMemory, runtime);
ArmCore core = new ArmCore(memoryWithInvalidation, SwiDispatcher.empty());
```

Para interceptar SWIs no host mantendo acesso ao número solicitado:

```java
SwiDispatcher dispatcher = SwiDispatcher.empty();
dispatcher.fallbackWithNumber((swi, state) -> {
    return state.withR0(swi);
});
```

## Desenvolvendo a lib junto com um consumidor

Desde a publicação no Maven Central (`dev.vitorsilverio:arm-jitter`, task F5), nenhum
consumidor (gbaemu, ndsemu, armbox, virtual-arm-box, n3dsemu) precisa de `mvn install` local
para compilar — a dependência resolve direto do Central.

Enquanto uma mudança da lib não está publicada, o consumidor precisa da versão local:

1. No arm-jitter: `<version>X.Y.Z-SNAPSHOT</version>` + `mvn -o install`.
2. No consumidor: apontar a dependência para `X.Y.Z-SNAPSHOT` **sem commitar**.
3. Ao publicar (tag `vX.Y.Z`, ver [PUBLICAR.md](PUBLICAR.md)), voltar os dois para a versão
   final e aí sim commitar.

Nunca commite um consumidor apontando para um `-SNAPSHOT`: o CI não resolve SNAPSHOT do
Central e o build quebra para todo mundo.
